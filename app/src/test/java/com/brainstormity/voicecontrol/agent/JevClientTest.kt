package com.brainstormity.voicecontrol.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JevClientTest {
    private lateinit var server: MockWebServer
    private val img = "SCREENSHOT".toByteArray()

    /** Bodies built by upstream's own ask_jev()/refine_target() (plus our note sentence), see app/src/test/golden/gen_bodies_golden.py. */
    private val golden by lazy {
        JSONObject(javaClass.classLoader!!.getResource("upstream_bodies_golden.json")!!.readText())
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(key: String = "test-key") = JevClient({ key }, server.url("/v1/systemone").toString())

    private val answer = """
        {"answers": {
           "action": {"choice": "TYPE_ENTER", "confidence": 0.42,
                      "probabilities": {"CLICK": 0.30, "TYPE": 0.2, "TYPE_ENTER": 0.42, "BACK": 0.08}},
           "target": {"box": {"left": 0.1, "top": 0.2, "right": 0.3, "bottom": 0.25}, "confidence": 0.55},
           "text": {"choice": "3", "confidence": 0.9, "probabilities": {"3": 0.9, "0": 0.1}},
           "website": {"choice": "1", "confidence": 0.8}
         },
         "usage": {"input_tokens": 2345, "output_tokens": 12}}
    """.trimIndent()

    @Test fun askSendsUpstreamBodyAndHeaders() {
        server.enqueue(MockResponse().setBody(answer))
        val task = golden.getString("task")
        val history = (0 until golden.getJSONArray("history").length()).map { golden.getJSONArray("history").getString(it) }
        val apps = listOf(TaskParser.App("settings", "com.android.settings"), TaskParser.App("chrome", "com.android.chrome"))
        val r = client().ask(task, img, history, TaskParser.textOptions(task), TaskParser.websites(task), apps)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        assertEquals("true", req.getHeader("TypeSafe-Danger-Zone-Images"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        // Byte-for-byte the same JSON (keys in the same order) as upstream's body.
        assertEquals(golden.getJSONArray("compact").getString(0), req.body.readUtf8())
        assertEquals("TYPE_ENTER", r.action!!.choice)
        assertEquals(2345L, r.usage.inputTokens)
    }

    @Test fun minimalAskDropsTypingWebsiteAndAppActions() {
        server.enqueue(MockResponse().setBody(answer))
        client().ask("Open Settings", img, emptyList(), emptyList(), emptyList(), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertEquals(golden.getJSONArray("compact").getString(1), body)
        val q = JSONObject(body).getJSONObject("questions")
        assertEquals(setOf("action", "target"), q.keySet())
        val criteria = q.getJSONObject("action").getJSONObject("criteria").keySet()
        assertTrue("TYPE" !in criteria && "TYPE_ENTER" !in criteria && "OPEN_WEBSITE" !in criteria && "OPEN_APP" !in criteria)
    }

    @Test fun refineSendsUpstreamBody() {
        server.enqueue(MockResponse().setBody(
            """{"answers":{"target":{"box":{"left":0.25,"top":0.5,"right":0.75,"bottom":1.0},"confidence":null}}}"""))
        val r = client().refine(golden.getString("task"), img, emptyList())
        assertEquals(golden.getJSONArray("compact").getString(2), server.takeRequest().body.readUtf8())
        assertEquals(0.0, r.target!!.confidence, 0.0)  // upstream: answer.get("confidence") or 0
        assertEquals(JevUsage(0, 0), r.usage)
    }

    @Test fun parsesAnswers() {
        val r = JevClient.parse(answer)
        assertEquals("TYPE_ENTER", r.action!!.choice)
        assertEquals(0.42, r.action!!.confidence, 1e-9)
        assertEquals(0.30, r.action!!.probabilities["CLICK"]!!, 1e-9)
        assertEquals(Box(0.1, 0.2, 0.3, 0.25), r.target!!.box)
        assertEquals(0.55, r.target!!.confidence, 1e-9)
        assertEquals("3", r.text!!.choice)
        assertEquals("1", r.website!!.choice)
        assertNull(r.app)
        assertEquals(JevUsage(2345, 12), r.usage)
    }

    @Test fun httpErrorIncludesTruncatedBody() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}" + "x".repeat(2000)))
        try {
            client().ask("Open Settings", img, emptyList(), emptyList(), emptyList(), emptyList())
            fail("expected JevException")
        } catch (e: JevException) {
            assertTrue(e.message!!, e.message!!.startsWith("JEV error 401: {\"error\":\"bad key\"}"))
            assertEquals("JEV error 401: ".length + 500, e.message!!.length)
        }
    }

    @Test fun missingKeyFailsBeforeAnyRequest() {
        try {
            client(key = " ").ask("Open Settings", img, emptyList(), emptyList(), emptyList(), emptyList())
            fail("expected JevException")
        } catch (e: JevException) {
            assertTrue(e.message!!.contains("JEV_KEY"))
            assertEquals(0, server.requestCount)
        }
    }
}
