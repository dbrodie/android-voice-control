package com.brainstormity.voicecontrol.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.control.AccessibilityDevice
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.SwipeDirection
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Debug-only adb hooks to exercise each Hands/Screen method without the agent loop (logs to VC/HandsTest):
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.HANDS --es op tap --ei x 540 --ei y 1200
 *   ... --es op longpress --ei x 540 --ei y 1200
 *   ... --es op swipe --es dir up|down|left|right
 *   ... --es op type --es text "'hello world'"
 *   ... --es op screenshot [--ei n 5]      (saves files/hands_test.png, logs size + ms per shot)
 *   ... --es op openapp --es pkg com.android.settings
 *   ... --es op url --es url "'https://example.com'"
 *   ... --es op back|home|enter|allapps|apps|size|fg|windows
 *   add --ei delay 15000 to run the op 15 s after the broadcast (broadcasts grant a temporary BAL token)
 * Ops run sequentially (one at a time) on a background coroutine. Same runtime/manifest split as
 * [DebugReceiver]: plain `-a` broadcasts reach the runtime copy while the process is alive; add
 * `-p com.brainstormity.voicecontrol` to hit the manifest copy.
 */
class HandsTestReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {

    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        if (intent.action != ACTION) return
        val op = intent.getStringExtra("op")?.lowercase()
        if (op == null) {
            VcLog.w(TAG, "HANDS broadcast without --es op ...")
            return
        }
        val app = context.applicationContext
        // --ei delay <ms>: run the op after the broadcast has finished, so the broadcast's temporary
        // background-activity-start allowance doesn't mask BAL restrictions (tests openapp/url honestly).
        val delayMs = intent.getIntExtra("delay", 0).toLong()
        val pending = if (delayMs > 0) null else goAsync()
        scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                lock.withLock { run(app, op, intent) }
            } catch (e: Throwable) {
                VcLog.e(TAG, "op=$op FAILED: ${e.message}", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun run(ctx: Context, op: String, intent: Intent) {
        val hands = Device.hands.value ?: error("Device.hands is null (accessibility service not connected)")
        val screen = Device.screen.value ?: error("Device.screen is null (accessibility service not connected)")
        val t0 = SystemClock.elapsedRealtime()
        val detail: String = when (op) {
            "tap" -> intent.xy().let { (x, y) -> hands.tap(x, y); "($x,$y)" }
            "longpress" -> intent.xy().let { (x, y) -> hands.longPress(x, y); "($x,$y)" }
            "swipe" -> {
                val dir = SwipeDirection.valueOf((intent.getStringExtra("dir") ?: "up").uppercase())
                hands.swipe(dir); dir.name
            }
            "type" -> {
                val text = intent.getStringExtra("text") ?: error("type needs --es text \"'...'\"")
                hands.typeText(text); "\"$text\""
            }
            "screenshot" -> {
                val n = intent.getIntExtra("n", 1).coerceIn(1, 50)
                val times = mutableListOf<Long>()
                var last: Bitmap? = null
                repeat(n) {
                    val s = SystemClock.elapsedRealtime()
                    last?.recycle()
                    last = screen.screenshot()
                    times += SystemClock.elapsedRealtime() - s
                }
                val bmp = last!!
                val file = File(ctx.filesDir, "hands_test.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val size = screen.screenSize()
                "n=$n ${bmp.width}x${bmp.height} (screenSize ${size.width}x${size.height}, " +
                    "match=${bmp.width == size.width && bmp.height == size.height}) ms=$times " +
                    "avg=${times.average().toInt()}ms -> ${file.absolutePath}"
            }
            "openapp" -> {
                val pkg = intent.getStringExtra("pkg") ?: error("openapp needs --es pkg <package>")
                hands.openApp(pkg); pkg
            }
            "url" -> {
                val url = intent.getStringExtra("url") ?: error("url needs --es url <url>")
                hands.openUrl(url); url
            }
            "back" -> { hands.back(); "" }
            "home" -> { hands.home(); "" }
            "enter" -> { hands.enter(); "" }
            "allapps" -> { hands.allApps(); "" }
            "apps" -> {
                val apps = hands.installedApps().sorted()
                "${apps.size} apps: settings=${"com.android.settings" in apps} chrome=${"com.android.chrome" in apps} " +
                    apps.joinToString(",")
            }
            "size" -> screen.screenSize().toString()
            "fg" -> "front=" + ((hands as? AccessibilityDevice)?.foregroundPackage() ?: "?")
            "windows" -> (hands as? AccessibilityDevice)?.describeWindows() ?: "?"
            else -> error("unknown op '$op'")
        }
        VcLog.i(TAG, "op=$op $detail OK in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun Intent.xy(): Pair<Int, Int> {
        require(hasExtra("x") && hasExtra("y")) { "needs --ei x <px> --ei y <px>" }
        return getIntExtra("x", 0) to getIntExtra("y", 0)
    }

    companion object {
        private const val TAG = "HandsTest"
        const val ACTION = "com.brainstormity.voicecontrol.HANDS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val lock = Mutex()
    }
}

/** Debug-only: registers the runtime HandsTestReceiver at process start (implicit broadcasts). */
class HandsTestReceiverInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        ContextCompat.registerReceiver(
            ctx, HandsTestReceiver(runtimeRegistered = true), IntentFilter(HandsTestReceiver.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        VcLog.d("HandsTest", "runtime HandsTestReceiver registered")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
