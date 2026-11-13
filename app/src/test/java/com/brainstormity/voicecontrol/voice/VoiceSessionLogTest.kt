package com.brainstormity.voicecontrol.voice

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.ui.runs.RunFormat
import com.brainstormity.voicecontrol.voice.live.LiveChannel
import com.brainstormity.voicecontrol.voice.live.LiveConnectionState
import com.brainstormity.voicecontrol.voice.live.LiveEvent
import com.brainstormity.voicecontrol.voice.live.LiveProtocol
import com.brainstormity.voicecontrol.voice.live.LiveSession
import com.brainstormity.voicecontrol.voice.live.LiveTaskBridge
import com.brainstormity.voicecontrol.voice.live.LiveUsage
import com.brainstormity.voicecontrol.voice.live.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Voice pricing, usage parsing, and the voice session records (on a temp dir). */
class VoiceSessionLogTest {
    private lateinit var tmp: File
    private lateinit var log: VoiceSessionLog
    private var clock = 0L

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("voice-test").toFile()
        log = VoiceSessionLog(File(tmp, "voice"))
        clock = 1_763_000_000_000L // a fixed instant
        VoiceSessionLog.active = null
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
        VoiceSessionLog.active = null
    }

    // ------------------------------------------------------------------ pricing

    @Test
    fun pricing() {
        assertEquals(0.05, VoicePricing.USD_PER_SESSION_MINUTE, 0.0)
        assertEquals(0.05, VoicePricing.costUsd(60_000, 0.05), 1e-12)
        assertEquals(0.011, VoicePricing.costUsd(13_200, 0.05), 1e-12)   // per second, not rounded up
        assertEquals(0.075, VoicePricing.costUsd(90_000, 0.05), 1e-12)   // the docs' worked example
        assertEquals(0.0, VoicePricing.costUsd(0, 0.05), 0.0)
        assertEquals(0.02, VoicePricing.costUsd(60_000, 0.02), 1e-12)    // the record's own rate is used
    }

    @Test
    fun billedTime() {
        assertEquals(30_500L, VoicePricing.billedMs(30_500, serverFinal = true, connectedMs = 31_000))
        assertEquals(62_000L, VoicePricing.billedMs(30_200, serverFinal = false, connectedMs = 62_000))
        assertEquals(42_200L, VoicePricing.billedMs(42_200, serverFinal = false, connectedMs = 10_700))
        assertEquals(6_000L, VoicePricing.billedMs(null, serverFinal = false, connectedMs = 6_000))
        assertEquals(0L, VoicePricing.billedMs(null, serverFinal = false, connectedMs = null))
        assertEquals(0L, VoicePricing.billedMs(0, serverFinal = false, connectedMs = null))
        assertEquals("server", VoicePricing.billedFrom(42_200, false, 10_700))
        assertEquals("clock", VoicePricing.billedFrom(30_200, false, 62_000))
        assertEquals("none", VoicePricing.billedFrom(null, false, null))
    }

    // ------------------------------------------------------------------ usage events

    @Test
    fun parsesCodexUsageUpdated() {
        // Captured from the ChatGPT (Codex) route on 2026-11-13.
        val payload = """{"type":"session.usage.updated","usage":{"audio_duration_ms":13200,"backend_model_usage":[]},""" +
            """"usage_limit":{"status":null,"reset_seconds":null}}"""
        val e = LiveProtocol.parseEvent(payload) as LiveEvent.Usage
        assertEquals(13_200L, e.usage.durationMs)
        assertEquals("audio_duration_ms", e.usage.source)
        assertTrue(e.usage.hasLimit)
        assertNull(e.usage.limitStatus)
        assertNull(e.usage.limitResetSeconds)
    }

    @Test
    fun parsesCodexUsageLimitHit() {
        val e = LiveProtocol.parseEvent(
            """{"type":"session.usage.updated","usage":{"audio_duration_ms":5000},"usage_limit":{"status":"exceeded","reset_seconds":3600}}""",
        ) as LiveEvent.Usage
        assertEquals("exceeded", e.usage.limitStatus)
        assertEquals(3600L, e.usage.limitResetSeconds)
    }

    @Test
    fun parsesPublicUsageAndClose() {
        val u = LiveProtocol.parseEvent(
            """{"type":"session.usage.updated","event_id":"event_usage_1","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}""",
        ) as LiveEvent.Usage
        assertEquals(12_000L, u.usage.durationMs)
        assertEquals("seconds", u.usage.source)
        assertFalse(u.usage.hasLimit)

        val c = LiveProtocol.parseEvent("""{"type":"session.closed","reason":"close_requested","usage":{"seconds":30.5}}""")
            as LiveEvent.Closed
        assertEquals("close_requested", c.reason)
        assertEquals(30_500L, c.usage?.durationMs)

        // A session.closed without usage (and the captured Codex close with it).
        val c2 = LiveProtocol.parseEvent("""{"type":"session.closed","reason":"client_request"}""") as LiveEvent.Closed
        assertNull(c2.usage)
        val c3 = LiveProtocol.parseEvent( // captured on the Codex route, 2026-11-13
            """{"type":"session.closed","reason":"client_request","usage":{"audio_duration_ms":145000,"backend_model_usage":[]}}""",
        ) as LiveEvent.Closed
        assertEquals("client_request", c3.reason)
        assertEquals(145_000L, c3.usage?.durationMs)
        assertFalse(c3.usage!!.hasLimit)
        // A usage event without any usage is just "other".
        assertTrue(LiveProtocol.parseEvent("""{"type":"session.usage.updated"}""") is LiveEvent.Other)
    }

    // ------------------------------------------------------------------ recorder -> record -> parse

    private fun recorder(backend: String = "chatgpt", model: String? = "gpt-live-1-codex") =
        VoiceSessionRecorder(log, backend, model, now = { clock })

    @Test
    fun recordsAChatGptSession() {
        val rec = recorder()
        assertEquals(rec.id, VoiceSessionLog.active)
        // Written at once, so even a crash during connect leaves a record.
        assertTrue(File(log.root, "${rec.id}.json").isFile)

        clock += 1_000
        rec.connected("rtc_abc")
        clock += 14_000
        rec.onEvent(LiveEvent.Usage(LiveUsage(13_200, "audio_duration_ms", null, null, hasLimit = true)))
        rec.turn(Role.ASSISTANT)
        rec.turn(Role.USER)
        rec.turn(Role.ASSISTANT)
        rec.tasks(listOf(LiveTaskBridge.Delegated("Open Settings and turn on Bluetooth", "20261113-144600", "done")))
        clock += 5_000
        rec.onEvent(LiveEvent.Usage(LiveUsage(19_500, "audio_duration_ms", null, null, hasLimit = true)))
        rec.onEvent(LiveEvent.Closed("client_request"))
        assertTrue(rec.isFinished)
        assertNull(VoiceSessionLog.active)

        val raw = JSONObject(File(log.root, "${rec.id}.json").readText())
        assertEquals(1, raw.getInt("version"))
        assertEquals(19_500, raw.getLong("audio_duration_ms"))
        assertEquals(2, raw.getInt("usage_updates"))
        assertFalse(raw.getBoolean("usage_final"))
        assertEquals("server", raw.getString("billed_from"))
        assertEquals(19.5, raw.getDouble("billed_seconds"), 1e-9)
        assertEquals("equivalent", raw.getString("cost_kind"))
        assertEquals(0.01625, raw.getDouble("cost_usd"), 1e-9)
        assertEquals(0.05, raw.getDouble("rate_usd_per_minute"), 0.0)
        assertTrue(raw.getJSONObject("usage_limit").isNull("status"))

        val s = log.load(rec.id)!!
        assertEquals("chatgpt", s.backend)
        assertEquals("gpt-live-1-codex", s.model)
        assertEquals("rtc_abc", s.callId)
        assertEquals(20.0, s.seconds, 1e-9)
        assertEquals(19.0, s.connectedSeconds!!, 1e-9)
        assertEquals(19_500L, s.billedMs)
        assertTrue(s.billedFromServer)
        assertEquals(0.01625, s.costUsd, 1e-12)
        assertTrue(s.isPlan)
        assertEquals(1, s.userTurns)
        assertEquals(2, s.assistantTurns)
        assertEquals(listOf(VoiceTask("Open Settings and turn on Bluetooth", "20261113-144600", "done")), s.tasks)
        assertEquals("client_request", s.endReason)
        assertEquals("ended", s.status)
        assertEquals("ChatGPT plan · equiv. \$0.016", RunFormat.voiceCost(s))
        assertEquals(s, VoiceSessionLog.sessionForRun(log.list(), "20261113-144600"))
        assertNull(VoiceSessionLog.sessionForRun(log.list(), "20261113-999999"))
    }

    @Test
    fun recordsAFailedApiKeySession() {
        val rec = recorder("openai_key", "gpt-live-1")
        clock += 800
        rec.fail("Invalid OpenAI API key (401). Check the key in settings.")
        // A late close doesn't replace the reason or error.
        rec.onEvent(LiveEvent.Closed("close_requested"))
        val s = log.load(rec.id)!!
        assertEquals("error", s.status)
        assertEquals("error", s.endReason)
        assertEquals("Invalid OpenAI API key (401). Check the key in settings.", s.error)
        assertNull(s.connectedSeconds)
        assertEquals(0L, s.billedMs)          // never connected: nothing billed
        assertEquals("OpenAI API · \$0.000", RunFormat.voiceCost(s))
        assertFalse(s.isPlan)
    }

    @Test
    fun finalUsageFromCloseAndClockFallback() {
        // Official route: the final usage rides on session.closed.
        val a = recorder("openai_key", "gpt-live-1")
        a.connected("live_1")
        clock += 31_000
        a.onEvent(LiveEvent.Usage(LiveUsage(12_000, "seconds")))
        // What the transports do: the close's usage as a final Usage event, then the Closed.
        a.onEvent(LiveEvent.Usage(LiveUsage(30_500, "seconds"), final = true))
        a.onEvent(LiveEvent.Closed("close_requested"))
        val sa = log.load(a.id)!!
        assertTrue(sa.usageFinal)
        assertEquals(31.0, sa.connectedSeconds!!, 1e-9)
        assertEquals(30_500L, sa.billedMs)   // the final server figure wins over the (longer) clock
        assertEquals("server", sa.billedFrom)

        // ChatGPT route: the last snapshot lags (updates every ~15 s, no usage on close), so the clock is used.
        clock += 1_000
        val c = recorder()
        c.connected("rtc_lag")
        clock += 62_000
        c.onEvent(LiveEvent.Usage(LiveUsage(30_200, "audio_duration_ms")))
        c.onEvent(LiveEvent.Closed("audio connection failed"))
        val sc = log.load(c.id)!!
        assertEquals(62_000L, sc.billedMs)
        assertEquals("clock", sc.billedFrom)

        // No usage at all (very short call): estimated from the connected time.
        clock += 2_000
        val b = recorder()
        b.connected("rtc_2")
        clock += 6_000
        b.onEvent(LiveEvent.Closed("client_request"))
        val s = log.load(b.id)!!
        assertFalse(s.billedFromServer)
        assertEquals(6_000L, s.billedMs)
        assertTrue(RunFormat.voiceMetrics(s).contains("(est.)"))
    }

    @Test
    fun runningRecordAndInterrupted() {
        val rec = recorder()
        rec.connected("rtc")
        clock += 9_000
        rec.tick()
        val live = log.list().single()
        assertTrue(live.isActive)
        assertEquals("active", live.status)
        assertNull(live.finished)
        assertEquals(9.0, live.seconds, 1e-9)
        // The app died: the record is still "open" but no longer active.
        VoiceSessionLog.active = null
        assertEquals("interrupted", log.list().single().status)
    }

    @Test
    fun rateStaysWithTheRecord() {
        val text = """{"version":1,"started":"2026-11-13T10:00:00","finished":"2026-11-13T10:01:00","seconds":60,
            "connected_seconds":59,"backend":"openai_key","audio_duration_ms":60000,"rate_usd_per_minute":0.08}"""
        val s = VoiceSessionLog.parse("20261113-100000", text)!!
        assertEquals(0.08, s.costUsd, 1e-12)
        // A record without a rate (or most fields) still parses, at today's price.
        val bare = VoiceSessionLog.parse("20261113-100001", """{"audio_duration_ms":30000}""")!!
        assertEquals("2026-11-13T10:00:01", bare.started)
        assertEquals(0.025, bare.costUsd, 1e-12)
        assertNull(VoiceSessionLog.parse("x", "not json"))
    }

    @Test
    fun totalsAndPrune() {
        fun write(id: String, ms: Long, backend: String) = log.write(id, JSONObject()
            .put("started", VoiceSessionLog.startedFromId(id)).put("finished", "x").put("backend", backend)
            .put("audio_duration_ms", ms).put("rate_usd_per_minute", 0.05))
        write("20261113-100000", 60_000, "chatgpt")
        write("20261113-110000", 30_000, "openai_key")
        write("20261113-110000-2", 30_000, "openai_key")
        File(log.root, "junk.json").writeText("{}")          // not an id: ignored
        File(log.root, "20261113-120000.json").writeText("{") // broken: skipped
        val all = log.list()
        assertEquals(listOf("20261113-110000-2", "20261113-110000", "20261113-100000"), all.map { it.id })
        val t = VoiceSessionLog.totals(all)
        assertEquals(3, t.sessions)
        assertEquals(120_000L, t.billedMs)
        assertEquals(2.0, t.minutes, 1e-12)
        assertEquals(0.10, t.costUsd, 1e-12)

        log.prune(keep = 2)
        assertFalse(File(log.root, "20261113-100000.json").exists())
        assertTrue(File(log.root, "junk.json").exists())
        // newId never reuses an existing file's id
        assertTrue(log.newId(clock).matches(VoiceSessionLog.ID))
    }

    @Test
    fun recorderKeepsTheLast100() {
        for (i in 0 until 105) {
            log.write(String.format("20250101-%06d", i), JSONObject().put("finished", "x"))
        }
        recorder()
        assertEquals(VoiceSessionLog.KEEP, log.root.listFiles { f -> f.name.endsWith(".json") }!!.size)
        assertFalse(File(log.root, "20250101-000000.json").exists())
    }

    @Test
    fun formats() {
        assertEquals("\$0.011", RunFormat.usdVoice(0.011))
        assertEquals("<\$0.001", RunFormat.usdVoice(0.0002))
        assertEquals("\$0.000", RunFormat.usdVoice(0.0))
        assertEquals("0.2", RunFormat.minutes(13_200))
        assertEquals("12.5", RunFormat.minutes(750_000))
    }

    // ------------------------------------------------------------------ bridge: delegations -> run ids

    private class FakeTasks : TaskControllerApi {
        val flow = MutableStateFlow<AgentState>(AgentState.Idle)
        override val state: StateFlow<AgentState> = flow
        var running = false
        var onRun: (String) -> Unit = {}
        override fun runTask(task: String) { running = true; onRun(task) }
        override fun stop() {}
        override val isRunning: Boolean get() = running
    }

    private class FakeSession : LiveSession {
        override val connection: StateFlow<LiveConnectionState> = MutableStateFlow(LiveConnectionState.CONNECTED)
        override val callId: String? = "x"
        override var onEvent: ((LiveEvent) -> Unit)? = null
        override suspend fun connect() {}
        override fun append(text: String, channel: LiveChannel, delegationId: String?) = true
        override fun send(event: JSONObject) = true
        override fun setMicEnabled(enabled: Boolean) {}
        override suspend fun close() {}
    }

    @Test
    fun bridgeRecordsDelegationsWithTheirRun() {
        var lastRun: String? = "20261113-090000"  // an older run, not ours
        val tasks = FakeTasks()
        tasks.onRun = { task ->
            // TaskController creates the run folder and finishes synchronously in this fake.
            lastRun = "20261113-144600"
            tasks.running = false
            tasks.flow.value = AgentState.Finished(task, "done", "Bluetooth is on", 3, 0.001)
        }
        val bridge = LiveTaskBridge(FakeSession(), tasks, CoroutineScope(Dispatchers.Unconfined), lastRunId = { lastRun })
        var changes = 0
        bridge.onDelegatedChanged = { changes++ }
        bridge.onDelegation("d1", "Open Settings and turn on Bluetooth")
        bridge.onDelegation(null, "  ")
        tasks.running = true
        bridge.onDelegation("d2", "Open the camera")      // busy
        bridge.cancel()
        assertEquals(
            listOf(
                LiveTaskBridge.Delegated("Open Settings and turn on Bluetooth", "20261113-144600", "done"),
                LiveTaskBridge.Delegated("", null, "empty"),
                LiveTaskBridge.Delegated("Open the camera", null, "busy"),
            ),
            bridge.delegated,
        )
        assertTrue(changes >= 3)
    }

    @Test
    fun bridgeTaskThatMakesNoRunHasNoRunId() {
        val tasks = FakeTasks()
        tasks.onRun = { task ->
            tasks.running = false  // e.g. no JEV key: finishes at once without a run folder
            tasks.flow.value = AgentState.Finished(task, "error", "Add your JEV API key", 0, 0.0)
        }
        val bridge = LiveTaskBridge(FakeSession(), tasks, CoroutineScope(Dispatchers.Unconfined), lastRunId = { "20261113-090000" })
        bridge.onDelegation("d1", "Open Settings")
        assertEquals(LiveTaskBridge.Delegated("Open Settings", null, "error"), bridge.delegated.single())
        assertNotNull(bridge.delegated)
    }
}
