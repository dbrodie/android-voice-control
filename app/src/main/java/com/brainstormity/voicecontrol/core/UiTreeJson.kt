package com.brainstormity.voicecontrol.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * [UiTree] <-> JSON, exactly the schema in the [UiTree] KDoc (snake_case keys). Pure Kotlin + org.json so it runs
 * in JVM unit tests and in the Python reference tooling's format.
 *
 * Encoding is written by hand (not via JSONObject) so key order is the documented one and output is deterministic
 * on both Android's org.json (insertion-ordered) and the JVM's (hash-ordered): one node per line, which keeps the
 * committed test dumps diffable. Decoding is lenient about missing keys (defaults: "" / false / -1, enabled and
 * visible default true) so hand-written fixtures can omit what they don't care about.
 */
object UiTreeJson {

    fun encode(tree: UiTree): String {
        val sb = StringBuilder(256 + tree.nodes.size * 300)
        sb.append("{\"package\":").q(tree.packageName)
            .append(",\"activity\":").q(tree.activity)
            .append(",\"screen\":{\"w\":").append(tree.screen.width).append(",\"h\":").append(tree.screen.height).append('}')
            .append(",\"keyboard_shown\":").append(tree.keyboardShown)
            .append(",\"taken_at\":").append(tree.takenAt)
            .append(",\n\"windows\":[")
        tree.windows.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append("\n{\"id\":").append(w.id)
                .append(",\"type\":").q(w.type)
                .append(",\"layer\":").append(w.layer)
                .append(",\"active\":").append(w.active)
                .append(",\"focused\":").append(w.focused)
                .append(",\"bounds\":").bounds(w.bounds)
                .append('}')
        }
        sb.append("],\n\"nodes\":[")
        tree.nodes.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            sb.append("\n{\"id\":").append(n.id)
                .append(",\"parent\":").append(n.parent)
                .append(",\"window\":").append(n.window)
                .append(",\"class\":").q(n.className)
                .append(",\"text\":").q(n.text)
                .append(",\"desc\":").q(n.desc)
                .append(",\"hint\":").q(n.hint)
                .append(",\"res_id\":").q(n.resId)
                .append(",\"bounds\":").bounds(n.bounds)
                .append(",\"clickable\":").append(n.clickable)
                .append(",\"long_clickable\":").append(n.longClickable)
                .append(",\"editable\":").append(n.editable)
                .append(",\"scrollable\":").append(n.scrollable)
                .append(",\"checkable\":").append(n.checkable)
                .append(",\"checked\":").append(n.checked)
                .append(",\"selected\":").append(n.selected)
                .append(",\"enabled\":").append(n.enabled)
                .append(",\"focused\":").append(n.focused)
                .append(",\"password\":").append(n.password)
                .append(",\"visible\":").append(n.visible)
                .append('}')
        }
        sb.append("]}\n")
        return sb.toString()
    }

    /** Parses [encode]'s output (or any JSON in the documented schema). Throws [org.json.JSONException] on bad input. */
    fun decode(json: String): UiTree {
        val o = JSONObject(json)
        val screen = o.optJSONObject("screen")
        val windows = o.optJSONArray("windows") ?: JSONArray()
        val nodes = o.optJSONArray("nodes") ?: JSONArray()
        return UiTree(
            packageName = o.optString("package", ""),
            activity = o.optString("activity", ""),
            screen = ScreenSize(screen?.optInt("w", 0) ?: 0, screen?.optInt("h", 0) ?: 0),
            keyboardShown = o.optBoolean("keyboard_shown", false),
            takenAt = o.optLong("taken_at", 0L),
            windows = List(windows.length()) { i ->
                val w = windows.getJSONObject(i)
                UiWindow(
                    id = w.getInt("id"),
                    type = w.optString("type", "unknown"),
                    layer = w.optInt("layer", 0),
                    active = w.optBoolean("active", false),
                    focused = w.optBoolean("focused", false),
                    bounds = w.optJSONArray("bounds").toBounds(),
                )
            },
            nodes = List(nodes.length()) { i ->
                val n = nodes.getJSONObject(i)
                UiNode(
                    id = n.optInt("id", i),
                    parent = n.optInt("parent", -1),
                    window = n.optInt("window", 0),
                    className = n.optString("class", ""),
                    text = n.optString("text", ""),
                    desc = n.optString("desc", ""),
                    hint = n.optString("hint", ""),
                    resId = n.optString("res_id", ""),
                    bounds = n.optJSONArray("bounds").toBounds(),
                    clickable = n.optBoolean("clickable", false),
                    longClickable = n.optBoolean("long_clickable", false),
                    editable = n.optBoolean("editable", false),
                    scrollable = n.optBoolean("scrollable", false),
                    checkable = n.optBoolean("checkable", false),
                    checked = n.optBoolean("checked", false),
                    selected = n.optBoolean("selected", false),
                    enabled = n.optBoolean("enabled", true),
                    focused = n.optBoolean("focused", false),
                    password = n.optBoolean("password", false),
                    visible = n.optBoolean("visible", true),
                )
            },
        )
    }

    private fun JSONArray?.toBounds(): IntArray =
        if (this == null) IntArray(4) else IntArray(4) { optInt(it, 0) }

    private fun StringBuilder.bounds(b: IntArray): StringBuilder {
        append('[')
        for (i in 0 until 4) {
            if (i > 0) append(',')
            append(b.getOrElse(i) { 0 })
        }
        return append(']')
    }

    /** JSON string literal. Escapes like org.json's JSONObject.quote, but leaves '/' alone and escapes U+2028/9. */
    private fun StringBuilder.q(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    append("\\u").append(String.format("%04x", c.code))
                } else append(c)
            }
        }
        return append('"')
    }
}
