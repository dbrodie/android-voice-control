package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.Hands
import com.brainstormity.voicecontrol.core.OverlayInfo
import com.brainstormity.voicecontrol.core.Screen
import com.brainstormity.voicecontrol.core.ScreenSize
import com.brainstormity.voicecontrol.core.SwipeDirection
import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The real eyes and hands, backed by the connected [PhoneAccessibilityService].
 *
 * Coordinates: real display pixels of the default display in its current rotation. That is the same
 * space as [screenshot] (full resolution, no scaling) and as [AccessibilityService.dispatchGesture].
 */
internal class AccessibilityDevice(private val service: AccessibilityService) : Screen, Hands {

    private val capturer = ScreenCapturer(service)
    private val textEntry = TextEntry(service)
    internal val treeCapture = UiTreeCapture(service, ::screenSize) { lastWindowState }
    private val gestureMutex = Mutex()

    @Volatile private var appsCache: Set<String>? = null
    @Volatile private var appsCachedAt = 0L

    // ------------------------------------------------------------------ Screen

    /** Software ARGB_8888 bitmap at full display resolution; the caller owns (and may recycle) it. */
    override suspend fun screenshot(): Bitmap = withContext(Dispatchers.Default) { capturer.capture() }

    /** Raw accessibility tree of everything on screen (see [UiTreeCapture]); blocking IPC, so off the caller's thread. */
    override suspend fun uiTree(): UiTree? = withContext(Dispatchers.Default) { treeCapture.capture() }

    override fun screenSize(): ScreenSize {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m) // real pixels, current rotation, no insets subtracted
        return ScreenSize(m.widthPixels, m.heightPixels)
    }

    // ------------------------------------------------------------------ gestures

    override suspend fun tap(x: Int, y: Int) {
        gesture("tap($x,$y)", Path().apply { moveTo(x.toFloat(), y.toFloat()) }, TAP_MS)
    }

    override suspend fun longPress(x: Int, y: Int) {
        gesture("longPress($x,$y)", Path().apply { moveTo(x.toFloat(), y.toFloat()) }, LONG_PRESS_MS)
    }

    override suspend fun swipe(direction: SwipeDirection) {
        // Same geometry as upstream phone.py: across the middle of the screen, 300 ms.
        val (w, h) = screenSize().let { it.width.toFloat() to it.height.toFloat() }
        val (sx, sy, ex, ey) = when (direction) {
            SwipeDirection.UP -> listOf(w / 2, h * 0.70f, w / 2, h * 0.30f)
            SwipeDirection.DOWN -> listOf(w / 2, h * 0.30f, w / 2, h * 0.70f)
            SwipeDirection.LEFT -> listOf(w * 0.85f, h / 2, w * 0.15f, h / 2)
            SwipeDirection.RIGHT -> listOf(w * 0.15f, h / 2, w * 0.85f, h / 2)
        }
        line("swipe($direction)", sx, sy, ex, ey, SWIPE_MS)
    }

    override suspend fun allApps() {
        home()
        delay(ALL_APPS_HOME_SETTLE_MS) // let the launcher come to the front before swiping
        val s = screenSize()
        val x = s.width / 2f
        line("allApps swipe", x, s.height * 0.92f, x, s.height * 0.25f, SWIPE_MS)
    }

    private suspend fun line(name: String, sx: Float, sy: Float, ex: Float, ey: Float, ms: Long) {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        gesture("$name (${sx.toInt()},${sy.toInt()})->(${ex.toInt()},${ey.toInt()})", path, ms)
    }

    /** Dispatches a one-stroke gesture and suspends until the system reports it completed or cancelled. */
    private suspend fun gesture(name: String, path: Path, durationMs: Long) = gestureMutex.withLock {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val t0 = SystemClock.elapsedRealtime()
        val result = withTimeoutOrNull(durationMs + GESTURE_TIMEOUT_SLACK_MS) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume("completed")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume("cancelled")
                    }
                }, null)
                if (!dispatched && cont.isActive) cont.resume("rejected")
            }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        when (result) {
            "completed" -> VcLog.d(TAG, "$name completed in ${ms}ms")
            "cancelled" -> VcLog.w(TAG, "$name CANCELLED after ${ms}ms (another gesture/touch interrupted it)")
            "rejected" -> connectionLost("$name: dispatchGesture rejected")
            else -> VcLog.w(TAG, "$name: no completion callback within ${ms}ms; continuing")
        }
    }

    // ------------------------------------------------------------------ global actions

    override suspend fun back() = global("back", AccessibilityService.GLOBAL_ACTION_BACK)

    override suspend fun home() = global("home", AccessibilityService.GLOBAL_ACTION_HOME)

    private fun global(name: String, action: Int) {
        if (!service.performGlobalAction(action)) connectionLost("$name: performGlobalAction failed")
        VcLog.d(TAG, "$name ok")
    }

    /**
     * dispatchGesture/performGlobalAction only fail when this service instance has lost its system
     * connection (e.g. the system unbound it without onUnbind after a crash/reinstall). Unpublish it so
     * Device.isReady turns false; a re-bound instance publishes itself again in onServiceConnected.
     */
    private fun connectionLost(what: String): Nothing {
        Device.screen.compareAndSet(this, null)
        Device.hands.compareAndSet(this, null)
        val msg = "$what: accessibility service is no longer connected (turn it off and on again in " +
            "Settings > Accessibility)"
        VcLog.e(TAG, msg)
        throw IllegalStateException(msg)
    }

    // ------------------------------------------------------------------ text

    // Node lookups are blocking IPC into the target app (can take seconds while it is busy), so keep
    // them off the caller's thread even if it is Main.
    override suspend fun enter() = withContext(Dispatchers.Default) { textEntry.enter() }

    override suspend fun typeText(text: String) = withContext(Dispatchers.Default) { textEntry.typeText(text) }

    override suspend fun replaceText(text: String) =
        withContext(Dispatchers.Default) { textEntry.typeText(text, replace = true) }

    // ------------------------------------------------------------------ apps / urls

    override suspend fun openApp(packageName: String) {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("openApp: $packageName is not installed or has no launcher activity")
        // Start the app on its main screen (upstream: `am start -n <launcher activity>`), not wherever the user
        // left it: with RESET_TASK_IF_NEEDED, "Open Settings" resumed a stale Settings search page and JEV was
        // lost from step 1. CLEAR_TASK gives every task the same, predictable starting point.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        launch("openApp($packageName)", intent, packageName)
    }

    override suspend fun openUrl(url: String) {
        val uri = Uri.parse(if (url.contains("://")) url else "https://$url")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch("openUrl($uri)", intent, null)
    }

    private suspend fun launch(name: String, intent: Intent, expectPackage: String?) {
        val alreadyFront = expectPackage != null && lastWindowPackage == expectPackage
        val t0 = SystemClock.elapsedRealtime()
        try {
            service.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("$name: no activity can handle it", e)
        }
        val tStarted = SystemClock.elapsedRealtime()
        if (alreadyFront) {
            VcLog.d(TAG, "$name: already in front (startActivity ${tStarted - t0}ms)")
            return
        }
        // startActivity doesn't report background-activity-start blocks, so wait (cheaply, via the
        // window-state events the service already receives) until a new window shows up. Any package
        // counts: the task may resume on another app's activity (Settings -> its search UI, URL -> browser).
        while (SystemClock.elapsedRealtime() - tStarted < LAUNCH_CONFIRM_MS) {
            if (lastWindowAt >= t0) {
                VcLog.d(TAG, "$name: window from $lastWindowPackage after ${SystemClock.elapsedRealtime() - t0}ms " +
                    "(startActivity ${tStarted - t0}ms)")
                return
            }
            delay(50)
        }
        // Normal when the target was already showing (e.g. a URL opening as a new tab in the browser in
        // front); otherwise a very slow cold start or a blocked launch.
        VcLog.w(TAG, "$name: no new window within ${LAUNCH_CONFIRM_MS}ms (target already in front, slow " +
            "cold start, or launch blocked; last window: $lastWindowPackage)")
    }

    @Volatile private var lastWindowPackage: String? = null
    @Volatile private var lastWindowAt = 0L
    /** (package, class name) of the last window-state event from another app: the activity for [uiTree]. */
    @Volatile private var lastWindowState: Pair<String, String>? = null

    /** Called by the service for TYPE_WINDOW_STATE_CHANGED events (the only events it subscribes to). */
    fun onWindowStateChanged(packageName: CharSequence?, className: CharSequence? = null) {
        // Our own overlay (the strip appearing as a task starts) is not the launched app showing up: seen as
        // "openApp(com.android.chrome): window from com.brainstormity.voicecontrol after 235ms".
        if (packageName?.toString() == service.packageName) return
        lastWindowPackage = packageName?.toString()
        lastWindowAt = SystemClock.elapsedRealtime()
        // Only activity-like class names: dialogs/popups report widget classes (FrameLayout, ...) we'd rather not
        // show as "activity".
        val cls = className?.toString()
        if (packageName != null && cls != null && !cls.startsWith("android.widget.") && !cls.startsWith("android.view.")) {
            lastWindowState = packageName.toString() to cls
        }
    }

    /**
     * Package of the top application window (ignores our overlay, system bars and the IME). Debug aid:
     * fetching window roots is a blocking IPC into the app and can stall seconds while it is starting.
     */
    fun foregroundPackage(): String? = try {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()
    } catch (e: Exception) {
        null
    } ?: service.rootInActiveWindow?.packageName?.toString()

    /**
     * Whether our overlay is on screen right now. Android hides other apps' overlays while Settings and some
     * permission screens are in front, and hidden windows are missing from [AccessibilityService.getWindows].
     * Our touchable overlay windows (bubble, stop button) are listed there when visible, so look for a window
     * whose bounds equal one of the touchable [OverlayInfo] regions. Null if there's nothing to look for or the
     * window list is unavailable. Uses only window bounds (no node IPC).
     */
    fun overlayVisible(): Boolean? = try {
        val ours = OverlayInfo.regions.value.filter { it.touchable }.map { it.rect }
        if (ours.isEmpty()) null else {
            val b = Rect()
            service.windows.any { w ->
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) false
                else { w.getBoundsInScreen(b); b in ours }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Debug aid: one line per window (type, layer, package, active/focused). */
    fun describeWindows(): String = try {
        service.windows.joinToString(" | ") { w ->
            "type=${w.type} layer=${w.layer} pkg=${w.root?.packageName} title=${w.title}" +
                (if (w.isActive) " ACTIVE" else "") + (if (w.isFocused) " FOCUSED" else "")
        } + " || activeRoot=${service.rootInActiveWindow?.packageName}"
    } catch (e: Exception) {
        "windows failed: $e"
    }

    override fun installedApps(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        appsCache?.let { if (now - appsCachedAt < APPS_CACHE_MS) return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = service.packageManager.queryIntentActivities(intent, 0)
            .mapTo(HashSet()) { it.activityInfo.packageName }
        appsCache = apps
        appsCachedAt = now
        VcLog.d(TAG, "installedApps: ${apps.size} launchable packages (${SystemClock.elapsedRealtime() - now}ms)")
        return apps
    }

    companion object {
        private const val TAG = "Hands"
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 700L
        const val SWIPE_MS = 300L
        private const val ALL_APPS_HOME_SETTLE_MS = 600L
        private const val GESTURE_TIMEOUT_SLACK_MS = 2000L
        private const val LAUNCH_CONFIRM_MS = 2000L
        private const val APPS_CACHE_MS = 60_000L
    }
}
