package com.notesprout.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
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
    private var isEraserActive = false

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null

    /**
     * IDs of the active page and layer, populated by [loadStrokes] after DB open.
     * Required by [saveStrokes] to parent new stroke rows correctly.
     */
    private var pageId: String? = null
    private var layerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — equivalent to Flutter's SystemUiMode.immersiveSticky.
        // Canvas sits at physical screen (0,0), so Onyx touch coordinates align exactly.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityDrawingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Toolbar ───────────────────────────────────────────────────────────
        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH)
        binding.tvNotebookName.text = notebookPath
            ?.let { File(it).nameWithoutExtension }
            ?: ""

        binding.btnClose.setOnClickListener {
            closeNotebook()
            finish()
        }
        binding.btnClear.setOnClickListener { drawingView.clearCanvas() }
        binding.btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            binding.btnEraser.text = if (isEraserActive) "Pen" else "Erase"
            drawingView.setEraserMode(isEraserActive)
        }

        // ── Back press — same closeNotebook() path as the Close button ────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeNotebook()
                finish()
            }
        })

        // ── Drawing view ──────────────────────────────────────────────────────
        drawingView = if (isBooxDevice()) OnyxDrawingView(this) else GenericDrawingView(this)
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

        // ── Open the Room DB ──────────────────────────────────────────────────
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

    // ── Notebook DB lifecycle ─────────────────────────────────────────────────

    /**
     * Saves strokes, runs housekeeping PRAGMAs, then closes the Room database.
     *
     * This is a blocking call — all IO runs on [Dispatchers.IO] via [runBlocking],
     * then [SoilDatabase.close] is called on the main thread after the coroutine
     * completes.  Using runBlocking here is intentional: close must be synchronous
     * so [finish] is only called after the file is fully written and sealed.
     *
     * Idempotent — safe to call multiple times (guarded by null check).
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
     * Load strokes from the database and hand them to the drawing view.
     *
     * Runs asynchronously on [Dispatchers.IO]; switches back to [Dispatchers.Main]
     * to call [DrawingView.loadStrokes] (which triggers invalidate).
     *
     * Also stores [pageId] and [layerId] for use by [saveStrokes].
     */
    private fun loadStrokes() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val strokeLists = withContext(Dispatchers.IO) {
                val dao = db.notebookDao()

                val page = dao.getFirstByType("page")
                if (page == null) {
                    Log.w(TAG, "loadStrokes: no page found in notebook")
                    return@withContext emptyList()
                }
                pageId = page.id
                Log.d(TAG, "loadStrokes: pageId=$pageId")

                val layer = dao.getObjectsByParent(page.id)
                    .filter { it.type == "layer" }
                    .firstOrNull()
                if (layer == null) {
                    Log.w(TAG, "loadStrokes: no layer found under page ${page.id}")
                    return@withContext emptyList()
                }
                layerId = layer.id
                Log.d(TAG, "loadStrokes: layerId=$layerId")

                val strokeObjects = dao.getObjectsByParent(layer.id)
                Log.d(TAG, "loadStrokes: found ${strokeObjects.size} stroke rows")

                strokeObjects.mapNotNull { obj ->
                    try {
                        StrokeData.fromJson(obj.data).toPointFs()
                    } catch (e: Exception) {
                        Log.e(TAG, "loadStrokes: failed to deserialize stroke ${obj.id}", e)
                        null
                    }
                }
            }
            // Back on Main — safe to call invalidate inside loadStrokes.
            drawingView.loadStrokes(strokeLists)
        }
    }

    /**
     * Persist the current in-memory stroke list to the database.
     *
     * Strategy: full replace — soft-delete all existing stroke rows for this layer,
     * then insert the current set fresh.  Incremental delta saves (soft-delete only
     * the erased rows by UUID) are a future step.
     *
     * Must be called on [Dispatchers.IO] — do not invoke directly from the main thread.
     */
    private suspend fun saveStrokes(db: SoilDatabase) {
        val currentLayerId = layerId ?: run {
            Log.w(TAG, "saveStrokes: layerId is null — skipping (notebook may not have loaded)")
            return
        }
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()

        // Soft-delete all existing stroke rows for this layer.
        val existingStrokes = dao.getObjectsByParent(currentLayerId)
        for (stroke in existingStrokes) {
            dao.softDelete(stroke.id, now)
        }
        Log.d(TAG, "saveStrokes: soft-deleted ${existingStrokes.size} existing rows")

        // Insert current strokes.
        val currentStrokes = drawingView.getStrokes()
        Log.d(TAG, "saveStrokes: inserting ${currentStrokes.size} strokes")

        for ((index, points) in currentStrokes.withIndex()) {
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

            dao.insertObject(
                NotebookObject(
                    id          = UUID.randomUUID().toString(),
                    type        = "stroke",
                    parentId    = currentLayerId,
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isBooxDevice() =
        Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
}
