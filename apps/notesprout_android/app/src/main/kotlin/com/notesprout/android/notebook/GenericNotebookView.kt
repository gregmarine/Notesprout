package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.notesprout.android.R
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.util.TypedValue
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import com.notesprout.android.core.markdown.TextObjectRenderer
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender
import java.io.ByteArrayOutputStream
import java.util.UUID

// Flutter-equivalent of GenericDrawingEngine — pure Android Canvas rendering.
// Stylus-only: finger touch is rejected at the MotionEvent level.
// Two-layer approach mirrors the Flutter implementation:
//   committed layer — Bitmap accumulates finished strokes (redrawn only on stroke commit or clear)
//   active layer    — current in-progress stroke drawn directly in onDraw per touch event
class GenericNotebookView(context: Context) : View(context), NotebookView {

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

    // Text object store — populated from type="text" rows at page load time.
    private var textObjects: List<TextRender> = emptyList()

    // Line object store — populated from type="line" rows at page load time.
    private var lineObjects: List<LineRender> = emptyList()

    private val textObjectTextSizePx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics
    )
    private val textObjectPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textObjectTextSizePx
    }

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

    // ── Text placement mode ───────────────────────────────────────────────────

    private var isTextPlacementMode = false
    override var onTextPlacementTap: ((Float, Float) -> Unit)? = null

    // Coordinates captured on ACTION_DOWN; callback fires on ACTION_UP so the full
    // tap gesture is consumed by placement mode and not leaked to the drawing path.
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
    private var dragOriginalHeadings: List<HeadingStroke> = emptyList()
    private var dragOriginalTextObjects: List<TextRender> = emptyList()
    private var dragOriginalLineObjects: List<LineRender> = emptyList()
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

    private val lassoEraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 150, 150, 150)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }

    // ── NotebookView callbacks ────────────────────────────────────────────────

    override var onStrokeErased: ((String) -> Unit)? = null
    override var onHeadingErased: ((HeadingStroke) -> Unit)? = null
    override var onTextErased: ((TextRender) -> Unit)? = null
    override var onLineErased: ((LineRender) -> Unit)? = null
    override var onScribbleEraseComplete: ((List<String>, List<HeadingStroke>, List<TextRender>, List<LineRender>) -> Unit)? = null
    override var onSmartLassoComplete: ((List<String>, RectF) -> Unit)? = null
    override var onPenLifted: (() -> Unit)? = null

    // Wall-clock time of the last ACTION_DOWN, used to compute gesture duration for smart-lasso velocity.
    private var strokeStartTimeMs = 0L
    override var onSnapshotReady: ((String) -> Unit)? = null
    override var onLassoComplete: ((Path, PointF) -> Unit)? = null
    override var onLassoTapToDismiss: (() -> Unit)? = null
    override var onLassoEraseComplete: ((List<String>) -> Unit)? = null
    override var lassoSelectedIds: Set<String> = emptySet()
    override var onStrokesMoved: ((List<LiveStroke>, List<LiveStroke>, List<HeadingStroke>, List<HeadingStroke>, List<TextRender>, List<TextRender>, List<LineRender>, List<LineRender>) -> Unit)? = null
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
        if (isTextPlacementMode) return handleTextPlacementTouch(event)
        if (isLassoMode) return handleLassoTouch(event)
        if (isLassoEraserMode) return handleLassoEraserTouch(event)

        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false

        val erasing = toolType == MotionEvent.TOOL_TYPE_ERASER || isEraserActive

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePoints.clear()
                activePoints.add(PointF(event.x, event.y))
                strokeStartTimeMs = System.currentTimeMillis()
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
                    activePoints.clear()
                    invalidate()
                } else {
                    activePoints.add(PointF(event.x, event.y))
                    commitActiveStroke()
                    activePoints.clear()
                    invalidate()
                    val durationMs = (System.currentTimeMillis() - strokeStartTimeMs).coerceAtLeast(1L)
                    checkAndDispatchGesture(durationMs)
                }
            }
        }
        return true
    }

    private fun handleTextPlacementTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record the press point but stay in placement mode so MOVE/UP are
                // also consumed here — not leaked to the normal stroke-drawing path.
                textPlacementTapX = event.x
                textPlacementTapY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTextPlacementMode = false
                onTextPlacementTap?.invoke(textPlacementTapX, textPlacementTapY)
            }
        }
        return true
    }

    private fun handleLassoTouch(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

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
                    dragOriginalHeadings = headings
                        .filter { it.id in lassoSelectedIds }
                        .map { h -> HeadingStroke(h.id, android.graphics.RectF(h.boundingBox),
                            h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                            recognizedText = h.recognizedText) }
                    dragOriginalTextObjects = textObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { TextRender(it.id, RectF(it.boundingBox), it.text) }
                    dragOriginalLineObjects = lineObjects
                        .filter { it.id in lassoSelectedIds }
                        .map { it.copy(boundingBox = RectF(it.boundingBox)) }
                    val nonSelectedStrokes  = strokes.filter { it.id !in lassoSelectedIds }
                    val nonSelectedHeadings = headings.filter { it.id !in lassoSelectedIds }
                    val nonSelectedTexts    = textObjects.filter { it.id !in lassoSelectedIds }
                    val nonSelectedLines    = lineObjects.filter { it.id !in lassoSelectedIds }
                    dragBackingBitmap = buildRenderBitmap(nonSelectedStrokes, templateBitmap, nonSelectedHeadings, nonSelectedTexts, nonSelectedLines)
                    snapObjectTargets = if (isSnapEnabled) (nonSelectedHeadings.map { RectF(it.boundingBox) } + nonSelectedTexts.map { RectF(it.boundingBox) } + nonSelectedLines.map { RectF(it.boundingBox) }) else emptyList()
                    return true
                }
                // Normal lasso: clear any existing selection so the user sees immediate feedback.
                lassoGestureHadSelection = lassoSelectionBox != null
                lassoSelectionBox = null
                lassoOverlayPath  = null
                invalidate()
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
                            stroke.copy(points = stroke.points.map { pt ->
                                PointF(pt.x + dragDx, pt.y + dragDy)
                            })
                        }
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
                            )
                        }
                        val movedTextObjects = dragOriginalTextObjects.map { t ->
                            TextRender(t.id, RectF(
                                t.boundingBox.left + dragDx, t.boundingBox.top + dragDy,
                                t.boundingBox.right + dragDx, t.boundingBox.bottom + dragDy,
                            ), t.text)
                        }
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
                        val movedById = movedStrokes.associateBy { it.id }
                        val updated = strokes.map { movedById[it.id] ?: it }
                        strokes.clear(); strokes.addAll(updated)
                        val headingById = movedHeadings.associateBy { it.id }
                        headings = headings.map { headingById[it.id] ?: it }
                        val textById = movedTextObjects.associateBy { it.id }
                        textObjects = textObjects.map { textById[it.id] ?: it }
                        val lineById = movedLineObjects.associateBy { it.id }
                        lineObjects = lineObjects.map { lineById[it.id] ?: it }
                        lassoSelectionBox = lassoSelectionBox?.let { b ->
                            RectF(b.left + dragDx, b.top + dragDy, b.right + dragDx, b.bottom + dragDy)
                        }
                        val origStrokes = dragOriginalStrokes
                        val origHeadings = dragOriginalHeadings
                        val origTextObjects = dragOriginalTextObjects
                        val origLineObjects = dragOriginalLineObjects
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        redrawCanvas()
                        onStrokesMoved?.invoke(origStrokes, movedStrokes, origHeadings, movedHeadings, origTextObjects, movedTextObjects, origLineObjects, movedLineObjects)
                    } else {
                        // Below threshold — treat as a tap inside the selection box.
                        val tapX = event.x; val tapY = event.y
                        dragBackingBitmap?.recycle(); dragBackingBitmap = null
                        isDragMoveActive = false; dragThresholdMet = false
                        dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList(); snapObjectTargets = emptyList()
                        dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
                        dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
                        onLassoTap?.invoke(tapX, tapY)
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
            canvas.drawColor(Color.WHITE)
            dragBackingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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
            for (textObj in dragOriginalTextObjects) {
                drawTextObject(canvas, textObj, width)
            }
            for (lineObj in dragOriginalLineObjects) {
                drawLineObject(canvas, lineObj)
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
            hitHeadings.forEach { onHeadingErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Text-object hit-test: erase entire text object if eraser AABB intersects its box.
        val hitTexts = textObjects.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitTexts.isNotEmpty()) {
            val hitIds = hitTexts.mapTo(HashSet()) { it.id }
            textObjects = textObjects.filter { it.id !in hitIds }
            hitTexts.forEach { onTextErased?.invoke(it) }
            throttledEraseRedraw()
        }

        // Line-object hit-test: erase entire line if eraser AABB intersects its box.
        val hitLines = lineObjects.filter { RectF.intersects(eBounds, it.boundingBox) }
        if (hitLines.isNotEmpty()) {
            val hitIds = hitLines.mapTo(HashSet()) { it.id }
            lineObjects = lineObjects.filter { it.id !in hitIds }
            hitLines.forEach { onLineErased?.invoke(it) }
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

    /**
     * Render a type="line" object onto [canvas].
     * Line style (solid/dashed/dotted) is applied via PathEffect on a transient Paint.
     */
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
            com.notesprout.android.data.LineStyle.SOLID -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            com.notesprout.android.data.LineStyle.DASHED -> {
                paint.style = Paint.Style.STROKE
                paint.pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
                canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, paint)
            }
            com.notesprout.android.data.LineStyle.DOTTED -> {
                paint.style = Paint.Style.FILL
                val spacing = lineObj.dotSpacingPx.takeIf { it > 0f } ?: (sw * 4f)
                val r = sw / 2f
                when (lineObj.orientation) {
                    com.notesprout.android.data.LineOrientation.HORIZONTAL -> {
                        var x = lineObj.startX
                        while (x <= lineObj.endX) { canvas.drawCircle(x, lineObj.startY, r, paint); x += spacing }
                    }
                    com.notesprout.android.data.LineOrientation.VERTICAL -> {
                        var y = lineObj.startY
                        while (y <= lineObj.endY) { canvas.drawCircle(lineObj.startX, y, r, paint); y += spacing }
                    }
                }
            }
        }
    }

    /**
     * Redraws the render bitmap from scratch: white base → template → all current strokes.
     * Call whenever strokes are added/removed or the template changes.
     */
    private fun drawSnapGuides(canvas: Canvas) {
        if (activeSnapGuides.isEmpty()) return
        for (guide in activeSnapGuides) {
            when (guide) {
                is SnapGuide.Vertical   -> canvas.drawLine(guide.x, 0f, guide.x, height.toFloat(), snapGuidePaint)
                is SnapGuide.Horizontal -> canvas.drawLine(0f, guide.y, width.toFloat(), guide.y, snapGuidePaint)
            }
        }
    }

    private fun redrawCanvas() {
        val canvas = renderCanvas ?: return
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
        for (textObj in textObjects) {
            drawTextObject(canvas, textObj, width)
        }
        for (lineObj in lineObjects) {
            drawLineObject(canvas, lineObj)
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

    // ── Gesture detection at pen lift (smart-lasso → scribble-to-erase → normal) ──────────

    /**
     * Called after [commitActiveStroke] on every non-eraser pen lift. Runs the detection
     * gate chain in priority order on a single background thread:
     *   Gate 1 — Smart lasso: fast closed circle enclosing ≥1 object → enter lasso selection.
     *   Gate 2 — Scribble-to-erase: dense back-and-forth crossing ≥1 object → erase.
     *   Default — Normal stroke: fire [onPenLifted] so the activity saves the stroke to DB.
     */
    private fun checkAndDispatchGesture(durationMs: Long) {
        val lastStroke = strokes.lastOrNull()
        if (lastStroke == null) { onPenLifted?.invoke(); return }

        val points    = lastStroke.points
        val density   = resources.displayMetrics.density
        val strokeId  = lastStroke.id

        val isSmartLasso = isSmartLassoCandidate(points, durationMs, density)
        val isScribble   = !isSmartLasso && isScribbleCandidate(points)
        if (!isSmartLasso && !isScribble) { onPenLifted?.invoke(); return }

        val strokeSnapshot  = strokes.filter { it.id != strokeId }.toList()
        val headingSnapshot = headings.toList()
        val textSnapshot    = textObjects.toList()
        val lineSnapshot    = lineObjects.toList()

        Thread {
            // ── Gate 1: Smart lasso ────────────────────────────────────────────────
            if (isSmartLasso) {
                val path = Path().also { p ->
                    p.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
                    p.lineTo(points[0].x, points[0].y)
                    p.close()
                }
                val hitIds = runLassoHitTest(path, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot)
                if (hitIds.isNotEmpty()) {
                    val hitSet      = hitIds.toSet()
                    val unionBounds = RectF()
                    for (s in strokeSnapshot) { if (s.id in hitSet) unionBounds.union(s.boundingBox) }
                    for (h in headingSnapshot) { if (h.id in hitSet) unionBounds.union(h.boundingBox) }
                    for (t in textSnapshot)    { if (t.id in hitSet) unionBounds.union(t.boundingBox) }
                    for (l in lineSnapshot)    { if (l.id in hitSet) unionBounds.union(l.boundingBox) }
                    post {
                        strokes.removeAll { it.id == strokeId }
                        onSmartLassoComplete?.invoke(hitIds, unionBounds)
                    }
                    return@Thread
                }
                // Smart-lasso geometry passed but no objects enclosed — fall through to
                // scribble check (a fast circle over empty space is an unusual but valid stroke).
                if (!isScribbleCandidate(points)) {
                    post { onPenLifted?.invoke() }
                    return@Thread
                }
            }

            // ── Gate 2: Scribble-to-erase ──────────────────────────────────────────
            val hitIds = scribbleHitTest(points, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot, density)
            post {
                if (hitIds.isEmpty()) {
                    onPenLifted?.invoke()
                } else {
                    strokes.removeAll { it.id == strokeId }
                    val hitSet         = hitIds.toSet()
                    val erasedHeadings = headingSnapshot.filter { it.id in hitSet }
                    val erasedTexts    = textSnapshot.filter { it.id in hitSet }
                    val erasedLines    = lineSnapshot.filter { it.id in hitSet }
                    onScribbleEraseComplete?.invoke(hitIds, erasedHeadings, erasedTexts, erasedLines)
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

    private fun isScribbleCandidate(points: List<PointF>): Boolean {
        if (points.size < 4) return false
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
        if (diagonal < 10f) return false
        if (pathLength / diagonal < SCRIBBLE_DENSITY_RATIO) return false
        val filtered = mutableListOf(points[0])
        for (p in points) {
            val last = filtered.last()
            val dx = p.x - last.x; val dy = p.y - last.y
            if (dx * dx + dy * dy >= 4f) filtered.add(p)
        }
        if (filtered.size < 3) return false
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

    private fun scribbleHitTest(
        scribblePoints: List<PointF>,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        lineObjects: List<LineRender>,
        density: Float,
    ): List<String> {
        if (scribblePoints.size < 2) return emptyList()
        val touchRadiusPx = SCRIBBLE_STROKE_TOUCH_RADIUS_DP * density
        val touchRadiusSq = touchRadiusPx * touchRadiusPx
        val penetrationPx = SCRIBBLE_BBOX_PENETRATION_DP * density
        var sMinX = scribblePoints[0].x; var sMinY = scribblePoints[0].y
        var sMaxX = sMinX;               var sMaxY = sMinY
        for (sp in scribblePoints) {
            if (sp.x < sMinX) sMinX = sp.x else if (sp.x > sMaxX) sMaxX = sp.x
            if (sp.y < sMinY) sMinY = sp.y else if (sp.y > sMaxY) sMaxY = sp.y
        }
        val scribbleBounds = RectF(
            sMinX - touchRadiusPx, sMinY - touchRadiusPx,
            sMaxX + touchRadiusPx, sMaxY + touchRadiusPx,
        )
        val rawBounds = RectF(sMinX, sMinY, sMaxX, sMaxY)
        val hitIds = mutableListOf<String>()
        for (stroke in strokes) {
            if (!RectF.intersects(scribbleBounds, stroke.boundingBox)) continue
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
        for (heading in headings) {
            if (!RectF.intersects(rawBounds, heading.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, heading.boundingBox) >= penetrationPx) {
                hitIds.add(heading.id)
            }
        }
        for (textObj in textObjects) {
            if (!RectF.intersects(rawBounds, textObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, textObj.boundingBox) >= penetrationPx) {
                hitIds.add(textObj.id)
            }
        }
        for (lineObj in lineObjects) {
            if (!RectF.intersects(rawBounds, lineObj.boundingBox)) continue
            if (scribblePathPenetration(scribblePoints, lineObj.boundingBox) >= penetrationPx) {
                hitIds.add(lineObj.id)
            }
        }
        return hitIds
    }

    private fun scribblePathPenetration(points: List<PointF>, box: RectF): Float {
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

    // ── NotebookView interface ────────────────────────────────────────────────

    override fun asView(): View = this
    // No-op: the toolbar overlay consumes its own touches; the generic engine only sees stylus.
    override fun setToolbarExclusion(rect: Rect?) {}
    override fun enableDrawing() {}
    override fun disableDrawing() {}

    override fun setTextPlacementMode(active: Boolean) {
        isTextPlacementMode = active
    }

    override fun setDragMoveMode(enabled: Boolean) {
        if (!enabled && isDragMoveActive) {
            dragBackingBitmap?.recycle(); dragBackingBitmap = null
            isDragMoveActive = false; dragThresholdMet = false
            dragDx = 0f; dragDy = 0f; activeSnapGuides = emptyList()
            dragOriginalStrokes = emptyList(); dragOriginalHeadings = emptyList()
            dragOriginalTextObjects = emptyList(); dragOriginalLineObjects = emptyList()
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
                // Classify by gesture extent, not net displacement, so a small circular erase
                // gesture (returns near its origin) is not mistaken for a tap.
                val gestureBounds = RectF()
                path.computeBounds(gestureBounds, true)
                if (gestureBounds.width() >= tapThresholdPx || gestureBounds.height() >= tapThresholdPx) {
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
        val strokeSnapshot  = strokes.toList()
        val headingSnapshot = headings.toList()
        val textSnapshot    = textObjects.toList()
        val lineSnapshot    = lineObjects.toList()
        Thread {
            val hitIds = runLassoHitTest(drawnPath, strokeSnapshot, headingSnapshot, textSnapshot, lineSnapshot)
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

    private fun runLassoHitTest(
        path: Path,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke> = emptyList(),
        textObjects: List<TextRender> = emptyList(),
        lineObjects: List<LineRender> = emptyList(),
    ): List<String> {
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

    override fun eraseAll() {
        activePoints.clear()
        strokes.clear()
        headings = emptyList()
        textObjects = emptyList()
        lineObjects = emptyList()
        // Clear to white then re-apply template so the template persists after erase.
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

    override fun loadTextObjects(textObjects: List<TextRender>) {
        this.textObjects = textObjects
    }

    override fun getTextObjects(): List<TextRender> = textObjects

    override fun compositeTextObjects(bitmap: Bitmap) {
        if (textObjects.isEmpty()) return
        val canvas = Canvas(bitmap)
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
        val canvas = Canvas(bitmap)
        for (lineObj in lineObjects) {
            drawLineObject(canvas, lineObj)
        }
    }

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
        textObjects: List<TextRender>?,
        lineObjects: List<LineRender>?,
    ): Bitmap? {
        val w = width; val h = height
        if (w == 0 || h == 0) return null
        // null = fall back to stored field (undo/redo paths); non-null = explicit list (page load)
        val effectiveTextObjects = textObjects ?: this.textObjects
        val effectiveLineObjects = lineObjects ?: this.lineObjects
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
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
        for (textObj in effectiveTextObjects) {
            drawTextObject(canvas, textObj, w)
        }
        for (lineObj in effectiveLineObjects) {
            drawLineObject(canvas, lineObj)
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
     * boundary to OnyxNotebookView.onWindowFocusChanged(false).
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
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty()) return null
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
        for (textObj in textObjects) {
            drawTextObject(snapshotCanvas, textObj, w)
        }
        for (lineObj in lineObjects) {
            drawLineObject(snapshotCanvas, lineObj)
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
