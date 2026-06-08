package com.notesprout.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.withTransaction
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.core.Slog
import com.notesprout.android.data.copyPageAfter
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TYPE_HEADING
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.data.StrokePoint
import com.notesprout.android.databinding.ActivityDrawingBinding
import com.notesprout.android.databinding.DialogEditHeadingTextBinding
import com.notesprout.android.drawing.DrawingView
import com.notesprout.android.drawing.GenericDrawingView
import com.notesprout.android.drawing.OnyxDrawingView
import com.notesprout.android.history.UndoRedoAction
import com.notesprout.android.history.UndoRedoManager
import com.notesprout.android.recognition.HandwritingRecognizer
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.toc.TocDialog
import com.notesprout.android.toc.TocRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

class DrawingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Notesprout"

        /** Intent extra key — the absolute path to the `.soil` notebook file. */
        const val EXTRA_NOTEBOOK_PATH = "notebook_path"
    }

    /**
     * Result of [loadCurrentPage]: carries everything needed to display a page and
     * decide whether a background stroke-deserialization pass is needed.
     *
     * [usedSnapshot] = true  → [strokes] is empty; display [displayBitmap] then
     *                          deserialize strokes in background via [postDisplayWork].
     * [usedSnapshot] = false → [strokes] is fully populated; [displayBitmap] was built
     *                          from scratch; [postDisplayWork] captures a snapshot.
     */
    private data class PageLoadResult(
        val strokes: List<LiveStroke>,
        val templateBitmap: Bitmap?,
        val displayBitmap: Bitmap?,
        val usedSnapshot: Boolean,
        val headings: List<HeadingStroke> = emptyList(),
    )

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var drawingView: DrawingView
    private var isEraserActive = false

    // ── Lasso selection state ─────────────────────────────────────────────────
    private var isLassoMode = false
    private var isLassoEraserMode = false
    /** IDs of objects selected by the most recent lasso gesture. */
    val selectedObjectIds = mutableSetOf<String>()

    // ── One-finger page swipe state ───────────────────────────────────────────
    // Deliberate full-width horizontal swipe turns pages.  Three guards ensure
    // only intentional gestures qualify: minimum distance (screen-width relative),
    // minimum velocity (1.5× system fling threshold), and horizontal dominance.
    // A second finger cancels the gesture so palm+finger combos never fire.
    private var pageSwipeActive = false
    private var pageSwipeStartX = 0f
    private var pageSwipeStartY = 0f
    private var pageSwipeVelocityTracker: VelocityTracker? = null

    // Two-finger horizontal swipe → insert a new page before/after the current page.
    private var twoFingerSwipeActive = false
    private var twoFingerSwipeStartX = 0f
    private var twoFingerSwipeStartY = 0f
    private var twoFingerSwipeVelocityTracker: VelocityTracker? = null

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null
    private var sessionStartTime: Long = 0L

    /**
     * In-memory notebook metadata row.  Loaded once when the notebook is opened.
     * Holds the notebook UUID (used as parentId for all pages) and the last-opened
     * page UUID (used to restore position on re-open).
     * Null for notebooks that pre-date the metadata row (falls back gracefully).
     */
    private var notebookMetadata: NotebookMetadata? = null

    // ── Page state ────────────────────────────────────────────────────────────

    /** All live pages in sorted order. Populated (and refreshed) by [setupPageIds]. */
    private var pages: MutableList<NotebookObject> = mutableListOf()

    /** Index into [pages] for the currently displayed page. */
    private var currentPageIndex: Int = 0

    /** ID of the currently displayed page row. Set by [setupPageIds]. */
    private var currentPageId: String = ""

    /** ID of the content layer under [currentPageId]. Set by [setupPageIds]. */
    private var currentLayerId: String = ""

    // ── Persisted stroke tracking ─────────────────────────────────────────────

    /**
     * IDs of strokes confirmed to exist in the DB for the current page/layer.
     * Populated by [deserializeStrokesFromDb] after each page load and extended by
     * [saveStrokes] after each incremental save.  Reduced by the [onStrokeErased]
     * callback and cleared by the page-clear handler.
     *
     * Avoids redundant [StrokeData.toJson] serialization in [saveStrokes] — the
     * IGNORE in INSERT OR IGNORE skips already-present rows at the SQL level, but
     * JSON encoding still ran for every stroke before Fix #2a.  Tracking persisted
     * IDs cuts serialization work to zero for strokes already in the DB.
     *
     * Accessed from both the IO thread ([loadStrokesFromDb], [saveStrokes]) and the
     * main thread ([onStrokeErased], clear handler).  Access is sequential in normal
     * usage (navigation/save/erase are mutually exclusive user actions), so a plain
     * [MutableSet] is sufficient.  [saveStrokes] takes a snapshot before the
     * transaction to insulate against any edge-case main-thread mutation.
     */
    private val persistedStrokeIds = mutableSetOf<String>()

    /**
     * The template bitmap currently displayed on the canvas.  Kept in sync by [displayPage]
     * so the undo/redo optimised stroke path can pass it to [buildRenderBitmap] without
     * re-reading the DB.  Null = plain white background.
     */
    private var currentTemplateBitmap: Bitmap? = null

    // ── Undo/redo ─────────────────────────────────────────────────────────────

    /**
     * Notebook-level undo/redo history.  Replaced wholesale on [onStart] restoration;
     * cleared and file deleted on [closeNotebook].
     */
    private var undoRedoManager = UndoRedoManager()

    // ── Cover image picker ────────────────────────────────────────────────────

    /** Set by openCoverDialog(); called when the image picker returns a URI. */
    private var onCoverImagePicked: ((Uri) -> Unit)? = null

    private val coverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onCoverImagePicked?.invoke(uri)
    }

    // ── Template import launcher ──────────────────────────────────────────────

    /** Invoked on the main thread after a template file has been successfully imported. */
    private var onTemplateImportDone: (() -> Unit)? = null

    private val templateImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) performTemplateImport(uri)
    }

    // ── Copy/paste clipboard ──────────────────────────────────────────────────

    /** Page ID waiting to be pasted. Null means clipboard is empty. */
    private var pendingCopyPageId: String? = null

    // ── Lasso copy/paste toolbar state ───────────────────────────────────────

    /** Anchor point (in root-view coordinates) below btnLasso — computed once after layout. */
    private var lassoToolbarAnchor: PointF? = null

    // ── Page index launcher ───────────────────────────────────────────────────

    private val pageIndexLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data

            // Push paste actions recorded during the index session onto the undo stack.
            val pastedIds     = data?.getStringExtra(PageIndexActivity.EXTRA_PASTED_PAGE_IDS)
            val pastedIndices = data?.getStringExtra(PageIndexActivity.EXTRA_PASTED_PAGE_INDICES)
            if (!pastedIds.isNullOrEmpty() && !pastedIndices.isNullOrEmpty()) {
                val ids     = pastedIds.split(",")
                val indices = pastedIndices.split(",").mapNotNull { it.toIntOrNull() }
                ids.zip(indices).forEach { (pageId, pageIndex) ->
                    undoRedoManager.push(UndoRedoAction.PagePasted(pageId, pageIndex))
                }
            }

            // Push delete actions recorded during the index session onto the undo stack.
            val deletedIds        = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_IDS)
            val deletedIndices    = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_INDICES)
            val deletedTimestamps = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_TIMESTAMPS)
            if (!deletedIds.isNullOrEmpty() && !deletedIndices.isNullOrEmpty() && !deletedTimestamps.isNullOrEmpty()) {
                val ids        = deletedIds.split(",")
                val indices    = deletedIndices.split(",").mapNotNull { it.toIntOrNull() }
                val timestamps = deletedTimestamps.split(",").mapNotNull { it.toLongOrNull() }
                ids.zip(indices).zip(timestamps).forEach { (idIndex, ts) ->
                    undoRedoManager.push(UndoRedoAction.PageDeleted(idIndex.first, idIndex.second, ts))
                }
            }

            // Push move actions recorded during the index session onto the undo stack.
            val movedIds       = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_PAGE_IDS)
            val movedPrevAfter = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_PREV_AFTER_IDS)
            val movedTargets   = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_TARGET_IDS)
            if (!movedIds.isNullOrEmpty() && !movedTargets.isNullOrEmpty()) {
                val ids     = movedIds.split(",")
                val prevs   = movedPrevAfter?.split(",") ?: emptyList()
                val targets = movedTargets.split(",")
                ids.zip(targets).forEachIndexed { i, (pageId, targetId) ->
                    val prevAfterId = prevs.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    undoRedoManager.push(UndoRedoAction.PageMoved(pageId, prevAfterId, targetId))
                }
            }

            val anySessionActions = !pastedIds.isNullOrEmpty() || !deletedIds.isNullOrEmpty() || !movedIds.isNullOrEmpty()
            if (anySessionActions) updateUndoRedoButtons()

            val selected = data?.getIntExtra(PageIndexActivity.EXTRA_SELECTED_PAGE_INDEX, -1) ?: -1
            when {
                // User tapped a card — navigate to that page (forces full reload).
                selected >= 0 -> navigateToPage(selected)
                // No card tapped but session actions occurred — reload in place so the
                // pages list, page count, and canvas reflect the paste/delete/move changes.
                anySessionActions -> navigateToPage(currentPageIndex)
            }
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — equivalent to Flutter's SystemUiMode.immersiveSticky.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityDrawingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Toolbar ───────────────────────────────────────────────────────────
        binding.btnClose.setOnClickListener {
            closeNotebook()
            finish()
        }

        binding.btnInsertPageBefore.setOnClickListener {
            insertPageBeforeCurrentAndNavigate()
        }

        binding.btnInsertPageAfter.setOnClickListener {
            insertPageAfterCurrentAndNavigate()
        }

        binding.btnDeletePage.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            // Confirmation dialog — flat Notesprout style (elevation=0, shape_bordered).
            val dialog = AlertDialog.Builder(this)
                .setMessage("Delete this page?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    // Capture pageId, pageIndex, and deletedAt timestamp BEFORE the delete runs.
                    // All three soft-delete calls (page, layer, strokes) use the same timestamp
                    // so PageDeleted.undo can restore exactly those rows via restoreChildrenDeletedSince.
                    val deletedPageId    = currentPageId
                    val deletedPageIndex = currentPageIndex
                    val deletedAt        = System.currentTimeMillis()
                    lifecycleScope.launch {
                        // No snapshot needed — the page being deleted is discarded.
                        withContext(Dispatchers.IO) { deletePage(db, deletedAt) }
                        undoRedoManager.push(
                            UndoRedoAction.PageDeleted(deletedPageId, deletedPageIndex, deletedAt)
                        )
                        updateUndoRedoButtons()
                        drawingView.eraseAll()
                        val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                        displayPage(result)
                        updatePageIndicator()
                        saveLastOpenedPage(currentPageId)
                        postDisplayWork(db, result)
                    }
                }
                .create()
            dialog.show()
            // Style after show() — window only exists once the dialog is displayed.
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        }

        binding.btnEraseAll.setOnClickListener {
            val db = soilDatabase
            val dialog = AlertDialog.Builder(this)
                .setMessage("Erase this page?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Erase") { _, _ ->
                    // Snapshot the page/layer IDs now — they may change before the coroutine runs.
                    val eraseAllPageId  = currentPageId
                    val eraseAllLayerId = currentLayerId
                    val eraseAllHeadingIds = drawingView.getHeadings().map { it.id }
                    val hasContent = drawingView.getStrokes().isNotEmpty() ||
                                     eraseAllHeadingIds.isNotEmpty()
                    drawingView.eraseAll()
                    drawingView.loadHeadings(emptyList())
                    // All content removed from memory and will be soft-deleted from DB.
                    // Clear the persisted-ID registry; no snapshot needed for a user-initiated erase.
                    persistedStrokeIds.clear()
                    if (db != null && hasContent) {
                        lifecycleScope.launch {
                            val deletedAt = System.currentTimeMillis()
                            withContext(Dispatchers.IO) {
                                // Soft-delete all layer children (strokes, headings, any future types)
                                // with a shared timestamp so restoreChildrenDeletedSince recovers everything atomically.
                                db.notebookDao().softDeleteByParentId(eraseAllLayerId, deletedAt)
                                // Invalidate the snapshot: tryLoadSnapshotBitmap checks only type='stroke' rows
                                // for staleness, so a heading-only erase would leave the old snapshot live.
                                // Removing it forces the full render path on next navigation back.
                                invalidatePageSnapshot(db, eraseAllPageId)
                            }
                            // Record the erase as a single undoable action after the DB writes succeed.
                            // headingIds stored so undo can restore them explicitly by ID (belt-and-suspenders
                            // alongside restoreChildrenDeletedSince which uses a timestamp filter).
                            undoRedoManager.push(
                                UndoRedoAction.PageEraseAll(eraseAllPageId, eraseAllLayerId, deletedAt, eraseAllHeadingIds)
                            )
                            updateUndoRedoButtons()
                        }
                    }
                }
                .create()
            dialog.show()
            // Style after show() — window only exists once the dialog is displayed.
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        }

        // Pen tool button — activates pen mode (default)
        binding.btnPen.setOnClickListener {
            hideLassoPopupToolbar()
            if (isLassoMode) exitLassoMode()
            if (isLassoEraserMode) exitLassoEraserMode()
            if (isEraserActive) {
                isEraserActive = false
                drawingView.setEraserMode(false)
            }
            binding.btnPen.isSelected = true
            binding.btnEraser.isSelected = false
            binding.btnLassoEraser.isSelected = false
            binding.btnLasso.isSelected = false
        }

        binding.btnEraser.setOnClickListener {
            hideLassoPopupToolbar()
            if (isLassoMode) exitLassoMode()
            if (isLassoEraserMode) exitLassoEraserMode()
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnEraser.isSelected = isEraserActive
            binding.btnPen.isSelected = !isEraserActive
            binding.btnLassoEraser.isSelected = false
            binding.btnLasso.isSelected = false
        }

        binding.btnLassoEraser.setOnClickListener {
            hideLassoPopupToolbar()
            if (!isLassoEraserMode) enterLassoEraserMode()
            // Tapping the active lasso eraser button is a no-op.
        }

        binding.btnLasso.setOnClickListener {
            if (isLassoEraserMode) exitLassoEraserMode()
            if (!isLassoMode) {
                enterLassoMode()
            } else {
                // Already in lasso mode — toggle popup if clipboard has content.
                if (NotesproutClipboard.hasContent()) {
                    if (binding.lassoPopupToolbar.visibility == View.VISIBLE) {
                        hideLassoPopupToolbar()
                    } else {
                        updateLassoPopupToolbar()
                    }
                }
                // Empty clipboard: silent no-op.
            }
        }

        // TODO: implement toolbar show/hide UX
        binding.btnTemplate.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return@setOnClickListener
            val notebookId = notebookMetadata?.id ?: MainActivity.NIL_UUID
            TemplateDialog(
                activity        = this,
                lifecycleScope  = lifecycleScope,
                db              = db,
                pageId          = pageId,
                notebookId      = notebookId,
                onConfirm       = { templateId, bitmap ->
                    applyTemplateToCurrentPage(templateId, bitmap)
                },
                onRequestImport = { onDone ->
                    onTemplateImportDone = onDone
                    templateImportLauncher.launch(arrayOf("image/png"))
                },
            ).show()
        }

        binding.btnToc.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            lifecycleScope.launch {
                val entries = withContext(Dispatchers.IO) {
                    TocRepository(db.notebookDao()).buildTocEntries()
                }
                TocDialog(
                    context = this@DrawingActivity,
                    entries = entries,
                    currentPageIndex = currentPageIndex,
                    onPageSelected = { pageId ->
                        val index = pages.indexOfFirst { it.id == pageId }
                        if (index >= 0 && index != currentPageIndex) navigateToPage(index)
                    }
                ).show()
            }
        }

        binding.btnCover.setOnClickListener {
            openCoverDialog()
        }

        binding.btnExport.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            startExport(db)
        }

        binding.btnPageIndex.setOnClickListener { openPageIndex() }
        binding.tvPageIndicator.setOnClickListener { openPageIndex() }

        binding.btnCopyPage.setOnClickListener { copyCurrentPage() }
        binding.btnPastePage.setOnClickListener { pasteCopiedPage() }
        updateCopyPasteButtons()

        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }
        updateUndoRedoButtons()  // both disabled initially (empty stacks)

        // Initial tool state: pen is selected by default
        binding.btnPen.isSelected = true
        binding.btnEraser.isSelected = false
        binding.btnLassoEraser.isSelected = false
        binding.btnLasso.isSelected = false

        // ── Back press ────────────────────────────────────────────────────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeNotebook()
                finish()
            }
        })

        // ── Drawing view ──────────────────────────────────────────────────────
        drawingView = if (isBooxDevice()) OnyxDrawingView(this) else GenericDrawingView(this)

        // Erase callback — soft-delete the stroke's DB row as soon as it leaves memory.
        drawingView.onStrokeErased = { strokeId ->
            // Remove from the persisted-ID registry so saveStrokes doesn't try to
            // re-insert a stroke that has already been soft-deleted from the DB.
            persistedStrokeIds.remove(strokeId)
            val db = soilDatabase
            val pageId  = currentPageId
            val layerId = currentLayerId
            if (db != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.notebookDao().softDeleteById(strokeId, System.currentTimeMillis())
                    }
                    // Record undo action after the DB write completes.
                    if (pageId.isNotEmpty() && layerId.isNotEmpty()) {
                        undoRedoManager.push(UndoRedoAction.StrokeErased(pageId, layerId, strokeId))
                        updateUndoRedoButtons()
                    }
                }
            }
        }

        // Heading erase callback — the view has already removed the heading from its in-memory
        // list before this fires. Persist the delete, push an undo action, and rebuild the
        // bitmap so the EPD panel reflects the erased heading.
        drawingView.onHeadingErased = { heading ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                // Deep-copy before any async work so the undo action holds stable data.
                val capturedHeading = HeadingStroke(
                    heading.id, RectF(heading.boundingBox),
                    heading.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(heading.id, deletedAt)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds  = emptyList(),
                                headingIds = listOf(heading.id),
                                pageId     = pageId,
                                headings   = listOf(capturedHeading),
                            ))
                            updateUndoRedoButtons()
                        }
                        val strokes = drawingView.getStrokes()
                        val templateBmp = currentTemplateBitmap
                        val bitmap = withContext(Dispatchers.IO) {
                            drawingView.buildRenderBitmap(strokes, templateBmp, drawingView.getHeadings())
                        }
                        if (bitmap != null) {
                            drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
                        } else {
                            drawingView.loadStrokes(strokes)
                        }
                    }
                }
            }
        }

        // Persist new strokes immediately after each pen lift.
        // The EPD overlay stays active during writing; this is purely a DB save trigger.
        drawingView.onPenLifted = {
            val db = soilDatabase
            if (db != null) {
                lifecycleScope.launch {
                    val newIds = withContext(Dispatchers.IO) { saveStrokes(db) }
                    // Push one StrokeAdded per newly persisted stroke.
                    val pageId  = currentPageId
                    val layerId = currentLayerId
                    if (pageId.isNotEmpty() && layerId.isNotEmpty()) {
                        for (id in newIds) {
                            undoRedoManager.push(UndoRedoAction.StrokeAdded(pageId, layerId, id))
                        }
                        if (newIds.isNotEmpty()) updateUndoRedoButtons()
                    }
                }
            }
        }

        // Snapshot callback — fired by the drawing view at non-writing transitions
        // (eraser mode, template change, window focus loss).  Persist to the page's
        // data JSON so the next page load can use the fast snapshot path.
        drawingView.onSnapshotReady = { snapshot ->
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null && pageId.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { persistSnapshot(db, pageId, snapshot) }
                }
            }
        }

        // Lasso gesture complete — auto-close the path and run hit test off main thread.
        drawingView.onLassoComplete = { drawnPath, startPoint ->
            // Dismiss popup whenever a lasso gesture completes.
            hideLassoPopupToolbar()

            // Auto-close: straight line from end back to start, then close polygon.
            drawnPath.lineTo(startPoint.x, startPoint.y)
            drawnPath.close()

            selectedObjectIds.clear()
            val strokeSnapshot  = drawingView.getStrokes()
            val headingSnapshot = drawingView.getHeadings()
            lifecycleScope.launch(Dispatchers.Default) {
                val lassoBounds = RectF()
                drawnPath.computeBounds(lassoBounds, true)

                // Minimum size guard: ignore accidental taps / trivially small paths.
                val density = resources.displayMetrics.density
                val minPx = 10f * density
                if (lassoBounds.width() < minPx && lassoBounds.height() < minPx) return@launch

                // Build a Region from the closed lasso polygon for point-in-polygon tests.
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
                    // Phase 1: AABB pre-filter — O(1) per stroke.
                    if (!RectF.intersects(lassoBounds, stroke.boundingBox)) continue
                    // Phase 2: any stroke point inside the lasso polygon?
                    for (pt in stroke.points) {
                        if (lassoRegion.contains(pt.x.toInt(), pt.y.toInt())) {
                            hitIds.add(stroke.id)
                            unionBounds.union(stroke.boundingBox)
                            break
                        }
                    }
                }

                // Heading hit-test: center-point containment within the lasso polygon.
                for (heading in headingSnapshot) {
                    if (!RectF.intersects(lassoBounds, heading.boundingBox)) continue
                    val cx = heading.boundingBox.centerX().toInt()
                    val cy = heading.boundingBox.centerY().toInt()
                    if (lassoRegion.contains(cx, cy)) {
                        hitIds.add(heading.id)
                        unionBounds.union(heading.boundingBox)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (hitIds.isEmpty()) return@withContext  // nothing caught — stay in lasso mode idle
                    selectedObjectIds.clear()
                    selectedObjectIds.addAll(hitIds)
                    drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                    // Pad the bounding box slightly so the dashed rect doesn't sit on the ink.
                    val pad = 8f * resources.displayMetrics.density
                    unionBounds.inset(-pad, -pad)
                    drawingView.setLassoOverlay(null, unionBounds)
                    updateFloatingSelectionToolbar(unionBounds)
                }
            }
        }

        // Tap to dismiss: clear the selection visual but stay in lasso mode.
        drawingView.onLassoTapToDismiss = {
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
        }

        // Stylus tap in lasso mode — trigger paste if clipboard has content and no selection.
        drawingView.onLassoTap = tap@{ tapX, tapY ->
            // Heading text edit tap — checked first, before paste or dismiss logic.
            if (selectedObjectIds.size == 1) {
                val selectedId = selectedObjectIds.first()
                val selectedHeading = drawingView.getHeadings().find {
                    it.id == selectedId && it.recognizedText != null
                }
                if (selectedHeading != null && selectedHeading.boundingBox.contains(tapX, tapY)) {
                    showHeadingTextEditDialog(selectedHeading)
                    return@tap
                }
            }
            if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) {
                hideLassoPopupToolbar()
                performLassoPaste(tapX, tapY)
            }
        }

        // Drag threshold crossed — hide the floating toolbar during the drag move.
        drawingView.onDragStarted = {
            hideFloatingSelectionToolbar()
        }

        // Fresh lasso gesture started (cleared old selection) — hide the floating toolbar.
        drawingView.onLassoSelectionCleared = {
            hideFloatingSelectionToolbar()
        }

        // Lasso eraser gesture complete — soft-delete hit strokes/headings and push undo action.
        drawingView.onLassoEraseComplete = { erasedIds ->
            val pageId = currentPageId.takeIf { it.isNotEmpty() }
            if (erasedIds.isNotEmpty() && pageId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    erasedIds.forEach { id ->
                        soilDatabase?.notebookDao()?.softDeleteById(id, now)
                    }
                    withContext(Dispatchers.Main) {
                        val erasedSet = erasedIds.toSet()
                        // Partition into heading IDs vs stroke IDs.
                        val erasedHeadings = drawingView.getHeadings().filter { it.id in erasedSet }
                        val erasedHeadingIds = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                        val capturedHeadings = erasedHeadings.map { h ->
                            HeadingStroke(h.id, RectF(h.boundingBox),
                                h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) })
                        }
                        val updatedStrokes  = drawingView.getStrokes().filter { it.id !in erasedSet }
                        val updatedHeadings = drawingView.getHeadings().filter { it.id !in erasedHeadingIds }
                        persistedStrokeIds.removeAll(erasedSet - erasedHeadingIds)
                        drawingView.setStrokeListSilently(updatedStrokes)
                        drawingView.loadHeadings(updatedHeadings)
                        undoRedoManager.push(UndoRedoAction.LassoErased(
                            strokeIds  = erasedIds.toList(),
                            pageId     = pageId,
                            headingIds = erasedHeadingIds.toList(),
                            headings   = capturedHeadings,
                        ))
                        updateUndoRedoButtons()
                        val templateBmp = currentTemplateBitmap
                        val bitmap = withContext(Dispatchers.IO) {
                            drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
                        }
                        if (bitmap != null) {
                            drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                        } else {
                            drawingView.loadStrokes(updatedStrokes)
                        }
                    }
                }
            }
        }

        // Lasso move gesture complete — persist translated coordinates and push undo action.
        drawingView.onStrokesMoved = { originalStrokes, movedStrokes, originalHeadings, movedHeadings ->
            val pageId = currentPageId.takeIf { it.isNotEmpty() }
            val db = soilDatabase
            if (pageId != null && db != null) {
                lifecycleScope.launch {
                    val now = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            for (moved in movedStrokes) {
                                val strokePoints = moved.points.map { pt ->
                                    StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = now)
                                }
                                val strokeData = StrokeData(
                                    color = "#000000",
                                    strokeWidth = 3.0f,
                                    points = strokePoints,
                                )
                                db.notebookDao().updateStrokeData(moved.id, strokeData.toJson(), now)
                            }
                            for (heading in movedHeadings) {
                                val bboxJson = """{"x":${heading.boundingBox.left},"y":${heading.boundingBox.top},"width":${heading.boundingBox.width()},"height":${heading.boundingBox.height()}}"""
                                db.notebookDao().updateHeadingData(
                                    heading.id, bboxJson, HeadingObject(heading.strokes, heading.recognizedText).toJson(), now
                                )
                            }
                        }
                    }
                    undoRedoManager.push(
                        UndoRedoAction.StrokesMoved(
                            pageId,
                            originalStrokes.toList(),
                            movedStrokes.toList(),
                            originalHeadings = originalHeadings.toList(),
                            movedHeadings = movedHeadings.toList(),
                        )
                    )
                    updateUndoRedoButtons()
                    // Reshow floating toolbar at the new selection box position after the move.
                    val newBox = computeUnionBoundingBox(movedStrokes, movedHeadings)
                    val pad = 8f * resources.displayMetrics.density
                    newBox.inset(-pad, -pad)
                    updateFloatingSelectionToolbar(newBox)
                }
            }
        }

        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Wire the floating selection toolbar Copy and Cut buttons.
        binding.btnLassoCopy.setOnClickListener {
            performLassoCopy()
        }

        binding.btnLassoCut.setOnClickListener {
            performLassoCut()
        }

        binding.btnLassoDelete.setOnClickListener {
            performLassoDelete()
        }

        binding.btnMakeHeading.setOnClickListener {
            val ids = drawingView.lassoSelectedIds
            val selectedStrokes = drawingView.getStrokes().filter { it.id in ids }
            if (selectedStrokes.isEmpty()) return@setOnClickListener
            val box = RectF()
            selectedStrokes.forEach { box.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            box.inset(-pad, -pad)
            lifecycleScope.launch(Dispatchers.IO) {
                createHeadingFromStrokes(selectedStrokes, box)
            }
        }

        binding.btnUnheading.setOnClickListener {
            val heading = selectedHeadings.firstOrNull() ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                removeHeading(heading)
            }
        }

        // Wire the lasso popup Clear Clipboard button.
        binding.btnLassoClearClipboard.setOnClickListener {
            NotesproutClipboard.clear()
            updateLassoButtonIcon()
            hideLassoPopupToolbar()
        }

        // Compute the lasso popup anchor point once the toolbar has been laid out.
        binding.drawingToolbar.doOnLayout {
            computeLassoToolbarAnchor()
        }

        // Pass the toolbar's pixel height to the drawing view after layout so
        // BOOX can set the correct setLimitRect exclusion zone.
        binding.drawingToolbar.doOnLayout { toolbar ->
            drawingView.setToolbarHeight(toolbar.height)
        }

        // ── Page swipe gesture (one-finger, deliberate) ──────────────────────
        // Page navigation requires a deliberate full-width horizontal finger swipe.
        // Three guards gate the gesture: minimum distance (screen-width relative),
        // minimum velocity (1.5× system fling threshold), and horizontal dominance.
        // A second finger cancels the gesture so palm+finger combos never fire.
        //
        // State machine:
        //   ACTION_DOWN          → arm tracker; record start position
        //   ACTION_POINTER_DOWN  → second finger → cancel gesture
        //   ACTION_MOVE          → feed tracker while active
        //   ACTION_UP            → compute velocity + displacement; evaluate
        //   ACTION_CANCEL        → reset
        // No-op on init — handled entirely in dispatchTouchEvent / helpers below.

        // ── Open the Room DB ──────────────────────────────────────────────────
        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH)
        if (notebookPath != null) {
            sessionStartTime = System.currentTimeMillis()
            soilDatabase = Room.databaseBuilder(
                applicationContext,
                SoilDatabase::class.java,
                notebookPath,
            )
                .addCallback(SoilDatabase.openCallback())
                .build()
        }

        loadStrokes()
    }

    override fun onStart() {
        super.onStart()
        // Restore undo/redo state if the app was backgrounded with a non-empty history.
        val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
        val file = undoRedoPersistenceFile(path)
        if (file.exists()) {
            try {
                undoRedoManager = UndoRedoManager.fromJson(file.readText())
                updateUndoRedoButtons()
            } catch (e: Exception) {
                Log.e(TAG, "onStart: failed to restore undo/redo state", e)
            } finally {
                file.delete()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist undo/redo stacks so they survive process death when the app is backgrounded.
        if (!undoRedoManager.isEmpty()) {
            val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
            try {
                undoRedoPersistenceFile(path).writeText(undoRedoManager.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "onStop: failed to persist undo/redo state", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        drawingView.enableDrawing()
        updateLassoButtonIcon()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
        // Safety net: if the activity is destroyed without the user tapping Close
        // (e.g. system kill), ensure the DB is still closed cleanly. Seal synchronously
        // (blocking) here — there's no surviving UI to defer to and the process may be
        // dying. In the normal close path soilDatabase is already null, so this no-ops.
        closeNotebook(blocking = true)
    }

    /**
     * Feed every touch event to the page swipe detector before normal dispatch.
     *
     * This is required because on BOOX the Onyx TouchHelper consumes stylus events
     * at the view level and never bubbles them back to a parent touch listener.
     * Running the detector here means we always see DOWN / POINTER_DOWN events
     * regardless of which child consumed intermediate move events.  We do not
     * consume events ourselves (return super), so the drawing view receives everything
     * it expects.
     *
     * Only finger events are forwarded — stylus and eraser events belong to the
     * drawing engine exclusively.  A pen swipe must never turn the page.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            handlePageSwipe(event)
        }
        // Dismiss the lasso popup toolbar on any touch outside its bounds.
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && binding.lassoPopupToolbar.visibility == View.VISIBLE) {
            val loc = IntArray(2)
            binding.lassoPopupToolbar.getLocationOnScreen(loc)
            val popupRect = Rect(
                loc[0], loc[1],
                loc[0] + binding.lassoPopupToolbar.width,
                loc[1] + binding.lassoPopupToolbar.height,
            )
            if (!popupRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                hideLassoPopupToolbar()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * One-finger deliberate page-turn detector.
     *
     * Arms on the first finger down; fires at ACTION_UP if the gesture passes
     * all three guards in [evaluatePageFling].  A second finger immediately
     * cancels so palm+finger combos and pinch-style gestures never turn pages.
     */
    private fun handlePageSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Single finger down — arm and record the start position.
                pageSwipeActive = true
                pageSwipeStartX = event.x
                pageSwipeStartY = event.y
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Cancel the 1-finger gesture unconditionally.
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
                // Arm 2-finger insert gesture when exactly two fingers are down.
                if (event.pointerCount == 2) {
                    twoFingerSwipeActive = true
                    twoFingerSwipeStartX = (event.getX(0) + event.getX(1)) / 2f
                    twoFingerSwipeStartY = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerSwipeVelocityTracker?.recycle()
                    twoFingerSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                } else {
                    // 3+ fingers — cancel.
                    twoFingerSwipeActive = false
                    twoFingerSwipeVelocityTracker?.recycle()
                    twoFingerSwipeVelocityTracker = null
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted from a 2-finger gesture — evaluate and clean up.
                if (twoFingerSwipeActive && event.pointerCount == 2) {
                    val tracker = twoFingerSwipeVelocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val remainingIndex = 1 - event.actionIndex
                        val endX = event.getX(remainingIndex)
                        val endY = event.getY(remainingIndex)
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        val dx = endX - twoFingerSwipeStartX
                        val dy = endY - twoFingerSwipeStartY
                        evaluateTwoFingerInsertFling(vx, vy, dx, dy)
                        tracker.recycle()
                        twoFingerSwipeVelocityTracker = null
                    }
                    twoFingerSwipeActive = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageSwipeActive) {
                    pageSwipeVelocityTracker?.addMovement(event)
                }
                if (twoFingerSwipeActive && event.pointerCount >= 2) {
                    twoFingerSwipeVelocityTracker?.addMovement(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pageSwipeActive) {
                    val tracker = pageSwipeVelocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        val dx = event.x - pageSwipeStartX
                        val dy = event.y - pageSwipeStartY
                        evaluatePageFling(vx, vy, dx, dy)
                        tracker.recycle()
                        pageSwipeVelocityTracker = null
                    }
                }
                pageSwipeActive = false
                twoFingerSwipeActive = false
                twoFingerSwipeVelocityTracker?.recycle()
                twoFingerSwipeVelocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
                twoFingerSwipeActive = false
                twoFingerSwipeVelocityTracker?.recycle()
                twoFingerSwipeVelocityTracker = null
            }
        }
    }

    /**
     * Applies three guards and navigates pages if the gesture qualifies.
     *
     * Guard 1 — minimum distance: ≥50% of screen width.
     * Guard 2 — minimum velocity: 1.5× [ViewConfiguration.scaledMinimumFlingVelocity].
     * Guard 3 — horizontal dominance: |dx| > |dy|.
     *
     * Swipe left (dx < 0) → next page (or insert new page on last page).
     * Swipe right (dx > 0) → previous page.
     */
    private fun evaluatePageFling(velocityX: Float, velocityY: Float, dx: Float, dy: Float) {
        // Guard 3: displacement must be predominantly horizontal.
        if (Math.abs(dx) <= Math.abs(dy)) return
        // Guard 2: must meet 1.5× system minimum fling velocity.
        val minVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity * 1.5f
        if (Math.abs(velocityX) < minVelocity) return
        // Guard 1: minimum distance relative to screen width.
        val dm = resources.displayMetrics
        val screenWidthPx = dm.widthPixels.toFloat()
        if (Math.abs(dx) < 0.50f * screenWidthPx) return

        if (velocityX < 0) {
            // Swipe left → advance to next page.
            val next = currentPageIndex + 1
            if (next <= pages.lastIndex) {
                navigateToPage(next)
            } else {
                // Already on the last page — insert a new page (same as the + button).
                val db = soilDatabase ?: return
                lifecycleScope.launch {
                    val snapshot = drawingView.captureSnapshot()
                    val leavingPageId = currentPageId
                    withContext(Dispatchers.IO) {
                        if (snapshot != null && leavingPageId.isNotEmpty()) {
                            persistSnapshot(db, leavingPageId, snapshot)
                        }
                        addPage(db)
                    }
                    undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex))
                    updateUndoRedoButtons()
                    selectedObjectIds.clear()
                    drawingView.setLassoOverlay(null, null)
                    hideFloatingSelectionToolbar()
                    drawingView.eraseAll()
                    val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                    displayPage(result)
                    updatePageIndicator()
                    saveLastOpenedPage(currentPageId)
                    postDisplayWork(db, result)
                }
            }
        } else {
            // Swipe right → go back to previous page.
            val prev = currentPageIndex - 1
            if (prev >= 0) navigateToPage(prev)
        }
    }

    /**
     * Applies the same three guards as [evaluatePageFling] and inserts a new page
     * relative to the current one.
     *
     * Swipe left (dx < 0) → insert AFTER the current page, navigate to it.
     * Swipe right (dx > 0) → insert BEFORE the current page, navigate to it.
     */
    private fun evaluateTwoFingerInsertFling(velocityX: Float, velocityY: Float, dx: Float, dy: Float) {
        if (Math.abs(dx) <= Math.abs(dy)) return
        val minVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity * 1.5f
        if (Math.abs(velocityX) < minVelocity) return
        val screenWidthPx = resources.displayMetrics.widthPixels.toFloat()
        if (Math.abs(dx) < 0.50f * screenWidthPx) return

        if (dx < 0) {
            insertPageAfterCurrentAndNavigate()
        } else {
            insertPageBeforeCurrentAndNavigate()
        }
    }

    private fun insertPageAfterCurrentAndNavigate() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val snapshot = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                addPage(db)
            }
            undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex))
            updateUndoRedoButtons()
            drawingView.eraseAll()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    private fun insertPageBeforeCurrentAndNavigate() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val snapshot = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                addPageBefore(db)
            }
            undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex, insertedBefore = true))
            updateUndoRedoButtons()
            drawingView.eraseAll()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    // ── Notebook DB lifecycle ─────────────────────────────────────────────────

    /**
     * Tears down the open notebook: captures a final snapshot, then seals the file
     * (save strokes, housekeeping PRAGMAs, close the Room DB, delete the stray -journal).
     *
     * Snapshot is captured here on the main thread (before [soilDatabase] is cleared) so
     * the close button and back-press paths always persist the current page state.
     * [onWindowFocusChanged] fires AFTER [finish], so its [onSnapshotReady] callback would
     * find [soilDatabase] already null — capturing here is the only reliable close-path hook.
     *
     * The heavy seal ([sealNotebook]) runs on [Dispatchers.IO]:
     * - [blocking] = false (user-initiated close): launched on [NotesproutApplication.appScope]
     *   so it outlives this activity. [finish] fires immediately, off the seal — no ANR/jank
     *   on large notebooks. The null-guard below makes the later [onDestroy] call a no-op, so
     *   exactly one seal runs per notebook instance.
     * - [blocking] = true ([onDestroy] safety net on an abnormal teardown): sealed synchronously
     *   via [runBlocking] so the file is sealed before the process can die. Only reached when no
     *   user-initiated close ran first (otherwise [soilDatabase] is already null → early return).
     *
     * Idempotent — guarded by a null check.
     */
    private fun closeNotebook(blocking: Boolean = false) {
        val db = soilDatabase ?: return
        soilDatabase = null   // mark as closed before any potentially-throwing work

        // Clear history, clipboard, and remove any on-disk persistence file — notebook is done.
        undoRedoManager.clear()
        NotesproutClipboard.clear()
        val nbPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH)
        if (nbPath != null) undoRedoPersistenceFile(nbPath).takeIf { it.exists() }?.delete()

        // Capture snapshot on the main thread — View operations must run here.
        // Reading the strokes for the seal is thread-safe: getStrokes() returns a copy and
        // releaseResources() never mutates the stroke list, so the async seal can read it
        // even after onDestroy has run.
        val snapshot = drawingView.captureSnapshot()
        val pageId   = currentPageId

        val sessionStart = sessionStartTime
        if (blocking) {
            runBlocking { sealNotebook(db, snapshot, pageId, nbPath, sessionStart) }
        } else {
            NotesproutApplication.appScope.launch { sealNotebook(db, snapshot, pageId, nbPath, sessionStart) }
        }
    }

    /**
     * Heavy file-seal: persist the page snapshot + any new strokes, run housekeeping PRAGMAs,
     * close the Room database, and delete the stray -journal Android leaves during DB init.
     * All IO — runs on [Dispatchers.IO] regardless of the calling dispatcher.
     */
    private suspend fun sealNotebook(
        db: SoilDatabase,
        snapshot: String?,
        pageId: String,
        nbPath: String?,
        sessionStart: Long,
    ) = withContext(Dispatchers.IO) {
        // Persist snapshot for the page we are closing (mirrors navigateToPage).
        if (snapshot != null && pageId.isNotEmpty()) {
            persistSnapshot(db, pageId, snapshot)
        }
        saveStrokes(db)
        // Hard-delete rows soft-deleted before this session — they predate the undo stack
        // and can never be restored, so they are dead weight. Current-session soft-deletes
        // (deletedAt >= sessionStart) are kept for undo/redo safety on abnormal teardown.
        db.notebookDao().hardDeleteOldSoftDeleted(before = sessionStart)
        db.openHelper.writableDatabase.apply {
            query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
            query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        db.close()
        if (nbPath != null) File("$nbPath-journal").takeIf { it.exists() }?.delete()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Launch a coroutine that loads the current page and hands it to the drawing view.
     * Also restores the last-opened page position on first call (notebook open).
     *
     * Fast path: if the page's stored snapshot is valid (no strokes changed since it was
     * captured), the composite bitmap is decoded and displayed immediately — stroke
     * deserialization happens in the background ([postDisplayWork]).
     *
     * Full path: strokes are deserialized, a render bitmap is built off-thread, then
     * displayed.  A snapshot is captured and persisted after display so the NEXT load
     * can use the fast path.
     */
    private fun loadStrokes() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Restore last-opened page position (first open only).
                notebookMetadata = loadNotebookMetadataFromDb(db)
                val lastPage = notebookMetadata?.lastOpenedPage
                if (lastPage != null) {
                    val allPages = db.notebookDao().getPagesSorted()
                    val idx = allPages.indexOfFirst { it.id == lastPage }
                    if (idx >= 0) currentPageIndex = idx
                    // If idx == -1 (page deleted), currentPageIndex = 0 is the safe fallback.
                }
                loadCurrentPage(db)
            }
            displayPage(result)
            updatePageIndicator()
            postDisplayWork(db, result)
        }
    }

    /**
     * Refresh [pages], [currentPageId], and [currentLayerId] from the database using
     * [currentPageIndex] as the target.  Does NOT load or deserialize stroke data.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun setupPageIds(db: SoilDatabase) {
        val dao = db.notebookDao()
        pages = dao.getPagesSorted().toMutableList()
        if (pages.isEmpty()) {
            Log.w(TAG, "setupPageIds: no pages in notebook")
            currentPageId = ""; currentLayerId = ""; return
        }
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        Slog.d(TAG) { "setupPageIds: page $currentPageId (${currentPageIndex + 1}/${pages.size})" }
        val layer = dao.getLayerForPage(currentPageId)
        if (layer == null) {
            Log.w(TAG, "setupPageIds: no layer for page $currentPageId")
            currentLayerId = ""; return
        }
        currentLayerId = layer.id
        Slog.d(TAG) { "setupPageIds: layerId=$currentLayerId" }
    }

    /**
     * Deserialize stroke rows for [currentLayerId] and return them as [LiveStroke]s.
     * Also repopulates [persistedStrokeIds].
     * Must be called on [Dispatchers.IO] AFTER [setupPageIds] has set [currentLayerId].
     */
    private suspend fun deserializeStrokesFromDb(db: SoilDatabase): List<LiveStroke> {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return emptyList()
        val strokeObjects = db.notebookDao().getStrokesForLayer(layerId)
        Slog.d(TAG) { "deserializeStrokesFromDb: found ${strokeObjects.size} rows" }
        val result = strokeObjects.mapNotNull { obj ->
            try {
                LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs())
            } catch (e: Exception) {
                Log.e(TAG, "deserializeStrokesFromDb: failed to parse stroke ${obj.id}", e)
                null
            }
        }
        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(result.map { it.id })
        return result
    }

    /**
     * Try to build a composite display bitmap (white → template → snapshot PNG) for the
     * current page.  Returns null if the snapshot is absent, stale, or the view isn't
     * laid out yet — callers fall through to the full render path.
     * Must be called on [Dispatchers.IO] AFTER [setupPageIds].
     */
    private suspend fun tryLoadSnapshotBitmap(db: SoilDatabase, templateBitmap: Bitmap?): Bitmap? {
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return null
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return null

        val page    = db.notebookDao().getObjectById(pageId) ?: return null
        val dataObj = try { org.json.JSONObject(page.data) } catch (e: Exception) { return null }
        val b64     = dataObj.optString("snapshot", "").takeIf { it.isNotEmpty() } ?: return null

        // Stale check: any stroke (including soft-deleted) updated after the snapshot?
        // Soft-deleted rows have updatedAt = deletedAt, so erased strokes are also detected.
        val maxStroke = db.notebookDao().getMaxContentUpdatedAt(layerId) ?: 0L
        if (maxStroke > page.updatedAt) {
            Slog.d(TAG) { "tryLoadSnapshotBitmap: stale (maxStroke=$maxStroke > page=${page.updatedAt})" }
            return null
        }

        val w = drawingView.asView().width
        val h = drawingView.asView().height
        if (w == 0 || h == 0) return null

        val bytes       = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return null }
        // Bounded decode (M-1): cap to the view size so a crafted/oversized snapshot can't OOM.
        val snapshotBmp = BitmapDecode.decodeSampled(bytes, w, h) ?: return null

        // Build composite: white → template → strokes-only snapshot (transparent PNG)
        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(composite)
        c.drawColor(Color.WHITE)
        templateBitmap?.let { c.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        c.drawBitmap(snapshotBmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        snapshotBmp.recycle()

        Slog.d(TAG) { "tryLoadSnapshotBitmap: hit for page $pageId" }
        return composite
    }

    /**
     * Load the current page with optional snapshot fast path.  Calls [setupPageIds] to
     * resolve the current page/layer IDs from [currentPageIndex].
     *
     * Fast path (snapshot valid): returns a [PageLoadResult] with empty [strokes] and
     * a composite [displayBitmap] ready for immediate display.  Stroke deserialization
     * is deferred to [postDisplayWork].
     *
     * Full path (no/stale snapshot): deserializes strokes, builds render bitmap, returns
     * fully populated [PageLoadResult].  [postDisplayWork] captures and persists a fresh
     * snapshot after display.
     *
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadCurrentPage(db: SoilDatabase): PageLoadResult {
        setupPageIds(db)
        val templateBitmap  = loadPageTemplateFromDb(db)
        val headings        = loadHeadingsFromDb(db, currentLayerId)
        val snapshotBitmap  = tryLoadSnapshotBitmap(db, templateBitmap)
        return if (snapshotBitmap != null) {
            PageLoadResult(emptyList(), templateBitmap, snapshotBitmap, usedSnapshot = true, headings = headings)
        } else {
            val strokes      = deserializeStrokesFromDb(db)
            val renderBitmap = drawingView.buildRenderBitmap(strokes, templateBitmap, headings)
            PageLoadResult(strokes, templateBitmap, renderBitmap, usedSnapshot = false, headings = headings)
        }
    }

    /**
     * Load heading rows for [layerId] from [db] and convert them to render-time [HeadingStroke]s.
     * Rows with missing or malformed `boundingBox` or `data` are silently skipped.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadHeadingsFromDb(db: SoilDatabase, layerId: String): List<HeadingStroke> {
        if (layerId.isEmpty()) return emptyList()
        val rows = db.notebookDao().getHeadingsForLayer(layerId)
        return rows.mapNotNull { row ->
            val box = row.parseBoundingBox() ?: return@mapNotNull null
            val headingObj = runCatching { HeadingObject.fromJson(row.data) }.getOrNull()
                ?: return@mapNotNull null
            HeadingStroke(id = row.id, boundingBox = box, strokes = headingObj.strokes, recognizedText = headingObj.recognizedText)
        }
    }

    private fun NotebookObject.parseBoundingBox(): android.graphics.RectF? {
        return try {
            val obj = org.json.JSONObject(boundingBox)
            val x = obj.getDouble("x").toFloat()
            val y = obj.getDouble("y").toFloat()
            val w = obj.getDouble("width").toFloat()
            val h = obj.getDouble("height").toFloat()
            android.graphics.RectF(x, y, x + w, y + h)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply a [PageLoadResult] to the drawing view on the main thread.
     * Also keeps [currentTemplateBitmap] in sync for the undo/redo optimised stroke path.
     */
    private fun displayPage(result: PageLoadResult) {
        currentTemplateBitmap = result.templateBitmap
        drawingView.loadHeadings(result.headings)
        val bitmap = result.displayBitmap
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(result.strokes, bitmap, result.templateBitmap)
        } else {
            drawingView.setTemplate(result.templateBitmap)
            drawingView.loadStrokes(result.strokes)
        }
    }

    /**
     * Work to run after [displayPage] returns, depending on which load path was taken.
     *
     * Snapshot path ([PageLoadResult.usedSnapshot] = true): deserialize strokes in the
     * background and silently inject them into the drawing view — no visual redraw since
     * the snapshot composite is already displayed.
     *
     * Full path: capture and persist a snapshot so the next load can use the fast path.
     *
     * Must be called on the main thread (launches IO coroutines internally).
     */
    private fun postDisplayWork(db: SoilDatabase, result: PageLoadResult) {
        if (result.usedSnapshot) {
            // Background stroke deserialization — strokes needed for erase / export / save.
            val pageId = currentPageId
            lifecycleScope.launch {
                val strokes = withContext(Dispatchers.IO) { deserializeStrokesFromDb(db) }
                Slog.d(TAG) { "postDisplayWork(snapshot): silently loaded ${strokes.size} strokes for $pageId" }
                drawingView.setStrokeListSilently(strokes)
            }
        } else {
            // Full render just completed — capture snapshot for next time.
            val snapshot = drawingView.captureSnapshot()
            val pageId   = currentPageId
            if (snapshot != null && pageId.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { persistSnapshot(db, pageId, snapshot) }
                    Slog.d(TAG) { "postDisplayWork(full): persisted snapshot for $pageId" }
                }
            }
        }
    }

    /**
     * Read the current page's `template` property, look up the template row, and decode
     * its base64 image to a Bitmap.  Returns null if the page has no template (blank).
     *
     * Must be called on [Dispatchers.IO]. Uses [currentPageId] set by [loadStrokesFromDb].
     */
    private suspend fun loadPageTemplateFromDb(db: SoilDatabase): Bitmap? {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return null

        val page = db.notebookDao().getObjectById(pageId) ?: return null

        val templateId = TemplateDialog.parseTemplateId(page.data).takeIf { it.isNotEmpty() } ?: return null

        val templateObj = db.notebookDao().getTemplateById(templateId) ?: return null

        return try {
            val dataObj = JSONObject(templateObj.data)
            val b64 = dataObj.getString("image")
            val bytes = Base64.decode(b64, android.util.Base64.DEFAULT)
            // Bounded decode (M-1): cap to the view size (fall back to MAX_DIMENSION before layout).
            val view = drawingView.asView()
            val reqW = view.width.takeIf  { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            val reqH = view.height.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            BitmapDecode.decodeSampled(bytes, reqW, reqH)
        } catch (e: Exception) {
            Log.e(TAG, "loadPageTemplateFromDb: failed to decode template bitmap", e)
            null
        }
    }

    /**
     * Incrementally persist in-memory strokes to the database using INSERT OR IGNORE.
     *
     * Strokes already in the DB (same UUID) are silently skipped — no full delete +
     * re-insert cycle.  Erased strokes are removed from DB via the [DrawingView.onStrokeErased]
     * callback; this function only adds new ones.
     *
     * Returns the list of stroke IDs that were newly inserted in this call (i.e. not
     * previously in [persistedStrokeIds]).  The caller uses this to push [UndoRedoAction.StrokeAdded]
     * for each new stroke.  Returns an empty list when no new strokes were saved.
     *
     * Called from: page navigation (before switch), [closeNotebook], [onPenLifted].
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun saveStrokes(db: SoilDatabase): List<String> {
        val layerId = currentLayerId
        if (layerId.isEmpty()) {
            Log.w(TAG, "saveStrokes: currentLayerId is empty — skipping")
            return emptyList()
        }
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val currentStrokes = drawingView.getStrokes()

        // Fix #2a — skip serialization for strokes already committed to the DB.
        // Take a snapshot of persistedStrokeIds before the transaction so the filter
        // is stable even if the main thread modifies the set during IO (edge case).
        val alreadyPersisted = persistedStrokeIds.toHashSet()
        val newStrokes = currentStrokes.filter { it.id !in alreadyPersisted }

        Slog.d(TAG) { "saveStrokes: ${newStrokes.size} new / ${currentStrokes.size} total strokes" }

        if (newStrokes.isEmpty()) {
            // Nothing to write — return immediately without touching the DB at all.
            return emptyList()
        }

        // Pre-build a stable index map so each new stroke keeps its global draw-order
        // position even though we iterate only the subset.
        val strokeIndexMap = currentStrokes.withIndex().associate { (i, s) -> s.id to i }

        // Single transaction — all new inserts share one BEGIN/COMMIT.
        db.withTransaction {
            for (liveStroke in newStrokes) {
                val points = liveStroke.points
                if (points.size < 2) continue   // degenerate stroke — skip

                val minX = points.minOf { it.x }
                val minY = points.minOf { it.y }
                val maxX = points.maxOf { it.x }
                val maxY = points.maxOf { it.y }
                val bboxJson = """{"x":$minX,"y":$minY,"width":${maxX - minX},"height":${maxY - minY}}"""

                val strokePoints = points.map { pt ->
                    StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = now)
                }
                val strokeData = StrokeData(
                    color       = "#000000",
                    strokeWidth = 3.0f,
                    points      = strokePoints,
                )

                dao.insertOrIgnore(
                    NotebookObject(
                        id          = liveStroke.id,
                        type        = "stroke",
                        parentId    = layerId,
                        boundingBox = bboxJson,
                        sortOrder   = strokeIndexMap[liveStroke.id] ?: 0,
                        createdAt   = now,
                        updatedAt   = now,
                        deletedAt   = null,
                        data        = strokeData.toJson(),
                    )
                )
            }
        }

        // Extend the registry so subsequent saves skip these IDs immediately.
        val newIds = newStrokes.map { it.id }
        persistedStrokeIds.addAll(newIds)
        return newIds
    }

    /**
     * Persist a [snapshot] base64 PNG into the `snapshot` field of the page row's
     * `data` JSON.  Bumps [NotebookObject.updatedAt] so the stale-snapshot check in
     * [tryLoadSnapshotBitmap] can detect subsequent stroke changes.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun persistSnapshot(db: SoilDatabase, pageId: String, snapshot: String) {
        val page    = db.notebookDao().getObjectById(pageId) ?: return
        val dataObj = try { org.json.JSONObject(page.data) } catch (e: Exception) { org.json.JSONObject() }
        dataObj.put("snapshot", snapshot)
        db.notebookDao().updateData(pageId, dataObj.toString(), System.currentTimeMillis())
        Slog.d(TAG) { "persistSnapshot: saved for page $pageId (${snapshot.length} chars)" }
    }

    // ── Template operations ───────────────────────────────────────────────────

    // ── Page index ────────────────────────────────────────────────────────────

    /**
     * Saves the current page snapshot and strokes, then launches [PageIndexActivity].
     * Snapshot is persisted first so PageIndexActivity reads the freshest state.
     */
    private fun openPageIndex() {
        val db           = soilDatabase ?: return
        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
        lifecycleScope.launch {
            val snapshot = drawingView.captureSnapshot()
            val pageId   = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && pageId.isNotEmpty()) {
                    persistSnapshot(db, pageId, snapshot)
                }
                saveStrokes(db)
            }
            val i = Intent(this@DrawingActivity, PageIndexActivity::class.java).apply {
                putExtra(PageIndexActivity.EXTRA_NOTEBOOK_PATH, notebookPath)
                putExtra(PageIndexActivity.EXTRA_CURRENT_PAGE_INDEX, currentPageIndex)
            }
            pageIndexLauncher.launch(i)
        }
    }

    // ── Copy / paste ──────────────────────────────────────────────────────────

    /** Copies the current page to the in-memory clipboard. Tapping again clears it (toggle). */
    private fun copyCurrentPage() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        pendingCopyPageId = if (pendingCopyPageId == pageId) null else pageId
        updateCopyPasteButtons()
    }

    /** Pastes the clipboard page immediately after the current page, then navigates to it. */
    private fun pasteCopiedPage() {
        val db     = soilDatabase ?: return
        val copyId = pendingCopyPageId ?: return
        lifecycleScope.launch {
            val snapshot = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            var newPageId: String? = null
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
                newPageId = copyPageAfter(copyId, currentPageId, db)
                if (newPageId != null) {
                    pages = db.notebookDao().getPagesSorted().toMutableList()
                    val idx = pages.indexOfFirst { it.id == newPageId }
                    if (idx >= 0) {
                        currentPageIndex = idx
                        currentPageId    = pages[idx].id
                        currentLayerId   = db.notebookDao().getLayerForPage(currentPageId)?.id ?: ""
                    }
                }
            }
            if (newPageId == null) return@launch
            pendingCopyPageId = null
            updateCopyPasteButtons()
            undoRedoManager.push(UndoRedoAction.PagePasted(currentPageId, currentPageIndex))
            updateUndoRedoButtons()
            drawingView.eraseAll()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    private fun updateCopyPasteButtons() {
        binding.btnCopyPage.isSelected  = pendingCopyPageId != null
        binding.btnPastePage.isEnabled  = pendingCopyPageId != null
    }

    // ── Cover dialog ──────────────────────────────────────────────────────────

    /**
     * Opens [CoverDialog] for the current notebook.
     * The current page snapshot is captured first so the "Last Opened Page" card
     * shows the freshest possible preview.
     */
    private fun openCoverDialog() {
        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
        val snapshot = drawingView.captureSnapshot()
        val snapshotBitmap: Bitmap? = snapshot?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }

        CoverDialog(
            activity           = this,
            lifecycleScope     = lifecycleScope,
            soilFilePath       = notebookPath,
            lastOpenedSnapshot = snapshotBitmap,
            onRequestImagePick = { callback ->
                onCoverImagePicked = callback
                coverPickerLauncher.launch("image/*")
            },
            onCoverChanged = {
                android.widget.Toast.makeText(this, "Cover updated", android.widget.Toast.LENGTH_SHORT).show()
            },
        ).show()
    }

    private fun startExport(db: SoilDatabase) {
        // Save current page strokes before reading from DB so the export sees the latest content.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes(db) }
            runExport(db)
        }
    }

    private fun runExport(db: SoilDatabase) {
        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        lifecycleScope.launch {
            val pdfFile = try {
                withContext(Dispatchers.IO) {
                    NotebookExporter.export(
                        context = this@DrawingActivity,
                        db = db,
                        onProgress = { current, total ->
                            handler.post { tvMessage.text = "Exporting page $current of $total…" }
                        },
                    )
                }
            } catch (e: Exception) {
                dialog.dismiss()
                android.widget.Toast.makeText(this@DrawingActivity, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            dialog.dismiss()
            sharePdf(pdfFile)
        }
    }

    private fun sharePdf(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    /**
     * Called when the user confirms a template selection in [TemplateDialog].
     * Updates the current page's `template` property in the DB, then applies the
     * bitmap to the drawing view so the change is immediately visible.
     *
     * [templateId] = "" means "Blank" (clear template).
     * [bitmap] is the full-resolution template Bitmap, or null for Blank.
     */
    private fun applyTemplateToCurrentPage(templateId: String, bitmap: Bitmap?) {
        val db = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        // Apply to drawing view immediately (before DB write so the user sees it at once).
        drawingView.setTemplate(bitmap)

        // Persist the page's template property and record the undo action in the background.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Read current (previous) template ID before overwriting — needed for undo.
                val page = db.notebookDao().getObjectById(pageId)
                val previousTemplateId = page
                    ?.let { TemplateDialog.parseTemplateId(it.data) }
                    ?.takeIf { it.isNotEmpty() }

                persistPageTemplate(db.notebookDao(), pageId, templateId)

                // Push undo action on the main thread after the DB write completes.
                withContext(Dispatchers.Main) {
                    undoRedoManager.push(
                        UndoRedoAction.TemplateChanged(
                            pageId           = pageId,
                            previousTemplateId = previousTemplateId,
                            newTemplateId    = templateId.takeIf { it.isNotEmpty() },
                        )
                    )
                    updateUndoRedoButtons()
                }
            }
            // TODO: apply template to all pages
        }
    }

    /**
     * Update the `template` property inside the page row's `data` JSON and persist it.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun persistPageTemplate(dao: NotebookDao, pageId: String, templateId: String) {
        val page = dao.getObjectById(pageId) ?: return
        val dataObj = try { JSONObject(page.data) } catch (e: Exception) { JSONObject() }
        dataObj.put("template", templateId)
        dao.updateData(pageId, dataObj.toString(), System.currentTimeMillis())
    }

    // ── Notebook metadata operations ──────────────────────────────────────────

    /**
     * Load the notebook metadata row from [db] and parse it into a [NotebookMetadata].
     * Returns null if the row doesn't exist (old notebook without metadata row) or
     * the JSON is malformed.  Must be called on [Dispatchers.IO].
     */
    private suspend fun loadNotebookMetadataFromDb(db: SoilDatabase): NotebookMetadata? {
        val obj = db.notebookDao().getNotebookObject() ?: return null
        return try {
            NotebookMetadata.fromJson(obj.id, obj.data)
        } catch (e: Exception) {
            Log.e(TAG, "loadNotebookMetadataFromDb: failed to parse metadata row", e)
            null
        }
    }

    /**
     * Asynchronously persist [pageId] as the last-opened page in the notebook metadata row.
     * No-op if [notebookMetadata] is null (old notebook without the row).
     * Fast, non-blocking — launches a background coroutine and returns immediately.
     */
    private fun saveLastOpenedPage(pageId: String) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val meta = notebookMetadata ?: return@withContext
                val updatedMeta = meta.copy(lastOpenedPage = pageId)
                notebookMetadata = updatedMeta
                val obj = db.notebookDao().getNotebookObject() ?: return@withContext
                val now = System.currentTimeMillis()
                db.notebookDao().upsertNotebookObject(
                    obj.copy(data = updatedMeta.toJson(), updatedAt = now)
                )
            }
        }
    }

    // ── Page operations ───────────────────────────────────────────────────────

    /**
     * Add a new page immediately after the current page.
     *
     * The new page inherits the current page's template: if the current page has a
     * non-empty template id, the new page starts with the same template.
     *
     * Steps: save current strokes → shift order of subsequent pages → insert new
     * page + bootstrap layer → reload pages → advance [currentPageIndex].
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun addPage(db: SoilDatabase) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val bounds = screenBounds()
        val screenW = bounds.width().toFloat()
        val screenH = bounds.height().toFloat()
        val bboxJson = """{"x":0.0,"y":0.0,"width":$screenW,"height":$screenH}"""

        val insertionIndex = currentPageIndex + 1

        // Inherit the current page's template — re-read from DB so we always get the latest
        // template id even if the in-memory `pages` list is stale (e.g. after applyTemplateToCurrentPage
        // updated the DB without refreshing the list).
        val freshCurrentPage = dao.getObjectById(currentPageId)
        val inheritedTemplate = freshCurrentPage?.let { TemplateDialog.parseTemplateId(it.data) } ?: ""

        // Shift the order of every page that comes after the insertion point.
        for (i in insertionIndex until pages.size) {
            dao.updateOrder(pages[i].id, i + 1)
        }

        // Insert the new page.  parentId is the notebook metadata UUID (if present)
        // so the hierarchy is correct; falls back to NIL_UUID for legacy notebooks.
        val newPageId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newPageId,
                type        = "page",
                parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                boundingBox = bboxJson,
                sortOrder   = insertionIndex,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = """{"width":$screenW,"height":$screenH,"template":"$inheritedTemplate"}""",
            )
        )

        // Bootstrap a content layer for the new page.
        val newLayerId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newLayerId,
                type        = "layer",
                parentId    = newPageId,
                boundingBox = bboxJson,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
            )
        )

        // Reload and advance the page pointer so loadStrokesFromDb uses the new page.
        pages = dao.getPagesSorted().toMutableList()
        currentPageIndex = insertionIndex
        currentPageId = newPageId
        currentLayerId = newLayerId
    }

    /**
     * Insert a new page immediately BEFORE the current page.
     *
     * Mirrors [addPage] exactly, except insertionIndex = currentPageIndex so the new page
     * lands at the current position and the current page shifts forward by one.
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun addPageBefore(db: SoilDatabase) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val bounds = screenBounds()
        val screenW = bounds.width().toFloat()
        val screenH = bounds.height().toFloat()
        val bboxJson = """{"x":0.0,"y":0.0,"width":$screenW,"height":$screenH}"""

        val insertionIndex = currentPageIndex

        val freshCurrentPage = dao.getObjectById(currentPageId)
        val inheritedTemplate = freshCurrentPage?.let { TemplateDialog.parseTemplateId(it.data) } ?: ""

        for (i in insertionIndex until pages.size) {
            dao.updateOrder(pages[i].id, i + 1)
        }

        val newPageId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newPageId,
                type        = "page",
                parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                boundingBox = bboxJson,
                sortOrder   = insertionIndex,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = """{"width":$screenW,"height":$screenH,"template":"$inheritedTemplate"}""",
            )
        )

        val newLayerId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newLayerId,
                type        = "layer",
                parentId    = newPageId,
                boundingBox = bboxJson,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
            )
        )

        pages = dao.getPagesSorted().toMutableList()
        currentPageIndex = insertionIndex
        currentPageId = newPageId
        currentLayerId = newLayerId
    }

    /**
     * Delete the current page, its layer, and all strokes under the layer.
     *
     * If the notebook would become empty, a fresh page + layer is bootstrapped
     * automatically so the user is never left with a blank notebook.
     *
     * @param deletedAt The timestamp to use for all soft-delete operations.  Callers must
     * pass the same value that was captured before calling this function so the undo action
     * ([UndoRedoAction.PageDeleted.deletedAt]) matches the DB rows exactly — every cascade
     * delete uses the identical timestamp so [NotebookDao.restoreChildrenDeletedSince] can
     * recover exactly those rows on undo.
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun deletePage(db: SoilDatabase, deletedAt: Long = System.currentTimeMillis()) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = deletedAt

        // Cascade soft-deletes: page → layer → all strokes.
        dao.softDeleteById(currentPageId, now)
        dao.softDeleteByParentId(currentPageId, now)   // soft-deletes the layer row(s)
        dao.softDeleteByParentId(currentLayerId, now)  // soft-deletes all stroke rows

        // Reload surviving pages.
        pages = dao.getPagesSorted().toMutableList()

        if (pages.isEmpty()) {
            // Notebook is empty — bootstrap one fresh page so the user is never stuck.
            val bounds = screenBounds()
            val screenW = bounds.width().toFloat()
            val screenH = bounds.height().toFloat()
            val bboxJson = """{"x":0.0,"y":0.0,"width":$screenW,"height":$screenH}"""
            val newNow = System.currentTimeMillis()

            val newPageId = UUID.randomUUID().toString()
            dao.insertObject(
                NotebookObject(
                    id          = newPageId,
                    type        = "page",
                    parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = newNow,
                    updatedAt   = newNow,
                    deletedAt   = null,
                    data        = """{"width":$screenW,"height":$screenH,"template":""}""",
                )
            )
            val newLayerId = UUID.randomUUID().toString()
            dao.insertObject(
                NotebookObject(
                    id          = newLayerId,
                    type        = "layer",
                    parentId    = newPageId,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = newNow,
                    updatedAt   = newNow,
                    deletedAt   = null,
                    data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
                )
            )
            pages = dao.getPagesSorted().toMutableList()
        }

        // Clamp the page pointer and resolve the new current page/layer IDs.
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        currentLayerId = dao.getLayerForPage(currentPageId)?.id ?: ""
    }

    // ── Page navigation ───────────────────────────────────────────────────────

    /**
     * Save the current page's strokes and capture its snapshot, then switch to [newIndex]
     * and load its content via the snapshot fast path or full render path.
     * Persists [NotebookMetadata.lastOpenedPage] after every page turn.
     * Called from the swipe gesture detector.
     */
    private fun navigateToPage(newIndex: Int) {
        lifecycleScope.launch { navigateToPageInternal(newIndex) }
    }

    /**
     * Suspend implementation of [navigateToPage], callable directly from coroutines
     * (e.g. undo/redo execution).  Saves the current page, clears the canvas, loads
     * the new page, and updates the UI — fully handling all thread context switches.
     *
     * Must NOT be called while already inside a [withContext] block that holds the IO
     * dispatcher, because it starts with main-thread View work ([captureSnapshot]).
     * Always call from the main-thread coroutine context.
     */
    private suspend fun navigateToPageInternal(newIndex: Int) {
        val db = soilDatabase ?: return
        // Capture snapshot of the page we are leaving — must be on the main thread.
        val snapshot      = drawingView.captureSnapshot()
        val leavingPageId = currentPageId

        withContext(Dispatchers.IO) {
            if (snapshot != null && leavingPageId.isNotEmpty()) {
                persistSnapshot(db, leavingPageId, snapshot)
            }
            saveStrokes(db)
        }

        currentPageIndex = newIndex
        selectedObjectIds.clear()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        drawingView.eraseAll()

        val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
        displayPage(result)
        updatePageIndicator()
        saveLastOpenedPage(currentPageId)
        postDisplayWork(db, result)
    }

    /**
     * Lightweight pre-flight for undo/redo cross-page navigation: saves and snapshots
     * the current page and sets [currentPageIndex] to [newIndex], but does NOT load the
     * new page's content — the undo/redo caller performs its DB operation and then runs a
     * final [loadCurrentPage] which loads the correct post-operation state in one pass.
     *
     * Avoids the double-load (navigate + undo reload) that [navigateToPageInternal] would cause,
     * eliminating the background-deserialization race that could call [setStrokeListSilently]
     * with stale data after the DB operation changed stroke rows.
     *
     * Must be called from the main-thread coroutine context ([captureSnapshot] requires main thread).
     */
    private suspend fun saveAndSwitchPage(newIndex: Int) {
        val db = soilDatabase ?: return
        val snapshot      = drawingView.captureSnapshot()
        val leavingPageId = currentPageId
        withContext(Dispatchers.IO) {
            if (snapshot != null && leavingPageId.isNotEmpty()) {
                persistSnapshot(db, leavingPageId, snapshot)
            }
            saveStrokes(db)
        }
        currentPageIndex = newIndex
        drawingView.eraseAll()
    }

    /** Refresh the page indicator overlay text. Call on the main thread. */
    private fun updatePageIndicator() {
        binding.tvPageIndicator.text = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}"
    }

    // ── Undo/redo execution ───────────────────────────────────────────────────

    /**
     * Undo/redo buttons always appear enabled — matching the native BOOX notes app behaviour.
     * Tapping when the stack is empty is a no-op; [performUndo] / [performRedo] guard that.
     * No visual state change needed here; this function is kept as a call-site placeholder.
     */
    private fun updateUndoRedoButtons() {
        // No-op — buttons are statically enabled in the layout and never tinted.
    }

    // ── Lasso copy/paste helpers ──────────────────────────────────────────────

    /**
     * Compute the bounding box for a text heading from its embedded stroke positions and
     * recognized text, using the same 20sp paint + 8dp padding as [createHeadingFromStrokes].
     * Falls back to stroke-based bounds when [recognizedText] is null.
     */
    private fun headingBoundingBox(embeddedStrokes: List<LiveStroke>, recognizedText: String?): RectF {
        val strokeBox = RectF()
        embeddedStrokes.forEach { strokeBox.union(it.boundingBox) }
        val pad = 8f * resources.displayMetrics.density
        if (recognizedText == null) {
            strokeBox.inset(-pad, -pad)
            return strokeBox
        }
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        }
        val textWidth  = textPaint.measureText(recognizedText)
        val fm         = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        return RectF(
            strokeBox.left, strokeBox.top,
            strokeBox.left + pad + textWidth + pad,
            strokeBox.top  + pad + textHeight + pad,
        )
    }

    private fun computeUnionBoundingBox(
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
    ): RectF {
        val union = RectF()
        strokes.forEach { union.union(it.boundingBox) }
        headings.forEach { union.union(it.boundingBox) }
        return union
    }

    /** Copy the currently selected strokes/headings to [NotesproutClipboard]. */
    private fun performLassoCopy() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes  = drawingView.getStrokes().filter { it.id in ids }
        val headings = drawingView.getHeadings().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty()) return
        val box = computeUnionBoundingBox(strokes, headings)
        NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
            strokes  = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                recognizedText = h.recognizedText) },
            boundingBox = box,
        )
        updateLassoButtonIcon()
    }

    /**
     * Paste clipboard strokes/headings onto the current page, centered on [tapX], [tapY].
     * Inserts new rows into the DB, updates in-memory lists, sets them as the active
     * selection, and shows the floating toolbar at the new selection box.
     */
    private fun performLassoPaste(tapX: Float, tapY: Float) {
        val clip = NotesproutClipboard.content ?: return
        val db = soilDatabase ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return

        val cx = clip.boundingBox.centerX()
        val cy = clip.boundingBox.centerY()
        val dx = tapX - cx
        val dy = tapY - cy

        val insertedAt = System.currentTimeMillis()
        val newStrokes = clip.strokes.map { stroke ->
            LiveStroke(
                id = java.util.UUID.randomUUID().toString(),
                points = stroke.points.map { PointF(it.x + dx, it.y + dy) },
            )
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                    h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s ->
                    LiveStroke(java.util.UUID.randomUUID().toString(),
                        s.points.map { PointF(it.x + dx, it.y + dy) })
                },
                recognizedText = h.recognizedText,
            )
        }

        lifecycleScope.launch {
            val existingStrokes = drawingView.getStrokes()
            val baseOrder = existingStrokes.size
            withContext(Dispatchers.IO) {
                val now = insertedAt
                val dao = db.notebookDao()
                db.withTransaction {
                    newStrokes.forEachIndexed { i, stroke ->
                        val points = stroke.points
                        if (points.size < 2) return@forEachIndexed
                        val minX = points.minOf { it.x }
                        val minY = points.minOf { it.y }
                        val maxX = points.maxOf { it.x }
                        val maxY = points.maxOf { it.y }
                        val bboxJson = """{"x":$minX,"y":$minY,"width":${maxX - minX},"height":${maxY - minY}}"""
                        val strokePoints = points.map { pt ->
                            StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = now)
                        }
                        val strokeData = StrokeData(color = "#000000", strokeWidth = 3.0f, points = strokePoints)
                        dao.insertOrIgnore(
                            NotebookObject(
                                id          = stroke.id,
                                type        = "stroke",
                                parentId    = layerId,
                                boundingBox = bboxJson,
                                sortOrder   = baseOrder + i,
                                createdAt   = now,
                                updatedAt   = now,
                                deletedAt   = null,
                                data        = strokeData.toJson(),
                            )
                        )
                    }
                    newHeadings.forEach { heading ->
                        val bboxJson = """{"x":${heading.boundingBox.left},"y":${heading.boundingBox.top},"width":${heading.boundingBox.width()},"height":${heading.boundingBox.height()}}"""
                        dao.insertOrIgnore(
                            NotebookObject(
                                id          = heading.id,
                                type        = TYPE_HEADING,
                                parentId    = layerId,
                                boundingBox = bboxJson,
                                sortOrder   = 0,
                                createdAt   = now,
                                updatedAt   = now,
                                deletedAt   = null,
                                data        = HeadingObject(heading.strokes, heading.recognizedText).toJson(),
                            )
                        )
                    }
                }
            }

            val allStrokes  = existingStrokes + newStrokes
            val allHeadings = drawingView.getHeadings() + newHeadings
            newStrokes.forEach { persistedStrokeIds.add(it.id) }
            drawingView.loadHeadings(allHeadings)

            undoRedoManager.push(UndoRedoAction.LassoPasted(
                strokeIds  = newStrokes.map { it.id },
                pageId     = pageId,
                insertedAt = insertedAt,
                headingIds = newHeadings.map { it.id },
            ))
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, templateBmp, allHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(allStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(allStrokes)
            }

            // Set pasted content as the active selection.
            val newBox = computeUnionBoundingBox(newStrokes, newHeadings)
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
        }
    }

    /**
     * Cut the currently selected strokes/headings: soft-delete them and populate
     * [NotesproutClipboard].  Lasso mode stays active so the user can immediately paste.
     */
    private fun performLassoCut() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        val selectedStrokes  = drawingView.getStrokes().filter { it.id in ids }
        val selectedHeadings = drawingView.getHeadings().filter { it.id in ids }
        if (selectedStrokes.isEmpty() && selectedHeadings.isEmpty()) return

        val box = computeUnionBoundingBox(selectedStrokes, selectedHeadings)

        val clipStrokes  = selectedStrokes.map { s ->
            LiveStroke(s.id, s.points.map { pt -> PointF(pt.x, pt.y) })
        }
        val clipHeadings = selectedHeadings.map { h ->
            HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                recognizedText = h.recognizedText)
        }
        NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
            strokes  = clipStrokes,
            headings = clipHeadings,
            boundingBox = box,
        )

        val strokeIds  = selectedStrokes.map { it.id }
        val headingIds = selectedHeadings.map { it.id }

        lifecycleScope.launch(Dispatchers.IO) {
            val deletedAt = System.currentTimeMillis()
            (strokeIds + headingIds).forEach { id ->
                soilDatabase?.notebookDao()?.softDeleteById(id, deletedAt)
            }
            withContext(Dispatchers.Main) {
                val erasedStrokeSet  = strokeIds.toSet()
                val erasedHeadingSet = headingIds.toSet()
                val updatedStrokes  = drawingView.getStrokes().filter { it.id !in erasedStrokeSet }
                val updatedHeadings = drawingView.getHeadings().filter { it.id !in erasedHeadingSet }
                persistedStrokeIds.removeAll(erasedStrokeSet)
                drawingView.setStrokeListSilently(updatedStrokes)
                drawingView.loadHeadings(updatedHeadings)
                undoRedoManager.push(UndoRedoAction.LassoCut(
                    strokeIds  = strokeIds,
                    pageId     = pageId,
                    deletedAt  = deletedAt,
                    strokes    = clipStrokes,
                    headingIds = headingIds,
                    headings   = clipHeadings,
                ))
                updateUndoRedoButtons()
                val templateBmp = currentTemplateBitmap
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                } else {
                    drawingView.loadStrokes(updatedStrokes)
                }
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
                updateLassoButtonIcon()
            }
        }
    }

    /**
     * Delete the currently selected strokes/headings from the page.
     * Unlike cut, the clipboard is never touched. Lasso mode stays active with no selection.
     */
    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        val selectedStrokes  = drawingView.getStrokes().filter { it.id in ids }
        val selectedHeadings = drawingView.getHeadings().filter { it.id in ids }
        if (selectedStrokes.isEmpty() && selectedHeadings.isEmpty()) return

        val strokeIds  = selectedStrokes.map { it.id }
        val headingIds = selectedHeadings.map { it.id }
        val capturedStrokes  = selectedStrokes.map { s ->
            LiveStroke(s.id, s.points.map { pt -> PointF(pt.x, pt.y) })
        }
        val capturedHeadings = selectedHeadings.map { h ->
            HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                recognizedText = h.recognizedText)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val deletedAt = System.currentTimeMillis()
            (strokeIds + headingIds).forEach { id ->
                soilDatabase?.notebookDao()?.softDeleteById(id, deletedAt)
            }
            withContext(Dispatchers.Main) {
                val erasedStrokeSet  = strokeIds.toSet()
                val erasedHeadingSet = headingIds.toSet()
                val updatedStrokes  = drawingView.getStrokes().filter { it.id !in erasedStrokeSet }
                val updatedHeadings = drawingView.getHeadings().filter { it.id !in erasedHeadingSet }
                persistedStrokeIds.removeAll(erasedStrokeSet)
                drawingView.setStrokeListSilently(updatedStrokes)
                drawingView.loadHeadings(updatedHeadings)
                undoRedoManager.push(UndoRedoAction.LassoDeleted(
                    strokeIds  = strokeIds,
                    pageId     = pageId,
                    deletedAt  = deletedAt,
                    strokes    = capturedStrokes,
                    headingIds = headingIds,
                    headings   = capturedHeadings,
                ))
                updateUndoRedoButtons()
                val templateBmp = currentTemplateBitmap
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                } else {
                    drawingView.loadStrokes(updatedStrokes)
                }
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            }
        }
    }

    /**
     * Convert the [selectedStrokes] lasso selection into a heading object.
     * [boundingBox] is the padded selection RectF used as the heading's visual background.
     * Must be called on [Dispatchers.IO]; switches to Main for in-memory + UI updates.
     */
    private suspend fun createHeadingFromStrokes(
        selectedStrokes: List<LiveStroke>,
        boundingBox: RectF,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return

        val strokesToConvert = selectedStrokes.toList()
        val boundsToConvert  = RectF(boundingBox)

        val recognizer = HandwritingRecognizerProvider.instance
        val recognizedText: String = if (recognizer != null && recognizer.isReady()) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    recognizer.recognize(strokesToConvert, boundsToConvert) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        } else {
            HandwritingRecognizer.FALLBACK_TEXT
        }

        // Resize bounding box to fit the recognized text (left/top anchor preserved).
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        }
        val textWidth = textPaint.measureText(recognizedText)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val pad = 8f * resources.displayMetrics.density
        boundsToConvert.set(
            boundsToConvert.left,
            boundsToConvert.top,
            boundsToConvert.left + pad + textWidth + pad,
            boundsToConvert.top + pad + textHeight + pad,
        )

        val deletedAt  = System.currentTimeMillis()
        val headingId  = UUID.randomUUID().toString()
        val originalStrokeIds = strokesToConvert.map { it.id }

        // Fresh UUIDs for embedded strokes — the same instances go into both the
        // HeadingObject (DB) and the HeadingCreated undo action.
        val embeddedStrokes = strokesToConvert.map { stroke ->
            LiveStroke(id = UUID.randomUUID().toString(), points = stroke.points.map { PointF(it.x, it.y) })
        }

        db.withTransaction {
            val dao = db.notebookDao()
            originalStrokeIds.forEach { dao.softDeleteById(it, deletedAt) }
            val now        = System.currentTimeMillis()
            val bboxJson   = """{"x":${boundsToConvert.left},"y":${boundsToConvert.top},"width":${boundsToConvert.width()},"height":${boundsToConvert.height()}}"""
            val headingObj = HeadingObject(strokes = embeddedStrokes, recognizedText = recognizedText)
            dao.insertObject(
                NotebookObject(
                    id          = headingId,
                    parentId    = layerId,
                    type        = TYPE_HEADING,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    deletedAt   = null,
                    data        = headingObj.toJson(),
                )
            )
        }

        // Invalidate any existing page snapshot — it predates the heading and doesn't
        // include the heading background.  The next close will capture a fresh snapshot
        // via captureSnapshot() which now includes heading backgrounds.
        invalidatePageSnapshot(db, pageId)

        withContext(Dispatchers.Main) {
            val erasedSet = originalStrokeIds.toSet()
            val updatedStrokes = drawingView.getStrokes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)

            val newHeading    = HeadingStroke(id = headingId, boundingBox = boundsToConvert, strokes = embeddedStrokes, recognizedText = recognizedText)
            val updatedHeadings = drawingView.getHeadings() + newHeading
            drawingView.loadHeadings(updatedHeadings)

            undoRedoManager.push(
                UndoRedoAction.HeadingCreated(
                    headingId         = headingId,
                    pageId            = pageId,
                    layerId           = layerId,
                    deletedAt         = deletedAt,
                    originalStrokeIds = originalStrokeIds,
                    embeddedStrokes   = embeddedStrokes,
                    recognizedText    = recognizedText,
                )
            )
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            val newSelection = setOf(headingId)
            val pad = 8f * resources.displayMetrics.density
            val selectionBox = RectF(boundsToConvert).also { it.inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(newSelection, selectionBox)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newSelection)
            updateFloatingSelectionToolbar(selectionBox)
        }
    }

    /**
     * Remove a heading, re-inserting its embedded strokes as individual live rows on the
     * current layer.  Must be called on [Dispatchers.IO]; switches to Main for UI updates.
     */
    private suspend fun removeHeading(heading: HeadingStroke) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val now    = System.currentTimeMillis()

        // Fresh-UUID copies of the embedded strokes to re-insert as live rows.
        val restoredStrokes = heading.strokes.map { embedded ->
            embedded.copy(id = UUID.randomUUID().toString())
        }

        db.withTransaction {
            val dao = db.notebookDao()
            // 1. Soft-delete the heading row.
            dao.softDeleteById(heading.id, now)
            // 2. Re-insert each embedded stroke as a new live row on the current layer.
            restoredStrokes.forEach { stroke ->
                val bboxJson = """{"x":${stroke.boundingBox.left},"y":${stroke.boundingBox.top},"width":${stroke.boundingBox.width()},"height":${stroke.boundingBox.height()}}"""
                val strokePoints = stroke.points.map { pt ->
                    StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = now)
                }
                val strokeData = StrokeData(color = "#000000", strokeWidth = 3.0f, points = strokePoints)
                dao.insertObject(NotebookObject(
                    id          = stroke.id,
                    parentId    = currentLayerId,
                    type        = "stroke",
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    deletedAt   = null,
                    data        = strokeData.toJson(),
                ))
            }
        }

        withContext(Dispatchers.Main) {
            undoRedoManager.push(UndoRedoAction.HeadingRemoved(
                headingId      = heading.id,
                pageId         = pageId,
                restoredStrokes = restoredStrokes,
                embeddedStrokes = heading.strokes,
                recognizedText = heading.recognizedText,
            ))
            updateUndoRedoButtons()

            val updatedHeadings = drawingView.getHeadings().filter { it.id != heading.id }
            drawingView.loadHeadings(updatedHeadings)
            val updatedStrokes = drawingView.getStrokes() + restoredStrokes
            drawingView.setStrokeListSilently(updatedStrokes)
            restoredStrokes.forEach { persistedStrokeIds.add(it.id) }

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            // Select the restored strokes so the user can act on them immediately.
            val selBox = computeUnionBoundingBox(restoredStrokes, emptyList())
            val pad = 8f * resources.displayMetrics.density
            selBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(restoredStrokes.map { it.id })
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), selBox)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    private fun showHeadingTextEditDialog(heading: HeadingStroke) {
        val dialogBinding = DialogEditHeadingTextBinding.inflate(layoutInflater)
        dialogBinding.editHeadingText.setText(heading.recognizedText)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Heading")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val newText = dialogBinding.editHeadingText.text?.toString()?.trim().orEmpty()
                if (newText.isNotBlank()) {
                    updateHeadingText(heading, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        dialogBinding.editHeadingText.requestFocus()
        dialogBinding.editHeadingText.selectAll()
        dialogBinding.editHeadingText.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editHeadingText)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editHeadingText, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    private fun updateHeadingText(heading: HeadingStroke, newText: String) {
        val db = soilDatabase ?: return
        val previousText = heading.recognizedText
        // Measure new text width using the same 20sp paint used by the drawing views.
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        }
        val paddingPx = 8f * resources.displayMetrics.density
        val textWidth = textPaint.measureText(newText)
        val newBox = RectF(
            heading.boundingBox.left,
            heading.boundingBox.top,
            heading.boundingBox.left + textWidth + 2f * paddingPx,
            heading.boundingBox.bottom,
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val row = db.notebookDao().getObjectById(heading.id) ?: return@withContext
                val headingObj = HeadingObject.fromJson(row.data)
                val updated = headingObj.copy(recognizedText = newText)
                val bboxJson = """{"x":${newBox.left},"y":${newBox.top},"width":${newBox.width()},"height":${newBox.height()}}"""
                db.notebookDao().updateHeadingData(heading.id, bboxJson, updated.toJson(), System.currentTimeMillis())
            }
            val updatedHeadings = drawingView.getHeadings().map { h ->
                if (h.id == heading.id) h.copy(recognizedText = newText, boundingBox = newBox) else h
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(newBox).also { it.inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                updateFloatingSelectionToolbar(selBox)
            }
            undoRedoManager.push(
                UndoRedoAction.HeadingTextEdited(
                    headingId    = heading.id,
                    pageId       = currentPageId,
                    previousText = previousText,
                    newText      = newText,
                )
            )
            updateUndoRedoButtons()
        }
    }

    /** Update btnLasso icon: ic_lasso_clipboard when clipboard has content, ic_lasso otherwise. */
    private fun updateLassoButtonIcon() {
        binding.btnLasso.setImageResource(
            if (NotesproutClipboard.hasContent()) R.drawable.ic_lasso_clipboard
            else R.drawable.ic_lasso
        )
    }

    // ── Floating toolbar helpers ──────────────────────────────────────────────

    private val selectedHeadings: List<HeadingStroke>
        get() = drawingView.getHeadings().filter { it.id in drawingView.lassoSelectedIds }

    private val selectedStrokes: List<LiveStroke>
        get() = drawingView.getStrokes().filter { it.id in drawingView.lassoSelectedIds }

    /**
     * Position and show the floating selection toolbar relative to [selectionBox].
     * Default: centered below the box with an 8dp gap.
     * Fallback: centered above when the bottom edge would clip off screen.
     */
    private fun updateFloatingSelectionToolbar(selectionBox: RectF) {
        val selHeadings = selectedHeadings
        val selStrokes  = selectedStrokes
        val selectionIsSingleHeading = selHeadings.size == 1 && selStrokes.isEmpty()
        val selectionIsPureStrokes   = selStrokes.isNotEmpty() && selHeadings.isEmpty()
        binding.btnMakeHeading.visibility = if (selectionIsPureStrokes)   View.VISIBLE else View.GONE
        binding.btnUnheading.visibility   = if (selectionIsSingleHeading) View.VISIBLE else View.GONE
        binding.headingDivider.visibility =
            if (selectionIsPureStrokes || selectionIsSingleHeading) View.VISIBLE else View.GONE
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post {
            val toolbarW = binding.floatingSelectionToolbar.measuredWidth.toFloat()
            val toolbarH = binding.floatingSelectionToolbar.measuredHeight.toFloat()
            val screenW  = binding.root.width.toFloat()
            val screenH  = binding.root.height.toFloat()
            val dpGap    = 8f * resources.displayMetrics.density
            val minY     = binding.drawingToolbar.height + dpGap

            var x = selectionBox.centerX() - toolbarW / 2f
            var y = selectionBox.bottom + dpGap

            // Fallback: above the selection box if clipped at the bottom.
            if (y + toolbarH > screenH) {
                y = selectionBox.top - dpGap - toolbarH
            }
            // Clamp to safe area (below main toolbar, above bottom edge).
            y = y.coerceIn(minY, (screenH - toolbarH).coerceAtLeast(minY))
            x = x.coerceIn(0f, (screenW - toolbarW).coerceAtLeast(0f))

            binding.floatingSelectionToolbar.x = x
            binding.floatingSelectionToolbar.y = y
        }
    }

    private fun hideFloatingSelectionToolbar() {
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    /** Compute and store the anchor point below btnLasso in root-view coordinates. */
    private fun computeLassoToolbarAnchor() {
        val btnLoc  = IntArray(2).also { binding.btnLasso.getLocationOnScreen(it) }
        val rootLoc = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        lassoToolbarAnchor = PointF(
            btnLoc[0] + binding.btnLasso.width  / 2f - rootLoc[0].toFloat(),
            (btnLoc[1] + binding.btnLasso.height - rootLoc[1]).toFloat(),
        )
    }

    /** Position and show the lasso popup toolbar anchored below btnLasso. */
    private fun updateLassoPopupToolbar() {
        val anchor = lassoToolbarAnchor ?: run { computeLassoToolbarAnchor(); lassoToolbarAnchor } ?: return
        val dpGap  = 8f * resources.displayMetrics.density
        binding.lassoPopupToolbar.visibility = View.VISIBLE
        binding.lassoPopupToolbar.post {
            val w = binding.lassoPopupToolbar.measuredWidth.toFloat()
            val screenW = binding.root.width.toFloat()
            val x = (anchor.x - w / 2f).coerceIn(0f, (screenW - w).coerceAtLeast(0f))
            binding.lassoPopupToolbar.x = x
            binding.lassoPopupToolbar.y = anchor.y + dpGap
        }
    }

    private fun hideLassoPopupToolbar() {
        binding.lassoPopupToolbar.visibility = View.GONE
    }

    // ── Lasso mode helpers ────────────────────────────────────────────────────

    private fun enterLassoMode() {
        isLassoMode = true
        // Ensure the drawing engine is in a neutral state before handing off to lasso.
        if (isEraserActive) {
            isEraserActive = false
            drawingView.setEraserMode(false)
        }
        drawingView.setLassoMode(true)
        binding.btnLasso.isSelected  = true
        binding.btnPen.isSelected    = false
        binding.btnEraser.isSelected = false
    }

    /** Exit lasso mode, clearing all selection state. The caller is responsible for activating the desired tool. */
    private fun exitLassoMode() {
        isLassoMode = false
        selectedObjectIds.clear()
        drawingView.setLassoMode(false)
        binding.btnLasso.isSelected = false
        hideFloatingSelectionToolbar()
        hideLassoPopupToolbar()
    }

    private fun enterLassoEraserMode() {
        if (isLassoMode) exitLassoMode()
        if (isEraserActive) {
            isEraserActive = false
            drawingView.setEraserMode(false)
        }
        isLassoEraserMode = true
        drawingView.setLassoEraserMode(true)
        binding.btnLassoEraser.isSelected = true
        binding.btnPen.isSelected         = false
        binding.btnEraser.isSelected      = false
        binding.btnLasso.isSelected       = false
    }

    private fun exitLassoEraserMode() {
        isLassoEraserMode = false
        drawingView.setLassoEraserMode(false)
        binding.btnLassoEraser.isSelected = false
    }

    private fun performUndo() {
        val db = soilDatabase ?: return
        val action = undoRedoManager.undo() ?: return
        updateUndoRedoButtons()
        lifecycleScope.launch { executeAction(db, action, isUndo = true) }
    }

    private fun performRedo() {
        val db = soilDatabase ?: return
        val action = undoRedoManager.redo() ?: return
        updateUndoRedoButtons()
        lifecycleScope.launch { executeAction(db, action, isUndo = false) }
    }

    /**
     * Reorders [pageId] to be immediately after [afterPageId] (or first if null).
     * All page `order` values are reassigned sequentially in a single transaction.
     * Must be called on [Dispatchers.IO] (called inside [withContext] in [executeAction]).
     */
    private suspend fun movePageToAfter(db: SoilDatabase, pageId: String, afterPageId: String?) {
        db.withTransaction {
            val dao = db.notebookDao()
            val allPages = dao.getPagesSorted().toMutableList()
            val movingIdx = allPages.indexOfFirst { it.id == pageId }
            if (movingIdx < 0) return@withTransaction
            val movingPage = allPages.removeAt(movingIdx)
            val insertAt = if (afterPageId == null) {
                0
            } else {
                val afterIdx = allPages.indexOfFirst { it.id == afterPageId }
                if (afterIdx >= 0) afterIdx + 1 else allPages.size
            }
            allPages.add(insertAt, movingPage)
            allPages.forEachIndexed { i, page -> dao.updateOrder(page.id, i) }
        }
    }

    /**
     * Execute a single undo or redo [action].
     *
     * Called from the main-thread coroutine context (launched via [lifecycleScope]).
     * Handles all thread switching internally.
     *
     * Flow — stroke actions, same page:
     *   DB op → in-memory stroke list update → rebuild bitmap → [loadStrokesWithBitmap]
     *   (one EPD handoff; user sees only the affected stroke change)
     *
     * Flow — stroke actions, cross-page (two-phase):
     *   Phase 1: save leaving page → set [currentPageIndex] → load target page in its
     *            PRE-undo state (stroke still present/absent as it was) → display it so
     *            the user can see the page, THEN
     *   Phase 2: DB op → in-memory update → [loadStrokesWithBitmap] so the stroke
     *            visibly appears or disappears in front of the user.
     *
     * Flow — page / template actions:
     *   [saveAndSwitchPage] if cross-page → DB op → snapshot invalidation →
     *   [eraseAll] → [loadCurrentPage] → [displayPage]
     */
    private suspend fun executeAction(db: SoilDatabase, action: UndoRedoAction, isUndo: Boolean) {
        val now = System.currentTimeMillis()
        val dao = db.notebookDao()

        val targetPageId: String? = when (action) {
            is UndoRedoAction.StrokeAdded     -> action.pageId
            is UndoRedoAction.StrokeErased    -> action.pageId
            is UndoRedoAction.LassoErased     -> action.pageId
            is UndoRedoAction.LassoPasted     -> action.pageId
            is UndoRedoAction.LassoCut        -> action.pageId
            is UndoRedoAction.LassoDeleted    -> action.pageId
            is UndoRedoAction.StrokesMoved    -> action.pageId
            is UndoRedoAction.TemplateChanged -> action.pageId
            is UndoRedoAction.PageEraseAll     -> action.pageId
            is UndoRedoAction.HeadingCreated   -> action.pageId
            is UndoRedoAction.HeadingRemoved   -> action.pageId
            is UndoRedoAction.HeadingTextEdited -> action.pageId
            else                               -> null
        }
        val isCrossPage = targetPageId != null && targetPageId != currentPageId

        // ── Cross-page LassoErased: two-phase display (mixed stroke + heading batch) ──
        // strokeIds may contain both stroke IDs and heading IDs.
        if (isCrossPage && action is UndoRedoAction.LassoErased) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) action.strokeIds.forEach { dao.restoreById(it, now) }
                else        action.strokeIds.forEach { dao.softDeleteById(it, now) }
            }

            val idsSet = action.strokeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo: re-erase → remove from in-memory stroke and heading lists.
                val preUndoHeadingIds = preUndoHeadings.mapTo(mutableSetOf()) { it.id }
                val erasedHeadingIds  = idsSet.intersect(preUndoHeadingIds)
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in erasedHeadingIds }
                persistedStrokeIds.removeAll(idsSet - erasedHeadingIds)
            } else {
                // Undo: restore → fetch from DB, partition by type.
                val restoredRows = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id -> dao.getObjectById(id) }
                }
                val restoredStrokes = restoredRows
                    .filter { it.type != TYPE_HEADING }
                    .mapNotNull { obj ->
                        try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                        catch (e: Exception) {
                            Log.e(TAG, "executeAction LassoErased: failed to deserialize ${obj.id}", e); null
                        }
                    }
                val restoredHeadings = restoredRows
                    .filter { it.type == TYPE_HEADING }
                    .mapNotNull { obj ->
                        val box = obj.parseBoundingBox() ?: return@mapNotNull null
                        val headingObj = runCatching { HeadingObject.fromJson(obj.data) }.getOrNull() ?: return@mapNotNull null
                        HeadingStroke(id = obj.id, boundingBox = box, strokes = headingObj.strokes, recognizedText = headingObj.recognizedText)
                    }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + restoredHeadings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return
        }

        // ── Cross-page LassoCut: two-phase display (same as LassoErased + clipboard on redo) ─
        if (isCrossPage && action is UndoRedoAction.LassoCut) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                }
            }

            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo cut: remove from in-memory lists and repopulate clipboard.
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
                val clipBox = computeUnionBoundingBox(action.strokes, action.headings)
                NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
                    strokes  = action.strokes.map { s -> LiveStroke(s.id, s.points.map { pt -> PointF(pt.x, pt.y) }) },
                    headings = action.headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                        h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                        recognizedText = h.recognizedText) },
                    boundingBox = clipBox,
                )
                updateLassoButtonIcon()
            } else {
                // Undo cut: fetch strokes from DB; use action.headings for headings.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoCut: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + action.headings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return
        }

        // ── Cross-page LassoDeleted: two-phase display (identical to LassoErased — no clipboard) ─
        if (isCrossPage && action is UndoRedoAction.LassoDeleted) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                }
            }

            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo delete: remove from in-memory lists.
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Undo delete: fetch strokes from DB; use action.headings for headings.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoDeleted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + action.headings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return
        }

        // ── Cross-page LassoPasted: two-phase display (inverse of LassoErased) ─
        // Undo paste = remove strokes/headings (mirrors LassoErased redo).
        // Redo paste = restore strokes/headings (mirrors LassoErased undo).
        if (isCrossPage && action is UndoRedoAction.LassoPasted) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                }
            }

            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Undo paste → remove the pasted strokes and headings.
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Redo paste → fetch restored strokes from DB; reload headings from DB.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoPasted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                val restoredHeadings = withContext(Dispatchers.IO) {
                    action.headingIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try {
                                val ho = HeadingObject.fromJson(obj.data)
                                val box = obj.parseBoundingBox() ?: return@mapNotNull null
                                HeadingStroke(id = obj.id, boundingBox = box, strokes = ho.strokes, recognizedText = ho.recognizedText)
                            } catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoPasted redo: failed to deserialize heading $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + restoredHeadings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return
        }

        // ── Cross-page HeadingCreated: two-phase display ─────────────────────
        // Undo: heading soft-deleted, original strokes restored — page shows strokes, no heading.
        // Redo: original strokes soft-deleted, heading restored — page shows heading, no strokes.
        if (isCrossPage && action is UndoRedoAction.HeadingCreated) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.headingId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.headingId, now)
                }
            }

            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction HeadingCreated: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings.filter { it.id != action.headingId }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = preUndoStrokes.filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val newHeading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText)
                updatedHeadings = preUndoHeadings + newHeading
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return
        }

        // ── Cross-page HeadingRemoved: two-phase display ─────────────────────
        // Undo: restoredStrokes soft-deleted, heading restored — page shows heading.
        // Redo: heading soft-deleted, restoredStrokes restored — page shows strokes.
        if (isCrossPage && action is UndoRedoAction.HeadingRemoved) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Undo: soft-delete restored strokes, restore heading row.
                    action.restoredStrokes.forEach { dao.softDeleteById(it.id, now) }
                    dao.restoreById(action.headingId, now)
                } else {
                    // Redo: soft-delete heading row, restore restored strokes.
                    dao.softDeleteById(action.headingId, now)
                    action.restoredStrokes.forEach { dao.restoreById(it.id, now) }
                }
            }

            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Undo: strokes removed, heading re-appears.
                val restoredIds = action.restoredStrokes.mapTo(mutableSetOf()) { it.id }
                updatedStrokes = preUndoStrokes.filter { it.id !in restoredIds }
                persistedStrokeIds.removeAll(restoredIds)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val heading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText)
                updatedHeadings = preUndoHeadings + heading
            } else {
                // Redo: heading removed, strokes re-appear.
                updatedHeadings = preUndoHeadings.filter { it.id != action.headingId }
                val restoredStrokes = action.restoredStrokes.map { s ->
                    LiveStroke(id = s.id, points = s.points.map { pt -> PointF(pt.x, pt.y) })
                }
                updatedStrokes = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return
        }

        // ── Cross-page HeadingTextEdited: two-phase display ──────────────────
        if (isCrossPage && action is UndoRedoAction.HeadingTextEdited) {
            val targetText = if (isUndo) action.previousText else action.newText
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                val row = dao.getObjectById(action.headingId) ?: return@withContext
                val headingObj = HeadingObject.fromJson(row.data)
                val updated = headingObj.copy(recognizedText = targetText)
                if (targetText != null) {
                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
                    }
                    val paddingPx = 8f * resources.displayMetrics.density
                    val box = row.parseBoundingBox() ?: return@withContext
                    val textWidth = textPaint.measureText(targetText)
                    val newBox = RectF(box.left, box.top, box.left + textWidth + 2f * paddingPx, box.bottom)
                    val bboxJson = """{"x":${newBox.left},"y":${newBox.top},"width":${newBox.width()},"height":${newBox.height()}}"""
                    dao.updateHeadingData(action.headingId, bboxJson, updated.toJson(), now)
                } else {
                    dao.updateHeadingData(action.headingId, row.boundingBox, updated.toJson(), now)
                }
            }

            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return
        }

        // ── Cross-page StrokesMoved: two-phase display ────────────────────────
        if (isCrossPage && action is UndoRedoAction.StrokesMoved) {
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            // Phase 1: load and display the target page in its pre-undo state.
            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            // Phase 2: apply DB update and rebuild with target positions.
            val targetStrokes  = if (isUndo) action.originalStrokes else action.movedStrokes
            val targetHeadings = if (isUndo) action.originalHeadings else action.movedHeadings
            withContext(Dispatchers.IO) {
                val ts = System.currentTimeMillis()
                db.withTransaction {
                    for (stroke in targetStrokes) {
                        val strokePoints = stroke.points.map { pt ->
                            StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = ts)
                        }
                        val strokeData = StrokeData(color = "#000000", strokeWidth = 3.0f, points = strokePoints)
                        dao.updateStrokeData(stroke.id, strokeData.toJson(), ts)
                    }
                    for (heading in targetHeadings) {
                        val bboxJson = """{"x":${heading.boundingBox.left},"y":${heading.boundingBox.top},"width":${heading.boundingBox.width()},"height":${heading.boundingBox.height()}}"""
                        dao.updateHeadingData(heading.id, bboxJson, HeadingObject(heading.strokes, heading.recognizedText).toJson(), ts)
                    }
                }
            }
            val strokeById  = targetStrokes.associateBy { it.id }
            val headingById = targetHeadings.associateBy { it.id }
            val updatedStrokes  = preUndoStrokes.map { strokeById[it.id] ?: it }
            val updatedHeadings = preUndoHeadings.map { headingById[it.id] ?: it }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            // No selection box update — user is no longer on the original page.
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return
        }

        // ── Cross-page stroke: two-phase display ──────────────────────────────
        // Phase 1 displays the target page in its pre-undo state so the user sees
        // the stroke present/absent before the action is applied.  Phase 2 applies
        // the DB change and updates the visual so the stroke visibly appears/disappears.
        if (isCrossPage && (action is UndoRedoAction.StrokeAdded || action is UndoRedoAction.StrokeErased)) {
            val strokeId = when (action) {
                is UndoRedoAction.StrokeAdded  -> action.strokeId
                is UndoRedoAction.StrokeErased -> action.strokeId
                else -> error("unreachable")
            }

            // ── Phase 1a: save and snapshot the page we are leaving ───────────
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId
            withContext(Dispatchers.IO) {
                if (snapshot != null && leavingPageId.isNotEmpty()) {
                    persistSnapshot(db, leavingPageId, snapshot)
                }
                saveStrokes(db)
            }

            // Switch page index; setupPageIds (called below) will resolve IDs.
            currentPageIndex = pages.indexOfFirst { it.id == targetPageId }.coerceAtLeast(0)

            // ── Phase 1b: load and display target page in PRE-undo state ─────
            // The DB has not been modified yet, so the stroke is still in its
            // original state — present for StrokeAdded, absent for StrokeErased.
            // Force the full render path (bypass snapshot) so strokes are in memory
            // for Phase 2's in-memory update.
            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp      = loadPageTemplateFromDb(db)
                preUndoStrokes   = deserializeStrokesFromDb(db)
                preUndoHeadings  = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            // Display: user now sees the target page with stroke in original state.
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            // ── Phase 2a: apply the DB operation ─────────────────────────────
            withContext(Dispatchers.IO) {
                when (action) {
                    is UndoRedoAction.StrokeAdded  ->
                        if (isUndo) dao.softDeleteById(strokeId, now) else dao.restoreById(strokeId, now)
                    is UndoRedoAction.StrokeErased ->
                        if (isUndo) dao.restoreById(strokeId, now) else dao.softDeleteById(strokeId, now)
                    else -> Unit
                }
            }

            // ── Phase 2b: in-memory visual update — stroke appears/disappears ─
            val shouldRemove = (action is UndoRedoAction.StrokeAdded  && isUndo) ||
                               (action is UndoRedoAction.StrokeErased && !isUndo)
            val updatedStrokes: List<LiveStroke>
            if (shouldRemove) {
                updatedStrokes = preUndoStrokes.filter { it.id != strokeId }
                persistedStrokeIds.remove(strokeId)
            } else {
                val strokeObj = withContext(Dispatchers.IO) { dao.getObjectById(strokeId) }
                val restored = strokeObj?.let {
                    try { LiveStroke(id = it.id, points = StrokeData.fromJson(it.data).toPointFs()) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction: failed to deserialize stroke $strokeId", e); null
                    }
                }
                updatedStrokes = buildList { addAll(preUndoStrokes); restored?.let { add(it) } }
                if (restored != null) persistedStrokeIds.add(strokeId)
            }
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, preUndoHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return
        }

        // ── Step 1: Cross-page navigation (template / page actions only) ──────
        // Stroke cross-page is handled above.  Page-add/delete skip navigation
        // here — currentPageIndex is set inside the DB block.
        if (isCrossPage) {
            val idx = pages.indexOfFirst { it.id == targetPageId }
            if (idx >= 0) saveAndSwitchPage(idx)
        }
        // After saveAndSwitchPage we are back on the main thread.

        // ── Step 2: Action-specific DB operation ──────────────────────────────
        when (action) {
            is UndoRedoAction.StrokeAdded -> withContext(Dispatchers.IO) {
                if (isUndo) dao.softDeleteById(action.strokeId, now)
                else        dao.restoreById(action.strokeId, now)
            }

            is UndoRedoAction.StrokeErased -> withContext(Dispatchers.IO) {
                if (isUndo) dao.restoreById(action.strokeId, now)
                else        dao.softDeleteById(action.strokeId, now)
            }

            is UndoRedoAction.PageAdded -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.pageId, now)
                    dao.softDeleteByParentId(action.pageId, now)          // layer
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.softDeleteByParentId(layer.id, now) // strokes
                    // For insert-before: the original page returns to action.pageIndex after removal.
                    // For insert-after: the original page was at action.pageIndex - 1.
                    currentPageIndex = if (action.insertedBefore) action.pageIndex
                                       else (action.pageIndex - 1).coerceAtLeast(0)
                } else {
                    // Redo: restore the page and its layer (strokes were never deleted on add).
                    dao.restoreById(action.pageId, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.restoreById(layer.id, now)
                    currentPageIndex = action.pageIndex
                }
            }

            is UndoRedoAction.PageDeleted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore the page row and all children soft-deleted at the same instant.
                    dao.restoreById(action.pageId, now)
                    dao.restoreChildrenDeletedSince(action.pageId, action.deletedAt, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) {
                        dao.restoreChildrenDeletedSince(layer.id, action.deletedAt, now)
                    }
                    currentPageIndex = action.pageIndex
                } else {
                    // Redo: soft-delete the page and all surviving children.
                    dao.softDeleteById(action.pageId, now)
                    dao.softDeleteByParentId(action.pageId, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.softDeleteByParentId(layer.id, now)
                    // currentPageIndex will be coerced by setupPageIds in loadCurrentPage.
                }
            }

            is UndoRedoAction.TemplateChanged -> {
                val templateId = if (isUndo) action.previousTemplateId else action.newTemplateId
                withContext(Dispatchers.IO) {
                    persistPageTemplate(dao, action.pageId, templateId ?: "")
                }
                // Visual update is handled by the full page reload in step 3b below.
            }

            is UndoRedoAction.PageEraseAll -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore all content soft-deleted by the clear (timestamp-based, type-agnostic).
                    dao.restoreChildrenDeletedSince(action.layerId, action.deletedAt, now)
                    // Belt-and-suspenders: restore heading rows explicitly by ID.
                    // restoreChildrenDeletedSince filters by deletedAt >= since, which should cover
                    // these rows, but explicit ID restore ensures headings are never silently skipped.
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    // Redo: soft-delete all surviving content on this layer.
                    dao.softDeleteByParentId(action.layerId, now)
                }
            }

            is UndoRedoAction.PagePasted -> {
                if (isUndo) {
                    val deletedAt = withContext(Dispatchers.IO) {
                        val ts = System.currentTimeMillis()
                        dao.softDeleteById(action.pageId, ts)
                        dao.softDeleteByParentId(action.pageId, ts)          // layer
                        val layer = dao.getLayerForPageAny(action.pageId)
                        if (layer != null) dao.softDeleteByParentId(layer.id, ts) // strokes
                        ts
                    }
                    // Store the soft-delete timestamp so redo can restore exactly these rows.
                    undoRedoManager.amendLastRedo(action.copy(undoDeletedAt = deletedAt))
                    currentPageIndex = (action.pageIndex - 1).coerceAtLeast(0)
                } else {
                    withContext(Dispatchers.IO) {
                        dao.restoreById(action.pageId, now)
                        val layer = dao.getLayerForPageAny(action.pageId)
                        if (layer != null) {
                            dao.restoreById(layer.id, now)
                            if (action.undoDeletedAt > 0) {
                                dao.restoreChildrenDeletedSince(layer.id, action.undoDeletedAt, now)
                            }
                        }
                    }
                    currentPageIndex = action.pageIndex
                }
            }

            is UndoRedoAction.PageMoved -> withContext(Dispatchers.IO) {
                // Undo: put the page back where it was; redo: put it at the target position.
                val afterId = if (isUndo) action.previousAfterPageId else action.targetPageId
                movePageToAfter(db, action.pageId, afterId)
                // currentPageIndex is resolved by setupPageIds in the full reload below.
            }

            is UndoRedoAction.LassoErased -> withContext(Dispatchers.IO) {
                if (isUndo) action.strokeIds.forEach { dao.restoreById(it, now) }
                else        action.strokeIds.forEach { dao.softDeleteById(it, now) }
            }

            is UndoRedoAction.LassoCut -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                }
            }

            is UndoRedoAction.LassoDeleted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                }
            }

            is UndoRedoAction.LassoPasted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                }
            }

            is UndoRedoAction.StrokesMoved -> withContext(Dispatchers.IO) {
                val targetStrokes  = if (isUndo) action.originalStrokes  else action.movedStrokes
                val targetHeadings = if (isUndo) action.originalHeadings else action.movedHeadings
                db.withTransaction {
                    for (stroke in targetStrokes) {
                        val strokePoints = stroke.points.map { pt ->
                            StrokePoint(x = pt.x, y = pt.y, pressure = null, tilt = null, timestamp = now)
                        }
                        val strokeData = StrokeData(color = "#000000", strokeWidth = 3.0f, points = strokePoints)
                        dao.updateStrokeData(stroke.id, strokeData.toJson(), now)
                    }
                    for (heading in targetHeadings) {
                        val bb = heading.boundingBox
                        val bbJson = """{"x":${bb.left},"y":${bb.top},"width":${bb.width()},"height":${bb.height()}}"""
                        val dataJson = HeadingObject(strokes = heading.strokes, recognizedText = heading.recognizedText).toJson()
                        dao.updateHeadingData(heading.id, bbJson, dataJson, now)
                    }
                }
            }

            is UndoRedoAction.HeadingCreated -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.headingId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.headingId, now)
                }
            }

            is UndoRedoAction.HeadingRemoved -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Undo: soft-delete restored strokes, restore heading row.
                    action.restoredStrokes.forEach { dao.softDeleteById(it.id, now) }
                    dao.restoreById(action.headingId, now)
                } else {
                    // Redo: soft-delete heading row, restore restored strokes.
                    dao.softDeleteById(action.headingId, now)
                    action.restoredStrokes.forEach { dao.restoreById(it.id, now) }
                }
            }

            is UndoRedoAction.HeadingTextEdited -> withContext(Dispatchers.IO) {
                val targetText = if (isUndo) action.previousText else action.newText
                val row = dao.getObjectById(action.headingId) ?: return@withContext
                val headingObj = HeadingObject.fromJson(row.data)
                val updated = headingObj.copy(recognizedText = targetText)
                if (targetText != null) {
                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
                    }
                    val paddingPx = 8f * resources.displayMetrics.density
                    val box = row.parseBoundingBox() ?: return@withContext
                    val textWidth = textPaint.measureText(targetText)
                    val newBox = RectF(box.left, box.top, box.left + textWidth + 2f * paddingPx, box.bottom)
                    val bboxJson = """{"x":${newBox.left},"y":${newBox.top},"width":${newBox.width()},"height":${newBox.height()}}"""
                    dao.updateHeadingData(action.headingId, bboxJson, updated.toJson(), now)
                } else {
                    // Restoring null: keep existing bounding box, just clear the text field.
                    dao.updateHeadingData(action.headingId, row.boundingBox, updated.toJson(), now)
                }
            }
        }
        // After each when-branch we are back on the main thread.

        // ── Step 3a-strokes-moved: Same-page StrokesMoved — optimised in-memory update ─
        if (action is UndoRedoAction.StrokesMoved) {
            val targetStrokes  = if (isUndo) action.originalStrokes else action.movedStrokes
            val targetHeadings = if (isUndo) action.originalHeadings else action.movedHeadings
            val strokeById = targetStrokes.associateBy { it.id }
            val updatedStrokes = drawingView.getStrokes().map { strokeById[it.id] ?: it }
            val updatedHeadings = if (targetHeadings.isNotEmpty()) {
                val headingById = targetHeadings.associateBy { it.id }
                drawingView.getHeadings().map { headingById[it.id] ?: it }
            } else {
                drawingView.getHeadings()
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            // If the moved objects are the active selection, update the selection box.
            val movedIds = (action.originalStrokes.map { it.id } + action.originalHeadings.map { it.id }).toSet()
            if (movedIds == selectedObjectIds) {
                val unionBounds = computeUnionBoundingBox(targetStrokes, targetHeadings)
                val pad = 8f * resources.displayMetrics.density
                unionBounds.inset(-pad, -pad)
                drawingView.setLassoOverlay(null, unionBounds)
                drawingView.lassoSelectedIds = movedIds
                updateFloatingSelectionToolbar(unionBounds)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-batch: Same-page LassoErased — optimised in-memory update ─
        // strokeIds contains ALL erased IDs (strokes + headings); headingIds is the subset.
        if (action is UndoRedoAction.LassoErased) {
            val idsSet = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val pureStrokeIdsSet = idsSet - headingIdsSet
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo: re-erase → remove from in-memory lists.
                updatedStrokes  = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(pureStrokeIdsSet)
            } else {
                // Undo: restore strokes from DB; use action.headings for headings (no DB fetch).
                val restoredStrokes = withContext(Dispatchers.IO) {
                    pureStrokeIdsSet.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoErased: failed to deserialize ${obj.id}", e); null
                            }
                        }
                    }
                }
                // If headings field is populated (new format), use it directly.
                // Otherwise fall back to DB fetch for backward compat with old undo stacks.
                val restoredHeadings: List<HeadingStroke> = if (action.headings.isNotEmpty()) {
                    action.headings
                } else if (headingIdsSet.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        headingIdsSet.mapNotNull { id ->
                            dao.getObjectById(id)?.let { obj ->
                                val box = obj.parseBoundingBox() ?: return@mapNotNull null
                                val headingObj = runCatching { HeadingObject.fromJson(obj.data) }.getOrNull() ?: return@mapNotNull null
                                HeadingStroke(id = obj.id, boundingBox = box, strokes = headingObj.strokes, recognizedText = headingObj.recognizedText)
                            }
                        }
                    }
                } else emptyList()
                updatedStrokes  = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings = drawingView.getHeadings() + restoredHeadings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-cut: Same-page LassoCut — optimised in-memory update ─────────
        if (action is UndoRedoAction.LassoCut) {
            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo cut: remove from in-memory lists and repopulate clipboard.
                updatedStrokes  = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
                val clipBox = computeUnionBoundingBox(action.strokes, action.headings)
                NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
                    strokes  = action.strokes.map { s -> LiveStroke(s.id, s.points.map { pt -> PointF(pt.x, pt.y) }) },
                    headings = action.headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                        h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                        recognizedText = h.recognizedText) },
                    boundingBox = clipBox,
                )
                updateLassoButtonIcon()
            } else {
                // Undo cut: fetch restored strokes from DB; use action.headings for headings.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoCut: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings = drawingView.getHeadings() + action.headings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-delete: Same-page LassoDeleted — optimised in-memory update ─
        if (action is UndoRedoAction.LassoDeleted) {
            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (!isUndo) {
                // Redo delete: remove from in-memory lists.
                updatedStrokes  = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Undo delete: fetch restored strokes from DB; use action.headings for headings.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoDeleted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings = drawingView.getHeadings() + action.headings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-heading: Same-page HeadingCreated — optimised in-memory update ─
        if (action is UndoRedoAction.HeadingCreated) {
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Heading soft-deleted, original strokes restored.
                updatedHeadings = drawingView.getHeadings().filter { it.id != action.headingId }
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction HeadingCreated: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                // Original strokes soft-deleted, heading restored.
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = drawingView.getStrokes().filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val newHeading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText)
                updatedHeadings = drawingView.getHeadings() + newHeading
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-unheading: Same-page HeadingRemoved — optimised in-memory update ─
        if (action is UndoRedoAction.HeadingRemoved) {
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Undo: remove restored strokes, re-add heading.
                val restoredIds = action.restoredStrokes.mapTo(mutableSetOf()) { it.id }
                updatedStrokes = drawingView.getStrokes().filter { it.id !in restoredIds }
                persistedStrokeIds.removeAll(restoredIds)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val heading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText)
                updatedHeadings = drawingView.getHeadings() + heading
            } else {
                // Redo: remove heading, add restored strokes back.
                updatedHeadings = drawingView.getHeadings().filter { it.id != action.headingId }
                val restoredStrokes = action.restoredStrokes.map { s ->
                    LiveStroke(id = s.id, points = s.points.map { pt -> PointF(pt.x, pt.y) })
                }
                updatedStrokes = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-paste: Same-page LassoPasted — optimised in-memory update ──
        if (action is UndoRedoAction.LassoPasted) {
            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Undo paste → remove the pasted strokes and headings.
                updatedStrokes  = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Redo paste → fetch restored strokes and headings from DB and append.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs()) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoPasted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                val restoredHeadings = withContext(Dispatchers.IO) {
                    action.headingIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            val box = obj.parseBoundingBox() ?: return@mapNotNull null
                            val hObj = runCatching { HeadingObject.fromJson(obj.data) }.getOrNull() ?: return@mapNotNull null
                            HeadingStroke(id = obj.id, boundingBox = box, strokes = hObj.strokes, recognizedText = hObj.recognizedText)
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings = drawingView.getHeadings() + restoredHeadings
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a-text-edit: Same-page HeadingTextEdited — in-memory update ─
        if (action is UndoRedoAction.HeadingTextEdited) {
            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            // Update selection box if the edited heading is still selected.
            val updatedHeading = updatedHeadings.find { it.id == action.headingId }
            if (updatedHeading != null && action.headingId in selectedObjectIds) {
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(updatedHeading.boundingBox).also { it.inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                    updateFloatingSelectionToolbar(selBox)
                }
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3a: Same-page stroke — optimised in-memory update ────────────
        // No eraseAll(); one EPD handoff via loadStrokesWithBitmap.
        // The snapshot stale-check uses MAX(stroke.updatedAt) > page.updatedAt,
        // which the DB op above already guarantees — no explicit invalidation needed.
        if (action is UndoRedoAction.StrokeAdded || action is UndoRedoAction.StrokeErased) {
            val strokeId = when (action) {
                is UndoRedoAction.StrokeAdded  -> action.strokeId
                is UndoRedoAction.StrokeErased -> action.strokeId
                else -> error("unreachable")
            }
            val shouldRemove = (action is UndoRedoAction.StrokeAdded  && isUndo) ||
                               (action is UndoRedoAction.StrokeErased && !isUndo)

            val updatedStrokes: List<LiveStroke>
            if (shouldRemove) {
                updatedStrokes = drawingView.getStrokes().filter { it.id != strokeId }
                persistedStrokeIds.remove(strokeId)
            } else {
                val strokeObj = withContext(Dispatchers.IO) { dao.getObjectById(strokeId) }
                val restored = strokeObj?.let {
                    try { LiveStroke(id = it.id, points = StrokeData.fromJson(it.data).toPointFs()) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction: failed to deserialize stroke $strokeId", e); null
                    }
                }
                updatedStrokes = buildList {
                    addAll(drawingView.getStrokes())
                    restored?.let { add(it) }
                }
                if (restored != null) persistedStrokeIds.add(strokeId)
            }

            val templateBmp = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return
        }

        // ── Step 3b: Page / template actions — full reload ────────────────────
        val snapshotPageId: String? = when (action) {
            is UndoRedoAction.TemplateChanged -> action.pageId
            is UndoRedoAction.PageDeleted     -> if (isUndo) action.pageId else null
            is UndoRedoAction.PageAdded       -> if (!isUndo) action.pageId else null
            is UndoRedoAction.PagePasted      -> if (!isUndo) action.pageId else null  // redo restores strokes
            is UndoRedoAction.PageEraseAll     -> action.pageId  // strokes change on both undo and redo
            else                              -> null
        }
        if (snapshotPageId != null) {
            withContext(Dispatchers.IO) { invalidatePageSnapshot(db, snapshotPageId) }
        }

        persistedStrokeIds.clear()
        drawingView.eraseAll()
        val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
        displayPage(result)
        updatePageIndicator()
        saveLastOpenedPage(currentPageId)
        postDisplayWork(db, result)
    }

    /**
     * Remove the `snapshot` field from the page row's `data` JSON, forcing the next
     * [loadCurrentPage] to take the full render path and capture a fresh snapshot.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun invalidatePageSnapshot(db: SoilDatabase, pageId: String) {
        val page = db.notebookDao().getObjectById(pageId) ?: return
        val dataObj = try { org.json.JSONObject(page.data) } catch (e: Exception) { return }
        if (!dataObj.has("snapshot")) return   // nothing to remove
        dataObj.remove("snapshot")
        db.notebookDao().updateData(pageId, dataObj.toString(), System.currentTimeMillis())
        Slog.d(TAG) { "invalidatePageSnapshot: cleared for page $pageId" }
    }

    /**
     * Decode the full-resolution template bitmap for [templateId] directly from the
     * template row in [db].  Returns null if the template row is not found or the image
     * cannot be decoded.  Must be called on [Dispatchers.IO].
     */
    private suspend fun loadTemplateBitmapById(db: SoilDatabase, templateId: String): Bitmap? {
        val templateObj = db.notebookDao().getTemplateById(templateId) ?: return null
        return try {
            val dataObj = org.json.JSONObject(templateObj.data)
            val b64 = dataObj.getString("image")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // Bounded decode (M-1): cap to the view size (fall back to MAX_DIMENSION before layout).
            val view = drawingView.asView()
            val reqW = view.width.takeIf  { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            val reqH = view.height.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            BitmapDecode.decodeSampled(bytes, reqW, reqH)
        } catch (e: Exception) {
            Log.e(TAG, "loadTemplateBitmapById: failed to decode template $templateId", e)
            null
        }
    }

    /**
     * Returns the app-private JSON file used to persist the undo/redo stacks while the
     * app is backgrounded.  The filename encodes the notebook path so each notebook has
     * its own independent history file.  Never accessible to the user.
     */
    private fun undoRedoPersistenceFile(notebookPath: String): java.io.File =
        java.io.File(filesDir, "undo_redo_${notebookPath.hashCode()}.json")

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Physical screen bounds in pixels — used for page and layer bounding boxes. */
    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    private fun isBooxDevice() =
        Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")

    // ── Template import ───────────────────────────────────────────────────────

    private fun performTemplateImport(uri: Uri) {
        lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } ?: "template.png"

            val destDir = getExternalFilesDir("Templates")!!.also { it.mkdirs() }
            val destFile = File(destDir, displayName)

            if (destFile.exists()) {
                val dialog = AlertDialog.Builder(this@DrawingActivity)
                    .setTitle("Template already exists")
                    .setMessage("\"${destFile.nameWithoutExtension}\" already exists. Overwrite it?")
                    .setPositiveButton("Overwrite") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { copyUriToFile(uri, destFile) }
                            onTemplateImportDone?.invoke()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.show()
                dialog.window?.setElevation(0f)
                dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            } else {
                withContext(Dispatchers.IO) { copyUriToFile(uri, destFile) }
                onTemplateImportDone?.invoke()
            }
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
