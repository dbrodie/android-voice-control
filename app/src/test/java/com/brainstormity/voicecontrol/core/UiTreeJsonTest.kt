package com.brainstormity.voicecontrol.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeJsonTest {

    private fun res(name: String) = javaClass.classLoader!!.getResource("trees/$name")!!.readText()

    private val fixtures = listOf(
        "settings_like.json", "chrome_like.json", "dialog_over_content.json", "keyboard_shown.json",
        "many_candidates.json", "nested_recycler.json",
        "emulator/settings.json", "emulator/chrome_wikipedia.json", "emulator/chrome_keyboard.json",
        "emulator/clock.json", "emulator/home.json",
    )

    private fun assertTreesEqual(a: UiTree, b: UiTree) {
        assertEquals(a.packageName, b.packageName)
        assertEquals(a.activity, b.activity)
        assertEquals(a.screen, b.screen)
        assertEquals(a.keyboardShown, b.keyboardShown)
        assertEquals(a.takenAt, b.takenAt)
        assertEquals(a.windows.size, b.windows.size)
        a.windows.zip(b.windows).forEach { (x, y) ->
            assertEquals(listOf(x.id, x.type, x.layer, x.active, x.focused), listOf(y.id, y.type, y.layer, y.active, y.focused))
            assertArrayEquals(x.bounds, y.bounds)
        }
        assertEquals(a.nodes.size, b.nodes.size)
        val noBounds = IntArray(0)
        a.nodes.zip(b.nodes).forEach { (x, y) ->
            assertArrayEquals(x.bounds, y.bounds)
            // data-class equality on everything but the array (arrays compare by identity)
            assertEquals(x.copy(bounds = noBounds), y.copy(bounds = noBounds))
        }
    }

    @Test
    fun roundTripsEveryFixture() {
        for (f in fixtures) {
            val tree = UiTreeJson.decode(res(f))
            val json = UiTreeJson.encode(tree)
            val again = UiTreeJson.decode(json)
            assertTreesEqual(tree, again)
            assertEquals("$f: encode is stable", json, UiTreeJson.encode(again))
        }
    }

    @Test
    fun encodesExactlyTheDocumentedSchema() {
        val tree = UiTree(
            packageName = "com.android.settings", activity = "com.android.settings.Settings",
            screen = ScreenSize(1080, 2400), keyboardShown = false, takenAt = 1731800000000,
            windows = listOf(UiWindow(1, "application", 0, true, true, intArrayOf(0, 0, 1080, 2400))),
            nodes = listOf(
                UiNode(0, -1, 1, "android.widget.FrameLayout", "", "", "", "", intArrayOf(0, 0, 1080, 2400),
                    clickable = false, longClickable = false, editable = false, scrollable = false, checkable = false,
                    checked = false, selected = false, enabled = true, focused = false, password = false, visible = true)
            ),
        )
        val o = JSONObject(UiTreeJson.encode(tree))
        assertEquals(listOf("activity", "keyboard_shown", "nodes", "package", "screen", "taken_at", "windows"),
            o.keySet().sorted())
        assertEquals(listOf("h", "w"), o.getJSONObject("screen").keySet().sorted())
        assertEquals(listOf("active", "bounds", "focused", "id", "layer", "type"),
            o.getJSONArray("windows").getJSONObject(0).keySet().sorted())
        assertEquals(
            listOf("bounds", "checkable", "checked", "class", "clickable", "desc", "editable", "enabled", "focused",
                "hint", "id", "long_clickable", "parent", "password", "res_id", "scrollable", "selected", "text",
                "visible", "window"),
            o.getJSONArray("nodes").getJSONObject(0).keySet().sorted())
        // key order in the text follows the KDoc
        val text = UiTreeJson.encode(tree)
        assertTrue(text.startsWith("{\"package\":\"com.android.settings\",\"activity\":\"com.android.settings.Settings\"," +
            "\"screen\":{\"w\":1080,\"h\":2400},\"keyboard_shown\":false,\"taken_at\":1731800000000,"))
        assertTrue(text.contains("{\"id\":0,\"parent\":-1,\"window\":1,\"class\":\"android.widget.FrameLayout\",\"text\":\"\"," +
            "\"desc\":\"\",\"hint\":\"\",\"res_id\":\"\",\"bounds\":[0,0,1080,2400],\"clickable\":false,\"long_clickable\":false," +
            "\"editable\":false,\"scrollable\":false,\"checkable\":false,\"checked\":false,\"selected\":false,\"enabled\":true," +
            "\"focused\":false,\"password\":false,\"visible\":true}"))
    }

    @Test
    fun escapesAwkwardText() {
        val weird = "quote\" backslash\\ slash/ nl\n tab\t ctl\u0001 ls  emoji😀 nbsp "
        val tree = UiTree("p", "", ScreenSize(1, 1), false, 0, emptyList(), listOf(
            UiNode(0, -1, 0, "c", weird, weird, "", "", intArrayOf(0, 0, 1, 1), false, false, false, false, false,
                false, false, true, false, false, true)))
        val json = UiTreeJson.encode(tree)
        assertTrue(json.contains("\\u2028"))
        assertTrue(json.contains("\\u0001"))
        assertEquals(weird, UiTreeJson.decode(json).nodes[0].text)
        assertEquals(weird, JSONObject(json).getJSONArray("nodes").getJSONObject(0).getString("desc"))
    }

    @Test
    fun decodeDefaultsMissingKeys() {
        val t = UiTreeJson.decode("""{"package":"x","screen":{"w":10,"h":20},"windows":[{"id":3}],
            "nodes":[{"parent":-1,"window":3,"text":"hi"}]}""")
        assertEquals(ScreenSize(10, 20), t.screen)
        assertEquals("unknown", t.windows[0].type)
        val n = t.nodes[0]
        assertEquals(0, n.id)
        assertTrue(n.enabled && n.visible)
        assertArrayEquals(IntArray(4), n.bounds)
    }
}
