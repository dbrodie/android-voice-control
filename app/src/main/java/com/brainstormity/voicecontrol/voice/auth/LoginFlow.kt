package com.brainstormity.voicecontrol.voice.auth

import java.util.Locale

/**
 * Progress of one browser sign-in, published by [ChatGptAuth.login] and rendered by [LoginActivity].
 * Separate from [AuthState] (which says whether tokens are stored): a failed or cancelled attempt never
 * touches an existing sign-in. [id] identifies the attempt, so the screen knows whether it already opened the
 * browser for it.
 */
sealed class LoginState {
    object Idle : LoginState() { override fun toString() = "Idle" }
    /** Binding the loopback port (can take ~2 s when 1455 is busy). */
    data class Starting(val id: Int) : LoginState()
    /** Listening on 127.0.0.1:[port]; the browser shows OpenAI's login page. [authorizeUrl] contains no secrets. */
    data class Waiting(val id: Int, val port: Int, val authorizeUrl: String, val deadlineMs: Long) : LoginState() {
        override fun toString() = "Waiting(id=$id, port=$port, deadlineMs=$deadlineMs)"
    }
    /** The browser came back with a code; exchanging it at auth.openai.com/oauth/token. */
    data class Exchanging(val id: Int) : LoginState()
    data class Succeeded(val id: Int, val email: String?, val plan: String?) : LoginState()
    data class Failed(val id: Int, val reason: LoginFailure, val detail: String) : LoginState()

    val isInProgress: Boolean get() = this is Starting || this is Waiting || this is Exchanging
    val isFinished: Boolean get() = this is Succeeded || this is Failed
}

/** Why a sign-in attempt ended without tokens. [title] is the headline the login screen shows. */
enum class LoginFailure(val title: String) {
    CANCELLED("Sign-in cancelled"),
    DENIED("Sign-in was declined"),
    OAUTH_ERROR("OpenAI reported a sign-in error"),
    STATE_MISMATCH("Sign-in couldn't be verified"),
    NETWORK("Can't reach OpenAI"),
    TOKEN_EXCHANGE("Token exchange failed"),
    MISSING_ACCOUNT("No ChatGPT account found"),
    PORT_IN_USE("Sign-in port is busy"),
    TIMEOUT("Sign-in timed out"),
    NO_BROWSER("No browser found"),
    NO_VOICE_PLAN("Your ChatGPT plan has no voice"),
    INTERNAL("Sign-in failed"),
}

/**
 * What survives the Activity (and the process) while the browser is in front: enough to bind the same port
 * again and finish the exchange with the same PKCE verifier and state. Stored in LoginActivity's saved
 * instance state only (never logged, never written to disk by us).
 */
data class PendingLogin(
    val verifier: String,
    val state: String,
    val port: Int,
    val authorizeUrl: String,
    val deadlineMs: Long,
) {
    val redirectUri: String get() = CodexOAuth.redirectUri(port)
    override fun toString() = "PendingLogin(port=$port, deadlineMs=$deadlineMs)"
}

/** Pure decision logic of the sign-in flow (unit-tested in LoginFlowTest). */
object LoginFlow {
    const val TIMEOUT_MS = 5 * 60_000L

    data class Problem(val reason: LoginFailure, val detail: String)

    /**
     * Validates the loopback callback's query parameters, in the same order as codex-rs server.rs:
     * state first (a forged or stale callback must not be able to report anything), then `error=`, then `code`.
     * Returns null when the code can be exchanged.
     */
    fun checkCallback(params: Map<String, String>, expectedState: String): Problem? {
        val error = params["error"]
        val desc = params["error_description"]?.takeIf { it.isNotBlank() }
        return when {
            params["state"] != expectedState -> Problem(
                LoginFailure.STATE_MISMATCH,
                "The browser answered for a different sign-in attempt (state mismatch), for example from an old " +
                    "sign-in page. Nothing was changed. Please try again.",
            )
            error == "access_denied" -> Problem(
                LoginFailure.DENIED,
                "OpenAI says access was denied${desc?.let { ": $it" } ?: ""}. Try again and approve the request.",
            )
            error != null -> Problem(
                LoginFailure.OAUTH_ERROR,
                "OpenAI returned \"$error\"${desc?.let { ": $it" } ?: ""}. Please try again.",
            )
            params["code"].isNullOrEmpty() -> Problem(
                LoginFailure.OAUTH_ERROR,
                "The browser came back without an authorization code. Please try again.",
            )
            else -> null
        }
    }

    /** ChatGPT plans whose accounts can't use GPT-Live through the Codex backend. Unknown plans are allowed. */
    private val NO_VOICE_PLANS = setOf("free", "guest")

    fun planHasVoice(plan: String?): Boolean = plan == null || plan.lowercase(Locale.US) !in NO_VOICE_PLANS

    /** "pro" -> "ChatGPT Pro", "prolite" -> "ChatGPT Pro Lite", unknown -> "ChatGPT <Capitalized>". */
    fun planDisplayName(plan: String?): String? {
        if (plan.isNullOrBlank()) return null
        val name = when (val p = plan.lowercase(Locale.US)) {
            "free" -> "Free"
            "go" -> "Go"
            "plus" -> "Plus"
            "pro" -> "Pro"
            "prolite", "pro_lite" -> "Pro Lite"
            "team" -> "Team"
            "business" -> "Business"
            "enterprise" -> "Enterprise"
            "edu" -> "Edu"
            else -> p.split('_', '-', ' ').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
        }
        return "ChatGPT $name"
    }

    fun noVoiceProblem(email: String?, plan: String?) = Problem(
        LoginFailure.NO_VOICE_PLAN,
        "${email ?: "This account"} is on ${planDisplayName(plan) ?: "a plan"}, which doesn't include GPT-Live " +
            "voice. Sign in with a ChatGPT Plus or Pro account, or use an OpenAI API key instead.",
    )

    /** Message for an exception thrown by [CodexOAuth.exchangeCode]. */
    fun exchangeProblem(e: Exception): Problem = when (e) {
        is CodexOAuth.TokenEndpointException -> Problem(
            LoginFailure.TOKEN_EXCHANGE,
            "OpenAI didn't accept the sign-in code (HTTP ${e.status}${e.errorCode?.let { " $it" } ?: ""}). " +
                "Please sign in again.",
        )
        is java.io.IOException -> Problem(
            LoginFailure.NETWORK,
            "Couldn't reach auth.openai.com to finish signing in (${e.javaClass.simpleName}" +
                "${e.message?.let { ": ${it.take(80)}" } ?: ""}). Check the internet connection and try again.",
        )
        else -> Problem(LoginFailure.INTERNAL, "Sign-in failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
    }

    fun timeoutProblem() = Problem(
        LoginFailure.TIMEOUT,
        "Nothing came back from the browser within ${TIMEOUT_MS / 60_000} minutes. Please try again.",
    )

    fun cancelledProblem() = Problem(LoginFailure.CANCELLED, "You cancelled the sign-in. Nothing was changed.")

    fun portInUseProblem(ports: String = "${CodexOAuth.DEFAULT_PORT} and ${CodexOAuth.FALLBACK_PORT}") = Problem(
        LoginFailure.PORT_IN_USE,
        "Port $ports on this phone is used by another app (another sign-in, or a Codex CLI login?). " +
            "Close it and try again.",
    )
}
