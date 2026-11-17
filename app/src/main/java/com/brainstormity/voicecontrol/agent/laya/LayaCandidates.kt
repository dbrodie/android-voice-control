package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.core.UiNode
import com.brainstormity.voicecontrol.core.UiTree

/**
 * One UI element the Laya-Android model can pick. [id] is its position in the list (the `target` option key is
 * `id.toString()`); [option] is the option label (`"<label> (<class>) <flags>"`, the criteria value of the target
 * question); [line] is the state's `ui` entry (`"[id] " + option`). [nodeIndex] is the kept (deepest) node in
 * [UiTree.nodes]; [bounds] are its screen-clipped bounds [left, top, right, bottom] in screen pixels: tap the centre.
 * [label] is the full (≤ 60 char) label used for dedupe and history; [depth] is the node's depth (root = 0).
 */
class Candidate(
    val id: Int,
    val line: String,
    val option: String,
    val label: String,
    val className: String,
    val resId: String,
    val nodeIndex: Int,
    val bounds: IntArray,
    val depth: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
) {
    val centerX: Int get() = (bounds[0] + bounds[2]) / 2
    val centerY: Int get() = (bounds[1] + bounds[3]) / 2
    override fun toString(): String = "$line @${bounds.joinToString(",", "[", "]")} node=$nodeIndex"
}

/**
 * Kotlin port of Byun11/laya-android's candidate extraction and serialization (the input format the
 * ByunByun/laya-android fine-tune was trained on; anything else is out of distribution). Sources, read at commit
 * HEAD of 2026-11-17: `src/accessibility.py` (visible_box, own_text, label), `src/candidates.py` (build, ground),
 * `src/serialization.py` (option_label, `"[%d] %s"` ui lines), `scripts/build_androidcontrol_dataset.py`
 * (history_str, HISTORY = 3). Golden outputs produced by running that Python on our fixtures live in
 * `app/src/test/resources/trees/laya_golden.json` (generator: `app/src/test/golden/gen_laya_golden.py`).
 *
 * Python semantics kept on purpose: `str.split()` / `strip()` whitespace set ([isPySpace]), slicing by code point
 * (`[:60]`, `[:40]`), string ordering by code point, `or` on empty strings, strict `>` on depth (first node seen
 * wins ties), descendant-text loop condition `sum(len(p) + 1) < limit`.
 */
object LayaCandidates {
    const val LABEL_LIMIT = 60
    const val OPTION_LABEL_LIMIT = 40
    const val HISTORY = 3

    /**
     * The candidate list for [tree]: visible nodes outside the input-method window that are clickable,
     * long-clickable, editable, scrollable or checkable, with bounds clipped to the screen; deduped on
     * (clipped bounds, label) keeping the deepest node and OR-ing the action flags; sorted by
     * (top, left, bottom, right, label, class).
     *
     * [topApplicationWindowOnly] is NOT part of Laya-Android (default false = faithful): when true, nodes of
     * application windows below the topmost application window (e.g. the activity behind a dialog) are skipped.
     */
    fun build(tree: UiTree, topApplicationWindowOnly: Boolean = false): List<Candidate> {
        val nodes = tree.nodes
        val n = nodes.size
        val children = Array(n) { ArrayList<Int>(2) }
        val depth = IntArray(n)
        for (i in 0 until n) {
            val p = nodes[i].parent
            if (p in 0 until n && p != i) {
                children[p].add(i)
                // Pre-order: parents precede children, so depth[p] is final. Fall back gracefully otherwise.
                depth[i] = if (p < i) depth[p] + 1 else 1
            }
        }
        val windowType = tree.windows.associate { it.id to it.type }
        val skipWindows = if (topApplicationWindowOnly) belowTopApplication(tree) else emptySet()
        val w = tree.screen.width
        val h = tree.screen.height

        val groups = LinkedHashMap<Pair<List<Int>, String>, Group>()
        for (i in 0 until n) {
            val node = nodes[i]
            if (windowType[node.window] == IME_WINDOW || !node.hasActionFlag()) continue
            if (node.window in skipWindows) continue
            val box = visibleBox(node, w, h) ?: continue
            val lab = label(i, nodes, children)
            val key = box.toList() to lab
            val c = groups[key]
            if (c == null || depth[i] > c.depth) {
                groups[key] = Group(
                    label = lab,
                    className = node.className.substringAfterLast('.'),
                    resId = node.resId.substringAfterLast('/'),
                    enabled = node.enabled, checked = node.checked, selected = node.selected,
                    bounds = box, depth = depth[i], nodeIndex = i,
                    clickable = node.clickable || (c?.clickable ?: false),
                    longClickable = node.longClickable || (c?.longClickable ?: false),
                    editable = node.editable || (c?.editable ?: false),
                    scrollable = node.scrollable || (c?.scrollable ?: false),
                    checkable = node.checkable || (c?.checkable ?: false),
                )
            } else {
                c.clickable = c.clickable || node.clickable
                c.longClickable = c.longClickable || node.longClickable
                c.editable = c.editable || node.editable
                c.scrollable = c.scrollable || node.scrollable
                c.checkable = c.checkable || node.checkable
            }
        }
        val sorted = groups.values.sortedWith(
            compareBy<Group>({ it.bounds[1] }, { it.bounds[0] }, { it.bounds[3] }, { it.bounds[2] })
                .then { a, b -> compareCodePoints(a.label, b.label) }
                .then { a, b -> compareCodePoints(a.className, b.className) }
        )
        return sorted.mapIndexed { id, g ->
            val option = optionLabel(g.label, g.className, g.editable, g.longClickable, g.clickable, g.scrollable,
                g.checked, g.selected, g.enabled)
            Candidate(
                id = id, line = "[$id] $option", option = option, label = g.label, className = g.className,
                resId = g.resId, nodeIndex = g.nodeIndex, bounds = g.bounds, depth = g.depth,
                clickable = g.clickable, longClickable = g.longClickable, editable = g.editable,
                scrollable = g.scrollable, checkable = g.checkable, checked = g.checked, selected = g.selected,
                enabled = g.enabled,
            )
        }
    }

    /** The state's `ui` list: `"[i] <label> (<class>) <flags>"` per candidate. */
    fun uiLines(cands: List<Candidate>): List<String> = cands.map { it.line }

    /** The `target` question's criteria: `{"0": option0, "1": option1, ...}` in candidate order. */
    fun targetCriteria(cands: List<Candidate>): LinkedHashMap<String, String> =
        cands.associateTo(LinkedHashMap()) { it.id.toString() to it.option }

    /**
     * Port of `serialization.option_label`. Flags, in this order, space-separated: `edit` (editable), `long`
     * (long-clickable and not clickable), `scroll` (scrollable and not clickable), `on` (checked), `selected`,
     * `disabled` (not enabled). `checkable` alone is not rendered. Empty label -> `-`.
     */
    fun optionLabel(
        label: String, className: String, editable: Boolean, longClickable: Boolean, clickable: Boolean,
        scrollable: Boolean, checked: Boolean, selected: Boolean, enabled: Boolean,
    ): String {
        val parts = ArrayList<String>(8)
        parts += pySlice(label, OPTION_LABEL_LIMIT).ifEmpty { "-" }
        parts += "($className)"
        if (editable) parts += "edit"
        if (longClickable && !clickable) parts += "long"
        if (scrollable && !clickable) parts += "scroll"
        if (checked) parts += "on"
        if (selected) parts += "selected"
        if (!enabled) parts += "disabled"
        return parts.joinToString(" ")
    }

    /**
     * Port of `candidates.ground`: the candidate a tap at (x, y) acts on, or null. Smallest containing box (edges
     * inclusive); ties -> deepest; then lowest id. Useful to turn a pixel tap (e.g. from JEV) into history.
     */
    fun ground(cands: List<Candidate>, x: Int, y: Int): Candidate? =
        cands.filter { x >= it.bounds[0] && x <= it.bounds[2] && y >= it.bounds[1] && y <= it.bounds[3] }
            .minWithOrNull(compareBy<Candidate>({ area(it.bounds) }, { -it.depth }, { it.id }))

    // ------------------------------------------------------------------ history (build_androidcontrol_dataset.py)

    /**
     * One history entry, port of `history_str(op, action, cands, gold)`. [op] is a Laya-Android op name
     * (CLICK, LONG_PRESS, SCROLL_UP/DOWN/LEFT/RIGHT, INPUT_TEXT, OPEN_APP, BACK, HOME, WAIT, DONE):
     * - CLICK / LONG_PRESS: `"CLICK <label>"` with the target's full (≤ 60 char) label, or its short class name if
     *   the label is empty, or `"CLICK (unlabeled)"` when the tap hit no candidate ([target] null);
     * - INPUT_TEXT: `INPUT_TEXT "<text>"` (no escaping); OPEN_APP: `"OPEN_APP <app name>"` (the app's display name
     *   in AndroidControl, e.g. "Settings", not the package); anything else: the op name alone.
     */
    fun historyEntry(op: String, target: Candidate? = null, text: String? = null, appName: String? = null): String =
        when (op) {
            "CLICK", "LONG_PRESS" -> "$op " + (if (target != null) target.label.ifEmpty { target.className } else "(unlabeled)")
            "INPUT_TEXT" -> "INPUT_TEXT \"${text ?: ""}\""
            "OPEN_APP" -> "OPEN_APP ${appName ?: ""}"
            else -> op
        }

    /** The state's `history`: the last [HISTORY] entries, oldest first (`hist[-3:]`). */
    fun historyLines(history: List<String>): List<String> = history.takeLast(HISTORY)

    // ------------------------------------------------------------------ accessibility.py

    private const val IME_WINDOW = "input_method"

    private class Group(
        val label: String, val className: String, val resId: String, val enabled: Boolean, val checked: Boolean,
        val selected: Boolean, val bounds: IntArray, val depth: Int, val nodeIndex: Int,
        var clickable: Boolean, var longClickable: Boolean, var editable: Boolean, var scrollable: Boolean,
        var checkable: Boolean,
    )

    private fun UiNode.hasActionFlag() = clickable || longClickable || editable || scrollable || checkable

    /** `visible_box`: bounds clipped to the screen, or null when invisible / zero-area / off-screen. */
    internal fun visibleBox(node: UiNode, w: Int, h: Int): IntArray? {
        if (!node.visible) return null
        val x1 = maxOf(0, node.bounds[0])
        val y1 = maxOf(0, node.bounds[1])
        val x2 = minOf(w, node.bounds[2])
        val y2 = minOf(h, node.bounds[3])
        if (x2 <= x1 || y2 <= y1) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    /** `own_text`: `(text or content_desc or hint or "").strip()` (first NON-EMPTY field, then stripped). */
    internal fun ownText(node: UiNode): String =
        pyStrip(node.text.ifEmpty { node.desc.ifEmpty { node.hint } })

    /** `label`: own text, else descendant texts in DFS order, whitespace-collapsed, capped at [limit] code points. */
    internal fun label(i: Int, nodes: List<UiNode>, children: Array<out List<Int>>, limit: Int = LABEL_LIMIT): String {
        val t = ownText(nodes[i])
        if (t.isNotEmpty()) return pySlice(pySplitJoin(t), limit)
        val parts = ArrayList<String>()
        var partsLen = 0 // sum(len(p) + 1 for p in parts), in code points
        val stack = ArrayList<Int>(children[i].asReversed())
        val seen = HashSet<Int>()
        while (stack.isNotEmpty() && partsLen < limit) {
            val cid = stack.removeAt(stack.size - 1)
            if (!seen.add(cid)) continue
            val ct = ownText(nodes[cid])
            if (ct.isNotEmpty()) {
                val p = pySplitJoin(ct)
                parts += p
                partsLen += p.codePointCount(0, p.length) + 1
            }
            stack.addAll(children[cid].asReversed())
        }
        return pySlice(parts.joinToString(" "), limit)
    }

    private fun area(b: IntArray) = (b[2] - b[0]).toLong() * (b[3] - b[1])

    /** Application windows other than the topmost one (highest layer). */
    private fun belowTopApplication(tree: UiTree): Set<Int> {
        val apps = tree.windows.filter { it.type == "application" }
        val top = apps.maxByOrNull { it.layer } ?: return emptySet()
        return apps.filter { it.id != top.id }.mapTo(HashSet()) { it.id }
    }

    // ------------------------------------------------------------------ Python string semantics

    /** Python 3 `str.isspace()` for one code point (the set `str.split()` / `strip()` use). */
    internal fun isPySpace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    internal fun pyStrip(s: String): String {
        var start = 0
        var end = s.length
        while (start < end) {
            val cp = s.codePointAt(start)
            if (!isPySpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!isPySpace(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    /** `" ".join(s.split())`. */
    internal fun pySplitJoin(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var pendingSpace = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isPySpace(cp)) {
                if (sb.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** `s[:n]` by code point. */
    internal fun pySlice(s: String, n: Int): String {
        if (s.length <= n) return s // fewer UTF-16 units than n -> fewer code points too
        return if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
    }

    /** Python string ordering (by code point, not UTF-16 unit). */
    internal fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return when {
            i < a.length -> 1   // b is a proper prefix of a
            j < b.length -> -1
            else -> 0
        }
    }
}
