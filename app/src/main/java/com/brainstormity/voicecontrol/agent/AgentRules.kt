package com.brainstormity.voicecontrol.agent

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Constants and pure decision rules from jev_agent.py (phone path). No Android types, so JVM-testable. */
object AgentRules {
    const val MAX_STEPS = 30
    /** Below this confidence, stop and ask for help instead of guessing. */
    const val MIN_CONFIDENCE = 0.5
    /** Actions that can't post, submit or change anything may go ahead with less certainty. */
    val HARMLESS_ACTIONS = setOf(
        "WAIT", "SCROLL_DOWN", "SCROLL_UP", "COPY_LINK", "OPEN_WEBSITE",
        "OPEN_APP", "ALL_APPS", "HOME", "SWIPE_LEFT", "SWIPE_RIGHT",
    )
    const val MIN_CONFIDENCE_HARMLESS = 0.25
    /** Typing taps the field first. */
    val CLICKS = setOf("CLICK", "DOUBLE_CLICK", "LONG_PRESS", "TYPE", "TYPE_ENTER")
    /** Each starts with the one before it: tap the field, then type, then press Enter. */
    val CHAIN = listOf("CLICK", "TYPE", "TYPE_ENTER")
    /** When JEV is less sure than this about WHERE to tap, zoom in and ask again. */
    const val CLOSE_UP_BELOW = 0.6
    /** https://docs.typesafe.ai/models.md: $0.042 per million input tokens; output tokens are free. */
    const val PRICE_PER_MILLION_INPUT_TOKENS = 0.042
    /** Actions that may load a new screen: must stay still a little longer (3 checks). */
    val LOADS_PAGE = setOf(
        "CLICK", "DOUBLE_CLICK", "TYPE_ENTER", "ENTER", "OPEN_WEBSITE", "OPEN_APP", "ALL_APPS", "BACK", "HOME",
    )

    /** Mobile Use: what JEV can do on an Android phone (PHONE_ACTIONS, same order and wording). */
    val PHONE_ACTIONS: Map<String, String> = linkedMapOf(
        "CLICK" to "Tap one visible element (button, link, app icon, tab, input field) to move toward the goal.",
        "LONG_PRESS" to "Press and hold a visible element, e.g. to open its menu.",
        "TYPE" to "Tap a text field (message, post or comment box like 'What's happening?') and type text from the " +
            "task into it, without submitting, e.g. the quoted message.",
        "TYPE_ENTER" to "Tap a search box, type the search term from the task, and press Enter to search.",
        "ENTER" to "Press Enter on the keyboard to submit what was just typed.",
        "OPEN_APP" to "Open an app named in the task directly, without hunting for its icon.",
        "ALL_APPS" to "Open the list of all installed apps (the app drawer, with a search box at the top), to find " +
            "an app that isn't on the home screen.",
        "OPEN_WEBSITE" to "Open a website named in the task in the phone's browser.",
        "SCROLL_DOWN" to "Swipe up to scroll down and reveal more content below.",
        "SCROLL_UP" to "Swipe down to scroll up to content above.",
        "SWIPE_LEFT" to "Swipe left, e.g. to the next home-screen page, photo or tab.",
        "SWIPE_RIGHT" to "Swipe right, e.g. to the previous home-screen page, photo or tab.",
        "BACK" to "Press the phone's Back button: go to the previous screen, or close the keyboard or a popup.",
        "HOME" to "Go to the phone's home screen, e.g. to find an app's icon.",
        "WAIT" to "The screen is still loading; wait.",
        "DONE" to "The whole task is finished and the result is visible on screen.",
    )

    fun minConfidenceFor(action: String): Double =
        if (action in HARMLESS_ACTIONS) MIN_CONFIDENCE_HARMLESS else MIN_CONFIDENCE

    fun costOf(inputTokens: Long): Double = inputTokens / 1_000_000.0 * PRICE_PER_MILLION_INPUT_TOKENS

    data class ChainResult(val action: String, val confidence: Double, val note: String)

    /**
     * When JEV is split between CLICK / TYPE / TYPE_ENTER, do only the longest part enough of JEV agrees on.
     * Returns the input unchanged when it doesn't apply.
     */
    fun splitChain(
        action: String, confidence: Double, probabilities: Map<String, Double>, who: String = "JEV",
    ): ChainResult {
        if (confidence < MIN_CONFIDENCE && action in CHAIN) {
            for (i in CHAIN.indices.reversed()) {
                val tail = CHAIN.subList(i, CHAIN.size)
                val agreed = tail.sumOf { probabilities[it] ?: 0.0 }
                if (agreed >= MIN_CONFIDENCE) {
                    val split = tail.filter { (probabilities[it] ?: 0.0) >= 0.05 }
                    val note = if (CHAIN[i] != action || split.size > 1)
                        "$who was split between ${split.joinToString(" / ")}, so only the shared first part " +
                            "(${CHAIN[i]}) is done."
                    else ""
                    return ChainResult(CHAIN[i], agreed, note)
                }
            }
        }
        return ChainResult(action, confidence, "")
    }

    /** Python's `f"{x:.0%}"`. */
    fun pct(x: Double): String = String.format(Locale.US, "%.0f%%", x * 100)

    /** Python's `f"{x:.5f}"`. */
    fun usd(x: Double): String = String.format(Locale.US, "%.5f", x)

    /** Python's `repr(str)` for the common cases (detail strings like `'hello' at (1, 2)`). */
    fun pyRepr(s: String): String {
        val q = if ('\'' in s && '"' !in s) '"' else '\''
        val sb = StringBuilder().append(q)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == q -> sb.append('\\').append(c)
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.US, "\\x%02x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append(q).toString()
    }
}

/** A box in 0..1 coordinates of the screenshot (JEV's `box` answer). */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val cx: Double get() = (left + right) / 2
    val cy: Double get() = (top + bottom) / 2
    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom
    fun toMap(): Map<String, Double> = linkedMapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)
}

/**
 * refine_target's geometry: a square a few times the target's size around it, cropped from the
 * screenshot ([imgW] x [imgH]) and enlarged so its long side is 800 px (each side at least 280 px).
 */
data class CloseUp(
    val imgW: Int, val imgH: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val outW: Int, val outH: Int,
) {
    val cropW: Int get() = right - left
    val cropH: Int get() = bottom - top

    /** Map a box answered on the close-up back to the whole screenshot (0..1). */
    fun mapBack(b: Box): Box = Box(
        left = (left + b.left * (right - left)) / imgW,
        top = (top + b.top * (bottom - top)) / imgH,
        right = (left + b.right * (right - left)) / imgW,
        bottom = (top + b.bottom * (bottom - top)) / imgH,
    )

    companion object {
        fun around(box: Box, w: Int, h: Int): CloseUp {
            val targetW = (box.right - box.left) * w
            val targetH = (box.bottom - box.top) * h
            val half = max(max(targetW, targetH), min(w, h) * 0.18) * 1.5
            val cx = box.cx * w
            val cy = box.cy * h
            var left = max(0.0, cx - half).toInt()
            var top = max(0.0, cy - half).toInt()
            val right = min(w.toDouble(), cx + half).toInt()
            val bottom = min(h.toDouble(), cy + half).toInt()
            // Guard (not in upstream, where PIL would fail): a box entirely off-screen gives an empty crop.
            if (right - left < 1) left = (right - 1).coerceIn(0, w - 1)
            if (bottom - top < 1) top = (bottom - 1).coerceIn(0, h - 1)
            val r = max(right, left + 1)
            val b = max(bottom, top + 1)
            val scale = 800.0 / max(r - left, b - top)
            val outW = max(280, ((r - left) * scale).toInt())
            val outH = max(280, ((b - top) * scale).toInt())
            return CloseUp(w, h, left, top, r, b, outW, outH)
        }
    }
}

/** Last tap/click that changed nothing, in 0..1 coordinates. */
data class Miss(val action: String, val cx: Double, val cy: Double) {
    fun isNear(action: String, x: Double, y: Double): Boolean =
        this.action == action && abs(this.cx - x) < 0.05 && abs(this.cy - y) < 0.05
}
