package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.agent.AgentRules
import com.brainstormity.voicecontrol.core.UiTreeJson
import org.junit.Assert.assertEquals
import org.junit.Test

class LayaHistoryTest {
    private val tree = UiTreeJson.decode(javaClass.classLoader!!.getResource("trees/emulator/chrome_keyboard.json")!!.readText())
    private val cands = LayaCandidates.build(tree)
    private val url = cands.first { it.resId == "url_bar" }

    @Test
    fun tapsAreGroundedOnTheCandidatesOfTheirStep() {
        val h = LayaHistory()
        assertEquals(listOf("OPEN_APP Chrome"), h.convert("t", listOf("OPEN_APP Chrome")))
        h.remember(1, cands, url) // step 1 saw 1 entry; its action becomes entry #1
        val tap = "CLICK at (${url.centerX}, ${url.centerY})"
        assertEquals(listOf("OPEN_APP Chrome", "CLICK ${url.label}"), h.convert("t", listOf("OPEN_APP Chrome", tap)))
        // A tap outside every candidate.
        h.remember(2, emptyList(), null)
        assertEquals("CLICK (unlabeled)", h.convert("t", listOf("OPEN_APP Chrome", tap, "CLICK at (1, 1) (nothing changed on screen)")).last())
    }

    @Test
    fun typingScrollsAndSwipesUseLayasVocabulary() {
        val h = LayaHistory()
        val typed = "TYPE_ENTER ${AgentRules.pyRepr("weather in Berlin")} + Enter at (487, 136)"
        val typed2 = "TYPE ${AgentRules.pyRepr("it's \"x\"\\y")} at (1, 2)"
        val out = h.convert("t", listOf(typed, typed2, "SCROLL_DOWN", "SWIPE_LEFT", "SWIPE_RIGHT", "BACK",
            "OPEN_WEBSITE https://x.com", "LONG_PRESS at (5, 5)"))
        assertEquals(listOf("INPUT_TEXT \"weather in Berlin\"", "INPUT_TEXT \"it's \"x\"\\y\"", "SCROLL_DOWN",
            "SCROLL_RIGHT", "SCROLL_LEFT", "BACK", "OPEN_WEBSITE https://x.com", "LONG_PRESS (unlabeled)"), out)
        assertEquals(3, LayaCandidates.historyLines(out).size)
    }

    @Test
    fun aNewRunStartsOver() {
        val h = LayaHistory()
        h.convert("a", listOf("OPEN_APP Clock", "BACK"))
        assertEquals(listOf("HOME"), h.convert("a", listOf("HOME")))
        assertEquals(listOf("OPEN_APP Clock"), h.convert("b", listOf("OPEN_APP Clock")))
    }

    @Test
    fun unReprInvertsPyRepr() {
        for (s in listOf("plain", "it's", "say \"hi\"", "both ' and \"", "tab\tnl\n", "back\\slash", "\u0001ctl")) {
            assertEquals(s, LayaHistory.unRepr(AgentRules.pyRepr(s)))
        }
    }
}
