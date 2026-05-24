package com.notesprout.android

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.NotebookDao
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
import java.io.File
import java.util.Locale
import java.util.UUID

class DrawingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NoteSprout"

        /** Intent extra key — the absolute path to the `.soil` notebook file. */
        const val EXTRA_NOTEBOOK_PATH = "notebook_path"
    }

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var drawingView: DrawingView
    private lateinit var pageGestureDetector: GestureDetector
    private var isEraserActive = false

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null

    // ── Page state ────────────────────────────────────────────────────────────

    /** All live pages in sorted order. Populated (and refreshed) by [loadStrokesFromDb]. */
    private var pages: MutableList<NotebookObject> = mutableListOf()

    /** Index into [pages] for the currently displayed page. */
    private var currentPageIndex: Int = 0

    /** ID of the currently displayed page row. Set by [loadStrokesFromDb]. */
    private var currentPageId: String = ""

    /** ID of the content layer under [currentPageId]. Set by [loadStrokesFromDb]. */
    private var currentLayerId: String = ""

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
                withContext(Dispatchers.IO) { addPage(db) }
                drawingView.clearCanvas()
                val strokes = withContext(Dispatchers.IO) { loadStrokesFromDb(db) }
                drawingView.loadStrokes(strokes)
                updatePageIndicator()
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
                        withContext(Dispatchers.IO) { deletePage(db) }
                        drawingView.clearCanvas()
                        val strokes = withContext(Dispatchers.IO) { loadStrokesFromDb(db) }
                        drawingView.loadStrokes(strokes)
                        updatePageIndicator()
                    }
                }
                .create()
            dialog.show()
            // Style after show() — window only exists once the dialog is displayed.
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        }

        binding.btnClear.setOnClickListener {
            // Capture the stroke IDs before clearing so we can soft-delete their DB rows.
            val db = soilDatabase
            val strokesToDelete = drawingView.getStrokes()
            drawingView.clearCanvas()
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

        binding.btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            binding.btnEraser.text = if (isEraserActive) "Pen" else "Erase"
            drawingView.setEraserMode(isEraserActive)
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
                        if (next <= pages.lastIndex) { navigateToPage(next); true } else false
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
     * Launch a coroutine that loads strokes for the current page from the database
     * and hands them to the drawing view.  Also refreshes [pages], [currentPageId],
     * [currentLayerId], and the page indicator.
     *
     * Always uses [currentPageIndex] — set it before calling to navigate pages.
     */
    private fun loadStrokes() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val strokes = withContext(Dispatchers.IO) { loadStrokesFromDb(db) }
            drawingView.loadStrokes(strokes)
            updatePageIndicator()
        }
    }

    /**
     * Query the database for the page at [currentPageIndex] and return its strokes
     * as a list of [LiveStroke] ready for the drawing view.
     *
     * Also updates [pages], [currentPageId], and [currentLayerId] as side effects.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadStrokesFromDb(db: SoilDatabase): List<LiveStroke> {
        val dao = db.notebookDao()
        pages = dao.getPagesSorted().toMutableList()

        if (pages.isEmpty()) {
            Log.w(TAG, "loadStrokesFromDb: no pages found in notebook")
            return emptyList()
        }

        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        Log.d(TAG, "loadStrokesFromDb: page $currentPageId (${currentPageIndex + 1}/${pages.size})")

        val layer = dao.getLayerForPage(currentPageId)
        if (layer == null) {
            Log.w(TAG, "loadStrokesFromDb: no layer found under page $currentPageId")
            return emptyList()
        }
        currentLayerId = layer.id
        Log.d(TAG, "loadStrokesFromDb: layerId=$currentLayerId")

        val strokeObjects = dao.getStrokesForLayer(currentLayerId)
        Log.d(TAG, "loadStrokesFromDb: found ${strokeObjects.size} stroke rows")

        return strokeObjects.mapNotNull { obj ->
            try {
                LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs())
            } catch (e: Exception) {
                Log.e(TAG, "loadStrokesFromDb: failed to deserialize stroke ${obj.id}", e)
                null
            }
        }
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
        val layerId = currentLayerId
        if (layerId.isEmpty()) {
            Log.w(TAG, "saveStrokes: currentLayerId is empty — skipping")
            return
        }
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val currentStrokes = drawingView.getStrokes()
        Log.d(TAG, "saveStrokes: inserting/ignoring ${currentStrokes.size} strokes")

        for ((index, liveStroke) in currentStrokes.withIndex()) {
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
                    sortOrder   = index,
                    createdAt   = now,
                    updatedAt   = now,
                    deletedAt   = null,
                    data        = strokeData.toJson(),
                )
            )
        }
    }

    // ── Page operations ───────────────────────────────────────────────────────

    /**
     * Add a new page immediately after the current page.
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

        // Shift the order of every page that comes after the insertion point.
        for (i in insertionIndex until pages.size) {
            dao.updateOrder(pages[i].id, i + 1)
        }

        // Insert the new page.
        val newPageId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newPageId,
                type        = "page",
                parentId    = MainActivity.NIL_UUID,
                boundingBox = bboxJson,
                sortOrder   = insertionIndex,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = """{"width":$screenW,"height":$screenH}""",
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
                    parentId    = MainActivity.NIL_UUID,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = newNow,
                    updatedAt   = newNow,
                    deletedAt   = null,
                    data        = """{"width":$screenW,"height":$screenH}""",
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
     * Save the current page's strokes, then switch to [newIndex] and load its strokes.
     * Called from the swipe gesture detector.
     */
    private fun navigateToPage(newIndex: Int) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes(db) }
            currentPageIndex = newIndex
            drawingView.clearCanvas()
            val strokes = withContext(Dispatchers.IO) { loadStrokesFromDb(db) }
            drawingView.loadStrokes(strokes)
            updatePageIndicator()
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
