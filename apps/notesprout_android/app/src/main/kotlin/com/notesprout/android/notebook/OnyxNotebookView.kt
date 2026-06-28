package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import com.notesprout.android.R
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
import com.notesprout.android.core.markdown.TextObjectRenderer
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.translate
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.io.ByteArrayOutputStream
import java.util.UUID

class OnyxNotebookView(context: Context) : View(context), NotebookView {

    companion object {
        private const val TAG = "Notesprout"
        private const val EPD_TAG = "EPD_TIMING"
        // Suppresses EPD hardware auto-GC16 refresh mid-session; we control quality
        // refreshes explicitly via handwritingRepaint in eraseAll() and after erasing.
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
    /** Toolbar exclusion zone in view coords; the BOOX pen layer never captures inside it. */
    private var toolbarExclusion: Rect? = null

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

    // Text object store — populated from type="text" rows at page load time.
    private var textObjects: List<TextRender> = emptyList()

    // Line object store — populated from type="line" rows at page load time.
    private var lineObjects: List<LineRender> = emptyList()

    // Link object store — populated from type="link" rows at page load time.
    private var links: List<LinkRender> = emptyList()

    // Sticky note store — populated from type="sticky_note" rows at page load time.
    private var stickyNotes: List<StickyNoteRender> = emptyList()

    // Shape object store — populated from type="shape" rows at page load time.
    private var shapeObjects: List<ShapeRender> = emptyList()

    private val textObjectTextSizePx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
    )
    private val textObjectPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = textObjectTextSizePx
    }

    // Link chrome — 1dp inkBlack outline/underline/chevron drawn around a link's union bbox.
    private val linkChromePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = resources.displayMetrics.density   // 1dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val linkChromeDashPaint = Paint(linkChromePaint).apply {
        val d = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
    }

    // When true, drawing callbacks treat pen input as erasing.
    // Set by setEraserMode(); also fires via physical eraser hardware callbacks regardless of this flag.
    private var isEraserMode = false

    // Timestamp of the last redrawCanvas() call triggered by erasing. Used to throttle
    // redraws to ERASE_REDRAW_INTERVAL_MS so a fast erase swipe doesn't queue up dozens
    // of full O(N) bitmap redraws on the main thread.
    private var lastEraseRedrawMs = 0L

    // ── Text placement mode ───────────────────────────────────────────────────

    private var isTextPlacementMode = false
    override var onTextPlacementTap: ((Float, Float) -> Unit)? = null

    // Coordinates captured on ACTION_DOWN; callback fires on ACTION_UP so the full
    // tap gesture is consumed by placement mode and raw drawing is only re-enabled
    // after the stylus has left the screen.
    private var textPlacementTapX = 0f
    private var textPlacementTapY = 0f

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
    // Deep copies of selected strokes/headings/textObjects captured at drag start (pre-move data).
    private var dragOriginalStrokes: List<LiveStroke> = emptyList()
    private var dragOriginalHeadings: List<HeadingStroke> = emptyList()
    private var dragOriginalTextObjects: List<TextRender> = emptyList()
    private var dragOriginalLineObjects: List<LineRender> = emptyList()
    private var dragOriginalLinks: List<LinkRender> = emptyList()
    private var dragOriginalStickyNotes: List<StickyNoteRender> = emptyList()
    private var dragOriginalShapeObjects: List<ShapeRender> = emptyList()
    // Backing bitmap: non-selected strokes/headings/textObjects + template, built once at drag start.
    private var dragBackingBitmap: Bitmap? = null
    private var activeSnapGuides: List<SnapGuide> = emptyList()
    private var snapObjectTargets: List<RectF> = emptyList()
    override var isSnapEnabled: Boolean = false

    private val snapGuidePaint: Paint by lazy {
        val density = resources.displayMetrics.density
        Paint().apply {
            style       = Paint.Style.STROKE
            color       = Color.BLACK
            strokeWidth = density
            pathEffect  = DashPathEffect(floatArrayOf(12f * density, 6f * density), 0f)
            isAntiAlias = false
        }
    }

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

    // ── NotebookView callbacks ────────────────────────────────────────────────

    override var onLassoTap: ((Float, Float) -> Unit)? = null
    override var onDragStarted: (() -> Unit)? = null
    override var onLassoSelectionCleared: (() -> Unit)? = null

    override var onStrokeErased: ((String) -> Unit)? = null
    override var onHeadingErased: ((HeadingStroke) -> Unit)? = null
    override var onTextErased: ((TextRender) -> Unit)? = null
    override var onLineErased: ((LineRender) -> Unit)? = null
    override var onLinkErased: ((LinkRender) -> Unit)? = null
    override var onStickyNoteErased: ((StickyNoteRender) -> Unit)? = null
    override var onShapeErased: ((ShapeRender) -> Unit)? = null
    override var onShapeRecognized: ((LiveStroke, ShapeRecognizer.Result) -> Unit)? = null
    override var onScribbleEraseComplete: ((List<String>, List<HeadingStroke>, List<TextRender>, List<LineRender>, List<LinkRender>, List<StickyNoteRender>) -> Unit)? = null
    override var onSmartLassoComplete: ((List<String>, RectF) -> Unit)? = null

    // Points and stroke IDs accumulated between onBeginRawDrawing and onEndRawDrawing.
    // Used for per-gesture scribble candidate detection after all points are collected.
    private val currentGesturePoints    = mutableListOf<PointF>()
    private val currentGestureStrokeIds = mutableListOf<String>()

    // Dwell tracking: how long the stylus was still at the end of the stroke.
    private var dwellAnchorX    = 0f
    private var dwellAnchorY    = 0f
    private var lastMoveTimeMs  = 0L
    private var dwellMs         = 0L

    /**
     * Invoked (on main thread) immediately after each pen lift (onEndRawDrawing).
     * The activity uses this to persist new strokes to the database after each stroke.
     * The EPD overlay remains active — it is only released at non-writing transitions
     * (tool change, page flip, page clear, window focus loss).
     */
    override var onPenLifted: (() -> Unit)? = null

    /**
     * Invoked (on main thread) at non-writing transition boundaries when a snapshot
     * of the current strokes has been captured.  NotebookActivity wires this to persist
     * the snapshot to the page's data JSON in the database.
     */
    override var onSnapshotReady: ((String) -> Unit)? = null
    override var onLassoComplete: ((Path, PointF) -> Unit)? = null
    override var onLassoTapToDismiss: (() -> Unit)? = null
    override var onLassoEraseComplete: ((List<String>) -> Unit)? = null
    override var lassoSelectedIds: Set<String> = emptySet()
    override var onStrokesMoved: ((List<LiveStroke>, List<LiveStroke>, List<HeadingStroke>, List<HeadingStroke>, List<TextRender>, List<TextRender>, List<LineRender>, List<LineRender>, List<LinkRender>, List<LinkRender>, List<StickyNoteRender>, List<StickyNoteRender>) -> Unit)? = null

    // ── Raw input callback ───────────────────────────────────────────────────

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_BEGIN_RAW_DRAWING isEraserMode=$isEraserMode isLassoMode=$isLassoMode isLassoEraserMode=$isLassoEraserMode isTextPlacementMode=$isTextPlacementMode isSetup=$isSetup" }
            if (isLassoMode || isLassoEraserMode || isTextPlacementMode) return
            if (isSetup && !isEraserMode) {
                touchHelper.setRawDrawingRenderEnabled(true)
                epd { "RENDER_ENABLED caller=onBeginRawDrawing" }
            }
            currentGesturePoints.clear()
            currentGestureStrokeIds.clear()
            beginRawDrawingTimeMs = System.currentTimeMillis()
            strokeRenderCount = 0
            dwellAnchorX   = touchPoint.x
            dwellAnchorY   = touchPoint.y
            lastMoveTimeMs = System.currentTimeMillis()
            dwellMs        = 0L
            epd { "ON_BEGIN_RAW_DRAWING_DONE beginTimeMs=$beginRawDrawingTimeMs" }
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            epd { "ON_END_RAW_DRAWING isEraserMode=$isEraserMode isLassoMode=$isLassoMode isLassoEraserMode=$isLassoEraserMode isTextPlacementMode=$isTextPlacementMode" }
            if (isLassoMode || isLassoEraserMode || isTextPlacementMode) return
            if (isEraserMode) {
                // Flush any throttled-but-not-yet-drawn erase removals before the EPD repaint.
                finalizeEraseRedraw()
                post {
                    EpdController.handwritingRepaint(this@OnyxNotebookView, Rect(0, 0, width, height))
                    epd { "HANDWRITING_REPAINT caller=onEndRawDrawing_eraser" }
                }
                // Eraser mode: normal pen-lift path (no scribble detection).
                epd { "PEN_LIFTED caller=onEndRawDrawing_eraser" }
                onPenLifted?.invoke()
            } else {
                // Pen mode: shape-dwell → smart-lasso → scribble-to-erase → normal stroke.
                epd { "PEN_LIFTED caller=onEndRawDrawing_pen gestureCheck" }
                val now = System.currentTimeMillis()
                dwellMs = now - lastMoveTimeMs
                val durationMs = now - beginRawDrawingTimeMs
                checkAndDispatchGesture(durationMs)
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            if (isLassoMode || isLassoEraserMode || isTextPlacementMode) return
            if (isEraserMode) eraseAtPath(listOf(PointF(touchPoint.x, touchPoint.y)))
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            if (isLassoMode || isLassoEraserMode || isTextPlacementMode) return
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
                EpdController.handwritingRepaint(this@OnyxNotebookView, Rect(0, 0, width, height))
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
        // Accumulate for end-of-gesture scribble/shape detection.
        currentGesturePoints.addAll(strokePoints)
        currentGestureStrokeIds.add(strokeId)
        // Update dwell anchor using per-point timestamps so the batch-delivery pattern
        // (all points arrive at once at pen-lift) doesn't collapse the dwell window to ~0ms.
        val dwellRadiusPx = SHAPE_DWELL_RADIUS_DP * resources.displayMetrics.density
        val now = System.currentTimeMillis()
        for (raw in points) {
            val dx = raw.x - dwellAnchorX
            val dy = raw.y - dwellAnchorY
            if (dx * dx + dy * dy > dwellRadiusPx * dwellRadiusPx) {
                dwellAnchorX   = raw.x
                dwellAnchorY   = raw.y
                lastMoveTimeMs = if (raw.timestamp > 0L) raw.timestamp else now
            }
        }
        val path = Path()
        path.moveTo(strokePoints[0].x, strokePoints[0].y)
        for (i in 1 until strokePoints.size) {
            path.lineTo(strokePoints[i].x, strokePoints[i].y)
        }
        canvas.drawPath(path, strokePaint)

        strokeRenderCount++
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

        // Text-object hit-test: erase entire text object if eraser AABB intersects its box.
        val hitTexts = textObjects.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitTexts.isNotEmpty()) {
            val hitIds = hitTexts.mapTo(HashSet()) { it.id }
            textObjects = textObjects.filter { it.id !in hitIds }
            hitTexts.forEach { onTextErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Line-object hit-test: erase entire line if eraser AABB intersects its box.
        val hitLines = lineObjects.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitLines.isNotEmpty()) {
            val hitIds = hitLines.mapTo(HashSet()) { it.id }
            lineObjects = lineObjects.filter { it.id !in hitIds }
            hitLines.forEach { onLineErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Link-object hit-test: erase the entire link if eraser AABB intersects its box.
        val hitLinks = links.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitLinks.isNotEmpty()) {
            val hitIds = hitLinks.mapTo(HashSet()) { it.id }
            links = links.filter { it.id !in hitIds }
            hitLinks.forEach { onLinkErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Sticky-note hit-test: erase the icon if eraser AABB intersects its icon box.
        val hitStickyNotes = stickyNotes.filter { android.graphics.RectF.intersects(eBounds, it.boundingBox) }
        if (hitStickyNotes.isNotEmpty()) {
            val hitIds = hitStickyNotes.mapTo(HashSet()) { it.id }
            stickyNotes = stickyNotes.filter { it.id !in hitIds }
            hitStickyNotes.forEach { onStickyNoteErased?.invoke(it) }
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
        val paddingPx = 8f * resources.displayMetrics.density
        val innerBox = android.graphics.RectF(box.left + paddingPx, box.top + paddingPx, box.right - paddingPx, box.bottom - paddingPx)
        val widthPx = kotlin.math.ceil(innerBox.width().toDouble()).toInt().coerceAtLeast(1)
        canvas.save()
        canvas.clipRect(box)
        TextObjectRenderer.draw(canvas, TextRender(heading.id, innerBox, text), widthPx, textObjectPaint, resources.displayMetrics.density, maxLines = 1)
        canvas.restore()
    }

    /**
     * Render a type="text" object onto [canvas].
     * - Non-blank text → markdown path via TextObjectRenderer.
     * - Blank text + non-empty strokes → render embedded strokes (unrecognized conversion state).
     * - Blank text + no strokes → nothing rendered.
     */
    private fun drawTextObject(canvas: Canvas, textObj: TextRender, widthPx: Int) {
        when {
            textObj.text.isNotBlank() ->
                TextObjectRenderer.draw(canvas, textObj, widthPx, textObjectPaint, resources.displayMetrics.density)
            !textObj.strokes.isNullOrEmpty() -> {
                for (liveStroke in textObj.strokes) {
                    val pts = liveStroke.points; if (pts.size < 2) continue
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
    }

    private fun drawLineObject(canvas: Canvas, lineObj: LineRender) {
        val density = resources.displayMetrics.density
        val sw = lineObj.strokeWidthDp * density
        val paint = Paint().apply {
            isAntiAlias = true
            color = context.getColor(R.color.inkLight)
            strokeCap = Paint.Cap.ROUND
            strokeWidth = sw
        }
        when (lineObj.style) {
            LineStyle.SOLID -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            LineStyle.DASHED -> {
                paint.style = Paint.Style.STROKE
                paint.pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            LineStyle.DOTTED -> {
                paint.style = Paint.Style.FILL
                val spacing = lineObj.dotSpacingPx.takeIf { it > 0f } ?: (sw * 4f)
                val r = sw / 2f
                when (lineObj.orientation) {
                    LineOrientation.HORIZONTAL -> {
                        var x = lineObj.startX
                        while (x <= lineObj.endX) { canvas.drawCircle(x, lineObj.startY, r, paint); x += spacing }
                    }
                    LineOrientation.VERTICAL -> {
                        var y = lineObj.startY
                        while (y <= lineObj.endY) { canvas.drawCircle(lineObj.startX, y, r, paint); y += spacing }
                    }
                }
            }
        }
    }

    private fun drawShapeObject(canvas: Canvas, shape: ShapeRender) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.BLACK
            strokeWidth = shape.strokeWidthPx
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(ShapeGeometry.pathFor(shape), paint)
    }

    /**
     * Render a type="link" object onto [canvas]: its embedded content (headings, text, lines,
     * strokes) painted via the existing per-type helpers, then the chrome around the union bbox.
     * Links are drawn after page lines and before top-level strokes (see redrawCanvas).
     */
    private fun drawLinkObject(canvas: Canvas, link: LinkRender, widthPx: Int) {
        for (heading in link.headings) {
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
        for (textObj in link.textObjects) drawTextObject(canvas, textObj, widthPx)
        for (lineObj in link.lines) drawLineObject(canvas, lineObj)
        for (liveStroke in link.strokes) {
            val pts = liveStroke.points; if (pts.size < 2) continue
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, strokePaint)
        }
        val iconOutside = link.headings.isNotEmpty() || link.textObjects.isNotEmpty()
        drawLinkChrome(canvas, link.boundingBox, link.chrome, iconOutside)
    }

    /** Draw a link's visual indicator: none, an underline, or a dotted box with a corner chevron.
     *  For text/heading links [iconOutside]=true: the stored bbox already includes the gap + icon
     *  room baked in at creation (6dp gap + 14dp icon + 3dp inner pad), so the chrome box IS the
     *  bbox and the icon is placed inside at bottom-right. */
    private fun drawLinkChrome(canvas: Canvas, box: RectF, chrome: LinkChrome, iconOutside: Boolean = false) {
        when (chrome) {
            LinkChrome.NONE -> {}
            LinkChrome.UNDERLINE ->
                canvas.drawLine(box.left, box.bottom, box.right, box.bottom, linkChromePaint)
            LinkChrome.DOTTED_CHEVRON -> {
                val d = resources.displayMetrics.density
                val iconSize = (14f * d).toInt()
                val pad = 3f * d
                if (iconOutside) {
                    // bbox already contains the full visual extent; draw it as-is.
                    canvas.drawRect(box, linkChromeDashPaint)
                    val iconLeft = (box.right - iconSize - pad).toInt()
                    val iconBottom = (box.bottom - pad).toInt()
                    AppCompatResources.getDrawable(context, R.drawable.ic_link)?.let { icon ->
                        icon.setBounds(iconLeft, iconBottom - iconSize, iconLeft + iconSize, iconBottom)
                        icon.draw(canvas)
                    }
                } else {
                    canvas.drawRect(box, linkChromeDashPaint)
                    val iconRight = (box.right - pad).toInt()
                    val iconBottom = (box.bottom - pad).toInt()
                    AppCompatResources.getDrawable(context, R.drawable.ic_link)?.let { icon ->
                        icon.setBounds(iconRight - iconSize, iconBottom - iconSize, iconRight, iconBottom)
                        icon.draw(canvas)
                    }
                }
            }
        }
    }

    private fun drawStickyNoteObject(canvas: Canvas, note: StickyNoteRender) {
        val box = note.boundingBox
        val left = box.left.toInt()
        val top = box.top.toInt()
        val right = box.right.toInt()
        val bottom = box.bottom.toInt()
        androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.let { icon ->
            icon.setBounds(left, top, right, bottom)
            icon.draw(canvas)
        }
    }

    private fun drawSnapGuides(canvas: Canvas) {
        if (activeSnapGuides.isEmpty()) return
        for (guide in activeSnapGuides) {
            when (guide) {
                is SnapGuide.Vertical   -> canvas.drawLine(guide.x, 0f, guide.x, height.toFloat(), snapGuidePaint)
                is SnapGuide.Horizontal -> canvas.drawLine(0f, guide.y, width.toFloat(), guide.y, snapGuidePaint)
            }
        }
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
        for (textObj in textObjects) {
            drawTextObject(canvas, textObj, width)
        }
        for (lineObj in lineObjects) {
            drawLineObject(canvas, lineObj)
        }
        for (shape in shapeObjects) {
            drawShapeObject(canvas, shape)
        }
        for (link in links) {
            drawLinkObject(canvas, link, width)
        }
        for (note in stickyNotes) {
            drawStickyNoteObject(canvas, note)
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

    // ── Gesture detection at pen lift (shape-dwell → smart-lasso → scribble-to-erase → normal) ──

    /**
     * Called at the end of every non-eraser pen gesture.  Runs the detection gate chain
     * in priority order on a single background thread:
     *   Gate 0 — Shape dwell: single stroke held still ≥ [SHAPE_DWELL_MS] → shape object.
     *   Gate 1 — Smart lasso: fast closed circle enclosing ≥1 object → enter lasso selection.
     *   Gate 2 — Scribble-to-erase: dense back-and-forth crossing ≥1 object → erase.
     *   Default — Normal stroke: fire [onPenLifted] so the activity saves the stroke to DB.
     */
    private fun checkAndDispatchGesture(durationMs: Long) {
        val gesturePoints    = currentGesturePoints.toList()
        val gestureStrokeIds = currentGestureStrokeIds.toList()
        currentGesturePoints.clear()
        currentGestureStrokeIds.clear()

        if (gesturePoints.isEmpty() || gestureStrokeIds.isEmpty()) {
            onPenLifted?.invoke()
            return
        }

        val density      = resources.displayMetrics.density
        val gestureIdSet = gestureStrokeIds.toSet()

        // ── Gate 0 pre-check: dwell candidate → always spawn a thread ─────────
        // On Onyx, a single pen contact produces multiple renderStroke batches
        // (multiple IDs in gestureStrokeIds). All batches belong to the same gesture,
        // so no stroke-count check — just the dwell time.
        val dwellCandidate = dwellMs >= SHAPE_DWELL_MS
        // Merge all gesture points into one synthetic stroke for undo restoration.
        // None of the gesture strokes are persisted (onPenLifted never fires for them).
        val mergedStroke: com.notesprout.android.data.LiveStroke? = if (dwellCandidate) {
            val strokeWidth = strokes.firstOrNull { it.id == gestureStrokeIds.firstOrNull() }?.strokeWidth
                ?: com.notesprout.android.data.LiveStroke.DEFAULT_STROKE_WIDTH
            com.notesprout.android.data.LiveStroke(
                id     = gestureStrokeIds.firstOrNull() ?: UUID.randomUUID().toString(),
                points = gesturePoints,
                strokeWidth = strokeWidth,
            )
        } else null

        // Cheap synchronous geometry checks — skip when already a dwell candidate (re-run
        // inside the thread on the null-recognition fallthrough path).
        val isSmartLasso = !dwellCandidate && isSmartLassoCandidate(gesturePoints, durationMs, density)
        val isScribble   = !dwellCandidate && !isSmartLasso && isScribbleCandidate(gesturePoints)
        if (!dwellCandidate && !isSmartLasso && !isScribble) {
            onPenLifted?.invoke()
            return
        }

        val strokeSnapshot    = strokes.toList()
        val headingSnapshot  = headings.toList()
        val textSnapshot     = textObjects.toList()
        val lineSnapshot     = lineObjects.toList()
        val linkSnapshot     = links.toList()
        val stickyNoteSnapshot = stickyNotes.toList()

        Thread {
            // ── Gate 0: Shape dwell trigger ────────────────────────────────────────
            // Runs first; on null falls through to gates 1 and 2.
            var runSmartLasso = isSmartLasso
            if (dwellCandidate && mergedStroke != null) {
                val result = ShapeRecognizer.recognize(gesturePoints, density)
                if (result != null) {
                    post {
                        strokes.removeAll { it.id in gestureIdSet }
                        if (isSetup) {
                            touchHelper.setRawDrawingRenderEnabled(false)
                            epd { "RENDER_DISABLED caller=shapeDwell" }
                            invalidate()
                            epd { "INVALIDATE caller=shapeDwell" }
                        }
                        onShapeRecognized?.invoke(mergedStroke, result)
                    }
                    return@Thread
                }
                // No shape recognized — check gates 1 and 2 for this stroke.
                runSmartLasso = isSmartLassoCandidate(gesturePoints, durationMs, density)
                val runScribble = !runSmartLasso && isScribbleCandidate(gesturePoints)
                if (!runSmartLasso && !runScribble) {
                    post { onPenLifted?.invoke() }
                    return@Thread
                }
            }

            // ── Gate 1: Smart lasso ────────────────────────────────────────────────
            if (runSmartLasso) {
                val path = Path().also { p ->
                    p.moveTo(gesturePoints[0].x, gesturePoints[0].y)
                    for (i in 1 until gesturePoints.size) p.lineTo(gesturePoints[i].x, gesturePoints[i].y)
                    p.lineTo(gesturePoints[0].x, gesturePoints[0].y)
                    p.close()
                }
                val hitIds = runLassoHitTest(
                    path,
                    strokeSnapshot.filter { it.id !in gestureIdSet },
                    headingSnapshot,
                    textSnapshot,
                    lineSnapshot,
                    linkSnapshot,
                    stickyNoteSnapshot,
                )
                if (hitIds.isNotEmpty()) {
                    val hitSet      = hitIds.toSet()
                    val unionBounds = RectF()
                    for (s in strokeSnapshot)   { if (s.id in hitSet) unionBounds.union(s.boundingBox) }
                    for (h in headingSnapshot)  { if (h.id in hitSet) unionBounds.union(h.boundingBox) }
                    for (t in textSnapshot)     { if (t.id in hitSet) unionBounds.union(t.boundingBox) }
                    for (l in lineSnapshot)     { if (l.id in hitSet) unionBounds.union(l.boundingBox) }
                    for (lk in linkSnapshot)    { if (lk.id in hitSet) unionBounds.union(lk.boundingBox) }
                    for (sn in stickyNoteSnapshot) { if (sn.id in hitSet) unionBounds.union(sn.boundingBox) }
                    post {
                        // Discard the gesture stroke — it is never page content.
                        strokes.removeAll { it.id in gestureIdSet }
                        if (isSetup) {
                            touchHelper.setRawDrawingRenderEnabled(false)
                            epd { "RENDER_DISABLED caller=smartLasso" }
                            invalidate()
                            epd { "INVALIDATE caller=smartLasso" }
                        }
                        onSmartLassoComplete?.invoke(hitIds, unionBounds)
                    }
                    return@Thread
                }
                // Smart-lasso geometry passed but no objects enclosed — fall through to
                // scribble check (a fast circle over empty space is an unusual but valid stroke).
                if (!isScribbleCandidate(gesturePoints)) {
                    post { onPenLifted?.invoke() }
                    return@Thread
                }
            }

            // ── Gate 2: Scribble-to-erase ──────────────────────────────────────────
            val hitIds = scribbleHitTest(
                gesturePoints,
                strokeSnapshot.filter { it.id !in gestureIdSet },
                headingSnapshot,
                textSnapshot,
                density,
                lineSnapshot,
                linkSnapshot,
                stickyNoteSnapshot,
            )
            post {
                if (hitIds.isEmpty()) {
                    onPenLifted?.invoke()
                } else {
                    strokes.removeAll { it.id in gestureIdSet }
                    if (isSetup) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                        epd { "RENDER_DISABLED caller=scribbleErase" }
                        invalidate()
                        epd { "INVALIDATE caller=scribbleErase" }
                    }
                    val hitSet            = hitIds.toSet()
                    val erasedHeadings    = headingSnapshot.filter { it.id in hitSet }
                    val erasedTexts       = textSnapshot.filter { it.id in hitSet }
                    val erasedLines       = lineSnapshot.filter { it.id in hitSet }
                    val erasedLinks       = linkSnapshot.filter { it.id in hitSet }
                    val erasedStickyNotes = stickyNoteSnapshot.filter { it.id in hitSet }
                    onScribbleEraseComplete?.invoke(hitIds, erasedHeadings, erasedTexts, erasedLines, erasedLinks, erasedStickyNotes)
                }
            }
        }.start()
    }

    /**
     * Returns true when [points] form a smart-lasso candidate — all three gates must pass:
     *  1. pathLength / durationMs ≥ [SMART_LASSO_MIN_VELOCITY] px/ms.
     *  2. Distance from first to last point ≤ [SMART_LASSO_CLOSURE_DISTANCE_DP] dp.
     *  3. The path winds ≥ [SMART_LASSO_MIN_WINDING_DEGREES]° around its centroid —
     *     i.e. the pen actually traced a loop, not a letter or open arc.
     */
    private fun isSmartLassoCandidate(points: List<PointF>, durationMs: Long, density: Float): Boolean {
        if (points.size < 4 || durationMs <= 0L) return false

        var pathLength = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLength += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        if (pathLength / durationMs < SMART_LASSO_MIN_VELOCITY) return false

        val closureThresholdPx = SMART_LASSO_CLOSURE_DISTANCE_DP * density
        val first = points[0]; val last = points[points.size - 1]
        val cdx = last.x - first.x; val cdy = last.y - first.y
        if (Math.sqrt((cdx * cdx + cdy * cdy).toDouble()).toFloat() > closureThresholdPx) return false

        // Winding check: accumulate signed angular change around the gesture centroid.
        // Letters and open arcs never wind 270°+ around a central point; loops always do.
        var cx = 0f; var cy = 0f
        for (p in points) { cx += p.x; cy += p.y }
        cx /= points.size; cy /= points.size

        var totalAngle = 0.0
        var prevAngle = Math.atan2((points[0].y - cy).toDouble(), (points[0].x - cx).toDouble())
        for (i in 1 until points.size) {
            val angle = Math.atan2((points[i].y - cy).toDouble(), (points[i].x - cx).toDouble())
            var delta = angle - prevAngle
            // Unwrap to [-π, π] so we measure true incremental rotation, not jumps.
            while (delta > Math.PI)  delta -= 2.0 * Math.PI
            while (delta < -Math.PI) delta += 2.0 * Math.PI
            totalAngle += delta
            prevAngle = angle
        }
        val windingDegrees = Math.abs(Math.toDegrees(totalAngle)).toFloat()
        return windingDegrees >= SMART_LASSO_MIN_WINDING_DEGREES
    }

    /**
     * Returns true when [points] satisfy both scribble heuristics:
     *  1. pathLength / boundingBoxDiagonal ≥ [SCRIBBLE_DENSITY_RATIO]
     *  2. At least [SCRIBBLE_MIN_DIRECTION_REVERSALS] significant direction reversals
     *     (consecutive movement vectors with a negative dot product, on noise-filtered points).
     */
    private fun isScribbleCandidate(points: List<PointF>): Boolean {
        if (points.size < 4) return false

        // Compute total path length and bounding box in one pass.
        var pathLength = 0f
        var minX = points[0].x; var minY = points[0].y
        var maxX = minX;        var maxY = minY
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLength += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (points[i].x < minX) minX = points[i].x else if (points[i].x > maxX) maxX = points[i].x
            if (points[i].y < minY) minY = points[i].y else if (points[i].y > maxY) maxY = points[i].y
        }
        val dw = maxX - minX; val dh = maxY - minY
        val diagonal = Math.sqrt((dw * dw + dh * dh).toDouble()).toFloat()
        if (diagonal < SCRIBBLE_MIN_DIAGONAL_DP * resources.displayMetrics.density) return false
        if (pathLength / diagonal < SCRIBBLE_DENSITY_RATIO) return false

        // Noise-filter: only keep points more than 2 px apart to reduce stylus jitter.
        val filtered = mutableListOf(points[0])
        for (p in points) {
            val last = filtered.last()
            val dx = p.x - last.x; val dy = p.y - last.y
            if (dx * dx + dy * dy >= 4f) filtered.add(p)
        }
        if (filtered.size < 3) return false

        // Count direction reversals on the filtered path.
        var reversals = 0
        for (i in 2 until filtered.size) {
            val ax = filtered[i - 1].x - filtered[i - 2].x
            val ay = filtered[i - 1].y - filtered[i - 2].y
            val bx = filtered[i].x - filtered[i - 1].x
            val by = filtered[i].y - filtered[i - 1].y
            if (ax * bx + ay * by < 0f) reversals++
        }
        return reversals >= SCRIBBLE_MIN_DIRECTION_REVERSALS
    }

    /**
     * Returns the IDs of all objects on the page touched by the scribble path.
     * Safe to call off the main thread (reads only the supplied snapshots).
     *
     * Strokes: AABB pre-filter then per-point segment-proximity within [SCRIBBLE_STROKE_TOUCH_RADIUS_DP].
     * Headings/text: the scribble path must travel at least [SCRIBBLE_BBOX_PENETRATION_DP] dp
     *   INSIDE the object's bounding box (prevents corner-grazes from triggering).
     */
    private fun scribbleHitTest(
        scribblePoints: List<PointF>,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        density: Float,
        lineObjects: List<LineRender> = emptyList(),
        links: List<LinkRender> = emptyList(),
        stickyNotes: List<StickyNoteRender> = emptyList(),
    ): List<String> {
        if (scribblePoints.size < 2) return emptyList()

        val touchRadiusPx = SCRIBBLE_STROKE_TOUCH_RADIUS_DP * density
        val touchRadiusSq = touchRadiusPx * touchRadiusPx
        val penetrationPx = SCRIBBLE_BBOX_PENETRATION_DP * density

        // Build expanded AABB of the scribble path (matches eraseAtPath pre-filter pattern).
        var sMinX = scribblePoints[0].x; var sMinY = scribblePoints[0].y
        var sMaxX = sMinX;               var sMaxY = sMinY
        for (sp in scribblePoints) {
            if (sp.x < sMinX) sMinX = sp.x else if (sp.x > sMaxX) sMaxX = sp.x
            if (sp.y < sMinY) sMinY = sp.y else if (sp.y > sMaxY) sMaxY = sp.y
        }
        val scribbleBounds = android.graphics.RectF(
            sMinX - touchRadiusPx, sMinY - touchRadiusPx,
            sMaxX + touchRadiusPx, sMaxY + touchRadiusPx,
        )

        val hitIds = mutableListOf<String>()

        // Stroke hit-test: any stroke point within touchRadius of any scribble segment.
        for (stroke in strokes) {
            if (!android.graphics.RectF.intersects(scribbleBounds, stroke.boundingBox)) continue
            var hit = false
            outer@ for (sp in stroke.points) {
                for (i in 1 until scribblePoints.size) {
                    if (pointToSegmentDistSq(sp, scribblePoints[i - 1], scribblePoints[i]) <= touchRadiusSq) {
                        hit = true; break@outer
                    }
                }
            }
            if (hit) hitIds.add(stroke.id)
        }

        // Heading hit-test: path must penetrate bbox by more than penetrationPx.
        val rawBounds = android.graphics.RectF(sMinX, sMinY, sMaxX, sMaxY)
        for (heading in headings) {
            if (!android.graphics.RectF.intersects(rawBounds, heading.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, heading.boundingBox) >= penetrationPx) {
                hitIds.add(heading.id)
            }
        }

        // Text object hit-test: same penetration rule.
        for (textObj in textObjects) {
            if (!android.graphics.RectF.intersects(rawBounds, textObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, textObj.boundingBox) >= penetrationPx) {
                hitIds.add(textObj.id)
            }
        }

        // Line object hit-test: same penetration rule (bbox is inflated on perpendicular axis at creation).
        for (lineObj in lineObjects) {
            if (!android.graphics.RectF.intersects(rawBounds, lineObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, lineObj.boundingBox) >= penetrationPx) {
                hitIds.add(lineObj.id)
            }
        }

        // Link object hit-test: same penetration rule (erases the whole link).
        for (link in links) {
            if (!android.graphics.RectF.intersects(rawBounds, link.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, link.boundingBox) >= penetrationPx) {
                hitIds.add(link.id)
            }
        }

        // Sticky-note icon hit-test: same penetration rule.
        for (note in stickyNotes) {
            if (!android.graphics.RectF.intersects(rawBounds, note.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, note.boundingBox) >= penetrationPx) {
                hitIds.add(note.id)
            }
        }

        return hitIds
    }

    /**
     * Sums the length of all scribble path segments that have at least one endpoint
     * inside [box]. Used to measure how far the scribble actually travels within the
     * bounding box, distinguishing deep crossings from edge-grazes.
     */
    private fun scribblePathPenetration(points: List<PointF>, box: android.graphics.RectF): Float {
        var total = 0f
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            if (box.contains(p1.x, p1.y) || box.contains(p2.x, p2.y)) {
                val dx = p2.x - p1.x; val dy = p2.y - p1.y
                total += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        }
        return total
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
                    // The Onyx SDK restores the previous session's exclusion zone asynchronously
                    // during or after openRawDrawing(), overwriting the setLimitRect we called
                    // inside it. Re-apply in the next looper turn — after the SDK restore has
                    // settled — using whatever toolbarExclusion the doOnLayout callbacks have set.
                    post { reapplyDrawingBounds() }
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
        // In any mode that disables the SDK raw-drawing path (setRawDrawingEnabled=false),
        // the SDK never fires onBeginRawErasing for the stylus button/eraser-end. Intercept
        // via Android events before the per-mode handler gets a chance to drop the event.
        // BOOX reports the barrel button as TOOL_TYPE_ERASER; also check BUTTON_STYLUS_PRIMARY.
        if (isTextPlacementMode || isLassoMode || isLassoEraserMode) {
            val t = event.getToolType(0)
            if (t == MotionEvent.TOOL_TYPE_ERASER
                || (t == MotionEvent.TOOL_TYPE_STYLUS
                    && (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)) {
                return handleBarrelButtonErase(event)
            }
        }
        if (isTextPlacementMode) return handleTextPlacementTouch(event)
        if (isLassoMode) return handleLassoTouch(event)
        if (isLassoEraserMode) return handleLassoEraserTouch(event)
        return if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)
    }

    private fun handleBarrelButtonErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                eraseAtPath(listOf(PointF(event.x, event.y)))
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = mutableListOf<PointF>()
                for (i in 0 until event.historySize) pts.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                pts.add(PointF(event.x, event.y))
                eraseAtPath(pts)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                eraseAtPath(listOf(PointF(event.x, event.y)))
                finalizeEraseRedraw()
                post {
                    EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                }
                onPenLifted?.invoke()
            }
        }
        return true
    }

    private fun handleTextPlacementTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record the press point but stay in placement mode so MOVE/UP are
                // also consumed here — not leaked to touchHelper / raw drawing.
                textPlacementTapX = event.x
                textPlacementTapY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Stylus has lifted: safe to exit placement mode and fire the callback.
                // enableDrawing() must NOT be called here — the dialog's focus-change
                // cycle (onWindowFocusChanged true → openRawDrawing) handles the
                // re-enable after the stylus is gone, eliminating the race where the
                // SDK sees a live stylus contact and starts a phantom stroke.
                isTextPlacementMode = false
                onTextPlacementTap?.invoke(textPlacementTapX, textPlacementTapY)
            }
        }
        return true
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
                    // Deep-copy selected strokes/headings/textObjects — original positions before any drag.
                    dragOriginalStrokes = strokes
                        .filter { it.id in lassoSelectedIds }
                        .map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) }
                    dragOriginalHeadings = headings
                        .filter { it.id in lassoSelectedIds }
                        .map { h -> HeadingStroke(h.id, android.graphics.RectF(h.boundingBox),
                            h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                            recognizedText = h.recognizedText,
                            level = h.level) }
                    dragOriginalTextObjects = textObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { TextRender(it.id, RectF(it.boundingBox), it.text) }
                    dragOriginalLineObjects = lineObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { it.copy(boundingBox = RectF(it.boundingBox)) }
                    dragOriginalLinks = links
                        .filter { it.id in lassoSelectedIds }
                        .map { it.translate(0f, 0f) }
                    dragOriginalStickyNotes = stickyNotes
                        .filter { it.id in lassoSelectedIds }
                        .map { it.translate(0f, 0f) }
                    // Build backing bitmap without selected strokes/headings/textObjects/lineObjects/links/stickyNotes (held for drag).
                    val nonSelectedStrokes  = strokes.filter { it.id !in lassoSelectedIds }
                    val nonSelectedHeadings = headings.filter { it.id !in lassoSelectedIds }
                    val nonSelectedTexts    = textObjects.filter { it.id !in lassoSelectedIds }
                    val nonSelectedLines    = lineObjects.filter { it.id !in lassoSelectedIds }
                    val nonSelectedLinks    = links.filter { it.id !in lassoSelectedIds }
                    val nonSelectedStickyNotes = stickyNotes.filter { it.id !in lassoSelectedIds }
                    dragBackingBitmap = buildRenderBitmap(nonSelectedStrokes, templateBitmap, nonSelectedHeadings, nonSelectedTexts, nonSelectedLines, nonSelectedLinks, nonSelectedStickyNotes)
                    snapObjectTargets = if (isSnapEnabled) (nonSelectedHeadings.map { RectF(it.boundingBox) } + nonSelectedTexts.map { RectF(it.boundingBox) } + nonSelectedLines.map { RectF(it.boundingBox) } + nonSelectedLinks.map { RectF(it.boundingBox) }) else emptyList()
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
                        if (isSnapEnabled) {
                            val density = resources.displayMetrics.density
                            val snap = SnapEngine.computeSnap(
                                lassoSelectionBox ?: RectF(),
                                dx, dy,
                                width.toFloat(), height.toFloat(),
                                SNAP_MARGIN_DP * density,
                                SNAP_THRESHOLD_DP * density,
                                snapObjectTargets,
                            )
                            dragDx = snap.snappedDx; dragDy = snap.snappedDy
                            activeSnapGuides = snap.activeGuides
                        } else {
                            dragDx = dx; dragDy = dy
                            activeSnapGuides = emptyList()
                        }
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
                            stroke.copy(points = stroke.points.map { pt ->
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
                                    s.copy(points = s.points.map { PointF(it.x + dragDx, it.y + dragDy) })
                                },
                                recognizedText = h.recognizedText,
                                level = h.level,
                            )
                        }
                        // Translate selected text objects (boundingBox only — text content unchanged).
                        val movedTextObjects = dragOriginalTextObjects.map { t ->
                            TextRender(t.id, RectF(
                                t.boundingBox.left + dragDx, t.boundingBox.top + dragDy,
                                t.boundingBox.right + dragDx, t.boundingBox.bottom + dragDy,
                            ), t.text)
                        }
                        // Translate selected line objects (bbox + start/end coordinates).
                        val movedLineObjects = dragOriginalLineObjects.map { l ->
                            l.copy(
                                boundingBox = RectF(
                                    l.boundingBox.left + dragDx, l.boundingBox.top + dragDy,
                                    l.boundingBox.right + dragDx, l.boundingBox.bottom + dragDy,
                                ),
                                startX = l.startX + dragDx, startY = l.startY + dragDy,
                                endX   = l.endX   + dragDx, endY   = l.endY   + dragDy,
                            )
                        }
                        // Translate selected links (bbox + every embedded object's coordinates).
                        val movedLinks = dragOriginalLinks.map { it.translate(dragDx, dragDy) }
                        // Translate selected sticky notes (icon bbox only — content is in its own space).
                        val movedStickyNotes = dragOriginalStickyNotes.map { it.translate(dragDx, dragDy) }
                        // Update in-memory stroke list with translated positions.
                        val movedById = movedStrokes.associateBy { it.id }
                        val updated = strokes.map { movedById[it.id] ?: it }
                        strokes.clear(); strokes.addAll(updated)
                        // Update in-memory heading list with translated positions.
                        val headingById = movedHeadings.associateBy { it.id }
                        headings = headings.map { headingById[it.id] ?: it }
                        // Update in-memory text object list with translated positions.
                        val textById = movedTextObjects.associateBy { it.id }
                        textObjects = textObjects.map { textById[it.id] ?: it }
                        // Update in-memory line object list with translated positions.
                        val lineById = movedLineObjects.associateBy { it.id }
                        lineObjects = lineObjects.map { lineById[it.id] ?: it }
                        // Update in-memory link list with translated positions.
                        val linkById = movedLinks.associateBy { it.id }
                        links = links.map { linkById[it.id] ?: it }
                        // Update in-memory sticky note list with translated icon boxes.
                        val stickyById = movedStickyNotes.associateBy { it.id }
                        stickyNotes = stickyNotes.map { stickyById[it.id] ?: it }
                        // Translate selection box to match new positions.
                        lassoSelectionBox = lassoSelectionBox?.let { b ->
                            RectF(b.left + dragDx, b.top + dragDy, b.right + dragDx, b.bottom + dragDy)
                        }
                        // Restore normal EPD update mode and commit pixels BEFORE firing callback.
                        val origStrokes = dragOriginalStrokes
                        val origHeadings = dragOriginalHeadings
                        val origTextObjects = dragOriginalTextObjects
                        val origLineObjects = dragOriginalLineObjects
                        val origLinks = dragOriginalLinks
                        val origStickyNotes = dragOriginalStickyNotes
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
                        EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU)
                        epd { "DRAG_COMMIT A2_MODE_OFF" }
                        redrawCanvas(caller = "dragCommit")
                        post {
                            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
                            epd { "HANDWRITING_REPAINT caller=dragCommit" }
                        }
                        onStrokesMoved?.invoke(origStrokes, movedStrokes, origHeadings, movedHeadings, origTextObjects, movedTextObjects, origLineObjects, movedLineObjects, origLinks, movedLinks, origStickyNotes, movedStickyNotes)
                    } else {
                        // Below threshold — treat as a tap inside the selection box.
                        val tapX = event.x; val tapY = event.y
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
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
                // Tap vs lasso: use the gesture's overall extent, not net start→end displacement.
                // A small circular lasso returns near its origin (tiny net displacement) but spans
                // a real bounding box — displacement alone would misclassify it as a tap and paste.
                val gestureBounds = RectF()
                path.computeBounds(gestureBounds, true)
                if (gestureBounds.width() < thresholdPx && gestureBounds.height() < thresholdPx) {
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

        // Drag layer: non-selected backing + selected headings/textObjects/strokes translated.
        if (isDragMoveActive && dragThresholdMet) {
            dragBackingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                ?: canvas.drawColor(Color.WHITE)
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
            for (heading in dragOriginalHeadings) {
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
            for (textObj in dragOriginalTextObjects) {
                drawTextObject(canvas, textObj, width)
            }
            for (lineObj in dragOriginalLineObjects) {
                drawLineObject(canvas, lineObj)
            }
            for (shape in dragOriginalShapeObjects) {
                drawShapeObject(canvas, shape)
            }
            for (link in dragOriginalLinks) {
                drawLinkObject(canvas, link, width)
            }
            for (note in dragOriginalStickyNotes) {
                drawStickyNoteObject(canvas, note)
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
            drawSnapGuides(canvas)
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

    // ── NotebookView interface ────────────────────────────────────────────────

    override fun asView(): View = this

    override fun setToolbarExclusion(rect: Rect?) {
        toolbarExclusion = rect?.takeUnless { it.isEmpty }?.let { Rect(it) }
        Slog.d(TAG) { "setToolbarExclusion rect=$toolbarExclusion" }
        if (isSetup) applyLimitRect()
    }

    override fun reapplyDrawingBounds() {
        if (!isSetup) return
        // Do NOT call restartRawDrawing() here. The Onyx SDK persists the exclusion zone and
        // restores it every time restartRawDrawing() (or openRawDrawing()) is called — so calling
        // restart would immediately undo the cleared exclusion we just set. Instead, call
        // applyLimitRect() directly: the SDK honours dynamic setLimitRect updates (proven by the
        // add-exclusion path), and the off-screen dummy rect in applyLimitRect() forces it to
        // actually process the call rather than treating an emptyList as a no-op.
        applyLimitRect()
        epd { "REAPPLY_LIMIT_RECT caller=reapplyDrawingBounds exclusion=${toolbarExclusion}" }
    }

    override fun enableDrawing() {
        if (isSetup && !isLassoMode && !isLassoEraserMode && !isTextPlacementMode) {
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

    override fun releaseRender() {
        if (!isSetup) return
        touchHelper.setRawDrawingRenderEnabled(false)
        epd { "RENDER_DISABLED caller=releaseRender" }
        invalidate()
        epd { "INVALIDATE caller=releaseRender" }
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

    override fun setTextPlacementMode(active: Boolean) {
        isTextPlacementMode = active
        if (active && isSetup) {
            touchHelper.setRawDrawingEnabled(false)
            epd { "RAW_DRAWING_ENABLED false caller=setTextPlacementMode" }
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
            dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList()
            dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
            dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
            dragOriginalLinks = emptyList(); dragOriginalStickyNotes = emptyList()
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
                // Tap vs lasso: classify by gesture extent, not net displacement, so a small
                // circular erase gesture (returns near its origin) is not mistaken for a tap.
                val gestureBounds = RectF()
                path.computeBounds(gestureBounds, true)
                if (gestureBounds.width() < tapThresholdPx && gestureBounds.height() < tapThresholdPx) {
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
        val strokeSnapshot    = strokes.toList()
        val headingSnapshot   = headings.toList()
        val textSnapshot      = textObjects.toList()
        val lineSnapshot      = lineObjects.toList()
        val linkSnapshot      = links.toList()
        val stickyNoteSnapshot = stickyNotes.toList()
        Thread {
            val hitIds = runLassoHitTest(drawnPath, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot, linkSnapshot, stickyNoteSnapshot)
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
        textObjects: List<TextRender> = emptyList(),
        lineObjects: List<LineRender> = emptyList(),
        links: List<LinkRender> = emptyList(),
        stickyNotes: List<StickyNoteRender> = emptyList(),
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
        // Heading / text / line hit-test: select if the lasso overlaps any part of the object's
        // bounding box (touch semantics, matching strokes) — not just its center point.
        for (heading in headings) {
            if (!RectF.intersects(bounds, heading.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, heading.boundingBox)) {
                hitIds.add(heading.id)
            }
        }
        for (textObj in textObjects) {
            if (!RectF.intersects(bounds, textObj.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, textObj.boundingBox)) {
                hitIds.add(textObj.id)
            }
        }
        for (lineObj in lineObjects) {
            if (!RectF.intersects(bounds, lineObj.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, lineObj.boundingBox)) {
                hitIds.add(lineObj.id)
            }
        }
        for (link in links) {
            if (!RectF.intersects(bounds, link.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, link.boundingBox)) {
                hitIds.add(link.id)
            }
        }
        for (note in stickyNotes) {
            if (!RectF.intersects(bounds, note.boundingBox)) continue
            if (LassoGeometry.regionIntersectsBox(region, note.boundingBox)) {
                hitIds.add(note.id)
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
     * Uses the same EPD handoff pattern as eraseAll(): disable overlay render → draw to bitmap
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

    override fun eraseAll() {
        epd { "ERASE_ALL_START isSetup=$isSetup strokeCountBefore=${strokes.size}" }
        strokes.clear()
        headings = emptyList()
        textObjects = emptyList()
        lineObjects = emptyList()
        shapeObjects = emptyList()
        links = emptyList()
        stickyNotes = emptyList()
        if (isSetup) {
            touchHelper.setRawDrawingRenderEnabled(false)
            epd { "RENDER_DISABLED caller=eraseAll" }
        }
        // Clear to white then re-apply template so the template persists after erase.
        renderCanvas?.let { canvas ->
            canvas.drawColor(Color.WHITE)
            epd { "WHITE_BITMAP_FILL caller=eraseAll" }
            templateBitmap?.let { tb ->
                canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            }
        }
        invalidate()
        epd { "INVALIDATE caller=eraseAll" }
        post {
            EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            epd { "HANDWRITING_REPAINT caller=eraseAll" }
            post {
                if (isSetup && !isLassoMode && !isLassoEraserMode) {
                    touchHelper.setRawDrawingEnabled(true)
                    epd { "RAW_DRAWING_ENABLED true caller=eraseAll" }
                    if (isEraserMode) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                        epd { "RENDER_DISABLED caller=eraseAll_eraserMode" }
                    }
                }
            }
        }
    }

    override fun loadHeadings(headings: List<HeadingStroke>) {
        this.headings = headings
    }

    override fun getHeadings(): List<HeadingStroke> = headings

    override fun loadTextObjects(textObjects: List<TextRender>) {
        this.textObjects = textObjects
    }

    override fun getTextObjects(): List<TextRender> = textObjects

    override fun compositeTextObjects(bitmap: Bitmap) {
        if (textObjects.isEmpty()) return
        val canvas = android.graphics.Canvas(bitmap)
        for (textObj in textObjects) {
            drawTextObject(canvas, textObj, bitmap.width)
        }
    }

    override fun loadLineObjects(lineObjects: List<LineRender>) {
        this.lineObjects = lineObjects
    }

    override fun getLineObjects(): List<LineRender> = lineObjects

    override fun compositeLineObjects(bitmap: Bitmap) {
        if (lineObjects.isEmpty()) return
        val canvas = android.graphics.Canvas(bitmap)
        for (lineObj in lineObjects) {
            drawLineObject(canvas, lineObj)
        }
    }

    override fun loadLinks(links: List<LinkRender>) {
        this.links = links
    }

    override fun getLinks(): List<LinkRender> = links

    override fun compositeLinks(bitmap: Bitmap) {
        if (links.isEmpty()) return
        val canvas = android.graphics.Canvas(bitmap)
        for (link in links) {
            drawLinkObject(canvas, link, bitmap.width)
        }
    }

    override fun loadStickyNotes(stickyNotes: List<StickyNoteRender>) {
        this.stickyNotes = stickyNotes
        redrawCanvas(caller = "loadStickyNotes")
    }

    override fun getStickyNotes(): List<StickyNoteRender> = stickyNotes

    override fun compositeStickyNotes(bitmap: Bitmap) {
        if (stickyNotes.isEmpty()) return
        val canvas = android.graphics.Canvas(bitmap)
        for (note in stickyNotes) {
            drawStickyNoteObject(canvas, note)
        }
    }

    override fun loadShapeObjects(shapeObjects: List<ShapeRender>) {
        this.shapeObjects = shapeObjects
    }

    override fun getShapeObjects(): List<ShapeRender> = shapeObjects

    override fun compositeShapeObjects(bitmap: Bitmap) {
        if (shapeObjects.isEmpty()) return
        val canvas = android.graphics.Canvas(bitmap)
        for (shape in shapeObjects) {
            drawShapeObject(canvas, shape)
        }
    }

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
        textObjects: List<TextRender>?,
        lineObjects: List<LineRender>?,
        links: List<LinkRender>?,
        stickyNotes: List<StickyNoteRender>?,
        shapeObjects: List<ShapeRender>?,
    ): Bitmap? {
        val buildStart = System.currentTimeMillis()
        epd { "BUILD_RENDER_BITMAP_START strokeCount=${strokes.size} hasTemplate=${templateBitmap != null}" }
        val w = width; val h = height
        if (w == 0 || h == 0) {
            epd { "BUILD_RENDER_BITMAP_ABORT reason=zeroSize size=${w}x${h}" }
            return null
        }
        // null = fall back to stored field (undo/redo paths); non-null = explicit list (page load)
        val effectiveTextObjects = textObjects ?: this.textObjects
        val effectiveLineObjects = lineObjects ?: this.lineObjects
        val effectiveLinks = links ?: this.links
        val effectiveStickyNotes = stickyNotes ?: this.stickyNotes
        val effectiveShapeObjects = shapeObjects ?: this.shapeObjects
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        for (heading in headings) {
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
        for (textObj in effectiveTextObjects) {
            drawTextObject(canvas, textObj, w)
        }
        for (lineObj in effectiveLineObjects) {
            drawLineObject(canvas, lineObj)
        }
        for (shape in effectiveShapeObjects) {
            drawShapeObject(canvas, shape)
        }
        for (link in effectiveLinks) {
            drawLinkObject(canvas, link, w)
        }
        for (note in effectiveStickyNotes) {
            drawStickyNoteObject(canvas, note)
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
     * handoff pattern of [setTemplate] / [eraseAll] so e-ink pixels are committed
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

        // EPD overlay handoff — same pattern as setTemplate() / eraseAll().
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
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return null
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Transparent base — do NOT fill with white, do NOT paint the template.
        val snapshotCanvas = Canvas(bmp)
        for (heading in headings) {
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
        for (textObj in textObjects) {
            drawTextObject(snapshotCanvas, textObj, w)
        }
        for (lineObj in lineObjects) {
            drawLineObject(snapshotCanvas, lineObj)
        }
        for (link in links) {
            drawLinkObject(snapshotCanvas, link, w)
        }
        for (note in stickyNotes) {
            drawStickyNoteObject(snapshotCanvas, note)
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
            minOf(width, frame.right - loc[0]),
            minOf(height, frame.bottom - loc[1])
        )
        // Pass an off-screen dummy rect instead of emptyList() when there is no exclusion.
        // The Onyx SDK treats an empty list as a no-op — it silently ignores the call and
        // keeps whatever exclusion zone was previously active (or restored from its persisted
        // state). A non-empty list with an off-screen rect forces the SDK to process the
        // update and effectively clears any previous exclusion zone.
        val exclusion = toolbarExclusion?.let { listOf(Rect(it)) }
            ?: listOf(Rect(-1, -1, 0, 0))
        Slog.d(TAG) { "applyLimitRect: limitRect=$limitRect exclusion=$exclusion" }
        touchHelper.setLimitRect(limitRect, exclusion)
    }

    private fun openRawDrawing() {
        epd { "OPEN_RAW_DRAWING_START isSetup=$isSetup size=${width}x${height} toolbarExclusion=$toolbarExclusion" }
        Slog.d(TAG) { "openRawDrawing isSetup=$isSetup toolbarExclusion=$toolbarExclusion size=${width}x${height}" }
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
        if (!isLassoMode && !isLassoEraserMode && !isTextPlacementMode) {
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
