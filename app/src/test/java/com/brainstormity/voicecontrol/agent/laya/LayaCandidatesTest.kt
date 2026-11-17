package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.UiTreeJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin port against Byun11/laya-android's own Python (golden file produced by
 * app/src/test/golden/gen_laya_golden.py), byte for byte, on hand-built and real emulator trees.
 */
class LayaCandidatesTest {

    private fun res(name: String) = javaClass.classLoader!!.getResource("trees/$name")!!.readText()
    private fun tree(name: String): UiTree = UiTreeJson.decode(res(name))
    private val golden = JSONObject(res("laya_golden.json"))

    @Test
    fun matchesPythonGoldenOnEveryFixture() {
        assertEquals(11, golden.length())
        for (name in golden.keys()) {
            val g = golden.getJSONObject(name)
            val cands = LayaCandidates.build(tree(name))
            val ui = g.getJSONArray("ui")
            assertEquals("$name: candidate count", ui.length(), cands.size)
            assertEquals("$name: ui lines", List(ui.length()) { ui.getString(it) }, LayaCandidates.uiLines(cands))
            val labels = g.getJSONArray("labels")
            val bounds = g.getJSONArray("bounds")
            val nodeIndex = g.getJSONArray("node_index")
            cands.forEachIndexed { i, c ->
                assertEquals("$name[$i] label", labels.getString(i), c.label)
                val b = bounds.getJSONArray(i)
                assertEquals("$name[$i] bounds", List(4) { b.getInt(it) }, c.bounds.toList())
                assertEquals("$name[$i] node", nodeIndex.getInt(i), c.nodeIndex)
                assertEquals(i, c.id)
                assertEquals("[$i] ${c.option}", c.line)
            }
        }
    }

    @Test
    fun settingsLikeEdgeCases() {
        val c = LayaCandidates.build(tree("settings_like.json"))
        // clickable row without text: label from descendants, cut to 40 in the option, 60 in the label
        assertEquals("Network & internet Mobile, Wi‑Fi, hotspot", c[2].label)
        assertEquals("[2] Network & internet Mobile, Wi‑Fi, hotspo (LinearLayout)", c[2].line)
        // whitespace (newline, tab, runs of spaces, NBSP) collapsed like Python's split()
        assertEquals("Apps Assistant, recent apps, default apps", c[4].label)
        assertEquals("[6] Battery 100% (LinearLayout) disabled", c[6].line)
        assertEquals("[9] Dark theme (Switch) on", c[9].line)
        // same box + same label: deepest node (Button) kept, parent's long_clickable OR-ed in but not shown
        assertEquals("[10] OK (Button)", c[10].line)
        assertTrue(c[10].longClickable && c[10].clickable)
        // whitespace-only own text -> descendants; NBSP is whitespace for Python
        assertEquals("[11] For you (LinearLayout) selected", c[11].line)
        assertEquals("[12] Hint only (LinearLayout)", c[12].line)
    }

    @Test
    fun chromeLikeClippingAndVisibility() {
        val c = LayaCandidates.build(tree("chrome_like.json"))
        val labels = c.map { it.label }
        assertFalse("invisible", "Hidden" in labels)
        assertFalse("zero width", "Zero width" in labels)
        assertFalse("fully below the screen", "Below the fold" in labels)
        val partly = c.single { it.label == "Partly off-screen link" }
        assertEquals(listOf(42, 2300, 1038, 2400), partly.bounds.toList())
        assertEquals(listOf(0, 900, 200, 980), c.single { it.label == "Negative left" }.bounds.toList())
        assertEquals("[12] - ()", c[12].line) // no class, no label
        assertEquals("[1] en.wikipedia.org/wiki/Android (EditText) edit", c[1].line) // clickable -> no "long"
    }

    @Test
    fun keyboardWindowIsExcluded() {
        val t = tree("keyboard_shown.json")
        assertTrue(t.keyboardShown)
        val c = LayaCandidates.build(t)
        assertEquals(listOf("[0] Back (ImageButton)", "[1] - (RecyclerView) scroll", "[2] Text message (EditText) edit",
            "[3] Send SMS (ImageButton) disabled"), LayaCandidates.uiLines(c))
    }

    @Test
    fun dialogOverContentFaithfulAndTopWindowOption() {
        val t = tree("dialog_over_content.json")
        val all = LayaCandidates.build(t)
        assertEquals(10, all.size) // Laya keeps the activity behind the dialog
        val top = LayaCandidates.build(t, topApplicationWindowOnly = true)
        assertEquals(listOf("[0] Don't ask again (CheckBox)", "[1] Cancel (Button)", "[2] Delete (Button)"),
            LayaCandidates.uiLines(top))
    }

    @Test
    fun manyCandidatesOrderingAndCodePoints() {
        val c = LayaCandidates.build(tree("many_candidates.json"))
        assertTrue(c.size > 40)
        // identical boxes sorted by label in code-point order
        assertEquals(listOf("Zebra", "apple", "apple pie", "Émile", "Ａ fullwidth", "😀 grin"),
            c.filter { it.bounds.toList() == listOf(0, 2300, 540, 2390) }.map { it.label })
        // 40 code points, not 40 UTF-16 units
        val emoji = c.single { it.label.startsWith("😀😀") }
        assertEquals(60, emoji.label.codePointCount(0, emoji.label.length))
        assertTrue(emoji.option.startsWith("😀".repeat(39) + "a ("))
        // equal-depth duplicates: first node wins non-flag fields, flags OR-ed
        val twin = c.single { it.label == "Twin" }
        assertEquals("Button", twin.className)
        assertTrue(twin.longClickable)
        assertFalse(twin.checked)
    }

    @Test
    fun nestedRecyclerRowsTakeDescendantText() {
        val c = LayaCandidates.build(tree("nested_recycler.json"))
        assertEquals("Product 0: Ultra-comfortable running shoe $40.99 4.5 stars F", c[0].label)
        assertEquals("[0] Product 0: Ultra-comfortable running sho (ViewGroup)", c[0].line)
        // ViewPager + RecyclerView with the same box/label collapse to the deeper RecyclerView
        assertEquals(1, c.count { it.className == "ViewPager" || it.bounds.toList() == listOf(0, 200, 1080, 2250) })
        assertEquals("[8] Home (FrameLayout) selected", c[8].line)
    }

    @Test
    fun groundAndHistory() {
        val c = LayaCandidates.build(tree("settings_like.json"))
        // tap inside the Network row: smallest containing box wins over the RecyclerView
        val hit = LayaCandidates.ground(c, 500, 400)!!
        assertEquals("Network & internet Mobile, Wi‑Fi, hotspot", hit.label)
        assertNull(LayaCandidates.ground(c, 5, 5))
        assertEquals("CLICK Network & internet Mobile, Wi‑Fi, hotspot", LayaCandidates.historyEntry("CLICK", hit))
        val unlabeled = LayaCandidates.build(tree("chrome_like.json")).single { it.line == "[12] - ()" }
        assertEquals("LONG_PRESS ", LayaCandidates.historyEntry("LONG_PRESS", unlabeled)) // label "" and class ""
        assertEquals("CLICK (unlabeled)", LayaCandidates.historyEntry("CLICK", null))
        assertEquals("INPUT_TEXT \"casual shoes\"", LayaCandidates.historyEntry("INPUT_TEXT", text = "casual shoes"))
        assertEquals("OPEN_APP Settings", LayaCandidates.historyEntry("OPEN_APP", appName = "Settings"))
        assertEquals("SCROLL_DOWN", LayaCandidates.historyEntry("SCROLL_DOWN"))
        assertEquals(listOf("b", "c", "d"), LayaCandidates.historyLines(listOf("a", "b", "c", "d")))
        assertEquals(listOf("a"), LayaCandidates.historyLines(listOf("a")))
        assertEquals(mapOf("0" to c[0].option, "1" to c[1].option), LayaCandidates.targetCriteria(c.take(2)))
    }

    @Test
    fun pythonStringHelpers() {
        assertEquals("a b c", LayaCandidates.pySplitJoin("  a\t\nb　c  "))
        assertEquals("x", LayaCandidates.pyStrip("\u0085x\u0085")) // U+0085 is space for Python, not for Java
        assertEquals("a​b", LayaCandidates.pySplitJoin("a​b")) // zero-width space is NOT whitespace
        assertEquals("😀a", LayaCandidates.pySlice("😀ab", 2))
        assertTrue(LayaCandidates.compareCodePoints("￿", "😀") < 0) // UTF-16 order would say > 0
        assertTrue(LayaCandidates.compareCodePoints("ab", "a") > 0)
        assertEquals(0, LayaCandidates.compareCodePoints("", ""))
    }

    // ------------------------------------------------------------------ real emulator dumps (API 36)

    @Test
    fun emulatorSettings() {
        val t = tree("emulator/settings.json")
        assertEquals("com.android.settings", t.packageName)
        val lines = LayaCandidates.uiLines(LayaCandidates.build(t))
        assertTrue(lines.toString(), lines.any { it.contains("] Network & internet") && it.endsWith("(LinearLayout)") })
        assertTrue(lines.any { it.contains("] Search Settings") })
        assertTrue(lines.any { it.contains("] Connected devices") })
    }

    @Test
    fun emulatorChromeHasPageContent() {
        val t = tree("emulator/chrome_wikipedia.json")
        assertEquals("com.android.chrome", t.packageName)
        val webView = t.nodes.first { it.className == "android.webkit.WebView" }
        val under = descendants(t, webView.id)
        assertTrue("web content nodes: $under", under > 300)
        val lines = LayaCandidates.uiLines(LayaCandidates.build(t))
        assertTrue(lines.toString(), lines.any { it.endsWith("] Linux kernel (View)") })
        assertTrue(lines.any { it.contains("(EditText) edit") && it.contains("en.wikipedia.org") })
    }

    @Test
    fun emulatorChromeKeyboard() {
        val t = tree("emulator/chrome_keyboard.json")
        assertTrue(t.keyboardShown)
        val ime = t.windows.single { it.type == "input_method" }
        val c = LayaCandidates.build(t)
        assertTrue(c.none { t.nodes[it.nodeIndex].window == ime.id })
        assertTrue(c.toString(), c.any { it.editable })
    }

    @Test
    fun emulatorClockAndHome() {
        val clock = LayaCandidates.uiLines(LayaCandidates.build(tree("emulator/clock.json")))
        assertTrue(clock.toString(), clock.any { it.endsWith("] Add alarm (Button)") })
        assertTrue(clock.any { it.contains("] Timers (") })
        val homeTree = tree("emulator/home.json")
        assertEquals("com.google.android.apps.nexuslauncher", homeTree.packageName)
        val home = LayaCandidates.uiLines(LayaCandidates.build(homeTree))
        assertTrue(home.toString(), home.any { it.endsWith("] Settings (TextView)") })
        assertTrue(home.any { it.endsWith("] Chrome (TextView)") })
    }

    private fun descendants(t: UiTree, root: Int): Int {
        val inside = BooleanArray(t.nodes.size)
        inside[root] = true
        var n = 0
        for (node in t.nodes) if (node.parent >= 0 && inside[node.parent]) { inside[node.id] = true; n++ }
        return n
    }
}
