package com.symmetricalpalmtree.sketchcompanion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.symmetricalpalmtree.sketchcompanion.R
import com.symmetricalpalmtree.sketchcompanion.crop.CropMath
import com.symmetricalpalmtree.sketchcompanion.crop.CropState
import com.symmetricalpalmtree.sketchcompanion.grid.Grid
import com.symmetricalpalmtree.sketchcompanion.grid.GridLayout
import com.symmetricalpalmtree.sketchcompanion.grid.GridPainter
import com.symmetricalpalmtree.sketchcompanion.grid.GridWeight
import kotlin.math.roundToInt

/**
 * The 3:4 frame: the photo through a matrix, clipped, always covering the frame; the grid over it;
 * a 1dp ink border. Pinch zooms about the fingers, one finger pans. Every gesture ends in a
 * [CropState] the Activity persists.
 */
class FrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var onCropChanged: ((CropState) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    var crop: CropState = CropState()
        private set

    private var gridKind = Grid.DEFAULT_KIND
    private var gridCount = Grid.DEFAULT_COUNT
    private var gridColor = Grid.DEFAULT_COLOR
    private var gridWeight = GridWeight.DEFAULT

    private val frame = RectF()
    private val matrix = Matrix()
    private var plan: GridLayout.Plan? = null
    private var planKey = 0L

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whitePaint = Paint().apply { color = context.getColor(R.color.paperWhite) }
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

    // Gestures
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (bitmap == null || frame.isEmpty) return true
            val u = (d.focusX - frame.left) / frame.width()
            val v = (d.focusY - frame.top) / frame.height()
            crop = CropMath.zoomAt(crop, d.scaleFactor, u, v, srcW, srcH)
            rebuildMatrix(); invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val availW = (w - paddingLeft - paddingRight).toFloat()
        val availH = (h - paddingTop - paddingBottom).toFloat()
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
    }

    override fun onDraw(canvas: Canvas) {
        if (frame.isEmpty) return
        canvas.save()
        canvas.clipRect(frame)
        val b = bitmap
        if (b != null) {
            canvas.drawBitmap(b, matrix, bitmapPaint)
        } else {
            canvas.drawRect(frame, whitePaint)
            canvas.drawText(hint, frame.centerX(), frame.centerY() - (hintPaint.ascent() + hintPaint.descent()) / 2f, hintPaint)
        }
        GridPainter.draw(canvas, plan, frame.left, frame.top, frame.width().roundToInt(), frame.height().roundToInt(), gridColor, gridWeight)
        canvas.restore()
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(frame.left + inset, frame.top + inset, frame.right - inset, frame.bottom - inset, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                lastX = event.x; lastY = event.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i >= 0 && !scaler.isInProgress) {
                    val x = event.getX(i); val y = event.getY(i)
                    crop = CropMath.pan(crop, (x - lastX) / frame.width(), (y - lastY) / frame.height(), srcW, srcH)
                    lastX = x; lastY = y; moved = true
                    rebuildMatrix(); invalidate()
                } else if (i >= 0) {
                    lastX = event.getX(i); lastY = event.getY(i)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val up = event.actionIndex
                if (event.getPointerId(up) == activePointer) {
                    val other = if (up == 0) 1 else 0
                    activePointer = event.getPointerId(other)
                    lastX = event.getX(other); lastY = event.getY(other)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointer = MotionEvent.INVALID_POINTER_ID
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved) performClick()
                onCropChanged?.invoke(crop)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
