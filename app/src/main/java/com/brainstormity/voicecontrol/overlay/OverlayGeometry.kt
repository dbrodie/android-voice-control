package com.brainstormity.voicecontrol.overlay

import kotlin.math.max
import kotlin.math.roundToInt

/** Integer rectangle in absolute screen pixels (plain Kotlin so the geometry is unit-testable on the JVM). */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The screen right now: full size + the part not covered by system bars / display cutout. */
internal data class Area(val width: Int, val height: Int, val safe: Box) {
    companion object {
        fun full(width: Int, height: Int) = Area(width, height, Box(0, 0, width, height))
    }
}

internal data class Pos(val x: Int, val y: Int)

/** Where the bubble sits, independent of the screen size: left/right edge + vertical fraction (0 = top). */
internal data class Anchor(val left: Boolean, val yFraction: Float)

internal data class StripLayout(val x: Int, val y: Int, val width: Int, val height: Int, val stopX: Int, val stopWidth: Int)

/**
 * Pure overlay geometry. Every function takes the *current* [Area]: nothing here caches a screen size, so after a
 * fold/unfold, rotation or multi-window resize the caller just passes the new area.
 * Positions are the top-left corner of the window, in absolute screen pixels.
 */
internal object OverlayGeometry {
    const val DEFAULT_Y_FRACTION = 0.62f

    /** Allowed range for the bubble window's top-left corner: minX, maxX, minY, maxY. */
    data class Range(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    fun bubbleRange(a: Area, size: Int) = Range(
        a.safe.left, max(a.safe.left, a.safe.right - size),
        a.safe.top, max(a.safe.top, a.safe.bottom - size),
    )

    fun clamp(p: Pos, a: Area, size: Int): Pos {
        val r = bubbleRange(a, size)
        return Pos(p.x.coerceIn(r.minX, r.maxX), p.y.coerceIn(r.minY, r.maxY))
    }

    /** True if the bubble's centre is on the left half of the screen. */
    fun isLeft(x: Int, a: Area, size: Int): Boolean = x + size / 2 < a.width / 2

    /** The nearest-edge position the bubble snaps to after a drag (y clamped into the safe area). */
    fun snap(p: Pos, a: Area, size: Int): Pos {
        val r = bubbleRange(a, size)
        return Pos(if (isLeft(p.x, a, size)) r.minX else r.maxX, p.y.coerceIn(r.minY, r.maxY))
    }

    fun anchorOf(p: Pos, a: Area, size: Int): Anchor {
        val r = bubbleRange(a, size)
        val frac = if (r.maxY > r.minY) (p.y - r.minY).toFloat() / (r.maxY - r.minY) else 0.5f
        return Anchor(isLeft(p.x, a, size), frac.coerceIn(0f, 1f))
    }

    fun positionFor(anchor: Anchor, a: Area, size: Int): Pos {
        val r = bubbleRange(a, size)
        val frac = anchor.yFraction.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: DEFAULT_Y_FRACTION
        return Pos(if (anchor.left) r.minX else r.maxX, r.minY + ((r.maxY - r.minY) * frac).roundToInt())
    }

    /** Moves a bubble at [p] on screen [from] to the same edge + vertical fraction on screen [to]. */
    fun relocate(p: Pos, from: Area, to: Area, size: Int): Pos = positionFor(anchorOf(p, from, size), to, size)

    /** The task strip: [fraction] of the screen width, centred, at the top of the safe area; ■ at its right end. */
    fun strip(a: Area, fraction: Float, height: Int, stopWidth: Int): StripLayout {
        val w = (a.width * fraction).roundToInt().coerceAtLeast(stopWidth)
        val x = (a.width - w) / 2
        return StripLayout(x, a.safe.top, w, height, x + w - stopWidth, stopWidth)
    }

    /** Pill of [w] x [h] beside the bubble at [bubble], on the side facing the middle of the screen. */
    fun pill(bubble: Pos, size: Int, w: Int, h: Int, gap: Int, a: Area): Pos {
        val x = if (isLeft(bubble.x, a, size)) bubble.x + size + gap else bubble.x - w - gap
        val y = bubble.y + size / 2 - h / 2
        return Pos(
            x.coerceIn(a.safe.left, max(a.safe.left, a.safe.right - w)),
            y.coerceIn(a.safe.top, max(a.safe.top, a.safe.bottom - h)),
        )
    }
}
