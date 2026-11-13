package com.brainstormity.voicecontrol.voice.live

/** ChatGPT credentials for the Codex GPT-Live route. Never log either field. */
data class LiveCredentials(val accessToken: String, val accountId: String) {
    override fun toString() = "LiveCredentials([redacted])"
}

/**
 * Where the live session gets its ChatGPT sign-in. `voice/auth/ChatGptAuth` (T4a) owns sign-in and refresh;
 * this package only codes against this tiny interface so the two can be built independently.
 *
 * Integration (one line, e.g. in OverlayService.onCreate or an Application class):
 * ```
 * LiveAuth.provider = LiveAuthProvider {
 *     ChatGptAuth.credentials(appContext)?.let { LiveCredentials(it.accessToken, it.accountId) }
 * }
 * ```
 * Debug builds install a file-based provider (`debug/CodexFileAuthProvider`) when nothing else is set.
 */
fun interface LiveAuthProvider {
    /** Fresh credentials, or null when the user is not signed in. May refresh (suspend) as needed. */
    suspend fun credentials(): LiveCredentials?
}

object LiveAuth {
    @Volatile
    var provider: LiveAuthProvider? = null
}
