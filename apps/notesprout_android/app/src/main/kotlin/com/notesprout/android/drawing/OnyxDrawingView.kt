package com.notesprout.android.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.notesprout.android.data.LiveStroke
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.UUID

class OnyxDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val TAG = "NoteSprout"
        // Suppresses EPD hardware auto-GC16 refresh mid-session; we control quality
        // refreshes explicitly via handwritingRepaint in clearCanvas() and after erasing.
        private const val EPD_UPDATE_LIST_SIZE = 512
        private const val ERASER_RADIUS_PX = 15f
    }

    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private var toolbarHeight = 0

    /** Template bitmap — drawn as the base layer behind all strokes. Null = white background. */
    private var templateBitmap: Bitmap? = null

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f
    }

    private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, rawInputCallback) }
    private var isSetup = false

    // Stroke store — LiveStroke carries the DB row UUID for incremental save / targeted erase.
    private val strokes = mutableListOf<LiveStroke>()

    // When true, drawing callbacks treat pen input as erasing.
    // Set by setEraserMode(); also fires via physical eraser hardware callbacks regardless of this flag.
    private var isEraserMode = false

    // ── DrawingView callbacks ────────────────────────────────────────────────

    /** Invoked (on main thread) when a stroke is removed by erasing. */
    override var onStrokeErased: ((String) -> Unit)? = null

    /**
     * Invoked (on main thread) after 1.5 s of pen inactivity — the same moment the
     * hardware overlay is released back to the Android canvas.
     */
    override var onIdleSave: (() -> Unit)? = null

    // ── Idle release + idle save ─────────────────────────────────────────────

    private val idleReleaseRunnable = Runnable {
        if (isSetup) {
            Log.d(TAG, "idle release — handing overlay back to canvas")
            touchHelper.setRawDrawingRenderEnabled(false)
            invalidate()
        }
        onIdleSave?.invoke()
    }

    // ── Raw input callback ───────────────────────────────────────────────────

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            removeCallbacks(idleReleaseRunnable)
            // In software eraser mode pen-tip events still arrive here, but we must not
            // re-enable the overlay render — doing so would show a black stroke on the hardware
            // buffer even though we're erasing, not drawing.
            if (isSetup && !isEraserMode) touchHelper.setRawDrawingRenderEnabled(true)
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            if (isEraserMode) post { EpdController.handwritingRepaint(this@OnyxDrawingView, Rect(0, 0, width, height)) }
            postDelayed(idleReleaseRunnable, 1500)
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            if (isEraserMode) eraseAtPath(listOf(PointF(touchPoint.x, touchPoint.y)))
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            // When software eraser mode is active the SDK still routes pen-tip events here.
            if (isEraserMode) {
                Log.d(TAG, "onRawDrawingTouchPointListReceived (eraser mode) count=${pointList.size()}")
                eraseAtPath(pointList.toPointFs())
            } else {
                Log.d(TAG, "onRawDrawingTouchPointListReceived count=${pointList.size()}")
                renderStroke(pointList)
            }
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            removeCallbacks(idleReleaseRunnable)
            // Release the overlay render immediately so bitmap updates (erased strokes
            // disappearing) are visible right away — same issue as toolbar eraser toggle.
            // Without this the overlay obscures the updated bitmap until the idle timer fires.
            if (isSetup) {
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
        }

        override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            post { EpdController.handwritingRepaint(this@OnyxDrawingView, Rect(0, 0, width, height)) }
            postDelayed(idleReleaseRunnable, 1500)
        }

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
            eraseAtPath(listOf(PointF(touchPoint.x, touchPoint.y)))
        }

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            Log.d(TAG, "onRawErasingTouchPointListReceived count=${pointList.size()}")
            eraseAtPath(pointList.toPointFs())
        }
    }

    private fun TouchPointList.toPointFs(): List<PointF> =
        points?.map { PointF(it.x, it.y) } ?: emptyList()

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private fun renderStroke(pointList: TouchPointList) {
        val canvas = renderCanvas ?: return
        val points = pointList.points
        if (points.isNullOrEmpty()) return
        val strokeId = UUID.randomUUID().toString()
        val strokePoints = points.map { PointF(it.x, it.y) }
        strokes.add(LiveStroke(strokeId, strokePoints))
        val path = Path()
        path.moveTo(strokePoints[0].x, strokePoints[0].y)
        for (i in 1 until strokePoints.size) {
            path.lineTo(strokePoints[i].x, strokePoints[i].y)
        }
        canvas.drawPath(path, strokePaint)
        invalidate()
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

    // ── View lifecycle ───────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && height > 0) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    Log.d(TAG, "onGlobalLayout: ${width}x${height} — calling openRawDrawing")
                    openRawDrawing()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.d(TAG, "onWindowFocusChanged hasFocus=$hasWindowFocus isSetup=$isSetup")
        if (hasWindowFocus) {
            if (width > 0 && height > 0) {
                openRawDrawing()
                invalidate()
            }
        } else {
            if (isSetup) {
                removeCallbacks(idleReleaseRunnable)
                invalidate()
                touchHelper.setRawDrawingEnabled(false)
            }
        }
    }

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

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Color.WHITE)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleReleaseRunnable)
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
    }

    // ── DrawingView interface ────────────────────────────────────────────────

    override fun asView(): View = this

    override fun setToolbarHeight(heightPx: Int) {
        toolbarHeight = heightPx
        Log.d(TAG, "setToolbarHeight toolbarHeight=$toolbarHeight")
        if (isSetup) applyLimitRect()
    }

    override fun enableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(true)
    }

    override fun disableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(false)
    }

    override fun resetOverlay() {
        if (!isSetup) return
        invalidate()
        touchHelper.setRawDrawingRenderEnabled(false)
        post { touchHelper.setRawDrawingRenderEnabled(true) }
    }

    override fun setEraserMode(active: Boolean) {
        isEraserMode = active
        if (isSetup) {
            touchHelper.setEraserRawDrawingEnabled(active, if (active) (ERASER_RADIUS_PX * 2).toInt() else 0)
            if (active) {
                // Release the overlay render immediately so the first eraser touch doesn't
                // show a phantom pen stroke on the hardware buffer.  Without this, render
                // stays enabled from the last drawing session until the idle timer fires
                // 1.5 s later, causing a visible phantom stroke on the first eraser gesture.
                removeCallbacks(idleReleaseRunnable)
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
        }
    }

    /**
     * Set the template bitmap to use as the page background.
     * Null = plain white. Redraws the canvas immediately (strokes on top of new template).
     *
     * Uses the same EPD handoff pattern as clearCanvas(): disable overlay render → draw to bitmap
     * → invalidate → handwritingRepaint → re-enable drawing.  Without handwritingRepaint the
     * e-ink pixels are not refreshed and the template change is invisible on screen.
     */
    override fun setTemplate(bitmap: Bitmap?) {
        templateBitmap = bitmap
        if (isSetup) touchHelper.setRawDrawingRenderEnabled(false)
        redrawCanvas()  // draws white → template → strokes, then calls invalidate()
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            post { if (isSetup) touchHelper.setRawDrawingEnabled(true) }
        }
    }

    override fun clearCanvas() {
        strokes.clear()
        if (isSetup) touchHelper.setRawDrawingRenderEnabled(false)
        // Clear to white then re-apply template so the template persists after clear.
        renderCanvas?.let { canvas ->
            canvas.drawColor(Color.WHITE)
            templateBitmap?.let { tb ->
                canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            }
        }
        invalidate()
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            post { if (isSetup) touchHelper.setRawDrawingEnabled(true) }
        }
    }

    override fun loadStrokes(strokes: List<LiveStroke>) {
        removeCallbacks(idleReleaseRunnable)
        this.strokes.clear()
        this.strokes.addAll(strokes)
        redrawCanvas()
        Log.d(TAG, "loadStrokes: loaded ${strokes.size} strokes")
    }

    override fun getStrokes(): List<LiveStroke> = strokes.toList()

    override fun releaseResources() {
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun applyLimitRect() {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val limitRect = Rect(
            maxOf(0, frame.left - loc[0]),
            maxOf(0, frame.top - loc[1]),
            frame.right - loc[0],
            frame.bottom - loc[1]
        )
        val exclusion = if (toolbarHeight > 0) {
            listOf(Rect(0, 0, width, toolbarHeight))
        } else {
            emptyList()
        }
        Log.d(TAG, "applyLimitRect: limitRect=$limitRect exclusion=$exclusion")
        touchHelper.setLimitRect(limitRect, exclusion)
    }

    private fun openRawDrawing() {
        Log.d(TAG, "openRawDrawing isSetup=$isSetup toolbarHeight=$toolbarHeight size=${width}x${height}")
        if (!isSetup) {
            applyLimitRect()
            touchHelper
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .openRawDrawing()
            isSetup = true
        } else {
            applyLimitRect()
            touchHelper.restartRawDrawing()
        }
        touchHelper.setRawDrawingEnabled(true)
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
        Log.d(TAG, "openRawDrawing done — inputEnabled=${touchHelper.isRawDrawingInputEnabled}")
    }
}
