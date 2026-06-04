package com.notesprout.android

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
        const val EXTRA_NOTEBOOK_PATH         = "notebook_path"
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
        /** Comma-separated UUIDs of pages moved during this session (may be empty). */
        const val EXTRA_MOVED_PAGE_IDS        = "moved_page_ids"
        /** Comma-separated previous-after page IDs matching [EXTRA_MOVED_PAGE_IDS] (empty string = was first page). */
        const val EXTRA_MOVED_PREV_AFTER_IDS  = "moved_prev_after_ids"
        /** Comma-separated target-after page IDs matching [EXTRA_MOVED_PAGE_IDS]. */
        const val EXTRA_MOVED_TARGET_IDS      = "moved_target_ids"
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

    private data class PageEntry(val id: String, val pageNumber: Int, val snapshot: String?)

    @Serializable
    private data class PageSnapshot(val snapshot: String? = null)

    private val pageCodec = Json { ignoreUnknownKeys = true }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var binding: ActivityPageIndexBinding
    private var pages: List<PageEntry> = emptyList()
    private var currentPageIndex: Int = 0   // 0-based index in pages list (the open page)
    private var currentGridPage: Int = 0    // 0-based pagination index
    private var gridSpec: GridSpec? = null

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }

    private val snapshotDecodeJobs = mutableListOf<Job>()

    // ── Action mode (long-press) and move mode ────────────────────────────────

    /** Index into [pages] of the card selected by a long-press. Null = normal mode. */
    private var actionModePageIndex: Int? = null

    /** Page ID waiting to be pasted. Null means clipboard is empty. */
    private var pendingCopyPageId: String? = null

    /** True while the user is picking a destination for a page move. */
    private var isMoveMode: Boolean = false

    /** ID of the page being moved (set when move mode is entered). */
    private var moveModeSourcePageId: String? = null

    /** Stable ID of the currently-open page in DrawingActivity — used to recompute
     *  [currentPageIndex] after a move reshuffles the pages list. */
    private var currentPageId: String? = null

    /** Paste operations performed this session — returned to DrawingActivity for undo/redo. */
    private val pastedActions  = mutableListOf<Pair<String, Int>>()          // (pageId, pageIndex)
    /** Delete operations performed this session — returned to DrawingActivity for undo/redo. */
    private val deletedActions = mutableListOf<Triple<String, Int, Long>>()  // (pageId, pageIndex, deletedAt)
    /** Move operations performed this session — returned to DrawingActivity for undo/redo. */
    private val movedActions   = mutableListOf<Triple<String, String?, String>>() // (pageId, prevAfterId, targetId)

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

        currentPageIndex = intent.getIntExtra(EXTRA_CURRENT_PAGE_INDEX, 0)

        binding.btnBack.setOnClickListener {
            when {
                isMoveMode -> cancelMoveMode()
                actionModePageIndex != null -> exitActionMode()
                else -> finishWithResult(null)
            }
        }

        binding.btnCopyPage.setOnClickListener  { copySelectedPage() }
        binding.btnPastePage.setOnClickListener { executePaste() }
        binding.btnDeletePage.setOnClickListener { executeDelete() }
        binding.btnMovePage.setOnClickListener  { enterMoveMode() }

        // Back gesture exits move/action mode; if already in normal mode, return to DrawingActivity.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isMoveMode -> cancelMoveMode()
                    actionModePageIndex != null -> exitActionMode()
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
        val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
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
                    result.add(PageEntry(id, number++, snapshot))
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
     * Action mode tap (same card): navigate + exit action mode.
     * Action mode tap (different card): move paste-target selection.
     * Move mode tap (source card): cancel move, return to action mode.
     * Move mode tap (any other card): execute move, return to normal mode.
     * Long press: enter action mode with this card selected.
     */
    private fun buildCardGroup(entry: PageEntry, spec: GridSpec): LinearLayout {
        val pageIndex  = entry.pageNumber - 1   // 0-based
        val isCurrent  = pageIndex == currentPageIndex
        val isSelected = pageIndex == actionModePageIndex
        val isSource   = isMoveMode && pages.getOrNull(pageIndex)?.id == moveModeSourcePageId

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL

            setOnClickListener {
                when {
                    isMoveMode -> {
                        if (isSource) cancelMoveMode()
                        else executeMoveAfter(pageIndex)
                    }
                    actionModePageIndex == null -> finishWithResult(pageIndex)
                    pageIndex == actionModePageIndex -> { exitActionMode(); finishWithResult(pageIndex) }
                    else -> { actionModePageIndex = pageIndex; renderGridPage() }
                }
            }

            setOnLongClickListener {
                enterActionMode(pageIndex)
                true
            }
        }

        // ── Card ──────────────────────────────────────────────────────────────
        // Move mode: highlight only the source card being moved.
        // Action mode: highlight only the selected card.
        // Normal mode: highlight only the currently-open page.
        val highlighted = when {
            isMoveMode -> isSource
            actionModePageIndex != null -> isSelected
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
            text      = "Page ${entry.pageNumber}"
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

    private fun enterActionMode(pageIndex: Int) {
        actionModePageIndex = pageIndex
        binding.btnCopyPage.visibility   = android.view.View.VISIBLE
        binding.btnPastePage.visibility  = android.view.View.VISIBLE
        binding.btnPastePage.isEnabled   = pendingCopyPageId != null
        binding.btnDeletePage.visibility = android.view.View.VISIBLE
        binding.btnMovePage.visibility   = android.view.View.VISIBLE
        renderGridPage()
    }

    private fun exitActionMode() {
        actionModePageIndex = null
        binding.btnCopyPage.visibility   = android.view.View.GONE
        binding.btnPastePage.visibility  = android.view.View.GONE
        binding.btnDeletePage.visibility = android.view.View.GONE
        binding.btnMovePage.visibility   = android.view.View.GONE
        renderGridPage()
    }

    // ── Move mode ─────────────────────────────────────────────────────────────

    /** Enter move mode: hide action buttons, show "Move to…" title, keep source card highlighted. */
    private fun enterMoveMode() {
        val idx = actionModePageIndex ?: return
        moveModeSourcePageId = pages.getOrNull(idx)?.id ?: return
        isMoveMode = true
        binding.btnCopyPage.visibility   = android.view.View.GONE
        binding.btnPastePage.visibility  = android.view.View.GONE
        binding.btnDeletePage.visibility = android.view.View.GONE
        binding.btnMovePage.visibility   = android.view.View.GONE
        binding.tvTopBarTitle.text = "Move to…"
        renderGridPage()
    }

    /** Cancel move mode: restore action mode buttons and "Pages" title. */
    private fun cancelMoveMode() {
        isMoveMode = false
        moveModeSourcePageId = null
        binding.tvTopBarTitle.text = "Pages"
        if (actionModePageIndex != null) {
            binding.btnCopyPage.visibility   = android.view.View.VISIBLE
            binding.btnPastePage.visibility  = android.view.View.VISIBLE
            binding.btnPastePage.isEnabled   = pendingCopyPageId != null
            binding.btnDeletePage.visibility = android.view.View.VISIBLE
            binding.btnMovePage.visibility   = android.view.View.VISIBLE
        }
        renderGridPage()
    }

    /** Complete move mode: restore "Pages" title and return to normal mode. */
    private fun completeMoveMode() {
        isMoveMode = false
        moveModeSourcePageId = null
        binding.tvTopBarTitle.text = "Pages"
        exitActionMode()  // hides all buttons, clears actionModePageIndex, re-renders
    }

    /** Move [moveModeSourcePageId] to immediately after the page at [destinationIndex]. */
    private fun executeMoveAfter(destinationIndex: Int) {
        val sourceId = moveModeSourcePageId ?: return
        val targetId = pages.getOrNull(destinationIndex)?.id ?: return
        if (sourceId == targetId) { cancelMoveMode(); return }
        val path = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return

        lifecycleScope.launch {
            val prevAfterResultRaw = withContext(Dispatchers.IO) {
                com.notesprout.android.data.movePageAfterRaw(sourceId, targetId, path)
            }
            if (prevAfterResultRaw == null) { cancelMoveMode(); return@launch }

            // "" = source was first page (no previous page); non-empty = UUID of previous page.
            val prevAfterId: String? = prevAfterResultRaw.ifEmpty { null }

            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }
            movedActions.add(Triple(sourceId, prevAfterId, targetId))

            // Recompute currentPageIndex by stable ID in case the move shifted it.
            val cid = currentPageId
            if (cid != null) {
                val newIdx = pages.indexOfFirst { it.id == cid }
                if (newIdx >= 0) currentPageIndex = newIdx
            }

            completeMoveMode()
        }
    }

    /**
     * Copies the selected page ID to the clipboard and stays in action mode.
     * The paste button is enabled immediately — the user can tap another card
     * to move the paste-target selection, then tap Paste.
     */
    private fun copySelectedPage() {
        val idx = actionModePageIndex ?: return
        pendingCopyPageId = pages.getOrNull(idx)?.id
        binding.btnPastePage.isEnabled = pendingCopyPageId != null
    }

    /** Deep-copies the clipboard page after the selected card, refreshes the grid. */
    private fun executePaste() {
        val sourcePageId = pendingCopyPageId ?: return
        val targetIdx    = actionModePageIndex ?: return
        val targetPageId = pages.getOrNull(targetIdx)?.id ?: return
        val path         = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return

        lifecycleScope.launch {
            val newPageId = withContext(Dispatchers.IO) {
                com.notesprout.android.data.copyPageAfterRaw(sourcePageId, targetPageId, path)
            }
            if (newPageId == null) return@launch

            // Reload pages and record the paste for undo/redo on return.
            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }
            val newPageIndex = pages.indexOfFirst { it.id == newPageId }
            if (newPageIndex >= 0) pastedActions.add(newPageId to newPageIndex)

            // Adjust currentPageIndex if the insertion shifted it.
            val insertionIndex = targetIdx + 1
            if (insertionIndex <= currentPageIndex) currentPageIndex++

            pendingCopyPageId = null
            exitActionMode()   // hides buttons, re-renders grid
        }
    }

    private fun executeDelete() {
        val idx      = actionModePageIndex ?: return
        val pageId   = pages.getOrNull(idx)?.id ?: return
        val path     = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: return
        val pageNum  = pages.getOrNull(idx)?.pageNumber ?: return

        if (pages.size <= 1) {
            android.widget.Toast.makeText(this, "Cannot delete the only page", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Delete Page $pageNum?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deletedAt = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.notesprout.android.data.deletePageRaw(pageId, path)
                    }
                    if (deletedAt == null) return@launch

                    deletedActions.add(Triple(pageId, idx, deletedAt))

                    // Adjust currentPageIndex for the removed page.
                    if (idx < currentPageIndex) currentPageIndex--
                    else if (idx == currentPageIndex) currentPageIndex = (idx - 1).coerceAtLeast(0)

                    pages = withContext(kotlinx.coroutines.Dispatchers.IO) { loadPagesFromSoil(path) }
                    // Clamp grid page in case we were on the last grid page and it's now empty.
                    currentGridPage = currentGridPage.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
                    exitActionMode()
                }
            }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
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
            result.putExtra(EXTRA_MOVED_PAGE_IDS,       movedActions.joinToString(",") { it.first })
            result.putExtra(EXTRA_MOVED_PREV_AFTER_IDS, movedActions.joinToString(",") { it.second ?: "" })
            result.putExtra(EXTRA_MOVED_TARGET_IDS,     movedActions.joinToString(",") { it.third })
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
