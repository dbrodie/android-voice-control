package com.brainstormity.voicecontrol.voice.auth

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
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The Codex CLI's "Sign in with ChatGPT" OAuth client, verified against openai/codex
 * (codex-rs/login, commit c899e21c). See voice/auth/README.md for the source line references.
 *
 * [issuer] is injectable so JVM tests can point the token endpoint at a MockWebServer; production code uses
 * the default instance (`CodexOAuth()` → https://auth.openai.com). Pure JVM code (java.util.Base64), no Android.
 */
internal class CodexOAuth(
    issuer: String = ISSUER,
    private val http: OkHttpClient = defaultHttp,
) {
    private val issuer = issuer.trimEnd('/')
    val authorizeEndpoint: String get() = "$issuer/oauth/authorize"
    val tokenEndpoint: String get() = "$issuer/oauth/token"

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
        return authorizeEndpoint + "?" + params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    /**
     * Authorization-code grant: form-encoded POST with the fields of codex-rs server.rs `exchange_code_for_tokens`
     * (grant_type, code, redirect_uri, client_id, code_verifier). The wire order below is the one a real
     * ChatGPT sign-in was verified with (2026-11-13); CodexOAuthTest pins it. Blocking.
     * @throws TokenEndpointException on a non-2xx answer, IOException on network problems.
     */
    fun exchangeCode(code: String, redirectUri: String, pkce: Pkce): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", pkce.verifier)
            .build()
        return post(Request.Builder().url(tokenEndpoint).post(body).build(), secrets = listOf(code, pkce.verifier))
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
        return post(Request.Builder().url(tokenEndpoint).post(body).build(), secrets = listOf(refreshToken))
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

    class Pkce(val verifier: String, val challenge: String)

    class TokenResponse(val idToken: String?, val accessToken: String?, val refreshToken: String?) {
        override fun toString() =
            "TokenResponse(idToken=${idToken != null}, accessToken=${accessToken != null}, refreshToken=${refreshToken != null})"
    }

    /** Non-2xx from the token endpoint. [errorCode] is e.g. invalid_grant / refresh_token_reused. */
    class TokenEndpointException(val status: Int, val errorCode: String?, message: String) : IOException(message) {
        /** Codex treats these as "sign in again" (auth/manager.rs `request_chatgpt_token_refresh`). */
        val isPermanent: Boolean
            get() = status == 401 ||
                (status == 400 && errorCode.equals("invalid_grant", ignoreCase = true)) ||
                errorCode?.lowercase() in PERMANENT_CODES
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
        const val ORIGINATOR = "codex_cli_rs"
        const val DEFAULT_PORT = 1455
        /** Codex falls back to this port (also on the redirect-URI allow-list) when 1455 is busy. */
        const val FALLBACK_PORT = 1457
        const val CALLBACK_PATH = "/auth/callback"

        private val PERMANENT_CODES = setOf("refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated")

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun redirectUri(port: Int) = "http://127.0.0.1:$port$CALLBACK_PATH"

        private val random = SecureRandom()

        private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** 64 random bytes -> base64url verifier; challenge = base64url(sha256(verifier)) (S256). */
        fun generatePkce(): Pkce = pkceFor(b64url(ByteArray(64).also(random::nextBytes)))

        /** The S256 pair for a known verifier (used when a login is resumed after the process died). */
        fun pkceFor(verifier: String): Pkce {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, b64url(digest))
        }

        /** 32 random bytes, base64url. */
        fun generateState(): String = b64url(ByteArray(32).also(random::nextBytes))

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8") // form encoding, like Rust's url::form_urlencoded

        /** Accepts `{"error":"x","error_description":"y"}` and `{"error":{"code":"x","message":"y"}}`. */
        internal fun parseError(text: String): Pair<String?, String?> = try {
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
}
