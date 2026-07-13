package com.notesprout.android.recognition.personal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import com.notesprout.android.data.LiveStroke
import java.util.UUID

/**
 * Minimal ink-capture surface for the enrollment flow: draws black strokes and exposes
 * them as [LiveStroke]s. Deliberately a plain View (no EPD raw-drawing acceleration) —
 * enrollment is 16 short sentences, and keeping it engine-free means it works identically
 * on every device. Coordinates are view-local pixels, which is fine: training normalizes
 * per line (see LineRasterizer / docs/handwriting-recognition.md).
 */
class EnrollmentInkView(context: Context) : View(context) {

    private val strokes = ArrayList<LiveStroke>()
    private val paths = ArrayList<Path>()
    private var currentPoints: MutableList<PointF>? = null
    private var currentPath: Path? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = STROKE_WIDTH_PX
        color = Color.BLACK
    }

    fun getStrokes(): List<LiveStroke> = strokes.toList()

    fun clear() {
        strokes.clear()
        paths.clear()
        currentPoints = null
        currentPath = null
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints = mutableListOf(PointF(event.x, event.y))
                currentPath = Path().apply { moveTo(event.x, event.y) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = currentPoints ?: return true
                val path = currentPath ?: return true
                for (h in 0 until event.historySize) {
                    pts.add(PointF(event.getHistoricalX(h), event.getHistoricalY(h)))
                    path.lineTo(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                pts.add(PointF(event.x, event.y))
                path.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pts = currentPoints
                val path = currentPath
                if (pts != null && pts.isNotEmpty() && path != null) {
                    strokes.add(
                        LiveStroke(
                            id = UUID.randomUUID().toString(),
                            points = pts.toList(),
                            strokeWidth = STROKE_WIDTH_PX,
                        )
                    )
                    paths.add(path)
                }
                currentPoints = null
                currentPath = null
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (p in paths) canvas.drawPath(p, paint)
        currentPath?.let { canvas.drawPath(it, paint) }
    }

    companion object {
        private const val STROKE_WIDTH_PX = 4f
    }
}
