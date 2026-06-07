package com.notesprout.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
// GestureDetectorCompat is deprecated; GestureDetector works directly on minSdk 29+.
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.loadNotebookCoverBitmap
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.notesprout.android.databinding.ActivityMainBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Nil UUID used as the parentId for root-level objects (notebook pages).
         * Defined as a constant to avoid magic strings in notebook creation.
         */
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
    }

    // ── Grid specification ────────────────────────────────────────────────────

    /**
     * Computed once after the gridContainer is measured.  Holds all pixel
     * dimensions needed to lay out cards for the current display.
     */
    private data class GridSpec(
        val cols: Int,
        val rows: Int,
        val cardWidthPx: Int,
        val cardHeightPx: Int,
        val gutterPx: Int,        // horizontal gap between columns AND vertical gap between rows
        val rowGapPx: Int,        // vertical gap between a card and its label
        val labelHeightPx: Int,
        val paddingHPx: Int,
        val paddingVPx: Int,
    ) {
        val itemsPerPage: Int get() = cols * rows
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding
    private var notebooks: List<File> = emptyList()
    private var currentPage = 0
    private var gridSpec: GridSpec? = null

    /** True when onResume fired before gridSpec was ready; renderPage deferred. */
    private var pendingScan = false

    // ── Color cache ───────────────────────────────────────────────────────────

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    // ── Cover load jobs (cancelled on each re-render) ─────────────────────────

    private val coverLoadJobs = mutableListOf<Job>()

    // ── Gesture detector (swipe left/right to change page) ───────────────────

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Honour horizontal flings only when they dominate the vertical component.
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                return if (velocityX < 0) {
                    navigatePage(currentPage + 1)
                    true
                } else {
                    navigatePage(currentPage - 1)
                    true
                }
            }
        })
    }

    // ── Cover image picker launcher ───────────────────────────────────────────

    /** Set by openCoverDialog(); called when the image picker returns a URI. */
    private var onCoverImagePicked: ((Uri) -> Unit)? = null

    private val coverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onCoverImagePicked?.invoke(uri)
    }

    // ── Storage permission launchers ──────────────────────────────────────────

    /** API 29: request WRITE_EXTERNAL_STORAGE at runtime. */
    private val writeStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showNewNotebookDialog()
        else Toast.makeText(this, "Storage permission is required to create notebooks", Toast.LENGTH_LONG).show()
    }

    /** API 30+: send user to the All Files Access settings screen. */
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            showNewNotebookDialog()
        } else {
            Toast.makeText(this, "Storage access is required to create notebooks", Toast.LENGTH_LONG).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — same pattern as DrawingActivity.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomBar()
        setupGridGestures()

        // Compute the grid specification once the gridContainer has been laid out
        // (width/height are 0 until the first layout pass).
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    if (pendingScan) {
                        pendingScan = false
                        scanAndRender()
                    } else {
                        renderPage()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (gridSpec != null) {
            scanAndRender()
        } else {
            // Layout not measured yet — defer until the global layout listener fires.
            pendingScan = true
        }
    }

    // ── Bottom bar wiring ─────────────────────────────────────────────────────

    private fun setupBottomBar() {
        binding.btnFirstPage.setOnClickListener { navigatePage(0) }
        binding.btnPrevPage.setOnClickListener  { navigatePage(currentPage - 1) }
        binding.btnNextPage.setOnClickListener  { navigatePage(currentPage + 1) }
        binding.btnLastPage.setOnClickListener  { navigatePage(totalPages() - 1) }

        binding.btnNewNotebook.setOnClickListener {
            if (hasStoragePermission()) showNewNotebookDialog()
            else requestStoragePermission()
        }
    }

    // ── Grid gesture wiring ───────────────────────────────────────────────────

    private fun setupGridGestures() {
        binding.gridContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ── Grid specification computation ────────────────────────────────────────

    /**
     * Derives an adaptive [GridSpec] from the available pixel area.
     *
     * Column count is chosen by screen-width breakpoints (in dp):
     *   < 480dp  → 3 columns  (phone / small device)
     *   480–719dp → 4 columns  (mid-size tablet)
     *   ≥ 720dp  → 5 columns  (large tablet, e.g. BOOX NoteAir)
     *
     * Card aspect ratio mirrors the physical screen (portrait: height ÷ width).
     * Row count is derived from how many full rows fit the available height.
     */
    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density    = resources.displayMetrics.density
        val gutterPx   = (12 * density).toInt()   // gap between cards, horizontal and vertical
        val paddingHPx = (16 * density).toInt()   // left/right padding inside the grid area
        val paddingVPx = (16 * density).toInt()   // top/bottom padding inside the grid area
        val rowGapPx   = (6  * density).toInt()   // gap between card bottom edge and label
        val labelHeightPx = (32 * density).toInt() // one line of body text + breathing room

        // Column count: 3 on tablets/large e-ink devices, 2 on phone-form-factor devices.
        val screenWidthDp = availableWidth / density
        val cols = if (screenWidthDp >= 480f) 3 else 2

        // Portrait ratio: how tall a card should be relative to its width.
        val dm = resources.displayMetrics
        val aspectRatio = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)

        val innerWidth  = availableWidth  - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx

        val cardWidth  = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx

        // Number of complete rows that fit vertically (accounting for gutters between rows).
        val rows = ((innerHeight + gutterPx) / (cellHeight + gutterPx)).coerceAtLeast(1)

        return GridSpec(
            cols          = cols,
            rows          = rows,
            cardWidthPx   = cardWidth,
            cardHeightPx  = cardHeight,
            gutterPx      = gutterPx,
            rowGapPx      = rowGapPx,
            labelHeightPx = labelHeightPx,
            paddingHPx    = paddingHPx,
            paddingVPx    = paddingVPx,
        )
    }

    // ── Notebook scanning ─────────────────────────────────────────────────────

    /** Rescans the NoteSprout directory then renders the current page. */
    private fun scanAndRender() {
        val docsDir   = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val notesDir  = File(docsDir, "NoteSprout")
        notebooks = if (notesDir.exists()) {
            notesDir.listFiles { f -> f.isFile && f.extension == "soil" }
                ?.sortedBy { it.nameWithoutExtension.lowercase() }
                ?: emptyList()
        } else {
            emptyList()
        }

        // Clamp currentPage to valid range after a rescan.
        val total = totalPages()
        currentPage = currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))

        renderPage()
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun totalPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || notebooks.isEmpty()) return 1
        return (notebooks.size + perPage - 1) / perPage
    }

    /** Clears the grid container and populates it with the current page's cards. */
    private fun renderPage() {
        coverLoadJobs.forEach { it.cancel() }
        coverLoadJobs.clear()

        val spec = gridSpec ?: return
        binding.gridContainer.removeAllViews()

        if (notebooks.isEmpty()) {
            renderEmptyState()
        } else {
            renderGrid(spec)
        }

        updatePaginationControls()
    }

    private fun renderEmptyState() {
        val tv = AppCompatTextView(this).apply {
            text = "No notebooks yet. Tap + to create one."
            setTextColor(inkLightColor)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        binding.gridContainer.addView(tv, lp)
    }

    private fun renderGrid(spec: GridSpec) {
        val start     = currentPage * spec.itemsPerPage
        val end       = minOf(start + spec.itemsPerPage, notebooks.size)
        val pageItems = notebooks.subList(start, end)

        // Outer container: vertically stacked rows, centred inside gridContainer.
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        val rowCount = (pageItems.size + spec.cols - 1) / spec.cols
        for (rowIdx in 0 until rowCount) {
            if (rowIdx > 0) {
                // Vertical gutter between rows.
                gridLayout.addView(Space(this), LinearLayout.LayoutParams(0, spec.gutterPx))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
            }

            for (colIdx in 0 until spec.cols) {
                if (colIdx > 0) {
                    // Horizontal gutter between columns.
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.gutterPx, 0))
                }

                val itemIdx = rowIdx * spec.cols + colIdx
                if (itemIdx < pageItems.size) {
                    row.addView(buildCardGroup(pageItems[itemIdx], spec))
                } else {
                    // Empty placeholder keeps grid columns aligned on the last row.
                    val placeholder = Space(this)
                    val totalCellHeight = spec.cardHeightPx + spec.rowGapPx + spec.labelHeightPx
                    row.addView(placeholder, LinearLayout.LayoutParams(spec.cardWidthPx, totalCellHeight))
                }
            }

            gridLayout.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val containerLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = spec.paddingVPx  // matches the 16dp side gutters baked into card widths
        }
        binding.gridContainer.addView(gridLayout, containerLp)
    }

    /**
     * Builds a single card group:
     * ```
     * [card FrameLayout — shape_bordered, 1dp padding]
     *   [coverImage — MATCH_PARENT, centerCrop; shown when a cover bitmap loads]
     *   [icon — ic_notebook fallback, centered; shown when no cover is available]
     * [rowGap]
     * [label TextView — below the card, not inside it]
     * ```
     * Tapping anywhere on the group opens the notebook in DrawingActivity.
     * A cover-load coroutine is launched immediately; its job is tracked in
     * [coverLoadJobs] so it can be cancelled when the grid is re-rendered.
     */
    private fun buildCardGroup(file: File, spec: GridSpec): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setOnClickListener { openNotebook(file) }
            setOnLongClickListener { showNotebookContextMenu(file, this); true }
        }

        // ── Card ─────────────────────────────────────────────────────────────
        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        // 1dp padding insets children inside the border so they don't render over the rounded corners.
        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        card.setPadding(pad1dp, pad1dp, pad1dp, pad1dp)

        // Cover image — fills the card, crops to fit; hidden until a bitmap loads.
        val coverImage = AppCompatImageView(this).apply {
            scaleType  = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        card.addView(coverImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // Fallback icon — centered, visible until a cover image is available.
        val iconSize = (minOf(spec.cardWidthPx, spec.cardHeightPx) * 0.45f).toInt()
        val icon = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_notebook)
        }
        card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        // ── Label (below the card) ────────────────────────────────────────────
        val label = AppCompatTextView(this).apply {
            text      = file.nameWithoutExtension
            maxLines  = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
            textSize  = 14f
            setTextColor(inkBlackColor)
        }
        group.addView(label, LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
            it.topMargin = spec.rowGapPx
        })

        // ── Cover load coroutine ──────────────────────────────────────────────
        val job = lifecycleScope.launch {
            val bitmap = loadNotebookCoverBitmap(file)
            if (bitmap != null) {
                coverImage.setImageBitmap(bitmap)
                coverImage.visibility = View.VISIBLE
                icon.visibility       = View.GONE
            }
            // No-cover branch: icon is already visible — nothing to update.
        }
        coverLoadJobs.add(job)

        return group
    }

    // ── Notebook opening ──────────────────────────────────────────────────────

    private fun openNotebook(file: File) {
        val intent = Intent(this, DrawingActivity::class.java).apply {
            putExtra(DrawingActivity.EXTRA_NOTEBOOK_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigatePage(page: Int) {
        val clamped = page.coerceIn(0, (totalPages() - 1).coerceAtLeast(0))
        if (clamped == currentPage) return
        currentPage = clamped
        renderPage()
    }

    private fun updatePaginationControls() {
        val total   = totalPages()
        val display = currentPage + 1   // 1-indexed for the user

        binding.tvPage.text = "$display/$total"

        val atFirst = currentPage == 0
        val atLast  = currentPage >= total - 1

        binding.btnFirstPage.isEnabled = !atFirst
        binding.btnPrevPage.isEnabled  = !atFirst
        binding.btnNextPage.isEnabled  = !atLast
        binding.btnLastPage.isEnabled  = !atLast
    }

    // ── New notebook dialog ───────────────────────────────────────────────────

    private fun showNewNotebookDialog() {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        val defaultName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        dialogBinding.editNotebookName.setText(defaultName)
        val dialog = AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "Notebook name cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    createNotebook(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Keyboard must be requested before show().
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Flat styling: no shadow, same shape_bordered drawable used everywhere.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        // Focus + open keyboard.  postDelayed(100) gives the dialog window time to
        // become the active input connection (required for WindowInsetsController).
        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    // Fallback for API 29
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    private fun createNotebook(name: String) {
        try {
            val docsDir   = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val notesDir  = File(docsDir, "NoteSprout")
            if (!notesDir.exists()) {
                check(notesDir.mkdirs()) { "Failed to create NoteSprout directory at ${notesDir.absolutePath}" }
            }

            val soilFile = File(notesDir, "$name.soil")

            val db = SQLiteDatabase.openOrCreateDatabase(soilFile, null)
            try {
                // PRAGMAs that return result sets require rawQuery + moveToFirst().
                db.rawQuery("PRAGMA journal_mode = WAL",        null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_autocheckpoint = 100",  null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notebook (
                        id          TEXT    NOT NULL PRIMARY KEY,
                        parentId    TEXT    NOT NULL,
                        boundingBox TEXT    NOT NULL,
                        "order"     INTEGER NOT NULL DEFAULT 0,
                        createdAt   INTEGER NOT NULL,
                        updatedAt   INTEGER NOT NULL,
                        deletedAt   INTEGER,
                        type        TEXT    NOT NULL,
                        data        TEXT    NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
                        ON notebook(parentId, "order", deletedAt)
                    """.trimIndent()
                )

                // ── Bootstrap notebook metadata row + first page + layer ──────
                // Get physical screen dimensions for the page bounding box.
                val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds
                } else {
                    val dm = resources.displayMetrics
                    android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val screenW = screenBounds.width().toFloat()
                val screenH = screenBounds.height().toFloat()
                val bboxJson = """{"x":0.0,"y":0.0,"width":$screenW,"height":$screenH}"""
                val now = System.currentTimeMillis()

                // `order` is a reserved word in SQLite — must be double-quoted in SQL.
                // db.insert() with ContentValues builds the column list unquoted, so SQLite
                // silently rejects the INSERT (returns -1). Use execSQL with explicit quoting.
                val insertSql =
                    """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data)
                       VALUES (?, ?, ?, 0, ?, ?, NULL, ?, ?)"""

                // Pre-generate the first page UUID so the notebook metadata row can
                // record it as last_opened_page immediately at creation time.
                val notebookId = UUID.randomUUID().toString()
                val pageId     = UUID.randomUUID().toString()

                // 1. Notebook metadata row — must be inserted first; its UUID is the
                //    parentId for all page rows in this notebook.
                val notebookDataJson = NotebookMetadata(
                    id             = notebookId,
                    title          = name,
                    cover          = "",
                    lastOpenedPage = pageId,
                ).toJson()
                db.execSQL(insertSql, arrayOf(
                    notebookId, "", "{}", now, now, "notebook", notebookDataJson
                ))

                // 2. First page — parentId is the notebook metadata row's UUID.
                //    template:"" means no template (blank background).
                db.execSQL(insertSql, arrayOf(
                    pageId, notebookId, bboxJson, now, now, "page",
                    """{"width":$screenW,"height":$screenH,"template":""}"""
                ))

                // 3. Content layer for the first page.
                val layerId = UUID.randomUUID().toString()
                db.execSQL(insertSql, arrayOf(
                    layerId, pageId, bboxJson, now, now, "layer",
                    """{"label":"Content","isLocked":false,"isVisible":true}"""
                ))

                // Clean close: reclaim space and truncate WAL to zero bytes.
                db.rawQuery("PRAGMA incremental_vacuum",        null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",  null).use { it.moveToFirst() }
            } finally {
                db.close()
            }

            // Delete any 0-byte rollback journal left from database initialisation.
            File("${soilFile.absolutePath}-journal").takeIf { it.exists() }?.delete()

            Toast.makeText(this, "Notebook '$name' created", Toast.LENGTH_SHORT).show()

            // Rescan so the list is current when the user presses back.
            scanAndRender()
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                val idx = notebooks.indexOfFirst { it.nameWithoutExtension == name }
                if (idx >= 0) navigatePage(idx / spec.itemsPerPage)
            }

            // Open the new notebook immediately — no need to tap it in the list.
            val intent = Intent(this, DrawingActivity::class.java).apply {
                putExtra(DrawingActivity.EXTRA_NOTEBOOK_PATH, soilFile.absolutePath)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Notebook context menu ─────────────────────────────────────────────────

    /**
     * Shows a PopupMenu anchored to [anchor] with two groups:
     *   Group 1 (notebook actions): Set Cover
     *   Group 2 (destructive): Delete Notebook
     */
    private fun showNotebookContextMenu(file: File, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_notebook_context, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_set_cover -> {
                    openCoverDialog(file)
                    true
                }
                R.id.action_delete_notebook -> {
                    showDeleteNotebookConfirmation(file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Loads the last-opened page snapshot for [file], then opens [CoverDialog].
     * Cover changes trigger a per-card cover reload via the callback.
     */
    private fun openCoverDialog(file: File) {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { loadLastPageSnapshot(file) }
            val dialog = CoverDialog(
                activity           = this@MainActivity,
                lifecycleScope     = lifecycleScope,
                soilFilePath       = file.absolutePath,
                lastOpenedSnapshot = snapshot,
                onRequestImagePick = { callback ->
                    onCoverImagePicked = callback
                    coverPickerLauncher.launch("image/*")
                },
                onCoverChanged = {
                    // Reload the cover bitmap for this specific notebook card.
                    reloadCoverForNotebook(file)
                },
            )
            dialog.show()
        }
    }

    /** Loads the last-opened page snapshot from [file] read-only. Returns null on any error. */
    private fun loadLastPageSnapshot(file: File): Bitmap? {
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            db.use {
                val (notebookId, metaJson) = it.rawQuery(
                    "SELECT id, data FROM notebook WHERE type = 'notebook' LIMIT 1", null
                ).use { c ->
                    if (!c.moveToFirst()) return null
                    Pair(c.getString(0), c.getString(1))
                }
                val metadata = NotebookMetadata.fromJson(notebookId, metaJson)
                val pageId = metadata.lastOpenedPage ?: return null
                if (pageId.isEmpty()) return null

                val pageJson = it.rawQuery(
                    "SELECT data FROM notebook WHERE id = ? AND deletedAt IS NULL LIMIT 1",
                    arrayOf(pageId)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

                // Extract the snapshot field from the page data JSON.
                val snapshotB64 = try {
                    val element = kotlinx.serialization.json.Json.parseToJsonElement(pageJson)
                    element.jsonObject["snapshot"]?.jsonPrimitive?.content ?: ""
                } catch (_: Exception) { "" }
                if (snapshotB64.isEmpty()) return null

                val bytes = Base64.decode(snapshotB64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cancels any in-flight cover load job for [file] and starts a fresh one that
     * reloads the bitmap and rebinds it to the matching card view in the current grid.
     *
     * This must run on the main thread.  The cover load is launched on IO via
     * [loadNotebookCoverBitmap] — the card view reference is safe because we only
     * update it from the main thread after the coroutine completes.
     */
    private fun reloadCoverForNotebook(file: File) {
        // Re-render the current grid page so the new cover is reflected.
        // This is the simplest correct approach — it cancels all in-flight jobs and
        // rebuilds all cards, picking up the updated cover from the .soil file.
        scanAndRender()
    }

    /**
     * Shows a confirmation AlertDialog before deleting [file].
     * The notebook name is included so the user knows exactly what they're deleting.
     */
    private fun showDeleteNotebookConfirmation(file: File) {
        val name   = file.nameWithoutExtension
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete notebook \"$name\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteNotebook(file) }
            .create()
        dialog.show()
        // Style after show() — window only exists once the dialog is displayed.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * Deletes a notebook's .soil file and any sibling SQLite artefacts
     * (e.g. name.soil-wal, name.soil-shm, name.soil-journal) on the IO dispatcher,
     * then refreshes the grid on the main thread.
     *
     * Guard note: DrawingActivity manages its own Room instance; there is no shared
     * database handle in MainActivity.  If a user somehow reaches this path while the
     * notebook is open in DrawingActivity (which the normal back-stack makes impossible),
     * deleting the file while Room holds it open is safe on Android — the process-level
     * file handle keeps data intact until DrawingActivity closes its instance.
     */
    private fun deleteNotebook(file: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Delete the .soil file itself plus any sibling artefacts whose names
                // start with the .soil filename (catches -wal, -shm, -journal suffixes).
                file.parentFile
                    ?.listFiles { f -> f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
            }
            // Re-scan on the main thread so the grid reflects the deletion.
            scanAndRender()
        }
    }

    // ── Storage permissions ───────────────────────────────────────────────────

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            writeStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
