package com.brainstormity.voicecontrol.agent

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * `changed_pixels` / `grow` / `fraction` / `ImageChops.lighter` / `getbbox` from jev_agent.py, on small
 * grayscale frames (see [Images.grayThumb]: <= 160 px wide, box-averaged) instead of the 1568 px screenshot,
 * so each check costs well under a millisecond. Pure Kotlin, JVM-testable.
 */
object ScreenDiff {
    /** Top 3% of the screen: the status-bar clock and icons change on their own. */
    const val MENU_BAR = 0.03
    /** Under 0.4% of the screen moving counts as still (e.g. a loading spinner). */
    const val STILL_ENOUGH = 0.004
    /** Over 0.01% of the screen changing, outside things that move on their own, = a reaction. */
    const val REACTED = 0.0001
    /** A pixel counts as changed when its gray value moved by more than this. */
    const val THRESHOLD = 16
    /** upstream's `grow` works at 1/8 of a ~700 px wide screenshot; our frames are ~4.4x smaller. */
    const val GROW_BLOCK = 2

    /** 8-bit grayscale frame, row-major. */
    class Gray(val width: Int, val height: Int, val v: IntArray) {
        init { require(v.size == width * height) }
    }

    /** Black/white mask, row-major. */
    class Mask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
        operator fun get(x: Int, y: Int) = bits[y * width + x]
        fun count(): Int = bits.count { it }
        fun sameSize(o: Mask) = o.width == width && o.height == height
        fun copy() = Mask(width, height, bits.copyOf())
    }

    /** Pixels that changed between two frames, menu bar excluded. Frames of different size: all changed. */
    fun changedPixels(before: Gray, after: Gray): Mask {
        val w = after.width
        val top = (after.height * MENU_BAR).toInt()
        val h = after.height - top
        if (before.width != after.width || before.height != after.height) {
            return Mask(w, h, BooleanArray(w * h) { true })
        }
        val m = Mask(w, h)
        val off = top * w
        for (i in 0 until w * h) m.bits[i] = abs(before.v[off + i] - after.v[off + i]) > THRESHOLD
        return m
    }

    /** An empty mask the size of [changedPixels] for this frame (upstream: `changed_pixels(last, last)`). */
    fun emptyMaskFor(g: Gray): Mask = Mask(g.width, g.height - (g.height * MENU_BAR).toInt())

    /** Widen a changed-pixels mask a little (coarse blocks + 3x3 max filter) so it covers its neighbourhood. */
    fun grow(mask: Mask, block: Int = GROW_BLOCK): Mask {
        val cw = (mask.width + block - 1) / block
        val ch = (mask.height + block - 1) / block
        val coarse = BooleanArray(cw * ch)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask[x, y]) coarse[(y / block) * cw + x / block] = true
        }
        val dilated = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var any = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until cw && yy in 0 until ch && coarse[yy * cw + xx]) any = true
            }
            dilated[y * cw + x] = any
        }
        val out = Mask(mask.width, mask.height)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            out.bits[y * mask.width + x] = dilated[(y / block) * cw + x / block]
        }
        return out
    }

    /** Share of the screen that changed, leaving out the [ignore] areas. */
    fun fraction(mask: Mask, ignore: Mask? = null): Double {
        val total = mask.width * mask.height
        if (total == 0) return 0.0
        var n = 0
        if (ignore != null && ignore.sameSize(mask)) {
            for (i in 0 until total) if (mask.bits[i] && !ignore.bits[i]) n++
        } else {
            n = mask.count()
        }
        return n.toDouble() / total
    }

    /** `ImageChops.lighter`: union of two masks (if sizes differ, the newer one wins). */
    fun lighter(a: Mask, b: Mask): Mask {
        if (!a.sameSize(b)) return b.copy()
        return Mask(a.width, a.height, BooleanArray(a.bits.size) { a.bits[it] || b.bits[it] })
    }

    /** `getbbox` as fractions of the mask [left, top, right, bottom], rounded to 2 decimals; null if empty. */
    fun bbox(mask: Mask): List<Double>? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask[x, y]) { l = min(l, x); t = min(t, y); r = max(r, x + 1); b = max(b, y + 1) }
        }
        if (r < 0) return null
        fun f(v: Int, d: Int) = Math.round(v.toDouble() / d * 100) / 100.0
        return listOf(f(l, mask.width), f(t, mask.height), f(r, mask.width), f(b, mask.height))
    }
}
