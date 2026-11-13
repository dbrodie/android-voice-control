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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GPT-Live over the ChatGPT subscription (the route Codex voice / opencode-gpt-live use):
 * 1. WebRTC offer (mic track + `oai-events` data channel, host candidates only).
 * 2. `POST https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas`
 *    JSON `{sdp, session}` with ChatGPT headers -> body = SDP answer, `Location` = call id.
 * 3. Apply the answer; audio flows through libwebrtc's audio device module (speakerphone, IN_COMMUNICATION).
 * 4. Join the control WebSocket `wss://api.openai.com/v1/live/<callId>` (same headers, retry/backoff).
 * Events come from the sideband; data-channel events are only used while the sideband is not open.
 */
class CodexLiveTransport(
    context: Context,
    private val auth: LiveAuthProvider,
    private val config: LiveSessionConfig,
    private val http: OkHttpClient = defaultClient,
) : LiveSession {
    private val appContext = context.applicationContext
    private val _connection = MutableStateFlow(LiveConnectionState.IDLE)
    override val connection: StateFlow<LiveConnectionState> = _connection.asStateFlow()
    @Volatile override var callId: String? = null
        private set
    @Volatile override var onEvent: ((LiveEvent) -> Unit)? = null

    private val ids = LiveProtocol.RequestIds()
    private var creds: LiveCredentials? = null
    private var peer: RtcAudioPeer? = null
    private var sideband: LiveSideband? = null
    private val audio = AudioRouting(appContext)
    private val closedEvent = CompletableDeferred<LiveEvent.Closed>()
    @Volatile private var finished = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        check(_connection.value == LiveConnectionState.IDLE) { "connect() called twice" }
        _connection.value = LiveConnectionState.CONNECTING
        try {
            val c = auth.credentials() ?: throw LiveException("Not signed in to ChatGPT", 401)
            creds = c
            val headers = LiveProtocol.headers(c, ids, BuildConfig.VERSION_NAME)

            val p = RtcAudioPeer(appContext).also { peer = it }
            p.onDataChannelMessage = { text -> if (sideband?.isOpen != true) dispatch(text, "dc") }
            p.onPeerLost = { reason -> finish(reason) }
            audio.acquire()
            p.create()
            val offer = p.createOffer()

            val (id, answer) = createCall(headers, offer)
            callId = id
            VcLog.i(TAG, "call created: id=$id answer=${answer.length} bytes")
            p.applyAnswer(answer)
            VcLog.i(TAG, "WebRTC audio connected (peer=${p.peerState.value}, dataChannel=${p.dataChannelOpen})")

            val sb = LiveSideband(
                http,
                LiveProtocol.SIDEBAND_URL + java.net.URLEncoder.encode(id, "UTF-8"),
                headers,
                onText = { dispatch(it, "ws") },
                onClosed = { reason -> finish(reason) },
            )
            sideband = sb
            try {
                sb.connect()
            } catch (e: LiveException) {
                // Audio still works and the data channel carries the same events, so keep the call going.
                if (!p.dataChannelOpen) throw e
                VcLog.w(TAG, "sideband unavailable (${e.message}); using the data channel for control events")
            }
            _connection.value = LiveConnectionState.CONNECTED
        } catch (e: Exception) {
            _connection.value = LiveConnectionState.FAILED
            release()
            throw when (e) {
                is LiveException, is kotlinx.coroutines.CancellationException -> e
                else -> LiveException(e.message ?: e.javaClass.simpleName, 0, e)
            }
        }
    }

    private fun createCall(headers: Map<String, String>, sdp: String): Pair<String, String> {
        val body = JSONObject().put("sdp", sdp).put("session", config.toJson()).toString()
        val request = Request.Builder()
            .url(LiveProtocol.CALL_URL)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            VcLog.i(TAG, "POST calls -> HTTP ${response.code}")
            if (!response.isSuccessful) {
                val detail = LiveProtocol.redact(text.trim(), creds).replace(Regex("\\s+"), " ").take(300)
                VcLog.w(TAG, "call creation rejected: HTTP ${response.code} body=$detail")
                throw LiveException(LiveProtocol.describeHttpError(response.code, detail), response.code)
            }
            val id = LiveProtocol.parseCallId(response.header("Location"), response.header("openai-session-id"))
            if (text.isBlank()) throw LiveException("GPT-Live returned an empty SDP answer", response.code)
            return id to text
        }
    }

    private fun dispatch(payload: String, via: String) {
        val event = LiveProtocol.parseEvent(payload) ?: return
        when (event) {
            is LiveEvent.Other -> when {
                // The sideband reflects every audio packet; far too noisy to log.
                event.type.endsWith("audio.append") || event.type.endsWith("audio.delta") -> {}
                RAW_LOG -> VcLog.d(TAG, "event[$via] ${event.type}: ${payload.take(400)}")
                else -> VcLog.d(TAG, "event[$via] ${event.type}")
            }
            is LiveEvent.Transcript -> if (RAW_LOG) VcLog.d(TAG, "event[$via] $event")
            else -> VcLog.d(TAG, "event[$via] $event")
        }
        if (event is LiveEvent.Closed) {
            closedEvent.complete(event)
            finish(event.reason ?: "closed")
            return
        }
        onEvent?.invoke(event)
    }

    override fun send(event: JSONObject): Boolean {
        val sb = sideband
        if (sb != null && sb.isOpen) return sb.send(event)
        return peer?.sendDataChannel(event.toString()) ?: false
    }

    override fun append(text: String, channel: LiveChannel, delegationId: String?): Boolean {
        var ok = true
        for (message in LiveProtocol.contextAppend(text, channel, delegationId)) ok = send(message) && ok
        VcLog.d(TAG, "append[${channel.wire}${delegationId?.let { " d=$it" } ?: ""}] ok=$ok: ${text.take(160)}")
        return ok
    }

    override fun setMicEnabled(enabled: Boolean) {
        peer?.setMicEnabled(enabled)
    }

    override suspend fun close() {
        if (finished) return
        _connection.value = LiveConnectionState.CLOSING
        val sent = send(LiveProtocol.sessionClose())
        if (sent) {
            // Keep the transport open briefly so `session.closed` (final usage) can arrive.
            val closed = withTimeoutOrNull(2_000) { closedEvent.await() }
            VcLog.d(TAG, "close: session.closed ${if (closed != null) "received (${closed.reason})" else "not received"}")
            finish(closed?.reason ?: "close_requested")
            return
        }
        finish("close_requested")
    }

    /** Ends the call once: releases resources and emits a final [LiveEvent.Closed]. */
    private fun finish(reason: String) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        VcLog.i(TAG, "call ended: $reason")
        release()
        _connection.value = LiveConnectionState.CLOSED
        onEvent?.invoke(LiveEvent.Closed(reason))
    }

    private fun release() {
        sideband?.shutdown()
        peer?.dispose()
        audio.release()
    }

    companion object {
        private const val TAG = "Live"
        /** Log raw payloads of unhandled events (debug builds only). They never contain credentials. */
        private val RAW_LOG = BuildConfig.DEBUG
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
