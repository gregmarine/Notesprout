package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
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
    private lateinit var pageGestureDetector: GestureDetector
    private var isEraserActive = false

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

        binding.btnAddPage.setOnClickListener {
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
                    lifecycleScope.launch {
                        // No snapshot needed — the page being deleted is discarded.
                        withContext(Dispatchers.IO) { deletePage(db) }
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
                    // Capture the stroke IDs before clearing so we can soft-delete their DB rows.
                    val strokesToDelete = drawingView.getStrokes()
                    drawingView.clearCanvas()
                    // All strokes removed from memory and will be soft-deleted from DB.
                    // Clear the persisted-ID registry; no snapshot needed for a user-initiated clear.
                    persistedStrokeIds.clear()
                    if (db != null && strokesToDelete.isNotEmpty()) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                val dao = db.notebookDao()
                                for (stroke in strokesToDelete) {
                                    dao.softDeleteById(stroke.id, now)
                                }
                            }
                        }
                    }
                }
                .create()
            dialog.show()
            // Style after show() — window only exists once the dialog is displayed.
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        }

        binding.btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            binding.btnEraser.text = if (isEraserActive) "Pen" else "Erase"
            drawingView.setEraserMode(isEraserActive)
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
            if (db != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.notebookDao().softDeleteById(strokeId, System.currentTimeMillis())
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
                    withContext(Dispatchers.IO) { saveStrokes(db) }
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

        // ── Page swipe gesture ────────────────────────────────────────────────
        // Initialised here; fed events via dispatchTouchEvent() so ALL finger
        // swipes are seen regardless of which child view (drawingView) consumes
        // the event.  On BOOX, touchHelper swallows stylus events through the
        // hardware overlay and never bubbles them back, so a container-level
        // touch listener would miss them.  dispatchTouchEvent runs before any
        // child dispatch, so we always get the DOWN that arms the fling tracker.
        //
        // onDown() MUST return true — SimpleOnGestureListener returns false by
        // default, which tells the GestureDetector the gesture sequence is
        // uninteresting and silently discards all subsequent events including
        // the ACTION_UP/fling.
        pageGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true   // arm the fling tracker

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    // Only honour horizontal-dominant flings.
                    if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                    return if (velocityX < 0) {
                        val next = currentPageIndex + 1
                        if (next <= pages.lastIndex) {
                            navigateToPage(next); true
                        } else {
                            // Already on the last page — swipe left inserts a new page,
                            // identical to tapping the + button.
                            val db = soilDatabase ?: return false
                            lifecycleScope.launch {
                                val snapshot = drawingView.captureSnapshot()
                                val leavingPageId = currentPageId
                                withContext(Dispatchers.IO) {
                                    if (snapshot != null && leavingPageId.isNotEmpty()) {
                                        persistSnapshot(db, leavingPageId, snapshot)
                                    }
                                    addPage(db)
                                }
                                drawingView.clearCanvas()
                                val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                                displayPage(result)
                                updatePageIndicator()
                                saveLastOpenedPage(currentPageId)
                                postDisplayWork(db, result)
                            }
                            true
                        }
                    } else {
                        val prev = currentPageIndex - 1
                        if (prev >= 0) { navigateToPage(prev); true } else false
                    }
                }
            },
        )

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
     * Feed every touch event to the page gesture detector before normal dispatch.
     *
     * This is required because on BOOX the Onyx TouchHelper consumes stylus events
     * at the view level and never bubbles them back to a parent touch listener.
     * Running the detector here means the DOWN event always arms the fling tracker,
     * and the subsequent UP is always seen — regardless of which child consumed the
     * intermediate move events.  We do not consume events ourselves (return super),
     * so the drawing view still receives everything it expects.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Only feed finger touches to the page gesture detector.
        // Stylus and eraser events belong to the drawing engine exclusively —
        // pen swipes must never accidentally trigger page navigation.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            pageGestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
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
     */
    private fun displayPage(result: PageLoadResult) {
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
     * Called from: page navigation (before switch), [closeNotebook].
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun saveStrokes(db: SoilDatabase) {
        val layerId = currentLayerId
        if (layerId.isEmpty()) {
            Log.w(TAG, "saveStrokes: currentLayerId is empty — skipping")
            return
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
            return
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
        persistedStrokeIds.addAll(newStrokes.map { it.id })
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

        // Persist the page's template property in the background.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                persistPageTemplate(db.notebookDao(), pageId, templateId)
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
     * Delete the current page, its layer, and all strokes under the layer.
     *
     * If the notebook would become empty, a fresh page + layer is bootstrapped
     * automatically so the user is never left with a blank notebook.
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun deletePage(db: SoilDatabase) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()

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
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            // Capture snapshot of the page we are leaving (main thread, before clear).
            val snapshot      = drawingView.captureSnapshot()
            val leavingPageId = currentPageId

            withContext(Dispatchers.IO) {
                // Persist snapshot and save strokes concurrently in the same IO block.
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
    }

    /** Refresh the page indicator overlay text. Call on the main thread. */
    private fun updatePageIndicator() {
        binding.tvPageIndicator.text = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}"
    }

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
