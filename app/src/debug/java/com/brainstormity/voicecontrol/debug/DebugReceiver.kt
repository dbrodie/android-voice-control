package com.brainstormity.voicecontrol.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.overlay.OverlayDemo
import com.brainstormity.voicecontrol.overlay.OverlayService
import com.brainstormity.voicecontrol.voice.VoiceController

/**
 * Debug-only adb hooks (this file lives in src/debug, so release builds don't contain it):
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.RUN_TASK --es task "Open Settings"
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.STOP
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.VOICE_START
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.VOICE_STOP
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.VOICE_TEXT --es text "open settings"
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.OVERLAY_START   (or OVERLAY_STOP)
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.OVERLAY_DEMO --es voice "'said:turn on wifi'" --es agent "'running:3:TAP:Wi-Fi toggle'"
 *     (display-only fake states for the overlay; see overlay/OverlayDemo; "off" clears)
 *
 * Two instances exist: the manifest one (gets explicit broadcasts, e.g. with `-p <pkg>`, and can cold-start
 * the process) and a runtime-registered one (gets implicit broadcasts while the process is alive, which it
 * is whenever the accessibility service is enabled). The runtime one ignores explicit intents so nothing
 * is handled twice.
 */
class DebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {

    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        val action = intent.action ?: return
        VcLog.i(TAG, "DebugReceiver got $action (${if (runtimeRegistered) "runtime" else "manifest"})")
        when (action) {
            RUN_TASK -> {
                val task = intent.getStringExtra("task")
                if (task.isNullOrBlank()) VcLog.w(TAG, "RUN_TASK without --es task \"...\"")
                else TaskController.runTask(task)
            }
            STOP -> TaskController.stop()
            VOICE_START -> VoiceController.start(context.applicationContext)
            VOICE_STOP -> VoiceController.stop()
            VOICE_TEXT -> {
                val text = intent.getStringExtra("text")
                if (text.isNullOrBlank()) VcLog.w(TAG, "VOICE_TEXT without --es text \"...\"")
                else VoiceController.sendText(text)
            }
            OVERLAY_START -> if (!OverlayService.start(context.applicationContext)) {
                VcLog.w(TAG, "OVERLAY_START refused (background FGS start); open MainActivity and tap Start overlay")
            }
            OVERLAY_STOP -> OverlayService.stop(context.applicationContext)
            OVERLAY_DEMO -> OverlayDemo.apply(intent.getStringExtra("voice"), intent.getStringExtra("agent"))
            else -> VcLog.w(TAG, "unknown action $action")
        }
    }

    companion object {
        private const val TAG = "Debug"
        private const val P = "com.brainstormity.voicecontrol."
        const val RUN_TASK = P + "RUN_TASK"
        const val STOP = P + "STOP"
        const val VOICE_START = P + "VOICE_START"
        const val VOICE_STOP = P + "VOICE_STOP"
        const val VOICE_TEXT = P + "VOICE_TEXT"
        const val OVERLAY_START = P + "OVERLAY_START"
        const val OVERLAY_STOP = P + "OVERLAY_STOP"
        const val OVERLAY_DEMO = P + "OVERLAY_DEMO"
        val ACTIONS = listOf(RUN_TASK, STOP, VOICE_START, VOICE_STOP, VOICE_TEXT, OVERLAY_START, OVERLAY_STOP, OVERLAY_DEMO)
    }
}

/** Debug-only: created automatically at process start; registers the runtime DebugReceiver. */
class DebugReceiverInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val filter = IntentFilter().apply { DebugReceiver.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, DebugReceiver(runtimeRegistered = true), filter, ContextCompat.RECEIVER_EXPORTED)
        VcLog.d("Debug", "runtime DebugReceiver registered")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
