package com.brainstormity.voicecontrol.voice.live

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class LiveConnectionState { IDLE, CONNECTING, CONNECTED, CLOSING, CLOSED, FAILED }

/**
 * One GPT-Live call, independent of how audio and control events travel.
 *
 * Implementations:
 * - [CodexLiveTransport]: ChatGPT subscription. WebRTC audio + `oai-events` data channel, call created via
 *   chatgpt.com, control over the `wss://api.openai.com/v1/live/<callId>` sideband.
 * - [OpenAiLiveWsTransport]: OpenAI API key. Public `wss://api.openai.com/v1/live/sessions`, PCM16 audio and
 *   events over the same WebSocket.
 *
 * Contract:
 * - [connect] suspends until the call is up and control events can be sent, or throws [LiveException]
 *   (with `status` = HTTP status for call-creation failures).
 * - Every server event is parsed with [LiveProtocol.parseEvent] and delivered to [onEvent] exactly once,
 *   on a background thread. When the call ends for any reason, a final [LiveEvent.Closed] is delivered.
 * - [append] chunks text into 500-byte context appends; [send] sends any raw client event.
 */
interface LiveSession {
    val connection: StateFlow<LiveConnectionState>
    /** Server-side call/session id once known (for logs and the sideband). */
    val callId: String?
    var onEvent: ((LiveEvent) -> Unit)?

    suspend fun connect()

    /** Adds context to the call. With [delegationId] it answers that delegation. Returns false if not sent. */
    fun append(text: String, channel: LiveChannel, delegationId: String? = null): Boolean

    /** Sends one raw client event. Returns false if no control channel is open. */
    fun send(event: JSONObject): Boolean

    /** Mutes/unmutes the microphone without ending the call. */
    fun setMicEnabled(enabled: Boolean)

    /** Graceful close: `session.close`, wait briefly for `session.closed`, then release audio + network. */
    suspend fun close()
}
