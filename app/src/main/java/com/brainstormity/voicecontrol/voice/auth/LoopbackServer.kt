package com.brainstormity.voicecontrol.voice.auth

import com.brainstormity.voicecontrol.core.VcLog
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 that waits for the OAuth redirect (`GET /auth/callback?code=..&state=..`).
 * Other paths get a 404, except `GET /cancel` which aborts the wait (same convention as the Codex CLI, so a
 * newer login can evict a stale one). One connection at a time, `Connection: close`.
 */
internal class LoopbackServer private constructor(private val server: ServerSocket) : Closeable {

    val port: Int get() = server.localPort

    sealed class Result {
        /** The browser hit the callback. Call [respond] exactly once. */
        class Callback(private val socket: Socket, val params: Map<String, String>) : Result() {
            fun respond(status: Int, html: String) = writeResponse(socket, status, html)
        }
        object Cancelled : Result()
        object TimedOut : Result()
    }

    /** Blocks until the callback arrives, `/cancel` is requested, [close] is called, or [deadlineMs] passes. */
    fun await(deadlineMs: Long): Result {
        while (true) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) return Result.TimedOut
            val socket = try {
                server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                server.accept()
            } catch (_: SocketTimeoutException) {
                return Result.TimedOut
            } catch (_: SocketException) {
                return Result.Cancelled // closed by cancelLogin()
            }
            try {
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine()
                if (requestLine == null) {
                    socket.close()
                    continue
                }
                while (true) { // drain headers
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = if ('?' in target) target.substringAfter('?') else ""
                VcLog.d(TAG, "loopback request: ${parts.getOrNull(0)} $path")
                when (path) {
                    CodexOAuth.CALLBACK_PATH -> return Result.Callback(socket, parseQuery(query))
                    "/cancel" -> {
                        writeResponse(socket, 200, "Login cancelled")
                        return Result.Cancelled
                    }
                    else -> writeResponse(socket, 404, "Not found")
                }
            } catch (e: IOException) {
                VcLog.w(TAG, "loopback request failed: ${e.message}")
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        private const val TAG = "Auth"

        /**
         * Binds 127.0.0.1:1455. If it's taken, asks whoever holds it to `/cancel` (an older login of ours or a
         * Codex CLI), retries for ~2 s, then falls back to 1457 like codex-rs `bind_server`.
         * Throws [BindException] if neither port can be bound.
         */
        fun bind(primary: Int = CodexOAuth.DEFAULT_PORT, fallback: Int = CodexOAuth.FALLBACK_PORT): LoopbackServer {
            bindWithCancel(primary)?.let { return it }
            VcLog.w(TAG, "port $primary still in use; falling back to $fallback")
            tryBind(fallback)?.let { return LoopbackServer(it) }
            throw BindException(
                "Ports $primary and $fallback are both in use " +
                    "(is another sign-in, or a Codex CLI login, running on this device?)"
            )
        }

        /**
         * Binds exactly [port] (same `/cancel` + retry dance, no fallback). Used to resume a sign-in after the
         * process was killed: the redirect_uri sent to OpenAI names this port, so no other port would do.
         */
        fun bindExact(port: Int): LoopbackServer =
            bindWithCancel(port) ?: throw BindException("Port $port is in use")

        private fun bindWithCancel(port: Int): LoopbackServer? {
            tryBind(port)?.let { return LoopbackServer(it) }
            VcLog.w(TAG, "port $port in use; sending /cancel to its owner")
            sendCancel(port)
            repeat(10) {
                Thread.sleep(200)
                tryBind(port)?.let { return LoopbackServer(it) }
            }
            return null
        }

        private fun tryBind(port: Int): ServerSocket? = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            }
        } catch (_: IOException) {
            null
        }

        private fun sendCancel(port: Int) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000)
                    s.soTimeout = 2000
                    s.getOutputStream().write(
                        "GET /cancel HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    s.getOutputStream().flush()
                    runCatching { s.getInputStream().read(ByteArray(64)) }
                }
            }
        }

        internal fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val k = pair.substringBefore('=')
                val v = if ('=' in pair) pair.substringAfter('=') else ""
                decode(k) to decode(v)
            }

        /** Lenient: a malformed %-escape from a hostile/broken request must not abort the wait. */
        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        private fun writeResponse(socket: Socket, status: Int, body: String) {
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Error" }
                val type = if (body.trimStart().startsWith("<")) "text/html; charset=utf-8" else "text/plain; charset=utf-8"
                val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            } catch (e: IOException) {
                VcLog.w(TAG, "loopback response failed: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}
