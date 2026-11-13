package com.brainstormity.voicecontrol.voice.auth

import android.util.Base64
import org.json.JSONObject

/**
 * Unverified JWT payload reading (we only need our own tokens' claims; the server verifies signatures).
 * Mirrors codex-rs/login/src/token_data.rs (`decode_jwt_payload`, `parse_chatgpt_jwt_claims`).
 */
internal object Jwt {
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    fun payload(jwt: String?): JSONObject? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split('.')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        return try {
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** `exp` claim in epoch milliseconds, or null. */
    fun expiresAtMs(jwt: String?): Long? =
        payload(jwt)?.optLong("exp", 0L)?.takeIf { it > 0 }?.times(1000)

    private fun authClaim(jwt: String?): JSONObject? = payload(jwt)?.optJSONObject(AUTH_CLAIM)

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    /** `https://api.openai.com/auth`.chatgpt_account_id (falls back to a top-level claim, like auth.ts). */
    fun accountId(jwt: String?): String? =
        authClaim(jwt)?.str("chatgpt_account_id") ?: payload(jwt)?.str("chatgpt_account_id")

    fun planType(jwt: String?): String? = authClaim(jwt)?.str("chatgpt_plan_type")

    /** `email`, else `https://api.openai.com/profile`.email. */
    fun email(jwt: String?): String? {
        val p = payload(jwt) ?: return null
        return p.str("email") ?: p.optJSONObject(PROFILE_CLAIM)?.str("email")
    }
}
