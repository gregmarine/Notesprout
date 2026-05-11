package com.notesprout.android.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View

// Flutter-equivalent of GenericDrawingEngine — pure Android Canvas rendering.
// Stylus-only: finger touch is rejected at the MotionEvent level.
// Two-layer approach mirrors the Flutter implementation:
//   committed layer — Bitmap accumulates finished strokes (redrawn only on stroke commit or clear)
//   active layer    — current in-progress stroke drawn directly in onDraw per touch event
class GenericDrawingView(context: Context) : View(context), DrawingView {

    private val activePoints = ArrayList<PointF>()
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        renderBitmap?.recycle()
        renderBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            renderCanvas = Canvas(it)
            renderCanvas!!.drawColor(Color.WHITE)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Stylus-only — reject finger touch to match Flutter's allowTouch: false policy
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePoints.clear()
                activePoints.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Capture historical points for smoother high-speed strokes
                for (i in 0 until event.historySize) {
                    activePoints.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                activePoints.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePoints.add(PointF(event.x, event.y))
                commitActiveStroke()
                activePoints.clear()
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Draw active stroke on top of committed bitmap
        if (activePoints.size >= 2) {
            val path = Path()
            path.moveTo(activePoints[0].x, activePoints[0].y)
            for (i in 1 until activePoints.size) {
                path.lineTo(activePoints[i].x, activePoints[i].y)
            }
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun commitActiveStroke() {
        val canvas = renderCanvas ?: return
        if (activePoints.size < 2) return
        val path = Path()
        path.moveTo(activePoints[0].x, activePoints[0].y)
        for (i in 1 until activePoints.size) {
            path.lineTo(activePoints[i].x, activePoints[i].y)
        }
        canvas.drawPath(path, strokePaint)
    }

    // ── DrawingView interface ──

    override fun asView(): View = this
    override fun setToolbarHeight(heightPx: Int) {} // no limit rect needed; toolbar is above canvas in z-order
    override fun enableDrawing() {}
    override fun disableDrawing() {}

    override fun clearCanvas() {
        activePoints.clear()
        renderCanvas?.drawColor(Color.WHITE)
        invalidate()
    }

    override fun releaseResources() {
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }
}
