package com.notesprout.android

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender
import com.notesprout.android.databinding.ActivityStickyNoteEditorBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.ToolPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory sticky note editor — opened by [NotebookActivity] when inserting or tapping a sticky
 * note icon. Content is never written to the DB here; it travels via [StickyNoteEditorTransfer].
 *
 * On [onPause] the current in-memory content is written to [StickyNoteEditorTransfer.output];
 * [NotebookActivity]'s editorLauncher callback reads and persists it, then pushes
 * [com.notesprout.android.history.UndoRedoAction.StickyNoteContentEdited] if content changed.
 */
class StickyNoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickyNoteEditorBinding
    private lateinit var drawingView: NotebookView

    private var contentWidth = 0f
    private var contentHeight = 0f

    private var isLassoMode = false
    private var isSmartLassoSession = false
    private val selectedObjectIds = mutableSetOf<String>()
    private var isEraserActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickyNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (resources.getBoolean(R.bool.is_large_screen)) {
            val dm = resources.displayMetrics
            val lp = binding.stickyNoteEditorWindow.layoutParams as FrameLayout.LayoutParams
            lp.width   = (dm.widthPixels  * 0.75f).toInt()
            lp.height  = (dm.heightPixels * 0.75f).toInt()
            lp.gravity = Gravity.CENTER
            binding.stickyNoteEditorWindow.layoutParams = lp
        }

        binding.btnStickyNoteClose.setOnClickListener { finish() }

        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        binding.floatingSelectionToolbar.bringToFront()

        wireDrawingCallbacks()
        wireToolButtons()
        updateLassoButtonIcon()

        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnStickyPen.isSelected    = false
                binding.btnStickyEraser.isSelected = true
            }
            else -> {
                binding.btnStickyPen.isSelected    = true
                binding.btnStickyEraser.isSelected = false
            }
        }

        binding.btnLassoCopy.setOnClickListener { performLassoCopy() }
        binding.btnLassoCut.setOnClickListener  { performLassoCut() }
        binding.btnLassoDelete.setOnClickListener { performLassoDelete() }

        val input = StickyNoteEditorTransfer.input
        if (binding.drawingContainer.width > 0) {
            if (input != null) loadContent(input) else recordCanvasSize()
        } else {
            binding.drawingContainer.doOnLayout {
                if (input != null) loadContent(input) else recordCanvasSize()
            }
        }
    }

    // ── Content load ──────────────────────────────────────────────────────────

    private fun loadContent(input: StickyNoteEditorTransfer.Content) {
        val canvasW = binding.drawingContainer.width.toFloat()
        val canvasH = binding.drawingContainer.height.toFloat()
        contentWidth  = if (input.contentWidth  > 0f) input.contentWidth  else canvasW
        contentHeight = if (input.contentHeight > 0f) input.contentHeight else canvasH

        lifecycleScope.launch {
            drawingView.loadHeadings(input.headings)
            drawingView.loadTextObjects(input.textObjects)
            drawingView.loadLineObjects(input.lines)
            drawingView.loadLinks(emptyList())
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(
                    input.strokes, null, input.headings, input.textObjects, input.lines,
                )
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(input.strokes, bitmap, null)
            } else {
                drawingView.setTemplate(null)
                drawingView.loadStrokes(input.strokes)
            }
        }
    }

    private fun recordCanvasSize() {
        contentWidth  = binding.drawingContainer.width.toFloat()
        contentHeight = binding.drawingContainer.height.toFloat()
    }

    private fun writeTransferOutput() {
        val w = if (contentWidth  > 0f) contentWidth  else binding.drawingContainer.width.toFloat()
        val h = if (contentHeight > 0f) contentHeight else binding.drawingContainer.height.toFloat()
        StickyNoteEditorTransfer.output = StickyNoteEditorTransfer.Content(
            strokes      = drawingView.getStrokes(),
            headings     = drawingView.getHeadings(),
            textObjects  = drawingView.getTextObjects(),
            lines        = drawingView.getLineObjects(),
            contentWidth  = w,
            contentHeight = h,
        )
    }

    // ── Drawing callbacks ─────────────────────────────────────────────────────

    private fun wireDrawingCallbacks() {
        // Pen strokes accumulate in the view's in-memory list — no DB save needed.
        drawingView.onPenLifted = { }

        // Hardware / scribble eraser: view already removed the item from its internal lists;
        // just rebuild the render bitmap so the canvas reflects the change.
        drawingView.onStrokeErased = { lifecycleScope.launch { rebuildBitmap() } }
        drawingView.onHeadingErased = { lifecycleScope.launch { rebuildBitmap() } }
        drawingView.onTextErased = { lifecycleScope.launch { rebuildBitmap() } }
        drawingView.onLineErased = { lifecycleScope.launch { rebuildBitmap() } }
        drawingView.onLinkErased = { lifecycleScope.launch { rebuildBitmap() } }

        // No snapshot persistence in the editor.
        drawingView.onSnapshotReady = null

        // Lasso eraser: the view reports which IDs it erased; we filter our in-memory lists.
        drawingView.onLassoEraseComplete = { erasedIds ->
            if (erasedIds.isNotEmpty()) {
                val erasedSet      = erasedIds.toSet()
                val erasedHIds     = drawingView.getHeadings().filter { it.id in erasedSet }.mapTo(mutableSetOf()) { it.id }
                val erasedTIds     = drawingView.getTextObjects().filter { it.id in erasedSet }.mapTo(mutableSetOf()) { it.id }
                val erasedLIds     = drawingView.getLineObjects().filter { it.id in erasedSet }.mapTo(mutableSetOf()) { it.id }
                val updStrokes     = drawingView.getStrokes().filter { it.id !in erasedSet }
                val updHeadings    = drawingView.getHeadings().filter { it.id !in erasedHIds }
                val updTexts       = drawingView.getTextObjects().filter { it.id !in erasedTIds }
                val updLines       = drawingView.getLineObjects().filter { it.id !in erasedLIds }
                drawingView.setStrokeListSilently(updStrokes)
                drawingView.loadHeadings(updHeadings)
                drawingView.loadTextObjects(updTexts)
                drawingView.loadLineObjects(updLines)
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines)
                    }
                    if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
                    else drawingView.loadStrokes(updStrokes)
                }
            }
        }

        // Scribble erase: batch removal, same pattern as lasso eraser (no undo in editor).
        drawingView.onScribbleEraseComplete = { erasedObjectIds, erasedHeadings, erasedTextObjects, erasedLineObjects, erasedLinks, _ ->
            if (erasedObjectIds.isNotEmpty()) {
                val erasedHIds  = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                val erasedTIds  = erasedTextObjects.mapTo(mutableSetOf()) { it.id }
                val erasedLIds  = erasedLineObjects.mapTo(mutableSetOf()) { it.id }
                val erasedSet   = erasedObjectIds.toSet()
                val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
                val updHeadings = drawingView.getHeadings().filter { it.id !in erasedHIds }
                val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedTIds }
                val updLines    = drawingView.getLineObjects().filter { it.id !in erasedLIds }
                drawingView.setStrokeListSilently(updStrokes)
                drawingView.loadHeadings(updHeadings)
                drawingView.loadTextObjects(updTexts)
                drawingView.loadLineObjects(updLines)
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines)
                    }
                    if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
                    else drawingView.loadStrokes(updStrokes)
                }
            }
        }

        // Lasso callbacks.
        drawingView.onLassoComplete = { drawnPath, startPoint ->
            drawnPath.lineTo(startPoint.x, startPoint.y)
            drawnPath.close()
            selectedObjectIds.clear()
            val strokeSnapshot  = drawingView.getStrokes()
            val headingSnapshot = drawingView.getHeadings()
            val textSnapshot    = drawingView.getTextObjects()
            val lineSnapshot    = drawingView.getLineObjects()
            lifecycleScope.launch(Dispatchers.Default) {
                val lassoBounds = RectF()
                drawnPath.computeBounds(lassoBounds, true)
                val density = resources.displayMetrics.density
                val minPx = 10f * density
                if (lassoBounds.width() < minPx && lassoBounds.height() < minPx) return@launch
                val clipRect = Rect(
                    (lassoBounds.left - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.top  - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.right + 1f).toInt(),
                    (lassoBounds.bottom + 1f).toInt(),
                )
                val lassoRegion = Region()
                lassoRegion.setPath(drawnPath, Region(clipRect))
                val hitIds      = mutableSetOf<String>()
                val unionBounds = RectF()
                for (stroke in strokeSnapshot) {
                    if (!RectF.intersects(lassoBounds, stroke.boundingBox)) continue
                    for (pt in stroke.points) {
                        if (lassoRegion.contains(pt.x.toInt(), pt.y.toInt())) {
                            hitIds.add(stroke.id); unionBounds.union(stroke.boundingBox); break
                        }
                    }
                }
                for (heading in headingSnapshot) {
                    if (!RectF.intersects(lassoBounds, heading.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, heading.boundingBox)) {
                        hitIds.add(heading.id); unionBounds.union(heading.boundingBox)
                    }
                }
                for (textObj in textSnapshot) {
                    if (!RectF.intersects(lassoBounds, textObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, textObj.boundingBox)) {
                        hitIds.add(textObj.id); unionBounds.union(textObj.boundingBox)
                    }
                }
                for (lineObj in lineSnapshot) {
                    if (!RectF.intersects(lassoBounds, lineObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, lineObj.boundingBox)) {
                        hitIds.add(lineObj.id); unionBounds.union(lineObj.boundingBox)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (hitIds.isEmpty()) return@withContext
                    selectedObjectIds.clear()
                    selectedObjectIds.addAll(hitIds)
                    drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                    val pad = 8f * resources.displayMetrics.density
                    unionBounds.inset(-pad, -pad)
                    drawingView.setLassoOverlay(null, unionBounds)
                    updateFloatingSelectionToolbar(unionBounds)
                }
            }
        }

        drawingView.onLassoTapToDismiss = {
            val hadActiveSelection = selectedObjectIds.isNotEmpty()
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession && hadActiveSelection) {
                exitLassoMode()
                drawingView.enableDrawing()
            }
        }

        drawingView.onLassoTap = { tapX, tapY ->
            if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) {
                performLassoPaste(tapX, tapY)
            }
        }

        drawingView.onDragStarted = { hideFloatingSelectionToolbar() }

        drawingView.onStrokesMoved = { _, movedStrokes, _, movedHeadings, _, movedTextObjects, _, movedLineObjects, _, movedLinks, _, _ ->
            // No DB persistence — just reposition the floating toolbar.
            val newBox = RectF()
            movedStrokes.forEach { newBox.union(it.boundingBox) }
            movedHeadings.forEach { newBox.union(it.boundingBox) }
            movedTextObjects.forEach { newBox.union(it.boundingBox) }
            movedLineObjects.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            updateFloatingSelectionToolbar(newBox)
        }

        drawingView.onLassoSelectionCleared = { hideFloatingSelectionToolbar() }

        drawingView.onSmartLassoComplete = { hitIds, unionBounds ->
            if (!isLassoMode) {
                enterLassoMode()
                isSmartLassoSession = true
            }
            selectedObjectIds.clear()
            selectedObjectIds.addAll(hitIds)
            drawingView.lassoSelectedIds = selectedObjectIds.toSet()
            val pad          = 8f * resources.displayMetrics.density
            val paddedBounds = RectF(unionBounds).also { it.inset(-pad, -pad) }
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(currentStrokes, null, currentHeadings, currentTexts, currentLines)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, null)
                }
                drawingView.setLassoOverlay(null, paddedBounds)
                updateFloatingSelectionToolbar(paddedBounds)
            }
        }
    }

    // ── Tool buttons ──────────────────────────────────────────────────────────

    private fun wireToolButtons() {
        binding.btnStickyPen.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            isEraserActive = false
            drawingView.setEraserMode(false)
            binding.btnStickyPen.isSelected    = true
            binding.btnStickyEraser.isSelected = false
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, ActiveTool.PEN)
        }
        binding.btnStickyEraser.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnStickyEraser.isSelected = isEraserActive
            binding.btnStickyPen.isSelected    = !isEraserActive
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }
        binding.btnStickyLasso.setOnClickListener {
            if (!isLassoMode) enterLassoMode() else exitLassoMode()
            drawingView.releaseRender()
        }
    }

    // ── Lasso mode helpers ────────────────────────────────────────────────────

    private fun enterLassoMode() {
        isLassoMode = true
        if (isEraserActive) { isEraserActive = false; drawingView.setEraserMode(false) }
        drawingView.setLassoMode(true)
        binding.btnStickyLasso.isSelected  = true
        binding.btnStickyPen.isSelected    = false
        binding.btnStickyEraser.isSelected = false
    }

    private fun exitLassoMode() {
        isLassoMode = false
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.setLassoMode(false)
        binding.btnStickyLasso.isSelected  = false
        binding.btnStickyPen.isSelected    = !isEraserActive
        binding.btnStickyEraser.isSelected = isEraserActive
        hideFloatingSelectionToolbar()
    }

    // ── Lasso operations ──────────────────────────────────────────────────────

    private fun performLassoCopy() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes     = drawingView.getStrokes().filter { it.id in ids }
        val headings    = drawingView.getHeadings().filter { it.id in ids }
        val textObjects = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects = drawingView.getLineObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }

        NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                    recognizedText = h.recognizedText, level = h.level)
            },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = emptyList(),
        )
        updateLassoButtonIcon()
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
    }

    private fun performLassoCut() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes     = drawingView.getStrokes().filter { it.id in ids }
        val headings    = drawingView.getHeadings().filter { it.id in ids }
        val textObjects = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects = drawingView.getLineObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }

        NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                    recognizedText = h.recognizedText, level = h.level)
            },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = emptyList(),
        )
        updateLassoButtonIcon()

        val allIds  = strokes.map { it.id } + headings.map { it.id } +
                      textObjects.map { it.id } + lineObjects.map { it.id }
        val erasedSet = allIds.toSet()
        val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
        val updHeadings = drawingView.getHeadings().filter { it.id !in erasedSet }
        val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedSet }
        val updLines    = drawingView.getLineObjects().filter { it.id !in erasedSet }
        drawingView.setStrokeListSilently(updStrokes)
        drawingView.loadHeadings(updHeadings)
        drawingView.loadTextObjects(updTexts)
        drawingView.loadLineObjects(updLines)
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
            else drawingView.loadStrokes(updStrokes)
        }
    }

    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val allIds = (drawingView.getStrokes().filter { it.id in ids }.map { it.id } +
                      drawingView.getHeadings().filter { it.id in ids }.map { it.id } +
                      drawingView.getTextObjects().filter { it.id in ids }.map { it.id } +
                      drawingView.getLineObjects().filter { it.id in ids }.map { it.id })
        if (allIds.isEmpty()) return
        val erasedSet   = allIds.toSet()
        val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
        val updHeadings = drawingView.getHeadings().filter { it.id !in erasedSet }
        val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedSet }
        val updLines    = drawingView.getLineObjects().filter { it.id !in erasedSet }
        drawingView.setStrokeListSilently(updStrokes)
        drawingView.loadHeadings(updHeadings)
        drawingView.loadTextObjects(updTexts)
        drawingView.loadLineObjects(updLines)
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
            else drawingView.loadStrokes(updStrokes)
        }
    }

    private fun performLassoPaste(tapX: Float, tapY: Float) {
        val clip    = NotesproutClipboard.content ?: return
        val density = resources.displayMetrics.density

        val dx = tapX - clip.boundingBox.centerX()
        val dy = tapY - clip.boundingBox.centerY()

        val newStrokes = clip.strokes.map { s ->
            s.copy(id = java.util.UUID.randomUUID().toString(),
                   points = s.points.map { PointF(it.x + dx, it.y + dy) })
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                    h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s ->
                    s.copy(id = java.util.UUID.randomUUID().toString(),
                           points = s.points.map { PointF(it.x + dx, it.y + dy) })
                },
                recognizedText = h.recognizedText,
                level = h.level,
            )
        }
        val newTextObjects = clip.textObjects.map { t ->
            TextRender(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(t.boundingBox.left + dx, t.boundingBox.top + dy,
                                    t.boundingBox.right + dx, t.boundingBox.bottom + dy),
                text = t.text,
                strokes = t.strokes,
            )
        }
        val newLineObjects = clip.lineObjects.map { l ->
            l.copy(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy,
                                    l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                startX = l.startX + dx, startY = l.startY + dy,
                endX   = l.endX   + dx, endY   = l.endY   + dy,
            )
        }

        lifecycleScope.launch {
            val allStrokes  = drawingView.getStrokes()  + newStrokes
            val allHeadings = drawingView.getHeadings() + newHeadings
            val allTexts    = drawingView.getTextObjects() + newTextObjects
            val allLines    = drawingView.getLineObjects() + newLineObjects
            drawingView.loadHeadings(allHeadings)
            drawingView.loadTextObjects(allTexts)
            drawingView.loadLineObjects(allLines)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, null, allHeadings, allTexts, allLines)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(allStrokes, bitmap, null)
            else drawingView.loadStrokes(allStrokes)

            val newBox = RectF()
            newStrokes.forEach     { newBox.union(it.boundingBox) }
            newHeadings.forEach    { newBox.union(it.boundingBox) }
            newTextObjects.forEach { newBox.union(it.boundingBox) }
            newLineObjects.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            selectedObjectIds.addAll(newTextObjects.map { it.id })
            selectedObjectIds.addAll(newLineObjects.map { it.id })
            isSmartLassoSession = false
            if (!isLassoMode) enterLassoMode()
            binding.btnStickyLasso.isSelected  = true
            binding.btnStickyPen.isSelected    = false
            binding.btnStickyEraser.isSelected = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
        }
    }

    // ── Bitmap rebuild ────────────────────────────────────────────────────────

    private suspend fun rebuildBitmap() {
        val strokes  = drawingView.getStrokes()
        val headings = drawingView.getHeadings()
        val texts    = drawingView.getTextObjects()
        val lines    = drawingView.getLineObjects()
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, null, headings, texts, lines)
        }
        if (bitmap != null) drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
        else drawingView.loadStrokes(strokes)
    }

    // ── Lasso button icon ─────────────────────────────────────────────────────

    private fun updateLassoButtonIcon() {
        binding.btnStickyLasso.setImageResource(
            if (NotesproutClipboard.hasContent()) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
        )
    }

    // ── Floating selection toolbar ────────────────────────────────────────────

    private fun updateFloatingSelectionToolbar(selectionBox: RectF) {
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post { positionFloatingToolbar(selectionBox) }
    }

    private fun hideFloatingSelectionToolbar() {
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    private fun positionFloatingToolbar(selectionBox: RectF) {
        val view       = binding.floatingSelectionToolbar
        val viewW      = view.measuredWidth.toFloat()
        val viewH      = view.measuredHeight.toFloat()
        val containerW = binding.drawingContainer.width.toFloat()
        val containerH = binding.drawingContainer.height.toFloat()
        val dpGap      = 8f * resources.displayMetrics.density

        var x = selectionBox.centerX() - viewW / 2f
        var y = selectionBox.bottom + dpGap
        if (y + viewH > containerH) y = selectionBox.top - dpGap - viewH
        y = y.coerceIn(0f, (containerH - viewH).coerceAtLeast(0f))
        x = x.coerceIn(0f, (containerW - viewW).coerceAtLeast(0f))
        view.x = x; view.y = y
    }

    // ── Touch dispatch ────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            && event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isTouchInView(event, binding.chromeBar) ||
                isTouchInView(event, binding.editorToolbar) ||
                (binding.floatingSelectionToolbar.visibility == View.VISIBLE &&
                 isTouchInView(event, binding.floatingSelectionToolbar))) {
                drawingView.releaseRender()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchInView(event: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX; val y = event.rawY
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        drawingView.enableDrawing()
        updateLassoButtonIcon()
    }

    override fun onPause() {
        super.onPause()
        writeTransferOutput()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
    }
}
