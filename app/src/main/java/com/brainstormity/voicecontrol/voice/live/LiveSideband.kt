package com.brainstormity.voicecontrol.voice.live

import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The control WebSocket for a live call (port of `Sideband` in opencode-gpt-live live.ts):
 * `wss://api.openai.com/v1/live/<callId>` with the same headers as call creation. Up to five attempts with
 * exponential backoff (200, 400, 800, 1600 ms), 15 s handshake timeout each.
 */
class LiveSideband(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val onText: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    val isOpen: Boolean get() = socket != null && !closed

    suspend fun connect() {
        var last: Exception? = null
        for (attempt in 0 until 5) {
            if (closed) throw LiveException("control channel closed")
            try {
                open()
                return
            } catch (e: LiveException) {
                last = e
                VcLog.w(TAG, "sideband attempt ${attempt + 1} failed: ${e.message}")
                // Auth failures will not fix themselves.
                if (e.status == 401 || e.status == 403) throw e
                if (attempt < 4) delay(200L shl attempt)
            }
        }
        throw last ?: LiveException("GPT-Live control channel failed to connect")
    }

    private suspend fun open() {
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                VcLog.i(TAG, "sideband WS open (HTTP ${response.code})")
                socket = webSocket
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket === webSocket) onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(LiveException("control channel closed during startup ($code)"))
                } else ended(webSocket, reason.ifBlank { "closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) {
                    val status = response?.code ?: 0
                    val body = try { response?.body?.string()?.take(300)?.let(::redact) } catch (_: Exception) { null }
                    VcLog.w(TAG, "sideband handshake failed: HTTP $status ${t.javaClass.simpleName}" +
                        (body?.let { " body=${it.replace(Regex("\\s+"), " ")}" } ?: ""))
                    opened.completeExceptionally(
                        LiveException("GPT-Live control channel failed (HTTP $status): ${t.message}", status, t),
                    )
                } else ended(webSocket, "connection lost: ${t.message}")
            }
        })
        val ok = withTimeoutOrNull(15_000) { opened.await() }
        if (ok == null) {
            ws.cancel()
            throw LiveException("GPT-Live control channel timed out")
        }
    }

    private fun ended(webSocket: WebSocket, reason: String) {
        if (socket !== webSocket) return
        socket = null
        if (!closed) {
            closed = true
            onClosed(reason)
        }
    }

    private fun redact(text: String): String {
        var out = text
        headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotEmpty() }?.let { out = out.replace(it, "[redacted]") }
        headers["ChatGPT-Account-ID"]?.takeIf { it.isNotEmpty() }?.let { out = out.replace(it, "[redacted]") }
        return out
    }

    fun send(message: JSONObject): Boolean = socket?.send(message.toString()) ?: false

    /** Sends `session.close`; the socket itself is closed by [shutdown]. */
    fun requestClose(): Boolean = send(LiveProtocol.sessionClose())

    fun shutdown() {
        closed = true
        socket?.close(1000, "session closed")
        socket = null
    }

    companion object { private const val TAG = "Live" }
}
