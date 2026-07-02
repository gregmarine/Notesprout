package com.notesprout.android

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.ShapeObject
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.ShapeType
import com.notesprout.android.data.TextRender
import com.notesprout.android.databinding.ActivityStickyNoteEditorBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.ShapeRecognizer
import com.notesprout.android.notebook.ToolPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var isShapeTransformMode = false

    private var shapeConvertJob: Job? = null
    private var shapeConvertStroke: com.notesprout.android.data.LiveStroke? = null
    private var shapeConvertResult: ShapeRecognizer.Result? = null

    // ── Undo/redo — in-memory full-content snapshot history ──────────────────────
    private data class EditorSnapshot(
        val strokes: List<LiveStroke>,
        val headings: List<HeadingStroke>,
        val texts: List<TextRender>,
        val lines: List<LineRender>,
        val shapes: List<ShapeRender>,
    )
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private val historyCap = 50
    private var currentSnapshot = EditorSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val doubleTapSlopPx by lazy { ViewConfiguration.get(this).scaledDoubleTapSlop }

    // ── Multi-finger double-tap: 2-finger = undo, 3-finger = redo (mirrors NotebookActivity) ──
    private var mfTapPeakCount = 0
    private var mfTapArmed = false
    private var mfTapMoved = false
    private var mfTapDownTime = 0L
    private var mfTapCentroidStartX = 0f
    private var mfTapCentroidStartY = 0f
    private var twoFingerTapFirstTime = 0L
    private var twoFingerTapFirstX = 0f
    private var twoFingerTapFirstY = 0f
    private var threeFingerTapFirstTime = 0L
    private var threeFingerTapFirstX = 0f
    private var threeFingerTapFirstY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickyNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

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

        binding.btnStickyUndo.setOnClickListener { undo() }
        binding.btnStickyRedo.setOnClickListener { redo() }

        binding.btnLassoCopy.setOnClickListener { performLassoCopy() }
        binding.btnLassoCut.setOnClickListener  { performLassoCut() }
        binding.btnLassoDelete.setOnClickListener { performLassoDelete() }
        binding.btnConvertShape.setOnClickListener {
            val stroke = shapeConvertStroke ?: return@setOnClickListener
            val result = shapeConvertResult ?: return@setOnClickListener
            convertStrokeToShape(stroke, result)
        }

        binding.btnShapeAspectLock.setOnClickListener {
            val updated = drawingView.toggleShapeAspectLock() ?: return@setOnClickListener
            updateShapeTransformToggle(updated)
        }
        binding.btnShapeTransformDone.setOnClickListener {
            exitShapeTransformMode(clearSelection = false)
        }

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
            drawingView.loadShapeObjects(input.shapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(
                    input.strokes, null, input.headings, input.textObjects, input.lines,
                    shapeObjects = input.shapes,
                )
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(input.strokes, bitmap, null)
            } else {
                drawingView.setTemplate(null)
                drawingView.loadStrokes(input.strokes)
            }
            initHistory()
        }
    }

    private fun recordCanvasSize() {
        contentWidth  = binding.drawingContainer.width.toFloat()
        contentHeight = binding.drawingContainer.height.toFloat()
        initHistory()
    }

    // ── Undo / redo (in-memory full-content snapshot history) ────────────────────

    /** Deep-copy the current canvas content so later in-place mutations can't corrupt the snapshot. */
    private fun captureState(): EditorSnapshot = EditorSnapshot(
        strokes  = drawingView.getStrokes().map { it.copy(points = it.points.map { p -> PointF(p.x, p.y) }) },
        headings = drawingView.getHeadings().map { h ->
            h.copy(boundingBox = RectF(h.boundingBox),
                   strokes = h.strokes.map { s -> s.copy(points = s.points.map { p -> PointF(p.x, p.y) }) })
        },
        texts    = drawingView.getTextObjects().map { t ->
            t.copy(boundingBox = RectF(t.boundingBox),
                   strokes = t.strokes?.map { s -> s.copy(points = s.points.map { p -> PointF(p.x, p.y) }) })
        },
        lines    = drawingView.getLineObjects().map { it.copy(boundingBox = RectF(it.boundingBox)) },
        shapes   = drawingView.getShapeObjects().map { it.copy(boundingBox = RectF(it.boundingBox)) },
    )

    private fun initHistory() {
        currentSnapshot = captureState()
        undoStack.clear()
        redoStack.clear()
    }

    /** Capture post-change state, pushing the prior state onto the undo stack. */
    private fun pushHistory() {
        undoStack.addLast(currentSnapshot)
        while (undoStack.size > historyCap) undoStack.removeFirst()
        currentSnapshot = captureState()
        redoStack.clear()
    }

    private fun applySnapshot(snap: EditorSnapshot) {
        if (isLassoMode) exitLassoMode()
        drawingView.setStrokeListSilently(snap.strokes)
        drawingView.loadHeadings(snap.headings)
        drawingView.loadTextObjects(snap.texts)
        drawingView.loadLineObjects(snap.lines)
        drawingView.loadShapeObjects(snap.shapes)
        // Re-isolate currentSnapshot from the view's now-shared object references so a
        // later in-place move/resize can't corrupt the history entry.
        currentSnapshot = captureState()
        lifecycleScope.launch { rebuildBitmap() }
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(currentSnapshot)
        applySnapshot(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(currentSnapshot)
        applySnapshot(redoStack.removeLast())
    }

    private fun writeTransferOutput() {
        val w = if (contentWidth  > 0f) contentWidth  else binding.drawingContainer.width.toFloat()
        val h = if (contentHeight > 0f) contentHeight else binding.drawingContainer.height.toFloat()
        StickyNoteEditorTransfer.output = StickyNoteEditorTransfer.Content(
            strokes       = drawingView.getStrokes(),
            headings      = drawingView.getHeadings(),
            textObjects   = drawingView.getTextObjects(),
            lines         = drawingView.getLineObjects(),
            shapes        = drawingView.getShapeObjects(),
            contentWidth  = w,
            contentHeight = h,
        )
    }

    // ── Drawing callbacks ─────────────────────────────────────────────────────

    private fun wireDrawingCallbacks() {
        // Pen strokes accumulate in the view's in-memory list — no DB save needed.
        drawingView.onPenLifted = { pushHistory() }

        // Hardware / scribble eraser: view already removed the item from its internal lists;
        // just rebuild the render bitmap so the canvas reflects the change.
        drawingView.onStrokeErased = { lifecycleScope.launch { rebuildBitmap() }; pushHistory() }
        drawingView.onHeadingErased = { lifecycleScope.launch { rebuildBitmap() }; pushHistory() }
        drawingView.onTextErased = { lifecycleScope.launch { rebuildBitmap() }; pushHistory() }
        drawingView.onLineErased = { lifecycleScope.launch { rebuildBitmap() }; pushHistory() }
        drawingView.onLinkErased = { lifecycleScope.launch { rebuildBitmap() }; pushHistory() }

        // Shape hardware/scribble erased — remove from in-memory list and rebuild.
        drawingView.onShapeErased = { erasedShape ->
            val updatedShapes = drawingView.getShapeObjects().filter { it.id != erasedShape.id }
            drawingView.loadShapeObjects(updatedShapes)
            lifecycleScope.launch { rebuildBitmap() }
            pushHistory()
        }

        // Shape recognized from a held stroke — convert in-memory, no DB.
        drawingView.onShapeRecognized = { stroke, result ->
            convertStrokeToShape(stroke, result)
        }

        // Shape transform drag/resize completed — update in-memory list and bitmap.
        drawingView.onShapeTransformed = { _, after ->
            val updatedShapes = drawingView.getShapeObjects().map { if (it.id == after.id) after else it }
            drawingView.loadShapeObjects(updatedShapes)
            val strokes  = drawingView.getStrokes()
            val headings = drawingView.getHeadings()
            val texts    = drawingView.getTextObjects()
            val lines    = drawingView.getLineObjects()
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, shapeObjects = updatedShapes)
                }
                if (bitmap != null) drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
                else drawingView.loadStrokes(strokes)
                if (isShapeTransformMode && after.id in selectedObjectIds) {
                    val pad    = 8f * resources.displayMetrics.density
                    val selBox = RectF(after.boundingBox).apply { inset(-pad, -pad) }
                    drawingView.setLassoOverlay(null, selBox)
                    updateFloatingSelectionToolbar(selBox)
                }
                pushHistory()
            }
        }

        // Tap outside transform handle — exit transform but keep selection.
        drawingView.onShapeTransformTapOutside = {
            if (isShapeTransformMode) exitShapeTransformMode(clearSelection = false)
        }

        // Drag started in transform mode — hide toolbar while dragging.
        drawingView.onShapeTransformDragStarted = {
            hideFloatingSelectionToolbar()
        }

        // Shape moved during transform — reposition toolbar.
        drawingView.onShapeTransformMoved = { newBoundingBox ->
            val pad    = 8f * resources.displayMetrics.density
            val selBox = RectF(newBoundingBox).apply { inset(-pad, -pad) }
            updateFloatingSelectionToolbar(selBox)
        }

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
                val updShapes      = drawingView.getShapeObjects().filter { it.id !in erasedSet }
                drawingView.setStrokeListSilently(updStrokes)
                drawingView.loadHeadings(updHeadings)
                drawingView.loadTextObjects(updTexts)
                drawingView.loadLineObjects(updLines)
                drawingView.loadShapeObjects(updShapes)
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines, shapeObjects = updShapes)
                    }
                    if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
                    else drawingView.loadStrokes(updStrokes)
                    pushHistory()
                }
            }
        }

        // Scribble erase: batch removal.
        drawingView.onScribbleEraseComplete = { erasedObjectIds, erasedHeadings, erasedTextObjects, erasedLineObjects, erasedLinks, _, erasedShapes ->
            if (erasedObjectIds.isNotEmpty()) {
                val erasedHIds  = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                val erasedTIds  = erasedTextObjects.mapTo(mutableSetOf()) { it.id }
                val erasedLIds  = erasedLineObjects.mapTo(mutableSetOf()) { it.id }
                val erasedSIds  = erasedShapes.mapTo(mutableSetOf()) { it.id }
                val erasedSet   = erasedObjectIds.toSet()
                val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
                val updHeadings = drawingView.getHeadings().filter { it.id !in erasedHIds }
                val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedTIds }
                val updLines    = drawingView.getLineObjects().filter { it.id !in erasedLIds }
                val updShapes   = drawingView.getShapeObjects().filter { it.id !in erasedSIds }
                drawingView.setStrokeListSilently(updStrokes)
                drawingView.loadHeadings(updHeadings)
                drawingView.loadTextObjects(updTexts)
                drawingView.loadLineObjects(updLines)
                drawingView.loadShapeObjects(updShapes)
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines, shapeObjects = updShapes)
                    }
                    if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
                    else drawingView.loadStrokes(updStrokes)
                    pushHistory()
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
            val shapeSnapshot   = drawingView.getShapeObjects()
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
                for (shape in shapeSnapshot) {
                    if (!RectF.intersects(lassoBounds, shape.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, shape.boundingBox)) {
                        hitIds.add(shape.id); unionBounds.union(shape.boundingBox)
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
            if (isShapeTransformMode) {
                // handled by onShapeTransformTapOutside
            } else if (selectedObjectIds.size == 1) {
                val selId    = selectedObjectIds.first()
                val selShape = drawingView.getShapeObjects().find { it.id == selId }
                if (selShape != null && selShape.boundingBox.contains(tapX, tapY)) {
                    enterShapeTransformMode(selShape)
                }
            } else if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) {
                performLassoPaste(tapX, tapY)
            }
        }

        drawingView.onDragStarted = { hideFloatingSelectionToolbar() }

        drawingView.onStrokesMoved = { _, movedStrokes, _, movedHeadings, _, movedTextObjects, _, movedLineObjects, _, movedLinks, _, _, _, movedShapes ->
            val newBox = RectF()
            movedStrokes.forEach { newBox.union(it.boundingBox) }
            movedHeadings.forEach { newBox.union(it.boundingBox) }
            movedTextObjects.forEach { newBox.union(it.boundingBox) }
            movedLineObjects.forEach { newBox.union(it.boundingBox) }
            movedShapes.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            updateFloatingSelectionToolbar(newBox)
            pushHistory()
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
            val currentShapes   = drawingView.getShapeObjects()
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(currentStrokes, null, currentHeadings, currentTexts, currentLines, shapeObjects = currentShapes)
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
        val shapes      = drawingView.getShapeObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && shapes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        shapes.forEach { box.union(it.boundingBox) }

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
            shapeObjects = shapes.map { it.copy(boundingBox = RectF(it.boundingBox)) },
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
        val shapes      = drawingView.getShapeObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && shapes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        shapes.forEach { box.union(it.boundingBox) }

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
            shapeObjects = shapes.map { it.copy(boundingBox = RectF(it.boundingBox)) },
        )
        updateLassoButtonIcon()

        val allIds    = strokes.map { it.id } + headings.map { it.id } +
                        textObjects.map { it.id } + lineObjects.map { it.id } +
                        shapes.map { it.id }
        val erasedSet = allIds.toSet()
        val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
        val updHeadings = drawingView.getHeadings().filter { it.id !in erasedSet }
        val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedSet }
        val updLines    = drawingView.getLineObjects().filter { it.id !in erasedSet }
        val updShapes   = drawingView.getShapeObjects().filter { it.id !in erasedSet }
        drawingView.setStrokeListSilently(updStrokes)
        drawingView.loadHeadings(updHeadings)
        drawingView.loadTextObjects(updTexts)
        drawingView.loadLineObjects(updLines)
        drawingView.loadShapeObjects(updShapes)
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines, shapeObjects = updShapes)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
            else drawingView.loadStrokes(updStrokes)
            pushHistory()
        }
    }

    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val allIds = (drawingView.getStrokes().filter { it.id in ids }.map { it.id } +
                      drawingView.getHeadings().filter { it.id in ids }.map { it.id } +
                      drawingView.getTextObjects().filter { it.id in ids }.map { it.id } +
                      drawingView.getLineObjects().filter { it.id in ids }.map { it.id } +
                      drawingView.getShapeObjects().filter { it.id in ids }.map { it.id })
        if (allIds.isEmpty()) return
        val erasedSet   = allIds.toSet()
        val updStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
        val updHeadings = drawingView.getHeadings().filter { it.id !in erasedSet }
        val updTexts    = drawingView.getTextObjects().filter { it.id !in erasedSet }
        val updLines    = drawingView.getLineObjects().filter { it.id !in erasedSet }
        val updShapes   = drawingView.getShapeObjects().filter { it.id !in erasedSet }
        drawingView.setStrokeListSilently(updStrokes)
        drawingView.loadHeadings(updHeadings)
        drawingView.loadTextObjects(updTexts)
        drawingView.loadLineObjects(updLines)
        drawingView.loadShapeObjects(updShapes)
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updStrokes, null, updHeadings, updTexts, updLines, shapeObjects = updShapes)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(updStrokes, bitmap, null)
            else drawingView.loadStrokes(updStrokes)
            pushHistory()
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
        val newShapes = clip.shapeObjects.map { shape ->
            shape.copy(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(
                    shape.boundingBox.left  + dx, shape.boundingBox.top    + dy,
                    shape.boundingBox.right + dx, shape.boundingBox.bottom + dy,
                ),
                centerX = shape.centerX + dx,
                centerY = shape.centerY + dy,
            )
        }

        lifecycleScope.launch {
            val allStrokes  = drawingView.getStrokes()       + newStrokes
            val allHeadings = drawingView.getHeadings()      + newHeadings
            val allTexts    = drawingView.getTextObjects()   + newTextObjects
            val allLines    = drawingView.getLineObjects()   + newLineObjects
            val allShapes   = drawingView.getShapeObjects()  + newShapes
            drawingView.loadHeadings(allHeadings)
            drawingView.loadTextObjects(allTexts)
            drawingView.loadLineObjects(allLines)
            drawingView.loadShapeObjects(allShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, null, allHeadings, allTexts, allLines, shapeObjects = allShapes)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(allStrokes, bitmap, null)
            else drawingView.loadStrokes(allStrokes)

            val newBox = RectF()
            newStrokes.forEach     { newBox.union(it.boundingBox) }
            newHeadings.forEach    { newBox.union(it.boundingBox) }
            newTextObjects.forEach { newBox.union(it.boundingBox) }
            newLineObjects.forEach { newBox.union(it.boundingBox) }
            newShapes.forEach      { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            selectedObjectIds.addAll(newTextObjects.map { it.id })
            selectedObjectIds.addAll(newLineObjects.map { it.id })
            selectedObjectIds.addAll(newShapes.map { it.id })
            isSmartLassoSession = false
            if (!isLassoMode) enterLassoMode()
            binding.btnStickyLasso.isSelected  = true
            binding.btnStickyPen.isSelected    = false
            binding.btnStickyEraser.isSelected = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
            pushHistory()
        }
    }

    // ── Shape conversion (in-memory) ──────────────────────────────────────────

    private fun convertStrokeToShape(stroke: LiveStroke, result: ShapeRecognizer.Result) {
        val density = resources.displayMetrics.density
        val shapeId = java.util.UUID.randomUUID().toString()
        val shapeObj = ShapeObject(
            type          = result.type,
            centerX       = result.centerX,
            centerY       = result.centerY,
            width         = result.width,
            height        = result.height,
            rotationDeg   = result.rotationDeg,
            strokeWidthDp = stroke.strokeWidth / density,
            aspectLocked  = result.aspectLocked,
            pointCount    = result.pointCount,
        )
        val shapeRender    = ShapeRender.from(shapeId, shapeObj, density)
        val updatedStrokes = drawingView.getStrokes().filter { it.id != stroke.id }
        val updatedShapes  = drawingView.getShapeObjects() + shapeRender
        drawingView.setStrokeListSilently(updatedStrokes)
        drawingView.loadShapeObjects(updatedShapes)
        val currentHeadings = drawingView.getHeadings()
        val currentTexts    = drawingView.getTextObjects()
        val currentLines    = drawingView.getLineObjects()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, null, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, null)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            if (!isLassoMode) enterLassoMode()
            isSmartLassoSession = true
            val pad    = 8f * density
            val selBox = RectF(shapeRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(shapeId), selBox)
            selectedObjectIds.clear()
            selectedObjectIds.add(shapeId)
            updateFloatingSelectionToolbar(selBox)
            pushHistory()
        }
    }

    // ── Shape transform mode ──────────────────────────────────────────────────

    private fun enterShapeTransformMode(shape: ShapeRender) {
        isShapeTransformMode = true
        drawingView.setLassoOverlay(null, null)
        drawingView.enterShapeTransform(shape)
        hideFloatingSelectionToolbar()
        showShapeTransformButtons(shape.boundingBox, shape)
        val shapesExcluded = drawingView.getShapeObjects().filter { it.id != shape.id }
        val strokes        = drawingView.getStrokes()
        val headings       = drawingView.getHeadings()
        val texts          = drawingView.getTextObjects()
        val lines          = drawingView.getLineObjects()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, shapeObjects = shapesExcluded)
            }
            if (bitmap != null && isShapeTransformMode) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
            }
        }
    }

    private fun exitShapeTransformMode(clearSelection: Boolean = false) {
        val finalRender = drawingView.getShapeTransformWorkingRender()
        isShapeTransformMode = false
        drawingView.exitShapeTransform()
        hideShapeTransformButtons()
        if (clearSelection || finalRender == null) {
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession) {
                exitLassoMode()
                drawingView.enableDrawing()
            }
        } else {
            val pad    = 8f * resources.displayMetrics.density
            val selBox = RectF(finalRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            updateFloatingSelectionToolbar(selBox)
        }
        val allShapes   = drawingView.getShapeObjects()
        val strokes     = drawingView.getStrokes()
        val headings    = drawingView.getHeadings()
        val texts       = drawingView.getTextObjects()
        val lines       = drawingView.getLineObjects()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, shapeObjects = allShapes)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
            else drawingView.loadStrokes(strokes)
        }
    }

    private fun showShapeTransformButtons(selBox: RectF, shape: ShapeRender? = null) {
        val working = shape ?: drawingView.getShapeTransformWorkingRender()
        if (working != null) updateShapeTransformToggle(working)
        binding.btnLassoCopy.visibility             = View.GONE
        binding.btnLassoCut.visibility              = View.GONE
        binding.btnLassoDelete.visibility           = View.GONE
        binding.btnConvertShape.visibility          = View.GONE
        binding.shapeConvertDivider.visibility      = View.GONE
        binding.btnShapeAspectLock.visibility       = View.VISIBLE
        binding.btnShapeTransformDone.visibility    = View.VISIBLE
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post { positionFloatingToolbar(selBox) }
    }

    private fun hideShapeTransformButtons() {
        binding.btnShapeAspectLock.visibility    = View.GONE
        binding.btnShapeTransformDone.visibility = View.GONE
        binding.btnLassoCopy.visibility          = View.VISIBLE
        binding.btnLassoCut.visibility           = View.VISIBLE
        binding.btnLassoDelete.visibility        = View.VISIBLE
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    private fun updateShapeTransformToggle(shape: ShapeRender) {
        val isLocked = shape.aspectLocked
        val label = if (shape.type == ShapeType.ELLIPSE) {
            if (isLocked) "Circle" else "Oval"
        } else {
            if (isLocked) "Locked" else "Lock ratio"
        }
        binding.btnShapeAspectLock.text       = label
        binding.btnShapeAspectLock.isSelected = isLocked
    }

    // ── Bitmap rebuild ────────────────────────────────────────────────────────

    private suspend fun rebuildBitmap() {
        val strokes  = drawingView.getStrokes()
        val headings = drawingView.getHeadings()
        val texts    = drawingView.getTextObjects()
        val lines    = drawingView.getLineObjects()
        val shapes   = drawingView.getShapeObjects()
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, shapeObjects = shapes)
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
        val selStrokes = drawingView.getStrokes().filter { it.id in drawingView.lassoSelectedIds }
        val isSingleStrokeOnly = drawingView.lassoSelectedIds.size == 1 && selStrokes.size == 1
        shapeConvertJob?.cancel()
        if (isSingleStrokeOnly) {
            val stroke = selStrokes.first()
            val density = resources.displayMetrics.density
            if (shapeConvertStroke?.id != stroke.id) {
                binding.btnConvertShape.visibility  = View.GONE
                binding.shapeConvertDivider.visibility = View.GONE
                shapeConvertStroke = stroke
                shapeConvertResult = null
                shapeConvertJob = lifecycleScope.launch(Dispatchers.IO) {
                    val result = ShapeRecognizer.recognize(stroke.points, density)
                    withContext(Dispatchers.Main) {
                        if (shapeConvertStroke?.id == stroke.id) {
                            shapeConvertResult = result
                            val visible = if (result != null) View.VISIBLE else View.GONE
                            binding.btnConvertShape.visibility  = visible
                            binding.shapeConvertDivider.visibility = visible
                        }
                    }
                }
            }
        } else {
            shapeConvertStroke = null
            shapeConvertResult = null
            binding.btnConvertShape.visibility  = View.GONE
            binding.shapeConvertDivider.visibility = View.GONE
        }
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
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            val inChrome   = isTouchInView(event, binding.chromeBar)
            val inToolbar  = isTouchInView(event, binding.editorToolbar)
            val inFloating = binding.floatingSelectionToolbar.visibility == View.VISIBLE &&
                isTouchInView(event, binding.floatingSelectionToolbar)
            if (event.actionMasked == MotionEvent.ACTION_DOWN && (inChrome || inToolbar || inFloating)) {
                drawingView.releaseRender()
            }
            if (!inChrome && !inToolbar && !inFloating) handleMultiFingerDoubleTap(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchInView(event: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX; val y = event.rawY
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    /**
     * Multi-finger stationary double-tap: 2 fingers = undo, 3 fingers = redo. Ported from
     * [NotebookActivity]. Arms on the first pointer-down, evaluates on UP (or CANCEL for the
     * BOOX 3-finger interception case) when the centroid stayed within tap slop and the gesture
     * was short. Peak pointer count distinguishes the two cases.
     */
    private fun handleMultiFingerDoubleTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mfTapPeakCount = 1
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val count = event.pointerCount
                if (count > mfTapPeakCount) mfTapPeakCount = count
                if (count >= 4) {
                    mfTapArmed = false
                    mfTapMoved = true
                    return
                }
                if (!mfTapArmed) {
                    mfTapArmed = true
                    mfTapDownTime = event.eventTime
                }
                mfTapCentroidStartX = (0 until count).map { event.getX(it) }.average().toFloat()
                mfTapCentroidStartY = (0 until count).map { event.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mfTapArmed && !mfTapMoved && event.pointerCount >= 2) {
                    val count = event.pointerCount
                    val cx = (0 until count).map { event.getX(it) }.average().toFloat()
                    val cy = (0 until count).map { event.getY(it) }.average().toFloat()
                    val dx = cx - mfTapCentroidStartX
                    val dy = cy - mfTapCentroidStartY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx) mfTapMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mfTapArmed && !mfTapMoved) {
                    val duration = event.eventTime - mfTapDownTime
                    if (duration <= ViewConfiguration.getLongPressTimeout()) {
                        evaluateMultiFingerTap(event.eventTime)
                    }
                }
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mfTapArmed && !mfTapMoved && mfTapPeakCount == 3) {
                    evaluateMultiFingerTap(SystemClock.uptimeMillis(), threeOnly = true)
                } else {
                    twoFingerTapFirstTime = 0L
                    threeFingerTapFirstTime = 0L
                }
                mfTapArmed = false
                mfTapMoved = false
            }
        }
    }

    /** Shared tap-completion logic for [handleMultiFingerDoubleTap]. */
    private fun evaluateMultiFingerTap(now: Long, threeOnly: Boolean = false) {
        when (if (threeOnly) 3 else mfTapPeakCount) {
            2 -> {
                val withinTime = twoFingerTapFirstTime != 0L &&
                    now - twoFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = twoFingerTapFirstTime != 0L && Math.hypot(
                    (mfTapCentroidStartX - twoFingerTapFirstX).toDouble(),
                    (mfTapCentroidStartY - twoFingerTapFirstY).toDouble(),
                ) <= doubleTapSlopPx
                if (withinTime && withinSlop) {
                    twoFingerTapFirstTime = 0L
                    drawingView.releaseRender()
                    undo()
                } else {
                    twoFingerTapFirstTime = now
                    twoFingerTapFirstX = mfTapCentroidStartX
                    twoFingerTapFirstY = mfTapCentroidStartY
                }
            }
            3 -> {
                val withinTime = threeFingerTapFirstTime != 0L &&
                    now - threeFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = threeFingerTapFirstTime != 0L && Math.hypot(
                    (mfTapCentroidStartX - threeFingerTapFirstX).toDouble(),
                    (mfTapCentroidStartY - threeFingerTapFirstY).toDouble(),
                ) <= doubleTapSlopPx
                if (withinTime && withinSlop) {
                    threeFingerTapFirstTime = 0L
                    drawingView.releaseRender()
                    redo()
                } else {
                    threeFingerTapFirstTime = now
                    threeFingerTapFirstX = mfTapCentroidStartX
                    threeFingerTapFirstY = mfTapCentroidStartY
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        drawingView.resumeDrawing()
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
