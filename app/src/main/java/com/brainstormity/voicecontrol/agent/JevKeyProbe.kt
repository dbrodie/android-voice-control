package com.brainstormity.voicecontrol.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks a JEV API key with one tiny live request to `/v1/systemone`: a single text-only `choice` question
 * (no image). It costs ~300 input tokens (≈ $0.00001) and proves the key can really run a request.
 *
 * Seen live (2026-11): valid key -> 200 `{"model":"jev-1.14.0","answers":{"ok":{...}},"usage":{...}}`;
 * wrong key -> 401 `{"detail":{"error_type":"authentication_error","message":"Cannot authenticate ..."}}`;
 * no key -> 403 `{"detail":{"error_type":"authentication_error","message":"Must supply an API key! ..."}}`.
 * The server checks the key before validating the body (a wrong key + an invalid body is still 401), so
 * 400/422 also mean the key was accepted.
 */
object JevKeyProbe {
    sealed class Result {
        abstract val httpCode: Int?
        /** Auth passed (200, or a 4xx validation error that comes after the auth check). */
        data class Valid(override val httpCode: Int) : Result()
        /** 401/403: TypeSafe rejected the key. */
        data class Invalid(override val httpCode: Int, val detail: String) : Result()
        /** Anything else (5xx, 402, 429 ...): we can't tell whether the key is good. */
        data class Unexpected(override val httpCode: Int, val detail: String) : Result()
        data class NetworkError(val message: String) : Result() { override val httpCode: Int? = null }
    }

    const val BODY = """{"model":"${JevClient.MODEL}","state":{"purpose":"API key check from Voice Control"},""" +
        """"questions":{"ok":{"type":"choice","instructions":"Pick yes.","criteria":{"yes":"yes","no":"no"}}}}"""

    private val JSON_TYPE = "application/json".toMediaType()

    private val defaultHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Blocking: call from a background thread. [log] gets "HTTP <code>: <first 200 chars of body>" (never the
     * key) or the network error.
     */
    fun probe(
        key: String,
        url: String = JevClient.API_URL,
        http: OkHttpClient = defaultHttp,
        log: (String) -> Unit = {},
    ): Result {
        val clean = normalize(key)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $clean")
            .post(BODY.toRequestBody(JSON_TYPE))
            .build()
        return try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                log("HTTP ${r.code}: ${body.take(200)}")
                classify(r.code, body)
            }
        } catch (e: IOException) {
            val msg = e.message ?: e.javaClass.simpleName
            log("network error: ${e.javaClass.simpleName}: $msg")
            Result.NetworkError(msg)
        }
    }

    fun classify(code: Int, body: String): Result = when {
        code in 200..299 -> Result.Valid(code)
        code == 401 || code == 403 -> Result.Invalid(code, detail(body))
        code == 400 || code == 422 -> Result.Valid(code)
        else -> Result.Unexpected(code, detail(body))
    }

    /** The server's `detail.message` if there is one, else the start of the body. */
    fun detail(body: String): String {
        val m = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
        return (m ?: body.trim()).take(200)
    }

    /** What we store and send: pasted keys often carry spaces/newlines or a "Bearer " prefix. */
    fun normalize(key: String): String =
        key.trim().removePrefix("Bearer ").removePrefix("bearer ").filterNot { it.isWhitespace() }

    /** For display: the first 12 characters and "…", never the whole key. */
    fun mask(key: String): String {
        val k = normalize(key)
        return when {
            k.isEmpty() -> ""
            k.length <= 12 -> k.take(minOf(4, k.length / 2)) + "…"
            else -> k.take(12) + "…"
        }
    }
}
