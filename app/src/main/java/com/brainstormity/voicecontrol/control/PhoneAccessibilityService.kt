package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.VcLog

/**
 * The phone's eyes and hands. While the user has this accessibility service enabled, it publishes an
 * [AccessibilityDevice] (Screen + Hands) into [Device]; it clears them again when unbound/destroyed.
 *
 * Events: the config subscribes only to the rare window-state events, used just to note which package
 * last showed a window (confirms openApp launches without blocking IPC).
 */
class PhoneAccessibilityService : AccessibilityService() {

    private var device: AccessibilityDevice? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val d = AccessibilityDevice(this)
        device = d
        Device.screen.value = d
        Device.hands.value = d
        val size = d.screenSize()
        val wm = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        VcLog.i(
            TAG, "onServiceConnected: PhoneAccessibilityService connected, screen ${size.width}x${size.height}" +
                " (window max ${wm.width()}x${wm.height()})"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Config subscribes only to TYPE_WINDOW_STATE_CHANGED; used to confirm app launches cheaply.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            device?.onWindowStateChanged(event.packageName)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        VcLog.i(TAG, "onUnbind: clearing Device")
        clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }

    private fun clear() {
        val d = device ?: return
        // Only clear what we registered (a re-bound instance may already have replaced it).
        Device.screen.compareAndSet(d, null)
        Device.hands.compareAndSet(d, null)
        device = null
    }

    companion object {
        private const val TAG = "Hands"
    }
}
