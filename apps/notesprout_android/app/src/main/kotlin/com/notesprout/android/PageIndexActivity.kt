package com.notesprout.android

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
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
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.core.Slog
import com.notesprout.android.core.TopGuard
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.templateObject
import com.notesprout.android.data.recents.TemplateRecentsManager
import com.notesprout.android.data.soilFile
import com.notesprout.android.data.loadPageRefs
import com.notesprout.android.databinding.ActivityPageIndexBinding
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
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
        /**
         * Comma-separated UUIDs of pages removed from the source notebook during a cross-notebook
         * move. NotebookActivity uses these to build a [UndoRedoAction.CrossNotebookPagesRemoved]
         * action so the source removal can be undone.
         */
        const val EXTRA_XNB_REMOVED_PAGE_IDS   = "xnb_removed_page_ids"
        /** The shared soft-delete timestamp for [EXTRA_XNB_REMOVED_PAGE_IDS]. */
        const val EXTRA_XNB_REMOVED_DELETED_AT = "xnb_removed_deleted_at"
        /**
         * When the user chooses "Open ‹DestName›" after a cross-notebook copy/move, set to the
         * destination notebook id so [NotebookActivity] can seal the source and open the dest.
         */
        const val EXTRA_OPEN_NOTEBOOK_ID   = "open_notebook_id"
        /** Display name matching [EXTRA_OPEN_NOTEBOOK_ID]. */
        const val EXTRA_OPEN_NOTEBOOK_NAME = "open_notebook_name"
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

    private data class PageEntry(val id: String, val pageNumber: Int, val headingName: String? = null)

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

    /** entry.id → the card's thumbnail ImageView for the current grid page; filled by
     *  [buildCardGroup], consumed by [renderVisibleThumbnails] which renders on demand. */
    private val thumbnailTargets = mutableMapOf<String, AppCompatImageView>()

    /** Rendered page thumbnails, keyed by page id — an access-ordered LRU. Page content cannot
     *  change while this screen is open except through its own template action (which invalidates
     *  the affected ids), and moves/copies/deletes never alter a surviving page's pixels, so
     *  entries stay valid across reloads and grid-page flips. Bounded to [thumbCacheCap] entries
     *  (~3 grid pages: current + both prefetched neighbours); evicted bitmaps are dropped to GC,
     *  never recycled — an evicted entry may still be set on a visible ImageView. Cleared wholesale
     *  only on a card-size change and in onDestroy. Touched from the main thread only. */
    private val thumbnailCache = LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true)
    private var thumbCardW = 0
    private var thumbCardH = 0

    /** Decoded template bitmaps shared across thumbnail renders (pages usually share a template,
     *  so the grid decodes it once, not once per card). See [NotebookExporter.renderPageThumbnail].
     *  Cleared alongside [thumbnailCache]; entries are dropped to GC, never recycled. */
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /** One Room connection reused by every thumbnail pass (read-only under WAL, so it never
     *  contends with the raw write ops), opened via the raw-key fast path when the key is cached.
     *  Built lazily by [thumbDao]; closed in [onDestroy]. */
    private var thumbDb: SoilDatabase? = null
    private val thumbDbMutex = kotlinx.coroutines.sync.Mutex()

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

    /**
     * The destination card the user has tapped in destination-picking mode but not yet confirmed.
     * `null` = no destination chosen yet (still picking). When non-null, the insertion-bar preview
     * is drawn and the Confirm (✓) button is shown; the op only commits on [confirmDestination].
     */
    private var pendingDestPageId: String? = null

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

    // ── Cross-notebook page transfer state ────────────────────────────────────

    /** Parameters for a cross-notebook copy or move, set while the user picks a dest page. */
    private data class CrossNotebookInfo(
        val sourcePageIds: List<String>,
        val sourcePath: String,
        val sourceNotebookId: String,
        val sourcePass: String?,
        val destNotebookId: String,
        val destName: String,
        val destPath: String,
        val destPass: String?,
        val isCopy: Boolean,
    )
    private var crossInfo: CrossNotebookInfo? = null
    /** [pages] snapshot before swapping in dest pages; restored on cancel or after the op. */
    private var savedSourcePages: List<PageEntry> = emptyList()
    /** Source IDs snapshotted when the user taps "Other Notebook" in the scope chooser. */
    private var crossPendingSourceIds: List<String> = emptyList()
    /** Whether the pending cross-notebook op is a copy (true) or move (false). */
    private var pendingCrossIsCopy: Boolean = true
    /** Page IDs removed from source by a cross-notebook move — returned for source-side undo. */
    private var xnbRemovedPageIds: List<String> = emptyList()
    private var xnbRemovedDeletedAt: Long = 0L
    /** When the user chooses "Open ‹DestName›", set so finishWithResult includes the dest extras. */
    private var pendingOpenNotebookId: String? = null
    private var pendingOpenNotebookName: String? = null

    /** Global-index repository (templates live in `notesprout.db`, not the `.soil`). */
    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    /** Key resolved for this notebook when [KeySession] was cold. See [resolveNotebookKey]. */
    private var resolvedKey: String? = null

    /** This notebook's key scope per the global index (null = plaintext or unknown); set by
     *  [resolveNotebookKey], consumed by the raw-key fast open paths. */
    private var keyScope: com.notesprout.android.crypto.KeyScope? = null

    /** Pages whose template will be set once the picker returns (snapshot of the selection). */
    private var pendingTemplateTargets: List<String> = emptyList()

    private val notebookPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            // Cancelled — stay in action mode.
            refreshActionMode()
            renderGridPage()
            return@registerForActivityResult
        }
        val destId   = result.data?.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_ID) ?: return@registerForActivityResult
        val destName = result.data?.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_NAME) ?: ""
        enterCrossNotebookDestMode(destId, destName, pendingCrossIsCopy)
    }

    private val templatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val templateId = result.data?.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_ID)
            ?: return@registerForActivityResult
        applyTemplateToSelection(templateId)
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
        // Nothing has opened the index if Android rebuilt this task itself — see IndexGuard.
        if (!IndexGuard.ready(this)) return
        if (bounceIfIndexNotReady()) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityPageIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Immersive: system bars are hidden, so their inset is 0. Reserve the guard
        // explicitly so the top bar isn't parked in the status bar's reveal zone.
        TopGuard.applyRootPadding(binding.root)

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
        binding.btnCopyPage.setOnClickListener   { showScopeChooser(isCopy = true) }
        binding.btnDeletePage.setOnClickListener { executeDelete() }
        binding.btnMovePage.setOnClickListener   { showScopeChooser(isCopy = false) }
        binding.btnSetTemplate.setOnClickListener { chooseTemplateForSelection() }
        binding.btnExportPage.setOnClickListener { executeExport() }
        binding.btnInsertBefore.setOnClickListener { insertBefore = true;  refreshInsertBeforeAfter() }
        binding.btnInsertAfter.setOnClickListener  { insertBefore = false; refreshInsertBeforeAfter() }
        binding.btnConfirmDest.setOnClickListener  { confirmDestination() }

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

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        super.onDestroy()
        // lifecycleScope is cancelled by now; a render worker that raced the close reports through
        // the pass's catch. Cached bitmaps are dropped to GC, not recycled (see thumbnailCache docs).
        thumbDb?.close()
        thumbDb = null
        thumbnailCache.clear()
        templateCache.clear()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    /**
     * Resolve this notebook's SQLCipher key, preferring the foreground session.
     *
     * [KeySession] is process-scoped and in-memory: after process death (or when this screen is
     * reached by launch restore rather than from NotebookActivity) it is empty, and a bare
     * `KeySession.getFor()` would hand a null key to the raw open path. That is not "plaintext" —
     * it is "not resolved yet", and treating the two as the same is what destroyed a notebook.
     * Mirrors ExportActivity.resolveKey().
     */
    private suspend fun resolveNotebookKey(): String? {
        // Scope is read up front (not only on a KeySession miss): the raw-key fast paths
        // ([thumbDao], [loadPagesFromSoil]) need it to find the cached derived key.
        val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(notebookId) }
        keyScope = info?.keyScope
        KeySession.getFor(notebookId)?.let { return it }
        if (info == null || !info.encrypted) return null
        val key = if (info.keyScope == com.notesprout.android.crypto.KeyScope.GLOBAL) {
            com.notesprout.android.crypto.PassphraseStore.getGlobalPassphrase(this)
        } else {
            KeyResolver.resolveForOpen(this, notebookId, info)
        }
        resolvedKey = key
        return key
    }

    private fun loadPagesAsync() {
        val path = notebookSoilPath ?: return
        lifecycleScope.launch {
            resolveNotebookKey()
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

    /** Key for THIS notebook: live session first, then the key resolved by [resolveNotebookKey]. */
    private fun notebookKey(): String? = KeySession.getFor(notebookId) ?: resolvedKey

    private fun loadPagesFromSoil(path: String, passphrase: String? = notebookKey()): List<PageEntry> {
        // Reloads do NOT invalidate [thumbnailCache]: entries are keyed by page id, and no reload
        // source (move / delete / paste / cross-notebook op) changes a surviving page's content.
        // The one in-screen content change — set template — invalidates its ids directly.
        // Raw-key fast path only for THIS notebook (dest-notebook loads pass their own passphrase).
        val rawKey = if (passphrase != null && path == notebookSoilPath) {
            com.notesprout.android.crypto.KeyOpener.cachedRawKey(this, notebookId, keyScope)
        } else null
        return loadPageRefs(path, passphrase, rawKey).map { PageEntry(it.id, it.number, it.headingName) }
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
        thumbnailTargets.clear()

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

        // Render thumbnails on demand for just the visible pagination page.
        renderVisibleThumbnails(pageItems.toList(), spec)
    }

    /** [thumbnailCache] entry cap: the visible grid page + both prefetched neighbours. */
    private fun thumbCacheCap(spec: GridSpec): Int = (spec.itemsPerPage * 3).coerceAtLeast(12)

    /** Insert into the LRU and trim past [thumbCacheCap]. Main thread only. */
    private fun cacheThumbnail(id: String, bitmap: android.graphics.Bitmap, spec: GridSpec) {
        thumbnailCache[id] = bitmap
        val cap = thumbCacheCap(spec)
        val it = thumbnailCache.keys.iterator()
        while (thumbnailCache.size > cap && it.hasNext()) { it.next(); it.remove() }
    }

    /**
     * The shared thumbnail DAO — one Room connection for the whole visit, built on first use.
     * Opened via [KeyOpener.roomFactoryFor]: with the derived key cached (the normal case) that is
     * a raw-key open (~35 ms) instead of a fresh KDF (~300–700 ms), and either way the cost is paid
     * once per visit rather than once per render pass.
     */
    private suspend fun thumbDao(path: String, key: String?): NotebookDao =
        thumbDbMutex.withLock {
            thumbDb?.let { return it.notebookDao() }
            val builder = SoilDatabase.builder(this, path)
            if (key != null) {
                builder.openHelperFactory(
                    com.notesprout.android.crypto.KeyOpener.roomFactoryFor(
                        this, notebookId, java.io.File(path), keyScope, key,
                    )
                )
            }
            builder.build().also { thumbDb = it }.notebookDao()
        }

    /**
     * Fill each currently-visible card with a page-preview thumbnail. On-demand replacement for the
     * removed per-page snapshot, with a [thumbnailCache] so state-only re-renders (selection, action
     * mode, destination highlight, before/after toggle) and revisited grid pages reuse
     * already-rendered bitmaps. Cache hits are set immediately; misses render via
     * [NotebookExporter.renderPageThumbnail] on the shared [thumbDao] connection, visible cards
     * first, then the adjacent grid pages are prefetched into the cache so pagination lands warm.
     * The cache is cleared only on a card-size change; entry lifetime is the LRU cap (see the
     * field docs for why entries stay valid across reloads).
     */
    private fun renderVisibleThumbnails(items: List<PageEntry>, spec: GridSpec) {
        val path = notebookSoilPath ?: return
        if (items.isEmpty()) return

        // A card-size change (first layout, form-factor change) makes every cached bitmap the
        // wrong size — drop them all. Dropped, not recycled: GC reclaims pixel data safely even
        // if a bitmap is still set on a detached ImageView.
        if (thumbCardW != spec.cardWidthPx || thumbCardH != spec.cardHeightPx) {
            thumbnailCache.clear()
            templateCache.clear()
            thumbCardW = spec.cardWidthPx
            thumbCardH = spec.cardHeightPx
        }

        // Cache hits: set immediately, no render work. Collect misses for the async pass.
        val misses = mutableListOf<PageEntry>()
        for (entry in items) {
            val cached = thumbnailCache[entry.id]   // get() marks LRU recency
            if (cached != null) {
                thumbnailTargets[entry.id]?.apply {
                    setImageBitmap(cached)
                    visibility = android.view.View.VISIBLE
                }
            } else {
                misses += entry
            }
        }

        // Prefetch the next / previous grid pages after the visible misses so a pagination step
        // usually finds its bitmaps already cached. Their cards aren't in [thumbnailTargets], so
        // completion just fills the cache. (In cross-notebook dest mode these — like the visible
        // cards — resolve against the source connection and come up empty; unchanged behaviour.)
        val perPage = spec.itemsPerPage
        val prefetch = mutableListOf<PageEntry>()
        if (perPage > 0) {
            val next = (currentGridPage + 1) * perPage
            prefetch += pages.drop(next).take(perPage)
            if (currentGridPage > 0) {
                prefetch += pages.drop((currentGridPage - 1) * perPage).take(perPage)
            }
            prefetch.retainAll { it.id !in thumbnailCache }
        }
        if (misses.isEmpty() && prefetch.isEmpty()) return

        val key = notebookKey()
        val work = misses + prefetch
        val job = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dao = thumbDao(path, key)
                    // Render in parallel across a small worker pool sharing the one Room connection
                    // (WAL allows concurrent reads). Each worker pulls the next index off a shared
                    // cursor — visible misses sit ahead of prefetch, so cards fill first — and posts
                    // its bitmap to Main as it finishes, so cards fill in progressively. Bounded to
                    // the cores actually available to keep CPU-bound stroke parse + rasterization
                    // from over-subscribing.
                    val cursor = java.util.concurrent.atomic.AtomicInteger(0)
                    val workers = minOf(
                        work.size,
                        Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                        4,
                    )
                    kotlinx.coroutines.coroutineScope {
                        repeat(workers) {
                            launch {
                                while (isActive) {
                                    val i = cursor.getAndIncrement()
                                    if (i >= work.size) break
                                    val entry = work[i]
                                    // Per-page guard: one bad page must not blank the rest of the grid.
                                    val bitmap = try {
                                        val pageRow = dao.getObjectById(entry.id) ?: continue
                                        NotebookExporter.renderPageThumbnail(
                                            dao, pageRow, this@PageIndexActivity,
                                            spec.cardWidthPx, spec.cardHeightPx, templateCache,
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Slog.d("PageIndex") { "thumbnail render failed for page ${entry.id}: ${e.message}" }
                                        null
                                    } ?: continue
                                    withContext(Dispatchers.Main) {
                                        cacheThumbnail(entry.id, bitmap, spec)
                                        thumbnailTargets[entry.id]?.apply {
                                            setImageBitmap(bitmap)
                                            visibility = android.view.View.VISIBLE
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d("PageIndex") { "thumbnail render pass failed: ${e.message}" }
                }
            }
        }
        snapshotDecodeJobs.add(job)
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
                            // Tapped a source card. If a destination is already pending, just clear
                            // the pending pick (back to "picking"); otherwise cancel to action mode.
                            if (pendingDestPageId != null) {
                                pendingDestPageId = null
                                refreshDestChrome()
                                renderGridPage()
                            } else {
                                cancelDestMode()
                            }
                        } else {
                            // Select + preview this destination (do not commit until OK).
                            pendingDestPageId = entry.id
                            refreshDestChrome()
                            renderGridPage()
                        }
                    }
                    destMode == DestMode.PASTE -> {
                        // Any card is a valid paste destination — select + preview, don't commit.
                        pendingDestPageId = entry.id
                        refreshDestChrome()
                        renderGridPage()
                    }
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
        // Destination mode: highlight the source pages (selection / moveSourceIds) and the pending
        // destination card.
        // Action mode: highlight every selected card.
        // Normal mode: highlight only the currently-open page.
        val isPendingDest = destMode != DestMode.NONE && entry.id == pendingDestPageId
        val highlighted = when {
            destMode != DestMode.NONE -> isSelected || entry.id in moveSourceIds || isPendingDest
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

        // Thumbnail image — filled once the page is rendered on demand off the main thread
        // ([renderVisibleThumbnails]). Registered by entry id so the render pass can target it.
        val snapshotImage = AppCompatImageView(this).apply {
            scaleType  = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        card.addView(snapshotImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        thumbnailTargets[entry.id] = snapshotImage

        // ── Insertion-bar preview ──────────────────────────────────────────────
        // For the pending destination, draw a bold inkBlack bar on the leading edge (insertBefore)
        // or trailing edge (insert after) — the side conveys where the block will land. An arrowhead
        // with an inset white triangle points in the insertion direction for extra flair. Overlaid
        // inside the card FrameLayout so it doesn't disturb grid measurement.
        if (isPendingDest) {
            val markerWidthPx = (12 * resources.displayMetrics.density + 0.5f).toInt()
            val marker = InsertionMarkerView(this, insertBefore, inkBlackColor)
            card.addView(marker, FrameLayout.LayoutParams(
                markerWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (insertBefore) Gravity.START else Gravity.END,
            ))
        }

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
        pendingDestPageId = null
        binding.tvTopBarTitle.text = "Pages"
        binding.btnSelectAll.visibility    = android.view.View.GONE
        binding.btnCopyPage.visibility     = android.view.View.GONE
        binding.btnDeletePage.visibility   = android.view.View.GONE
        binding.btnMovePage.visibility     = android.view.View.GONE
        binding.btnSetTemplate.visibility  = android.view.View.GONE
        binding.btnExportPage.visibility   = android.view.View.GONE
        binding.btnInsertBefore.visibility = android.view.View.GONE
        binding.btnInsertAfter.visibility  = android.view.View.GONE
        binding.btnConfirmDest.visibility  = android.view.View.GONE
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
        pendingDestPageId = null   // no destination chosen yet

        if (mode == DestMode.MOVE) {
            moveSourceIds = selectedPageIds.toList()
        }

        binding.btnSelectAll.visibility  = android.view.View.GONE
        binding.btnCopyPage.visibility   = android.view.View.GONE
        binding.btnDeletePage.visibility = android.view.View.GONE
        binding.btnMovePage.visibility   = android.view.View.GONE
        binding.btnSetTemplate.visibility = android.view.View.GONE
        binding.btnExportPage.visibility = android.view.View.GONE
        binding.btnConfirmDest.visibility = android.view.View.GONE

        // Show the Before/After selectable text buttons; Before is selected by default.
        val verb = if (mode == DestMode.MOVE) "Move" else "Copy"
        binding.btnInsertBefore.text = "$verb Before"
        binding.btnInsertAfter.text  = "$verb After"
        binding.btnInsertBefore.visibility = android.view.View.VISIBLE
        binding.btnInsertAfter.visibility  = android.view.View.VISIBLE
        refreshInsertBeforeAfter()
        refreshDestChrome()
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
        // When a destination is already chosen, flipping before/after must update both the
        // confirming title and which edge the insertion bar is drawn on.
        if (pendingDestPageId != null) {
            refreshDestChrome()
            renderGridPage()
        }
    }

    /**
     * Reflect the pending-destination sub-state in the chrome: show the Confirm button only
     * once a destination is chosen, and update the title from "pick a spot" to a confirming prompt.
     * Called from [enterDestMode], destination taps, and before/after flips.
     */
    private fun refreshDestChrome() {
        if (destMode == DestMode.NONE && crossInfo == null) return
        val target = pendingDestPageId
        binding.btnConfirmDest.visibility =
            if (target != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnConfirmDest.isSelected = target != null

        val cross = crossInfo
        val verb = when {
            cross != null            -> if (cross.isCopy) "Copy" else "Move"
            destMode == DestMode.MOVE -> "Move"
            else                     -> "Paste"
        }

        if (target == null) {
            val titlePrefix = if (cross != null) "$verb to ${cross.destName}" else verb
            binding.tvTopBarTitle.text = "$titlePrefix — before/after…"
        } else {
            val side    = if (insertBefore) "before" else "after"
            val pageNum = pages.indexOfFirst { it.id == target }.let { if (it >= 0) it + 1 else null }
            binding.tvTopBarTitle.text =
                if (pageNum != null) "$verb $side p.$pageNum?" else "$verb $side…"
        }
    }

    /**
     * Commit the chosen destination. The destination tap selects + previews only; this runs the
     * actual batch op. [executeMove] / [executePaste] / [executeCrossNotebookOp] handle teardown.
     */
    private fun confirmDestination() {
        val target = pendingDestPageId ?: return
        when {
            crossInfo != null      -> executeCrossNotebookOp(target)
            destMode == DestMode.MOVE  -> executeMove(target)
            destMode == DestMode.PASTE -> executePaste(target)
            else -> {}
        }
    }

    /** Cancel destination-picking mode: restore action mode chrome and selection state. */
    private fun cancelDestMode() {
        // If cancelling a cross-notebook op, restore source pages.
        if (crossInfo != null) {
            crossInfo = null
            pages = savedSourcePages
        }
        destMode = DestMode.NONE
        moveSourceIds = emptyList()
        pendingDestPageId = null
        // Hide the Before/After + Confirm buttons before refreshActionMode re-shows action buttons.
        binding.btnInsertBefore.visibility = android.view.View.GONE
        binding.btnInsertAfter.visibility  = android.view.View.GONE
        binding.btnConfirmDest.visibility  = android.view.View.GONE
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
                com.notesprout.android.data.movePagesRelativeRaw(sources, targetPageId, insertBefore, path, notebookKey())
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
                com.notesprout.android.data.copyPagesRelativeRaw(sources, targetPageId, insertBefore, path, notebookKey())
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
     * Shows a scope chooser (This Notebook / Other Notebook) for Copy or Move.
     * "This Notebook" follows the existing same-file dest-pick flow; "Other Notebook" launches
     * the [NotebookPickerActivity] then wires the cross-notebook engine.
     */
    private fun showScopeChooser(isCopy: Boolean) {
        if (selectedPageIds.isEmpty()) return
        val verb = if (isCopy) "Copy" else "Move"
        ActionSheetDialog(this)
            .title("$verb pages")
            .addAction(null, "This Notebook") {
                if (isCopy) {
                    pendingCopyPageIds = selectedPageIds.toList()
                    enterDestMode(DestMode.PASTE)
                } else {
                    enterDestMode(DestMode.MOVE)
                }
            }
            .addAction(null, "Other Notebook") {
                crossPendingSourceIds = selectedPageIds.toList()
                pendingCrossIsCopy = isCopy
                val intent = android.content.Intent(this, NotebookPickerActivity::class.java).apply {
                    putExtra(NotebookPickerActivity.EXTRA_EXCLUDE_NOTEBOOK_ID, notebookId)
                }
                notebookPickerLauncher.launch(intent)
            }
            .show()
    }

    /**
     * After a destination notebook is picked, resolve its key, load its pages, and enter the
     * cross-notebook destination-picking mode (same Before/After/Confirm chrome as single-file ops).
     */
    private fun enterCrossNotebookDestMode(destId: String, destName: String, isCopy: Boolean) {
        val sourceIds = crossPendingSourceIds
        if (sourceIds.isEmpty()) { refreshActionMode(); renderGridPage(); return }
        val sourcePath = notebookSoilPath ?: return
        val sourcePass = notebookKey()
        val destPath   = soilFile(this, destId).absolutePath

        lifecycleScope.launch {
            val destInfo = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(destId) }
            val destPass: String? = if (destInfo.encrypted) {
                KeySession.getFor(destId)
                    ?: KeyResolver.resolveForOpen(this@PageIndexActivity, destId, destInfo)
            } else null
            if (destInfo.encrypted && destPass == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Notebook is locked", android.widget.Toast.LENGTH_SHORT).show()
                refreshActionMode(); renderGridPage()
                return@launch
            }

            val destPages = withContext(Dispatchers.IO) { loadPagesFromSoil(destPath, destPass) }

            // Snapshot source pages and swap in dest pages for the card-pick UI.
            savedSourcePages = pages
            crossInfo = CrossNotebookInfo(
                sourcePageIds    = sourceIds,
                sourcePath       = sourcePath,
                sourceNotebookId = notebookId,
                sourcePass       = sourcePass,
                destNotebookId   = destId,
                destName         = destName,
                destPath         = destPath,
                destPass         = destPass,
                isCopy           = isCopy,
            )
            pages = destPages

            // Enter dest-picking mode (reuse PASTE card-click UI; crossInfo drives the engine).
            destMode = DestMode.PASTE
            insertBefore = true
            pendingDestPageId = null

            binding.btnSelectAll.visibility   = android.view.View.GONE
            binding.btnCopyPage.visibility    = android.view.View.GONE
            binding.btnDeletePage.visibility  = android.view.View.GONE
            binding.btnMovePage.visibility    = android.view.View.GONE
            binding.btnSetTemplate.visibility = android.view.View.GONE
            binding.btnExportPage.visibility  = android.view.View.GONE
            binding.btnConfirmDest.visibility = android.view.View.GONE

            val verbLabel = if (isCopy) "Copy" else "Move"
            binding.btnInsertBefore.text = "$verbLabel Before"
            binding.btnInsertAfter.text  = "$verbLabel After"
            binding.btnInsertBefore.visibility = android.view.View.VISIBLE
            binding.btnInsertAfter.visibility  = android.view.View.VISIBLE
            refreshInsertBeforeAfter()
            refreshDestChrome()
            renderGridPage()
        }
    }

    /**
     * Run the cross-notebook copy or move engine after the user confirms a destination page.
     * Applies the smart encryption gate before writing, restores the source page view on completion,
     * and records source-removed page data for source-side undo (move only).
     */
    private fun executeCrossNotebookOp(targetPageId: String) {
        val cross = crossInfo ?: return

        lifecycleScope.launch {
            val srcInfo = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(cross.sourceNotebookId) }
            val dstInfo = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(cross.destNotebookId) }

            val protectionDrops = srcInfo.encrypted &&
                (!dstInfo.encrypted || cross.destPass != cross.sourcePass)

            if (protectionDrops) {
                val verb = if (cross.isCopy) "Copying" else "Moving"
                val confirmed = suspendCancellableCoroutine<Boolean> { cont ->
                    val d = androidx.appcompat.app.AlertDialog.Builder(this@PageIndexActivity)
                        .setMessage("This notebook is encrypted. $verb these pages to ${cross.destName} will store their contents outside this notebook's encryption. Continue?")
                        .setPositiveButton("Continue") { _, _ -> if (cont.isActive) cont.resume(true) }
                        .setNegativeButton("Cancel")   { _, _ -> if (cont.isActive) cont.resume(false) }
                        .setOnCancelListener           { if (cont.isActive) cont.resume(false) }
                        .create()
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                    cont.invokeOnCancellation { d.dismiss() }
                }
                if (!confirmed) return@launch
            }

            val n = cross.sourcePageIds.size
            val nLabel = if (n == 1) "page" else "pages"

            if (cross.isCopy) {
                val results = withContext(Dispatchers.IO) {
                    com.notesprout.android.data.copyPagesAcrossNotebooks(
                        cross.sourcePageIds, cross.sourcePath, cross.sourcePass,
                        targetPageId, insertBefore, cross.destPath, cross.destPass,
                    )
                }
                if (results == null) {
                    android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't copy pages", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
            } else {
                val result = withContext(Dispatchers.IO) {
                    com.notesprout.android.data.movePagesAcrossNotebooks(
                        cross.sourcePageIds, cross.sourcePath, cross.sourcePass,
                        targetPageId, insertBefore, cross.destPath, cross.destPass,
                    )
                }
                if (result == null) {
                    android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't move pages", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                xnbRemovedPageIds  = result.sourceDeletedPageIds
                xnbRemovedDeletedAt = result.sourceDeletedAt
            }

            // Restore source page view (reload for move since pages were removed; reuse snapshot for copy).
            crossInfo = null
            pages = if (!cross.isCopy) {
                withContext(Dispatchers.IO) { loadPagesFromSoil(cross.sourcePath, cross.sourcePass) }
            } else {
                savedSourcePages
            }

            pruneSelection()
            val cid = currentPageId
            if (cid != null) {
                val newIdx = pages.indexOfFirst { it.id == cid }
                if (newIdx >= 0) currentPageIndex = newIdx
            }
            exitActionMode()

            // Navigation prompt — "Stay here" or "Open ‹DestName›".
            val verb = if (cross.isCopy) "copied" else "moved"
            val openChosen = suspendCancellableCoroutine<Boolean> { cont ->
                val d = androidx.appcompat.app.AlertDialog.Builder(this@PageIndexActivity)
                    .setMessage("$n $nLabel $verb to ${cross.destName}.")
                    .setPositiveButton("Stay here") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setNegativeButton("Open ${cross.destName}") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .create()
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                cont.invokeOnCancellation { d.dismiss() }
            }
            if (openChosen) {
                pendingOpenNotebookId   = cross.destNotebookId
                pendingOpenNotebookName = cross.destName
                finishWithResult(null)
            }
        }
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
                    val tObj = entity.templateObject() ?: return@withContext null
                    if (tObj.image.isEmpty()) return@withContext null
                    val key = notebookKey()
                    val parentId = com.notesprout.android.data.readNotebookRowId(path, key)
                        ?: MainActivity.NIL_UUID
                    com.notesprout.android.data.insertSoilTemplateRaw(
                        notebookPath = path,
                        parentId     = parentId,
                        width        = tObj.width,
                        height       = tObj.height,
                        name         = entity.name,
                        imageBase64  = tObj.image,
                        passphrase   = key,
                    )
                }
            }
            if (soilTemplateId == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't set template", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val pairs = withContext(Dispatchers.IO) {
                com.notesprout.android.data.setPagesTemplateRaw(targets, soilTemplateId, path, notebookKey())
            }
            if (pairs == null) {
                android.widget.Toast.makeText(this@PageIndexActivity, "Couldn't set template", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val newId = soilTemplateId.takeIf { it.isNotEmpty() }
            pairs.forEach { (pageId, prev) -> templateChanges.add(Triple(pageId, prev, newId)) }

            // The one in-screen operation that changes a page's rendered content — drop exactly
            // the affected thumbnails so the exitActionMode re-render redraws them.
            pairs.forEach { (pageId, _) -> thumbnailCache.remove(pageId) }

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
                            val deletedAt = com.notesprout.android.data.deletePageRaw(id, path, notebookKey())
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
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    /**
     * Entry point for the Export toolbar button: hand the current selection to [ExportActivity],
     * which owns format, options and destination for every export in the app.
     */
    private fun executeExport() {
        if (!inActionMode()) return
        // Sort by position in [pages] (which is ordered by `order`) rather than by selection order.
        val idSet = selectedPageIds.toSet()
        val entries = pages.filter { it.id in idSet }.takeIf { it.isNotEmpty() } ?: return
        startActivity(
            ExportActivity.intentFor(
                context = this,
                notebookId = notebookId,
                notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook",
                selectedPageIds = entries.map { it.id },
            )
        )
        exitActionMode()
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
        if (xnbRemovedPageIds.isNotEmpty()) {
            result.putExtra(EXTRA_XNB_REMOVED_PAGE_IDS,   xnbRemovedPageIds.joinToString(","))
            result.putExtra(EXTRA_XNB_REMOVED_DELETED_AT, xnbRemovedDeletedAt.toString())
        }
        val openId = pendingOpenNotebookId
        val openName = pendingOpenNotebookName
        if (openId != null && openName != null) {
            result.putExtra(EXTRA_OPEN_NOTEBOOK_ID,   openId)
            result.putExtra(EXTRA_OPEN_NOTEBOOK_NAME, openName)
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

/**
 * The insertion-bar preview drawn on the pending destination card. A full-height inkBlack bar sits
 * on the card's leading ([before]) or trailing edge, with a centred arrowhead (apex on the bar's
 * outer edge) and an inset white triangle — both pointing in the insertion direction. Drawn in code
 * for crisp, color-free e-ink rendering. Sized 12dp wide, overlaid inside the card FrameLayout.
 */
private class InsertionMarkerView(
    context: android.content.Context,
    private val before: Boolean,
    inkBlack: Int,
) : android.view.View(context) {

    private val density = resources.displayMetrics.density
    private val inkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = inkBlack
        style = android.graphics.Paint.Style.FILL
    }
    private val whitePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    private val inkPath = android.graphics.Path()
    private val whitePath = android.graphics.Path()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = 3f * density
        val triW = 9f * density          // how far the arrowhead extends from the edge
        val triHalf = 9f * density       // half the arrowhead height
        val cy = h / 2f

        // Vertical bar on the appropriate edge.
        if (before) canvas.drawRect(0f, 0f, barW, h, inkPaint)
        else        canvas.drawRect(w - barW, 0f, w, h, inkPaint)

        // Black arrowhead: apex on the outer edge, base inside the card — points in the insert dir.
        inkPath.reset()
        if (before) {
            inkPath.moveTo(0f, cy)
            inkPath.lineTo(triW, cy - triHalf)
            inkPath.lineTo(triW, cy + triHalf)
        } else {
            inkPath.moveTo(w, cy)
            inkPath.lineTo(w - triW, cy - triHalf)
            inkPath.lineTo(w - triW, cy + triHalf)
        }
        inkPath.close()
        canvas.drawPath(inkPath, inkPaint)

        // Inset white triangle for flair, same direction, sitting inside the arrowhead.
        val inset = 2.5f * density
        val wHalf = triHalf - inset * 1.4f
        whitePath.reset()
        if (before) {
            whitePath.moveTo(inset, cy)
            whitePath.lineTo(triW - inset, cy - wHalf)
            whitePath.lineTo(triW - inset, cy + wHalf)
        } else {
            whitePath.moveTo(w - inset, cy)
            whitePath.lineTo(w - (triW - inset), cy - wHalf)
            whitePath.lineTo(w - (triW - inset), cy + wHalf)
        }
        whitePath.close()
        canvas.drawPath(whitePath, whitePaint)
    }
}
