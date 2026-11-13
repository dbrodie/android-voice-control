package com.brainstormity.voicecontrol.voice.live

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * GPT-Live wire contract for the ChatGPT-subscription ("Codex voice") route.
 *
 * Ported from opencode-gpt-live `src/server/live.ts` (MIT). Call creation posts a WebRTC SDP offer to the
 * Codex backend, which answers with the SDP answer and a call id. The control channel ("sideband") is a
 * WebSocket on the public Live API joined with the same ChatGPT credentials. None of this is a documented
 * public API, so everything route-specific lives in this file.
 *
 * The parser also accepts the documented public Live API names (`session.delegation.created`), so an
 * API-key transport can feed the same [LiveEvent] stream later.
 */
object LiveProtocol {
    const val MODEL = "gpt-live-1-codex"
    const val CALL_URL = "https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas"
    const val SIDEBAND_URL = "wss://api.openai.com/v1/live/"
    const val APPEND_MAX_BYTES = 500
    const val DEFAULT_VOICE = "cove"
    /** Voices accepted by the Codex route (from opencode-gpt-live `VOICES`). */
    val VOICES = listOf("cove", "juniper", "maple", "spruce", "ember", "vale", "breeze", "arbor", "sol")

    private val CALL_ID = Regex(
        "^(rtc_[\\w-]+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$",
        RegexOption.IGNORE_CASE,
    )

    /** Per-call request ids, sent as headers on both the call POST and the sideband handshake. */
    data class RequestIds(
        val realtimeSessionId: String = UUID.randomUUID().toString(),
        val sessionId: String = UUID.randomUUID().toString(),
        val threadId: String = UUID.randomUUID().toString(),
    )

    fun headers(creds: LiveCredentials, ids: RequestIds, version: String): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer ${creds.accessToken}",
        "ChatGPT-Account-ID" to creds.accountId,
        "OpenAI-Alpha" to "quicksilver=v2",
        "session-id" to ids.sessionId,
        "thread-id" to ids.threadId,
        "x-session-id" to ids.realtimeSessionId,
        "originator" to "android_voice_control",
        "User-Agent" to "android-voice-control/$version",
    )

    /** Removes credentials from any server text before it is logged or shown. */
    fun redact(text: String, creds: LiveCredentials?): String {
        if (creds == null) return text
        var out = text
        if (creds.accessToken.isNotEmpty()) out = out.replace(creds.accessToken, "[redacted]")
        if (creds.accountId.isNotEmpty()) out = out.replace(creds.accountId, "[redacted]")
        return out
    }

    /** Human-readable message for a failed call creation (mirrors `describe` in live.ts). */
    fun describeHttpError(status: Int, detail: String): String = when (status) {
        401 -> "Sign in again: ChatGPT rejected the sign-in (401)."
        403 -> "Your ChatGPT plan has no voice, or the voice allowance is used up (403)."
        429 -> "Voice limit reached (429). Try again later."
        else -> "Voice call failed ($status)" + if (detail.isNotBlank()) ": $detail" else ""
    }

    /** Call id from the `Location` header path (e.g. `/v1/realtime/calls/rtc_123`), else `openai-session-id`. */
    fun parseCallId(location: String?, sessionHeader: String?): String {
        if (!location.isNullOrBlank()) {
            val path = try { URI(CALL_URL).resolve(location.trim()).path } catch (_: Exception) { location }
            path.orEmpty().split('/').filter { it.isNotEmpty() }.firstOrNull { CALL_ID.matches(it) }
                ?.let { return it }
        }
        val fallback = sessionHeader?.trim()
        if (!fallback.isNullOrEmpty() && CALL_ID.matches(fallback)) return fallback
        throw LiveException("GPT-Live call response did not include a call id")
    }

    // ---------------------------------------------------------------------------------------------------
    // Server events

    /** Parses one server event. Returns null for non-JSON / untyped payloads. */
    fun parseEvent(payload: String): LiveEvent? {
        val event = try { JSONObject(payload) } catch (_: Exception) { return null }
        val type = event.optString("type", "")
        if (type.isEmpty()) return null
        return when (type) {
            "session.started" -> LiveEvent.Started(event.optJSONObject("session")?.optStringOrNull("id"))
            "input_transcript.added", "output_transcript.added" -> {
                val text = event.optJSONObject("item")?.optStringOrNull("text") ?: return LiveEvent.Other(type)
                LiveEvent.Transcript(if (type.startsWith("input")) Role.USER else Role.ASSISTANT, text, final = false)
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val delta = event.optStringOrNull("delta") ?: return LiveEvent.Other(type)
                LiveEvent.Transcript(if (type.contains("input")) Role.USER else Role.ASSISTANT, delta, final = false)
            }
            "turn.done" -> {
                val turn = event.optJSONObject("turn") ?: return LiveEvent.Other(type)
                val role = when (turn.optString("role")) {
                    "user" -> Role.USER
                    "assistant" -> Role.ASSISTANT
                    else -> return LiveEvent.Other(type)
                }
                val transcript = turn.optStringOrNull("transcript") ?: return LiveEvent.Other(type)
                LiveEvent.Transcript(role, transcript, final = true)
            }
            // Codex route: the delegation carries the request text in item.content[].input_text.
            "delegation.created" -> {
                val item = event.optJSONObject("item") ?: return LiveEvent.Other(type)
                val id = item.optStringOrNull("id")
                if (item.optString("type") != "delegation" || item.optString("target") != "client" || id == null) {
                    return LiveEvent.Other(type)
                }
                LiveEvent.Delegation(id, contentText(item.optJSONArray("content")))
            }
            // Public Live API: metadata only; the text has to come from the transcript.
            "session.delegation.created" -> {
                val d = event.optJSONObject("delegation") ?: return LiveEvent.Other(type)
                val id = d.optStringOrNull("id") ?: return LiveEvent.Other(type)
                if (d.optString("target", "client") != "client") return LiveEvent.Other(type)
                LiveEvent.Delegation(id, contentText(d.optJSONArray("content")))
            }
            "output_audio_buffer.cleared" -> LiveEvent.SpeechCleared
            "session.closed" -> LiveEvent.Closed(event.optStringOrNull("reason"))
            "error" -> {
                val error = event.optJSONObject("error")
                val status = when {
                    event.has("status") -> event.optInt("status")
                    error?.has("status") == true -> error.optInt("status")
                    else -> 0
                }
                val code = (event.optStringOrNull("code") ?: error?.optStringOrNull("code") ?: "").lowercase()
                val message = error?.optStringOrNull("message")
                    ?: event.optStringOrNull("message")
                    ?: event.optStringOrNull("error")
                    ?: "GPT-Live error"
                val fatal = status == 401 ||
                    code in setOf("authentication_error", "invalid_token", "token_expired", "invalid_api_key")
                LiveEvent.Error(message, fatal, code.ifEmpty { null })
            }
            else -> LiveEvent.Other(type)
        }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "input_text") part.optStringOrNull("text")?.let(sb::append)
        }
        return sb.toString()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) opt(key) as? String else null

    // ---------------------------------------------------------------------------------------------------
    // Client events

    /** Splits text into chunks of at most [maxBytes] UTF-8 bytes without breaking a code point. */
    fun chunk(text: String, maxBytes: Int = APPEND_MAX_BYTES): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val size = ch.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && bytes + size > maxBytes) {
                chunks += current.toString()
                current.setLength(0)
                bytes = 0
            }
            current.append(ch)
            bytes += size
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * `session.context.append` (general) or `delegation.context.append` (answering one delegation),
     * one message per 500-byte chunk. Codex-route schema (bridge.ts / live.ts).
     */
    fun contextAppend(text: String, channel: LiveChannel, delegationId: String? = null): List<JSONObject> =
        chunk(text).map { part ->
            val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", part))
            if (delegationId != null) {
                JSONObject()
                    .put("type", "delegation.context.append")
                    .put("delegation_item_id", delegationId)
                    .put("channel", channel.wire)
                    .put("content", content)
            } else {
                JSONObject()
                    .put("type", "session.context.append")
                    .put("channel", channel.wire)
                    .put("content", content)
            }
        }

    fun sessionClose(): JSONObject = JSONObject().put("type", "session.close")
}

enum class Role { USER, ASSISTANT }

/**
 * `SPEAKABLE`: GPT should say it now (paraphrased). `COMMENTARY`: silent background it can use later.
 * (Public Live API equivalents: `session.commentary.append` and `session.thinking.append`.)
 */
enum class LiveChannel(val wire: String) { SPEAKABLE("speakable"), COMMENTARY("commentary") }

sealed class LiveEvent {
    data class Started(val sessionId: String?) : LiveEvent()
    data class Transcript(val role: Role, val text: String, val final: Boolean) : LiveEvent()
    data class Delegation(val id: String, val text: String) : LiveEvent()
    object SpeechCleared : LiveEvent() { override fun toString() = "SpeechCleared" }
    data class Closed(val reason: String?) : LiveEvent()
    data class Error(val message: String, val fatal: Boolean, val code: String? = null) : LiveEvent()
    data class Other(val type: String) : LiveEvent()
}

/** A failure with an optional HTTP status (call creation) so the UI can map 401/403/429. */
class LiveException(message: String, val status: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/**
 * The `session` object posted with the SDP offer:
 * `{model, instructions, audio:{output:{voice}}, delegation:{type:"client", ack_filler?}}`.
 */
data class LiveSessionConfig(
    val instructions: String,
    val voice: String = LiveProtocol.DEFAULT_VOICE,
    val model: String = LiveProtocol.MODEL,
    /** Let GPT-Live speak a short filler ("one moment") right after delegating. Null = omit the field. */
    val ackFiller: Boolean? = null,
) {
    fun toJson(): JSONObject {
        val delegation = JSONObject().put("type", "client")
        if (ackFiller != null) delegation.put("ack_filler", ackFiller)
        return JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("audio", JSONObject().put("output", JSONObject().put("voice", voice)))
            .put("delegation", delegation)
    }
}
