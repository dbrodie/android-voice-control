package com.brainstormity.voicecontrol.agent.laya

import java.io.Reader

/**
 * A minimal streaming (pull) JSON reader with no Android dependency, so the 34 MB `tokenizer.json` is read once
 * without building a generic tree of 256k boxed map entries + 580k merge lists (and JVM unit tests can use it;
 * android.util.JsonReader is a stub there). Strict RFC 8259 input is assumed (the files are machine-written).
 *
 * Usage mirrors android.util.JsonReader: [beginObject] / [hasNext] / [nextName] / value / [endObject].
 */
internal class LayaJsonReader(private val reader: Reader) {
    private val buf = CharArray(64 * 1024)
    private var pos = 0
    private var limit = 0
    private val sb = StringBuilder(64)

    /** Whether the next value in the current object/array exists (consumes a separating comma). */
    fun hasNext(): Boolean {
        val c = peekNonWs()
        if (c == ','.code) { pos++; return true }
        return c != '}'.code && c != ']'.code
    }

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    fun nextName(): String {
        val s = nextString()
        expect(':')
        return s
    }

    /** The next token's kind without consuming it: one of `{ [ " n t f` or a digit / '-'. */
    fun peek(): Char = peekNonWs().let { if (it < 0) throw error("unexpected end of JSON") else it.toChar() }

    fun nextNull() {
        expectWord("null")
    }

    fun nextBoolean(): Boolean = when (peek()) {
        't' -> { expectWord("true"); true }
        'f' -> { expectWord("false"); false }
        else -> throw error("expected a boolean")
    }

    /** A string, or null for JSON null. */
    fun nextStringOrNull(): String? = if (peek() == 'n') { nextNull(); null } else nextString()

    fun nextString(): String {
        expect('"')
        sb.setLength(0)
        while (true) {
            val c = read()
            when {
                c < 0 -> throw error("unterminated string")
                c == '"'.code -> return sb.toString()
                c == '\\'.code -> when (val e = read()) {
                    '"'.code -> sb.append('"')
                    '\\'.code -> sb.append('\\')
                    '/'.code -> sb.append('/')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000c')
                    'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r')
                    't'.code -> sb.append('\t')
                    'u'.code -> {
                        var v = 0
                        repeat(4) {
                            val h = read()
                            v = v * 16 + when (h) {
                                in '0'.code..'9'.code -> h - '0'.code
                                in 'a'.code..'f'.code -> h - 'a'.code + 10
                                in 'A'.code..'F'.code -> h - 'A'.code + 10
                                else -> throw error("bad \\u escape")
                            }
                        }
                        sb.append(v.toChar())
                    }
                    else -> throw error("bad escape \\${e.toChar()}")
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    /** A number as text (for ints and doubles). */
    private fun nextNumberText(): String {
        peekNonWs()
        sb.setLength(0)
        while (true) {
            if (pos >= limit && !fill()) break
            val c = buf[pos]
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') { sb.append(c); pos++ } else break
        }
        if (sb.isEmpty()) throw error("expected a number")
        return sb.toString()
    }

    fun nextInt(): Int = nextNumberText().toInt()
    fun nextLong(): Long = nextNumberText().toLong()
    fun nextDouble(): Double = nextNumberText().toDouble()

    /** Skips one value of any kind. */
    fun skipValue() {
        when (peek()) {
            '{' -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            '[' -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            '"' -> nextString()
            't', 'f' -> nextBoolean()
            'n' -> nextNull()
            else -> nextNumberText()
        }
    }

    /** Reads any value into Kotlin types (insertion-ordered maps, lists, String, Double/Long, Boolean, null). */
    fun readValue(): Any? = when (peek()) {
        '{' -> {
            beginObject()
            val m = LinkedHashMap<String, Any?>()
            while (hasNext()) { val k = nextName(); m[k] = readValue() }
            endObject(); m
        }
        '[' -> {
            beginArray()
            val l = ArrayList<Any?>()
            while (hasNext()) l.add(readValue())
            endArray(); l
        }
        '"' -> nextString()
        't', 'f' -> nextBoolean()
        'n' -> { nextNull(); null }
        else -> nextNumberText().let { t -> if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong() }
    }

    // ------------------------------------------------------------------ low level

    private fun fill(): Boolean {
        limit = reader.read(buf, 0, buf.size)
        pos = 0
        if (limit < 0) { limit = 0; return false }
        return true
    }

    private fun read(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos++].code
    }

    private fun peekNonWs(): Int {
        while (true) {
            if (pos >= limit && !fill()) return -1
            val c = buf[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return c.code
        }
    }

    private fun expect(ch: Char) {
        val c = peekNonWs()
        if (c != ch.code) throw error("expected '$ch' but found ${if (c < 0) "end" else "'${c.toChar()}'"}")
        pos++
    }

    private fun expectWord(w: String) {
        peekNonWs()
        for (ch in w) if (read() != ch.code) throw error("expected $w")
    }

    private fun error(msg: String) = IllegalArgumentException("JSON: $msg")
}
