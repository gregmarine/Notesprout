package com.notesprout.android.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.Base64
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import java.io.ByteArrayOutputStream
import java.util.UUID

// Flutter-equivalent of GenericDrawingEngine — pure Android Canvas rendering.
// Stylus-only: finger touch is rejected at the MotionEvent level.
// Two-layer approach mirrors the Flutter implementation:
//   committed layer — Bitmap accumulates finished strokes (redrawn only on stroke commit or clear)
//   active layer    — current in-progress stroke drawn directly in onDraw per touch event
class GenericDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val ERASER_RADIUS_PX = 15f
        private const val ERASE_REDRAW_INTERVAL_MS = 60L
        private const val LASSO_REFRESH_INTERVAL_MS = 60L
    }

    private val activePoints = ArrayList<PointF>()
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private var isEraserActive = false

    private var lastEraseRedrawMs = 0L

    /** Template bitmap — drawn as the base layer behind all strokes. Null = white background. */
    private var templateBitmap: Bitmap? = null

    // Stroke store — LiveStroke carries the DB row UUID for incremental save / targeted erase.
    private val strokes = mutableListOf<LiveStroke>()

    // Heading store — populated from type="heading" rows at page load time.
    private var headings: List<HeadingStroke> = emptyList()

    private val headingPaint = Paint().apply {
        style = Paint.Style.FILL
        color = HEADING_BACKGROUND_COLOR
        isAntiAlias = false
    }

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f
    }

    // ── Lasso state ──────────────────────────────────────────────────────────

    private var isLassoMode = false
    private var isLassoEraserMode = false
    private var lassoOverlayPath: Path? = null
    private var lassoSelectionBox: RectF? = null
    private var lassoGestureStartPoint: PointF? = null
    private var lassoGesturePath: Path? = null
    private var lastLassoRefreshMs = 0L

    private var lassoEraserDisplayPath: Path? = null
    private val lassoEraserRandom = java.util.Random()
    private fun jitter() = (lassoEraserRandom.nextFloat() - 0.5f) * 8f

    // ── Lasso drag move state ────────────────────────────────────────────────

    private var isDragMoveActive = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragThresholdMet = false
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragOriginalStrokes: List<LiveStroke> = emptyList()
    private var dragBackingBitmap: Bitmap? = null

    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }

    private val lassoEraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 150, 150, 150)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }

    // ── DrawingView callbacks ────────────────────────────────────────────────

    override var onStrokeErased: ((String) -> Unit)? = null
    override var onHeadingErased: ((String) -> Unit)? = null
    override var onPenLifted: (() -> Unit)? = null
    override var onSnapshotReady: ((String) -> Unit)? = null
    override var onLassoComplete: ((Path, PointF) -> Unit)? = null
    override var onLassoTapToDismiss: (() -> Unit)? = null
    override var onLassoEraseComplete: ((List<String>) -> Unit)? = null
    override var lassoSelectedIds: Set<String> = emptySet()
    override var onStrokesMoved: ((List<LiveStroke>, List<LiveStroke>) -> Unit)? = null
    override var onLassoTap: ((Float, Float) -> Unit)? = null
    override var onDragStarted: (() -> Unit)? = null
    override var onLassoSelectionCleared: (() -> Unit)? = null

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
        if (isLassoMode) return handleLassoTouch(event)
        if (isLassoEraserMode) return handleLassoEraserTouch(event)

        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false

        val erasing = toolType == MotionEvent.TOOL_TYPE_ERASER || isEraserActive

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePoints.clear()
                activePoints.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val newPoints = mutableListOf<PointF>()
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
                    finalizeEraseRedraw()
                } else {
                    activePoints.add(PointF(event.x, event.y))
                    commitActiveStroke()
                    onPenLifted?.invoke()
                }
                activePoints.clear()
                invalidate()
            }
        }
        return true
    }

    private fun handleLassoTouch(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Finger tap with an active selection dismisses it.
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS
            && lassoSelectionBox != null
            && event.actionMasked == MotionEvent.ACTION_DOWN) {
            onLassoTapToDismiss?.invoke()
            return true
        }

        // Only stylus builds the lasso path / drag; finger taps fall through to false.
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
                    dragOriginalStrokes = strokes
                        .filter { it.id in lassoSelectedIds }
                        .map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) }
                    val nonSelected = strokes.filter { it.id !in lassoSelectedIds }
                    dragBackingBitmap = buildRenderBitmap(nonSelected, templateBitmap, headings)
                    return true
                }
                // Normal lasso: clear any existing selection so the user sees immediate feedback.
                val hadSelection = lassoSelectionBox != null
                lassoSelectionBox = null
                lassoOverlayPath  = null
                invalidate()
                if (hadSelection) onLassoSelectionCleared?.invoke()
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
                        }
                    }
                    if (dragThresholdMet) {
                        dragDx = dx; dragDy = dy
                        val now = System.currentTimeMillis()
                        if (now - lastLassoRefreshMs >= LASSO_REFRESH_INTERVAL_MS) {
                            lastLassoRefreshMs = now
                            invalidate()
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
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragMoveActive) {
                    if (dragThresholdMet) {
                        val movedStrokes = dragOriginalStrokes.map { stroke ->
                            LiveStroke(stroke.id, stroke.points.map { pt ->
                                PointF(pt.x + dragDx, pt.y + dragDy)
                            })
                        }
                        val movedById = movedStrokes.associateBy { it.id }
                        val updated = strokes.map { movedById[it.id] ?: it }
                        strokes.clear(); strokes.addAll(updated)
                        lassoSelectionBox = lassoSelectionBox?.let { b ->
                            RectF(b.left + dragDx, b.top + dragDy, b.right + dragDx, b.bottom + dragDy)
                        }
                        val origStrokes = dragOriginalStrokes
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; dragOriginalStrokes = emptyList()
                        redrawCanvas()
                        onStrokesMoved?.invoke(origStrokes, movedStrokes)
                    } else {
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; dragOriginalStrokes = emptyList()
                    }
                    return true
                }
                val path = lassoGesturePath ?: return true
                val start = lassoGestureStartPoint ?: return true
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
                lassoGesturePath = null
                lassoGestureStartPoint = null
                lassoOverlayPath = null
                invalidate()
                val dx = event.x - start.x
                val dy = event.y - start.y
                if (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() < thresholdPx) {
                    onLassoTapToDismiss?.invoke()
                    onLassoTap?.invoke(event.x, event.y)
                } else {
                    onLassoComplete?.invoke(path, start)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Drag layer: non-selected backing + selected strokes translated by drag offset.
        if (isDragMoveActive && dragThresholdMet) {
            canvas.drawColor(Color.WHITE)
            dragBackingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            val save = canvas.save()
            canvas.translate(dragDx, dragDy)
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

        canvas.drawColor(Color.WHITE)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        if (!isEraserActive && !isLassoMode && activePoints.size >= 2) {
            val path = Path()
            path.moveTo(activePoints[0].x, activePoints[0].y)
            for (i in 1 until activePoints.size) {
                path.lineTo(activePoints[i].x, activePoints[i].y)
            }
            canvas.drawPath(path, strokePaint)
        }

        // Lasso overlay — drawn on top of everything.
        if (isLassoEraserMode) {
            lassoEraserDisplayPath?.let { canvas.drawPath(it, lassoEraserPaint) }
        } else {
            lassoOverlayPath?.let { canvas.drawPath(it, lassoPaint) }
            lassoSelectionBox?.let { canvas.drawRect(it, lassoPaint) }
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

        // Build the expanded AABB of the entire eraser path for fast stroke pre-rejection.
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
        val hitHeadings = headings.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitHeadings.isNotEmpty()) {
            val hitIds = hitHeadings.mapTo(HashSet()) { it.id }
            headings = headings.filter { it.id !in hitIds }
            hitHeadings.forEach { onHeadingErased?.invoke(it.id) }
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
     * Strokes are already removed from [strokes] before this is called.
     */
    private fun throttledEraseRedraw() {
        val now = System.currentTimeMillis()
        if (now - lastEraseRedrawMs >= ERASE_REDRAW_INTERVAL_MS) {
            lastEraseRedrawMs = now
            redrawCanvas()
        }
    }

    /**
     * Force an immediate redraw at gesture end — flushes any throttled removals so
     * the canvas is always correct on pen lift.
     */
    private fun finalizeEraseRedraw() {
        lastEraseRedrawMs = System.currentTimeMillis()
        redrawCanvas()
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
        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
            for (liveStroke in heading.strokes) {
                val pts = liveStroke.points; if (pts.size < 2) continue
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, strokePaint)
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
    override fun setToolbarHeight(heightPx: Int) {}
    override fun enableDrawing() {}
    override fun disableDrawing() {}

    override fun setDragMoveMode(enabled: Boolean) {
        if (!enabled && isDragMoveActive) {
            dragBackingBitmap?.recycle(); dragBackingBitmap = null
            isDragMoveActive = false; dragThresholdMet = false
            dragDx = 0f; dragDy = 0f; dragOriginalStrokes = emptyList()
            invalidate()
        }
    }

    override fun setLassoMode(active: Boolean) {
        isLassoMode = active
        if (!active) {
            if (isDragMoveActive) setDragMoveMode(false)
            lassoOverlayPath = null
            lassoSelectionBox = null
            lassoGestureStartPoint = null
            lassoGesturePath = null
            invalidate()
        }
    }

    override fun setLassoEraserMode(active: Boolean) {
        isLassoEraserMode = active
        if (!active) {
            lassoOverlayPath       = null
            lassoEraserDisplayPath = null
            lassoGestureStartPoint = null
            lassoGesturePath       = null
            invalidate()
        }
    }

    private fun handleLassoEraserTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        val tapThresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoEraserDisplayPath = null
                invalidate()
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
                val dx = event.x - start.x
                val dy = event.y - start.y
                if (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() >= tapThresholdPx) {
                    performLassoErase(path, start)
                }
                // Tap: clear overlay, stay in lasso eraser mode (no further action needed).
            }
        }
        return true
    }

    private fun performLassoErase(drawnPath: Path, startPoint: PointF) {
        drawnPath.lineTo(startPoint.x, startPoint.y)
        drawnPath.close()
        val strokeSnapshot = strokes.toList()
        Thread {
            val hitIds = runLassoHitTest(drawnPath, strokeSnapshot)
            post {
                lassoOverlayPath       = null
                lassoEraserDisplayPath = null
                invalidate()
                if (hitIds.isNotEmpty()) {
                    onLassoEraseComplete?.invoke(hitIds)
                }
            }
        }.start()
    }

    private fun runLassoHitTest(path: Path, strokes: List<LiveStroke>): List<String> {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 10f || bounds.height() < 10f) return emptyList()
        val clipRect = android.graphics.Rect(
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
        return hitIds
    }

    override fun setLassoSelectedIds(ids: Set<String>, box: RectF) {
        lassoSelectedIds = ids
        setLassoOverlay(null, box)
    }

    override fun setLassoOverlay(path: Path?, selectionBox: RectF?) {
        lassoOverlayPath = path
        lassoSelectionBox = selectionBox
        invalidate()
    }

    override fun setEraserMode(active: Boolean) {
        if (active) {
            // Capture snapshot BEFORE erasing begins — strokes still in memory at this point.
            captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
        }
        isEraserActive = active
    }

    /**
     * Set the template bitmap to use as the page background.
     * Null = plain white. Redraws the canvas immediately (strokes on top of new template).
     */
    override fun setTemplate(bitmap: Bitmap?) {
        // Capture snapshot BEFORE the template changes — snapshot is strokes-only so it
        // remains valid across template switches.  Must run before templateBitmap is updated.
        captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
        templateBitmap = bitmap
        redrawCanvas()
    }

    override fun clearCanvas() {
        activePoints.clear()
        strokes.clear()
        headings = emptyList()
        // Clear to white then re-apply template so the template persists after clear.
        renderCanvas?.let { canvas ->
            canvas.drawColor(Color.WHITE)
            templateBitmap?.let { tb ->
                canvas.drawBitmap(tb, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            }
        }
        invalidate()
    }

    override fun loadHeadings(headings: List<HeadingStroke>) {
        this.headings = headings
    }

    override fun getHeadings(): List<HeadingStroke> = headings

    override fun loadStrokes(strokes: List<LiveStroke>) {
        this.strokes.clear()
        this.strokes.addAll(strokes)
        redrawCanvas()
    }

    override fun getStrokes(): List<LiveStroke> = strokes.toList()

    // ── Option B: off-thread bitmap pre-build ─────────────────────────────────

    /** Build the render bitmap on a background thread. Safe to call from Dispatchers.IO. */
    override fun buildRenderBitmap(
        strokes: List<LiveStroke>,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke>,
    ): Bitmap? {
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
            for (liveStroke in heading.strokes) {
                val pts = liveStroke.points; if (pts.size < 2) continue
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, strokePaint)
            }
        }
        for (liveStroke in strokes) {
            val pts = liveStroke.points
            if (pts.size < 2) continue
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, strokePaint)
        }
        return bmp
    }

    /** Swap in a pre-built bitmap on the main thread — skips the O(N) redraw. */
    override fun loadStrokesWithBitmap(
        strokes: List<LiveStroke>,
        bitmap: Bitmap,
        templateBitmap: Bitmap?,
    ) {
        this.strokes.clear()
        this.strokes.addAll(strokes)
        this.templateBitmap = templateBitmap
        renderBitmap?.recycle()
        renderBitmap = bitmap
        renderCanvas = Canvas(bitmap)
        invalidate()
    }

    /**
     * Capture snapshot when the app loses window focus — equivalent non-writing transition
     * boundary to OnyxDrawingView.onWindowFocusChanged(false).
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            captureSnapshot()?.let { onSnapshotReady?.invoke(it) }
        }
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
            for (liveStroke in heading.strokes) {
                val points = liveStroke.points
                if (points.size < 2) continue
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                snapshotCanvas.drawPath(path, strokePaint)
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
     * Silently update the in-memory stroke list without triggering any canvas redraw.
     * Called after a snapshot fast-load so stroke data is available for erasing and
     * export while the snapshot composite is already displayed on screen.
     */
    override fun setStrokeListSilently(strokes: List<LiveStroke>) {
        this.strokes.clear()
        this.strokes.addAll(strokes)
        // No redraw — the snapshot composite bitmap already shows the correct visual state.
    }

    override fun releaseResources() {
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
        dragBackingBitmap?.recycle()
        dragBackingBitmap = null
    }
}
