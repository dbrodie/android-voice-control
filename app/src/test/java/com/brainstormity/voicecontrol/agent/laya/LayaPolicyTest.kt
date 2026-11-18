package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.agent.AgentRules
import com.brainstormity.voicecontrol.agent.JevClient
import com.brainstormity.voicecontrol.agent.TaskParser
import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.UiTreeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayaPolicyTest {
    private fun tree(name: String): UiTree =
        UiTreeJson.decode(javaClass.classLoader!!.getResource("trees/$name")!!.readText())

    @Test
    fun operationQuestionIsByun11sTwelveOpsInOrder() {
        val q = LayaPolicy.operationQuestion()
        assertEquals("Which operation should be performed next?", q.instructions)
        assertEquals(listOf("CLICK", "LONG_PRESS", "SCROLL_UP", "SCROLL_DOWN", "SCROLL_LEFT", "SCROLL_RIGHT",
            "OPEN_APP", "INPUT_TEXT", "BACK", "HOME", "WAIT", "DONE"), q.keys)
        assertEquals("the goal is complete; stop", q.criteria["DONE"])
        // Same text as the fixtures' operation criteria (first 12 entries).
        val fx = LayaFixtures.questions(LayaFixtures.row("settings_bt_from_main")).getValue("operation")
        assertEquals(fx.criteria, q.criteria)
    }

    @Test
    fun opMappingKeepsScrollNamesAndFlipsHorizontalScrolls() {
        assertEquals("SCROLL_DOWN", LayaPolicy.mapOp("SCROLL_DOWN", "TYPE"))
        assertEquals("SCROLL_UP", LayaPolicy.mapOp("SCROLL_UP", "TYPE"))
        assertEquals("SWIPE_LEFT", LayaPolicy.mapOp("SCROLL_RIGHT", "TYPE"))
        assertEquals("SWIPE_RIGHT", LayaPolicy.mapOp("SCROLL_LEFT", "TYPE"))
        assertEquals("TYPE_ENTER", LayaPolicy.mapOp("INPUT_TEXT", "TYPE_ENTER"))
        for (op in listOf("CLICK", "LONG_PRESS", "OPEN_APP", "BACK", "HOME", "WAIT", "DONE")) {
            assertEquals(op, LayaPolicy.mapOp(op, "TYPE"))
        }
        // Every mapped action is one of ours.
        for (op in LayaPolicy.OPS.keys) assertTrue(op, LayaPolicy.mapOp(op, "TYPE") in AgentRules.PHONE_ACTIONS)
    }

    @Test
    fun probabilitiesSumMappedMassOverOfferedActionsOnly() {
        val ops = mapOf("CLICK" to 0.5, "SCROLL_RIGHT" to 0.1, "OPEN_APP" to 0.2, "INPUT_TEXT" to 0.15, "DONE" to 0.05)
        val offered = JevClient.offeredActions(listOf("x"), emptyList(), emptyList()).keys // no OPEN_APP / OPEN_WEBSITE
        val p = LayaPolicy.actionProbabilities(ops, offered, "TYPE", hasCandidates = true)
        assertEquals(offered.toList(), p.keys.toList())
        assertEquals(0.5, p["CLICK"]!!, 1e-12)
        assertEquals(0.1, p["SWIPE_LEFT"]!!, 1e-12)
        assertEquals(0.15, p["TYPE"]!!, 1e-12)
        assertEquals(0.0, p["TYPE_ENTER"]!!, 1e-12)
        assertEquals(0.0, p["ENTER"]!!, 1e-12)
        assertEquals(0.8, p.values.sum(), 1e-12) // OPEN_APP's 0.2 is not expressible here
        assertEquals("CLICK" to 0.5, LayaPolicy.best(p))
    }

    @Test
    fun tapOpsGetNothingWithoutCandidates() {
        val ops = mapOf("CLICK" to 0.6, "BACK" to 0.3, "HOME" to 0.1)
        val p = LayaPolicy.actionProbabilities(ops, AgentRules.PHONE_ACTIONS.keys, "TYPE", hasCandidates = false)
        assertEquals(0.0, p["CLICK"]!!, 0.0)
        assertEquals("BACK" to 0.3, LayaPolicy.best(p))
    }

    @Test
    fun searchFieldsTypeAndPressEnter() {
        val t = tree("emulator/chrome_keyboard.json")
        val cands = LayaCandidates.build(t)
        val url = cands.first { it.resId == "url_bar" }
        assertEquals("TYPE_ENTER", LayaPolicy.inputTextAction(LayaPolicy.fieldInfo(t, url), "Open Chrome and search for weather"))
        val msg = LayaPolicy.FieldInfo("compose_message_text", "Text message", "", "", "android.widget.EditText")
        assertEquals("TYPE", LayaPolicy.inputTextAction(msg, "Reply \"see you\" to Dana"))
        assertEquals("TYPE_ENTER", LayaPolicy.inputTextAction(null, "search for cats"))
        assertEquals("TYPE", LayaPolicy.inputTextAction(null, "write \"hi\""))
    }

    @Test
    fun typeTargetPrefersEditableThenFocused() {
        val t = tree("emulator/chrome_keyboard.json")
        val cands = LayaCandidates.build(t)
        val url = cands.first { it.resId == "url_bar" }
        val mic = cands.first { it.resId == "mic_button" }
        assertEquals(url.id, LayaPolicy.typeTarget(t, cands, mic)!!.id) // not editable -> the focused field
        assertEquals(url.id, LayaPolicy.typeTarget(t, cands, url)!!.id)
        assertNull(LayaPolicy.typeTarget(t, emptyList(), null))
    }

    @Test
    fun textChoiceFromTheTask() {
        fun pick(task: String) = TaskParser.textOptions(task).let { o -> LayaPolicy.chooseText(task, o)?.let { o[it] } }
        assertEquals("weather in Berlin", pick("Open Chrome and search for \"weather in Berlin\""))
        assertEquals("weather in Berlin", pick("Search for weather in Berlin"))
        assertEquals("cats", pick("search for cats in Chrome"))
        assertEquals("see you at 7:30", pick("Reply \"see you at 7:30\" to Dana"))
        assertNull(pick("Rename my phone to Pixel please and thanks")) // no trigger, no quotes: ask Laya
        assertEquals(0, LayaPolicy.chooseText("x", listOf("only")))
        assertNull(LayaPolicy.chooseText("x", emptyList()))
    }

    @Test
    fun textQuestionCapsOptions() {
        val q = LayaPolicy.textQuestion(List(100) { "t$it" })
        assertEquals(LayaPolicy.MAX_TEXT_OPTIONS, q.size)
        assertEquals("t0", q.criteria["0"])
        assertEquals("Which text should be typed into the focused field?", q.instructions)
    }

    private fun toggle(label: String, checked: Boolean, cls: String = "android.widget.Switch", checkable: Boolean = true) = Candidate(
        id = 7, line = "[7] $label ($cls) ${if (checked) "on" else ""}", option = label, label = label, className = cls,
        resId = "", nodeIndex = 3, bounds = intArrayOf(900, 1000, 1060, 1080), depth = 5, clickable = true,
        longClickable = false, editable = false, scrollable = false, checkable = checkable, checked = checked,
        selected = false, enabled = true,
    )

    @Test
    fun wantedStateFromTheTask() {
        assertEquals(true, LayaPolicy.wantedState("Open Settings and turn on Dark theme"))
        assertEquals(true, LayaPolicy.wantedState("turn Bluetooth on"))
        assertEquals(false, LayaPolicy.wantedState("switch off Wi-Fi"))
        assertEquals(false, LayaPolicy.wantedState("Disable airplane mode"))
        assertNull(LayaPolicy.wantedState("Open the Clock app and show my alarms"))
        assertNull(LayaPolicy.wantedState("turn on Wi-Fi and turn off Bluetooth"))   // ambiguous: no override
    }

    @Test
    fun doneOnAnOffSwitchBecomesClick() {
        val probs = mapOf("CLICK" to 0.30, "DONE" to 0.67, "BACK" to 0.03)
        val o = LayaPolicy.toggleOverride("Open Settings and turn on Dark theme", "DONE", probs, toggle("Dark theme", false), 0.94)
        assertNotNull(o)
        assertEquals("CLICK", o!!.action)
        assertEquals(0.97, o.confidence, 1e-9)
        assertTrue(o.note, o.note.contains("still off"))
    }

    @Test
    fun clickOnASwitchAlreadyInTheWantedStateBecomesDone() {
        val probs = mapOf("CLICK" to 0.8, "DONE" to 0.1)
        val o = LayaPolicy.toggleOverride("turn on Bluetooth", "CLICK", probs, toggle("Bluetooth", true), 0.9)
        assertEquals("DONE", o?.action)
        assertEquals(0.9, o!!.confidence, 1e-9)
    }

    @Test
    fun overrideDoesNotFireWithoutTheConditions() {
        val probs = mapOf("CLICK" to 0.3, "DONE" to 0.67)
        val off = toggle("Dark theme", false)
        // Normal cases: DONE on an on switch, CLICK on an off switch.
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "DONE", probs, toggle("Dark theme", true), 0.9))
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "CLICK", probs, off, 0.9))
        // No on/off intent, label not in the task, not a toggle, target unsure, other actions.
        assertNull(LayaPolicy.toggleOverride("Open display settings", "DONE", probs, off, 0.9))
        assertNull(LayaPolicy.toggleOverride("turn on Bluetooth", "DONE", probs, off, 0.9))
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "DONE", probs,
            toggle("Dark theme", false, cls = "android.widget.LinearLayout", checkable = false), 0.9))
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "DONE", probs, off, 0.5))
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "SCROLL_DOWN", probs, off, 0.9))
        assertNull(LayaPolicy.toggleOverride("turn on Dark theme", "DONE", probs, null, 0.9))
    }
}
