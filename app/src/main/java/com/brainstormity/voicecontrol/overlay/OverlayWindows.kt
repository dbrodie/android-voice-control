package com.brainstormity.voicecontrol.overlay

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.view.ContextThemeWrapper
import androidx.core.animation.doOnEnd
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.OverlayInfo
import com.brainstormity.voicecontrol.core.OverlayRegion
import com.brainstormity.voicecontrol.core.VcLog
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the overlay windows (all TYPE_APPLICATION_OVERLAY, positioned in absolute screen pixels):
 *  - "bubble": 72dp touchable window holding the 56dp mic bubble (drag / tap / snap to edge),
 *  - "pill":   non-touchable status text beside the bubble,
 *  - "strip":  non-touchable slim bar at the top while a task runs, plus
 *  - "stop":   a small touchable ■ window over the strip's right end.
 * Main thread only.
 */
internal class OverlayWindows(
    service: Context,
    private val onBubbleTap: () -> Unit,
    private val onStopTap: () -> Unit,
) {
    private val ctx: Context
    private val wm: WindowManager

    init {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val windowCtx = service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        ctx = ContextThemeWrapper(windowCtx, R.style.Theme_VoiceControl)
        wm = windowCtx.getSystemService(WindowManager::class.java)
    }

    private val inflater = LayoutInflater.from(ctx)
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val res get() = ctx.resources
    private fun dp(v: Int): Int = (v * res.displayMetrics.density).roundToInt()
    private val bubbleWin get() = res.getDimensionPixelSize(R.dimen.vc_bubble_window)

    /** Screen size + the area not covered by system bars / cutout, in absolute screen pixels. */
    private data class Area(val width: Int, val height: Int, val safe: Rect)

    private fun area(): Area {
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return Area(b.width(), b.height(), Rect(ins.left, ins.top, b.width() - ins.right, b.height() - ins.bottom))
    }

    private var lastArea: Area? = null
    private var current: OverlayUi? = null

    private var bubbleRoot: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var ringView: View? = null
    private var bubbleView: View? = null
    private var ringAnim: Animator? = null
    private var connectAnim: Animator? = null
    private var snapAnim: ValueAnimator? = null

    private var pillView: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private var stripView: View? = null
    private var stripText: TextView? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var stopView: View? = null
    private var stopParams: WindowManager.LayoutParams? = null

    // ---------------------------------------------------------------- public

    fun render(ui: OverlayUi) {
        val a = area()
        val prev = lastArea
        if (prev != null && prev != a) {
            VcLog.d(TAG, "screen geometry changed ${prev.width}x${prev.height} -> ${a.width}x${a.height}; re-adding views")
            removeAll()
        }
        lastArea = a
        current = ui
        when (ui) {
            is OverlayUi.Strip -> {
                removeBubble()
                removePill()
                showStrip(a, ui.text, ui.look)
            }
            is OverlayUi.Bubble -> {
                removeStrip()
                showBubble(a, ui)
                showPill(a, ui.pill)
            }
        }
        publishRegions()
    }

    /** Re-adds every view (after rotation / configuration change). */
    fun rebuild() {
        removeAll()
        lastArea = null
        current?.let(::render)
    }

    fun destroy() {
        removeAll()
        current = null
        OverlayInfo.regions.value = emptyList()
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble(a: Area, ui: OverlayUi.Bubble) {
        if (bubbleRoot == null) {
            val root = inflater.inflate(R.layout.overlay_bubble, null)
            val p = params(bubbleWin, bubbleWin, touchable = true, name = "bubble")
            val (x, y) = savedBubblePosition(a)
            p.x = x; p.y = y
            root.setOnClickListener { onBubbleTap() }
            root.setOnTouchListener(DragListener())
            if (!add(root, p)) return
            bubbleRoot = root
            bubbleParams = p
            ringView = root.findViewById(R.id.bubbleRing)
            bubbleView = root.findViewById(R.id.bubble)
        }
        applyLook(ui.look)
        setRing(ui.look.pulsing)
        setConnecting(ui.look.fading)
        bubbleView?.contentDescription = res.getString(
            if (ui.look.active) R.string.overlay_bubble_description_active
            else R.string.overlay_bubble_description
        )
    }

    private fun removeBubble() {
        snapAnim?.cancel(); snapAnim = null
        ringAnim?.cancel(); ringAnim = null
        connectAnim?.cancel(); connectAnim = null
        lookAnim?.cancel(); lookAnim = null
        remove(bubbleRoot)
        bubbleRoot = null; bubbleParams = null; ringView = null; bubbleView = null
        bubbleLook = null; bubbleColors = null
    }

    /** Bubble gradient (top, bottom) + ring colour currently on screen; animated towards the target look. */
    private var bubbleLook: BubbleLook? = null
    private var bubbleColors: IntArray? = null
    private var lookAnim: ValueAnimator? = null

    private fun applyLook(look: BubbleLook) {
        if (look == bubbleLook) return
        val b = bubbleView as? ImageView ?: return
        val bg = (b.background.mutate() as? GradientDrawable) ?: return
        val ring = ringView?.background?.mutate() as? GradientDrawable
        if (bubbleLook?.icon != look.icon) b.setImageResource(look.icon)
        bubbleLook = look
        val target = intArrayOf(ctx.getColor(look.top), ctx.getColor(look.bottom), ctx.getColor(look.ring))
        val from = bubbleColors
        lookAnim?.cancel(); lookAnim = null
        fun paint(c: IntArray) {
            bg.colors = intArrayOf(c[0], c[1])
            ring?.setColor(c[2])
            bubbleColors = c
        }
        if (from == null) {   // first show: no animation
            paint(target); return
        }
        lookAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOK_ANIM_MS
            addUpdateListener { va ->
                val f = va.animatedFraction
                paint(IntArray(3) { i -> ARGB.evaluate(f, from[i], target[i]) as Int })
            }
            start()
        }
    }

    private fun setRing(on: Boolean) {
        if (on == (ringAnim != null)) return
        ringAnim?.cancel(); ringAnim = null
        val ring = ringView ?: return
        if (on) {
            ringAnim = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.27f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.27f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.95f, 0f),
            ).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            ring.alpha = 0f; ring.scaleX = 1f; ring.scaleY = 1f
        }
    }

    private fun setConnecting(on: Boolean) {
        if (on == (connectAnim != null)) return
        connectAnim?.cancel(); connectAnim = null
        val b = bubbleView ?: return
        if (on) {
            connectAnim = ObjectAnimator.ofFloat(b, View.ALPHA, 1f, 0.5f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            b.alpha = 1f
        }
    }

    private fun bubbleBounds(a: Area): IntArray {
        // minX, maxX, minY, maxY for the bubble window's top-left corner
        val w = bubbleWin
        return intArrayOf(a.safe.left, a.safe.right - w, a.safe.top, maxOf(a.safe.top, a.safe.bottom - w))
    }

    private fun savedBubblePosition(a: Area): Pair<Int, Int> {
        val (minX, maxX, minY, maxY) = bubbleBounds(a)
        val left = prefs.getInt(KEY_SIDE, SIDE_RIGHT) == SIDE_LEFT
        val frac = prefs.getFloat(KEY_YFRAC, 0.62f).coerceIn(0f, 1f)
        return (if (left) minX else maxX) to (minY + ((maxY - minY) * frac).roundToInt())
    }

    private fun saveBubblePosition(a: Area, p: WindowManager.LayoutParams) {
        val (_, _, minY, maxY) = bubbleBounds(a)
        val left = p.x + bubbleWin / 2 < a.width / 2
        val frac = if (maxY > minY) (p.y - minY).toFloat() / (maxY - minY) else 0.5f
        prefs.edit().putInt(KEY_SIDE, if (left) SIDE_LEFT else SIDE_RIGHT).putFloat(KEY_YFRAC, frac).apply()
    }

    private fun snapToEdge() {
        val a = lastArea ?: return
        val p = bubbleParams ?: return
        val root = bubbleRoot ?: return
        val (minX, maxX, minY, maxY) = bubbleBounds(a)
        p.y = p.y.coerceIn(minY, maxY)
        val targetX = if (p.x + bubbleWin / 2 < a.width / 2) minX else maxX
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                p.x = it.animatedValue as Int
                if (root.isAttachedToWindow) {
                    wm.updateViewLayout(root, p)
                    positionPill()
                }
            }
            doOnEnd {
                saveBubblePosition(a, p)
                publishRegions()
            }
            start()
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                    bubbleView?.animate()?.scaleX(0.9f)?.scaleY(0.9f)?.setDuration(90)?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val a = lastArea ?: return true
                        val (minX, maxX, minY, maxY) = bubbleBounds(a)
                        p.x = (startX + dx.roundToInt()).coerceIn(minX, maxX)
                        p.y = (startY + dy.roundToInt()).coerceIn(minY, maxY)
                        wm.updateViewLayout(v, p)
                        positionPill()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
                    if (dragging) snapToEdge() else {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
                    if (dragging) snapToEdge()
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- pill

    private fun showPill(a: Area, pill: Pill?) {
        if (pill == null || bubbleRoot == null) {
            removePill(); return
        }
        val existing = pillView
        val tv = existing ?: (inflater.inflate(R.layout.overlay_pill, null) as TextView)
        tv.text = pill.text
        tv.setTextColor(ctx.getColor(pill.tone.textColor))
        if (existing == null) {
            val p = params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false, "pill")
            layoutPill(tv, p, bubbleParams ?: return, a)
            if (!add(tv, p)) return
            pillView = tv
            pillParams = p
        } else {
            positionPill()
        }
    }

    private fun positionPill() {
        val tv = pillView ?: return
        val p = pillParams ?: return
        val bp = bubbleParams ?: return
        val a = lastArea ?: return
        layoutPill(tv, p, bp, a)
        if (tv.isAttachedToWindow) wm.updateViewLayout(tv, p)
    }

    private fun layoutPill(tv: TextView, p: WindowManager.LayoutParams, bp: WindowManager.LayoutParams, a: Area) {
        val gap = dp(2)
        val maxW = min(res.getDimensionPixelSize(R.dimen.vc_pill_max_width), a.safe.width() - bubbleWin - dp(16))
        tv.maxWidth = maxW
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = tv.measuredWidth
        val h = tv.measuredHeight
        val bubbleOnLeft = bp.x + bubbleWin / 2 < a.width / 2
        p.width = w
        p.height = h
        p.x = if (bubbleOnLeft) bp.x + bubbleWin + gap else bp.x - w - gap
        p.y = (bp.y + bubbleWin / 2 - h / 2).coerceIn(a.safe.top, maxOf(a.safe.top, a.safe.bottom - h))
    }

    private fun removePill() {
        remove(pillView)
        pillView = null; pillParams = null
    }

    // ---------------------------------------------------------------- strip

    private fun showStrip(a: Area, text: String, look: BubbleLook) {
        val h = res.getDimensionPixelSize(R.dimen.vc_strip_height)
        val w = (a.width * STRIP_WIDTH_FRACTION).roundToInt()
        val x = (a.width - w) / 2
        val y = a.safe.top
        if (stripView == null) {
            val v = inflater.inflate(R.layout.overlay_strip, null)
            val p = params(w, h, touchable = false, name = "strip")
            p.x = x; p.y = y
            if (!add(v, p)) return
            stripView = v
            stripText = v.findViewById(R.id.stripText)
            stripParams = p
        }
        stripText?.text = text
        applyStripLook(look)
        if (stopView == null) {
            val sw = res.getDimensionPixelSize(R.dimen.vc_strip_stop_width)
            val v = inflater.inflate(R.layout.overlay_stop, null)
            v.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onStopTap()
            }
            val p = params(sw, h, touchable = true, name = "stop")
            p.x = x + w - sw; p.y = y
            if (!add(v, p)) return
            stopView = v
            stopParams = p
        }
    }

    private fun removeStrip() {
        stripAnim?.cancel(); stripAnim = null
        remove(stripView); remove(stopView)
        stripView = null; stripText = null; stripParams = null; stopView = null; stopParams = null
        stripLook = null; stripColors = null
    }

    /** Strip background + dot colour currently on screen; animated like the bubble. */
    private var stripLook: BubbleLook? = null
    private var stripColors: IntArray? = null
    private var stripAnim: ValueAnimator? = null

    private fun applyStripLook(look: BubbleLook) {
        if (look == stripLook) return
        val v = stripView ?: return
        val bg = v.background?.mutate() as? GradientDrawable ?: return
        val dot = v.findViewById<View>(R.id.stripDot)?.background?.mutate() as? GradientDrawable
        stripLook = look
        val target = intArrayOf(ctx.getColor(look.strip), ctx.getColor(look.accent))
        val from = stripColors
        stripAnim?.cancel(); stripAnim = null
        fun paint(c: IntArray) {
            bg.setColor(c[0]); dot?.setColor(c[1]); stripColors = c
        }
        if (from == null) {
            paint(target); return
        }
        stripAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOK_ANIM_MS
            addUpdateListener { va ->
                val f = va.animatedFraction
                paint(IntArray(2) { i -> ARGB.evaluate(f, from[i], target[i]) as Int })
            }
            start()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun removeAll() {
        removeStrip(); removePill(); removeBubble()
    }

    private fun params(w: Int, h: Int, touchable: Boolean, name: String) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // Absolute screen coordinates: don't let the system shift us away from the bars / cutout.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Android 12+ blocks touches that pass through another app's overlay unless its alpha <= 0.8, so the
        // pass-through windows (pill, strip) must stay at 0.8 for taps to reach the app below.
        if (!touchable) alpha = PASS_THROUGH_ALPHA
        title = "VoiceControl:$name"
    }

    private fun add(v: View, p: WindowManager.LayoutParams): Boolean = try {
        wm.addView(v, p); true
    } catch (e: Exception) {
        VcLog.e(TAG, "addView(${p.title}) failed", e); false
    }

    private fun remove(v: View?) {
        if (v == null) return
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {
            VcLog.w(TAG, "removeView failed: ${e.message}")
        }
    }

    private fun publishRegions() {
        fun r(p: WindowManager.LayoutParams, w: Int, h: Int) = Rect(p.x, p.y, p.x + w, p.y + h)
        val list = buildList {
            bubbleParams?.let { add(OverlayRegion("bubble", r(it, bubbleWin, bubbleWin), true)) }
            val pv = pillView
            pillParams?.let { if (pv != null) add(OverlayRegion("pill", r(it, it.width, it.height), false)) }
            stripParams?.let { add(OverlayRegion("strip", r(it, it.width, it.height), false)) }
            stopParams?.let { add(OverlayRegion("stop", r(it, it.width, it.height), true)) }
        }
        if (OverlayInfo.regions.value != list) {
            OverlayInfo.regions.value = list
            VcLog.d(TAG, "regions: " + list.joinToString { "${it.name}=${it.rect.toShortString()}" })
        }
    }

    companion object {
        private const val TAG = "Overlay"
        private const val PREFS = "vc_overlay"
        private const val KEY_SIDE = "bubble_side"
        private const val KEY_YFRAC = "bubble_y_fraction"
        private const val SIDE_LEFT = 0
        private const val SIDE_RIGHT = 1
        const val STRIP_WIDTH_FRACTION = 0.6f
        const val PASS_THROUGH_ALPHA = 0.8f
        /** Colour cross-fade between bubble / strip looks. */
        private const val LOOK_ANIM_MS = 150L
        private val ARGB = ArgbEvaluator()
    }
}
