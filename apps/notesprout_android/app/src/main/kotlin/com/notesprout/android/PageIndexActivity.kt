package com.notesprout.android

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.Slog
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TemplateObject
import com.notesprout.android.data.recents.TemplateRecentsManager
import com.notesprout.android.data.soilFile
import com.notesprout.android.data.topHeadingNamesByPageId
import com.notesprout.android.databinding.ActivityPageIndexBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.os.Bundle
import android.view.ViewTreeObserver

class PageIndexActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID           = "notebook_id"
        const val EXTRA_NOTEBOOK_NAME         = "notebook_name"
        const val EXTRA_CURRENT_PAGE_INDEX    = "current_page_index"
        const val EXTRA_SELECTED_PAGE_INDEX   = "selected_page_index"
        /** Comma-separated UUIDs of pages pasted during this session (may be empty). */
        const val EXTRA_PASTED_PAGE_IDS       = "pasted_page_ids"
        /** Comma-separated 0-based indices matching [EXTRA_PASTED_PAGE_IDS]. */
        const val EXTRA_PASTED_PAGE_INDICES   = "pasted_page_indices"
        /** Comma-separated UUIDs of pages deleted during this session (may be empty). */
        const val EXTRA_DELETED_PAGE_IDS      = "deleted_page_ids"
        /** Comma-separated 0-based indices matching [EXTRA_DELETED_PAGE_IDS]. */
        const val EXTRA_DELETED_PAGE_INDICES  = "deleted_page_indices"
        /** Comma-separated deletedAt timestamps matching [EXTRA_DELETED_PAGE_IDS]. */
        const val EXTRA_DELETED_PAGE_TIMESTAMPS = "deleted_page_timestamps"
        /** Comma-separated UUIDs of pages moved during this session, flattened across all move
         *  operations (may be empty). Split into per-operation batches using [EXTRA_MOVED_OP_SIZES]. */
        const val EXTRA_MOVED_PAGE_IDS        = "moved_page_ids"
        /** Comma-separated previous-after page IDs matching [EXTRA_MOVED_PAGE_IDS] (empty string = was first page). */
        const val EXTRA_MOVED_PREV_AFTER_IDS  = "moved_prev_after_ids"
        /** Comma-separated new-after page IDs (post-move predecessor) matching [EXTRA_MOVED_PAGE_IDS]
         *  (empty string = became first page); used by redo. */
        const val EXTRA_MOVED_NEW_AFTER_IDS   = "moved_new_after_ids"
        /** Comma-separated page-counts, one per move operation, so the flattened move lists can be
         *  split back into per-operation batches (one PagesMoved undo step each). */
        const val EXTRA_MOVED_OP_SIZES        = "moved_op_sizes"
        /** Comma-separated UUIDs of pages whose template changed during this session (may be empty). */
        const val EXTRA_TEMPLATE_PAGE_IDS     = "template_page_ids"
        /** Comma-separated previous template-row ids matching [EXTRA_TEMPLATE_PAGE_IDS]
         *  (empty string = page had no template / was blank). */
        const val EXTRA_TEMPLATE_PREV_IDS     = "template_prev_ids"
        /** Comma-separated new template-row ids matching [EXTRA_TEMPLATE_PAGE_IDS]
         *  (empty string = template cleared / set to blank). */
        const val EXTRA_TEMPLATE_NEW_IDS      = "template_new_ids"
    }

    // ── Grid specification ────────────────────────────────────────────────────

    private data class GridSpec(
        val cols: Int,
        val rows: Int,
        val cardWidthPx: Int,
        val cardHeightPx: Int,
        val gutterPx: Int,
        val rowGapPx: Int,
        val labelHeightPx: Int,
        val paddingHPx: Int,
        val paddingVPx: Int,
    ) {
        val itemsPerPage: Int get() = cols * rows
    }

    // ── Page data ─────────────────────────────────────────────────────────────

    private data class PageEntry(val id: String, val pageNumber: Int, val snapshot: String?, val headingName: String? = null)

    @Serializable
    private data class PageSnapshot(val snapshot: String? = null)

    private val pageCodec = Json { ignoreUnknownKeys = true }

    // ── State ─────────────────────────────────────────────────────────────────

    private var notebookId: String = ""
    private var notebookSoilPath: String? = null

    private lateinit var binding: ActivityPageIndexBinding
    private var pages: List<PageEntry> = emptyList()
    private var currentPageIndex: Int = 0   // 0-based index in pages list (the open page)
    private var currentGridPage: Int = 0    // 0-based pagination index
    private var gridSpec: GridSpec? = null

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }

    private val snapshotDecodeJobs = mutableListOf<Job>()

    // ── Action mode (long-press) and destination-picking mode ────────────────

    /** Pages selected in action mode, by stable UUID. Insertion order is preserved
     *  (matters for paste/move block ordering). Empty = normal mode; non-empty = action mode. */
    private val selectedPageIds = LinkedHashSet<String>()

    private fun inActionMode(): Boolean = selectedPageIds.isNotEmpty()
    private fun selectedCount(): Int = selectedPageIds.size

    /**
     * Destination-picking mode unifies move and paste: the user taps a card to pick where the
     * block is inserted. [DestMode.NONE] = normal / action mode; [DestMode.MOVE] = picking a
     * destination for a move operation; [DestMode.PASTE] = picking a destination for a paste.
     */
    private enum class DestMode { NONE, MOVE, PASTE }
    private var destMode: DestMode = DestMode.NONE

    /** IDs of pages being moved (snapshot of [selectedPageIds] when Move is entered). */
    private var moveSourceIds: List<String> = emptyList()

    /** When true (default), insert before the tapped destination card; false = insert after. */
    private var insertBefore: Boolean = true

    /** Pages copied to the clipboard. Empty = clipboard is empty. */
    private var pendingCopyPageIds: List<String> = emptyList()

    /** Stable ID of the currently-open page in NotebookActivity — used to recompute
     *  [currentPageIndex] after a move reshuffles the pages list. */
    private var currentPageId: String? = null

    /** Paste operations performed this session — returned to NotebookActivity for undo/redo. */
    private val pastedActions  = mutableListOf<Pair<String, Int>>()          // (pageId, pageIndex)
    /** Delete operations performed this session — returned to NotebookActivity for undo/redo. */
    private val deletedActions = mutableListOf<Triple<String, Int, Long>>()  // (pageId, pageIndex, deletedAt)
    /** Move operations performed this session — returned to NotebookActivity for undo/redo.
     *  One entry per move OPERATION; each is the operation's per-page triples
     *  (pageId, prevAfterId [undo], newAfterId [redo]) in original document order. */
    private val movedActions   = mutableListOf<List<Triple<String, String?, String?>>>()
    /** Template-change operations performed this session — returned to NotebookActivity for
     *  undo/redo. Each entry is (pageId, previousTemplateId, newTemplateId); ids are `.soil`
     *  template-row ids, null = blank/no template. */
    private val templateChanges = mutableListOf<Triple<String, String?, String?>>()

    /** Global-index repository (templates live in `notesprout.db`, not the `.soil`). */
    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    /** Pages whose template will be set once the picker returns (snapshot of the selection). */
    private var pendingTemplateTargets: List<String> = emptyList()

    // ── Export save-to-device launchers ─────────────────────────────────────

    private var pendingExportFile: java.io.File? = null

    /** Files to write into the folder chosen by [openDocumentTreeLauncher] (PNG batch). */
    private var pendingPngFiles: List<java.io.File> = emptyList()

    private val saveTemplateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            android.widget.Toast.makeText(this, "Saved to Templates", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val templatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val templateId = result.data?.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_ID)
            ?: return@registerForActivityResult
        applyTemplateToSelection(templateId)
    }

    private val savePngLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val file = pendingExportFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this@PageIndexActivity, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Save a PDF to a location chosen by the user (multi-page PDF export). */
    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val file = pendingExportFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this@PageIndexActivity, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Folder picker for batch PNG export. Once the user selects a folder, all [pendingPngFiles]
     * are written into it via [DocumentsContract.createDocument], one file per page.
     */
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val files = pendingPngFiles
        pendingPngFiles = emptyList()
        if (treeUri == null || files.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                writePngFilesToTree(treeUri, files)
            }
            val msg = if (written == files.size) "Exported ${files.size} images"
                      else "Exported $written of ${files.size} images"
            android.widget.Toast.makeText(this@PageIndexActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ── Swipe gesture (left/right to paginate) ────────────────────────────────

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                return if (velocityX < 0) {
                    navigateGridPage(currentGridPage + 1); true
                } else {
                    navigateGridPage(currentGridPage - 1); true
                }
            }
        })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityPageIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notebookId       = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: ""
        notebookSoilPath = if (notebookId.isNotEmpty()) soilFile(this, notebookId).absolutePath else null

        currentPageIndex = intent.getIntExtra(EXTRA_CURRENT_PAGE_INDEX, 0)

        binding.btnBack.setOnClickListener {
            when {
                destMode != DestMode.NONE -> cancelDestMode()
                inActionMode() -> exitActionMode()
                else -> finishWithResult(null)
            }
        }

        binding.btnSelectAll.setOnClickListener  { toggleSelectAll() }
        binding.btnCopyPage.setOnClickListener   { copySelectedPages() }
        binding.btnDeletePage.setOnClickListener { executeDelete() }
        binding.btnMovePage.setOnClickListener   { enterDestMode(DestMode.MOVE) }
        binding.btnSetTemplate.setOnClickListener { chooseTemplateForSelection() }
        binding.btnExportPage.setOnClickListener { executeExport() }
        binding.btnInsertBefore.setOnClickListener { insertBefore = true;  refreshInsertBeforeAfter() }
        binding.btnInsertAfter.setOnClickListener  { insertBefore = false; refreshInsertBeforeAfter() }

        // Back gesture exits destination-picking / action mode; otherwise finish.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    destMode != DestMode.NONE -> cancelDestMode()
                    inActionMode() -> exitActionMode()
                    else -> finishWithResult(null)
                }
            }
        })

        binding.btnFirstPage.setOnClickListener { navigateGridPage(0) }
        binding.btnPrevPage.setOnClickListener  { navigateGridPage(currentGridPage - 1) }
        binding.btnNextPage.setOnClickListener  { navigateGridPage(currentGridPage + 1) }
        binding.btnLastPage.setOnClickListener  { navigateGridPage(totalGridPages() - 1) }

        binding.gridContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    if (pages.isNotEmpty()) renderGridPage()
                }
            }
        )

        loadPagesAsync()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadPagesAsync() {
        val path = notebookSoilPath ?: return
        lifecycleScope.launch {
            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }
            currentPageId = pages.getOrNull(currentPageIndex)?.id
            // Jump to the grid page that contains the currently-open page.
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                currentGridPage = currentPageIndex / spec.itemsPerPage
                renderGridPage()
            }
            // If gridSpec isn't ready yet the onGlobalLayout listener will render once it is.
        }
    }

    private fun loadPagesFromSoil(path: String): List<PageEntry> {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            val headingNames = topHeadingNamesByPageId(db)
            db.rawQuery(
                "SELECT id, data FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
                null
            ).use { c ->
                val result = mutableListOf<PageEntry>()
                var number = 1
                while (c.moveToNext()) {
                    val id       = c.getString(0)
                    val dataJson = c.getString(1)
                    val snapshot = try {
                        pageCodec.decodeFromString<PageSnapshot>(dataJson).snapshot
                    } catch (_: Exception) { null }
                    result.add(PageEntry(id, number++, snapshot, headingNames[id]))
                }
                result
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            db?.close()
        }
    }

    // ── Grid specification ────────────────────────────────────────────────────

    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density       = resources.displayMetrics.density
        val gutterPx      = (12 * density).toInt()
        val paddingHPx    = (16 * density).toInt()
        val paddingVPx    = (16 * density).toInt()
        val rowGapPx      = (6  * density).toInt()
        val labelHeightPx = (32 * density).toInt()

        // Column count: 3 on tablets/large e-ink devices, 2 on phone-form-factor devices.
        val screenWidthDp = availableWidth / density
        val cols = if (screenWidthDp >= 480f) 3 else 2

        val dm          = resources.displayMetrics
        val aspectRatio = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)

        val innerWidth  = availableWidth  - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx

        val cardWidth  = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx

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

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun totalGridPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || pages.isEmpty()) return 1
        return (pages.size + perPage - 1) / perPage
    }

    private fun renderGridPage() {
        snapshotDecodeJobs.forEach { it.cancel() }
        snapshotDecodeJobs.clear()

        val spec = gridSpec ?: return
        binding.gridContainer.removeAllViews()

        if (pages.isEmpty()) {
            renderEmptyState()
            updatePaginationControls()
            return
        }

        val start     = currentGridPage * spec.itemsPerPage
        val end       = minOf(start + spec.itemsPerPage, pages.size)
        val pageItems = pages.subList(start, end)

        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        val rowCount = (pageItems.size + spec.cols - 1) / spec.cols
        for (rowIdx in 0 until rowCount) {
            if (rowIdx > 0) {
                gridLayout.addView(Space(this), LinearLayout.LayoutParams(0, spec.gutterPx))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
            }
            for (colIdx in 0 until spec.cols) {
                if (colIdx > 0) {
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.gutterPx, 0))
                }
                val itemIdx = rowIdx * spec.cols + colIdx
                if (itemIdx < pageItems.size) {
                    row.addView(buildCardGroup(pageItems[itemIdx], spec))
                } else {
                    val totalCellHeight = spec.cardHeightPx + spec.rowGapPx + spec.labelHeightPx
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.cardWidthPx, totalCellHeight))
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
        ).apply { topMargin = spec.paddingVPx }
        binding.gridContainer.addView(gridLayout, containerLp)

        updatePaginationControls()
    }

    private fun renderEmptyState() {
        val tv = AppCompatTextView(this).apply {
            text = "No pages found."
            setTextColor(inkBlackColor)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        binding.gridContainer.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )
    }

    /**
     * Builds one card group:
     * ```
     * [card FrameLayout — shape_bordered/bg_page_card_current, padding matches border width]
     *   [snapshotImage — GONE until bitmap decoded]
     * [rowGap Space]
     * [label "Page N"]
     * ```
     * Normal tap: navigate to this page.
     * Action mode tap: toggle this card's selection (exits action mode if the set empties).
     * Action mode long-press: same as tap once in action mode.
     * Destination mode tap (MOVE — a source card): cancel, return to action mode.
     * Destination mode tap (MOVE — non-source): execute move to this page.
     * Destination mode tap (PASTE — any card): paste clipboard after/before this page.
     * Long press in normal mode: enter action mode with this card selected.
     */
    private fun buildCardGroup(entry: PageEntry, spec: GridSpec): LinearLayout {
        val pageIndex  = entry.pageNumber - 1   // 0-based
        val isCurrent  = pageIndex == currentPageIndex
        val isSelected = entry.id in selectedPageIds

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL

            setOnClickListener {
                when {
                    destMode == DestMode.MOVE -> {
                        if (entry.id in moveSourceIds) {
                            // Tapped a source card — cancel back to action mode.
                            cancelDestMode()
                        } else {
                            executeMove(entry.id)
                        }
                    }
                    destMode == DestMode.PASTE -> executePaste(entry.id)
                    !inActionMode() -> finishWithResult(pageIndex)
                    else -> toggleSelection(entry.id)
                }
            }

            setOnLongClickListener {
                if (destMode != DestMode.NONE) return@setOnLongClickListener true
                if (!inActionMode()) {
                    selectedPageIds.add(entry.id)
                    refreshActionMode()
                    renderGridPage()
                } else {
                    toggleSelection(entry.id)
                }
                true
            }
        }

        // ── Card ──────────────────────────────────────────────────────────────
        // Destination mode: highlight the source pages (selection / moveSourceIds).
        // Action mode: highlight every selected card.
        // Normal mode: highlight only the currently-open page.
        val highlighted = when {
            destMode != DestMode.NONE -> isSelected || entry.id in moveSourceIds
            inActionMode() -> isSelected
            else -> isCurrent
        }
        val card = FrameLayout(this).apply {
            setBackgroundResource(
                if (highlighted) R.drawable.bg_page_card_current else R.drawable.shape_bordered
            )
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        // Padding insets children inside the border so they don't render over the rounded corners.
        // Match padding to border width: 3dp for the highlighted card, 1dp otherwise.
        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        val padPx   = if (highlighted) (3 * density + 0.5f).toInt() else pad1dp
        card.setPadding(padPx, padPx, padPx, padPx)

        // Snapshot image — filled once the bitmap is decoded off the main thread.
        val snapshotImage = AppCompatImageView(this).apply {
            scaleType  = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        card.addView(snapshotImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // ── Label ─────────────────────────────────────────────────────────────
        val label = AppCompatTextView(this).apply {
            text      = entry.headingName ?: "Page ${entry.pageNumber}"
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
            textSize  = 14f
            setTextColor(inkBlackColor)
        }
        group.addView(label, LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
            it.topMargin = spec.rowGapPx
        })

        // ── Snapshot decode coroutine ─────────────────────────────────────────
        if (!entry.snapshot.isNullOrEmpty()) {
            val job = lifecycleScope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(entry.snapshot, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    snapshotImage.setImageBitmap(bitmap)
                    snapshotImage.visibility = android.view.View.VISIBLE
                }
            }
            snapshotDecodeJobs.add(job)
        }

        return group
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigateGridPage(page: Int) {
        val clamped = page.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
        if (clamped == currentGridPage) return
        currentGridPage = clamped
        renderGridPage()
    }

    // ── Action mode ───────────────────────────────────────────────────────────

    /** Toggle one page's membership in the selection. Exits action mode if the set empties. */
    private fun toggleSelection(pageId: String) {
        if (!selectedPageIds.remove(pageId)) selectedPageIds.add(pageId)
        if (selectedPageIds.isEmpty()) {
            exitActionMode()
        } else {
            refreshActionMode()
            renderGridPage()
        }
    }

    /**
     * Select-all toggle. When not everything is selected, select every page in the notebook
     * (across all grid pages). When everything is already selected, deselect all — which also
     * exits action mode, since an empty selection is normal mode. (Keeping one selected was
     * considered and rejected as surprising.)
     */
    private fun toggleSelectAll() {
        if (!inActionMode()) return
        if (selectedPageIds.size >= pages.size) {
            exitActionMode()
        } else {
            selectedPageIds.clear()
            pages.forEach { selectedPageIds.add(it.id) }
            refreshActionMode()
            renderGridPage()
        }
    }

    /** Drop any selected IDs no longer present in [pages] (e.g. after a reload). */
    private fun pruneSelection() {
        selectedPageIds.retainAll(pages.map { it.id }.toSet())
    }

    private fun setButtonEnabled(button: android.view.View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /**
     * Reflect the current selection in the chrome: action buttons visibility, the title count,
     * and per-button enablement.
     * - Copy/Move/Export/SetTemplate are enabled for any selection size.
     * - Delete is always enabled in action mode.
     * - The Paste button was removed in Session 2 fixes: Copy immediately enters PASTE dest mode.
     * Does not re-render the grid — callers do that.
     */
    private fun refreshActionMode() {
        val active = inActionMode()
        val vis    = if (active) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnSelectAll.visibility  = vis
        binding.btnCopyPage.visibility   = vis
        binding.btnDeletePage.visibility = vis
        binding.btnMovePage.visibility   = vis
        binding.btnSetTemplate.visibility = vis
        binding.btnExportPage.visibility = vis
        // Before/after buttons are only shown in destination-picking mode.
        binding.btnInsertBefore.visibility = android.view.View.GONE
        binding.btnInsertAfter.visibility  = android.view.View.GONE

        binding.tvTopBarTitle.text = if (active) "${selectedCount()} selected" else "Pages"

        if (active) {
            setButtonEnabled(binding.btnCopyPage,   true)
            setButtonEnabled(binding.btnMovePage,   true)
            setButtonEnabled(binding.btnSetTemplate, true)
            setButtonEnabled(binding.btnExportPage, true)   // multi-export enabled (Session 4)
            setButtonEnabled(binding.btnDeletePage, true)
            // Select-all content description reflects the toggle target.
            binding.btnSelectAll.contentDescription =
                if (selectedPageIds.size >= pages.size) "Deselect all" else "Select all"
        }
    }

    private fun exitActionMode() {
        selectedPageIds.clear()
        destMode = DestMode.NONE
        moveSourceIds = emptyList()
        binding.tvTopBarTitle.text = "Pages"
        binding.btnSelectAll.visibility    = android.view.View.GONE
        binding.btnCopyPage.visibility     = android.view.View.GONE
        binding.btnDeletePage.visibility   = android.view.View.GONE
        binding.btnMovePage.visibility     = android.view.View.GONE
        binding.btnSetTemplate.visibility  = android.view.View.GONE
        binding.btnExportPage.visibility   = android.view.View.GONE
        binding.btnInsertBefore.visibility = android.view.View.GONE
        binding.btnInsertAfter.visibility  = android.view.View.GONE
        renderGridPage()
    }

    // ── Destination-picking mode (move + paste) ───────────────────────────────

    /**
     * Enter destination-picking mode for [mode] (MOVE or PASTE). Hides action buttons, shows
     * the Before/After selectable buttons, and updates the title. [insertBefore] resets to true
     * (Before selected) each time destination mode is entered.
     */
    private fun enterDestMode(mode: DestMode) {
        if (mode == DestMode.NONE) return
        if (mode == DestMode.MOVE && selectedPageIds.isEmpty()) return
        if (mode == DestMode.PASTE && pendingCopyPageIds.isEmpty()) return

        destMode = mode
        insertBefore = true   // reset to "before" each time

        if (mode == DestMode.MOVE) {
            moveSourceIds = selectedPageIds.toList()
        }

        binding.btnSelectAll.visibility  = android.view.View.GONE
        binding.btnCopyPage.visibility   = android.view.View.GONE
        binding.btnDeletePage.visibility = android.view.View.GONE
        binding.btnMovePage.visibility   = android.view.View.GONE
        binding.btnSetTemplate.visibility = android.view.View.GONE
        binding.btnExportPage.visibility = android.view.View.GONE

        // Show the Before/After selectable text buttons; Before is selected by default.
        val verb = if (mode == DestMode.MOVE) "Move" else "Copy"
        binding.btnInsertBefore.text = "$verb Before"
        binding.btnInsertAfter.text  = "$verb After"
        binding.btnInsertBefore.visibility = android.view.View.VISIBLE
        binding.btnInsertAfter.visibility  = android.view.View.VISIBLE
        refreshInsertBeforeAfter()

        val titlePrefix = if (mode == DestMode.MOVE) "Move" else "Paste"
        binding.tvTopBarTitle.text = "$titlePrefix before/after…"
        renderGridPage()
    }

    /**
     * Update the selected state of the Before/After buttons to reflect [insertBefore].
     * [btnInsertBefore] is selected when [insertBefore] == true; [btnInsertAfter] is selected
     * when [insertBefore] == false. Before is selected by default. Uses the [bg_toolbar_button]
     * state_selected selector (1.5dp inkBlack border) to show which is active.
     */
    private fun refreshInsertBeforeAfter() {
        binding.btnInsertBefore.isSelected = insertBefore
        binding.btnInsertAfter.isSelected  = !insertBefore
    }

    /** Cancel destination-picking mode: restore action mode chrome and selection state. */
    private fun cancelDestMode() {
        destMode = DestMode.NONE
        moveSourceIds = emptyList()
        // Hide the Before/After buttons before refreshActionMode re-shows action buttons.
        binding.btnInsertBefore.visibility = android.view.View.GONE
        binding.btnInsertAfter.visibility  = android.view.View.GONE
        refreshActionMode()   // restores action buttons + title
        renderGridPage()
    }

    /**
     * Execute a batch move: moves all [moveSourceIds] before/after [targetPageId].
     * On success, appends this operation's undo/redo batch to [movedActions], reloads pages,
     * recomputes [currentPageIndex] by stable id, then returns to normal mode.
     */
    private fun executeMove(targetPageId: String) {
        val sources = moveSourceIds
        if (sources.isEmpty()) { cancelDestMode(); return }
        if (sources.all { it == targetPageId }) {
            android.widget.Toast.makeText(this, "Pick a page outside the selection", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val path = notebookSoilPath ?: return

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                com.notesprout.android.data.movePagesRelativeRaw(sources, targetPageId, insertBefore, path)
            }
            if (results == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't move pages", android.widget.Toast.LENGTH_SHORT).show()
                cancelDestMode(); return@launch
            }
            if (results.isNotEmpty()) movedActions.add(results)  // one batch per move operation

            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }
            pruneSelection()
            val cid = currentPageId
            if (cid != null) {
                val newIdx = pages.indexOfFirst { it.id == cid }
                if (newIdx >= 0) currentPageIndex = newIdx
            }
            exitActionMode()
        }
    }

    /**
     * Execute a batch paste: deep-copies [pendingCopyPageIds] before/after [targetPageId].
     * On success, appends undo pairs to [pastedActions], reloads pages, adjusts
     * [currentPageIndex] for inserted pages, then returns to normal mode.
     */
    private fun executePaste(targetPageId: String) {
        val sources = pendingCopyPageIds
        if (sources.isEmpty()) { cancelDestMode(); return }
        val path = notebookSoilPath ?: return

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                com.notesprout.android.data.copyPagesRelativeRaw(sources, targetPageId, insertBefore, path)
            }
            if (results == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't paste pages", android.widget.Toast.LENGTH_SHORT).show()
                cancelDestMode(); return@launch
            }
            pastedActions.addAll(results)

            // Reload and recompute currentPageIndex: count how many inserted pages land before it.
            val oldPageIndex = currentPageIndex
            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }

            // Shift currentPageIndex by however many new pages were inserted at or before it.
            val insertedBefore = results.count { it.second <= oldPageIndex }
            currentPageIndex = oldPageIndex + insertedBefore

            // Also try to recover from stable ID (more robust after reload).
            val cid = currentPageId
            if (cid != null) {
                val stableIdx = pages.indexOfFirst { it.id == cid }
                if (stableIdx >= 0) currentPageIndex = stableIdx
            }

            pendingCopyPageIds = emptyList()
            exitActionMode()
        }
    }

    /**
     * Copies the selected pages to the clipboard and immediately enters destination-picking
     * PASTE mode. The clipboard is stashed before the selection is cleared so that the pasted
     * content is exactly the pages that were highlighted when Copy was tapped.
     * The separate Paste button was removed in Session 2 fixes — Copy goes straight to paste mode.
     */
    private fun copySelectedPages() {
        if (selectedPageIds.isEmpty()) return
        // Stash clipboard before selection changes.
        pendingCopyPageIds = selectedPageIds.toList()
        // Immediately enter destination-picking paste mode (same flow as Move).
        enterDestMode(DestMode.PASTE)
    }

    /**
     * Launch the template picker (MODE_PICK) for the current selection. The selection is snapshotted
     * into [pendingTemplateTargets] so the chosen template is applied to exactly the pages that were
     * highlighted when Set Template was tapped (the picker activity may outlive a config change).
     */
    private fun chooseTemplateForSelection() {
        if (!inActionMode()) return
        pendingTemplateTargets = selectedPageIds.toList()
        val intent = Intent(this, TemplateBrowserActivity::class.java).apply {
            putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK)
            putExtra(TemplateBrowserActivity.EXTRA_TITLE, "Set template")
        }
        templatePickerLauncher.launch(intent)
    }

    /**
     * Apply the picked template to every page in [pendingTemplateTargets].
     *
     * [libraryTemplateId] is the global-index library id from the picker, or "" for Blank (clear).
     * A page's `template` property stores a `.soil` template-row id, so a non-blank library template
     * is first copied into the `.soil` ([insertSoilTemplateRaw]); one shared soil row is created per
     * operation and every selected page is pointed at it. Each page's previous/new template ids are
     * recorded in [templateChanges] for undo/redo.
     */
    private fun applyTemplateToSelection(libraryTemplateId: String) {
        val targets = pendingTemplateTargets
        pendingTemplateTargets = emptyList()
        if (targets.isEmpty()) return
        val path = notebookSoilPath ?: return

        lifecycleScope.launch {
            val soilTemplateId: String? = withContext(Dispatchers.IO) {
                if (libraryTemplateId.isEmpty()) {
                    ""  // Blank — clear template.
                } else {
                    val entity = indexRepo.getTemplate(libraryTemplateId) ?: return@withContext null
                    val tObj = TemplateObject.fromJson(entity.data) ?: return@withContext null
                    if (tObj.image.isEmpty()) return@withContext null
                    val parentId = com.notesprout.android.data.readNotebookRowId(path)
                        ?: MainActivity.NIL_UUID
                    com.notesprout.android.data.insertSoilTemplateRaw(
                        notebookPath = path,
                        parentId     = parentId,
                        width        = tObj.width,
                        height       = tObj.height,
                        name         = entity.name,
                        imageBase64  = tObj.image,
                    )
                }
            }
            if (soilTemplateId == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't set template", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val pairs = withContext(Dispatchers.IO) {
                com.notesprout.android.data.setPagesTemplateRaw(targets, soilTemplateId, path)
            }
            if (pairs == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't set template", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val newId = soilTemplateId.takeIf { it.isNotEmpty() }
            pairs.forEach { (pageId, prev) -> templateChanges.add(Triple(pageId, prev, newId)) }

            if (libraryTemplateId.isNotEmpty()) {
                TemplateRecentsManager.recordUse(this@PageIndexActivity, libraryTemplateId)
            }

            val msg = if (pairs.size == 1) "Template set" else "Template set on ${pairs.size} pages"
            android.widget.Toast.makeText(this@PageIndexActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
            exitActionMode()
        }
    }

    private fun executeDelete() {
        if (!inActionMode()) return
        val path = notebookSoilPath ?: return

        // The notebook must retain at least one page.
        if (selectedPageIds.size >= pages.size) {
            android.widget.Toast.makeText(this, "Cannot delete all pages", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Snapshot the selection and each page's pre-delete index, so all recorded indices are
        // consistent with the page list NotebookActivity will undo against.
        val targetIds   = selectedPageIds.toList()
        val indexById   = pages.withIndex().associate { (i, p) -> p.id to i }
        val message = if (targetIds.size == 1) {
            val pageNum = pages.firstOrNull { it.id == targetIds[0] }?.pageNumber
            if (pageNum != null) "Delete Page $pageNum?" else "Delete 1 page?"
        } else {
            "Delete ${targetIds.size} pages?"
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        targetIds.mapNotNull { id ->
                            val deletedAt = com.notesprout.android.data.deletePageRaw(id, path)
                            val originalIndex = indexById[id]
                            if (deletedAt != null && originalIndex != null) {
                                Triple(id, originalIndex, deletedAt)
                            } else null
                        }
                    }
                    if (deleted.isEmpty()) {
                        android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't delete pages", android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    deletedActions.addAll(deleted)

                    pages = withContext(kotlinx.coroutines.Dispatchers.IO) { loadPagesFromSoil(path) }
                    // Recompute the open page by stable ID; if it was deleted, clamp to a survivor.
                    val cid = currentPageId
                    val newIdx = if (cid != null) pages.indexOfFirst { it.id == cid } else -1
                    currentPageIndex = if (newIdx >= 0) newIdx
                        else currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    currentPageId = pages.getOrNull(currentPageIndex)?.id

                    // Clamp grid page in case the last grid page is now empty.
                    currentGridPage = currentGridPage.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
                    exitActionMode()
                }
            }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * Entry point for the Export toolbar button. Routes single-selection through the richer
     * existing single-page flow ([showExportChoice]: Save to device / Save as Template / Share),
     * and multi-selection through the new multi-export dialog (PDF / PNG / Cancel).
     */
    private fun executeExport() {
        if (!inActionMode()) return
        if (selectedCount() == 1) {
            executeSingleExport()
        } else {
            showMultiExportDialog()
        }
    }

    /** Single-page export — renders a PNG then offers Save / Template / Share. */
    private fun executeSingleExport() {
        val pageId = selectedPageIds.singleOrNull() ?: return
        val pageEntry = pages.firstOrNull { it.id == pageId } ?: return
        val path = notebookSoilPath ?: return
        val notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook"

        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        lifecycleScope.launch {
            val pngFile = try {
                withContext(Dispatchers.IO) {
                    NotebookExporter.exportPage(
                        context = this@PageIndexActivity,
                        soilPath = path,
                        pageId = pageEntry.id,
                        pageNumber = pageEntry.pageNumber,
                        notebookTitle = notebookName,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PageIndexActivity", "PNG export failed", e)
                dialog.dismiss()
                android.widget.Toast.makeText(this@PageIndexActivity, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            dialog.dismiss()
            exitActionMode()
            val defaultName = sanitizeTemplateName(
                pageEntry.headingName ?: "Page ${pageEntry.pageNumber}"
            )
            showExportChoice(pngFile, defaultName)
        }
    }

    /**
     * Multi-page export dialog: presents PDF / PNG / Cancel.
     * Selected pages are sorted to page order (not selection order) before export.
     */
    private fun showMultiExportDialog() {
        val n = selectedCount()
        val d = AlertDialog.Builder(this)
            .setTitle("Export $n pages")
            .setPositiveButton("PDF") { _, _ -> exportMultiAsPdf() }
            .setNeutralButton("PNG") { _, _ -> showPngSubchoiceDialog() }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * PNG sub-choice: Save images (to a folder) vs Save as templates (import into library).
     */
    private fun showPngSubchoiceDialog() {
        val d = AlertDialog.Builder(this)
            .setTitle("PNG export")
            .setPositiveButton("Save images") { _, _ -> exportMultiAsPngFiles() }
            .setNeutralButton("Save as templates") { _, _ -> exportMultiAsPngTemplates() }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * Build the ordered page list for a multi-export (by page order, not selection insertion order).
     * Returns null if there's nothing to export.
     */
    private fun orderedSelectedEntries(): List<PageEntry>? {
        if (selectedPageIds.isEmpty()) return null
        // Sort by position in the pages list (which is sorted by `order`) rather than selection order.
        val idSet = selectedPageIds.toSet()
        return pages.filter { it.id in idSet }.takeIf { it.isNotEmpty() }
    }

    /**
     * Export all selected pages to a single PDF. Shows a progress dialog while rendering;
     * afterwards offers Save to device ([savePdfLauncher]) and Share (FileProvider).
     */
    private fun exportMultiAsPdf() {
        val entries = orderedSelectedEntries() ?: return
        val path    = notebookSoilPath ?: return
        val notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook"
        val pageIds = entries.map { it.id }

        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val progressDialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setElevation(0f)
        progressDialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        lifecycleScope.launch {
            val pdfFile = try {
                withContext(Dispatchers.IO) {
                    NotebookExporter.exportPagesPdf(
                        context = this@PageIndexActivity,
                        soilPath = path,
                        pageIds = pageIds,
                        notebookTitle = notebookName,
                        onProgress = { current, total ->
                            handler.post { tvMessage.text = "Exporting page $current of $total…" }
                        },
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PageIndexActivity", "PDF export failed", e)
                progressDialog.dismiss()
                android.widget.Toast.makeText(this@PageIndexActivity, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            progressDialog.dismiss()
            exitActionMode()
            showPdfExportChoice(pdfFile)
        }
    }

    /**
     * Offer Save to device or Share after a multi-page PDF is rendered.
     */
    private fun showPdfExportChoice(file: java.io.File) {
        val d = AlertDialog.Builder(this)
            .setTitle("Export PDF")
            .setPositiveButton("Save to device") { _, _ ->
                pendingExportFile = file
                savePdfLauncher.launch(file.name)
            }
            .setNegativeButton("Share") { _, _ ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share PDF"))
            }
            .create()
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * Export all selected pages as individual PNGs, auto-named from notebook + page label.
     * Renders to cache first (with a progress dialog), then prompts for a destination folder
     * once via [openDocumentTreeLauncher]. No per-file prompts.
     */
    private fun exportMultiAsPngFiles() {
        val entries = orderedSelectedEntries() ?: return
        val path    = notebookSoilPath ?: return
        val notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook"

        // Build (pageId, filenameBase) pairs with sanitized, de-duplicated names.
        val safeNotebook = notebookName.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")
            .trim('_', ' ').ifBlank { "notebook" }
        val usedNames = mutableSetOf<String>()
        val pageSpecs: List<Pair<String, String>> = entries.map { entry ->
            val rawLabel = entry.headingName ?: "Page${entry.pageNumber}"
            val safeLabel = rawLabel.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
                .ifBlank { "Page${entry.pageNumber}" }
            val base = "${safeNotebook}_${safeLabel}"
            val uniqueBase = makeUniqueFilename(base, usedNames)
            usedNames.add(uniqueBase)
            Pair(entry.id, uniqueBase)
        }

        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val progressDialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setElevation(0f)
        progressDialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        lifecycleScope.launch {
            val pngFiles = try {
                withContext(Dispatchers.IO) {
                    NotebookExporter.exportPagesPng(
                        context = this@PageIndexActivity,
                        soilPath = path,
                        pages = pageSpecs,
                        onProgress = { current, total ->
                            handler.post { tvMessage.text = "Exporting page $current of $total…" }
                        },
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PageIndexActivity", "PNG batch export failed", e)
                progressDialog.dismiss()
                android.widget.Toast.makeText(this@PageIndexActivity, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            progressDialog.dismiss()
            exitActionMode()

            if (pngFiles.isEmpty()) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Nothing to export", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Prompt once for a destination folder; writes happen in openDocumentTreeLauncher callback.
            pendingPngFiles = pngFiles
            openDocumentTreeLauncher.launch(null)
        }
    }

    /**
     * Write [files] into the folder at [treeUri] using [DocumentsContract].
     * Returns the count of files successfully written.
     * Runs on the IO dispatcher (caller is responsible).
     */
    private fun writePngFilesToTree(treeUri: Uri, files: List<java.io.File>): Int {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        var written = 0
        for (file in files) {
            try {
                val docUri = DocumentsContract.createDocument(
                    contentResolver, treeDocUri, "image/png", file.name
                ) ?: continue
                contentResolver.openOutputStream(docUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                written++
            } catch (e: Exception) {
                android.util.Log.e("PageIndexActivity", "Failed to write ${file.name} to tree", e)
            }
        }
        Slog.d("PageIndexActivity") { "writePngFilesToTree: wrote $written of ${files.size}" }
        return written
    }

    /**
     * Export all selected pages as PNGs and import each into the template library at the root
     * folder (parentId = null). Page label is used as the template name (sanitized, de-duped
     * against existing root templates). All heavy work runs on [Dispatchers.IO].
     *
     * Phase-2 note: choosing a destination folder for the template import (rather than always
     * importing into root) is deferred to Phase 2.
     */
    private fun exportMultiAsPngTemplates() {
        val entries = orderedSelectedEntries() ?: return
        val path    = notebookSoilPath ?: return
        val notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook"

        // Build (pageId, filenameBase) specs for rendering; names will be re-sanitized for the
        // template library below.
        val safeNotebook = notebookName.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")
            .trim('_', ' ').ifBlank { "notebook" }
        val renderUsedNames = mutableSetOf<String>()
        val pageSpecs: List<Pair<String, String>> = entries.map { entry ->
            val rawLabel = entry.headingName ?: "Page${entry.pageNumber}"
            val safeLabel = rawLabel.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
                .ifBlank { "Page${entry.pageNumber}" }
            val base = "${safeNotebook}_${safeLabel}"
            val uniqueBase = makeUniqueFilename(base, renderUsedNames)
            renderUsedNames.add(uniqueBase)
            Pair(entry.id, uniqueBase)
        }
        // Template names: use the raw page label directly (shorter / more readable than the
        // notebook-prefixed filename base).
        val templateLabels: List<String> = entries.map { entry ->
            sanitizeTemplateName(entry.headingName ?: "Page ${entry.pageNumber}")
        }

        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val progressDialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setElevation(0f)
        progressDialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        lifecycleScope.launch {
            val pngFiles = try {
                withContext(Dispatchers.IO) {
                    NotebookExporter.exportPagesPng(
                        context = this@PageIndexActivity,
                        soilPath = path,
                        pages = pageSpecs,
                        onProgress = { current, total ->
                            handler.post { tvMessage.text = "Rendering page $current of $total…" }
                        },
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PageIndexActivity", "PNG-as-templates render failed", e)
                progressDialog.dismiss()
                android.widget.Toast.makeText(this@PageIndexActivity, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            if (pngFiles.isEmpty()) {
                progressDialog.dismiss()
                android.widget.Toast.makeText(this@PageIndexActivity, "Nothing to export", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            handler.post { tvMessage.text = "Importing templates…" }

            val importedCount = withContext(Dispatchers.IO) {
                // Fetch existing root-level template names once so de-duplication is consistent.
                val existing = runCatching { indexRepo.getTemplates(null) }.getOrElse { emptyList() }
                val existingNames = existing.map { it.name }.toMutableList()

                var count = 0
                for ((idx, file) in pngFiles.withIndex()) {
                    val rawName = templateLabels.getOrNull(idx) ?: sanitizeTemplateName(file.nameWithoutExtension)
                    val finalName = makeUniqueTemplateName(rawName, existingNames)
                    existingNames.add(finalName)  // reserve so subsequent iterations don't collide

                    try {
                        val bytes = file.readBytes()
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        val w = opts.outWidth; val h = opts.outHeight
                        if (w <= 0 || h <= 0) {
                            android.util.Log.w("PageIndexActivity", "Template import: invalid bounds for ${file.name}")
                            continue
                        }
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        indexRepo.createTemplate(finalName, null, w, h, base64)
                        Slog.d("PageIndexActivity") { "Imported template '$finalName' (${w}x${h})" }
                        count++
                    } catch (e: Exception) {
                        android.util.Log.e("PageIndexActivity", "Template import failed for ${file.name}", e)
                    }
                }
                count
            }

            progressDialog.dismiss()
            exitActionMode()

            val total = pngFiles.size
            val msg = if (importedCount == total) "Saved $total templates"
                      else "Saved $importedCount of $total templates"
            android.widget.Toast.makeText(this@PageIndexActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * De-duplicate a filename base among [used] names by appending `_2`, `_3`, etc.
     * Does NOT include an extension — callers add ".png" separately.
     */
    private fun makeUniqueFilename(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while ("${base}_$n" in used) n++
        return "${base}_$n"
    }

    /**
     * De-duplicate a template name among [existing] names using `(2)`, `(3)`, … suffix,
     * matching the convention used in [TemplateBrowserActivity.makeUniqueName].
     */
    private fun makeUniqueTemplateName(name: String, existing: List<String>): String {
        if (existing.none { it.equals(name, ignoreCase = true) }) return name
        var n = 2
        while (existing.any { it.equals("$name ($n)", ignoreCase = true) }) n++
        return "$name ($n)"
    }

    /** Whitelist a proposed template name to the browser's accepted characters; never empty. */
    private fun sanitizeTemplateName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "Template" else cleaned
    }

    private fun showExportChoice(file: java.io.File, templateDefaultName: String) {
        val d = AlertDialog.Builder(this)
            .setTitle("Export page")
            .setPositiveButton("Save to device") { _, _ ->
                pendingExportFile = file
                savePngLauncher.launch(file.name)
            }
            .setNeutralButton("Save as Template") { _, _ ->
                val intent = Intent(this, TemplateBrowserActivity::class.java).apply {
                    putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_SAVE_TARGET)
                    putExtra(TemplateBrowserActivity.EXTRA_SAVE_SOURCE_PATH, file.absolutePath)
                    putExtra(TemplateBrowserActivity.EXTRA_SAVE_DEFAULT_NAME, templateDefaultName)
                    putExtra(TemplateBrowserActivity.EXTRA_TITLE, "Save as Template")
                }
                saveTemplateLauncher.launch(intent)
            }
            .setNegativeButton("Share") { _, _ ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share page"))
            }
            .create()
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /** Encode all session paste/delete/move actions into the result and finish. */
    private fun finishWithResult(selectedPageIndex: Int?) {
        val result = Intent()
        if (selectedPageIndex != null) {
            result.putExtra(EXTRA_SELECTED_PAGE_INDEX, selectedPageIndex)
        }
        if (pastedActions.isNotEmpty()) {
            result.putExtra(EXTRA_PASTED_PAGE_IDS,     pastedActions.joinToString(",") { it.first })
            result.putExtra(EXTRA_PASTED_PAGE_INDICES, pastedActions.joinToString(",") { it.second.toString() })
        }
        if (deletedActions.isNotEmpty()) {
            result.putExtra(EXTRA_DELETED_PAGE_IDS,        deletedActions.joinToString(",") { it.first })
            result.putExtra(EXTRA_DELETED_PAGE_INDICES,    deletedActions.joinToString(",") { it.second.toString() })
            result.putExtra(EXTRA_DELETED_PAGE_TIMESTAMPS, deletedActions.joinToString(",") { it.third.toString() })
        }
        if (movedActions.isNotEmpty()) {
            // Flatten all operations; EXTRA_MOVED_OP_SIZES records the page-count per operation so
            // NotebookActivity can split them back into one PagesMoved undo step each.
            val flat = movedActions.flatten()
            result.putExtra(EXTRA_MOVED_PAGE_IDS,       flat.joinToString(",") { it.first })
            result.putExtra(EXTRA_MOVED_PREV_AFTER_IDS, flat.joinToString(",") { it.second ?: "" })
            result.putExtra(EXTRA_MOVED_NEW_AFTER_IDS,  flat.joinToString(",") { it.third ?: "" })
            result.putExtra(EXTRA_MOVED_OP_SIZES,       movedActions.joinToString(",") { it.size.toString() })
        }
        if (templateChanges.isNotEmpty()) {
            // Empty string encodes a null/blank template id (same convention as the moved extras).
            result.putExtra(EXTRA_TEMPLATE_PAGE_IDS, templateChanges.joinToString(",") { it.first })
            result.putExtra(EXTRA_TEMPLATE_PREV_IDS, templateChanges.joinToString(",") { it.second ?: "" })
            result.putExtra(EXTRA_TEMPLATE_NEW_IDS,  templateChanges.joinToString(",") { it.third ?: "" })
        }
        setResult(RESULT_OK, result)
        finish()
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun updatePaginationControls() {
        val total   = totalGridPages()
        val display = currentGridPage + 1

        binding.tvPage.text = "$display/$total"

        val atFirst = currentGridPage == 0
        val atLast  = currentGridPage >= total - 1

        binding.btnFirstPage.isEnabled = !atFirst
        binding.btnPrevPage.isEnabled  = !atFirst
        binding.btnNextPage.isEnabled  = !atLast
        binding.btnLastPage.isEnabled  = !atLast
    }
}
