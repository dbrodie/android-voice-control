package com.brainstormity.voicecontrol.voice.live

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Framing of [OpenAiLiveWsTransport] against a MockWebServer playing the public GPT-Live primary WebSocket. */
class OpenAiLiveWsTransportTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val received = LinkedBlockingQueue<JSONObject>()
    @Volatile private var serverSocket: WebSocket? = null
    private val events = CopyOnWriteArrayList<LiveEvent>()
    private val audio = FakeAudio()

    /** What the fake server does on `session.start` (default: answer `session.started`). */
    private var onStart: (WebSocket) -> Unit = { ws ->
        ws.send("""{"type":"session.started","event_id":"event_started_1","session":{"id":"live_123","status":"active","model":"gpt-live-1"}}""")
    }

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            received += json
            when (json.getString("type")) {
                "session.start" -> onStart(webSocket)
                "session.close" -> webSocket.send(
                    """{"type":"session.closed","event_id":"e9","client_event_id":"${json.optString("event_id")}","reason":"close_requested","usage":{"seconds":1.5}}""",
                )
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun transport(config: LiveSessionConfig = LiveSessionConfig(instructions = "Be brief.")) =
        OpenAiLiveWsTransport(
            apiKey = KEY,
            config = config,
            audioFactory = { audio },
            http = client,
            url = server.url("/v1/live/sessions").toString(),
            startTimeoutMs = 5_000,
            closeTimeoutMs = 3_000,
        ).also { t -> t.onEvent = { events += it } }

    private fun connected(config: LiveSessionConfig = LiveSessionConfig(instructions = "Be brief.")): OpenAiLiveWsTransport {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val t = transport(config)
        runBlocking { t.connect() }
        return t
    }

    private fun next(): JSONObject = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no client event")

    private fun nextOfType(type: String): JSONObject {
        while (true) { val e = next(); if (e.getString("type") == type) return e }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test fun sessionStartShapeAndHandshake() {
        // Codex-only voice and ack_filler must not leak into the public schema.
        val t = connected(LiveSessionConfig(instructions = "Be brief.", voice = "cove", ackFiller = true))
        val request = server.takeRequest()
        assertEquals("/v1/live/sessions", request.path)
        assertEquals("Bearer $KEY", request.getHeader("Authorization"))

        val start = next()
        assertEquals("session.start", start.getString("type"))
        assertTrue(start.getString("event_id").isNotEmpty())
        val session = start.getJSONObject("session")
        assertEquals("gpt-live-1", session.getString("model"))
        assertEquals("Be brief.", session.getString("instructions"))
        val format = session.getJSONObject("audio").getJSONObject("format")
        assertEquals("audio/pcm", format.getString("type"))
        assertEquals(24_000, format.getInt("rate"))
        assertEquals("marin", session.getJSONObject("audio").getJSONObject("output").getString("voice"))
        val delegation = session.getJSONObject("delegation")
        assertEquals("client", delegation.getString("type"))
        assertFalse(delegation.has("ack_filler"))

        assertEquals("live_123", t.callId)
        assertEquals(LiveConnectionState.CONNECTED, t.connection.value)
        assertTrue(audio.started)
        waitFor("Started event") { events.any { it is LiveEvent.Started && it.sessionId == "live_123" } }
    }

    @Test fun publicVoiceIsKept() {
        val start = LiveProtocol.publicSessionStart(LiveSessionConfig("x", voice = "cedar", model = "gpt-live-1"), "e1")
        assertEquals("cedar", start.getJSONObject("session").getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertEquals("gpt-live-1", LiveProtocol.publicModel("gpt-live-1-codex"))
        assertEquals("gpt-live-1", LiveProtocol.publicModel("gpt-realtime"))
        assertEquals("gpt-live-1-2026-10-01", LiveProtocol.publicModel("gpt-live-1-2026-10-01"))
    }

    @Test fun micPcmIsSentAsBase64Append() {
        connected()
        nextOfType("session.start")
        val pcm = ByteArray(1920) { (it * 7).toByte() } // 40 ms at 24 kHz
        audio.mic!!(pcm)
        val append = nextOfType("session.input_audio.append")
        assertArrayEquals(pcm, Base64.getDecoder().decode(append.getString("audio")))
        assertEquals(setOf("type", "audio"), append.keySet())
        // Odd byte counts are trimmed to whole 16-bit samples.
        audio.mic!!(ByteArray(5) { 1 })
        assertEquals(4, Base64.getDecoder().decode(nextOfType("session.input_audio.append").getString("audio")).size)
    }

    @Test fun outputAudioDeltaIsDecodedAndPlayed() {
        connected()
        val pcm = byteArrayOf(0, 0, -128, 0, -128, 0, 0, -128, 0, 0, -1, 127, 0, -128, 0, -128)
        serverSocket!!.send(
            JSONObject().put("type", "session.output_audio.delta").put("delta", Base64.getEncoder().encodeToString(pcm)).toString(),
        )
        waitFor("playback") { audio.played.isNotEmpty() }
        assertArrayEquals(pcm, audio.played.first())
        waitFor("audio event") { events.any { it is LiveEvent.Other && it.type == "session.output_audio.delta" } }
        // The documented example payload decodes too.
        assertArrayEquals(
            Base64.getDecoder().decode("AACAAIAAAIAAAP9/AIAAgA=="),
            LiveProtocol.outputAudio(JSONObject("""{"type":"session.output_audio.delta","delta":"AACAAIAAAIAAAP9/AIAAgA=="}""")),
        )
    }

    @Test fun appendMapsToCommentaryAndThinking() {
        val t = connected()
        nextOfType("session.start")

        assertTrue(t.append("Wi-Fi is on.", LiveChannel.SPEAKABLE, "del_abc123"))
        val commentary = next()
        assertEquals("session.commentary.append", commentary.getString("type"))
        assertEquals("del_abc123", commentary.getString("delegation_id"))
        assertEquals("Wi-Fi is on.", commentary.getString("content"))
        assertTrue(commentary.getString("event_id").isNotEmpty())

        assertTrue(t.append("Step 2: tap", LiveChannel.COMMENTARY, null))
        val thinking = next()
        assertEquals("session.thinking.append", thinking.getString("type"))
        assertTrue("delegation_id must be present and null", thinking.has("delegation_id") && thinking.isNull("delegation_id"))
        assertEquals("Step 2: tap", thinking.getString("content"))

        // Long text is chunked to <= 500 UTF-8 bytes per append, in order, with unique event ids.
        val long = "ä".repeat(400) + "x".repeat(300) // 1100 bytes
        assertTrue(t.append(long, LiveChannel.SPEAKABLE, "del_abc123"))
        val parts = (1..3).map { next() }
        assertTrue(parts.all { it.getString("type") == "session.commentary.append" && it.getString("delegation_id") == "del_abc123" })
        assertTrue(parts.all { it.getString("content").toByteArray().size <= 500 })
        assertEquals(long, parts.joinToString("") { it.getString("content") })
        assertEquals(3, parts.map { it.getString("event_id") }.toSet().size)
    }

    @Test fun delegationAndTranscriptsAreParsed() {
        connected()
        val ws = serverSocket!!
        ws.send("""{"type":"session.input_transcript.delta","event_id":"e1","delta":"Open settings","start_ms":1600,"end_ms":3400}""")
        ws.send("""{"type":"session.delegation.created","event_id":"e2","offset_ms":3600,"delegation":{"id":"del_abc123","type":"delegation","target":"client"}}""")
        ws.send("""{"type":"session.output_transcript.delta","event_id":"e3","delta":"On it.","start_ms":3700,"end_ms":4200}""")
        ws.send("""{"type":"session.delegation.created","event_id":"e4","offset_ms":3600,"delegation":{"id":"resp_1","type":"delegation","target":"responses","response_id":"resp_1"}}""")
        waitFor("events") { events.count { it !is LiveEvent.Started } >= 4 }
        val list = events.filter { it !is LiveEvent.Started }
        assertEquals(LiveEvent.Transcript(Role.USER, "Open settings", final = false), list[0])
        assertEquals(LiveEvent.Delegation("del_abc123", ""), list[1])
        assertEquals(LiveEvent.Transcript(Role.ASSISTANT, "On it.", final = false), list[2])
        assertEquals(LiveEvent.Other("session.delegation.created"), list[3]) // not ours to answer
    }

    @Test fun gracefulCloseWaitsForSessionClosed() {
        val t = connected()
        nextOfType("session.start")
        runBlocking { t.close() }
        val close = nextOfType("session.close")
        assertTrue(close.getString("event_id").isNotEmpty())
        assertEquals(LiveConnectionState.CLOSED, t.connection.value)
        assertTrue(audio.released)
        val closed = events.filterIsInstance<LiveEvent.Closed>()
        assertEquals(listOf(LiveEvent.Closed("close_requested")), closed)
        assertFalse(t.append("late", LiveChannel.SPEAKABLE, null))
    }

    @Test fun invalidKeyHandshakeIsReadable401() {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(
                """{"error":{"message":"Incorrect API key provided: $KEY. You can find your API key at https://platform.openai.com/account/api-keys.","type":"invalid_request_error","code":"invalid_api_key","param":null},"status":401}""",
            ),
        )
        val t = transport()
        try {
            runBlocking { t.connect() }
            fail("connect() should throw")
        } catch (e: LiveException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!, e.message!!.startsWith("Invalid OpenAI API key"))
            assertFalse(e.message!!.contains(KEY))
        }
        assertEquals(LiveConnectionState.FAILED, t.connection.value)
        assertFalse(audio.started)
        assertNull(LiveProtocol.errorBodyMessage("", KEY))
        assertEquals("Incorrect API key provided: [redacted].", LiveProtocol.errorBodyMessage("""{"error":{"message":"Incorrect API key provided: $KEY."}}""", KEY))
    }

    @Test fun errorBeforeStartedFailsConnect() {
        onStart = { ws ->
            ws.send("""{"type":"error","event_id":"evt_error_001","error":{"type":"invalid_request_error","code":"unknown_parameter","message":"Unknown parameter: 'session.voice'.","param":"session.voice","client_event_id":"x"}}""")
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val t = transport()
        try {
            runBlocking { t.connect() }
            fail("connect() should throw")
        } catch (e: LiveException) {
            assertEquals("GPT-Live rejected the session: Unknown parameter: 'session.voice'.", e.message)
        }
    }

    @Test fun bargeInFlushesQueuedAudioOnlyForRealSpeech() {
        connected()
        val ws = serverSocket!!
        ws.send(JSONObject().put("type", "session.output_audio.delta").put("delta", Base64.getEncoder().encodeToString(ByteArray(4800))).toString())
        waitFor("playback") { audio.played.isNotEmpty() }
        audio.queued = 2_000
        Thread.sleep(OpenAiLiveWsTransport.BARGE_IN_QUIET_MS + 100)
        ws.send("""{"type":"session.input_transcript.delta","event_id":"e1","delta":"mm","start_ms":1,"end_ms":2}""")
        Thread.sleep(200)
        assertEquals("a backchannel must not flush", 0, audio.flushes)
        ws.send("""{"type":"session.input_transcript.delta","event_id":"e2","delta":" wait stop","start_ms":2,"end_ms":3}""")
        waitFor("flush") { audio.flushes == 1 }
        waitFor("SpeechCleared") { events.contains(LiveEvent.SpeechCleared) }
    }

    @Test fun resamplerAndPcmHelpers() {
        val tone = ShortArray(4800) { (8000 * kotlin.math.sin(it * 2 * Math.PI * 440 / 48_000)).toInt().toShort() }
        val down = LinearResampler(48_000, 24_000)
        val out = down.process(tone.copyOfRange(0, 2400)).size + down.process(tone.copyOfRange(2400, 4800)).size
        assertTrue("48k->24k of 4800 samples gave $out", out in 2399..2401)
        val up = LinearResampler(16_000, 24_000)
        val upOut = up.process(ShortArray(640)).size + up.process(ShortArray(640)).size
        assertTrue("16k->24k of 1280 samples gave $upOut", upOut in 1919..1921)
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345)
        assertArrayEquals(samples, Pcm16.toShorts(Pcm16.toBytes(samples)))
        assertEquals(1, Pcm16.toBytes(shortArrayOf(1))[0].toInt())
    }

    @Test fun backendDecisionTable() {
        val r = com.brainstormity.voicecontrol.voice.VoiceBackends
        assertEquals("chatgpt", r.resolve("auto", signedIn = true, hasKey = true))
        assertEquals("openai_key", r.resolve("auto", signedIn = false, hasKey = true))
        assertEquals("", r.resolve("auto", signedIn = false, hasKey = false))
        assertEquals("", r.resolve("chatgpt", signedIn = false, hasKey = true))
        assertEquals("chatgpt", r.resolve("chatgpt", signedIn = true, hasKey = false))
        assertEquals("openai_key", r.resolve("openai_key", signedIn = true, hasKey = true))
        assertEquals("", r.resolve("openai_key", signedIn = true, hasKey = false))
        assertEquals("chatgpt", r.resolve("something-else", signedIn = true, hasKey = false))
        assertNotNull(r.NOT_CONFIGURED)
    }

    private class FakeAudio : PcmAudio {
        @Volatile var mic: ((ByteArray) -> Unit)? = null
        @Volatile var started = false
        @Volatile var released = false
        @Volatile var flushes = 0
        @Volatile var queued = 0L
        val played = CopyOnWriteArrayList<ByteArray>()
        override fun start(onMic: (ByteArray) -> Unit) { started = true; mic = onMic }
        override fun play(pcm: ByteArray) { played += pcm }
        override fun flushPlayback() { flushes++; queued = 0 }
        override fun queuedMs(): Long = queued
        override fun setMicEnabled(enabled: Boolean) {}
        override fun release() { released = true }
    }

    companion object {
        private const val KEY = "sk-test-not-a-real-key"
    }
}
