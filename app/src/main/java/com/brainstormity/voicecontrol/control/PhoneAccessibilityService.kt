package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.Hands
import com.brainstormity.voicecontrol.core.Screen
import com.brainstormity.voicecontrol.core.ScreenSize
import com.brainstormity.voicecontrol.core.SwipeDirection
import com.brainstormity.voicecontrol.core.VcLog

/**
 * STUB (skeleton). The accessibility agent replaces the stub Screen/Hands with real
 * takeScreenshot / dispatchGesture implementations. Registers itself into [Device] while connected.
 */
class PhoneAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        VcLog.i(TAG, "onServiceConnected: PhoneAccessibilityService connected")
        val stub = StubDevice(this)
        Device.screen.value = stub
        Device.hands.value = stub
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        VcLog.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        VcLog.i(TAG, "onUnbind: clearing Device")
        Device.screen.value = null
        Device.hands.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Device.screen.value = null
        Device.hands.value = null
        super.onDestroy()
    }

    private class StubDevice(private val service: AccessibilityService) : Screen, Hands {
        override fun screenSize(): ScreenSize {
            val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            return ScreenSize(bounds.width(), bounds.height())
        }

        override suspend fun screenshot(): Bitmap {
            VcLog.d(TAG, "screenshot() [stub: blank bitmap]")
            val s = screenSize()
            return Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        }

        override suspend fun tap(x: Int, y: Int) = VcLog.d(TAG, "tap($x, $y) [stub]")
        override suspend fun longPress(x: Int, y: Int) = VcLog.d(TAG, "longPress($x, $y) [stub]")
        override suspend fun swipe(direction: SwipeDirection) = VcLog.d(TAG, "swipe($direction) [stub]")
        override suspend fun allApps() = VcLog.d(TAG, "allApps() [stub]")
        override suspend fun back() = VcLog.d(TAG, "back() [stub]")
        override suspend fun home() = VcLog.d(TAG, "home() [stub]")
        override suspend fun enter() = VcLog.d(TAG, "enter() [stub]")
        override suspend fun typeText(text: String) = VcLog.d(TAG, "typeText(\"$text\") [stub]")
        override suspend fun openApp(packageName: String) = VcLog.d(TAG, "openApp($packageName) [stub]")
        override suspend fun openUrl(url: String) = VcLog.d(TAG, "openUrl($url) [stub]")
        override fun installedApps(): Set<String> {
            VcLog.d(TAG, "installedApps() [stub]")
            return emptySet()
        }
    }

    companion object {
        private const val TAG = "Hands"
    }
}
