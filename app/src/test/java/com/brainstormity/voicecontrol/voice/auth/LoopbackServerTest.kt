package com.brainstormity.voicecontrol.voice.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** The 127.0.0.1 callback listener with real sockets (ports picked free at runtime, not 1455/1457). */
class LoopbackServerTest {
    private val pool = Executors.newCachedThreadPool()

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun get(port: Int, pathAndQuery: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$pathAndQuery").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    private fun awaitAsync(server: LoopbackServer, ms: Long = 10_000): Future<LoopbackServer.Result> =
        pool.submit<LoopbackServer.Result> { server.await(System.currentTimeMillis() + ms) }

    @Test
    fun callback_isParsed_andAnswered_after404sForOtherPaths() {
        val server = LoopbackServer.bind(freePort(), freePort())
        server.use {
            val result = awaitAsync(server)
            assertEquals(404, get(server.port, "/favicon.ico").first)
            val response = pool.submit<Pair<Int, String>> {
                get(server.port, "/auth/callback?code=ac%2F1&state=s%3D1&scope=openid+email")
            }
            val r = result.get(5, TimeUnit.SECONDS) as LoopbackServer.Result.Callback
            assertEquals(mapOf("code" to "ac/1", "state" to "s=1", "scope" to "openid email"), r.params)
            r.respond(200, "<html>ok</html>")
            val (code, body) = response.get(5, TimeUnit.SECONDS)
            assertEquals(200, code)
            assertEquals("<html>ok</html>", body)
        }
    }

    @Test
    fun cancelPath_endsTheWait() {
        LoopbackServer.bind(freePort(), freePort()).use { server ->
            val result = awaitAsync(server)
            assertEquals(200, get(server.port, "/cancel").first)
            assertSame(LoopbackServer.Result.Cancelled, result.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun close_endsTheWaitAsCancelled() {
        val server = LoopbackServer.bind(freePort(), freePort())
        val result = awaitAsync(server)
        Thread.sleep(200)
        server.close()
        assertSame(LoopbackServer.Result.Cancelled, result.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun deadline_timesOut() {
        LoopbackServer.bind(freePort(), freePort()).use { server ->
            assertSame(LoopbackServer.Result.TimedOut, server.await(System.currentTimeMillis() + 300))
        }
    }

    @Test
    fun busyPrimaryPort_fallsBackAfterAskingItsOwnerToCancel() {
        val primary = freePort()
        val fallback = freePort()
        // Someone else holds the primary port and ignores /cancel (like a foreign app would).
        ServerSocket().use { squatter ->
            squatter.reuseAddress = true
            squatter.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), primary))
            val t0 = System.currentTimeMillis()
            LoopbackServer.bind(primary, fallback).use { server ->
                assertEquals(fallback, server.port)
                assertTrue("retries ~2 s before falling back", System.currentTimeMillis() - t0 >= 1500)
            }
        }
    }

    @Test
    fun busyPrimaryPort_heldByAnOlderLogin_isTakenOver() {
        val primary = freePort()
        val old = LoopbackServer.bind(primary, freePort())
        val oldResult = awaitAsync(old)
        // A new attempt sends /cancel to the old one, which then releases the port.
        val resultHolder = pool.submit<LoopbackServer> {
            val r = oldResult.get(5, TimeUnit.SECONDS)
            assertSame(LoopbackServer.Result.Cancelled, r)
            old.close()
            old
        }
        LoopbackServer.bind(primary, freePort()).use { server ->
            assertEquals(primary, server.port)
        }
        resultHolder.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun bindExact_failsWhenThePortStaysBusy() {
        val port = freePort()
        ServerSocket().use { squatter ->
            squatter.reuseAddress = true
            squatter.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            try {
                LoopbackServer.bindExact(port).close()
                fail("expected BindException")
            } catch (_: BindException) {
            }
        }
        LoopbackServer.bindExact(port).use { assertEquals(port, it.port) }
    }

    @Test
    fun malformedEscapes_doNotAbortTheWait() {
        assertEquals(mapOf("code" to "%zz", "state" to "s"), LoopbackServer.parseQuery("code=%zz&state=s"))
    }
}
