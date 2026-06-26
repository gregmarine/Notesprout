package com.notesprout.android

import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.Slog
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LinkObject
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.PageData
import com.notesprout.android.data.ScratchpadRepository
import com.notesprout.android.data.StickyNoteObject
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TYPE_STICKY_NOTE
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.toBoundingBoxJson
import com.notesprout.android.data.toLinkObject
import com.notesprout.android.data.toStickyNoteObject
import com.notesprout.android.data.translate
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ScratchpadEntity
import com.notesprout.android.notebook.STICKY_NOTE_ICON_SIZE_DP
import com.notesprout.android.databinding.ActivityScratchpadBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.ScratchpadPreferences
import com.notesprout.android.notebook.ToolPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ScratchpadActivity : AppCompatActivity() {

    companion object {
        /** Nullable — set when launched from a notebook; null when launched from MainActivity. */
        const val EXTRA_FROM_NOTEBOOK_ID        = "from_notebook_id"
        const val EXTRA_FROM_NOTEBOOK_NAME      = "from_notebook_name"
        const val EXTRA_FROM_NOTEBOOK_ENCRYPTED = "from_notebook_encrypted"

        /** If set, navigate to this page ID on open (used by "Send to Scratch Pad"). */
        const val EXTRA_JUMP_TO_PAGE_ID   = "jump_to_page_id"
        /** Comma-separated object IDs to select after load (used by "Send to Scratch Pad"). */
        const val EXTRA_SELECT_OBJECT_IDS = "select_object_ids"

        private const val TAG = "Notesprout"

        // One-finger page-swipe thresholds — mirrors NotebookActivity.
        private const val PAGE_SWIPE_MIN_DISTANCE_FRAC  = 0.30f
        private const val PAGE_SWIPE_LONG_DISTANCE_FRAC = 0.50f
        private const val PAGE_SWIPE_MIN_VELOCITY_MULT  = 1.0f
    }

    // ── Page-swipe state ──────────────────────────────────────────────────────
    private var pageSwipeActive = false
    private var pageSwipeStartX = 0f
    private var pageSwipeStartY = 0f
    private var pageSwipeVelocityTracker: VelocityTracker? = null

    // ── Sticky note finger-tap state ──────────────────────────────────────────
    private var stickyNoteTapCandidate: StickyNoteRender? = null
    private var stickyNoteTapDownX = 0f
    private var stickyNoteTapDownY = 0f
    private var stickyNoteTapDownTime = 0L
    private var stickyNoteTapMoved = false
    private val stickyNoteTouchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private lateinit var binding: ActivityScratchpadBinding
    private lateinit var drawingView: NotebookView
    private lateinit var repository: ScratchpadRepository

    private var pages: List<ScratchpadEntity> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentPageId: String = ""
    private var currentLayerId: String = ""
    private val persistedStrokeIds = mutableSetOf<String>()
    private var isEraserActive = false

    /** Non-null only when launched from a notebook — determines Send-to-Notebook visibility. */
    private var fromNotebookId: String? = null

    // ── Sticky note editor state ──────────────────────────────────────────────
    private var pendingStickyNote: StickyNoteRender? = null
    private var pendingStickyInitialCreate = false

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val out = StickyNoteEditorTransfer.output
        StickyNoteEditorTransfer.input  = null
        StickyNoteEditorTransfer.output = null
        val pending          = pendingStickyNote ?: return@registerForActivityResult
        pendingStickyNote    = null
        val wasInitialCreate = pendingStickyInitialCreate
        pendingStickyInitialCreate = false

        val afterRender = if (out != null) {
            StickyNoteRender(
                id            = pending.id,
                boundingBox   = pending.boundingBox,
                strokes       = out.strokes,
                headings      = out.headings,
                textObjects   = out.textObjects,
                lines         = out.lines,
                contentWidth  = out.contentWidth,
                contentHeight = out.contentHeight,
            )
        } else {
            pending
        }

        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return@registerForActivityResult
        val density = resources.displayMetrics.density

        if (out != null) {
            val afterData  = afterRender.toStickyNoteObject(density).toJson()
            val beforeData = pending.toStickyNoteObject(density).toJson()
            if (afterData != beforeData) {
                lifecycleScope.launch {
                    val dao = NotesproutIndex.scratchpadDao()
                    withContext(Dispatchers.IO) {
                        dao.updateData(pending.id, afterData, System.currentTimeMillis())
                    }
                    val updatedNotes = drawingView.getStickyNotes().map {
                        if (it.id == afterRender.id) afterRender else it
                    }
                    drawingView.loadStickyNotes(updatedNotes)
                    rebuildBitmap()
                    if (wasInitialCreate) selectStickyNoteIcon(afterRender)
                }
                return@registerForActivityResult
            }
        }
        if (wasInitialCreate) selectStickyNoteIcon(afterRender)
    }

    // ── Lasso state ───────────────────────────────────────────────────────────
    private var isLassoMode = false
    private var isSmartLassoSession = false
    private val selectedObjectIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScratchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ScratchpadRepository(NotesproutIndex.db(), NotesproutIndex.scratchpadDao())

        fromNotebookId = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_ID)
        binding.btnSendToNotebook.visibility =
            if (fromNotebookId != null) View.VISIBLE else View.GONE

        // On large screens constrain to 75% × 75%, centered.
        if (resources.getBoolean(R.bool.is_large_screen)) {
            val dm = resources.displayMetrics
            val lp = binding.scratchpadWindow.layoutParams as FrameLayout.LayoutParams
            lp.width   = (dm.widthPixels  * 0.75f).toInt()
            lp.height  = (dm.heightPixels * 0.75f).toInt()
            lp.gravity = Gravity.CENTER
            binding.scratchpadWindow.layoutParams = lp
        }

        // Tapping outside the bordered window dismisses; inside consumes the event.
        binding.root.setOnClickListener { finish() }
        binding.scratchpadWindow.setOnClickListener { }

        // ── Drawing view ──────────────────────────────────────────────────────
        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Bring the floating toolbar above the drawing view.
        binding.floatingSelectionToolbar.bringToFront()

        wireDrawingCallbacks()
        wireToolButtons()
        updateLassoButtonIcon()

        // Restore last-used tool state.
        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnScratchPen.isSelected    = false
                binding.btnScratchEraser.isSelected = true
            }
            else -> {
                binding.btnScratchPen.isSelected    = true
                binding.btnScratchEraser.isSelected = false
            }
        }

        binding.btnScratchStickyNote.setOnClickListener {
            lifecycleScope.launch { insertStickyNote() }
        }

        binding.btnSendToNotebook.setOnClickListener { sendPageToNotebook() }
        binding.btnLassoSendToNotebook.setOnClickListener { sendSelectionToNotebook() }

        binding.btnScratchAddPage.setOnClickListener {
            lifecycleScope.launch { addPage() }
        }
        binding.btnScratchDeletePage.setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Delete Page")
                .setMessage("Delete this page? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> lifecycleScope.launch { deletePage() } }
                .setNegativeButton("Cancel", null)
                .show()
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        }
        binding.btnScratchpadPrev.setOnClickListener {
            if (currentPageIndex > 0) lifecycleScope.launch { navigateTo(currentPageIndex - 1) }
        }
        binding.btnScratchpadNext.setOnClickListener {
            if (currentPageIndex < pages.lastIndex) lifecycleScope.launch { navigateTo(currentPageIndex + 1) }
        }

        // Floating toolbar action buttons.
        binding.btnLassoCopy.setOnClickListener { performLassoCopy() }
        binding.btnLassoCut.setOnClickListener { performLassoCut() }
        binding.btnLassoDelete.setOnClickListener { performLassoDelete() }

        // Bootstrap and load on first open.
        lifecycleScope.launch {
            repository.ensureBootstrap()
            pages = repository.getPages()

            val jumpPageId    = intent.getStringExtra(EXTRA_JUMP_TO_PAGE_ID)
            val selectIdsStr  = intent.getStringExtra(EXTRA_SELECT_OBJECT_IDS)
            val jumpIndex     = jumpPageId?.let { pid -> pages.indexOfFirst { it.id == pid }.takeIf { it >= 0 } }
            val savedIndex    = jumpIndex ?: ScratchpadPreferences.loadCurrentPageIndex(this@ScratchpadActivity)
            currentPageIndex  = savedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (jumpIndex != null) ScratchpadPreferences.saveCurrentPageIndex(this@ScratchpadActivity, currentPageIndex)

            val page = pages.getOrNull(currentPageIndex) ?: return@launch
            currentPageId   = page.id
            currentLayerId  = repository.getLayerForPage(currentPageId)?.id ?: ""
            updatePageIndicator()

            val selectIds = selectIdsStr?.split(",")?.toSet()?.takeIf { it.isNotEmpty() }

            if (binding.drawingContainer.width > 0) {
                loadCurrentPage()
                if (selectIds != null) selectInsertedObjects(selectIds)
            } else {
                binding.drawingContainer.doOnLayout {
                    lifecycleScope.launch {
                        loadCurrentPage()
                        if (selectIds != null) selectInsertedObjects(selectIds)
                    }
                }
            }
        }
    }

    // ── Drawing callbacks ─────────────────────────────────────────────────────

    private fun wireDrawingCallbacks() {
        drawingView.onStrokeErased = { strokeId ->
            persistedStrokeIds.remove(strokeId)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    NotesproutIndex.scratchpadDao().softDelete(strokeId, System.currentTimeMillis())
                }
            }
        }

        drawingView.onHeadingErased = { heading ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(heading.id, deletedAt)
                withContext(Dispatchers.Main) { lifecycleScope.launch { rebuildBitmap() } }
            }
        }

        drawingView.onTextErased = { textObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(textObject.id, deletedAt)
                withContext(Dispatchers.Main) { lifecycleScope.launch { rebuildBitmap() } }
            }
        }

        drawingView.onLineErased = { lineObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(lineObject.id, deletedAt)
                withContext(Dispatchers.Main) { lifecycleScope.launch { rebuildBitmap() } }
            }
        }

        drawingView.onLinkErased = { linkObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(linkObject.id, deletedAt)
                withContext(Dispatchers.Main) { lifecycleScope.launch { rebuildBitmap() } }
            }
        }

        drawingView.onStickyNoteErased = { noteObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(noteObject.id, deletedAt)
                withContext(Dispatchers.Main) { lifecycleScope.launch { rebuildBitmap() } }
            }
        }

        drawingView.onPenLifted = {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { saveStrokes() }
            }
        }

        drawingView.onSnapshotReady = { snapshot ->
            val pageId = currentPageId
            if (pageId.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { persistSnapshot(pageId, snapshot) }
                }
            }
        }

        // ── Lasso callbacks ───────────────────────────────────────────────────

        drawingView.onLassoComplete = { drawnPath, startPoint ->
            // Auto-close: straight line from end back to start, then close polygon.
            drawnPath.lineTo(startPoint.x, startPoint.y)
            drawnPath.close()

            selectedObjectIds.clear()
            val strokeSnapshot      = drawingView.getStrokes()
            val headingSnapshot     = drawingView.getHeadings()
            val textSnapshot        = drawingView.getTextObjects()
            val lineSnapshot        = drawingView.getLineObjects()
            val linkSnapshot        = drawingView.getLinks()
            val stickyNoteSnapshot  = drawingView.getStickyNotes()
            lifecycleScope.launch(Dispatchers.Default) {
                val lassoBounds = RectF()
                drawnPath.computeBounds(lassoBounds, true)

                val density = resources.displayMetrics.density
                val minPx = 10f * density
                if (lassoBounds.width() < minPx && lassoBounds.height() < minPx) return@launch

                val clipRect = Rect(
                    (lassoBounds.left   - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.top    - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.right  + 1f).toInt(),
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
                            hitIds.add(stroke.id)
                            unionBounds.union(stroke.boundingBox)
                            break
                        }
                    }
                }

                for (heading in headingSnapshot) {
                    if (!RectF.intersects(lassoBounds, heading.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, heading.boundingBox)) {
                        hitIds.add(heading.id)
                        unionBounds.union(heading.boundingBox)
                    }
                }

                for (textObj in textSnapshot) {
                    if (!RectF.intersects(lassoBounds, textObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, textObj.boundingBox)) {
                        hitIds.add(textObj.id)
                        unionBounds.union(textObj.boundingBox)
                    }
                }

                for (lineObj in lineSnapshot) {
                    if (!RectF.intersects(lassoBounds, lineObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, lineObj.boundingBox)) {
                        hitIds.add(lineObj.id)
                        unionBounds.union(lineObj.boundingBox)
                    }
                }

                for (link in linkSnapshot) {
                    if (!RectF.intersects(lassoBounds, link.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, link.boundingBox)) {
                        hitIds.add(link.id)
                        unionBounds.union(link.boundingBox)
                    }
                }

                for (note in stickyNoteSnapshot) {
                    if (!RectF.intersects(lassoBounds, note.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, note.boundingBox)) {
                        hitIds.add(note.id)
                        unionBounds.union(note.boundingBox)
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
            // Capture before clearing — a no-selection tap (e.g. tap-to-paste after cut)
            // must NOT exit smart-lasso; that would tear down lasso mode before paste runs.
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

        drawingView.onLassoTap = tap@{ tapX, tapY ->
            if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) {
                performLassoPaste(tapX, tapY)
            }
        }

        drawingView.onDragStarted = {
            hideFloatingSelectionToolbar()
        }

        drawingView.onStrokesMoved = { _, movedStrokes, _, movedHeadings, _, movedTextObjects, _, movedLineObjects, _, movedLinks, _, movedStickyNotes ->
            lifecycleScope.launch {
                val now     = System.currentTimeMillis()
                val density = resources.displayMetrics.density
                val dao     = NotesproutIndex.scratchpadDao()
                withContext(Dispatchers.IO) {
                    for (s in movedStrokes) {
                        val bbox = s.boundingBox
                        dao.updateObjectData(s.id, BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(), s.toStrokeData(now).toJson(), now)
                    }
                    for (h in movedHeadings) {
                        dao.updateObjectData(h.id, h.boundingBox.toBoundingBoxJson(), HeadingObject(h.strokes, h.recognizedText, h.level).toJson(), now)
                    }
                    for (t in movedTextObjects) {
                        dao.updateObjectData(t.id, t.boundingBox.toBoundingBoxJson(), TextObject(text = t.text, strokes = t.strokes).toJson(), now)
                    }
                    for (l in movedLineObjects) {
                        dao.updateObjectData(l.id, l.boundingBox.toBoundingBoxJson(), LineObject(l.style, l.orientation, l.strokeWidthDp, l.dotSpacingPx / density).toJson(), now)
                    }
                    for (lk in movedLinks) {
                        dao.updateObjectData(lk.id, lk.boundingBox.toBoundingBoxJson(), lk.toLinkObject(density).toJson(), now)
                    }
                    for (note in movedStickyNotes) {
                        dao.updateObjectData(note.id, note.boundingBox.toBoundingBoxJson(), note.toStickyNoteObject(density).toJson(), now)
                    }
                }
                val newBox = RectF()
                movedStrokes.forEach { newBox.union(it.boundingBox) }
                movedHeadings.forEach { newBox.union(it.boundingBox) }
                movedTextObjects.forEach { newBox.union(it.boundingBox) }
                movedLineObjects.forEach { newBox.union(it.boundingBox) }
                movedLinks.forEach { newBox.union(it.boundingBox) }
                movedStickyNotes.forEach { newBox.union(it.boundingBox) }
                val pad = 8f * resources.displayMetrics.density
                newBox.inset(-pad, -pad)
                updateFloatingSelectionToolbar(newBox)
            }
        }

        drawingView.onLassoSelectionCleared = {
            hideFloatingSelectionToolbar()
        }

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
            val currentStrokes      = drawingView.getStrokes()
            val currentHeadings     = drawingView.getHeadings()
            val currentTexts        = drawingView.getTextObjects()
            val currentLines        = drawingView.getLineObjects()
            val currentLinks        = drawingView.getLinks()
            val currentStickyNotes  = drawingView.getStickyNotes()
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(currentStrokes, null, currentHeadings, currentTexts, currentLines, currentLinks, stickyNotes = currentStickyNotes)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, null)
                }
                drawingView.setLassoOverlay(null, paddedBounds)
                updateFloatingSelectionToolbar(paddedBounds)
            }
        }

        drawingView.onScribbleEraseComplete = { erasedObjectIds, erasedHeadings, erasedTextObjects, erasedLineObjects, erasedLinks, erasedStickyNotes ->
            if (erasedObjectIds.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.softDeleteObjects(erasedObjectIds) }
                    val erasedHeadingIds    = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                    val erasedTextIds       = erasedTextObjects.mapTo(mutableSetOf()) { it.id }
                    val erasedLineIds       = erasedLineObjects.mapTo(mutableSetOf()) { it.id }
                    val erasedLinkIds       = erasedLinks.mapTo(mutableSetOf()) { it.id }
                    val erasedStickyIds     = erasedStickyNotes.mapTo(mutableSetOf()) { it.id }
                    val erasedSet           = erasedObjectIds.toSet()
                    val updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedSet }
                    val updatedHeadings     = drawingView.getHeadings().filter { it.id !in erasedHeadingIds }
                    val updatedTexts        = drawingView.getTextObjects().filter { it.id !in erasedTextIds }
                    val updatedLines        = drawingView.getLineObjects().filter { it.id !in erasedLineIds }
                    val updatedLinks        = drawingView.getLinks().filter { it.id !in erasedLinkIds }
                    val updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in erasedStickyIds }
                    persistedStrokeIds.removeAll(erasedSet - erasedHeadingIds - erasedTextIds - erasedLineIds - erasedLinkIds - erasedStickyIds)
                    drawingView.setStrokeListSilently(updatedStrokes)
                    drawingView.loadHeadings(updatedHeadings)
                    drawingView.loadTextObjects(updatedTexts)
                    drawingView.loadLineObjects(updatedLines)
                    drawingView.loadLinks(updatedLinks)
                    drawingView.loadStickyNotes(updatedStickyNotes)
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updatedStrokes, null, updatedHeadings, updatedTexts, updatedLines, updatedLinks, stickyNotes = updatedStickyNotes)
                    }
                    if (bitmap != null) {
                        drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, null)
                    } else {
                        drawingView.loadStrokes(updatedStrokes)
                    }
                }
            }
        }
    }

    // ── Tool buttons ──────────────────────────────────────────────────────────

    private fun wireToolButtons() {
        binding.btnScratchPen.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            isEraserActive = false
            drawingView.setEraserMode(false)
            binding.btnScratchPen.isSelected    = true
            binding.btnScratchEraser.isSelected = false
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, ActiveTool.PEN)
        }

        binding.btnScratchEraser.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnScratchEraser.isSelected = isEraserActive
            binding.btnScratchPen.isSelected    = !isEraserActive
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }

        binding.btnScratchLasso.setOnClickListener {
            if (!isLassoMode) enterLassoMode() else exitLassoMode()
            drawingView.releaseRender()
        }
    }

    // ── Lasso mode helpers ────────────────────────────────────────────────────

    private fun enterLassoMode() {
        isLassoMode = true
        if (isEraserActive) {
            isEraserActive = false
            drawingView.setEraserMode(false)
        }
        drawingView.setLassoMode(true)
        binding.btnScratchLasso.isSelected  = true
        binding.btnScratchPen.isSelected    = false
        binding.btnScratchEraser.isSelected = false
    }

    private fun exitLassoMode() {
        isLassoMode = false
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.setLassoMode(false)
        binding.btnScratchLasso.isSelected  = false
        binding.btnScratchPen.isSelected    = !isEraserActive
        binding.btnScratchEraser.isSelected = isEraserActive
        hideFloatingSelectionToolbar()
    }

    /** Clear page-specific selection state without changing the active tool mode. */
    private fun clearLassoSelectionForNavigation() {
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
    }

    // ── Lasso operations ──────────────────────────────────────────────────────

    private fun performLassoCopy() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val links        = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }

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
            links = links.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
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
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val links        = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }

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
            links = links.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
        )
        updateLassoButtonIcon()
        val allIds = strokes.map { it.id } + headings.map { it.id } + textObjects.map { it.id } +
                     lineObjects.map { it.id } + links.map { it.id } + stickyNotes.map { it.id }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.softDeleteObjects(allIds) }
            val erasedSet          = allIds.toSet()
            val updatedStrokes     = drawingView.getStrokes().filter { it.id !in erasedSet }
            val updatedHeadings    = drawingView.getHeadings().filter { it.id !in erasedSet }
            val updatedTexts       = drawingView.getTextObjects().filter { it.id !in erasedSet }
            val updatedLines       = drawingView.getLineObjects().filter { it.id !in erasedSet }
            val updatedLinks       = drawingView.getLinks().filter { it.id !in erasedSet }
            val updatedStickyNotes = drawingView.getStickyNotes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)
            drawingView.setStrokeListSilently(updatedStrokes)
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, null, updatedHeadings, updatedTexts, updatedLines, updatedLinks, stickyNotes = updatedStickyNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, null)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
        }
    }

    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val allIds = (drawingView.getStrokes().filter { it.id in ids }.map { it.id } +
                      drawingView.getHeadings().filter { it.id in ids }.map { it.id } +
                      drawingView.getTextObjects().filter { it.id in ids }.map { it.id } +
                      drawingView.getLineObjects().filter { it.id in ids }.map { it.id } +
                      drawingView.getLinks().filter { it.id in ids }.map { it.id } +
                      drawingView.getStickyNotes().filter { it.id in ids }.map { it.id })
        if (allIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.softDeleteObjects(allIds) }
            val erasedSet          = allIds.toSet()
            val updatedStrokes     = drawingView.getStrokes().filter { it.id !in erasedSet }
            val updatedHeadings    = drawingView.getHeadings().filter { it.id !in erasedSet }
            val updatedTexts       = drawingView.getTextObjects().filter { it.id !in erasedSet }
            val updatedLines       = drawingView.getLineObjects().filter { it.id !in erasedSet }
            val updatedLinks       = drawingView.getLinks().filter { it.id !in erasedSet }
            val updatedStickyNotes = drawingView.getStickyNotes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)
            drawingView.setStrokeListSilently(updatedStrokes)
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, null, updatedHeadings, updatedTexts, updatedLines, updatedLinks, stickyNotes = updatedStickyNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, null)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
        }
    }

    // ── Send to Notebook ──────────────────────────────────────────────────────

    /** Toolbar button: send all objects on the current page to the notebook. */
    private fun sendPageToNotebook() {
        if (fromNotebookId == null) return
        val strokes      = drawingView.getStrokes()
        val headings     = drawingView.getHeadings()
        val textObjects  = drawingView.getTextObjects()
        val lineObjects  = drawingView.getLineObjects()
        val links        = drawingView.getLinks()
        val stickyNotes  = drawingView.getStickyNotes()
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }

        ScratchpadTransfer.pending = NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                    recognizedText = h.recognizedText, level = h.level)
            },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = links.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
        )
        setResult(RESULT_OK)
        finish()
    }

    /** Floating-toolbar lasso button: send the current selection to the notebook. */
    private fun sendSelectionToNotebook() {
        if (fromNotebookId == null) return
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val links        = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }

        ScratchpadTransfer.pending = NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                    recognizedText = h.recognizedText, level = h.level)
            },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = links.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
        )
        setResult(RESULT_OK)
        finish()
    }

    private fun performLassoPaste(tapX: Float, tapY: Float) {
        val clip    = NotesproutClipboard.content ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val density = resources.displayMetrics.density

        val dx = tapX - clip.boundingBox.centerX()
        val dy = tapY - clip.boundingBox.centerY()

        val newStrokes = clip.strokes.map { s ->
            s.copy(
                id = java.util.UUID.randomUUID().toString(),
                points = s.points.map { PointF(it.x + dx, it.y + dy) },
            )
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                    h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s ->
                    s.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        points = s.points.map { PointF(it.x + dx, it.y + dy) },
                    )
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
        val newLinks = clip.links.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newStickyNotes = clip.stickyNotes.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val translatedClip = NotesproutClipboard.ClipboardContent(
            strokes     = newStrokes,
            headings    = newHeadings,
            boundingBox = RectF(clip.boundingBox.left + dx, clip.boundingBox.top + dy,
                                clip.boundingBox.right + dx, clip.boundingBox.bottom + dy),
            textObjects = newTextObjects,
            lineObjects = newLineObjects,
            links       = newLinks,
            stickyNotes = newStickyNotes,
        )

        lifecycleScope.launch {
            val existingStrokes      = drawingView.getStrokes()
            val existingHeadings     = drawingView.getHeadings()
            val existingTexts        = drawingView.getTextObjects()
            val existingLines        = drawingView.getLineObjects()
            val existingLinks        = drawingView.getLinks()
            val existingStickyNotes  = drawingView.getStickyNotes()
            withContext(Dispatchers.IO) { repository.insertObjects(layerId, translatedClip, density) }
            newStrokes.forEach { persistedStrokeIds.add(it.id) }

            val allStrokes      = existingStrokes     + newStrokes
            val allHeadings     = existingHeadings    + newHeadings
            val allTexts        = existingTexts       + newTextObjects
            val allLines        = existingLines       + newLineObjects
            val allLinks        = existingLinks       + newLinks
            val allStickyNotes  = existingStickyNotes + newStickyNotes
            drawingView.loadHeadings(allHeadings)
            drawingView.loadTextObjects(allTexts)
            drawingView.loadLineObjects(allLines)
            drawingView.loadLinks(allLinks)
            drawingView.loadStickyNotes(allStickyNotes)

            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, null, allHeadings, allTexts, allLines, allLinks, stickyNotes = allStickyNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(allStrokes, bitmap, null)
            } else {
                drawingView.loadStrokes(allStrokes)
            }

            val newBox = RectF()
            newStrokes.forEach      { newBox.union(it.boundingBox) }
            newHeadings.forEach     { newBox.union(it.boundingBox) }
            newTextObjects.forEach  { newBox.union(it.boundingBox) }
            newLineObjects.forEach  { newBox.union(it.boundingBox) }
            newLinks.forEach        { newBox.union(it.boundingBox) }
            newStickyNotes.forEach  { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            selectedObjectIds.addAll(newTextObjects.map { it.id })
            selectedObjectIds.addAll(newLineObjects.map { it.id })
            selectedObjectIds.addAll(newLinks.map { it.id })
            selectedObjectIds.addAll(newStickyNotes.map { it.id })
            isSmartLassoSession = false
            // Re-assert lasso mode visually — onLassoTapToDismiss may have fired
            // synchronously just before paste and cleared button state.
            if (!isLassoMode) enterLassoMode()
            binding.btnScratchLasso.isSelected  = true
            binding.btnScratchPen.isSelected    = false
            binding.btnScratchEraser.isSelected = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
        }
    }

    // ── Post-insert selection (from "Send to Scratch Pad") ───────────────────

    private fun selectInsertedObjects(ids: Set<String>) {
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val links        = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }

        val hitIds = mutableSetOf<String>()
        strokes.mapTo(hitIds) { it.id }
        headings.mapTo(hitIds) { it.id }
        textObjects.mapTo(hitIds) { it.id }
        lineObjects.mapTo(hitIds) { it.id }
        links.mapTo(hitIds) { it.id }
        stickyNotes.mapTo(hitIds) { it.id }
        if (hitIds.isEmpty()) return

        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }

        selectedObjectIds.clear()
        selectedObjectIds.addAll(hitIds)
        if (!isLassoMode) enterLassoMode()
        val pad = 8f * resources.displayMetrics.density
        box.inset(-pad, -pad)
        drawingView.setLassoSelectedIds(hitIds, box)
        updateFloatingSelectionToolbar(box)
    }

    // ── Lasso button icon ─────────────────────────────────────────────────────

    private fun updateLassoButtonIcon() {
        binding.btnScratchLasso.setImageResource(
            if (NotesproutClipboard.hasContent()) R.drawable.ic_lasso_clipboard
            else R.drawable.ic_lasso
        )
    }

    // ── Floating selection toolbar helpers ────────────────────────────────────

    private fun updateFloatingSelectionToolbar(selectionBox: RectF) {
        binding.btnLassoSendToNotebook.visibility =
            if (fromNotebookId != null) View.VISIBLE else View.GONE
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post { positionFloatingToolbar(selectionBox) }
    }

    private fun hideFloatingSelectionToolbar() {
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    private fun positionFloatingToolbar(selectionBox: RectF) {
        val view = binding.floatingSelectionToolbar
        val viewW = view.measuredWidth.toFloat()
        val viewH = view.measuredHeight.toFloat()
        val containerW = binding.drawingContainer.width.toFloat()
        val containerH = binding.drawingContainer.height.toFloat()
        val dpGap = 8f * resources.displayMetrics.density

        var x = selectionBox.centerX() - viewW / 2f
        var y = selectionBox.bottom + dpGap
        if (y + viewH > containerH) y = selectionBox.top - dpGap - viewH
        y = y.coerceIn(0f, (containerH - viewH).coerceAtLeast(0f))
        x = x.coerceIn(0f, (containerW - viewW).coerceAtLeast(0f))

        view.x = x
        view.y = y
    }

    // ── Page load ─────────────────────────────────────────────────────────────

    private suspend fun loadCurrentPage() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val density = resources.displayMetrics.density

        // Read container dimensions on the main thread before dispatching IO.
        val containerW = binding.drawingContainer.width.toFloat()
        val containerH = binding.drawingContainer.height.toFloat()

        val content = withContext(Dispatchers.IO) {
            // Record real page size on first layout if still at 0×0.
            if (containerW > 0 && containerH > 0) {
                val row = NotesproutIndex.scratchpadDao().getObjectById(pageId)
                val pd  = row?.let { PageData.fromJson(it.data) }
                if (pd != null && (pd.width == 0f || pd.height == 0f)) {
                    repository.setPageSize(pageId, containerW, containerH)
                }
            }
            repository.loadPage(pageId, density)
        }

        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(
                content.strokes,
                null, // scratch pad has no template
                content.headings,
                content.textObjects,
                content.lineObjects,
                content.links,
                stickyNotes = content.stickyNotes,
            )
        }

        // Apply on main thread.
        drawingView.loadHeadings(content.headings)
        drawingView.loadTextObjects(content.textObjects)
        drawingView.loadLineObjects(content.lineObjects)
        drawingView.loadLinks(content.links)
        drawingView.loadStickyNotes(content.stickyNotes)
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(content.strokes, bitmap, null)
        } else {
            drawingView.setTemplate(null)
            drawingView.loadStrokes(content.strokes)
        }

        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(content.strokes.map { it.id })
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    // Must be called on Dispatchers.IO.
    private suspend fun saveStrokes() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val currentStrokes = drawingView.getStrokes()
        val alreadyPersisted = persistedStrokeIds.toHashSet()
        val newStrokes = currentStrokes.filter { it.id !in alreadyPersisted }
        if (newStrokes.isEmpty()) return
        repository.saveStrokes(layerId, newStrokes)
        persistedStrokeIds.addAll(newStrokes.map { it.id })
    }

    private suspend fun persistSnapshot(pageId: String, snapshot: String) {
        val dao = NotesproutIndex.scratchpadDao()
        val row = dao.getObjectById(pageId) ?: return
        val updated = PageData.fromJson(row.data).copy(snapshot = snapshot).toJson()
        dao.updateData(pageId, updated, System.currentTimeMillis())
    }

    /** Rebuild the render bitmap using the drawing view's current in-memory object lists. */
    private suspend fun rebuildBitmap() {
        val strokes      = drawingView.getStrokes()
        val headings     = drawingView.getHeadings()
        val texts        = drawingView.getTextObjects()
        val lines        = drawingView.getLineObjects()
        val links        = drawingView.getLinks()
        val stickyNotes  = drawingView.getStickyNotes()
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, links, stickyNotes = stickyNotes)
        }
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
        } else {
            drawingView.loadStrokes(strokes)
        }
    }

    // ── Multi-page navigation ─────────────────────────────────────────────────

    /** Two-phase navigate: save leaving page, erase, load target. Main-thread coroutine only. */
    private suspend fun navigateTo(newIndex: Int) {
        withContext(Dispatchers.IO) { saveStrokes() }
        if (isLassoMode) clearLassoSelectionForNavigation()
        currentPageIndex = newIndex
        ScratchpadPreferences.saveCurrentPageIndex(this, currentPageIndex)
        val page = pages.getOrNull(currentPageIndex) ?: return
        currentPageId   = page.id
        currentLayerId  = repository.getLayerForPage(currentPageId)?.id ?: ""
        persistedStrokeIds.clear()
        drawingView.eraseAll()
        updatePageIndicator()
        loadCurrentPage()
        if (isLassoMode) drawingView.setLassoMode(true)
    }

    private suspend fun addPage() {
        withContext(Dispatchers.IO) { saveStrokes() }
        if (isLassoMode) clearLassoSelectionForNavigation()
        val newPageId = repository.addPage(afterIndex = currentPageIndex)
        pages = repository.getPages()
        currentPageIndex = pages.indexOfFirst { it.id == newPageId }.coerceAtLeast(0)
        currentPageId   = newPageId
        currentLayerId  = repository.getLayerForPage(newPageId)?.id ?: ""
        persistedStrokeIds.clear()
        ScratchpadPreferences.saveCurrentPageIndex(this, currentPageIndex)
        drawingView.eraseAll()
        updatePageIndicator()
        loadCurrentPage()
        if (isLassoMode) drawingView.setLassoMode(true)
    }

    private suspend fun deletePage() {
        val deletingId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        if (isLassoMode) clearLassoSelectionForNavigation()
        repository.deletePage(deletingId)
        pages = repository.getPages()
        currentPageIndex = currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val page = pages.getOrNull(currentPageIndex)
        currentPageId   = page?.id ?: ""
        currentLayerId  = if (currentPageId.isNotEmpty()) repository.getLayerForPage(currentPageId)?.id ?: "" else ""
        persistedStrokeIds.clear()
        ScratchpadPreferences.saveCurrentPageIndex(this, currentPageIndex)
        drawingView.eraseAll()
        updatePageIndicator()
        if (currentPageId.isNotEmpty()) {
            loadCurrentPage()
            if (isLassoMode) drawingView.setLassoMode(true)
        }
    }

    // ── Sticky note helpers ───────────────────────────────────────────────────

    private suspend fun insertStickyNote() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageW   = binding.drawingContainer.width.takeIf  { it > 0 } ?: return
        val pageH   = binding.drawingContainer.height.takeIf { it > 0 } ?: return
        val density = resources.displayMetrics.density

        val iconSizePx = STICKY_NOTE_ICON_SIZE_DP * density
        val left  = ((pageW - iconSizePx) / 2f).coerceAtLeast(0f)
        val top   = ((pageH - iconSizePx) / 2f).coerceAtLeast(0f)
        val bbox  = RectF(left, top, left + iconSizePx, top + iconSizePx)

        val noteId = java.util.UUID.randomUUID().toString()
        val now    = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            NotesproutIndex.scratchpadDao().insertObject(
                ScratchpadEntity(
                    id          = noteId,
                    parentId    = layerId,
                    boundingBox = bbox.toBoundingBoxJson(),
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = TYPE_STICKY_NOTE,
                    data        = StickyNoteObject().toJson(),
                )
            )
        }

        val noteRender   = StickyNoteRender(id = noteId, boundingBox = bbox)
        val updatedNotes = drawingView.getStickyNotes() + noteRender
        drawingView.loadStickyNotes(updatedNotes)
        rebuildBitmap()
        openStickyNote(noteRender, initialCreate = true)
    }

    private fun openStickyNote(note: StickyNoteRender, initialCreate: Boolean) {
        StickyNoteEditorTransfer.input = StickyNoteEditorTransfer.Content(
            strokes       = note.strokes,
            headings      = note.headings,
            textObjects   = note.textObjects,
            lines         = note.lines,
            contentWidth  = note.contentWidth,
            contentHeight = note.contentHeight,
        )
        StickyNoteEditorTransfer.output = null
        pendingStickyNote          = note
        pendingStickyInitialCreate = initialCreate
        editorLauncher.launch(Intent(this, StickyNoteEditorActivity::class.java))
    }

    private fun stickyNoteAt(x: Float, y: Float): StickyNoteRender? =
        drawingView.getStickyNotes().lastOrNull { it.boundingBox.contains(x, y) }

    private fun selectStickyNoteIcon(note: StickyNoteRender) {
        if (!isLassoMode) enterLassoMode()
        selectedObjectIds.clear()
        selectedObjectIds.add(note.id)
        drawingView.lassoSelectedIds = selectedObjectIds.toSet()
        val pad = 8f * resources.displayMetrics.density
        val selBox = RectF(note.boundingBox).apply { inset(-pad, -pad) }
        drawingView.setLassoOverlay(null, selBox)
        updateFloatingSelectionToolbar(selBox)
    }

    // ── Sticky note tap gesture ───────────────────────────────────────────────

    private fun handleStickyNoteTapGesture(event: MotionEvent) {
        // Convert window-relative event coordinates into drawing-container coordinates.
        val containerLoc = IntArray(2)
        binding.drawingContainer.getLocationInWindow(containerLoc)
        val localX = event.x - containerLoc[0]
        val localY = event.y - containerLoc[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stickyNoteTapDownX    = localX
                stickyNoteTapDownY    = localY
                stickyNoteTapDownTime = event.eventTime
                stickyNoteTapMoved    = false
                stickyNoteTapCandidate = if (!isLassoMode) stickyNoteAt(localX, localY) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                stickyNoteTapMoved     = true
                stickyNoteTapCandidate = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (!stickyNoteTapMoved && stickyNoteTapCandidate != null) {
                    val dx = localX - stickyNoteTapDownX
                    val dy = localY - stickyNoteTapDownY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > stickyNoteTouchSlopPx) {
                        stickyNoteTapMoved     = true
                        stickyNoteTapCandidate = null
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val candidate = stickyNoteTapCandidate
                stickyNoteTapCandidate = null
                val isTap = candidate != null
                    && !stickyNoteTapMoved
                    && event.pointerCount == 1
                    && event.eventTime - stickyNoteTapDownTime <= ViewConfiguration.getLongPressTimeout()
                if (isTap && candidate != null && candidate.boundingBox.contains(localX, localY)) {
                    openStickyNote(candidate, initialCreate = false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                stickyNoteTapMoved     = true
                stickyNoteTapCandidate = null
            }
        }
    }

    // ── Page-swipe gesture ────────────────────────────────────────────────────

    private fun handlePageSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageSwipeActive = true
                pageSwipeStartX = event.x
                pageSwipeStartY = event.y
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageSwipeActive) pageSwipeVelocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_UP -> {
                if (pageSwipeActive) {
                    val tracker = pageSwipeVelocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        evaluatePageFling(vx, vy, event.x - pageSwipeStartX, event.y - pageSwipeStartY)
                        tracker.recycle()
                        pageSwipeVelocityTracker = null
                    }
                }
                pageSwipeActive = false
            }
            MotionEvent.ACTION_CANCEL -> {
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
            }
        }
    }

    private fun evaluatePageFling(velocityX: Float, velocityY: Float, dx: Float, dy: Float) {
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx <= absDy) return
        val width    = binding.drawingContainer.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val minDist  = PAGE_SWIPE_MIN_DISTANCE_FRAC * width
        val minVel   = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityX) >= minVel
        val longEnough = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width
        if (absDx < minDist) return
        if (!fastEnough && !longEnough) return

        Slog.d(TAG) { "scratchpad evaluatePageFling: ${if (dx < 0) "next" else "prev"}" }
        if (dx < 0) {
            if (currentPageIndex < pages.lastIndex) {
                lifecycleScope.launch { navigateTo(currentPageIndex + 1) }
            } else {
                lifecycleScope.launch { addPage() }
            }
        } else {
            if (currentPageIndex > 0) lifecycleScope.launch { navigateTo(currentPageIndex - 1) }
        }
    }

    // ── Chrome helpers ────────────────────────────────────────────────────────

    private fun updatePageIndicator() {
        binding.tvScratchpadPageIndicator.text = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}"
    }

    // ── Touch dispatch ────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            val inChrome   = isTouchInView(event, binding.chromeBar)
            val inToolbar  = isTouchInView(event, binding.scratchpadToolbar)
            val inFloating = binding.floatingSelectionToolbar.visibility == View.VISIBLE &&
                             isTouchInView(event, binding.floatingSelectionToolbar)

            // Release EPD overlay when touching chrome, toolbar, or floating toolbar.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (inChrome || inToolbar || inFloating) drawingView.releaseRender()
            }

            // Sticky note tap: finger down→up with minimal movement in the drawing area.
            // Mirrors NotebookActivity.handleLinkFollowGesture — uses event.x/y (window-relative)
            // with getLocationInWindow offset to convert into drawing-container coordinates.
            handleStickyNoteTapGesture(event)

            // Swipe outside chrome/toolbar in any tool mode — lasso is stylus-only, finger navigates.
            if (!inChrome && !inToolbar) {
                handlePageSwipe(event)
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
        ScratchpadPreferences.saveCurrentPageIndex(this, currentPageIndex)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
    }
}
