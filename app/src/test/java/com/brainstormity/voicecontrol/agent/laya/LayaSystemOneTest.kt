package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.agent.AgentRules
import com.brainstormity.voicecontrol.agent.Availability
import com.brainstormity.voicecontrol.agent.StepInput
import com.brainstormity.voicecontrol.agent.SystemOneException
import com.brainstormity.voicecontrol.agent.TaskParser
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.UiTreeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** LayaSystemOne.ask with a fake scorer (no model), availability logic, manifest and engine helpers. */
class LayaSystemOneTest {
    private fun tree(name: String): UiTree =
        UiTreeJson.decode(javaClass.classLoader!!.getResource("trees/$name")!!.readText())

    /** A scorer that answers each question with [pick] (key -> probability mass for that key, rest spread). */
    private class Fake(val picks: Map<String, Pair<String, Double>>) : LayaScorer {
        val asked = ArrayList<Pair<String, LinkedHashMap<String, LayaQuestion>>>()
        override fun score(stateJson: String, questions: LinkedHashMap<String, LayaQuestion>): LayaEngine.Scored {
            asked += stateJson to questions
            val answers = LinkedHashMap<String, LayaDecoder.Answer>()
            for ((qid, q) in questions) {
                val (key, p) = picks[qid] ?: (q.keys.first() to 1.0)
                val rest = if (q.size > 1) (1 - p) / (q.size - 1) else 0.0
                val probs = DoubleArray(q.size) { if (q.keys[it] == key) p else rest }
                answers[qid] = LayaDecoder.Answer(q.keys, probs, FloatArray(q.size))
            }
            return LayaEngine.Scored(answers, 512, false, 100, 1, linkedMapOf("operation" to 10L), "CPU", null, 300)
        }
    }

    private fun input(task: String, t: UiTree?, history: List<String> = emptyList(), apps: List<TaskParser.App> = emptyList()) =
        StepInput(task, ByteArray(0), 100, 200, t, history, TaskParser.textOptions(task), TaskParser.websites(task), apps)

    @Test
    fun clickOnTheChosenCandidate() {
        val t = tree("emulator/settings.json")
        val cands = LayaCandidates.build(t)
        val fake = Fake(mapOf("operation" to ("CLICK" to 0.7), "target" to ("3" to 0.6)))
        val s1 = LayaSystemOne(null, fake)
        val d = s1.ask(input("Open Settings and turn on Bluetooth", t, listOf("OPEN_APP Settings"),
            listOf(TaskParser.App("Settings", "com.android.settings"))))
        assertEquals("CLICK", d.action!!.choice)
        assertEquals(0.7, d.action!!.confidence, 1e-9)
        val c = cands[3]
        assertEquals(c.bounds[0] / 1080.0, d.target!!.box.left, 1e-12)
        assertEquals(c.bounds[3] / 2400.0, d.target!!.box.bottom, 1e-12)
        assertEquals(1.0, d.target!!.confidence, 0.0)
        assertEquals(t.nodes[c.nodeIndex].id, d.targetNodeId)
        assertEquals("laya", d.backend)
        assertEquals("0", d.app!!.choice)
        assertEquals(cands.size, d.extras["candidates"])
        assertEquals("CLICK", d.extras["laya_op"])
        assertEquals(512, d.extras["window"])
        // Questions: exactly operation + target, state JSON with Laya history.
        val (state, qs) = fake.asked.single()
        assertEquals(listOf("operation", "target"), qs.keys.toList())
        assertTrue(state, state.startsWith("{\"goal\": \"Open Settings and turn on Bluetooth\", \"history\": [\"OPEN_APP Settings\"], \"ui\": [\"[0] "))
        // probabilities cover every offered action and sum to <= 1
        assertTrue(d.action!!.probabilities.keys.containsAll(listOf("CLICK", "OPEN_APP", "DONE")))
        assertTrue(d.action!!.probabilities.values.sum() <= 1.0 + 1e-9)
    }

    @Test
    fun inputTextTypesIntoTheFocusedSearchFieldWithTheQuotedText() {
        val t = tree("emulator/chrome_keyboard.json")
        val cands = LayaCandidates.build(t)
        val mic = cands.first { it.resId == "mic_button" }
        val fake = Fake(mapOf("operation" to ("INPUT_TEXT" to 0.8), "target" to (mic.id.toString() to 0.5)))
        val d = LayaSystemOne(null, fake).ask(input("Open Chrome and search for \"weather in Berlin\"", t))
        assertEquals("TYPE_ENTER", d.action!!.choice)
        val url = cands.first { it.resId == "url_bar" }
        assertEquals(url.centerX / 1080.0, d.target!!.box.cx, 0.002)
        assertEquals("0", d.text!!.choice)
        assertEquals("task", d.extras["text_source"])
        assertEquals(1, fake.asked.size) // no text question needed
    }

    @Test
    fun undecidableTextAsksLayasTextQuestion() {
        val t = tree("emulator/chrome_keyboard.json")
        val fake = Fake(mapOf("operation" to ("INPUT_TEXT" to 0.8), "text" to ("2" to 0.9)))
        val d = LayaSystemOne(null, fake).ask(input("Rename my phone to Pixel please", t))
        assertEquals("2", d.text!!.choice)
        assertEquals("laya", d.extras["text_source"])
        assertEquals(listOf("text"), fake.asked.last().second.keys.toList())
    }

    @Test
    fun scrollRightBecomesSwipeLeft() {
        val t = tree("emulator/home.json")
        val d = LayaSystemOne(null, Fake(mapOf("operation" to ("SCROLL_RIGHT" to 0.9)))).ask(input("Find the photos app", t))
        assertEquals("SWIPE_LEFT", d.action!!.choice)
    }

    @Test
    fun noTreeIsAnErrorAndNoCandidatesOnlyAllowsNonTapActions() {
        try {
            LayaSystemOne(null, Fake(emptyMap())).ask(input("x", null)); fail()
        } catch (e: SystemOneException) {
            assertEquals(LayaSystemOne.NO_TREE, e.message)
        }
        val empty = tree("emulator/home.json").let { it.copy(nodes = it.nodes.map { n -> n.copy(clickable = false, longClickable = false, scrollable = false, checkable = false, editable = false) }) }
        val fake = Fake(mapOf("operation" to ("CLICK" to 0.6)))
        val d = LayaSystemOne(null, fake).ask(input("Go home", empty))
        assertEquals(listOf("operation"), fake.asked.single().second.keys.toList())
        assertTrue(d.action!!.choice != "CLICK")
        assertNull(d.target)
    }

    @Test
    fun candidatesAreDroppedFromTheEndUntilTheQuestionsFit() {
        val t = tree("many_candidates.json")
        val all = LayaCandidates.build(t).size
        var calls = 0
        val fits = 20
        val scorer = LayaScorer { s, q ->
            calls++
            val k = q["target"]?.size ?: 0
            if (k > fits) throw LayaDoesNotFit("target: ${k - fits} options past 2048 tokens")
            Fake(mapOf("operation" to ("CLICK" to 0.9))).score(s, q)
        }
        val d = LayaSystemOne(null, scorer).ask(input("Open the last one", t))
        assertTrue(calls > 1)
        assertEquals(all, d.extras["candidates"])
        assertTrue((d.extras["candidates_dropped"] as Int) >= all - fits)
    }

    @Test
    fun historyIsConvertedAcrossSteps() {
        val t = tree("emulator/settings.json")
        val cands = LayaCandidates.build(t)
        val fake = Fake(mapOf("operation" to ("CLICK" to 0.9), "target" to ("2" to 0.9)))
        val s1 = LayaSystemOne(null, fake)
        val d = s1.ask(input("Turn on Bluetooth", t, listOf("OPEN_APP Settings")))
        val x = (d.target!!.box.cx * 1080).toInt()
        val y = (d.target!!.box.cy * 2400).toInt()
        s1.ask(input("Turn on Bluetooth", t, listOf("OPEN_APP Settings", "CLICK at ($x, $y)")))
        val state = fake.asked.last().first
        assertTrue(state, state.contains("\"history\": [\"OPEN_APP Settings\", \"CLICK ${cands[2].label}\"]"))
    }

    // ------------------------------------------------------------------ availability / files / engine helpers

    private val specs = listOf(LayaModelFiles.Spec("a", 10, ""), LayaModelFiles.Spec("b", 20, ""))
    private val okDevice = LayaModelFiles.Device("arm64-v8a", true, 8_000_000_000, false)

    @Test
    fun availabilityRules() {
        val none = LayaModelFiles.Status(specs, emptySet())
        val half = LayaModelFiles.Status(specs, setOf("a"))
        val all = LayaModelFiles.Status(specs, setOf("a", "b"))
        assertTrue(LayaModelFiles.availabilityFor(LayaModelFiles.Device("armeabi-v7a", false, 8_000_000_000, false), all, null, null, null) is Availability.Unsupported)
        assertTrue(LayaModelFiles.availabilityFor(LayaModelFiles.Device("x86", false, 8_000_000_000, false), all, null, null, null) is Availability.Unsupported)
        assertTrue(LayaModelFiles.availabilityFor(LayaModelFiles.Device("arm64-v8a", true, 2_000_000_000, false), all, null, null, null) is Availability.Unsupported)
        assertTrue(LayaModelFiles.availabilityFor(LayaModelFiles.Device("arm64-v8a", true, 2_000_000_000, true), all, null, null, null).isReady)
        assertTrue(LayaModelFiles.availabilityFor(LayaModelFiles.Device("x86_64", true, 4_000_000_000, false), all, null, null, null).isReady)
        val nd = LayaModelFiles.availabilityFor(okDevice, none, null, null, null) as Availability.NeedsDownload
        assertEquals(30, nd.bytes)
        val rest = LayaModelFiles.availabilityFor(okDevice, half, null, null, null) as Availability.NeedsDownload
        assertEquals(20, rest.bytes)
        val running = LayaModelFiles.availabilityFor(okDevice, half, LayaDownload.State.Running(15, 20, "b"), null, null)
        assertTrue(running.message, running.message.contains("75%"))
        val damaged = LayaModelFiles.availabilityFor(okDevice, all, null, "damaged", null)
        assertTrue(damaged is Availability.NeedsDownload && damaged.message == "damaged")
        assertEquals("loaded on CPU", LayaModelFiles.availabilityFor(okDevice, all, null, null, "loaded on CPU").message)
    }

    @Test
    fun bundledManifestMatchesTheToolsManifestAndListsTheRequiredSet() {
        val asset = listOf(File("src/main/assets/laya/MANIFEST.json"), File("app/src/main/assets/laya/MANIFEST.json")).first { it.isFile }
        val tools = File(LayaFixtures.fixtureDir.parentFile, "out/MANIFEST.json")
        assertEquals(tools.readText(), asset.readText())
        val m = asset.inputStream().use { LayaModelFiles.parseManifest(it) }
        val req = LayaModelFiles.requiredSpecs(m)
        assertEquals(5, req.size)
        assertEquals(702_945_081L, req.sumOf { it.bytes }) // T14's recommended set + token_embeddings.json (611 B)
        for (g in LayaModelFiles.SINGLE_GRAPHS.values) assertNotNull(m[g])
    }

    @Test
    fun statusChecksSizesAndVerifyCachesHashes() {
        val dir = createTempDir("laya")
        try {
            File(dir, "a").writeBytes(ByteArray(10))
            val sha = LayaModelFiles.sha256(File(dir, "a"))
            val sp = listOf(LayaModelFiles.Spec("a", 10, sha), LayaModelFiles.Spec("b", 3, "00"))
            val st = LayaModelFiles.statusOf(dir, sp)
            assertEquals(setOf("a"), st.present)
            assertFalse(st.complete)
            File(dir, "b").writeBytes(ByteArray(3))
            assertEquals(listOf("b"), LayaModelFiles.verify(dir, sp))
            assertTrue(File(dir, "verified.txt").readText().contains("a 10 "))
            assertEquals(listOf("b"), LayaModelFiles.verify(dir, sp)) // a is cached, b still bad
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backendOrderAndSmeDetection() {
        assertEquals(listOf(LayaEngine.Backend.GPU, LayaEngine.Backend.CPU), LayaEngine.backendOrder(AppSettings.LAYA_ACCEL_AUTO, false))
        assertEquals(listOf(LayaEngine.Backend.GPU, LayaEngine.Backend.CPU_REF), LayaEngine.backendOrder(AppSettings.LAYA_ACCEL_AUTO, true))
        assertEquals(listOf(LayaEngine.Backend.CPU), LayaEngine.backendOrder(AppSettings.LAYA_ACCEL_CPU, false))
        assertEquals(listOf(LayaEngine.Backend.CPU_REF), LayaEngine.backendOrder(AppSettings.LAYA_ACCEL_CPU_REF, false))
        assertEquals(listOf(LayaEngine.Backend.GPU), LayaEngine.backendOrder(AppSettings.LAYA_ACCEL_GPU, true))
        assertTrue(LayaEngine.cpuFeaturesHaveSme("Features\t: fp asimd bf16 smei16i64 sme2 sme2p1"))
        assertFalse(LayaEngine.cpuFeaturesHaveSme("Features\t: fp asimd bf16 smei16i64 asimddp"))
    }

    @Test
    fun halfToFloatIsExactForAllBitPatterns() {
        for (b in 0 until 65536) {
            val want = java.lang.Float.float16ToFloat(b.toShort())
            val got = LayaEngine.halfToFloat(b)
            if (want.isNaN()) assertTrue(got.isNaN()) else assertEquals("bits $b", want.toRawBits(), got.toRawBits())
        }
    }

    @Test
    fun confidenceGateUsesTheMappedMass() {
        // A split CLICK/TYPE answer below MIN_CONFIDENCE goes through AgentRules.splitChain like JEV's.
        val p = LayaPolicy.actionProbabilities(mapOf("CLICK" to 0.3, "INPUT_TEXT" to 0.35, "WAIT" to 0.35),
            AgentRules.PHONE_ACTIONS.keys, "TYPE", true)
        val split = AgentRules.splitChain("TYPE", p.getValue("TYPE"), p, "Laya")
        assertEquals("CLICK", split.action)
        assertEquals(0.65, split.confidence, 1e-9)
    }
}
