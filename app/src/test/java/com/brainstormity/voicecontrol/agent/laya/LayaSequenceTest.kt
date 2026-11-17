package com.brainstormity.voicecontrol.agent.laya

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayaSequenceTest {

    // ------------------------------------------------------------------ state JSON (no tokenizer needed)

    @Test
    fun stateJsonIsByteIdenticalToPythonJsonDumps() {
        val f = listOf(File("src/test/resources/laya/state_json_cases.json"), File("app/src/test/resources/laya/state_json_cases.json"))
            .first { it.isFile }
        val cases = JSONArray(f.readText(Charsets.UTF_8))
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = LayaSequence.stateJson(c.getString("goal"), LayaFixtures.strings(c.getJSONArray("history")),
                LayaFixtures.strings(c.getJSONArray("ui")))
            assertEquals(c.getString("name"), c.getString("json"), got)
        }
        assertTrue(cases.length() >= 27)
    }

    @Test
    fun stateJsonEdgeCases() {
        assertEquals("{\"goal\": \"a/b  \", \"history\": [], \"ui\": [\"\\u0001\\t\"]}",
            LayaSequence.stateJson("a/b  ", emptyList(), listOf("\u0001\t")))
    }

    // ------------------------------------------------------------------ builder with a fake tokenizer (no model files)

    /** 1 token per character: easy to reason about lengths. */
    private val fake: (String) -> IntArray = { s -> IntArray(s.length) { 100 + (s[it].code % 50) } }

    private fun q(vararg opts: Pair<String, String?>) =
        LayaQuestion("Pick", opts.associateTo(LinkedHashMap()) { it })

    @Test
    fun layoutIsClsHeadSepMaskedOptionsSepStateSep() {
        val state = IntArray(5) { 7 }
        val e = LayaSequence.build(fake, state, "q", q("A" to null, "B" to "bb"), 64)
        val head = "choice question: Pick".length
        assertEquals(LayaTokenizer.CLS, e.ids[0])
        assertEquals(LayaTokenizer.SEP, e.ids[1 + head])
        // option 0 = [MASK] " A" (3 tokens), option 1 = [MASK] " B: bb" (7 tokens)
        assertArrayEquals(intArrayOf(head + 2, head + 2 + 3), e.markers)
        assertEquals(LayaTokenizer.MASK, e.ids[e.markers[0]])
        assertEquals(LayaTokenizer.MASK, e.ids[e.markers[1]])
        val afterOpts = head + 2 + 3 + 7
        assertEquals(LayaTokenizer.SEP, e.ids[afterOpts])
        assertEquals(7, e.ids[afterOpts + 1])
        assertEquals(LayaTokenizer.SEP, e.ids.last())
        assertEquals(afterOpts + 1 + 5 + 1, e.ids.size)
        assertEquals(0, e.stateDropped)
    }

    @Test
    fun stateIsRightTruncatedToTheWindow() {
        val state = IntArray(100) { 7 }
        val e = LayaSequence.build(fake, state, "q", q("A" to null), 48)
        assertEquals(48, e.ids.size)
        assertEquals(LayaTokenizer.SEP, e.ids.last())
        assertTrue(e.stateDropped > 0)
        assertTrue(e.fits)
    }

    @Test
    fun optionsCappedAt48TokensAndMarkersPastTheWindowAreLost() {
        val long = "x".repeat(200)
        val e = LayaSequence.build(fake, IntArray(0), "q", q("A" to long), 4096)
        // [MASK] + 48 text tokens
        assertEquals(e.ids.size, 1 + "choice question: Pick".length + 1 + 49 + 1 + 1)
        val many = LayaSequence.build(fake, IntArray(0), "q", q(*Array(20) { "$it" to "opt" }), 64)
        assertFalse(many.fits)
        assertTrue(many.markersLost > 0)
    }

    @Test
    fun planPicksTheSmallestWindowWhenNothingIsTruncated() {
        val p = LayaSequence.planWithState(fake, IntArray(300) { 7 }, mapOf("q" to q("A" to null)))
        assertEquals(512, p.window)
        assertFalse(p.truncated)
        val small = LayaSequence.planWithState(fake, IntArray(10) { 7 }, mapOf("q" to q("A" to null)))
        assertEquals(256, small.window)
        val big = LayaSequence.planWithState(fake, IntArray(5000) { 7 }, mapOf("q" to q("A" to null)))
        assertEquals(2048, big.window)
        assertTrue(big.truncated)
    }

    @Test
    fun decoderAppliesTemperatureAndSoftmax() {
        val a = LayaDecoder.decode(listOf("a", "b"), floatArrayOf(1f, 0f))
        val t = LayaDecoder.TEMPERATURE_CHOICE
        val p0 = 1 / (1 + Math.exp(-1 / t))
        assertEquals(p0, a.probabilities[0], 1e-12)
        assertEquals("a", a.choice)
        assertEquals(p0, a.answerConfidence, 1e-12)
        assertEquals(1.0, LayaDecoder.decode(listOf("x"), floatArrayOf(3f)).entropyConfidence, 0.0)
        assertEquals(1.0, LayaDecoder.clampTemperature(Double.NaN), 0.0)
        assertEquals(5.0, LayaDecoder.clampTemperature(9.0), 0.0)
    }

    // ------------------------------------------------------------------ against the fixtures (needs the tokenizer)

    /** Every window the fixtures recorded (256/512/1024/2048): ids + markers identical, or both refuse. */
    @Test
    fun everyRecordedWindowMatchesTheFixtures() {
        val tok = LayaFixtures.tokenizer()
        var checked = 0
        var refused = 0
        for (row in LayaFixtures.rows) {
            val name = row.getString("name")
            val (goal, history, ui) = LayaFixtures.state(row)
            val stateIds = LayaSequence.encodeState(tok::encode, LayaSequence.stateJson(goal, history, ui))
            val byW = row.getJSONObject("expected_by_window")
            for (w in byW.keys()) {
                val v = byW.getJSONObject(w)
                val encoded = LayaFixtures.questions(row).map { (qid, q) -> LayaSequence.build(tok::encode, stateIds, qid, q, w.toInt()) }
                if (v.has("error")) {
                    assertTrue("$name@$w should not fit", encoded.any { !it.fits })
                    refused++
                    continue
                }
                val ids = v.getJSONObject("token_ids")
                for (e in encoded) {
                    val want = ids.getJSONObject(e.qid)
                    assertArrayEquals("$name/${e.qid}@$w", LayaFixtures.ints(want.getJSONArray("ids")), e.ids)
                    assertArrayEquals("$name/${e.qid}@$w markers", LayaFixtures.ints(want.getJSONArray("markers")), e.markers)
                    checked++
                }
                assertEquals("$name@$w truncated", v.getJSONObject("usage").getBoolean("truncated"),
                    encoded.any { it.stateDropped > 0 })
            }
        }
        println("$checked question-windows identical, $refused refusals identical")
        assertTrue(checked > 100)
    }

    /** The adaptive window: smallest recorded window whose sequences are untruncated. */
    @Test
    fun adaptiveWindowMatchesTheSmallestUntruncatedFixtureWindow() {
        val tok = LayaFixtures.tokenizer()
        for (row in LayaFixtures.rows) {
            val name = row.getString("name")
            val (goal, history, ui) = LayaFixtures.state(row)
            val plan = LayaSequence.plan(tok::encode, LayaSequence.stateJson(goal, history, ui), LayaFixtures.questions(row))
            val byW = row.getJSONObject("expected_by_window")
            val expected = listOf(256, 512, 1024, 2048).first { w ->
                val v = if (w == 1024) row else byW.optJSONObject(w.toString())
                v != null && !v.has("error") && !v.getJSONObject("usage").getBoolean("truncated")
            }
            assertEquals(name, expected, plan.window)
            assertTrue(name, plan.fits)
        }
    }
}
