package com.brainstormity.voicecontrol.agent.laya

import com.brainstormity.voicecontrol.agent.AgentRules
import com.brainstormity.voicecontrol.agent.TaskParser
import com.brainstormity.voicecontrol.core.UiTree

/**
 * Pure rules that turn Laya-Android's two trained answers into our [com.brainstormity.voicecontrol.agent.StepDecision]
 * vocabulary (AgentRules.PHONE_ACTIONS). No Android types: unit-tested in LayaPolicyTest.
 *
 * Questions (HOST_CONTRACT.md section 1; T14 found extra criteria hurt): `operation` over Byun11's 12 ops with
 * their exact descriptions, `target` over the candidates' option labels. Optional third: `text` (untrained).
 */
object LayaPolicy {
    /** Byun11 OP_DESC, byte-for-byte, in training order. */
    val OPS: LinkedHashMap<String, String> = linkedMapOf(
        "CLICK" to "tap a UI element",
        "LONG_PRESS" to "long-press a UI element",
        "SCROLL_UP" to "scroll up",
        "SCROLL_DOWN" to "scroll down",
        "SCROLL_LEFT" to "scroll left",
        "SCROLL_RIGHT" to "scroll right",
        "OPEN_APP" to "launch an app by name",
        "INPUT_TEXT" to "type text into the focused field",
        "BACK" to "press the system back button",
        "HOME" to "go to the home screen",
        "WAIT" to "wait for the screen to update",
        "DONE" to "the goal is complete; stop",
    )
    const val OPERATION_INSTRUCTIONS = "Which operation should be performed next?"
    const val TARGET_INSTRUCTIONS = "Which UI element should be targeted?"
    const val TEXT_INSTRUCTIONS = "Which text should be typed into the focused field?"
    /** Text options offered to the (untrained) text question at most. */
    const val MAX_TEXT_OPTIONS = 64

    fun operationQuestion() = LayaQuestion(OPERATION_INSTRUCTIONS, LinkedHashMap<String, String?>(OPS))

    fun targetQuestion(cands: List<Candidate>) =
        LayaQuestion(TARGET_INSTRUCTIONS, LinkedHashMap<String, String?>(LayaCandidates.targetCriteria(cands)))

    fun textQuestion(texts: List<String>) = LayaQuestion(TEXT_INSTRUCTIONS,
        texts.take(MAX_TEXT_OPTIONS).withIndex().associateTo(LinkedHashMap<String, String?>()) { (i, t) -> i.toString() to t })

    /**
     * Laya op -> our action. Direction check (T17): Byun11 folds AndroidControl's `scroll.direction` into the op
     * name (`src/data/android_control.py` canonical_op), and AndroidControl/AndroidWorld execute `scroll down` as a
     * drag from the centre to the TOP (`android_world/env/actuation.py`: down -> end_y = y_min; right -> end_x =
     * x_min), i.e. the direction names the content revealed and the finger moves the other way. Our
     * [com.brainstormity.voicecontrol.core.SwipeDirection] is the finger's direction (SCROLL_DOWN = swipe UP,
     * SWIPE_LEFT = finger right-to-left). Hence SCROLL_UP/DOWN keep their names, SCROLL_RIGHT -> SWIPE_LEFT and
     * SCROLL_LEFT -> SWIPE_RIGHT. INPUT_TEXT is TYPE or TYPE_ENTER ([inputTextAction]).
     */
    fun mapOp(op: String, inputTextAs: String): String = when (op) {
        "SCROLL_LEFT" -> "SWIPE_RIGHT"
        "SCROLL_RIGHT" -> "SWIPE_LEFT"
        "INPUT_TEXT" -> inputTextAs
        else -> op // CLICK, LONG_PRESS, SCROLL_UP, SCROLL_DOWN, OPEN_APP, BACK, HOME, WAIT, DONE
    }

    /** Ops that need a target element; with no candidates their mass can't be used. */
    val NEEDS_TARGET = setOf("CLICK", "LONG_PRESS", "INPUT_TEXT")

    /**
     * Our probabilities over [offered] (JevClient.offeredActions keys, in that order): each Laya op's mass goes to
     * its mapped action; ops we can't express here (mapped action not offered, e.g. OPEN_APP when the task names no
     * app, INPUT_TEXT without text options; or a tap op with [hasCandidates] false) are dropped, so the numbers may
     * sum to < 1 and an action can only win with mass the model really gave it. Actions Laya has no op for
     * (ENTER, ALL_APPS, OPEN_WEBSITE, DOUBLE_CLICK) get 0.
     */
    fun actionProbabilities(
        opProbs: Map<String, Double>, offered: Collection<String>, inputTextAs: String, hasCandidates: Boolean,
    ): LinkedHashMap<String, Double> {
        val out = offered.associateWithTo(LinkedHashMap()) { 0.0 }
        for ((op, p) in opProbs) {
            if (!hasCandidates && op in NEEDS_TARGET) continue
            val a = mapOp(op, inputTextAs)
            if (a in out) out[a] = out.getValue(a) + p
        }
        return out
    }

    /** Argmax (first max in [probs] order). */
    fun best(probs: Map<String, Double>): Pair<String, Double>? = probs.entries.maxByOrNull { it.value }?.toPair()

    // ------------------------------------------------------------------ typing

    private val SEARCH_HINTS = listOf("search", "url_bar", "omnibox", "query", "find", "suche", "buscar", "recherche")
    private val SEARCH_TASK = Regex("""\b(search|look up|google|find)\b""", RegexOption.IGNORE_CASE)

    /**
     * INPUT_TEXT -> TYPE_ENTER when the field looks like a search box, else TYPE. We can't see imeOptions (the
     * tree has no IME action), so: the field's resource id / hint / description / text / class mentions search
     * (Chrome's `url_bar` "Search or type web address", a SearchView, ...), or, if no field is known, the task
     * itself asks to search. AndroidControl has no ENTER op (submitting was a click on a suggestion or the key),
     * so pressing Enter right after typing a search is our addition (contract section 8).
     */
    fun inputTextAction(field: FieldInfo?, task: String): String {
        val looksSearch = field != null && listOf(field.resId, field.hint, field.desc, field.text, field.className)
            .any { s -> SEARCH_HINTS.any { s.contains(it, ignoreCase = true) } }
        return if (looksSearch || (field == null && SEARCH_TASK.containsMatchIn(task))) "TYPE_ENTER" else "TYPE"
    }

    /** What we know about a text field (from its UiNode). */
    data class FieldInfo(val resId: String, val hint: String, val desc: String, val text: String, val className: String)

    fun fieldInfo(tree: UiTree, c: Candidate): FieldInfo {
        val n = tree.nodes.getOrNull(c.nodeIndex)
        return FieldInfo(n?.resId ?: c.resId, n?.hint ?: "", n?.desc ?: "", n?.text ?: c.label, n?.className ?: c.className)
    }

    /**
     * The element to type into. Laya's target question was trained on CLICK/LONG_PRESS steps only (Byun11 sets no
     * target gold for INPUT_TEXT), so: its target if editable, else the focused editable field, else the first
     * editable candidate, else its target anyway (TaskRunner taps it, then types into whatever has focus).
     */
    fun typeTarget(tree: UiTree, cands: List<Candidate>, chosen: Candidate?): Candidate? {
        if (chosen != null && chosen.editable) return chosen
        cands.firstOrNull { it.editable && tree.nodes.getOrNull(it.nodeIndex)?.focused == true }?.let { return it }
        return cands.firstOrNull { it.editable } ?: chosen
    }

    // ------------------------------------------------------------------ text choice

    private val TRIGGER = Regex("""\b(?:search(?:\s+for)?|look\s+up|google|type|write|enter|find)\s+(.+?)[.!?]?$""",
        RegexOption.IGNORE_CASE)

    /** A trailing "in Chrome" / "on YouTube" / "using the app" (app names from TaskParser), not "in Berlin". */
    private val IN_APP: Regex = run {
        val names = (TaskParser.KNOWN_APPS.keys + TaskParser.EXTRA_APPS.map { it.first } + listOf("browser", "app", "web"))
            .distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("""\s+(?:in|on|with|using)\s+(?:the\s+)?(?:$names)(?:\s+app)?$""", RegexOption.IGNORE_CASE)
    }

    /**
     * Index into [texts] (TaskParser.textOptions) of what to type, or null = ask Laya's `text` question.
     * 1) one option -> 0; 2) quoted text -> its index (textOptions lists quoted texts first); 3) the words after
     * "search for / look up / type / write ..." (minus a trailing "in <app>") if they are an option; 4) null.
     * Laya's text question is untrained: T14 saw it prefer "7:30" over "see you at 7:30", so it is only the last
     * resort.
     */
    fun chooseText(task: String, texts: List<String>): Int? {
        if (texts.isEmpty()) return null
        if (texts.size == 1) return 0
        val quoted = TaskParser.quotedTexts(task)
        if (quoted.isNotEmpty()) texts.indexOf(quoted[0]).takeIf { it >= 0 }?.let { return it }
        TRIGGER.find(task)?.groupValues?.get(1)?.trim()?.replace(IN_APP, "")?.trim('.', ',', '"', '\'')?.let { phrase ->
            texts.indexOfFirst { it.equals(phrase, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    /** CLICKS check without importing TaskRunner. */
    fun isClick(action: String) = action in AgentRules.CLICKS
}
