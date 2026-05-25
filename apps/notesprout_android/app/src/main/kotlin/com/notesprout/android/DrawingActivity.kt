package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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

        /**
         * Maximum number of pages held in [strokeCache] at once.
         * LRU eviction drops the least-recently-accessed page when this limit is exceeded.
         * At ~1 KB per stroke and a worst-case 610-stroke page, 10 entries ≈ 6 MB max.
         */
        private const val MAX_CACHE_PAGES = 10
    }

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

    /** All live pages in sorted order. Populated (and refreshed) by [loadStrokesFromDb]. */
    private var pages: MutableList<NotebookObject> = mutableListOf()

    /** Index into [pages] for the currently displayed page. */
    private var currentPageIndex: Int = 0

    /** ID of the currently displayed page row. Set by [loadStrokesFromDb]. */
    private var currentPageId: String = ""

    /** ID of the content layer under [currentPageId]. Set by [loadStrokesFromDb]. */
    private var currentLayerId: String = ""

    // ── Option A: in-memory stroke cache ─────────────────────────────────────

    /**
     * Stroke cache keyed by page UUID.  Populated by [loadStrokesFromDb] after
     * every DB load and updated by [snapshotCurrentPageToCache] before every page
     * transition so any in-memory strokes drawn since the last load are included.
     *
     * On a cache hit [loadStrokesFromDb] skips [getStrokesForLayer] + JSON
     * deserialization entirely (~900ms saved on a 610-stroke page).
     *
     * Invalidated per-entry when strokes are erased or the page is cleared/deleted.
     * Lives for the lifetime of the activity — no cross-session persistence needed.
     */
    /**
     * LRU stroke cache — bounded to [MAX_CACHE_PAGES] entries.
     *
     * [LinkedHashMap] in access-order mode moves each entry to the head on every read,
     * so [removeEldestEntry] always evicts the least-recently-touched page.  At 10 pages
     * worst-case memory is ~6 MB (610 strokes × 1 KB × 10) regardless of notebook size.
     */
    private val strokeCache = object : LinkedHashMap<String, List<LiveStroke>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LiveStroke>>): Boolean =
            size > MAX_CACHE_PAGES
    }

    // ── Persisted stroke tracking ─────────────────────────────────────────────

    /**
     * IDs of strokes confirmed to exist in the DB for the current page/layer.
     * Populated by [loadStrokesFromDb] after each page load and extended by
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
                snapshotCurrentPageToCache()
                withContext(Dispatchers.IO) { addPage(db) }
                drawingView.clearCanvas()
                val (strokes, templateBitmap, prebuiltBitmap) = withContext(Dispatchers.IO) {
                    val (s, t) = loadPageData(db)
                    Triple(s, t, drawingView.buildRenderBitmap(s, t))
                }
                if (prebuiltBitmap != null) {
                    drawingView.loadStrokesWithBitmap(strokes, prebuiltBitmap, templateBitmap)
                } else {
                    drawingView.setTemplate(templateBitmap)
                    drawingView.loadStrokes(strokes)
                }
                updatePageIndicator()
                saveLastOpenedPage(currentPageId)
            }
        }

        binding.btnDeletePage.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            // Confirmation dialog — flat NoteSprout style (elevation=0, shape_bordered).
            val dialog = AlertDialog.Builder(this)
                .setMessage("Delete this page?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    val deletingPageId = currentPageId
                    strokeCache.remove(deletingPageId)  // deleted page has no future cache value
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { deletePage(db) }
                        drawingView.clearCanvas()
                        val (strokes, templateBitmap, prebuiltBitmap) = withContext(Dispatchers.IO) {
                            val (s, t) = loadPageData(db)
                            Triple(s, t, drawingView.buildRenderBitmap(s, t))
                        }
                        if (prebuiltBitmap != null) {
                            drawingView.loadStrokesWithBitmap(strokes, prebuiltBitmap, templateBitmap)
                        } else {
                            drawingView.setTemplate(templateBitmap)
                            drawingView.loadStrokes(strokes)
                        }
                        updatePageIndicator()
                        saveLastOpenedPage(currentPageId)
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
                    // All strokes removed from memory and will be soft-deleted from DB:
                    // clear the persisted-ID registry and update the stroke cache.
                    persistedStrokeIds.clear()
                    strokeCache[currentPageId] = emptyList()
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
            // Refresh cache for the current page so a future cache hit returns the
            // correct post-erase list (drawingView has already removed the stroke).
            strokeCache[currentPageId] = drawingView.getStrokes()
            val db = soilDatabase
            if (db != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.notebookDao().softDeleteById(strokeId, System.currentTimeMillis())
                    }
                }
            }
        }

        // Idle save — fire after ~1.5 s of pen inactivity to persist new strokes.
        drawingView.onIdleSave = {
            val db = soilDatabase
            if (db != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { saveStrokes(db) }
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
                                snapshotCurrentPageToCache()
                                withContext(Dispatchers.IO) { addPage(db) }
                                drawingView.clearCanvas()
                                val (strokes, templateBitmap, prebuiltBitmap) = withContext(Dispatchers.IO) {
                                    val (s, t) = loadPageData(db)
                                    Triple(s, t, drawingView.buildRenderBitmap(s, t))
                                }
                                if (prebuiltBitmap != null) {
                                    drawingView.loadStrokesWithBitmap(strokes, prebuiltBitmap, templateBitmap)
                                } else {
                                    drawingView.setTemplate(templateBitmap)
                                    drawingView.loadStrokes(strokes)
                                }
                                updatePageIndicator()
                                saveLastOpenedPage(currentPageId)
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
     * Saves strokes, runs housekeeping PRAGMAs, then closes the Room database.
     *
     * Blocking — all IO runs on [Dispatchers.IO] via [runBlocking]; [SoilDatabase.close]
     * is called on the main thread after the coroutine completes so [finish] only fires
     * once the file is fully sealed. Idempotent — guarded by a null check.
     */
    private fun closeNotebook() {
        val db = soilDatabase ?: return
        soilDatabase = null   // mark as closed before any potentially-throwing work

        runBlocking {
            withContext(Dispatchers.IO) {
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
     * Launch a coroutine that loads strokes and template for the current page from the
     * database and hands them to the drawing view. Also refreshes [pages], [currentPageId],
     * [currentLayerId], and the page indicator.
     *
     * On first call (notebook open): loads [notebookMetadata] and uses [NotebookMetadata.lastOpenedPage]
     * to restore the user's previous position before loading strokes.
     */
    private fun loadStrokes() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val openStart = System.currentTimeMillis()
            // IO: metadata restore + DB load + pre-build bitmap (Option A cache miss on first open).
            val (strokes, templateBitmap, prebuiltBitmap) = withContext(Dispatchers.IO) {
                // Restore last-opened page — runs before loadStrokesFromDb so
                // currentPageIndex is correct when strokes are fetched.
                val metaStart = System.currentTimeMillis()
                notebookMetadata = loadNotebookMetadataFromDb(db)
                Log.d("NoteSprout_Perf", "[PERF] loadNotebookMetadata: ${System.currentTimeMillis() - metaStart}ms")

                val lastPage = notebookMetadata?.lastOpenedPage
                if (lastPage != null) {
                    val pagesStart = System.currentTimeMillis()
                    val allPages = db.notebookDao().getPagesSorted()
                    Log.d("NoteSprout_Perf", "[PERF] getPagesSorted (last-page restore): ${System.currentTimeMillis() - pagesStart}ms (page_count=${allPages.size})")
                    val idx = allPages.indexOfFirst { it.id == lastPage }
                    if (idx >= 0) currentPageIndex = idx
                    // If idx == -1 (page deleted), keep currentPageIndex = 0 (fallback).
                }

                val loadStart = System.currentTimeMillis()
                val (s, t) = loadPageData(db)
                Log.d("NoteSprout_Perf", "[PERF] loadPageData (open): ${System.currentTimeMillis() - loadStart}ms")

                // Option B: pre-build render bitmap on IO thread so main thread just swaps.
                val bitmapStart = System.currentTimeMillis()
                val bmp = drawingView.buildRenderBitmap(s, t)
                Log.d("NoteSprout_Perf", "[PERF] buildRenderBitmap (open, IO): ${System.currentTimeMillis() - bitmapStart}ms (stroke_count=${s.size})")
                Triple(s, t, bmp)
            }

            // Main thread: fast bitmap swap instead of O(N) redraw.
            val swapStart = System.currentTimeMillis()
            if (prebuiltBitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, prebuiltBitmap, templateBitmap)
                Log.d("NoteSprout_Perf", "[PERF] loadStrokesWithBitmap (open, main thread): ${System.currentTimeMillis() - swapStart}ms (stroke_count=${strokes.size})")
            } else {
                drawingView.setTemplate(templateBitmap)
                Log.d("NoteSprout_Perf", "[PERF] setTemplate (open, main thread): ${System.currentTimeMillis() - swapStart}ms")
                val loadStrokesStart = System.currentTimeMillis()
                drawingView.loadStrokes(strokes)
                Log.d("NoteSprout_Perf", "[PERF] drawingView.loadStrokes (open, main thread): ${System.currentTimeMillis() - loadStrokesStart}ms (stroke_count=${strokes.size})")
            }

            Log.d("NoteSprout_Perf", "[PERF] loadStrokes TOTAL (open): ${System.currentTimeMillis() - openStart}ms (stroke_count=${strokes.size})")
            updatePageIndicator()
        }
    }

    /**
     * Load strokes and the template bitmap for the current page.
     * Calls [loadStrokesFromDb] (which updates [currentPageId] etc.) then
     * [loadPageTemplateFromDb]. Returns both results as a [Pair].
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadPageData(db: SoilDatabase): Pair<List<LiveStroke>, Bitmap?> {
        val strokes = loadStrokesFromDb(db)
        val templateBitmap = loadPageTemplateFromDb(db)
        return Pair(strokes, templateBitmap)
    }

    /**
     * Query the database for the page at [currentPageIndex] and return its strokes
     * as a list of [LiveStroke] ready for the drawing view.
     *
     * Also updates [pages], [currentPageId], and [currentLayerId] as side effects.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadStrokesFromDb(db: SoilDatabase): List<LiveStroke> {
        val fnStart = System.currentTimeMillis()
        val dao = db.notebookDao()

        // These two lightweight queries (~2–20ms total) are always needed to set
        // currentPageId and currentLayerId, even on a cache hit.
        val q1Start = System.currentTimeMillis()
        pages = dao.getPagesSorted().toMutableList()
        Log.d("NoteSprout_Perf", "[PERF] getPagesSorted (loadStrokesFromDb): ${System.currentTimeMillis() - q1Start}ms (page_count=${pages.size})")

        if (pages.isEmpty()) {
            Log.w(TAG, "loadStrokesFromDb: no pages found in notebook")
            return emptyList()
        }

        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        Log.d(TAG, "loadStrokesFromDb: page $currentPageId (${currentPageIndex + 1}/${pages.size})")

        val q2Start = System.currentTimeMillis()
        val layer = dao.getLayerForPage(currentPageId)
        Log.d("NoteSprout_Perf", "[PERF] getLayerForPage: ${System.currentTimeMillis() - q2Start}ms")

        if (layer == null) {
            Log.w(TAG, "loadStrokesFromDb: no layer found under page $currentPageId")
            return emptyList()
        }
        currentLayerId = layer.id
        Log.d(TAG, "loadStrokesFromDb: layerId=$currentLayerId")

        // Option A — cache hit: skip getStrokesForLayer + JSON deserialization entirely.
        val cached = strokeCache[currentPageId]
        if (cached != null) {
            persistedStrokeIds.clear()
            persistedStrokeIds.addAll(cached.map { it.id })
            Log.d("NoteSprout_Perf", "[PERF] loadStrokesFromDb TOTAL (cache hit): ${System.currentTimeMillis() - fnStart}ms (stroke_count=${cached.size})")
            return cached
        }

        // Cache miss — full DB load.
        val q3Start = System.currentTimeMillis()
        val strokeObjects = dao.getStrokesForLayer(currentLayerId)
        Log.d("NoteSprout_Perf", "[PERF] getStrokesForLayer: ${System.currentTimeMillis() - q3Start}ms (stroke_count=${strokeObjects.size})")
        Log.d(TAG, "loadStrokesFromDb: found ${strokeObjects.size} stroke rows")

        val jsonStart = System.currentTimeMillis()
        val result = strokeObjects.mapNotNull { obj ->
            try {
                LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs())
            } catch (e: Exception) {
                Log.e(TAG, "loadStrokesFromDb: failed to deserialize stroke ${obj.id}", e)
                null
            }
        }
        Log.d("NoteSprout_Perf", "[PERF] JSON deserialize all strokes: ${System.currentTimeMillis() - jsonStart}ms (stroke_count=${result.size})")
        Log.d("NoteSprout_Perf", "[PERF] loadStrokesFromDb TOTAL: ${System.currentTimeMillis() - fnStart}ms (stroke_count=${result.size})")

        // Populate persisted-ID registry and stroke cache for this page.
        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(result.map { it.id })
        strokeCache[currentPageId] = result

        return result
    }

    /**
     * Read the current page's `template` property, look up the template row, and decode
     * its base64 image to a Bitmap.  Returns null if the page has no template (blank).
     *
     * Must be called on [Dispatchers.IO]. Uses [currentPageId] set by [loadStrokesFromDb].
     */
    private suspend fun loadPageTemplateFromDb(db: SoilDatabase): Bitmap? {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return null

        val q1Start = System.currentTimeMillis()
        val page = db.notebookDao().getObjectById(pageId) ?: return null
        Log.d("NoteSprout_Perf", "[PERF] getObjectById (page for template): ${System.currentTimeMillis() - q1Start}ms")

        val templateId = TemplateDialog.parseTemplateId(page.data).takeIf { it.isNotEmpty() } ?: run {
            Log.d("NoteSprout_Perf", "[PERF] loadPageTemplateFromDb: no template on this page")
            return null
        }

        val q2Start = System.currentTimeMillis()
        val templateObj = db.notebookDao().getTemplateById(templateId) ?: return null
        Log.d("NoteSprout_Perf", "[PERF] getTemplateById: ${System.currentTimeMillis() - q2Start}ms")

        return try {
            val decodeStart = System.currentTimeMillis()
            val dataObj = JSONObject(templateObj.data)
            val b64 = dataObj.getString("image")
            val bytes = Base64.decode(b64, android.util.Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Log.d("NoteSprout_Perf", "[PERF] base64+BitmapFactory decode (template): ${System.currentTimeMillis() - decodeStart}ms (bytes=${bytes.size})")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "loadPageTemplateFromDb: failed to decode template bitmap", e)
            null
        }
    }

    /**
     * Capture the current in-memory stroke list into [strokeCache] for the current page.
     * Must be called on the main thread before any page transition so that strokes drawn
     * since the last DB load are included in future cache hits for this page.
     *
     * Safe to call redundantly — a no-op if [currentPageId] is empty.
     */
    private fun snapshotCurrentPageToCache() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        strokeCache[pageId] = drawingView.getStrokes()
    }

    /**
     * Incrementally persist in-memory strokes to the database using INSERT OR IGNORE.
     *
     * Strokes already in the DB (same UUID) are silently skipped — no full delete +
     * re-insert cycle.  Erased strokes are removed from DB via the [DrawingView.onStrokeErased]
     * callback; this function only adds new ones.
     *
     * Called from: idle timer, page navigation (before switch), [closeNotebook].
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun saveStrokes(db: SoilDatabase) {
        val saveStart = System.currentTimeMillis()
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
        Log.d("NoteSprout_Perf", "[PERF] saveStrokes new_count=${newStrokes.size} total_count=${currentStrokes.size}")

        if (newStrokes.isEmpty()) {
            // Nothing to write — return immediately without touching the DB at all.
            Log.d("NoteSprout_Perf", "[PERF] saveStrokes TOTAL: ${System.currentTimeMillis() - saveStart}ms (stroke_count=${currentStrokes.size}, new_count=0 — skipped)")
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

        Log.d("NoteSprout_Perf", "[PERF] saveStrokes TOTAL: ${System.currentTimeMillis() - saveStart}ms (stroke_count=${currentStrokes.size}, new_count=${newStrokes.size})")
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
     * Save the current page's strokes, then switch to [newIndex] and load its strokes
     * and template. Persists the new current page as [NotebookMetadata.lastOpenedPage]
     * after every turn. Called from the swipe gesture detector.
     */
    private fun navigateToPage(newIndex: Int) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val navStart = System.currentTimeMillis()

            // Option A: snapshot in-memory strokes (including any drawn since last DB load)
            // to the cache before the page switch, so returning here hits the cache.
            snapshotCurrentPageToCache()

            val saveStart = System.currentTimeMillis()
            withContext(Dispatchers.IO) { saveStrokes(db) }
            Log.d("NoteSprout_Perf", "[PERF] saveStrokes (pre-switch): ${System.currentTimeMillis() - saveStart}ms")

            currentPageIndex = newIndex
            drawingView.clearCanvas()

            // IO: load strokes (cache-aware) + pre-build render bitmap (Option B).
            val loadStart = System.currentTimeMillis()
            val (strokes, templateBitmap, prebuiltBitmap) = withContext(Dispatchers.IO) {
                val (s, t) = loadPageData(db)
                val bitmapStart = System.currentTimeMillis()
                val bmp = drawingView.buildRenderBitmap(s, t)
                Log.d("NoteSprout_Perf", "[PERF] buildRenderBitmap (navigate, IO): ${System.currentTimeMillis() - bitmapStart}ms (stroke_count=${s.size})")
                Triple(s, t, bmp)
            }
            Log.d("NoteSprout_Perf", "[PERF] loadPageData+buildBitmap (navigate): ${System.currentTimeMillis() - loadStart}ms (stroke_count=${strokes.size})")

            // Main thread: fast bitmap swap instead of O(N) redraw.
            val swapStart = System.currentTimeMillis()
            if (prebuiltBitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, prebuiltBitmap, templateBitmap)
                Log.d("NoteSprout_Perf", "[PERF] loadStrokesWithBitmap (navigate, main thread): ${System.currentTimeMillis() - swapStart}ms (stroke_count=${strokes.size})")
            } else {
                drawingView.setTemplate(templateBitmap)
                Log.d("NoteSprout_Perf", "[PERF] setTemplate (navigate, main thread): ${System.currentTimeMillis() - swapStart}ms")
                val loadStrokesStart = System.currentTimeMillis()
                drawingView.loadStrokes(strokes)
                Log.d("NoteSprout_Perf", "[PERF] drawingView.loadStrokes (navigate, main thread): ${System.currentTimeMillis() - loadStrokesStart}ms (stroke_count=${strokes.size})")
            }

            Log.d("NoteSprout_Perf", "[PERF] navigateToPage TOTAL: ${System.currentTimeMillis() - navStart}ms (stroke_count=${strokes.size})")
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
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
