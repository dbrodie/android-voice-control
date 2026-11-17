package com.brainstormity.voicecontrol.agent.laya

import kotlin.math.exp
import kotlin.math.ln

/**
 * One Laya `choice` question: [instructions] and its criteria (option key -> description, insertion order;
 * a null/empty description renders the bare key).
 */
data class LayaQuestion(val instructions: String, val criteria: LinkedHashMap<String, String?>) {
    val keys: List<String> get() = criteria.keys.toList()
    val size: Int get() = criteria.size
}

/** One question's model input: [ids] (CLS ... SEP), the option [markers] (positions of each `[MASK]`), qtype. */
class LayaEncoded(
    val qid: String,
    val ids: IntArray,
    val markers: IntArray,
    /** Tokens of the state cut off at the end (right truncation) to fit the window. */
    val stateDropped: Int,
    /** Number of option markers that did NOT fit the window (the question is then invalid). */
    val markersLost: Int,
) {
    val fits: Boolean get() = markersLost == 0
}

/** A step's questions encoded for one window. [window] is the signature to run (256, 512, 1024 or 2048). */
class LayaPlan(val window: Int, val questions: List<LayaEncoded>, val stateTokens: Int) {
    val truncated: Boolean get() = questions.any { it.stateDropped > 0 }
    val fits: Boolean get() = questions.all { it.fits }
    val longest: Int get() = questions.maxOfOrNull { it.ids.size } ?: 0
}

/**
 * The per-question sequence of laya 0.3.28 (`laya.common.build_head` + `build_sequence`), exactly as
 * `tools/laya/host.py` / HOST_CONTRACT.md section 3 (pure Kotlin, JVM-tested against the T14 fixtures):
 * ```
 * [CLS] choice question: <instructions> [SEP] [MASK] opt0 [MASK] opt1 ... [SEP] <state json> [SEP]
 * ```
 */
object LayaSequence {
    const val HEAD_MAX_LEN = 1536
    const val OPTION_TOKENS = 48
    const val MASK_TEXT = "<mask>"
    val WINDOWS = intArrayOf(256, 512, 1024, 2048)
    const val MAX_WINDOW = 2048

    /** qtype one-hot index: choice = 0 (all our questions). */
    const val QTYPE_CHOICE = 0

    /** Option text as laya renders it: `key` or `key: desc`. */
    fun optionText(key: String, desc: String?): String = if (desc.isNullOrEmpty()) key else "$key: $desc"

    /**
     * One question's ids for window [maxLen]. [encode] tokenizes without special tokens; [stateIds] is the
     * step's state, tokenized once ([encodeState]).
     */
    fun build(
        encode: (String) -> IntArray, stateIds: IntArray, qid: String, q: LayaQuestion, maxLen: Int,
        headMaxLen: Int = HEAD_MAX_LEN,
    ): LayaEncoded {
        val ins = q.instructions.replace(MASK_TEXT, " ")
        var head = encode("choice question: $ins")
        var opts = q.criteria.map { (k, v) ->
            val t = encode(" " + optionText(k, v).replace(MASK_TEXT, " "))
            intArrayOf(LayaTokenizer.MASK) + (if (t.size > OPTION_TOKENS) t.copyOf(OPTION_TOKENS) else t)
        }
        var optBudget = headMaxLen - opts.sumOf { it.size }
        if (optBudget < 16) {
            val per = maxOf(4, (headMaxLen - 16) / maxOf(1, opts.size))
            opts = opts.map { if (it.size > per) it.copyOf(per) else it }
            optBudget = headMaxLen - opts.sumOf { it.size }
        }
        val headLen = maxOf(8, optBudget)
        if (head.size > headLen) head = head.copyOf(headLen)
        val ids = IntArrayList(maxLen + 8)
        ids.add(LayaTokenizer.CLS)
        for (t in head) ids.add(t)
        ids.add(LayaTokenizer.SEP)
        val markers = IntArray(opts.size)
        for ((i, o) in opts.withIndex()) {
            markers[i] = ids.size
            for (t in o) ids.add(t)
        }
        ids.add(LayaTokenizer.SEP)
        val room = maxOf(0, maxLen - ids.size - 1)
        val take = minOf(room, stateIds.size)
        for (i in 0 until take) ids.add(stateIds[i])
        ids.add(LayaTokenizer.SEP)
        val all = ids.toArray()
        val cut = if (all.size > maxLen) all.copyOf(maxLen) else all
        val kept = markers.filter { it < maxLen }.toIntArray()
        return LayaEncoded(qid, cut, kept, stateIds.size - take, markers.size - kept.size)
    }

    /** The state tokens: `json.dumps(state, ensure_ascii=False)` with `<mask>` scrubbed. */
    fun encodeState(encode: (String) -> IntArray, stateJson: String): IntArray =
        encode(stateJson.replace(MASK_TEXT, " "))

    /**
     * Encodes every question at [maxWindow]; if nothing was truncated, re-labels the plan with the smallest
     * window that holds the longest sequence (identical ids: only the padding differs, HOST_CONTRACT section 4).
     */
    fun plan(
        encode: (String) -> IntArray, stateJson: String, questions: Map<String, LayaQuestion>,
        maxWindow: Int = MAX_WINDOW, windows: IntArray = WINDOWS,
    ): LayaPlan {
        val stateIds = encodeState(encode, stateJson)
        return planWithState(encode, stateIds, questions, maxWindow, windows)
    }

    fun planWithState(
        encode: (String) -> IntArray, stateIds: IntArray, questions: Map<String, LayaQuestion>,
        maxWindow: Int = MAX_WINDOW, windows: IntArray = WINDOWS,
    ): LayaPlan {
        val encoded = questions.map { (qid, q) -> build(encode, stateIds, qid, q, maxWindow) }
        val truncated = encoded.any { it.stateDropped > 0 || !it.fits }
        val longest = encoded.maxOfOrNull { it.ids.size } ?: 0
        val window = if (truncated) maxWindow else windows.filter { it <= maxWindow }.firstOrNull { it >= longest } ?: maxWindow
        return LayaPlan(window, encoded, stateIds.size)
    }

    // ------------------------------------------------------------------ state JSON

    /**
     * `json.dumps({"goal": goal, "history": history, "ui": ui}, ensure_ascii=False)`, byte-identical:
     * separators `", "` / `": "`, non-ASCII raw (incl. U+2028/U+2029 and astral chars), `/` NOT escaped,
     * escapes only `"` `\` `\n` `\r` `\t` `\b` `\f` and other U+0000-U+001F as `\u00xx` (lowercase hex).
     */
    fun stateJson(goal: String, history: List<String>, ui: List<String>): String {
        val sb = StringBuilder(64 + goal.length + history.sumOf { it.length + 4 } + ui.sumOf { it.length + 4 })
        sb.append("{\"goal\": ")
        appendPyString(sb, goal)
        sb.append(", \"history\": ")
        appendList(sb, history)
        sb.append(", \"ui\": ")
        appendList(sb, ui)
        sb.append('}')
        return sb.toString()
    }

    private fun appendList(sb: StringBuilder, items: List<String>) {
        sb.append('[')
        items.forEachIndexed { i, s ->
            if (i > 0) sb.append(", ")
            appendPyString(sb, s)
        }
        sb.append(']')
    }

    /** Python json string encoding with ensure_ascii=False. */
    fun appendPyString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** laya's `_decode_answers` for `choice` questions (HOST_CONTRACT section 6). */
object LayaDecoder {
    /** Laya-Android's `rl_agent_config.json` temperature[choice]; `temperature_by_options` is empty. */
    const val TEMPERATURE_CHOICE = 1.1609286069869995
    const val TEMP_MIN = 0.5
    const val TEMP_MAX = 5.0

    /** One decoded answer: probabilities in option order, the argmax key and both confidences. */
    class Answer(
        val keys: List<String>,
        val probabilities: DoubleArray,
        val raw: FloatArray,
    ) {
        val best: Int = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1 // first max
        val choice: String get() = keys[best]
        /** max(p): the calibrated number (gate on this). */
        val answerConfidence: Double get() = probabilities[best]
        /** 1 - H(p)/ln K (laya's "confidence"; display only). */
        val entropyConfidence: Double
            get() {
                val k = probabilities.size
                if (k < 2) return 1.0
                var h = 0.0
                for (p in probabilities) h -= p * ln(maxOf(p, 1e-12))
                return (1.0 - h / ln(k.toDouble())).coerceIn(0.0, 1.0)
            }
        fun probabilityMap(): LinkedHashMap<String, Double> =
            keys.indices.associateTo(LinkedHashMap()) { keys[it] to probabilities[it] }
    }

    /** temperature_by_options bucket name (unused by Laya-Android, kept for completeness). */
    fun bucket(k: Int): String = "choice:" + when {
        k <= 2 -> "2"
        k <= 5 -> "3-5"
        k <= 10 -> "6-10"
        else -> "11+"
    }

    fun clampTemperature(t: Double?): Double =
        if (t == null || t.isNaN() || t.isInfinite()) 1.0 else t.coerceIn(TEMP_MIN, TEMP_MAX)

    /** softmax(raw / T), in double, max-subtracted. */
    fun softmax(raw: FloatArray, temperature: Double): DoubleArray {
        if (raw.isEmpty()) return DoubleArray(0)
        val z = DoubleArray(raw.size) { raw[it] / temperature }
        val m = z.max()
        var s = 0.0
        for (i in z.indices) { z[i] = exp(z[i] - m); s += z[i] }
        for (i in z.indices) z[i] /= s
        return z
    }

    fun decode(keys: List<String>, raw: FloatArray, temperature: Double = TEMPERATURE_CHOICE): Answer =
        Answer(keys, softmax(raw, clampTemperature(temperature)), raw)
}
