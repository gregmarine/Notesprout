package com.notesprout.android.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.Base64
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.notesprout.android.core.Slog
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.io.ByteArrayOutputStream
import java.util.UUID

class OnyxDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val TAG = "NoteSprout"
        private const val EPD_TAG = "EPD_TIMING"
        // Suppresses EPD hardware auto-GC16 refresh mid-session; we control quality
        // refreshes explicitly via handwritingRepaint in clearCanvas() and after erasing.
        private const val EPD_UPDATE_LIST_SIZE = 512
        private const val ERASER_RADIUS_PX = 15f
        private const val ERASE_REDRAW_INTERVAL_MS = 60L
        private const val LASSO_REFRESH_INTERVAL_MS = 60L
    }

    // ── EPD diagnostic helpers ───────────────────────────────────────────────

    /**
     * Emit a timestamped EPD_TIMING log line including the current thread name.
     * `inline` + lambda message: in release the message is never built (no cost on
     * the per-stroke render hot path) and nothing reaches logcat (M-4).
     */
    private inline fun epd(msg: () -> String) {
        Slog.d(EPD_TAG) { "[${System.currentTimeMillis()}][${Thread.currentThread().name}] ${msg()}" }
    }

    // Tracks the wall-clock time of the most recent onBeginRawDrawing so we can
    // measure how long before the first actual stroke arrives.
    private var beginRawDrawingTimeMs = 0L

    // Rolling counter of renderStroke() invocations since the last onBeginRawDrawing.
    // Used to emit a "first stroke after begin" latency line and sample every-10th stroke.
    private var strokeRenderCount = 0

    // ── View state ───────────────────────────────────────────────────────────

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

    // Heading store — populated from type="heading" rows at page load time.
    private var headings: List<HeadingStroke> = emptyList()

    private val headingPaint = Paint().apply {
        style = Paint.Style.FILL
        color = HEADING_BACKGROUND_COLOR
        isAntiAlias = false
    }

    private val headingTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics
    )

    private val headingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        textSize = headingTextSizePx
    }

    // When true, drawing callbacks treat pen input as erasing.
    // Set by setEraserMode(); also fires via physical eraser hardware callbacks regardless of this flag.
    private var isEraserMode = false

    // Timestamp of the last redrawCanvas() call triggered by erasing. Used to throttle
    // redraws to ERASE_REDRAW_INTERVAL_MS so a fast erase swipe doesn't queue up dozens
    // of full O(N) bitmap redraws on the main thread.
    private var lastEraseRedrawMs = 0L

    // ── Lasso state ──────────────────────────────────────────────────────────

    private var isLassoMode = false
    private var isLassoEraserMode = false
    private var lassoOverlayPath: Path? = null
    private var lassoSelectionBox: RectF? = null
    private var lassoGestureStartPoint: PointF? = null
    private var lassoGesturePath: Path? = null
    private var lassoGestureHadSelection = false
    private var lastLassoRefreshMs = 0L

    // Lasso eraser display path: jitter baked in at point-add time so the grain is
    // static — no per-frame re-randomisation from PathEffect.
    private var lassoEraserDisplayPath: Path? = null
    private val lassoEraserRandom = java.util.Random()

    // ── Lasso drag move state ────────────────────────────────────────────────

    private var isDragMoveActive = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragThresholdMet = false
    private var dragDx = 0f
    private var dragDy = 0f
    // Deep copies of selected strokes/headings captured at drag start (pre-move data).
    private var dragOriginalStrokes: List<LiveStroke> = emptyList()
    private var dragOriginalHeadings: List<HeadingStroke> = emptyList()
    // Backing bitmap: non-selected strokes/headings + template, built once at drag start.
    private var dragBackingBitmap: Bitmap? = null

    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }

    // Grainy/scratchy stroke for lasso eraser — DiscretePathEffect adds random jitter
    // to each segment, giving a chalk/eraser feel distinct from the selection lasso.
    // No PathEffect — jitter is baked into the path at draw time so the grain is static.
    private val lassoEraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 150, 150, 150)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }

    private fun jitter() = (lassoEraserRandom.nextFloat() - 0.5f) * 8f  // ±4px

    // ── DrawingView callbacks ────────────────────────────────────────────────

    override var onLassoTap: ((Float, Float) -> Unit)? = null
    override var onDragStarted: (() -> Unit)? = null
    override var onLassoSelectionCleared: (() -> Unit)? = null

    override var onStrokeErased: ((String) -> Unit)? = null
    override var onHeadingErased: ((HeadingStroke) -> Unit)? = null

    /**
     * Invoked (on main thread) immediately after each pen lift (onEndRawDrawing).
     * The activity uses this to persist new strokes to the database after each stroke.
     * The EPD overlay remains active — it is only released at non-writing transitions
     * (tool change, page flip, page clear, window focus loss).
     */
    override var onPenLifted: (() -> Unit)? = null

    /**
     * Invoked (on main thread) at non-writing transition boundaries when a snapshot
     * of the current strokes has been captured.  DrawingActivity wires this to persist
     * the snapshot to the page's data JSON in the database.
     */
    override var onSnapshotReady: ((String) -> Unit)? = null
    override var onLassoComplete: ((Path, PointF) -> Unit)? = null
    override var onLassoTapToDismiss: (() -> Unit)? = null
    override var onLassoEraseComplete: ((List<String>) -> Unit)? = null
    override var lassoSelectedIds: Set<String> = emptySet()
    override var onStrokesMoved: ((List<LiveStroke>, List<LiveStroke>, List<HeadingStroke>, List<HeadingStroke>) -> Unit)? = null

    // ── Raw input callback ───────────────────────────────────────────────────

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_BEGIN_RAW_DRAWING isEraserMode=$isEraserMode isLassoMode=$isLassoMode isLassoEraserMode=$isLassoEraserMode isSetup=$isSetup" }
            if (isLassoMode || isLassoEraserMode) return
            if (isSetup && !isEraserMode) {
                touchHelper.setRawDrawingRenderEnabled(true)
                epd { "RENDER_ENABLED caller=onBeginRawDrawing" }
            }
            beginRawDrawingTimeMs = System.currentTimeMillis()
            strokeRenderCount = 0
            epd { "ON_BEGIN_RAW_DRAWING_DONE beginTimeMs=$beginRawDrawingTimeMs" }
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_END_RAW_DRAWING isEraserMode=$isEraserMode isLassoMode=$isLassoMode isLassoEraserMode=$isLassoEraserMode" }
            if (isLassoMode || isLassoEraserMode) return
            if (isEraserMode) {
                // Flush any throttled-but-not-yet-drawn erase removals before the EPD repaint.
                finalizeEraseRedraw()
                post {
                    EpdController.handwritingRepaint(this@OnyxDrawingView, Rect(0, 0, width, height))
                    epd { "HANDWRITING_REPAINT caller=onEndRawDrawing_eraser" }
                }
            }
            // Persist new strokes immediately on pen lift.
            // The EPD overlay stays active — no handoff here.
            epd { "PEN_LIFTED caller=onEndRawDrawing" }
            onPenLifted?.invoke()
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            if (isLassoMode || isLassoEraserMode) return
            if (isEraserMode) eraseAtPath(listOf(PointF(touchPoint.x, touchPoint.y)))
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            if (isLassoMode || isLassoEraserMode) return
            // When software eraser mode is active the SDK still routes pen-tip events here.
            if (isEraserMode) {
                Slog.d(TAG) { "onRawDrawingTouchPointListReceived (eraser mode) count=${pointList.size()}" }
                eraseAtPath(pointList.toPointFs())
            } else {
                Slog.d(TAG) { "onRawDrawingTouchPointListReceived count=${pointList.size()}" }
                renderStroke(pointList)
            }
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_BEGIN_RAW_ERASING isSetup=$isSetup" }
            // Release the overlay render immediately so bitmap updates (erased strokes
            // disappearing) are visible right away — same issue as toolbar eraser toggle.
            // Without this the overlay obscures the updated bitmap.
            if (isSetup) {
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=onBeginRawErasing" }
                invalidate()
                epd { "INVALIDATE caller=onBeginRawErasing" }
            }
            epd { "ON_BEGIN_RAW_ERASING_DONE" }
        }

        override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_END_RAW_ERASING" }
            // Flush any throttled-but-not-yet-drawn erase removals before the EPD repaint.
            finalizeEraseRedraw()
            post {
                EpdController.handwritingRepaint(this@OnyxDrawingView, Rect(0, 0, width, height))
                epd { "HANDWRITING_REPAINT caller=onEndRawErasing" }
            }
        }

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
            eraseAtPath(listOf(PointF(touchPoint.x, touchPoint.y)))
        }

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            Slog.d(TAG) { "onRawErasingTouchPointListReceived count=${pointList.size()}" }
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

        strokeRenderCount++
        val now = System.currentTimeMillis()
        if (strokeRenderCount == 1 && beginRawDrawingTimeMs > 0) {
            epd { "FIRST_STROKE_AFTER_BEGIN delta=${now - beginRawDrawingTimeMs}ms strokeId=${strokeId.take(8)}" }
        }
        if (strokeRenderCount % 10 == 0) {
            epd { "RENDER_STROKE_SAMPLE count=$strokeRenderCount strokeId=${strokeId.take(8)}" }
        }

        invalidate()
        if (strokeRenderCount == 1 || strokeRenderCount % 10 == 0) {
            epd { "INVALIDATE caller=renderStroke count=$strokeRenderCount" }
        }
    }

    private fun eraseAtPath(eraserPoints: List<PointF>) {
        if (eraserPoints.isEmpty()) return
        val thresholdSq = ERASER_RADIUS_PX * ERASER_RADIUS_PX

        // Build the expanded AABB of the entire eraser path for fast stroke pre-rejection.
        // Expanding by ERASER_RADIUS_PX ensures any stroke whose bounding box intersects
        // this rect could possibly be within eraser reach; non-intersecting strokes are
        // skipped entirely without any per-point math.
        var eMinX = eraserPoints[0].x; var eMinY = eraserPoints[0].y
        var eMaxX = eMinX;             var eMaxY = eMinY
        for (ep in eraserPoints) {
            if (ep.x < eMinX) eMinX = ep.x else if (ep.x > eMaxX) eMaxX = ep.x
            if (ep.y < eMinY) eMinY = ep.y else if (ep.y > eMaxY) eMaxY = ep.y
        }
        val eBounds = android.graphics.RectF(
            eMinX - ERASER_RADIUS_PX, eMinY - ERASER_RADIUS_PX,
            eMaxX + ERASER_RADIUS_PX, eMaxY + ERASER_RADIUS_PX
        )

        // Heading hit-test: erase entire heading if eraser AABB intersects its bounding box.
        val hitHeadings = headings.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitHeadings.isNotEmpty()) {
            val hitIds = hitHeadings.mapTo(HashSet()) { it.id }
            headings = headings.filter { it.id !in hitIds }
            hitHeadings.forEach { onHeadingErased?.invoke(it) }
            throttledEraseRedraw()
        }

        val toRemove = strokes.filter { stroke ->
            // Fast AABB rejection — O(1) per stroke, eliminates ~95% of candidates.
            android.graphics.RectF.intersects(eBounds, stroke.boundingBox) &&
            // Detailed per-point geometry only for strokes that passed the box check.
            stroke.points.any { sp ->
                eraserPoints.indices.drop(1).any { i ->
                    pointToSegmentDistSq(sp, eraserPoints[i - 1], eraserPoints[i]) <= thresholdSq
                } || pointToPointDistSq(sp, eraserPoints[0]) <= thresholdSq
            }
        }
        if (toRemove.isNotEmpty()) {
            val removeIds = toRemove.mapTo(HashSet(toRemove.size)) { it.id }
            strokes.removeAll { it.id in removeIds }
            toRemove.forEach { onStrokeErased?.invoke(it.id) }
            throttledEraseRedraw()
        }
    }

    /**
     * Redraw at most once every [ERASE_REDRAW_INTERVAL_MS] during active erasing.
     * Strokes are already removed from [strokes] before this is called, so any
     * skipped redraw just means a brief visual lag — never a stale-data problem.
     */
    private fun throttledEraseRedraw() {
        val now = System.currentTimeMillis()
        if (now - lastEraseRedrawMs >= ERASE_REDRAW_INTERVAL_MS) {
            lastEraseRedrawMs = now
            redrawCanvas(caller = "throttledEraseRedraw")
        }
    }

    /**
     * Force an immediate redraw at the end of an erase gesture, flushing any
     * throttled-but-not-yet-rendered stroke removals so the final state is correct
     * before [EpdController.handwritingRepaint] commits pixels to the e-ink panel.
     */
    private fun finalizeEraseRedraw() {
        lastEraseRedrawMs = System.currentTimeMillis()
        redrawCanvas(caller = "finalizeEraseRedraw")
    }

    private fun drawHeadingText(canvas: Canvas, heading: HeadingStroke) {
        val text = heading.recognizedText ?: return
        val box = heading.boundingBox
        canvas.save()
        canvas.clipRect(box)
        val fontMetrics = headingTextPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val y = box.top + (box.height() - textHeight) / 2f - fontMetrics.ascent
        val paddingPx = 8f * resources.displayMetrics.density
        canvas.drawText(text, box.left + paddingPx, y, headingTextPaint)
        canvas.restore()
    }

    /**
     * Redraws the render bitmap from scratch: white base → template → all current strokes.
     * Call whenever strokes are added/removed or the template changes.
     */
    private fun redrawCanvas(caller: String = "unknown") {
        val redrawStart = System.currentTimeMillis()
        epd { "REDRAW_CANVAS_START caller=$caller strokeCount=${strokes.size}" }
        val canvas = renderCanvas ?: run {
            epd { "REDRAW_CANVAS_ABORT caller=$caller reason=nullCanvas" }
            return
        }
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { tb ->
            canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        }
        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
            if (heading.recognizedText != null) {
                drawHeadingText(canvas, heading)
            } else {
                for (liveStroke in heading.strokes) {
                    val pts = liveStroke.points; if (pts.size < 2) continue
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    canvas.drawPath(path, strokePaint)
                }
            }
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
        epd { "INVALIDATE caller=redrawCanvas($caller)" }
        val elapsed = System.currentTimeMillis() - redrawStart
        epd { "REDRAW_CANVAS_END caller=$caller elapsed=${elapsed}ms strokeCount=${strokes.size}" }
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
                    Slog.d(TAG) { "onGlobalLayout: ${width}x${height} — calling openRawDrawing" }
                    openRawDrawing()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        epd { "WINDOW_FOCUS_CHANGED hasFocus=$hasWindowFocus isSetup=$isSetup" }
        Slog.d(TAG) { "onWindowFocusChanged hasFocus=$hasWindowFocus isSetup=$isSetup" }
        if (hasWindowFocus) {
            if (width > 0 && height > 0) {
                openRawDrawing()
                invalidate()
                epd { "INVALIDATE caller=onWindowFocusChanged_gained" }
            }
        } else {
            // Capture snapshot when the app is backgrounded — non-writing transition boundary.
            captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
            if (isSetup) {
                invalidate()
                epd { "INVALIDATE caller=windowFocusLost" }
                touchHelper.setRawDrawingEnabled(false)
                epd { "RAW_DRAWING_ENABLED false caller=windowFocusLost" }
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
        redrawCanvas(caller = "onSizeChanged")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLassoMode) return handleLassoTouch(event)
        if (isLassoEraserMode) return handleLassoEraserTouch(event)
        return if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)
    }

    private fun handleLassoTouch(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Only stylus builds the lasso path / drag; finger taps fall through.
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) return false

        val thresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Check if pen-down is inside the current selection box — if so, start a drag.
                val box = lassoSelectionBox
                if (box != null && lassoSelectedIds.isNotEmpty() && box.contains(event.x, event.y)) {
                    isDragMoveActive = true
                    dragStartX = event.x; dragStartY = event.y
                    dragThresholdMet = false
                    dragDx = 0f; dragDy = 0f
                    // Deep-copy selected strokes/headings — original positions before any drag.
                    dragOriginalStrokes = strokes
                        .filter { it.id in lassoSelectedIds }
                        .map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) }
                    dragOriginalHeadings = headings
                        .filter { it.id in lassoSelectedIds }
                        .map { h -> HeadingStroke(h.id, android.graphics.RectF(h.boundingBox),
                            h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                            recognizedText = h.recognizedText) }
                    // Build backing bitmap without selected strokes/headings (held for drag).
                    val nonSelectedStrokes  = strokes.filter { it.id !in lassoSelectedIds }
                    val nonSelectedHeadings = headings.filter { it.id !in lassoSelectedIds }
                    dragBackingBitmap = buildRenderBitmap(nonSelectedStrokes, templateBitmap, nonSelectedHeadings)
                    epd { "DRAG_START selected=${lassoSelectedIds.size}" }
                    return true
                }
                // Normal lasso: clear any existing selection so the user sees immediate feedback.
                lassoGestureHadSelection = lassoSelectionBox != null
                lassoSelectionBox  = null
                lassoOverlayPath   = null
                invalidate()
                epd { "INVALIDATE caller=lassoDown-clearSelection" }
                if (lassoGestureHadSelection) onLassoSelectionCleared?.invoke()
                lassoGesturePath = Path().also { it.moveTo(event.x, event.y) }
                lassoGestureStartPoint = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragMoveActive) {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    if (!dragThresholdMet) {
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (dist >= thresholdPx) {
                            dragThresholdMet = true
                            onDragStarted?.invoke()
                            // Switch to A2 fast mode for responsive visual feedback during drag.
                            EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU_FAST)
                            epd { "DRAG_THRESHOLD_MET dist=$dist A2_MODE_ON" }
                        }
                    }
                    if (dragThresholdMet) {
                        dragDx = dx; dragDy = dy
                        val now = System.currentTimeMillis()
                        if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                            lastLassoRefreshMs = now
                            invalidate()
                            epd { "INVALIDATE caller=dragMove" }
                        }
                    }
                    return true
                }
                val path = lassoGesturePath ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                val now = System.currentTimeMillis()
                if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                    lastLassoRefreshMs = now
                    lassoOverlayPath = path
                    invalidate()
                    epd { "INVALIDATE caller=lassoMove" }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragMoveActive) {
                    if (dragThresholdMet) {
                        // Translate selected strokes' points by the final drag offset.
                        val movedStrokes = dragOriginalStrokes.map { stroke ->
                            LiveStroke(stroke.id, stroke.points.map { pt ->
                                PointF(pt.x + dragDx, pt.y + dragDy)
                            })
                        }
                        // Translate selected headings (boundingBox + embedded stroke points).
                        val movedHeadings = dragOriginalHeadings.map { h ->
                            HeadingStroke(
                                id = h.id,
                                boundingBox = android.graphics.RectF(
                                    h.boundingBox.left + dragDx, h.boundingBox.top + dragDy,
                                    h.boundingBox.right + dragDx, h.boundingBox.bottom + dragDy,
                                ),
                                strokes = h.strokes.map { s ->
                                    LiveStroke(s.id, s.points.map { PointF(it.x + dragDx, it.y + dragDy) })
                                },
                                recognizedText = h.recognizedText,
                            )
                        }
                        // Update in-memory stroke list with translated positions.
                        val movedById = movedStrokes.associateBy { it.id }
                        val updated = strokes.map { movedById[it.id] ?: it }
                        strokes.clear(); strokes.addAll(updated)
                        // Update in-memory heading list with translated positions.
                        val headingById = movedHeadings.associateBy { it.id }
                        headings = headings.map { headingById[it.id] ?: it }
                        // Translate selection box to match new positions.
                        lassoSelectionBox = lassoSelectionBox?.let { b ->
                            RectF(b.left + dragDx, b.top + dragDy, b.right + dragDx, b.bottom + dragDy)
                        }
                        // Restore normal EPD update mode and commit pixels BEFORE firing callback.
                        val origStrokes = dragOriginalStrokes
                        val origHeadings = dragOriginalHeadings
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU)
                        epd { "DRAG_COMMIT A2_MODE_OFF" }
                        redrawCanvas(caller = "dragCommit")
                        post {
                            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                            epd { "HANDWRITING_REPAINT caller=dragCommit" }
                        }
                        onStrokesMoved?.invoke(origStrokes, movedStrokes, origHeadings, movedHeadings)
                    } else {
                        // Below threshold — treat as a tap inside the selection box.
                        val tapX = event.x; val tapY = event.y
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        epd { "DRAG_CANCELLED threshold_not_met -> onLassoTap" }
                        onLassoTap?.invoke(tapX, tapY)
                    }
                    return true
                }
                val path  = lassoGesturePath       ?: return true
                val start = lassoGestureStartPoint ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                lassoGesturePath       = null
                lassoGestureStartPoint = null
                lassoOverlayPath       = null
                invalidate()
                epd { "INVALIDATE caller=lassoUp" }
                val dx = event.x - start.x
                val dy = event.y - start.y
                if (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() < thresholdPx) {
                    val hadSelection = lassoGestureHadSelection
                    lassoGestureHadSelection = false
                    onLassoTapToDismiss?.invoke()
                    if (!hadSelection) onLassoTap?.invoke(event.x, event.y)
                } else {
                    lassoGestureHadSelection = false
                    onLassoComplete?.invoke(path, start)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Drag layer: non-selected backing + selected headings + selected strokes translated.
        if (isDragMoveActive && dragThresholdMet) {
            dragBackingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                ?: canvas.drawColor(Color.WHITE)
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
            for (heading in dragOriginalHeadings) {
                canvas.drawRect(heading.boundingBox, headingPaint)
                if (heading.recognizedText != null) {
                    drawHeadingText(canvas, heading)
                } else {
                    for (stroke in heading.strokes) {
                        val pts = stroke.points; if (pts.size < 2) continue
                        val path = Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        canvas.drawPath(path, strokePaint)
                    }
                }
            }
            for (stroke in dragOriginalStrokes) {
                val pts = stroke.points; if (pts.size < 2) continue
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, strokePaint)
            }
            canvas.restoreToCount(save)
            lassoSelectionBox?.let { box ->
                canvas.drawRect(
                    RectF(box.left + dragDx, box.top + dragDy, box.right + dragDx, box.bottom + dragDy),
                    lassoPaint,
                )
            }
            return
        }

        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Color.WHITE)

        // Lasso overlay — drawn on top of everything.
        if (isLassoEraserMode) {
            lassoEraserDisplayPath?.let { canvas.drawPath(it, lassoEraserPaint) }
        } else {
            lassoOverlayPath?.let { canvas.drawPath(it, lassoPaint) }
            lassoSelectionBox?.let { canvas.drawRect(it, lassoPaint) }
        }
    }

    override fun onDetachedFromWindow() {
        epd { "ON_DETACHED_FROM_WINDOW isSetup=$isSetup" }
        super.onDetachedFromWindow()
        if (isSetup) {
            touchHelper.closeRawDrawing()
            epd { "CLOSE_RAW_DRAWING caller=onDetachedFromWindow" }
            isSetup = false
        }
    }

    // ── DrawingView interface ────────────────────────────────────────────────

    override fun asView(): View = this

    override fun setToolbarHeight(heightPx: Int) {
        toolbarHeight = heightPx
        Slog.d(TAG) { "setToolbarHeight toolbarHeight=$toolbarHeight" }
        if (isSetup) applyLimitRect()
    }

    override fun enableDrawing() {
        if (isSetup && !isLassoMode && !isLassoEraserMode) {
            touchHelper.setRawDrawingEnabled(true)
            epd { "RAW_DRAWING_ENABLED true caller=enableDrawing" }
            if (isEraserMode) {
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=enableDrawing_eraserMode" }
            }
        }
    }

    override fun disableDrawing() {
        if (isSetup) {
            touchHelper.setRawDrawingEnabled(false)
            epd { "RAW_DRAWING_ENABLED false caller=disableDrawing" }
        }
    }

    override fun resetOverlay() {
        epd { "RESET_OVERLAY_START isSetup=$isSetup" }
        if (!isSetup) return
        invalidate()
        epd { "INVALIDATE caller=resetOverlay" }
        touchHelper.setRawDrawingRenderEnabled(false)
        epd { "RENDER_DISABLED caller=resetOverlay" }
        post {
            touchHelper.setRawDrawingRenderEnabled(true)
            epd { "RENDER_ENABLED caller=resetOverlay_post" }
        }
    }

    override fun setDragMoveMode(enabled: Boolean) {
        if (!enabled && isDragMoveActive) {
            epd { "SET_DRAG_MOVE_MODE false — cancelling drag" }
            if (dragThresholdMet) {
                EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU)
                epd { "A2_MODE_OFF caller=setDragMoveMode_cancel" }
            }
            dragBackingBitmap?.recycle(); dragBackingBitmap = null
            isDragMoveActive = false; dragThresholdMet = false
            dragDx = 0f; dragDy = 0f
            dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
            invalidate()
            epd { "INVALIDATE caller=setDragMoveMode_cancel" }
        }
    }

    override fun setLassoMode(active: Boolean) {
        epd { "SET_LASSO_MODE active=$active isSetup=$isSetup" }
        isLassoMode = active
        if (active) {
            if (isSetup) {
                touchHelper.setRawDrawingEnabled(false)
                epd { "RAW_DRAWING_ENABLED false caller=setLassoMode_active" }
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=setLassoMode_active" }
                invalidate()
                epd { "INVALIDATE caller=setLassoMode_active" }
            }
        } else {
            // Cancel any in-progress drag before clearing lasso state.
            if (isDragMoveActive) setDragMoveMode(false)
            lassoOverlayPath       = null
            lassoSelectionBox      = null
            lassoGestureStartPoint = null
            lassoGesturePath       = null
            invalidate()
            epd { "INVALIDATE caller=setLassoMode_exit" }
            post {
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                epd { "HANDWRITING_REPAINT caller=setLassoMode_exit" }
                post {
                    if (isSetup && !isLassoMode && !isLassoEraserMode) {
                        touchHelper.setRawDrawingEnabled(true)
                        epd { "RAW_DRAWING_ENABLED true caller=setLassoMode_exit" }
                        // setRawDrawingEnabled may re-enable the SDK render path internally;
                        // re-apply the render disable so the next eraser gesture stays clean.
                        if (isEraserMode) {
                            touchHelper.setRawDrawingRenderEnabled(false)
                            epd { "RENDER_DISABLED caller=setLassoMode_exit_eraserMode" }
                        }
                    }
                }
            }
        }
    }

    override fun setLassoEraserMode(active: Boolean) {
        epd { "SET_LASSO_ERASER_MODE active=$active isSetup=$isSetup" }
        isLassoEraserMode = active
        if (active) {
            if (isSetup) {
                touchHelper.setRawDrawingEnabled(false)
                epd { "RAW_DRAWING_ENABLED false caller=setLassoEraserMode_active" }
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=setLassoEraserMode_active" }
                invalidate()
                epd { "INVALIDATE caller=setLassoEraserMode_active" }
            }
        } else {
            lassoOverlayPath       = null
            lassoEraserDisplayPath = null
            lassoGestureStartPoint = null
            lassoGesturePath       = null
            invalidate()
            epd { "INVALIDATE caller=setLassoEraserMode_exit" }
            post {
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                epd { "HANDWRITING_REPAINT caller=setLassoEraserMode_exit" }
                post {
                    if (isSetup && !isLassoMode && !isLassoEraserMode) {
                        touchHelper.setRawDrawingEnabled(true)
                        epd { "RAW_DRAWING_ENABLED true caller=setLassoEraserMode_exit" }
                        if (isEraserMode) {
                            touchHelper.setRawDrawingRenderEnabled(false)
                            epd { "RENDER_DISABLED caller=setLassoEraserMode_exit_eraserMode" }
                        }
                    }
                }
            }
        }
    }

    private fun handleLassoEraserTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        val tapThresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoEraserDisplayPath = null
                invalidate()
                epd { "INVALIDATE caller=lassoEraserDown" }
                lassoGesturePath = Path().also { it.moveTo(event.x, event.y) }
                lassoEraserDisplayPath = Path().also { it.moveTo(event.x, event.y) }
                lassoGestureStartPoint = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val path = lassoGesturePath ?: return true
                val display = lassoEraserDisplayPath
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    path.lineTo(hx, hy)
                    display?.lineTo(hx + jitter(), hy + jitter())
                }
                path.lineTo(event.x, event.y)
                display?.lineTo(event.x + jitter(), event.y + jitter())
                val now = System.currentTimeMillis()
                if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                    lastLassoRefreshMs = now
                    invalidate()
                    epd { "INVALIDATE caller=lassoEraserMove" }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val path  = lassoGesturePath       ?: return true
                val start = lassoGestureStartPoint ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                lassoGesturePath       = null
                lassoGestureStartPoint = null
                lassoEraserDisplayPath = null
                invalidate()
                epd { "INVALIDATE caller=lassoEraserUp" }
                val dx = event.x - start.x
                val dy = event.y - start.y
                if (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() < tapThresholdPx) {
                    // Tap: clear overlay, stay in lasso eraser mode.
                    post {
                        EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                        epd { "HANDWRITING_REPAINT caller=lassoEraserTap" }
                    }
                } else {
                    performLassoErase(path, start)
                }
            }
        }
        return true
    }

    private fun performLassoErase(drawnPath: Path, startPoint: PointF) {
        drawnPath.lineTo(startPoint.x, startPoint.y)
        drawnPath.close()
        val strokeSnapshot = strokes.toList()
        val headingSnapshot = headings.toList()
        Thread {
            val hitIds = runLassoHitTest(drawnPath, strokeSnapshot, headingSnapshot)
            post {
                // Clear overlay regardless of result.
                lassoOverlayPath       = null
                lassoEraserDisplayPath = null
                invalidate()
                epd { "INVALIDATE caller=performLassoErase_result" }
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                epd { "HANDWRITING_REPAINT caller=performLassoErase_result hitCount=${hitIds.size}" }
                if (hitIds.isNotEmpty()) {
                    onLassoEraseComplete?.invoke(hitIds)
                }
            }
        }.start()
    }

    private fun runLassoHitTest(
        path: Path,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke> = emptyList(),
    ): List<String> {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 10f || bounds.height() < 10f) return emptyList()
        val clipRect = Rect(
            (bounds.left   - 1f).toInt().coerceAtLeast(0),
            (bounds.top    - 1f).toInt().coerceAtLeast(0),
            (bounds.right  + 1f).toInt(),
            (bounds.bottom + 1f).toInt(),
        )
        val region = Region()
        region.setPath(path, Region(clipRect))
        val hitIds = mutableListOf<String>()
        for (stroke in strokes) {
            if (!RectF.intersects(bounds, stroke.boundingBox)) continue
            for (pt in stroke.points) {
                if (region.contains(pt.x.toInt(), pt.y.toInt())) {
                    hitIds.add(stroke.id)
                    break
                }
            }
        }
        // Heading hit-test: center-point containment within the lasso polygon.
        for (heading in headings) {
            if (!RectF.intersects(bounds, heading.boundingBox)) continue
            val cx = heading.boundingBox.centerX().toInt()
            val cy = heading.boundingBox.centerY().toInt()
            if (region.contains(cx, cy)) {
                hitIds.add(heading.id)
            }
        }
        return hitIds
    }

    override fun setLassoSelectedIds(ids: Set<String>, box: RectF) {
        lassoSelectedIds = ids
        setLassoOverlay(null, box)
    }

    override fun setLassoOverlay(path: Path?, selectionBox: RectF?) {
        lassoOverlayPath  = path
        lassoSelectionBox = selectionBox
        invalidate()
        epd { "INVALIDATE caller=setLassoOverlay" }
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            epd { "HANDWRITING_REPAINT caller=setLassoOverlay" }
        }
    }

    override fun setEraserMode(active: Boolean) {
        epd { "SET_ERASER_MODE active=$active isSetup=$isSetup" }
        if (active) {
            // Capture snapshot BEFORE releasing the overlay so strokes are still in memory.
            // This is a non-writing transition boundary: the user is switching tools.
            captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
        }
        isEraserMode = active
        if (isSetup) {
            touchHelper.setEraserRawDrawingEnabled(active, if (active) (ERASER_RADIUS_PX * 2).toInt() else 0)
            if (active) {
                // Release the overlay render immediately so the first eraser touch doesn't
                // show a phantom pen stroke on the hardware buffer.  The overlay was kept
                // active during writing; dropping it here on tool switch is the correct
                // non-writing handoff point.
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=setEraserMode_active" }
                invalidate()
                epd { "INVALIDATE caller=setEraserMode_active" }
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
        epd { "SET_TEMPLATE_START hasTemplate=${bitmap != null} isSetup=$isSetup" }
        // Capture snapshot BEFORE the template changes — snapshot is strokes-only so it
        // remains valid across template switches.  Must run before templateBitmap is updated.
        captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
        templateBitmap = bitmap
        if (isSetup) {
            touchHelper.setRawDrawingRenderEnabled(false)
            epd { "RENDER_DISABLED caller=setTemplate" }
        }
        redrawCanvas(caller = "setTemplate")  // draws white → template → strokes, then calls invalidate()
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            epd { "HANDWRITING_REPAINT caller=setTemplate" }
            post {
                if (isSetup && !isLassoMode && !isLassoEraserMode) {
                    touchHelper.setRawDrawingEnabled(true)
                    epd { "RAW_DRAWING_ENABLED true caller=setTemplate" }
                    if (isEraserMode) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                        epd { "RENDER_DISABLED caller=setTemplate_eraserMode" }
                    }
                }
            }
        }
    }

    override fun clearCanvas() {
        epd { "CLEAR_CANVAS_START isSetup=$isSetup strokeCountBefore=${strokes.size}" }
        strokes.clear()
        headings = emptyList()
        if (isSetup) {
            touchHelper.setRawDrawingRenderEnabled(false)
            epd { "RENDER_DISABLED caller=clearCanvas" }
        }
        // Clear to white then re-apply template so the template persists after clear.
        renderCanvas?.let { canvas ->
            canvas.drawColor(Color.WHITE)
            epd { "WHITE_BITMAP_FILL caller=clearCanvas" }
            templateBitmap?.let { tb ->
                canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            }
        }
        invalidate()
        epd { "INVALIDATE caller=clearCanvas" }
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            epd { "HANDWRITING_REPAINT caller=clearCanvas" }
            post {
                if (isSetup && !isLassoMode && !isLassoEraserMode) {
                    touchHelper.setRawDrawingEnabled(true)
                    epd { "RAW_DRAWING_ENABLED true caller=clearCanvas" }
                    if (isEraserMode) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                        epd { "RENDER_DISABLED caller=clearCanvas_eraserMode" }
                    }
                }
            }
        }
    }

    override fun loadHeadings(headings: List<HeadingStroke>) {
        this.headings = headings
    }

    override fun getHeadings(): List<HeadingStroke> = headings

    override fun loadStrokes(strokes: List<LiveStroke>) {
        val loadStart = System.currentTimeMillis()
        epd { "LOAD_STROKES_START strokeCount=${strokes.size}" }
        this.strokes.clear()
        this.strokes.addAll(strokes)
        redrawCanvas(caller = "loadStrokes")
        Slog.d(TAG) { "loadStrokes: loaded ${strokes.size} strokes" }
        epd { "LOAD_STROKES_END elapsed=${System.currentTimeMillis() - loadStart}ms strokeCount=${strokes.size}" }
    }

    override fun getStrokes(): List<LiveStroke> = strokes.toList()

    // ── Option B: off-thread bitmap pre-build ─────────────────────────────────

    /**
     * Build the render bitmap on a background thread.  Does NOT touch view state —
     * safe to call from Dispatchers.IO.
     */
    override fun buildRenderBitmap(
        strokes: List<LiveStroke>,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke>,
    ): Bitmap? {
        val buildStart = System.currentTimeMillis()
        epd { "BUILD_RENDER_BITMAP_START strokeCount=${strokes.size} hasTemplate=${templateBitmap != null}" }
        val w = width; val h = height
        if (w == 0 || h == 0) {
            epd { "BUILD_RENDER_BITMAP_ABORT reason=zeroSize size=${w}x${h}" }
            return null
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
            if (heading.recognizedText != null) {
                drawHeadingText(canvas, heading)
            } else {
                for (liveStroke in heading.strokes) {
                    val pts = liveStroke.points; if (pts.size < 2) continue
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
        for (liveStroke in strokes) {
            val pts = liveStroke.points
            if (pts.size < 2) continue
            val path = android.graphics.Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, strokePaint)
        }
        epd { "BUILD_RENDER_BITMAP_END elapsed=${System.currentTimeMillis() - buildStart}ms strokeCount=${strokes.size}" }
        return bmp
    }

    /**
     * Swap in a pre-built bitmap on the main thread.  Replicates the EPD overlay
     * handoff pattern of [setTemplate] / [clearCanvas] so e-ink pixels are committed
     * cleanly: disable overlay render → swap bitmap → invalidate → handwritingRepaint
     * → re-enable drawing.
     */
    override fun loadStrokesWithBitmap(
        strokes: List<LiveStroke>,
        bitmap: Bitmap,
        templateBitmap: Bitmap?,
    ) {
        val loadStart = System.currentTimeMillis()
        epd { "LOAD_STROKES_WITH_BITMAP_START strokeCount=${strokes.size} isSetup=$isSetup" }
        this.strokes.clear()
        this.strokes.addAll(strokes)
        this.templateBitmap = templateBitmap

        // EPD overlay handoff — same pattern as setTemplate() / clearCanvas().
        if (isSetup) {
            touchHelper.setRawDrawingRenderEnabled(false)
            epd { "RENDER_DISABLED caller=loadStrokesWithBitmap" }
        }

        // Swap in the pre-built bitmap and bind a new renderCanvas to it so future
        // stroke commits via renderStroke() draw to the correct surface.
        renderBitmap?.recycle()
        renderBitmap = bitmap
        renderCanvas = android.graphics.Canvas(bitmap)

        invalidate()
        epd { "INVALIDATE caller=loadStrokesWithBitmap" }
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            epd { "HANDWRITING_REPAINT caller=loadStrokesWithBitmap" }
            post {
                if (isSetup && !isLassoMode && !isLassoEraserMode) {
                    touchHelper.setRawDrawingEnabled(true)
                    epd { "RAW_DRAWING_ENABLED true caller=loadStrokesWithBitmap" }
                    if (isEraserMode) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                        epd { "RENDER_DISABLED caller=loadStrokesWithBitmap_eraserMode" }
                    }
                }
            }
        }

        Slog.d(TAG) { "loadStrokesWithBitmap: swapped bitmap, ${strokes.size} strokes" }
        epd { "LOAD_STROKES_WITH_BITMAP_END elapsed=${System.currentTimeMillis() - loadStart}ms strokeCount=${strokes.size}" }
    }

    /**
     * Capture the current strokes and heading backgrounds as a base64-encoded PNG with a
     * TRANSPARENT background.  The template is intentionally excluded — at render time
     * the stack is: template → snapshot PNG → new strokes drawn this session.
     * Returns null if there are no strokes and no headings, or the view is not yet laid out.
     */
    override fun captureSnapshot(): String? {
        if (strokes.isEmpty() && headings.isEmpty()) return null
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Transparent base — do NOT fill with white, do NOT paint the template.
        val snapshotCanvas = Canvas(bmp)
        for (heading in headings) {
            snapshotCanvas.drawRect(heading.boundingBox, headingPaint)
            if (heading.recognizedText != null) {
                drawHeadingText(snapshotCanvas, heading)
            } else {
                for (liveStroke in heading.strokes) {
                    val points = liveStroke.points
                    if (points.size < 2) continue
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    snapshotCanvas.drawPath(path, strokePaint)
                }
            }
        }
        for (liveStroke in strokes) {
            val points = liveStroke.points
            if (points.size < 2) continue
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
            snapshotCanvas.drawPath(path, strokePaint)
        }
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bmp.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
    }

    /**
     * Silently update the in-memory stroke list without triggering any canvas redraw
     * or EPD repaint.  Called after a snapshot fast-load so stroke data is available
     * for erasing and export while the snapshot composite is already displayed.
     */
    override fun setStrokeListSilently(strokes: List<LiveStroke>) {
        this.strokes.clear()
        this.strokes.addAll(strokes)
        // No redraw — the snapshot composite bitmap already shows the correct visual state.
    }

    override fun releaseResources() {
        epd { "RELEASE_RESOURCES isSetup=$isSetup" }
        if (isSetup) {
            touchHelper.closeRawDrawing()
            epd { "CLOSE_RAW_DRAWING caller=releaseResources" }
            isSetup = false
        }
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
        dragBackingBitmap?.recycle()
        dragBackingBitmap = null
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
        Slog.d(TAG) { "applyLimitRect: limitRect=$limitRect exclusion=$exclusion" }
        touchHelper.setLimitRect(limitRect, exclusion)
    }

    private fun openRawDrawing() {
        epd { "OPEN_RAW_DRAWING_START isSetup=$isSetup size=${width}x${height} toolbarHeight=$toolbarHeight" }
        Slog.d(TAG) { "openRawDrawing isSetup=$isSetup toolbarHeight=$toolbarHeight size=${width}x${height}" }
        if (!isSetup) {
            applyLimitRect()
            touchHelper
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .openRawDrawing()
            epd { "OPEN_RAW_DRAWING_SDK_CALL done" }
            isSetup = true
        } else {
            applyLimitRect()
            touchHelper.restartRawDrawing()
            epd { "RESTART_RAW_DRAWING caller=openRawDrawing_alreadySetup" }
        }
        if (!isLassoMode && !isLassoEraserMode) {
            touchHelper.setRawDrawingEnabled(true)
            epd { "RAW_DRAWING_ENABLED true caller=openRawDrawing" }
            if (isEraserMode) {
                touchHelper.setRawDrawingRenderEnabled(false)
                epd { "RENDER_DISABLED caller=openRawDrawing_eraserMode" }
            }
        }
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
        epd { "SET_UPD_LIST_SIZE value=$EPD_UPDATE_LIST_SIZE caller=openRawDrawing" }
        Slog.d(TAG) { "openRawDrawing done — inputEnabled=${touchHelper.isRawDrawingInputEnabled}" }
        epd { "OPEN_RAW_DRAWING_DONE isSetup=$isSetup" }
    }
}
