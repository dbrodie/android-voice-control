package com.brainstormity.voicecontrol.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.agent.laya.LayaCandidates
import com.brainstormity.voicecontrol.control.AccessibilityDevice
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.UiTreeJson
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only: dump the current accessibility tree (logs to VC/Tree).
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.DUMP_TREE [--es name settings]
 * Writes files/trees/<yyyyMMdd-HHmmss>[-name].json (UiTree JSON, see core/UiTreeJson) and logs the path, node /
 * window / candidate counts, capture ms and every Laya-Android candidate line. Pull with
 *   adb exec-out run-as com.brainstormity.voicecontrol cat files/trees/<file>.json > tree.json
 * Same runtime/manifest split as [DebugReceiver].
 */
class TreeDebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {

    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val name = intent.getStringExtra("name")?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val pending = goAsync()
        scope.launch {
            try {
                dump(app, name)
            } catch (e: Throwable) {
                VcLog.e(TAG, "DUMP_TREE failed: $e", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dump(ctx: Context, name: String?) {
        val screen = Device.screen.value ?: error("Device.screen is null (accessibility service not connected)")
        val t0 = SystemClock.elapsedRealtime()
        val tree = screen.uiTree() ?: error("uiTree() returned null")
        val captureMs = SystemClock.elapsedRealtime() - t0
        val stats = (screen as? AccessibilityDevice)?.treeCapture?.lastStats ?: ""
        val dir = File(ctx.filesDir, "trees").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(tree.takenAt))
        val file = File(dir, stamp + (name?.let { "-$it" } ?: "") + ".json")
        file.writeText(UiTreeJson.encode(tree))
        val t1 = SystemClock.elapsedRealtime()
        val cands = LayaCandidates.build(tree)
        val candMs = SystemClock.elapsedRealtime() - t1
        VcLog.i(
            TAG, "DUMP_TREE ${file.absolutePath} package=${tree.packageName} activity=${tree.activity} " +
                "windows=${tree.windows.size} nodes=${tree.nodes.size} keyboard=${tree.keyboardShown} " +
                "captureMs=$captureMs candidates=${cands.size} candidateMs=$candMs [$stats]"
        )
        tree.windows.forEach { VcLog.d(TAG, "  window ${it.id} ${it.type} layer=${it.layer} active=${it.active} bounds=${it.bounds.toList()}") }
        cands.forEach { VcLog.i(TAG, "  ${it.line}   @${it.bounds.toList()}") }
    }

    companion object {
        private const val TAG = "Tree"
        const val ACTION = "com.brainstormity.voicecontrol.DUMP_TREE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Debug-only, created at process start: registers a runtime TreeDebugReceiver so plain `-a` broadcasts arrive. */
class TreeDebugInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        ContextCompat.registerReceiver(
            ctx, TreeDebugReceiver(runtimeRegistered = true), IntentFilter(TreeDebugReceiver.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        VcLog.d("Debug", "runtime TreeDebugReceiver registered")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
