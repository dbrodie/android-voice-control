package com.brainstormity.voicecontrol.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskParserTest {
    private val allApps = TaskParser.KNOWN_APPS.values.toSet()

    @Test fun quotedTexts() {
        assertEquals(listOf("Hello from my JEV agent!"),
            TaskParser.quotedTexts("Go to x.com and post \"Hello from my JEV agent!\""))
        assertEquals(listOf("cat videos"), TaskParser.quotedTexts("Search for “cat videos”"))
        assertEquals(listOf("hi", "bye"), TaskParser.quotedTexts("Send \"hi\" and \"bye\""))
        // Unclosed quote runs to the end of the task.
        assertEquals(listOf("Hello world!"), TaskParser.quotedTexts("Post with text \"Hello world!   "))
        assertEquals(emptyList<String>(), TaskParser.quotedTexts("Open Settings"))
    }

    @Test fun textOptionsOffersQuotedFirstThenPhrases() {
        val t = TaskParser.textOptions("Search YouTube for \"cat videos\" now")
        assertEquals("cat videos", t[0])
        assertEquals(listOf("cat videos", "Search", "YouTube", "for", "now", "Search YouTube", "YouTube for",
            "for now", "Search YouTube for", "YouTube for now"), t)
    }

    @Test fun textOptionsSplitsTimes() {
        val t = TaskParser.textOptions("Set an alarm for 7.30")
        assertTrue(t.containsAll(listOf("7.30", "7", "30", "7:30")))
    }

    @Test fun textOptionsCappedAt200() {
        val task = (1..200).joinToString(" ") { "w$it" }
        assertEquals(200, TaskParser.textOptions(task).size)
    }

    @Test fun websites() {
        assertEquals(listOf("https://x.com"), TaskParser.websites("Go to x.com and post \"hi\""))
        assertEquals(listOf("https://en.wikipedia.org", "https://github.com/foo", "https://reddit.com"),
            TaskParser.websites("Open wikipedia, then github.com/foo, and Reddit"))
        assertEquals(listOf("https://example.org/path?q=1"), TaskParser.websites("open https://example.org/path?q=1."))
        assertEquals(emptyList<String>(), TaskParser.websites("Open Settings and turn on Wi-Fi"))
    }

    @Test fun appsWithTheCapitalXRule() {
        assertEquals(listOf(TaskParser.App("x", "com.twitter.android")),
            TaskParser.appsInTask("Post on X: \"hello\"", allApps))
        // lowercase x and x inside words don't count
        assertEquals(emptyList<TaskParser.App>(), TaskParser.appsInTask("Relax with an xbox, x marks it", allApps))
        assertEquals(listOf(TaskParser.App("settings", "com.android.settings")),
            TaskParser.appsInTask("Open Settings and turn on Wi-Fi", allApps))
        // only installed apps, in task order
        assertEquals(listOf(TaskParser.App("play store", "com.android.vending")),
            TaskParser.appsInTask("Open the Play Store and install Spotify", allApps - "com.spotify.music"))
        assertEquals(emptyList<TaskParser.App>(), TaskParser.appsInTask("Open Settings", emptySet()))
    }

    /** Golden outputs produced by running upstream's own Python functions (app/src/test/golden/gen_parser_golden.py). */
    @Test fun matchesUpstreamPythonOnGoldenCases() {
        val golden = JSONObject(javaClass.classLoader!!.getResource("upstream_parser_golden.json")!!.readText())
        val missing = golden.getJSONArray("some_missing").strings().toSet()
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val task = c.getString("task")
            assertEquals("quoted: $task", c.getJSONArray("quoted").strings(), TaskParser.quotedTexts(task))
            assertEquals("texts: $task", c.getJSONArray("texts").strings(), TaskParser.textOptions(task))
            assertEquals("sites: $task", c.getJSONArray("sites").strings(), TaskParser.websites(task))
            assertEquals("apps_all: $task", c.getJSONArray("apps_all").apps(),
                TaskParser.appsInTask(task, allApps))
            assertEquals("apps_some: $task", c.getJSONArray("apps_some").apps(),
                TaskParser.appsInTask(task, allApps - missing))
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.apps() = (0 until length()).map {
        getJSONArray(it).let { a -> TaskParser.App(a.getString(0), a.getString(1)) }
    }
}
