package com.brainstormity.voicecontrol.ui.runs

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.brainstormity.voicecontrol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Small view helpers shared by the Runs screens. */
internal object RunUi {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Edge-to-edge (targetSdk 36): pad [root] by the system bars, light bars like MainActivity. */
    fun edgeToEdge(activity: Activity, root: View) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun toneColor(ctx: Context, tone: RunFormat.Tone): Int = ctx.getColor(
        when (tone) {
            RunFormat.Tone.ACCENT -> R.color.vc_accent
            RunFormat.Tone.OK -> R.color.vc_ok
            RunFormat.Tone.BAD -> R.color.vc_error
            RunFormat.Tone.WARN -> R.color.vc_warn
            RunFormat.Tone.MUTED -> R.color.vc_optional
        }
    )

    /** Turn [chip] into upstream's status pill: coloured text in a 1dp outline of the same colour. */
    fun styleChip(chip: TextView, status: String) {
        val ctx = chip.context
        val color = toneColor(ctx, RunFormat.tone(status))
        chip.text = RunFormat.label(status)
        chip.setTextColor(color)
        chip.textSize = 12f
        chip.setTypeface(chip.typeface, Typeface.BOLD)
        chip.setPadding(dp(ctx, 8), dp(ctx, 1), dp(ctx, 8), dp(ctx, 2))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 99).toFloat()
            setStroke(dp(ctx, 1), color)
            setColor(Color.TRANSPARENT)
        }
    }

    // ------------------------------------------------------------------ images

    /** Decoded thumbnails, keyed by path + width. ~16 MB max. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Decodes [file] downsampled to about [width] px wide (power-of-two sampling), or null. */
    fun decode(file: File, width: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Shows [file] in [view] at about [width] px, decoding off the main thread. */
    fun loadInto(scope: CoroutineScope, view: ImageView, file: File, width: Int) {
        val key = "${file.path}@$width@${file.lastModified()}"
        view.tag = key
        thumbs.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decode(file, width) }.getOrNull() }
            if (bmp != null) thumbs.put(key, bmp)
            if (view.tag == key) view.setImageBitmap(bmp)
        }
    }

    /** Full-screen viewer: the step's screenshot on black, with a caption. Tap anywhere to close. */
    fun showFullScreen(activity: Activity, scope: CoroutineScope, file: File, caption: String) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = caption
        }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val label = TextView(activity).apply {
            text = caption
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
        }
        frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        frame.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        dialog.show()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { decode(file, activity.resources.displayMetrics.widthPixels) }.getOrNull()
            }
            image.setImageBitmap(bmp)
        }
    }
}
