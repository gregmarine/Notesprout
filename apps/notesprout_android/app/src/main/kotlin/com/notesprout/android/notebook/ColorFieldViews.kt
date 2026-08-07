package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The two gradient pickers behind the custom-colour dialog.
 *
 * **The e-ink problem they exist to solve.** A gradient picker is a drag, and a drag on EPD is the
 * worst case: every repaint is a panel refresh, and a continuous stream of them smears badly. So both
 * views **throttle the whole downstream reaction**, not just their own `invalidate()` — the listener
 * fires at most once per [REPAINT_INTERVAL_MS] and then unconditionally once more on lift.
 *
 * Throttling the callback (rather than only the repaint) matters because the consumers are not cheap:
 * a hue change rebuilds [SvFieldView]'s cached bitmap, and every change rewrites the RGB fields, the
 * hex field and the preview — each its own view invalidation. The guaranteed final call on ACTION_UP
 * is what keeps the committed value exact despite the dropped intermediates. Same shape as
 * `throttledEraseRedraw` / `finalizeEraseRedraw` in the drawing views.
 */
private const val REPAINT_INTERVAL_MS = 60L

/** Tracks "fire at most every N ms, and always on settle". */
private class DragThrottle {
    private var lastMs = 0L

    /** True when the caller should emit now. [settled] forces it (ACTION_UP / CANCEL). */
    fun shouldEmit(settled: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        if (settled || now - lastMs >= REPAINT_INTERVAL_MS) {
            lastMs = now
            return true
        }
        return false
    }
}

/**
 * Saturation (x) × value (y) square for a single hue — the body of the picker.
 *
 * The gradient is **rasterized once per hue into a bitmap**, never re-shaded per frame. Composing the
 * shader is cheap on an LCD and not on this hardware, and it only changes when the hue does — so a
 * drag inside the square re-blits a cached bitmap and moves a ring, nothing more.
 */
class SvFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = strokePaint(Color.BLACK, density)
    private val thumbOuter = strokePaint(Color.BLACK, 2f * density)
    private val thumbInner = strokePaint(Color.WHITE, 1.5f * density)

    private var cache: Bitmap? = null
    private var cachedHue = Float.NaN
    private val throttle = DragThrottle()

    /** Throttled — see the file header. Always fires once more on lift with the settled value. */
    var onPick: ((saturation: Float, value: Float) -> Unit)? = null

    var hue: Float = 0f
        set(v) {
            if (field == v) return
            field = v
            rebuildCache()
            invalidate()
        }

    var saturation: Float = 1f
        private set
    var brightness: Float = 1f
        private set

    /** Move the thumb without notifying — used when the RGB/hex fields drive the field instead. */
    fun setSelection(s: Float, v: Float) {
        saturation = s.coerceIn(0f, 1f)
        brightness = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCache()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
        cachedHue = Float.NaN
    }

    private fun rebuildCache() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (cachedHue == hue && cache?.width == w && cache?.height == h) return
        cache?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        // White → hue across, white → black down, multiplied: the standard SV square.
        val across = LinearGradient(0f, 0f, w.toFloat(), 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        val down = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        Canvas(bmp).drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply { shader = ComposeShader(across, down, PorterDuff.Mode.MULTIPLY) },
        )
        cache = bmp
        cachedHue = hue
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        cache?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        val inset = density / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
        // Draw the ring inset by its own radius so a fully-saturated or fully-bright selection still
        // shows a whole circle instead of a half one clipped by the edge. The *value* is untouched —
        // this only keeps the indicator on screen at the extremes.
        val r = 7f * density
        val cx = (saturation * width).coerceIn(r, width - r)
        val cy = ((1f - brightness) * height).coerceIn(r, height - r)
        canvas.drawCircle(cx, cy, r, thumbOuter)
        canvas.drawCircle(cx, cy, r - 1.75f * density, thumbInner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (width <= 0 || height <= 0) return true

        saturation = (event.x / width).coerceIn(0f, 1f)
        brightness = (1f - event.y / height).coerceIn(0f, 1f)

        val settled = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        if (throttle.shouldEmit(settled)) {
            invalidate()
            onPick?.invoke(saturation, brightness)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Vertical hue ramp driving [SvFieldView.hue]. Same throttle contract. */
class HueStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint()
    private val borderPaint = strokePaint(Color.BLACK, density)
    private val markerOuter = strokePaint(Color.BLACK, 2f * density)
    private val markerInner = strokePaint(Color.WHITE, density)
    private val throttle = DragThrottle()

    /** Throttled — see the file header. Always fires once more on lift with the settled value. */
    var onPick: ((hue: Float) -> Unit)? = null

    var hue: Float = 0f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        // Six stops plus the wrap back to red — the full circle, top to bottom.
        val stops = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
        )
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), stops, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val inset = density / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
        // Notches at both edges, not a full-width bar. A bar spanning the strip reads as a seam —
        // as though the ramp were two stacked gradients — instead of as a position marker.
        val y = ((hue / 360f) * height).coerceIn(density, height - density)
        val notch = 9f * density
        for (line in listOf(0f to notch, width - notch to width.toFloat())) {
            canvas.drawLine(line.first, y, line.second, y, markerOuter)
            canvas.drawLine(line.first, y, line.second, y, markerInner)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (height <= 0) return true

        val next = ((event.y / height) * 360f).coerceIn(0f, 359.99f)
        val settled = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        if (throttle.shouldEmit(settled)) {
            hue = next          // setter invalidates
            onPick?.invoke(next)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

private fun strokePaint(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = colour
    strokeWidth = width
}
