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
            "session.closed" -> LiveEvent.Closed(event.optStringOrNull("reason"), parseUsage(event))
            "session.usage.updated" -> parseUsage(event)?.let { LiveEvent.Usage(it) } ?: LiveEvent.Other(type)
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

    /**
     * The voice usage in `session.usage.updated` / `session.closed`, or null if the event has none. Both routes
     * report a cumulative snapshot (each one replaces the previous; never sum them):
     * - Codex (ChatGPT) route, captured 2026-11-13:
     *   `{"type":"session.usage.updated","usage":{"audio_duration_ms":13200,"backend_model_usage":[]},
     *     "usage_limit":{"status":null,"reset_seconds":null}}` about every 15 s, and on a clean close
     *   `{"type":"session.closed","reason":"client_request","usage":{"audio_duration_ms":145000,"backend_model_usage":[]}}`
     *   (no `usage_limit` there). When the call dies on our side (audio failure) no `session.closed` arrives.
     * - Public API (https://developers.openai.com/api/docs/guides/voice-latency-cost?api=live):
     *   `{"type":"session.usage.updated","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}` and the final
     *   `session.closed {reason, usage:{seconds}}`.
     */
    fun parseUsage(event: JSONObject): LiveUsage? {
        val usage = event.optJSONObject("usage")
        val ms: Long?
        val source: String?
        when {
            usage != null && usage.has("audio_duration_ms") && !usage.isNull("audio_duration_ms") -> {
                ms = usage.optLong("audio_duration_ms"); source = "audio_duration_ms"
            }
            usage != null && usage.has("seconds") && !usage.isNull("seconds") -> {
                ms = Math.round(usage.optDouble("seconds", 0.0) * 1000); source = "seconds"
            }
            else -> { ms = null; source = null }
        }
        val limit = event.optJSONObject("usage_limit")
        if (ms == null && limit == null) return null
        return LiveUsage(
            durationMs = ms,
            source = source,
            limitStatus = limit?.let { if (it.has("status") && !it.isNull("status")) it.opt("status")?.toString() else null },
            limitResetSeconds = limit?.let { if (it.has("reset_seconds") && !it.isNull("reset_seconds")) it.optLong("reset_seconds") else null },
            hasLimit = limit != null,
        )
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

    // ---------------------------------------------------------------------------------------------------
    // Public GPT-Live API (API key, primary WebSocket). Verified 2026-11-13 against
    // https://developers.openai.com/api/reference/resources/live/primary-websocket and
    // https://developers.openai.com/api/docs/guides/voice-websockets?api=live (see voice/live/README.md).

    /** Primary WebSocket. No query parameters; `Authorization: Bearer <OpenAI API key>`. */
    const val PUBLIC_URL = "wss://api.openai.com/v1/live/sessions"
    const val PUBLIC_MODEL = "gpt-live-1"
    /** `session.audio.format` PCM rates accepted on the primary WebSocket (mono PCM16 LE). */
    const val PUBLIC_RATE = 24_000
    const val PUBLIC_DEFAULT_VOICE = "marin"
    /** Built-in voices of the public API (`session.audio.output.voice`); the Codex voices are not among them. */
    val PUBLIC_VOICES = setOf(
        "alloy", "ash", "ballad", "beacon", "bossa", "brise", "calandria", "cedar", "cinder", "compas", "coral",
        "delta", "destello", "echo", "flitz", "gleam", "halo", "harema", "hornero", "juni", "luciernaga", "marin",
        "meridian", "nira", "noeul", "nuri", "pearl", "quartz", "ripple", "sage", "shida", "shimmer", "sillage",
        "stone", "tempo", "verse", "vesper", "willow",
    )

    /** A public-API model id from a settings value: any `gpt-live*` id except the Codex-only one, else gpt-live-1. */
    fun publicModel(setting: String?): String {
        val m = setting?.trim().orEmpty()
        return if (m.startsWith("gpt-live") && !m.endsWith("-codex")) m else PUBLIC_MODEL
    }

    /**
     * `session.start` for the primary WebSocket. Based on [LiveSessionConfig.toJson], adapted to the public schema:
     * public model id, `audio.format {type:"audio/pcm", rate}`, a public voice (Codex voices fall back to marin),
     * and no `delegation.ack_filler` (not a public field; unknown parameters are rejected).
     */
    fun publicSessionStart(config: LiveSessionConfig, eventId: String, rate: Int = PUBLIC_RATE): JSONObject {
        val session = config.toJson()
        session.put("model", publicModel(config.model))
        val voice = config.voice.takeIf { it in PUBLIC_VOICES } ?: PUBLIC_DEFAULT_VOICE
        session.put(
            "audio", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", rate))
                .put("output", JSONObject().put("voice", voice)),
        )
        session.optJSONObject("delegation")?.remove("ack_filler")
        return JSONObject().put("type", "session.start").put("event_id", eventId).put("session", session)
    }

    /**
     * Public context appends: [LiveChannel.SPEAKABLE] -> `session.commentary.append` (GPT paraphrases it aloud),
     * [LiveChannel.COMMENTARY] -> `session.thinking.append` (silent). `delegation_id` is required but nullable.
     * `content` is limited to 500 tokens; chunks of at most 500 UTF-8 bytes can never exceed that.
     */
    fun publicAppend(text: String, channel: LiveChannel, delegationId: String?, eventId: (Int) -> String): List<JSONObject> =
        chunk(text).mapIndexed { i, part ->
            JSONObject()
                .put("type", if (channel == LiveChannel.SPEAKABLE) "session.commentary.append" else "session.thinking.append")
                .put("event_id", eventId(i))
                .put("delegation_id", delegationId ?: JSONObject.NULL)
                .put("content", part)
        }

    /** `session.input_audio.append` with base64 of raw PCM16 LE mono (even byte count). */
    fun inputAudioAppend(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): JSONObject {
        val b64 = java.util.Base64.getEncoder().encodeToString(
            if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length),
        )
        return JSONObject().put("type", "session.input_audio.append").put("audio", b64)
    }

    /** Decoded PCM of a `session.output_audio.delta`, or null for any other event / a malformed delta. */
    fun outputAudio(event: JSONObject): ByteArray? {
        if (event.optString("type") != "session.output_audio.delta") return null
        val delta = event.optStringOrNull("delta") ?: return null
        return try { java.util.Base64.getDecoder().decode(delta) } catch (_: IllegalArgumentException) { null }
    }

    /** Message of a JSON error body (`{"error":{"message":..}}`), with [secret] removed. */
    fun errorBodyMessage(body: String?, secret: String?): String? {
        if (body.isNullOrBlank()) return null
        val msg = try {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optStringOrNull("message") ?: root.optStringOrNull("message")
        } catch (_: Exception) { body.trim().take(200) } ?: return null
        return if (secret.isNullOrEmpty()) msg else msg.replace(secret, "[redacted]")
    }

    /** Human-readable message for a rejected API-key WebSocket handshake. */
    fun describePublicHttpError(status: Int, detail: String?): String = when (status) {
        401 -> "Invalid OpenAI API key (401). Check the key in settings."
        403 -> "This OpenAI API key has no access to GPT-Live (403)."
        429 -> "OpenAI rate limit or quota exceeded (429). Try again later."
        else -> "GPT-Live connection failed" + (if (status > 0) " (HTTP $status)" else "") +
            if (!detail.isNullOrBlank()) ": $detail" else ""
    }
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
    /** [usage]: the final usage snapshot carried by `session.closed`, if any. */
    data class Closed(val reason: String?, val usage: LiveUsage? = null) : LiveEvent()
    /**
     * `session.usage.updated`: a cumulative snapshot (replaces the previous one). [final]: the transport re-emits
     * the usage of `session.closed` this way: the session's final figure (both routes send one on a server-confirmed
     * close; without it the last snapshot can be ~15 s old on the Codex route).
     */
    data class Usage(val usage: LiveUsage, val final: Boolean = false) : LiveEvent()
    data class Error(val message: String, val fatal: Boolean, val code: String? = null) : LiveEvent()
    data class Other(val type: String) : LiveEvent()
}

/**
 * Voice usage reported by the server. [durationMs]: the cumulative billed voice duration so far (Codex route
 * `usage.audio_duration_ms`, public API `usage.seconds` × 1000), null if the event had none. [source] names the
 * field it came from. [limitStatus]/[limitResetSeconds]: the ChatGPT plan allowance (`usage_limit`, Codex route only;
 * both null while within the allowance as of 2026-11-13). [hasLimit]: the event carried a `usage_limit` object.
 */
data class LiveUsage(
    val durationMs: Long?,
    val source: String?,
    val limitStatus: String? = null,
    val limitResetSeconds: Long? = null,
    val hasLimit: Boolean = false,
)

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
