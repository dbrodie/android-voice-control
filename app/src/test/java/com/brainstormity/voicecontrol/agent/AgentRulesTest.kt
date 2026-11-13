package com.brainstormity.voicecontrol.agent

import com.brainstormity.voicecontrol.agent.ScreenDiff.Gray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRulesTest {
    @Test fun chainSplitDoesOnlyTheSharedPart() {
        // TYPE_ENTER at 42%, but TYPE + TYPE_ENTER together 62%: do TYPE.
        val r = AgentRules.splitChain("TYPE_ENTER", 0.42, mapOf("CLICK" to 0.3, "TYPE" to 0.2, "TYPE_ENTER" to 0.42))
        assertEquals("TYPE", r.action)
        assertEquals(0.62, r.confidence, 1e-9)
        assertEquals("JEV was split between TYPE / TYPE_ENTER, so only the shared first part (TYPE) is done.", r.note)
        // Not split: unchanged.
        assertEquals(AgentRules.ChainResult("CLICK", 0.9, ""),
            AgentRules.splitChain("CLICK", 0.9, mapOf("CLICK" to 0.9)))
        // Nothing agrees enough: unchanged (and later stopped as unsure).
        assertEquals("TYPE", AgentRules.splitChain("TYPE", 0.3, mapOf("TYPE" to 0.3, "BACK" to 0.3)).action)
        // Only CLICK + TYPE + TYPE_ENTER together reach 50%.
        val all = AgentRules.splitChain("TYPE", 0.3, mapOf("CLICK" to 0.15, "TYPE" to 0.3, "TYPE_ENTER" to 0.06))
        assertEquals("CLICK", all.action)
        assertEquals("JEV was split between CLICK / TYPE / TYPE_ENTER, so only the shared first part (CLICK) is done.",
            all.note)
    }

    @Test fun thresholds() {
        assertEquals(0.25, AgentRules.minConfidenceFor("SCROLL_DOWN"), 0.0)
        assertEquals(0.5, AgentRules.minConfidenceFor("CLICK"), 0.0)
        assertEquals(0.042, AgentRules.costOf(1_000_000), 1e-12)
    }

    @Test fun pythonFormatting() {
        assertEquals("'hello' at (1, 2)", AgentRules.pyRepr("hello") + " at (1, 2)")
        assertEquals("\"it's\"", AgentRules.pyRepr("it's"))
        assertEquals("42%", AgentRules.pct(0.42))
        assertEquals("0.00010", AgentRules.usd(0.0001))
    }

    @Test fun closeUpGeometryMatchesRefineTarget() {
        // Same numbers upstream computes for a 700x1500 screenshot and this box.
        val c = CloseUp.around(Box(0.4, 0.5, 0.6, 0.55), 700, 1500)
        assertEquals(listOf(140, 577, 560, 997), listOf(c.left, c.top, c.right, c.bottom))
        assertEquals(800, c.outW)
        assertEquals(800, c.outH)
        val m = c.mapBack(Box(0.0, 0.0, 1.0, 1.0))
        assertEquals(140 / 700.0, m.left, 1e-9)
        assertEquals(577 / 1500.0, m.top, 1e-9)
        assertEquals(560 / 700.0, m.right, 1e-9)
        assertEquals(997 / 1500.0, m.bottom, 1e-9)
        // Near an edge the crop is clipped and the short side is at least 280 px.
        val e = CloseUp.around(Box(0.0, 0.0, 0.02, 0.01), 700, 1500)
        assertEquals(0, e.left)
        assertEquals(0, e.top)
        assertTrue(e.outW >= 280 && e.outH >= 280)
    }

    @Test fun missIsNearSameActionOnly() {
        val m = Miss("CLICK", 0.5, 0.5)
        assertTrue(m.isNear("CLICK", 0.54, 0.46))
        assertFalse(m.isNear("CLICK", 0.56, 0.5))
        assertFalse(m.isNear("TYPE", 0.5, 0.5))
    }

    @Test fun screenDiff() {
        val w = 100
        val h = 200
        val a = Gray(w, h, IntArray(w * h) { 100 })
        val b = Gray(w, h, a.v.copyOf().also { v ->
            for (y in 100 until 110) for (x in 10 until 20) v[y * w + x] = 200   // 100 px change
            v[0] = 0                                                              // status bar: ignored
            v[50 * w + 50] = 110                                                  // below threshold
        })
        val mask = ScreenDiff.changedPixels(a, b)
        assertEquals(h - 6, mask.height)  // top 3% cut
        assertEquals(100, mask.count())
        assertEquals(100.0 / (w * (h - 6)), ScreenDiff.fraction(mask), 1e-12)
        // Ignoring a grown copy of the same mask leaves nothing.
        assertEquals(0.0, ScreenDiff.fraction(mask, ScreenDiff.grow(mask)), 0.0)
        assertTrue(ScreenDiff.grow(mask).count() > mask.count())
        assertEquals(listOf(0.1, 0.48, 0.2, 0.54), ScreenDiff.bbox(mask))
        assertNull(ScreenDiff.bbox(ScreenDiff.emptyMaskFor(a)))
        assertEquals(100, ScreenDiff.lighter(ScreenDiff.emptyMaskFor(a), mask).count())
        // Different sizes (rotation): everything changed.
        assertEquals(1.0, ScreenDiff.fraction(ScreenDiff.changedPixels(a, Gray(h, w, IntArray(w * h)))), 0.0)
    }

    @Test fun jsonWriter() {
        assertEquals("""{"a":[1,0.5,"x\"y\n"],"b":null,"c":true}""",
            Json.write(linkedMapOf("a" to listOf(1, 0.5, "x\"y\n"), "b" to null, "c" to true)))
    }
}
