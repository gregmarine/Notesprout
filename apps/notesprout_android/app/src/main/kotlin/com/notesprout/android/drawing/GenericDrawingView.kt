package com.notesprout.android.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.notesprout.android.data.LiveStroke
import java.util.UUID

// Flutter-equivalent of GenericDrawingEngine — pure Android Canvas rendering.
// Stylus-only: finger touch is rejected at the MotionEvent level.
// Two-layer approach mirrors the Flutter implementation:
//   committed layer — Bitmap accumulates finished strokes (redrawn only on stroke commit or clear)
//   active layer    — current in-progress stroke drawn directly in onDraw per touch event
class GenericDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val ERASER_RADIUS_PX = 15f
    }

    private val activePoints = ArrayList<PointF>()
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private var isEraserActive = false

    /** Template bitmap — drawn as the base layer behind all strokes. Null = white background. */
    private var templateBitmap: Bitmap? = null

    // Stroke store — LiveStroke carries the DB row UUID for incremental save / targeted erase.
    private val strokes = mutableListOf<LiveStroke>()

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f
    }

    // ── DrawingView callbacks ────────────────────────────────────────────────

    /** Invoked (on main thread) when a stroke is removed by erasing. */
    override var onStrokeErased: ((String) -> Unit)? = null

    /**
     * Invoked (on main thread) 1.5 s after the last ACTION_UP, matching the idle
     * pattern used by OnyxDrawingView.  The activity uses this to incrementally
     * save new strokes without blocking the drawing thread.
     */
    override var onIdleSave: (() -> Unit)? = null

    // ── Idle save ────────────────────────────────────────────────────────────

    private val idleSaveRunnable = Runnable {
        onIdleSave?.invoke()
    }

    // ── Touch handling ───────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        renderBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderBitmap = bmp
        renderCanvas = Canvas(bmp)
        // redrawCanvas handles white → template → strokes in one pass.
        // This ensures any strokes already loaded before layout (race with loadStrokes())
        // are not lost when the bitmap is first created.
        redrawCanvas()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        // Accept stylus tip and physical eraser end; reject finger touch
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false

        val erasing = toolType == MotionEvent.TOOL_TYPE_ERASER || isEraserActive

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(idleSaveRunnable)
                activePoints.clear()
                activePoints.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val newPoints = mutableListOf<PointF>()
                // Capture historical points for smoother high-speed strokes
                for (i in 0 until event.historySize) {
                    newPoints.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                newPoints.add(PointF(event.x, event.y))
                if (erasing) {
                    eraseAtPath(newPoints)
                } else {
                    activePoints.addAll(newPoints)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (erasing) {
                    eraseAtPath(listOf(PointF(event.x, event.y)))
                } else {
                    activePoints.add(PointF(event.x, event.y))
                    commitActiveStroke()
                    // Schedule idle save ~1.5 s after the stroke ends.
                    postDelayed(idleSaveRunnable, 1500)
                }
                activePoints.clear()
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // renderBitmap has template + committed strokes baked in; draw it over white fallback.
        canvas.drawColor(Color.WHITE)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Draw active stroke preview on top of committed bitmap (pen mode only).
        // In eraser mode there is no in-progress visual; strokes disappear on lift.
        if (!isEraserActive && activePoints.size >= 2) {
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
        val strokeId = UUID.randomUUID().toString()
        val strokePoints = activePoints.toList()
        strokes.add(LiveStroke(strokeId, strokePoints))
        val path = Path()
        path.moveTo(strokePoints[0].x, strokePoints[0].y)
        for (i in 1 until strokePoints.size) {
            path.lineTo(strokePoints[i].x, strokePoints[i].y)
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun eraseAtPath(eraserPoints: List<PointF>) {
        if (eraserPoints.isEmpty()) return
        val thresholdSq = ERASER_RADIUS_PX * ERASER_RADIUS_PX
        val toRemove = strokes.filter { liveStroke ->
            liveStroke.points.any { sp ->
                eraserPoints.indices.drop(1).any { i ->
                    pointToSegmentDistSq(sp, eraserPoints[i - 1], eraserPoints[i]) <= thresholdSq
                } || pointToPointDistSq(sp, eraserPoints[0]) <= thresholdSq
            }
        }
        if (toRemove.isNotEmpty()) {
            strokes.removeAll(toRemove.toSet())
            toRemove.forEach { onStrokeErased?.invoke(it.id) }
            redrawCanvas()
        }
    }

    /**
     * Redraws the render bitmap from scratch: white base → template → all current strokes.
     * Call whenever strokes are added/removed or the template changes.
     */
    private fun redrawCanvas() {
        val canvas = renderCanvas ?: return
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { tb ->
            canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        }
        for (liveStroke in strokes) {
            val points = liveStroke.points
            if (points.size < 2) continue
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            canvas.drawPath(path, strokePaint)
        }
        invalidate()
    }

    // Minimum squared distance from point p to segment a→b.
    private fun pointToSegmentDistSq(p: PointF, a: PointF, b: PointF): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq == 0f) return pointToPointDistSq(p, a)
        val t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
        val cx = a.x + t.coerceIn(0f, 1f) * abx
        val cy = a.y + t.coerceIn(0f, 1f) * aby
        val dx = p.x - cx
        val dy = p.y - cy
        return dx * dx + dy * dy
    }

    private fun pointToPointDistSq(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    // ── DrawingView interface ────────────────────────────────────────────────

    override fun asView(): View = this
    override fun setToolbarHeight(heightPx: Int) {} // no limit rect needed; toolbar is above canvas in z-order
    override fun enableDrawing() {}
    override fun disableDrawing() {}
    override fun setEraserMode(active: Boolean) { isEraserActive = active }

    /**
     * Set the template bitmap to use as the page background.
     * Null = plain white. Redraws the canvas immediately (strokes on top of new template).
     */
    override fun setTemplate(bitmap: Bitmap?) {
        templateBitmap = bitmap
        redrawCanvas()
    }

    override fun clearCanvas() {
        activePoints.clear()
        strokes.clear()
        removeCallbacks(idleSaveRunnable)
        // Clear to white then re-apply template so the template persists after clear.
        renderCanvas?.let { canvas ->
            canvas.drawColor(Color.WHITE)
            templateBitmap?.let { tb ->
                canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            }
        }
        invalidate()
    }

    override fun loadStrokes(strokes: List<LiveStroke>) {
        removeCallbacks(idleSaveRunnable)
        this.strokes.clear()
        this.strokes.addAll(strokes)
        redrawCanvas()
    }

    override fun getStrokes(): List<LiveStroke> = strokes.toList()

    override fun releaseResources() {
        removeCallbacks(idleSaveRunnable)
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }
}
