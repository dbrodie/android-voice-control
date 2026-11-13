package com.brainstormity.voicecontrol.ui.runs

import com.brainstormity.voicecontrol.agent.RunReport
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
import java.text.SimpleDateFormat
import java.util.Locale

/** Run reports as written on the emulator (copied into src/test/resources/runs) and the Trash on a temp dir. */
class RunStoreTest {
    private lateinit var tmp: File
    private lateinit var root: File
    private var active: String? = null
    private lateinit var store: RunStore

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource("runs/$name")!!.readText()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("runs-test").toFile()
        root = File(tmp, "runs").apply { mkdirs() }
        active = null
        store = RunStore(root) { active }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun addRun(id: String, json: String, vararg images: String): File {
        val d = File(root, id).apply { mkdirs() }
        File(d, "report.json").writeText(json)
        images.forEach { File(d, it).writeBytes(byteArrayOf(1, 2, 3)) }
        return d
    }

    private fun report(status: String, input: Long, output: Long, cost: Double?, steps: Int = 1): String {
        val stepJson = (1..steps).joinToString(",") {
            """{"n": $it, "action": "CLICK", "tokens": {"input_tokens": ${input / steps}, "output_tokens": 0}}"""
        }
        return """{"task": "t-$status", "status": "$status", "message": "m", "started": "2026-11-13T10:00:00",
            "steps": [$stepJson], "input_tokens": $input, "output_tokens": $output""" +
            (if (cost != null) """, "cost_usd": $cost""" else "") + "}"
    }

    // ------------------------------------------------------------------ parsing real reports

    @Test
    fun parsesRealDoneReport() {
        val r = RunStore.parse("20261113-105502", resource("report_done.json"))!!
        assertEquals("Open Settings and turn off Dark theme", r.task)
        assertEquals("done", r.status)
        assertEquals("done", r.displayStatus)
        assertEquals("JEV says the task is finished.", r.message)
        assertNull(r.warning)
        assertEquals("2026-11-13T10:55:02", r.started)
        assertEquals(19.9, r.seconds!!, 1e-9)
        assertEquals("android", r.device)
        assertEquals("sdk_gphone64_arm64", r.deviceName)
        assertEquals("the settings app", r.opened)
        assertEquals(8286L, r.inputTokens)
        assertEquals(600L, r.outputTokens)
        assertEquals(8886L, r.tokens)
        assertEquals(3.480120e-4, r.costUsd, 1e-12)
        assertEquals(2, r.steps.size)

        val s1 = r.steps[0]
        assertEquals(1, s1.n)
        assertEquals("CLICK", s1.action)
        assertEquals("at (550, 1848)", s1.detail)
        assertEquals(0.96, s1.confidence!!, 1e-9)
        assertEquals("CLICK" to 0.96, s1.options.first())
        assertEquals("SCROLL_DOWN" to 0.03, s1.options[1])
        assertEquals(15, s1.options.size)
        assertEquals(TargetBox(0.06, 0.74, 0.96, 0.8), s1.targetBox)
        assertEquals(0.85, s1.targetConfidence!!, 1e-9)
        assertEquals("01-CLICK.jpg", s1.image)
        assertEquals(0.8, s1.jevSeconds!!, 1e-9)
        assertEquals(4135L, s1.inputTokens)
        assertEquals(300L, s1.outputTokens)
        assertEquals(1.7367e-4, s1.costUsd, 1e-12)
        assertEquals("Executed.", s1.result)
        assertEquals(4.7, s1.waited!!, 1e-9)
        assertEquals(2.0, s1.lateBy!!, 1e-9)
        assertEquals(true, s1.reacted)
        assertEquals(9.1, s1.seconds!!, 1e-9)
        assertNull(s1.zoomNote)

        val s2 = r.steps[1]
        assertEquals("DONE", s2.action)
        assertNull(s2.targetBox)
        assertEquals("Task finished.", s2.result)
        assertNull(s2.waited)
        // The run's cost is the sum of its steps' costs.
        assertEquals(r.costUsd, r.steps.sumOf { it.costUsd }, 1e-12)
    }

    @Test
    fun parsesRealUnsureReportAndSplitsTheZoomNote() {
        val r = RunStore.parse("20261113-102904", resource("report_unsure_zoom.json"))!!
        assertEquals("unsure", r.status)
        assertTrue(r.message.startsWith("The screen hasn't changed for 5 steps"))
        assertEquals(7, r.steps.size)
        assertEquals(33994L + 2006L, r.tokens)
        val zoomed = r.steps[1]
        assertEquals("Zoomed in to find exactly where to tap (location confidence 85% → 81%).", zoomed.zoomNote)
        assertFalse(zoomed.why.contains("Zoomed"))
        assertTrue(zoomed.why.endsWith("to move toward the goal."))
        assertEquals("Executed, but nothing changed on screen.", r.steps[0].result)
        assertEquals(3, r.steps.count { it.zoomNote != null })
    }

    @Test
    fun toleratesMissingFieldsAndHalfWrittenReports() {
        assertNull(RunStore.parse("20261113-100000", """{"task": "Open Sett"""))
        assertNull(RunStore.parse("20261113-100000", ""))

        // Just started: only the first save() happened.
        val started = RunStore.parse("20261113-100000", """{"task": "x", "status": "running", "steps": []}""")!!
        assertEquals("interrupted", started.displayStatus)  // not the active run
        assertFalse(started.isActive)
        val live = RunStore.parse("20261113-100000", """{"task": "x", "status": "running"}""", isActive = true)!!
        assertEquals("running", live.displayStatus)
        assertTrue(live.isActive)
        assertEquals("2026-11-13T10:00:00", live.started)  // from the folder name
        assertEquals(0.0, live.costUsd, 0.0)

        // No cost recorded (old upstream reports): input tokens x $0.042 per million.
        val old = RunStore.parse("20261113-100000", """{"task": "x", "status": "done", "input_tokens": 1000000,
            "output_tokens": 5, "warning": null, "steps": [{"n": 1, "tokens": {"input_tokens": 1000000}}]}""")!!
        assertEquals(0.042, old.costUsd, 1e-12)
        assertEquals(0.042, old.steps[0].costUsd, 1e-12)
        assertNull(old.warning)
        assertEquals("?", old.steps[0].action)
        assertNull(old.steps[0].confidence)
        assertTrue(old.steps[0].options.isEmpty())

        val warned = RunStore.parse("20261113-100000", """{"status": "done", "warning": "Tabs left open."}""")!!
        assertEquals("Tabs left open.", warned.warning)
    }

    // ------------------------------------------------------------------ list / totals

    @Test
    fun listsNewestFirstAndSkipsJunk() {
        addRun("20261113-100000", report("done", 1000, 10, 0.001))
        addRun("20261113-110000", report("unsure", 2000, 20, 0.002))
        addRun("20261113-110000-2", report("error", 3000, 30, 0.003))
        addRun("20261113-120000", "{ half a rep")          // being written / broken: skipped
        File(root, "not-a-run").mkdirs()                     // not a run folder
        File(root, "20261113-130000").mkdirs()               // no report.json yet
        val ids = store.list().map { it.id }
        assertEquals(listOf("20261113-110000-2", "20261113-110000", "20261113-100000"), ids)
        assertEquals(0, store.count(trash = true))
    }

    @Test
    fun totals() {
        addRun("20261113-100000", report("done", 1000, 10, 0.001))
        addRun("20261113-100001", report("done", 1000, 10, 0.001))
        addRun("20261113-100002", report("unsure", 2000, 20, 0.002))
        addRun("20261113-100003", report("error", 3000, 30, null))     // cost from tokens
        addRun("20261113-100004", report("running", 500, 5, 0.0005))   // the live run: not finished yet
        active = "20261113-100004"
        val t = RunStore.totals(store.list())
        assertEquals(5, t.runs)
        assertEquals(0.001 + 0.001 + 0.002 + 3000 * 0.042 / 1e6 + 0.0005, t.costUsd, 1e-12)
        assertEquals(1010L + 1010 + 2020 + 3030 + 505, t.tokens)
        assertEquals(4, t.ended)
        assertEquals(2, t.done)
        assertEquals(0.5, t.successRate!!, 1e-12)
        assertNull(RunStore.totals(emptyList()).successRate)
    }

    // ------------------------------------------------------------------ trash / restore / delete forever

    @Test
    fun trashRestoreAndDeleteForever() {
        addRun("20261113-100000", report("done", 1, 0, 0.0), "01-CLICK.jpg")
        addRun("20261113-110000", report("stopped", 1, 0, 0.0), "01-CLICK.jpg", "02-DONE.jpg")
        addRun("20261113-120000", report("running", 1, 0, 0.0))
        active = "20261113-120000"

        // The run in progress can't be trashed; bad ids are ignored (no path tricks).
        assertEquals(0, store.trash(listOf("20261113-120000", "../runs", ".trash", "20261113-999999")))
        assertEquals(2, store.trash(listOf("20261113-100000", "20261113-110000")))
        assertEquals(listOf("20261113-120000"), store.list().map { it.id })
        assertEquals(listOf("20261113-110000", "20261113-100000"), store.list(trash = true).map { it.id })
        assertTrue(File(root, ".trash/20261113-110000/02-DONE.jpg").isFile)
        assertNotNull(store.load("20261113-110000", trash = true))
        assertNull(store.load("20261113-110000", trash = false))

        // Delete forever only works on runs in the Trash.
        assertEquals(0, store.deleteForever(listOf("20261113-120000")))
        assertTrue(File(root, "20261113-120000").isDirectory)

        assertEquals(1, store.restore(listOf("20261113-100000")))
        assertEquals(listOf("20261113-120000", "20261113-100000"), store.list().map { it.id })
        assertTrue(File(root, "20261113-100000/01-CLICK.jpg").isFile)

        assertEquals(1, store.deleteForever(listOf("20261113-110000")))
        assertFalse(File(root, ".trash/20261113-110000").exists())
        assertEquals(0, store.count(trash = true))
    }

    @Test
    fun trashedRunsDontCountTowardsRetention() {
        for (i in 0 until 22) addRun(String.format(Locale.US, "20261113-1000%02d", i), report("done", 1, 0, 0.0))
        // Five older runs in the Trash.
        for (i in 0 until 5) addRun(String.format(Locale.US, "20261112-0900%02d", i), report("done", 1, 0, 0.0))
        store.trash((0 until 5).map { String.format(Locale.US, "20261112-0900%02d", it) })
        RunReport.prune(root)
        val kept = store.list().map { it.id }
        assertEquals(RunReport.KEEP, kept.size)
        assertEquals("20261113-100021", kept.first())
        assertEquals("20261113-100002", kept.last())   // the two oldest went
        assertEquals(5, store.count(trash = true))    // the Trash is untouched
    }

    // ------------------------------------------------------------------ formatting

    @Test
    fun formatting() {
        assertEquals("$0.00035", RunFormat.usd(3.480120e-4))
        assertEquals("$0.00143", RunFormat.usd(0.0014277480000000002))
        assertEquals("96%", RunFormat.pct(0.96))
        assertEquals("–", RunFormat.pct(null))
        assertEquals("8,886", RunFormat.tokens(8886))
        assertEquals("20s", RunFormat.duration(19.9))
        assertEquals("1m 05s", RunFormat.duration(65.0))
        assertEquals("Not sure", RunFormat.label("unsure"))
        assertEquals("Gave up", RunFormat.label("gave_up"))
        assertEquals(RunFormat.Tone.WARN, RunFormat.tone("stopped"))
        assertEquals(RunFormat.Tone.BAD, RunFormat.tone("error"))
        assertEquals(RunFormat.Tone.OK, RunFormat.tone("done"))
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse("2026-11-13T18:00:00")!!
        assertEquals("Today 10:55", RunFormat.whenShort("2026-11-13T10:55:02", now))
        assertEquals("Yesterday 23:10", RunFormat.whenShort("2026-11-12T23:10:00", now))
        assertEquals("1 Nov 09:00", RunFormat.whenShort("2026-11-01T09:00:00", now))
        assertEquals("1 Nov 2025 09:00", RunFormat.whenShort("2025-11-01T09:00:00", now))
        assertEquals("13 Nov 2026, 10:55:02", RunFormat.whenLong("2026-11-13T10:55:02"))
        val r = RunStore.parse("20261113-105502", resource("report_done.json"))!!
        assertEquals("2 steps · 20s · $0.00035 · 8,886 tokens", RunFormat.rowMetrics(r))
        assertEquals("JEV took 0.8s · waited 4.7s for the screen (it reacted 2.0s late) · step took 9.1s",
            RunFormat.stepTiming(r.steps[0]))
        assertEquals("4,135 in / 300 out tokens · $0.00017", RunFormat.stepCost(r.steps[0]))
    }
}
