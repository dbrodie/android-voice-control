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
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.VoiceBackends
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth
import java.io.File

/**
 * Debug-only adb hooks for the voice backend chooser (log tag VC/Debug; keys are never logged):
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.BACKEND_STATUS
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.BACKEND_SET --es backend auto|chatgpt|openai_key
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.BACKEND_SET --es key sk-invalid      (store an API key)
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.BACKEND_SET --ez clear_key true      (back to BuildConfig)
 * Same manifest + runtime-registered pattern as DebugReceiver.
 */
class BackendDebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {
    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        val app = context.applicationContext
        when (intent.action) {
            BACKEND_SET -> {
                intent.getStringExtra("backend")?.let { AppSettings.setVoiceBackend(app, it) }
                intent.getStringExtra("key")?.let { AppSettings.setOpenAiApiKey(app, it) }
                if (intent.getBooleanExtra("clear_key", false)) AppSettings.setOpenAiApiKey(app, "")
                status(app, "BACKEND_SET")
            }
            BACKEND_STATUS -> status(app, "BACKEND_STATUS")
        }
    }

    private fun status(app: Context, label: String) {
        val key = AppSettings.openAiApiKey(app)
        VcLog.i(
            TAG, "$label setting=${AppSettings.voiceBackend(app)} resolve='${VoiceBackends.resolve(app)}' " +
                "describe=\"${VoiceBackends.describe(app)}\" chatgptSignedIn=${ChatGptAuth.isSignedIn(app)} " +
                "debugCodexFile=${codexFileReadable(app)} openAiKey=${if (key.isBlank()) "none" else "set (${key.length} chars)"} " +
                "model=${AppSettings.realtimeModel(app)}",
        )
    }

    companion object {
        private const val TAG = "Debug"
        private const val P = "com.brainstormity.voicecontrol."
        const val BACKEND_STATUS = P + "BACKEND_STATUS"
        const val BACKEND_SET = P + "BACKEND_SET"
        val ACTIONS = listOf(BACKEND_STATUS, BACKEND_SET)

        /** The files debug/CodexFileAuthProvider reads. */
        fun codexFileReadable(ctx: Context): Boolean =
            File(ctx.filesDir, "codex-auth.json").canRead() || File("/data/local/tmp/codex-auth.json").canRead()
    }
}

/** Debug-only, created at process start: runtime BackendDebugReceiver + lets "auto" see the pushed Codex auth.json. */
class BackendDebugInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val filter = IntentFilter().apply { BackendDebugReceiver.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, BackendDebugReceiver(runtimeRegistered = true), filter, ContextCompat.RECEIVER_EXPORTED)
        VoiceBackends.debugChatGptAvailable = BackendDebugReceiver::codexFileReadable
        VcLog.d("Debug", "runtime BackendDebugReceiver registered")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
