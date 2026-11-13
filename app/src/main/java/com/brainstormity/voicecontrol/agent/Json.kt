package com.brainstormity.voicecontrol.agent

/**
 * Minimal JSON writer for request bodies and run reports.
 *
 * Why not org.json for writing: the JVM (Maven) org.json used by unit tests does not keep key order, while
 * the order of `criteria` options is part of what JEV sees. Maps are written in iteration order, so use
 * `linkedMapOf` / `mapOf` (both ordered) to get exactly upstream's key order.
 */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value, null, 0) }.toString()

    /** Pretty-printed with [indent] spaces (like Python's `json.dumps(indent=2)`). */
    fun pretty(value: Any?, indent: Int = 2): String =
        StringBuilder().also { append(it, value, " ".repeat(indent), 0) }.toString()

    private fun append(sb: StringBuilder, v: Any?, ind: String?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> number(sb, v)
            is Float -> number(sb, v.toDouble())
            is Number -> number(sb, v.toDouble())
            is Map<*, *> -> container(sb, '{', '}', v.entries.toList(), ind, depth) { e ->
                e as Map.Entry<*, *>
                quote(sb, e.key.toString())
                sb.append(if (ind != null) ": " else ":")
                append(sb, e.value, ind, depth + 1)
            }
            is Iterable<*> -> container(sb, '[', ']', v.toList(), ind, depth) { append(sb, it, ind, depth + 1) }
            is Array<*> -> container(sb, '[', ']', v.toList(), ind, depth) { append(sb, it, ind, depth + 1) }
            is Pair<*, *> -> append(sb, listOf(v.first, v.second), ind, depth)
            else -> quote(sb, v.toString())
        }
    }

    private inline fun container(
        sb: StringBuilder, open: Char, close: Char, items: List<Any?>, ind: String?, depth: Int,
        item: (Any?) -> Unit,
    ) {
        sb.append(open)
        if (items.isEmpty()) { sb.append(close); return }
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            if (ind != null) sb.append('\n').append(ind.repeat(depth + 1))
            item(it)
        }
        if (ind != null) sb.append('\n').append(ind.repeat(depth))
        sb.append(close)
    }

    private fun number(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        sb.append(d.toString())  // e.g. 0.5, 1.0, 1.0E-4 (all valid JSON numbers)
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append(String.format("\\u%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }
}
