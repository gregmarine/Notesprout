package com.symmetricalpalmtree.sketchcompanion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.symmetricalpalmtree.sketchcompanion.R
import com.symmetricalpalmtree.sketchcompanion.crop.CropMath
import com.symmetricalpalmtree.sketchcompanion.crop.CropState
import com.symmetricalpalmtree.sketchcompanion.grid.Grid
import com.symmetricalpalmtree.sketchcompanion.grid.GridLayout
import com.symmetricalpalmtree.sketchcompanion.grid.GridPainter
import com.symmetricalpalmtree.sketchcompanion.grid.GridWeight
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The 3:4 frame: the photo through a matrix, clipped, always covering the frame; the grid over it;
 * a 1dp ink border (none in focus). One finger pans; two fingers pan, pinch-zoom and turn at once,
 * the turn snapping to a right angle when close. While [locked] every gesture is ignored. A tap
 * that never moved reports [onTap] (the Activity's focus toggle) whether locked or not.
 */
class FrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var onCropChanged: ((CropState) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var locked: Boolean = true

    private var bitmap: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    var crop: CropState = CropState()
        private set

    private var gridKind = Grid.DEFAULT_KIND
    private var gridCount = Grid.DEFAULT_COUNT
    private var gridColor = Grid.DEFAULT_COLOR
    private var gridWeight = GridWeight.DEFAULT
    private var focus = false

    private val frame = RectF()
    private val matrix = Matrix()
    private var plan: GridLayout.Plan? = null
    private var planKey = 0L

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whitePaint = Paint().apply { color = context.getColor(R.color.paperWhite) }
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.inkBlack)
        strokeWidth = resources.displayMetrics.density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.inkLight)
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val hint = context.getString(R.string.hint_empty)

    // Gesture state
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastX = 0f          // one finger: the finger; two: the midpoint
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var twoFingers = false

    fun setPhoto(bitmap: Bitmap?, srcW: Int, srcH: Int, crop: CropState) {
        this.bitmap = bitmap
        this.srcW = srcW
        this.srcH = srcH
        this.crop = if (bitmap != null) CropMath.clamp(crop, srcW, srcH) else CropState()
        rebuildMatrix(); invalidate()
    }

    fun setGrid(kind: Int, count: Int, color: Int, weight: GridWeight) {
        gridKind = kind; gridCount = count; gridColor = color; gridWeight = weight
        rebuildPlan(); invalidate()
    }

    /** Focus: black around the frame, no border, the frame as wide as the view allows. */
    fun setFocus(on: Boolean) {
        focus = on
        setBackgroundColor(if (on) Color.BLACK else context.getColor(R.color.paperWhite))
        val pad = if (on) 0 else resources.getDimensionPixelSize(R.dimen.frame_margin)
        setPadding(pad, pad, pad, pad)
        layoutFrame(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutFrame(w, h)

    private fun layoutFrame(w: Int, h: Int) {
        val availW = (w - paddingLeft - paddingRight).toFloat()
        val availH = (h - paddingTop - paddingBottom).toFloat()
        if (availW <= 0 || availH <= 0) return
        val fw = minOf(availW, availH * CropMath.ASPECT)
        val fh = fw / CropMath.ASPECT
        val left = paddingLeft + (availW - fw) / 2f
        val top = paddingTop + (availH - fh) / 2f
        frame.set(left, top, left + fw, top + fh)
        planKey = 0
        rebuildPlan(); rebuildMatrix()
    }

    private fun rebuildPlan() {
        val fw = frame.width().roundToInt()
        val fh = frame.height().roundToInt()
        val key = (fw.toLong() shl 40) or (fh.toLong() shl 16) or (gridKind.toLong() shl 8) or gridCount.toLong()
        if (key == planKey) return
        planKey = key
        plan = GridLayout.plan(gridKind, gridCount, fw, fh)
    }

    private fun rebuildMatrix() {
        val b = bitmap ?: return
        if (frame.isEmpty) return
        val cover = maxOf(frame.width() / b.width, frame.height() / b.height)
        val s = cover * crop.zoom
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(frame.centerX() - crop.cx * b.width * s, frame.centerY() - crop.cy * b.height * s)
        matrix.postRotate(crop.angle, frame.centerX(), frame.centerY())
    }

    override fun onDraw(canvas: Canvas) {
        if (frame.isEmpty) return
        canvas.save()
        canvas.clipRect(frame)
        val b = bitmap
        if (b != null) {
            if (focus) canvas.drawRect(frame, blackPaint)
            canvas.drawBitmap(b, matrix, bitmapPaint)
        } else {
            canvas.drawRect(frame, whitePaint)
            canvas.drawText(hint, frame.centerX(), frame.centerY() - (hintPaint.ascent() + hintPaint.descent()) / 2f, hintPaint)
        }
        GridPainter.draw(canvas, plan, frame.left, frame.top, frame.width().roundToInt(), frame.height().roundToInt(), gridColor, gridWeight)
        canvas.restore()
        if (!focus) {
            val inset = borderPaint.strokeWidth / 2f
            canvas.drawRect(frame.left + inset, frame.top + inset, frame.right - inset, frame.bottom - inset, borderPaint)
        }
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val adjustable = bitmap != null && !locked
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false; twoFingers = false
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                twoFingers = true
                readPair(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) moved = true
                if (event.pointerCount >= 2) moved = true
                if (adjustable && frame.width() > 0) {
                    if (event.pointerCount >= 2) twoFingerMove(event) else if (!twoFingers) oneFingerMove(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifts: re-anchor on the one that stays so nothing jumps.
                val staying = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount == 2) {
                    lastX = event.getX(staying); lastY = event.getY(staying)
                    twoFingers = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                    performClick(); onTap?.invoke()
                } else if (adjustable) {
                    crop = CropMath.snap(crop, srcW, srcH)
                    rebuildMatrix(); invalidate()
                    onCropChanged?.invoke(crop)
                }
            }
        }
        return true
    }

    private fun oneFingerMove(event: MotionEvent) {
        val x = event.x; val y = event.y
        crop = CropMath.pan(crop, (x - lastX) / frame.width(), (y - lastY) / frame.height(), srcW, srcH)
        lastX = x; lastY = y
        rebuildMatrix(); invalidate()
    }

    private fun twoFingerMove(event: MotionEvent) {
        val mx = (event.getX(0) + event.getX(1)) / 2f
        val my = (event.getY(0) + event.getY(1)) / 2f
        val dist = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        val angle = Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())).toFloat()
        val u = (mx - frame.left) / frame.width()
        val v = (my - frame.top) / frame.height()
        var c = CropMath.pan(crop, (mx - lastX) / frame.width(), (my - lastY) / frame.height(), srcW, srcH)
        if (lastDist > 0f) c = CropMath.zoomAt(c, dist / lastDist, u, v, srcW, srcH)
        var dA = angle - lastAngle
        if (dA > 180f) dA -= 360f
        if (dA < -180f) dA += 360f
        c = CropMath.rotateAt(c, dA, u, v, srcW, srcH)
        crop = c
        lastX = mx; lastY = my; lastDist = dist; lastAngle = angle
        rebuildMatrix(); invalidate()
    }

    private fun readPair(event: MotionEvent) {
        lastX = (event.getX(0) + event.getX(1)) / 2f
        lastY = (event.getY(0) + event.getY(1)) / 2f
        lastDist = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        lastAngle = Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())).toFloat()
    }

    override fun performClick(): Boolean = super.performClick()
}
