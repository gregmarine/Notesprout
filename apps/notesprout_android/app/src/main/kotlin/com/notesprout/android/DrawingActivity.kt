package com.notesprout.android

import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.room.Room
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.databinding.ActivityDrawingBinding
import com.notesprout.android.drawing.DrawingView
import com.notesprout.android.drawing.GenericDrawingView
import com.notesprout.android.drawing.OnyxDrawingView
import java.io.File
import java.util.Locale

class DrawingActivity : AppCompatActivity() {

    companion object {
        /** Intent extra key — the absolute path to the `.soil` notebook file. */
        const val EXTRA_NOTEBOOK_PATH = "notebook_path"
    }

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var drawingView: DrawingView
    private var isEraserActive = false

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null

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
                // TODO Step 5: move queries to background threads and remove this.
                .allowMainThreadQueries()
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
     * Runs housekeeping PRAGMAs then closes the Room database.
     *
     * Must be called before [finish] so the `.soil` file is clean:
     * - `PRAGMA incremental_vacuum` reclaims any free pages
     * - `PRAGMA wal_checkpoint(TRUNCATE)` truncates the WAL to zero bytes
     *
     * Idempotent — safe to call multiple times (guarded by null check).
     */
    private fun closeNotebook() {
        val db = soilDatabase ?: return
        soilDatabase = null   // mark as closed before any potentially-throwing work

        saveStrokes()

        try {
            db.openHelper.writableDatabase.apply {
                query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
                query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
        } finally {
            db.close()
            // Delete the empty -journal file Android leaves during DB initialisation.
            val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
            File("$path-journal").takeIf { it.exists() }?.delete()
        }
    }

    // ── Persistence stubs (Step 5) ────────────────────────────────────────────

    private fun saveStrokes() {
        // TODO: Step 5 — persist strokes to notebook table
    }

    private fun loadStrokes() {
        // TODO: Step 5 — load strokes from notebook table
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isBooxDevice() =
        Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
}
