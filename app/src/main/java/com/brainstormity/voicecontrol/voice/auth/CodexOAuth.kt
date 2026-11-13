package com.brainstormity.voicecontrol.voice.auth

import android.util.Base64
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The Codex CLI's "Sign in with ChatGPT" OAuth client, verified against openai/codex
 * (codex-rs/login, commit c899e21c). See voice/auth/README.md for the source line references.
 */
internal object CodexOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "$ISSUER/oauth/authorize"
    const val TOKEN_URL = "$ISSUER/oauth/token"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    const val ORIGINATOR = "codex_cli_rs"
    const val DEFAULT_PORT = 1455
    /** Codex falls back to this port (also on the redirect-URI allow-list) when 1455 is busy. */
    const val FALLBACK_PORT = 1457
    const val CALLBACK_PATH = "/auth/callback"

    fun redirectUri(port: Int) = "http://127.0.0.1:$port$CALLBACK_PATH"

    class Pkce(val verifier: String, val challenge: String)

    private val random = SecureRandom()

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** 64 random bytes -> base64url verifier; challenge = base64url(sha256(verifier)) (S256). */
    fun generatePkce(): Pkce {
        val bytes = ByteArray(64).also(random::nextBytes)
        val verifier = b64url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, b64url(digest))
    }

    /** 32 random bytes, base64url. */
    fun generateState(): String = b64url(ByteArray(32).also(random::nextBytes))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8") // form encoding, like Rust's url::form_urlencoded

    /** Same parameters and order as codex-rs `build_authorize_url` / `build_authorization_url`. */
    fun authorizeUrl(redirectUri: String, pkce: Pkce, state: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "scope" to SCOPE,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ORIGINATOR,
        )
        return AUTHORIZE_URL + "?" + params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    class TokenResponse(val idToken: String?, val accessToken: String?, val refreshToken: String?)

    /** Non-2xx from the token endpoint. [errorCode] is e.g. invalid_grant / refresh_token_reused. */
    class TokenEndpointException(val status: Int, val errorCode: String?, message: String) : IOException(message) {
        /** Codex treats these as "sign in again" (auth/manager.rs `request_chatgpt_token_refresh`). */
        val isPermanent: Boolean
            get() = status == 401 ||
                (status == 400 && errorCode.equals("invalid_grant", ignoreCase = true)) ||
                errorCode?.lowercase() in PERMANENT_CODES
    }

    private val PERMANENT_CODES = setOf("refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated")

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Authorization-code grant: form-encoded POST (codex-rs oauth/client.rs `exchange_code`, TokenEncoding::Form). Blocking. */
    fun exchangeCode(code: String, redirectUri: String, pkce: Pkce): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", pkce.verifier)
            .build()
        return post(Request.Builder().url(TOKEN_URL).post(body).build(), secrets = listOf(code, pkce.verifier))
    }

    /**
     * Refresh grant: JSON POST (codex-rs auth/manager.rs `request_chatgpt_token_refresh` uses TokenEncoding::Json)
     * with grant_type, client_id, refresh_token, refresh_reason. Blocking.
     * [reason] is one of proactive_expiry | scheduled | unauthorized_recovery | forced.
     */
    fun refresh(refreshToken: String, reason: String): TokenResponse {
        val json = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_reason", reason)
            .put("refresh_token", refreshToken)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return post(Request.Builder().url(TOKEN_URL).post(body).build(), secrets = listOf(refreshToken))
    }

    private fun post(request: Request, secrets: List<String>): TokenResponse {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val (code, desc) = parseError(text)
                var safe = (desc ?: text).take(200)
                secrets.forEach { if (it.isNotEmpty()) safe = safe.replace(it, "[redacted]") }
                throw TokenEndpointException(
                    resp.code, code,
                    "token endpoint HTTP ${resp.code}${code?.let { " $it" } ?: ""}: $safe",
                )
            }
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IOException("token endpoint returned non-JSON (HTTP ${resp.code})")
            }
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotBlank() } else null
            return TokenResponse(s("id_token"), s("access_token"), s("refresh_token"))
        }
    }

    /** Accepts `{"error":"x","error_description":"y"}` and `{"error":{"code":"x","message":"y"}}`. */
    private fun parseError(text: String): Pair<String?, String?> = try {
        val o = JSONObject(text)
        when (val err = o.opt("error")) {
            is JSONObject -> err.optString("code").ifBlank { err.optString("type") }.ifBlank { null } to
                err.optString("message").ifBlank { null }
            is String -> err to o.optString("error_description").ifBlank { null }
            else -> o.optString("code").ifBlank { null } to o.optString("message").ifBlank { null }
        }
    } catch (_: Exception) {
        null to null
    }
}
