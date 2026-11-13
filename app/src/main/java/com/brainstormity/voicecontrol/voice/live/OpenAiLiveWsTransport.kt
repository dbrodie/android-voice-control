package com.brainstormity.voicecontrol.voice.live

import android.content.Context
import com.brainstormity.voicecontrol.BuildConfig
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPT-Live over the official public API with an OpenAI API key: one WebSocket carries audio and events.
 *
 * 1. `wss://api.openai.com/v1/live/sessions` (no query), `Authorization: Bearer <key>`.
 * 2. First message `session.start` ([LiveProtocol.publicSessionStart]: model gpt-live-1, PCM16 24 kHz, client
 *    delegation); [connect] returns on `session.started` (a handshake 401 becomes "Invalid OpenAI API key").
 * 3. Mic -> `session.input_audio.append` (base64 PCM16, 40 ms chunks); `session.output_audio.delta` -> speaker.
 * 4. Events go through [LiveProtocol.parseEvent] (transcript deltas, `session.delegation.created`, errors, ...).
 * 5. [append]: SPEAKABLE -> `session.commentary.append`, COMMENTARY -> `session.thinking.append`.
 * 6. [close]: `session.close`, wait for `session.closed` (final usage), then close the socket.
 *
 * The primary WebSocket has no "output cleared" event, so barge-in is local: when the user's transcript shows
 * real words while queued assistant audio is still playing and the server has stopped streaming audio, the
 * queue is flushed and [LiveEvent.SpeechCleared] is emitted.
 */
class OpenAiLiveWsTransport(
    private val apiKey: String,
    private val config: LiveSessionConfig,
    private val audioFactory: () -> PcmAudio?,
    private val http: OkHttpClient = CodexLiveTransport.defaultClient,
    private val url: String = LiveProtocol.PUBLIC_URL,
    private val rate: Int = LiveProtocol.PUBLIC_RATE,
    private val startTimeoutMs: Long = 20_000,
    private val closeTimeoutMs: Long = 5_000,
) : LiveSession {
    constructor(context: Context, apiKey: String, config: LiveSessionConfig) :
        this(apiKey, config, { AndroidPcmAudio(context.applicationContext, LiveProtocol.PUBLIC_RATE) })

    private val _connection = MutableStateFlow(LiveConnectionState.IDLE)
    override val connection: StateFlow<LiveConnectionState> = _connection.asStateFlow()
    @Volatile override var callId: String? = null
        private set
    @Volatile override var onEvent: ((LiveEvent) -> Unit)? = null

    @Volatile private var socket: WebSocket? = null
    @Volatile private var audio: PcmAudio? = null
    private val started = CompletableDeferred<String?>()
    private val closedEvent = CompletableDeferred<LiveEvent.Closed>()
    @Volatile private var finished = false
    @Volatile private var streaming = false
    private val seq = AtomicInteger(0)

    // Barge-in bookkeeping.
    @Volatile private var lastOutputAudioAt = 0L
    @Volatile private var userWordsSinceOutput = 0
    private var droppedMicChunks = 0

    override suspend fun connect() = withContext(Dispatchers.IO) {
        check(_connection.value == LiveConnectionState.IDLE) { "connect() called twice" }
        if (apiKey.isBlank()) throw LiveException("Enter an OpenAI API key in settings", 401)
        _connection.value = LiveConnectionState.CONNECTING
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", "android-voice-control/${BuildConfig.VERSION_NAME}")
                .build()
            socket = http.newWebSocket(request, Listener())
            val id = withTimeoutOrNull(startTimeoutMs) { started.await() }
                ?: throw LiveException("GPT-Live did not start the session in time")
            callId = id
            VcLog.i(TAG, "session.started id=$id")
            startAudio()
            _connection.value = LiveConnectionState.CONNECTED
        } catch (e: Exception) {
            _connection.value = LiveConnectionState.FAILED
            finished = true
            release()
            throw when (e) {
                is LiveException, is kotlinx.coroutines.CancellationException -> e
                else -> LiveException(e.message ?: e.javaClass.simpleName, 0, e)
            }
        }
    }

    private fun startAudio() {
        val a = audioFactory() ?: return
        audio = a
        streaming = true
        a.start { pcm -> sendMic(pcm) }
    }

    private fun sendMic(pcm: ByteArray) {
        val ws = socket ?: return
        if (!streaming || pcm.isEmpty()) return
        // Back-pressure: on a stalled network drop mic audio rather than queueing megabytes.
        if (ws.queueSize() > MAX_QUEUE_BYTES) {
            if (droppedMicChunks++ % 50 == 0) VcLog.w(TAG, "network backlog ${ws.queueSize()} B: dropping mic audio")
            return
        }
        val even = pcm.size - pcm.size % 2
        ws.send(LiveProtocol.inputAudioAppend(pcm, 0, even).toString())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            VcLog.i(TAG, "WS open (HTTP ${response.code}); sending session.start")
            webSocket.send(LiveProtocol.publicSessionStart(config, nextId("start"), rate).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket === webSocket) onText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!started.isCompleted) {
                started.completeExceptionally(LiveException("GPT-Live closed the connection during startup ($code ${reason.take(200)})"))
            } else finish(reason.ifBlank { "connection_lost" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!started.isCompleted) {
                val status = response?.code ?: 0
                val body = try { response?.body?.string() } catch (_: Exception) { null }
                val detail = LiveProtocol.errorBodyMessage(body, apiKey)
                VcLog.w(TAG, "handshake failed: HTTP $status (${t.javaClass.simpleName})${detail?.let { " server says: $it" } ?: ""}")
                val message = if (status > 0) LiveProtocol.describePublicHttpError(status, detail)
                    else "GPT-Live connection failed: ${t.message ?: t.javaClass.simpleName}"
                started.completeExceptionally(LiveException(message, status, t))
            } else {
                VcLog.w(TAG, "connection lost: ${t.javaClass.simpleName}: ${t.message}")
                finish("connection_lost")
            }
        }
    }

    private fun onText(text: String) {
        // Audio deltas are the bulk of the traffic: decode and play without the generic parser.
        if (text.contains(OUTPUT_AUDIO_TYPE)) {
            val json = try { JSONObject(text) } catch (_: Exception) { null }
            val pcm = json?.let(LiveProtocol::outputAudio)
            if (pcm != null) {
                lastOutputAudioAt = System.currentTimeMillis()
                userWordsSinceOutput = 0
                audio?.play(pcm)
                onEvent?.invoke(LiveEvent.Other(OUTPUT_AUDIO_TYPE))
                return
            }
        }
        val event = LiveProtocol.parseEvent(text) ?: return
        when (event) {
            is LiveEvent.Started -> started.complete(event.sessionId)
            is LiveEvent.Error -> {
                VcLog.w(TAG, "server error${if (event.fatal) " (fatal)" else ""}: ${LiveProtocol.errorBodyMessage(text, apiKey) ?: event.message}")
                if (!started.isCompleted) {
                    val status = if (event.fatal) 401 else 0
                    val msg = if (event.fatal) LiveProtocol.describePublicHttpError(401, null)
                        else "GPT-Live rejected the session: ${redact(event.message)}"
                    started.completeExceptionally(LiveException(msg, status))
                    return
                }
            }
            is LiveEvent.Transcript -> {
                if (RAW_LOG) VcLog.d(TAG, "event $event")
                if (event.role == Role.USER) maybeBargeIn(event.text)
            }
            LiveEvent.SpeechCleared -> audio?.flushPlayback()
            is LiveEvent.Other -> if (RAW_LOG) VcLog.d(TAG, "event ${event.type}: ${redact(text.take(400))}") else VcLog.d(TAG, "event ${event.type}")
            else -> VcLog.d(TAG, "event $event")
        }
        if (event is LiveEvent.Closed) {
            closedEvent.complete(event)
            finish(event.reason ?: "closed")
            return
        }
        // Give the error a public-API reading (the parser's wording is shared with the Codex route).
        val out = if (event is LiveEvent.Error && event.fatal) event.copy(message = LiveProtocol.describePublicHttpError(401, null)) else event
        onEvent?.invoke(out)
    }

    private fun maybeBargeIn(delta: String) {
        val a = audio ?: return
        userWordsSinceOutput += delta.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        val serverQuietMs = System.currentTimeMillis() - lastOutputAudioAt
        if (userWordsSinceOutput >= BARGE_IN_WORDS && serverQuietMs >= BARGE_IN_QUIET_MS && a.queuedMs() > 0) {
            VcLog.d(TAG, "barge-in: user spoke over ${a.queuedMs()} ms of queued assistant audio; flushing")
            a.flushPlayback()
            onEvent?.invoke(LiveEvent.SpeechCleared)
        }
    }

    override fun send(event: JSONObject): Boolean {
        val ws = socket ?: return false
        if (finished) return false
        return ws.send(event.toString())
    }

    override fun append(text: String, channel: LiveChannel, delegationId: String?): Boolean {
        var ok = true
        for (message in LiveProtocol.publicAppend(text, channel, delegationId) { nextId(channel.wire) }) {
            ok = send(message) && ok
        }
        VcLog.d(TAG, "append[${channel.wire}${delegationId?.let { " d=$it" } ?: ""}] ok=$ok: ${text.take(160)}")
        return ok
    }

    override fun setMicEnabled(enabled: Boolean) {
        audio?.setMicEnabled(enabled)
    }

    override suspend fun close() {
        if (finished) return
        _connection.value = LiveConnectionState.CLOSING
        streaming = false
        val sent = started.isCompleted && send(LiveProtocol.sessionClose().put("event_id", nextId("close")))
        if (sent) {
            // Keep the socket open until `session.closed` (close reason + final usage) arrives.
            val closed = withTimeoutOrNull(closeTimeoutMs) { closedEvent.await() }
            VcLog.d(TAG, "close: session.closed ${if (closed != null) "received (${closed.reason})" else "not received"}")
            finish(closed?.reason ?: "close_requested")
            return
        }
        finish("close_requested")
    }

    /** Ends the session once: releases audio and the socket, emits a final [LiveEvent.Closed]. */
    private fun finish(reason: String) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        VcLog.i(TAG, "session ended: $reason")
        release()
        _connection.value = LiveConnectionState.CLOSED
        onEvent?.invoke(LiveEvent.Closed(reason))
    }

    private fun release() {
        streaming = false
        audio?.release()
        audio = null
        socket?.close(1000, "session closed")
        socket = null
    }

    private fun nextId(kind: String) = "vc_${kind}_${seq.incrementAndGet()}"

    private fun redact(text: String) = if (apiKey.isEmpty()) text else text.replace(apiKey, "[redacted]")

    companion object {
        private const val TAG = "Live"
        private const val OUTPUT_AUDIO_TYPE = "session.output_audio.delta"
        private const val MAX_QUEUE_BYTES = 1L shl 20
        /** Words of user transcript needed to treat speech over assistant audio as an interruption ("mm-hmm" is not). */
        const val BARGE_IN_WORDS = 2
        /** The server must have stopped streaming assistant audio for this long (it yielded the turn). */
        const val BARGE_IN_QUIET_MS = 300L
        private val RAW_LOG = BuildConfig.DEBUG
    }
}
