package com.brainstormity.voicecontrol.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JevKeyProbeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun probe(key: String = "apikey_test", logs: MutableList<String> = mutableListOf()) =
        JevKeyProbe.probe(key, server.url("/v1/systemone").toString(), log = { logs += it })

    // Real responses seen from api.typesafe.ai (2026-11).
    private val ok = """{"model":"jev-1.14.0","answers":{"ok":{"type":"choice","choice":"yes","confidence":0.9,""" +
        """"probabilities":{"yes":0.95,"no":0.05}}},"usage":{"input_tokens":309,"output_tokens":31}}"""
    private val badKey = """{"detail":{"error_type":"authentication_error","message":"Cannot authenticate with the server. Please check your API key and try again."}}"""
    private val noKey = """{"detail":{"error_type":"authentication_error","message":"Must supply an API key! Check your request and try again."}}"""
    private val validation = """{"detail":[{"type":"missing","loc":["body","questions"],"msg":"Field required"}]}"""

    @Test fun okIsValidAndSendsTinyTextOnlyRequest() {
        server.enqueue(MockResponse().setBody(ok))
        val logs = mutableListOf<String>()
        assertEquals(JevKeyProbe.Result.Valid(200), probe("  Bearer apikey_abc\n def ", logs))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("Bearer apikey_abcdef", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(JevClient.MODEL, body.getString("model"))
        assertEquals("choice", body.getJSONObject("questions").getJSONObject("ok").getString("type"))
        assertFalse(body.has("assets"))   // no image: as cheap as a request gets
        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].startsWith("HTTP 200: {\"model\""))
        assertFalse("never log the key", logs[0].contains("apikey_abcdef"))
    }

    @Test fun unauthorizedAndForbiddenAreInvalid() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(badKey))
        server.enqueue(MockResponse().setResponseCode(403).setBody(noKey))
        assertEquals(
            JevKeyProbe.Result.Invalid(401, "Cannot authenticate with the server. Please check your API key and try again."),
            probe(),
        )
        val r = probe()
        assertTrue(r is JevKeyProbe.Result.Invalid && r.httpCode == 403 && r.detail.startsWith("Must supply"))
    }

    @Test fun validationErrorsMeanAuthPassed() {
        server.enqueue(MockResponse().setResponseCode(422).setBody(validation))
        assertEquals(JevKeyProbe.Result.Valid(422), probe())
        assertEquals(JevKeyProbe.Result.Valid(400), JevKeyProbe.classify(400, "{}"))
    }

    @Test fun serverTroubleIsInconclusive() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down " + "x".repeat(500)))
        val r = probe()
        assertTrue(r.toString(), r is JevKeyProbe.Result.Unexpected && r.httpCode == 503)
        assertEquals(200, (r as JevKeyProbe.Result.Unexpected).detail.length)
        assertTrue(JevKeyProbe.classify(429, "slow down") is JevKeyProbe.Result.Unexpected)
        assertTrue(JevKeyProbe.classify(402, "pay") is JevKeyProbe.Result.Unexpected)
    }

    @Test fun networkFailureIsReported() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val logs = mutableListOf<String>()
        val r = probe(logs = logs)
        assertTrue(r.toString(), r is JevKeyProbe.Result.NetworkError)
        assertEquals(null, r.httpCode)
        assertTrue(logs.single().startsWith("network error"))
    }

    @Test fun maskShowsOnlyTheFirst12Chars() {
        val key = "apikey_0123456789abcdefghijklmnopqrstuvwxyz"
        assertEquals("apikey_01234…", JevKeyProbe.mask(key))
        assertEquals("apikey_01234…", JevKeyProbe.mask("  $key\n"))
        assertEquals("", JevKeyProbe.mask(""))
        assertEquals("", JevKeyProbe.mask("   "))
        // Short strings never show in full either.
        assertEquals("sh…", JevKeyProbe.mask("short"))
        assertEquals("a…", JevKeyProbe.mask("abc"))
        assertEquals("apikey_01234…", JevKeyProbe.mask("apikey_012345"))
    }

    @Test fun normalizeStripsWhitespaceAndBearer() {
        assertEquals("apikey_x", JevKeyProbe.normalize(" apikey_x \n"))
        assertEquals("apikey_xy", JevKeyProbe.normalize("Bearer apikey_x\ny"))
    }
}
