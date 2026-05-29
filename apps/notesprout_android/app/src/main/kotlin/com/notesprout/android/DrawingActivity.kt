package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.withTransaction
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.data.StrokePoint
import com.notesprout.android.databinding.ActivityDrawingBinding
import com.notesprout.android.drawing.DrawingView
import com.notesprout.android.drawing.GenericDrawingView
import com.notesprout.android.drawing.OnyxDrawingView
import com.notesprout.android.history.UndoRedoAction
import com.notesprout.android.history.UndoRedoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

class DrawingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NoteSprout"

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
    )

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var drawingView: DrawingView
    private var isEraserActive = false

    // ── Two-finger page swipe state ───────────────────────────────────────────
    // GestureDetector is single-finger only.  We use a VelocityTracker + a
    // simple state machine so that only genuine two-finger flings turn pages.
    // This prevents accidental page turns from resting palms / pinkies.
    private var twoFingerActive = false
    private var pageSwipeVelocityTracker: VelocityTracker? = null

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null

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
            val db = soilDatabase ?: return@setOnClickListener
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
                drawingView.clearCanvas()
                val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                displayPage(result)
                updatePageIndicator()
                saveLastOpenedPage(currentPageId)
                postDisplayWork(db, result)
            }
        }

        binding.btnInsertPageAfter.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            lifecycleScope.launch {
                // Capture snapshot of the page we are leaving (main thread — before clearing).
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
                drawingView.clearCanvas()
                val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                displayPage(result)
                updatePageIndicator()
                saveLastOpenedPage(currentPageId)
                postDisplayWork(db, result)
            }
        }

        binding.btnDeletePage.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            // Confirmation dialog — flat NoteSprout style (elevation=0, shape_bordered).
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
                        drawingView.clearCanvas()
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

        binding.btnClear.setOnClickListener {
            val db = soilDatabase
            val dialog = AlertDialog.Builder(this)
                .setMessage("Clear this page?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    // Snapshot the page/layer IDs now — they may change before the coroutine runs.
                    val clearedPageId  = currentPageId
                    val clearedLayerId = currentLayerId
                    // Capture the stroke IDs before clearing so we can soft-delete their DB rows.
                    val strokesToDelete = drawingView.getStrokes()
                    drawingView.clearCanvas()
                    // All strokes removed from memory and will be soft-deleted from DB.
                    // Clear the persisted-ID registry; no snapshot needed for a user-initiated clear.
                    persistedStrokeIds.clear()
                    if (db != null && strokesToDelete.isNotEmpty()) {
                        lifecycleScope.launch {
                            val deletedAt = System.currentTimeMillis()
                            withContext(Dispatchers.IO) {
                                val dao = db.notebookDao()
                                for (stroke in strokesToDelete) {
                                    dao.softDeleteById(stroke.id, deletedAt)
                                }
                            }
                            // Record the clear as a single undoable action after the DB writes succeed.
                            undoRedoManager.push(
                                UndoRedoAction.PageCleared(clearedPageId, clearedLayerId, deletedAt)
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
            if (isEraserActive) {
                isEraserActive = false
                drawingView.setEraserMode(false)
            }
            binding.btnPen.isSelected = true
            binding.btnEraser.isSelected = false
        }

        binding.btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnEraser.isSelected = isEraserActive
            binding.btnPen.isSelected = !isEraserActive
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
            ).show()
        }

        binding.btnCover.setOnClickListener {
            openCoverDialog()
        }

        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }
        updateUndoRedoButtons()  // both disabled initially (empty stacks)

        // Initial tool state: pen is selected by default
        binding.btnPen.isSelected = true
        binding.btnEraser.isSelected = false

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

        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Pass the toolbar's pixel height to the drawing view after layout so
        // BOOX can set the correct setLimitRect exclusion zone.
        binding.drawingToolbar.doOnLayout { toolbar ->
            drawingView.setToolbarHeight(toolbar.height)
        }

        // ── Page swipe gesture (two-finger) ──────────────────────────────────
        // Page navigation requires a deliberate two-finger horizontal fling so
        // that incidental contact (resting pinky, palm) never turns the page.
        //
        // GestureDetector is single-finger only, so we use a VelocityTracker +
        // state machine instead.  Events are fed in dispatchTouchEvent() before
        // any child dispatch, ensuring BOOX's TouchHelper cannot swallow them.
        //
        // State machine:
        //   ACTION_DOWN          → reset tracker; single-finger, not yet active
        //   ACTION_POINTER_DOWN  → second finger arrived; arm twoFingerActive
        //   ACTION_MOVE          → feed tracker while twoFingerActive
        //   ACTION_POINTER_UP    → 2→1 lift; compute velocity and fire if valid
        //   ACTION_UP / CANCEL   → reset (last finger gone or gesture cancelled)
        // No-op on init — handled entirely in dispatchTouchEvent / helpers below.

        // ── Open the Room DB ──────────────────────────────────────────────────
        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH)
        if (notebookPath != null) {
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
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
        // Safety net: if the activity is destroyed without the user tapping Close
        // (e.g. system kill), ensure the DB is still closed cleanly.
        closeNotebook()
    }

    /**
     * Feed every touch event to the two-finger page swipe detector before normal dispatch.
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
            handleTwoFingerPageSwipe(event)
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Two-finger horizontal fling detector for page navigation.
     *
     * Arms when a second finger touches down; fires when one of the two fingers
     * lifts (ACTION_POINTER_UP with pointerCount == 2 → going to 1).  Uses a
     * [VelocityTracker] so the fling threshold matches Android's standard minimum
     * fling velocity — fast enough to be deliberate, not so fast it is tiring.
     *
     * Requiring two simultaneous fingers prevents accidental page turns from a
     * resting pinky or palm that the stylus palm-rejection misses.
     */
    private fun handleTwoFingerPageSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // First finger — reset; not yet a two-finger gesture.
                twoFingerActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // Second finger arrived — arm the tracker.
                    twoFingerActive = true
                    pageSwipeVelocityTracker?.recycle()
                    pageSwipeVelocityTracker = VelocityTracker.obtain().also {
                        it.addMovement(event)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerActive) {
                    pageSwipeVelocityTracker?.addMovement(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One of the two fingers just lifted (2 → 1).  Evaluate the fling.
                if (twoFingerActive && event.pointerCount == 2) {
                    val tracker = pageSwipeVelocityTracker ?: return
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000)
                    // Use pointer 0 for velocity (the anchor finger).
                    val vx = tracker.getXVelocity(0)
                    val vy = tracker.getYVelocity(0)
                    evaluatePageFling(vx, vy)
                    twoFingerActive = false
                    tracker.recycle()
                    pageSwipeVelocityTracker = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Last finger gone or gesture cancelled — clean up.
                twoFingerActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
            }
        }
    }

    /**
     * Interprets a fling velocity pair and navigates pages if the gesture qualifies.
     *
     * Qualifications:
     * - Horizontal dominant (|vx| > |vy|)
     * - At or above [ViewConfiguration.scaledMinimumFlingVelocity]
     *
     * Swipe left (vx < 0) → next page (or insert new page on last page).
     * Swipe right (vx > 0) → previous page.
     */
    private fun evaluatePageFling(velocityX: Float, velocityY: Float) {
        // Must be horizontal-dominant.
        if (Math.abs(velocityX) < Math.abs(velocityY)) return
        // Must meet the system minimum fling velocity.
        val minVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat()
        if (Math.abs(velocityX) < minVelocity) return

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
                    drawingView.clearCanvas()
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

    // ── Notebook DB lifecycle ─────────────────────────────────────────────────

    /**
     * Saves strokes, captures a final snapshot, runs housekeeping PRAGMAs, then closes
     * the Room database.
     *
     * Snapshot is captured here on the main thread (before [soilDatabase] is cleared) so
     * the close button and back-press paths always persist the current page state.
     * [onWindowFocusChanged] fires AFTER [finish], so its [onSnapshotReady] callback would
     * find [soilDatabase] already null — capturing here is the only reliable close-path hook.
     *
     * Blocking — all IO runs on [Dispatchers.IO] via [runBlocking]; [SoilDatabase.close]
     * is called on the main thread after the coroutine completes so [finish] only fires
     * once the file is fully sealed. Idempotent — guarded by a null check.
     */
    private fun closeNotebook() {
        val db = soilDatabase ?: return
        soilDatabase = null   // mark as closed before any potentially-throwing work

        // Clear history and remove any on-disk persistence file — notebook is done.
        undoRedoManager.clear()
        val nbPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH)
        if (nbPath != null) undoRedoPersistenceFile(nbPath).takeIf { it.exists() }?.delete()

        // Capture snapshot on the main thread — View operations must run here.
        // Must happen before the runBlocking block so the bitmap data is ready for IO.
        val snapshot = drawingView.captureSnapshot()
        val pageId   = currentPageId

        runBlocking {
            withContext(Dispatchers.IO) {
                // Persist snapshot for the page we are closing (mirrors navigateToPage).
                if (snapshot != null && pageId.isNotEmpty()) {
                    persistSnapshot(db, pageId, snapshot)
                }
                saveStrokes(db)
                db.openHelper.writableDatabase.apply {
                    query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
                    query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }
            }
        }

        db.close()

        // Delete the empty -journal file Android leaves during DB initialisation.
        val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
        File("$path-journal").takeIf { it.exists() }?.delete()
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
        Log.d(TAG, "setupPageIds: page $currentPageId (${currentPageIndex + 1}/${pages.size})")
        val layer = dao.getLayerForPage(currentPageId)
        if (layer == null) {
            Log.w(TAG, "setupPageIds: no layer for page $currentPageId")
            currentLayerId = ""; return
        }
        currentLayerId = layer.id
        Log.d(TAG, "setupPageIds: layerId=$currentLayerId")
    }

    /**
     * Deserialize stroke rows for [currentLayerId] and return them as [LiveStroke]s.
     * Also repopulates [persistedStrokeIds].
     * Must be called on [Dispatchers.IO] AFTER [setupPageIds] has set [currentLayerId].
     */
    private suspend fun deserializeStrokesFromDb(db: SoilDatabase): List<LiveStroke> {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return emptyList()
        val strokeObjects = db.notebookDao().getStrokesForLayer(layerId)
        Log.d(TAG, "deserializeStrokesFromDb: found ${strokeObjects.size} rows")
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
        val maxStroke = db.notebookDao().getMaxStrokeUpdatedAt(layerId) ?: 0L
        if (maxStroke > page.updatedAt) {
            Log.d(TAG, "tryLoadSnapshotBitmap: stale (maxStroke=$maxStroke > page=${page.updatedAt})")
            return null
        }

        val bytes       = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return null }
        val snapshotBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val w = drawingView.asView().width
        val h = drawingView.asView().height
        if (w == 0 || h == 0) { snapshotBmp.recycle(); return null }

        // Build composite: white → template → strokes-only snapshot (transparent PNG)
        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(composite)
        c.drawColor(Color.WHITE)
        templateBitmap?.let { c.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        c.drawBitmap(snapshotBmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        snapshotBmp.recycle()

        Log.d(TAG, "tryLoadSnapshotBitmap: hit for page $pageId")
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
        val snapshotBitmap  = tryLoadSnapshotBitmap(db, templateBitmap)
        return if (snapshotBitmap != null) {
            PageLoadResult(emptyList(), templateBitmap, snapshotBitmap, usedSnapshot = true)
        } else {
            val strokes      = deserializeStrokesFromDb(db)
            val renderBitmap = drawingView.buildRenderBitmap(strokes, templateBitmap)
            PageLoadResult(strokes, templateBitmap, renderBitmap, usedSnapshot = false)
        }
    }

    /**
     * Apply a [PageLoadResult] to the drawing view on the main thread.
     * Also keeps [currentTemplateBitmap] in sync for the undo/redo optimised stroke path.
     */
    private fun displayPage(result: PageLoadResult) {
        currentTemplateBitmap = result.templateBitmap
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
                Log.d(TAG, "postDisplayWork(snapshot): silently loaded ${strokes.size} strokes for $pageId")
                drawingView.setStrokeListSilently(strokes)
            }
        } else {
            // Full render just completed — capture snapshot for next time.
            val snapshot = drawingView.captureSnapshot()
            val pageId   = currentPageId
            if (snapshot != null && pageId.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { persistSnapshot(db, pageId, snapshot) }
                    Log.d(TAG, "postDisplayWork(full): persisted snapshot for $pageId")
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
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

        Log.d(TAG, "saveStrokes: ${newStrokes.size} new / ${currentStrokes.size} total strokes")

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
        Log.d(TAG, "persistSnapshot: saved for page $pageId (${snapshot.length} chars)")
    }

    // ── Template operations ───────────────────────────────────────────────────

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
        drawingView.clearCanvas()

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
        drawingView.clearCanvas()
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
     *   [clearCanvas] → [loadCurrentPage] → [displayPage]
     */
    private suspend fun executeAction(db: SoilDatabase, action: UndoRedoAction, isUndo: Boolean) {
        val now = System.currentTimeMillis()
        val dao = db.notebookDao()

        val targetPageId: String? = when (action) {
            is UndoRedoAction.StrokeAdded     -> action.pageId
            is UndoRedoAction.StrokeErased    -> action.pageId
            is UndoRedoAction.TemplateChanged -> action.pageId
            is UndoRedoAction.PageCleared     -> action.pageId
            else                              -> null
        }
        val isCrossPage = targetPageId != null && targetPageId != currentPageId

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
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp   = loadPageTemplateFromDb(db)
                preUndoStrokes = deserializeStrokesFromDb(db)
            }
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp)
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
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp)
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

            is UndoRedoAction.PageCleared -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore every stroke that was soft-deleted at the clear's timestamp.
                    dao.restoreChildrenDeletedSince(action.layerId, action.deletedAt, now)
                } else {
                    // Redo: soft-delete all surviving strokes on this layer.
                    dao.softDeleteByParentId(action.layerId, now)
                }
            }
        }
        // After each when-branch we are back on the main thread.

        // ── Step 3a: Same-page stroke — optimised in-memory update ────────────
        // No clearCanvas(); one EPD handoff via loadStrokesWithBitmap.
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
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp)
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
            is UndoRedoAction.PageCleared     -> action.pageId  // strokes change on both undo and redo
            else                              -> null
        }
        if (snapshotPageId != null) {
            withContext(Dispatchers.IO) { invalidatePageSnapshot(db, snapshotPageId) }
        }

        persistedStrokeIds.clear()
        drawingView.clearCanvas()
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
        Log.d(TAG, "invalidatePageSnapshot: cleared for page $pageId")
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
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
}
