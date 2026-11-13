package com.brainstormity.voicecontrol.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap helpers for the agent loop (the Pillow parts of jev_agent.py). */
object Images {
    /** JEV's image size limit: max 1568 px per side. */
    const val MAX_SIDE = 1568
    /** Width of the grayscale frames used to detect screen changes. */
    const val DIFF_WIDTH = 160

    /** Screenshots from AccessibilityService.takeScreenshot are HARDWARE bitmaps; pixels need a software copy. */
    fun toSoftware(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE || b.config == null) b.copy(Bitmap.Config.ARGB_8888, false) else b

    /** PIL's `thumbnail((1568, 1568))`: shrink (never enlarge) so the longer side fits, keeping the aspect. */
    fun shrink(b: Bitmap, maxSide: Int = MAX_SIDE): Bitmap {
        val src = toSoftware(b)
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toDouble() / longest
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /**
     * A small grayscale copy (box-averaged, <= [maxWidth] wide) for change detection. Gray uses PIL's "L"
     * weights (299/587/114).
     */
    fun grayThumb(b: Bitmap, maxWidth: Int = DIFF_WIDTH): ScreenDiff.Gray {
        val src = toSoftware(b)
        val sw = src.width
        val sh = src.height
        val w = min(maxWidth, sw)
        val h = max(1, (sh.toLong() * w / sw).toInt())
        val px = IntArray(sw * sh)
        src.getPixels(px, 0, sw, 0, 0, sw, sh)
        val sum = LongArray(w * h)
        val cnt = IntArray(w * h)
        for (y in 0 until sh) {
            val ty = min(h - 1, (y.toLong() * h / sh).toInt())
            val row = y * sw
            for (x in 0 until sw) {
                val c = px[row + x]
                val gray = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
                val i = ty * w + min(w - 1, (x.toLong() * w / sw).toInt())
                sum[i] += gray.toLong()
                cnt[i]++
            }
        }
        return ScreenDiff.Gray(w, h, IntArray(w * h) { if (cnt[it] == 0) 0 else (sum[it] / cnt[it]).toInt() })
    }

    /** Crop [c] out of [img] and enlarge it (refine_target). */
    fun closeUp(img: Bitmap, c: CloseUp): Bitmap {
        val crop = Bitmap.createBitmap(img, c.left, c.top, c.cropW, c.cropH)
        return Bitmap.createScaledBitmap(crop, c.outW, c.outH, true)
    }

    /** The step's screenshot with a red box around the target and a circle where we tap (as upstream saves it). */
    fun annotate(img: Bitmap, box: Box?, cx: Double, cy: Double): Bitmap {
        val out = toSoftware(img).copy(Bitmap.Config.ARGB_8888, true)
        if (box == null) return out
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE }
        paint.strokeWidth = 2f
        canvas.drawRect(
            (box.left * out.width).toFloat(), (box.top * out.height).toFloat(),
            (box.right * out.width).toFloat(), (box.bottom * out.height).toFloat(), paint,
        )
        paint.strokeWidth = 4f
        canvas.drawCircle((cx * out.width).toFloat(), (cy * out.height).toFloat(), 12f, paint)
        return out
    }
}
