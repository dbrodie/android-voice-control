package com.brainstormity.voicecontrol.voice

import android.content.Context
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth
import com.brainstormity.voicecontrol.voice.live.LiveAuth
import com.brainstormity.voicecontrol.voice.live.LiveAuthProvider
import com.brainstormity.voicecontrol.voice.live.LiveCredentials

/**
 * Chooses and wires the GPT-Live transport. Call [install] once from Application.onCreate.
 *
 * Backends (see AppSettings.voiceBackend): "chatgpt" = Codex GPT-Live route with Sign in with ChatGPT (WebRTC),
 * "openai_key" = official GPT-Live API over WebSocket with an OpenAI API key, "auto" = ChatGPT if signed in,
 * otherwise the API key if one is set.
 */
object VoiceBackends {
    private const val TAG = "VoiceBackends"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        LiveAuth.provider = LiveAuthProvider {
            ChatGptAuth.credentials(app)?.let { LiveCredentials(it.accessToken, it.accountId) }
        }
        // T4c replaces VoiceController.sessionFactory here with a chooser based on AppSettings.voiceBackend.
        VcLog.d(TAG, "installed (backend setting=${AppSettings.voiceBackend(app)})")
    }

    /** Human-readable description of what [resolve] would pick right now, for the settings screen. */
    fun describe(ctx: Context): String {
        val app = ctx.applicationContext
        return when (resolve(app)) {
            AppSettings.BACKEND_CHATGPT -> "ChatGPT sign-in (GPT-Live via Codex)"
            AppSettings.BACKEND_OPENAI_KEY -> "OpenAI API key (official GPT-Live API)"
            else -> "Not configured: sign in with ChatGPT or enter an OpenAI API key"
        }
    }

    /** The concrete backend id for the current settings, or "" when neither is usable. */
    fun resolve(ctx: Context): String {
        val app = ctx.applicationContext
        val signedIn = ChatGptAuth.isSignedIn(app)
        val hasKey = AppSettings.openAiApiKey(app).isNotBlank()
        return when (AppSettings.voiceBackend(app)) {
            AppSettings.BACKEND_CHATGPT -> if (signedIn) AppSettings.BACKEND_CHATGPT else ""
            AppSettings.BACKEND_OPENAI_KEY -> if (hasKey) AppSettings.BACKEND_OPENAI_KEY else ""
            else -> if (signedIn) AppSettings.BACKEND_CHATGPT else if (hasKey) AppSettings.BACKEND_OPENAI_KEY else ""
        }
    }
}
