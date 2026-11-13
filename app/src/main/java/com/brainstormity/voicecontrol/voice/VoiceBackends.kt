package com.brainstormity.voicecontrol.voice

import android.content.Context
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth
import com.brainstormity.voicecontrol.voice.live.CodexLiveTransport
import com.brainstormity.voicecontrol.voice.live.LiveAuth
import com.brainstormity.voicecontrol.voice.live.LiveAuthProvider
import com.brainstormity.voicecontrol.voice.live.LiveCredentials
import com.brainstormity.voicecontrol.voice.live.LiveException
import com.brainstormity.voicecontrol.voice.live.LivePrompt
import com.brainstormity.voicecontrol.voice.live.LiveProtocol
import com.brainstormity.voicecontrol.voice.live.LiveSession
import com.brainstormity.voicecontrol.voice.live.LiveSessionConfig
import com.brainstormity.voicecontrol.voice.live.OpenAiLiveWsTransport

/**
 * Chooses and wires the GPT-Live transport. Call [install] once from Application.onCreate.
 *
 * Backends (see AppSettings.voiceBackend): "chatgpt" = Codex GPT-Live route with Sign in with ChatGPT (WebRTC,
 * [CodexLiveTransport]), "openai_key" = official GPT-Live API over WebSocket with an OpenAI API key
 * ([OpenAiLiveWsTransport]), "auto" = ChatGPT if signed in, otherwise the API key if one is set.
 * The choice is made per call in [createSession] (VoiceController's default session factory), so changing the
 * setting or signing in takes effect on the next start() without reinstalling anything.
 */
object VoiceBackends {
    private const val TAG = "VoiceBackends"
    const val NOT_CONFIGURED = "Sign in with ChatGPT or enter an OpenAI API key in settings"

    /**
     * Debug builds only: reports ChatGPT credentials that live outside ChatGptAuth (the pushed Codex auth.json read
     * by debug/CodexFileAuthProvider), so "auto" still picks the ChatGPT route with them. Null in release builds.
     */
    @Volatile var debugChatGptAvailable: ((Context) -> Boolean)? = null

    @Volatile private var installed = false

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        if (!installed) {
            installed = true
            // Keep whatever provider was there before (debug: the Codex auth.json reader) as a fallback.
            val fallback = LiveAuth.provider
            LiveAuth.provider = LiveAuthProvider {
                ChatGptAuth.credentials(app)?.let { LiveCredentials(it.accessToken, it.accountId) }
                    ?: fallback?.credentials()
            }
        }
        VoiceController.sessionFactory = ::createSession
        VcLog.d(TAG, "installed (backend setting=${AppSettings.voiceBackend(app)}, resolves to '${resolve(app)}')")
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
        return resolve(AppSettings.voiceBackend(app), chatGptAvailable(app), AppSettings.openAiApiKey(app).isNotBlank())
    }

    /** Pure decision table behind [resolve] (unit-tested). */
    fun resolve(setting: String, signedIn: Boolean, hasKey: Boolean): String = when (setting) {
        AppSettings.BACKEND_CHATGPT -> if (signedIn) AppSettings.BACKEND_CHATGPT else ""
        AppSettings.BACKEND_OPENAI_KEY -> if (hasKey) AppSettings.BACKEND_OPENAI_KEY else ""
        else -> if (signedIn) AppSettings.BACKEND_CHATGPT else if (hasKey) AppSettings.BACKEND_OPENAI_KEY else ""
    }

    /** Signed in with ChatGPT (or, in debug builds, a pushed Codex auth.json is readable). */
    fun chatGptAvailable(ctx: Context): Boolean =
        ChatGptAuth.isSignedIn(ctx) || debugChatGptAvailable?.invoke(ctx.applicationContext) == true

    /**
     * VoiceController's session factory: a transport for the resolved backend.
     * @throws LiveException with [NOT_CONFIGURED] when no backend is usable.
     */
    fun createSession(ctx: Context, auth: LiveAuthProvider): LiveSession {
        val app = ctx.applicationContext
        return when (val backend = resolve(app)) {
            AppSettings.BACKEND_CHATGPT -> {
                VcLog.i(TAG, "backend: chatgpt (Codex GPT-Live, WebRTC)")
                CodexLiveTransport(app, auth, LiveSessionConfig(instructions = LivePrompt.INSTRUCTIONS))
            }
            AppSettings.BACKEND_OPENAI_KEY -> {
                val model = LiveProtocol.publicModel(AppSettings.realtimeModel(app))
                VcLog.i(TAG, "backend: openai_key (public GPT-Live API, WebSocket, model=$model)")
                OpenAiLiveWsTransport(
                    app,
                    AppSettings.openAiApiKey(app),
                    LiveSessionConfig(
                        instructions = LivePrompt.INSTRUCTIONS,
                        voice = LiveProtocol.PUBLIC_DEFAULT_VOICE,
                        model = model,
                    ),
                )
            }
            else -> {
                VcLog.w(TAG, "backend: none (setting=${AppSettings.voiceBackend(app)}, resolved='$backend')")
                throw LiveException(NOT_CONFIGURED)
            }
        }
    }
}
