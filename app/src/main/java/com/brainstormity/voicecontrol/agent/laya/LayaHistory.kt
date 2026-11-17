package com.brainstormity.voicecontrol.agent.laya

/**
 * Turns TaskRunner's history strings into Laya-Android's training format (Byun11 `history_str`), for the state's
 * `history` (last 3, oldest first).
 *
 * TaskRunner writes `"$action $detail"` (+ `" (nothing changed on screen)"`): `CLICK at (540, 1200)`,
 * `TYPE 'hello' at (x, y)`, `TYPE_ENTER 'weather' + Enter at (x, y)`, `OPEN_APP Settings`, `SCROLL_DOWN`, ...
 * Pixel taps carry no label, so this keeps, per run, the candidates (and the element Laya chose) of the step at
 * which each entry was added ([remember]); a tap is grounded on them (the chosen element if the tap is inside it,
 * else Byun11's `ground`: smallest containing box).
 *
 * | ours | Laya |
 * |---|---|
 * | CLICK / DOUBLE_CLICK / LONG_PRESS at (x, y) | `CLICK <label>` / `LONG_PRESS <label>` (label, or short class if empty; `(unlabeled)` if no element) |
 * | TYPE / TYPE_ENTER 'text' ... | `INPUT_TEXT "text"` (the Enter is not a Laya op) |
 * | OPEN_APP Name | same |
 * | SCROLL_UP / SCROLL_DOWN, BACK, HOME, WAIT | same |
 * | SWIPE_LEFT / SWIPE_RIGHT | `SCROLL_RIGHT` / `SCROLL_LEFT` (inverse of LayaPolicy.mapOp) |
 * | OPEN_WEBSITE url, ENTER, ALL_APPS, anything else | kept as is (not in Laya's vocabulary; best effort) |
 * The "(nothing changed on screen)" note is dropped: Laya was never trained with it.
 */
class LayaHistory {
    private class Memo(val cands: List<Candidate>, val chosen: Candidate?)

    private var task: String? = null
    private val seen = ArrayList<String>()
    private val converted = ArrayList<String>()
    private val memos = HashMap<Int, Memo>()

    /** Laya history lines (all of them, oldest first) for this step's TaskRunner [history]. */
    @Synchronized
    fun convert(task: String, history: List<String>): List<String> {
        val samePrefix = this.task == task && history.size >= seen.size && history.subList(0, seen.size) == seen
        if (!samePrefix) {
            this.task = task
            seen.clear(); converted.clear(); memos.clear()
        }
        for (i in seen.size until history.size) {
            converted += entry(history[i], memos[i])
            seen += history[i]
        }
        return converted.toList()
    }

    /** The step that sees [historySize] entries showed [cands] and Laya picked [chosen]: its action becomes entry [historySize]. */
    @Synchronized
    fun remember(historySize: Int, cands: List<Candidate>, chosen: Candidate?) {
        memos[historySize] = Memo(cands, chosen)
    }

    private fun entry(raw: String, memo: Memo?): String {
        val s = raw.removeSuffix(NOTHING_CHANGED).trim()
        TAP.matchEntire(s)?.let { m ->
            val op = if (m.groupValues[1] == "LONG_PRESS") "LONG_PRESS" else "CLICK"
            val x = m.groupValues[2].toInt()
            val y = m.groupValues[3].toInt()
            val target = memo?.let { md ->
                md.chosen?.takeIf { inside(it, x, y) } ?: LayaCandidates.ground(md.cands, x, y)
            }
            return LayaCandidates.historyEntry(op, target)
        }
        TYPE.matchEntire(s)?.let { m -> return LayaCandidates.historyEntry("INPUT_TEXT", text = unRepr(m.groupValues[2])) }
        return when (s) {
            "SWIPE_LEFT" -> "SCROLL_RIGHT"
            "SWIPE_RIGHT" -> "SCROLL_LEFT"
            else -> s
        }
    }

    private fun inside(c: Candidate, x: Int, y: Int) = x >= c.bounds[0] && x <= c.bounds[2] && y >= c.bounds[1] && y <= c.bounds[3]

    companion object {
        const val NOTHING_CHANGED = " (nothing changed on screen)"
        private val TAP = Regex("""(CLICK|DOUBLE_CLICK|LONG_PRESS) at \((-?\d+), (-?\d+)\)""")
        private val TYPE = Regex("""(TYPE|TYPE_ENTER) ('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")(?: \+ Enter)? at \((-?\d+), (-?\d+)\)""")

        /** Inverse of AgentRules.pyRepr (quotes stripped; \\ \' \" \n \r \t \xNN). */
        fun unRepr(q: String): String {
            if (q.length < 2) return q
            val body = q.substring(1, q.length - 1)
            val sb = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'x' -> if (i + 3 < body.length) {
                            sb.append(body.substring(i + 2, i + 4).toInt(16).toChar()); i += 2
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}
