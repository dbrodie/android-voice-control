package com.brainstormity.voicecontrol.agent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import com.brainstormity.voicecontrol.core.OverlayInfo
import com.brainstormity.voicecontrol.core.OverlayRegion
import com.brainstormity.voicecontrol.core.VcLog
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Keeps our own overlay (bubble, pill, strip, stop button) out of what JEV sees and out of the change
 * detection: every screenshot gets the overlay windows painted over (blended into the surrounding app
 * background, see [blend]) before it is encoded or diffed. Regions are [OverlayInfo.regions] (real screen
 * pixels).
 *
 * The overlay re-renders on the main thread while screenshots are taken on another, so a screenshot can
 * still show a window that was just removed (e.g. the bubble right when the strip replaces it). Regions
 * that disappeared less than [LINGER_MS] ago are therefore masked too.
 */
internal object OverlayMask {
    private const val LINGER_MS = 800L
    /** Fallback when a region has no neighbours on either side (full-width): neutral gray. */
    private val FILL = Color.rgb(40, 40, 40)

    private var lastSeen: List<OverlayRegion> = emptyList()
    private var leaving: List<OverlayRegion> = emptyList()
    private var leftAt = 0L
    private var lastHidden = false

    /** The regions to mask right now (current + recently removed). */
    @Synchronized
    fun regions(): List<OverlayRegion> {
        val now = SystemClock.uptimeMillis()
        val current = OverlayInfo.regions.value
        if (current != lastSeen) {
            val gone = lastSeen.filter { old -> current.none { it.rect == old.rect } }
            if (gone.isNotEmpty()) {
                leaving = gone
                leftAt = now
            }
            lastSeen = current
        }
        val recent = if (now - leftAt < LINGER_MS) leaving else emptyList()
        if (current.isEmpty() && recent.isEmpty()) return emptyList()
        // Android hides our overlay while Settings (and some permission screens) are in front: then there is
        // nothing to mask, and masking would hide real content (e.g. the top of a Settings page).
        val hidden = OverlayInfo.isShowing() == false
        if (hidden != lastHidden) {
            lastHidden = hidden
            VcLog.d("Agent", if (hidden) "overlay hidden by the system: not masking" else "overlay on screen: masking it")
        }
        if (hidden) return emptyList()
        return current + recent
    }

    /**
     * Returns [img] (a screenshot already scaled from a [screenW] x [screenH] screen) with the overlay painted
     * over. Returns the same bitmap if there is nothing to mask; otherwise a mutable copy is painted (or [img]
     * itself if it's mutable).
     */
    fun apply(img: Bitmap, screenW: Int, screenH: Int, regions: List<OverlayRegion> = regions()): Bitmap {
        if (regions.isEmpty() || screenW <= 0 || screenH <= 0) return img
        val out = if (img.isMutable) img else img.copy(Bitmap.Config.ARGB_8888, true)
        val sx = out.width.toFloat() / screenW
        val sy = out.height.toFloat() / screenH
        for (r in regions) {
            val left = floor(r.rect.left * sx).toInt() - 1
            val top = floor(r.rect.top * sy).toInt() - 1
            val right = ceil(r.rect.right * sx).toInt() + 1
            val bottom = ceil(r.rect.bottom * sy).toInt() + 1
            blend(out, left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtMost(out.width),
                bottom.coerceAtMost(out.height))
        }
        return out
    }

    /**
     * Paints over [l, r) x [t, b) row by row, blending from the pixel just left of it to the pixel just right of
     * it (or copying the one side that exists). A flat dark fill looked like a UI element itself (JEV aimed
     * just below the dark bar on Chrome's toolbar); a blend makes the overlay vanish into the app's background.
     */
    private fun blend(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        if (w <= 0 || b <= t) return
        val row = IntArray(w)
        for (y in t until b) {
            val lc = if (l > 0) bmp.getPixel(l - 1, y) else null
            val rc = if (r < bmp.width) bmp.getPixel(r, y) else null
            val a = lc ?: rc ?: FILL
            val z = rc ?: lc ?: FILL
            for (i in 0 until w) {
                val f = (i + 0.5f) / w
                row[i] = Color.rgb(
                    (Color.red(a) + (Color.red(z) - Color.red(a)) * f).toInt(),
                    (Color.green(a) + (Color.green(z) - Color.green(a)) * f).toInt(),
                    (Color.blue(a) + (Color.blue(z) - Color.blue(a)) * f).toInt(),
                )
            }
            bmp.setPixels(row, 0, w, l, y, w, 1)
        }
    }

    /** The touchable overlay window (bubble / stop button) at screen pixel ([x], [y]), if any. */
    fun touchableAt(x: Int, y: Int): OverlayRegion? =
        OverlayInfo.regions.value.firstOrNull { it.touchable && grow(it.rect).contains(x, y) }
            ?.takeIf { OverlayInfo.isShowing() != false }  // hidden by the system: the tap reaches the app

    private fun grow(r: Rect, by: Int = 4) = Rect(r.left - by, r.top - by, r.right + by, r.bottom + by)
}
