package com.notesprout.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
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
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.insertBlankPageRaw
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.soilFile
import com.notesprout.android.data.topHeadingNamesByPageId
import com.notesprout.android.databinding.ActivityLinkTargetPickerBinding
import com.notesprout.android.search.SearchDialog
import com.notesprout.android.search.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Full-screen link-target picker.
 *
 * **This notebook** path (Session 2): a [PageIndexActivity]-style thumbnail grid where a tap on a
 * page card returns `(chrome, current page id)`.
 *
 * **Other notebook** path (Session 3): a folder/notebook browser (breadcrumb + back, mirroring
 * MainActivity) with a Notebook/Page sub-toggle and notebook-name search. In **Notebook** mode a
 * notebook tap returns `(chrome, OtherNotebook)`; in **Page** mode a notebook tap opens that
 * notebook's page list (read from its `.soil` via [soilFile]) and a page tap returns
 * `(chrome, OtherNotebookPage)`.
 *
 * Used both to create a link (no initial target) and to edit one — [EXTRA_INITIAL_CHROME] plus the
 * `EXTRA_INITIAL_*` target extras pre-select the chrome and pre-navigate to the link's target.
 */
class LinkTargetPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID        = "notebook_id"
        const val EXTRA_NOTEBOOK_NAME      = "notebook_name"
        const val EXTRA_CURRENT_PAGE_INDEX = "current_page_index"

        // Target-kind discriminator shared by the initial (edit) and result contracts.
        const val TARGET_CURRENT_PAGE        = "current_page"
        const val TARGET_OTHER_NOTEBOOK      = "other_notebook"
        const val TARGET_OTHER_NOTEBOOK_PAGE = "other_notebook_page"

        /** Pre-selected chrome (a [LinkChrome] name) when editing; defaults to NONE. */
        const val EXTRA_INITIAL_CHROME            = "initial_chrome"
        /** Which target kind is being edited (one of the TARGET_* consts); absent ⇒ creating. */
        const val EXTRA_INITIAL_TARGET_KIND       = "initial_target_kind"
        /** Pre-selected current-notebook page id (current-page edit). */
        const val EXTRA_INITIAL_PAGE_ID           = "initial_page_id"
        /** Pre-selected other-notebook id (other-notebook / other-notebook-page edit). */
        const val EXTRA_INITIAL_NOTEBOOK_ID       = "initial_notebook_id"
        /** Pre-selected other-notebook page id (other-notebook-page edit). */
        const val EXTRA_INITIAL_NOTEBOOK_PAGE_ID  = "initial_notebook_page_id"

        /** Result: chosen chrome (a [LinkChrome] name). */
        const val EXTRA_RESULT_CHROME       = "result_chrome"
        /** Result: chosen target kind (one of the TARGET_* consts). */
        const val EXTRA_RESULT_TARGET_KIND  = "result_target_kind"
        /** Result: page id — current-notebook page, or the page within the target notebook. */
        const val EXTRA_RESULT_PAGE_ID      = "result_page_id"
        /** Result: target notebook id (other-notebook / other-notebook-page). */
        const val EXTRA_RESULT_NOTEBOOK_ID  = "result_notebook_id"
    }

    // ── View state ──────────────────────────────────────────────────────────────

    private enum class TargetTab { CURRENT, OTHER }
    private enum class OtherKind { NOTEBOOK, PAGE }
    /** Within the Other tab: browsing folders/notebooks, or viewing one notebook's pages. */
    private enum class OtherView { BROWSE, PAGES }

    // ── Grid specification (mirrors PageIndexActivity) ─────────────────────────

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

    private data class PageEntry(val id: String, val pageNumber: Int, val snapshot: String?, val headingName: String? = null)

    /** A folder or notebook row in the Other-notebook browser. */
    private sealed class BrowseItem {
        abstract val entity: ObjectEntity
        data class Folder(override val entity: ObjectEntity) : BrowseItem()
        data class Notebook(override val entity: ObjectEntity, val label: String) : BrowseItem()
    }

    @Serializable
    private data class PageSnapshot(val snapshot: String? = null)

    private val pageCodec = Json { ignoreUnknownKeys = true }

    // ── State ─────────────────────────────────────────────────────────────────

    private var notebookId: String = ""
    private var notebookSoilPath: String? = null

    private lateinit var binding: ActivityLinkTargetPickerBinding

    private var targetTab = TargetTab.CURRENT
    private var otherKind = OtherKind.NOTEBOOK
    private var otherView = OtherView.BROWSE

    // Current-notebook pages.
    private var pages: List<PageEntry> = emptyList()
    private var currentPageIndex: Int = 0

    // Other-notebook browser (folders + notebooks). null root entry in the stack = top level.
    private val directoryStack = ArrayDeque<ObjectEntity?>().apply { add(null) }
    private var browseItems: List<BrowseItem> = emptyList()
    private var browseLoaded = false
    private var searchQuery: String = ""

    // Selected other notebook whose pages are showing (OtherView.PAGES).
    private var otherNotebookId: String? = null
    private var otherNotebookName: String = ""
    private var otherPages: List<PageEntry> = emptyList()

    // The currently visible grid page index (shared across all grids).
    private var currentGridPage: Int = 0
    private var gridSpec: GridSpec? = null

    private var selectedChrome: LinkChrome = LinkChrome.NONE

    // Edit pre-selection (the link's current target).
    private var initialTargetKind: String? = null
    private var initialCurrentPageId: String? = null
    private var initialNotebookId: String? = null
    private var initialNotebookPageId: String? = null

    // Pending selection — updated on each tap; committed when OK is pressed.
    private var pendingTargetKind: String? = null
    private var pendingPageId: String? = null
    private var pendingNotebookId: String? = null
    private var pendingNotebookPageId: String? = null

    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    private val decodeJobs = mutableListOf<Job>()

    private var currentNotebookPagesChanged = false

    // ── Swipe gesture (left/right to paginate) ────────────────────────────────

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
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

        binding = ActivityLinkTargetPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notebookId       = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: ""
        notebookSoilPath = if (notebookId.isNotEmpty()) soilFile(this, notebookId).absolutePath else null
        currentPageIndex = intent.getIntExtra(EXTRA_CURRENT_PAGE_INDEX, 0)
        selectedChrome   = intent.getStringExtra(EXTRA_INITIAL_CHROME)
            ?.let { runCatching { LinkChrome.valueOf(it) }.getOrNull() } ?: LinkChrome.NONE

        initialTargetKind     = intent.getStringExtra(EXTRA_INITIAL_TARGET_KIND)
        initialCurrentPageId  = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID)
        initialNotebookId     = intent.getStringExtra(EXTRA_INITIAL_NOTEBOOK_ID)
        initialNotebookPageId = intent.getStringExtra(EXTRA_INITIAL_NOTEBOOK_PAGE_ID)

        // Pre-populate the pending selection from the initial (editing) target.
        pendingTargetKind     = initialTargetKind
        pendingPageId         = initialCurrentPageId
        pendingNotebookId     = initialNotebookId
        pendingNotebookPageId = initialNotebookPageId

        binding.btnBack.setOnClickListener { finish() }

        binding.btnConfirm.visibility = View.VISIBLE
        binding.btnConfirm.setOnClickListener { confirmPendingTarget() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleInPickerBack()) finish()
            }
        })

        binding.btnChromeNone.setOnClickListener      { selectedChrome = LinkChrome.NONE; refreshChromeButtons() }
        binding.btnChromeUnderline.setOnClickListener { selectedChrome = LinkChrome.UNDERLINE; refreshChromeButtons() }
        binding.btnChromeBox.setOnClickListener       { selectedChrome = LinkChrome.DOTTED_CHEVRON; refreshChromeButtons() }
        refreshChromeButtons()

        binding.btnTargetCurrent.setOnClickListener { switchTab(TargetTab.CURRENT) }
        binding.btnTargetOther.setOnClickListener   { switchTab(TargetTab.OTHER) }

        binding.btnKindNotebook.setOnClickListener { switchKind(OtherKind.NOTEBOOK) }
        binding.btnKindPage.setOnClickListener     { switchKind(OtherKind.PAGE) }

        binding.btnBrowseBack.setOnClickListener  { handleInPickerBack() }
        binding.btnBrowseSearch.setOnClickListener { showSearchDialog() }
        binding.btnBrowseClearSearch.setOnClickListener { clearSearch() }

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
                    render()
                }
            }
        )

        binding.btnNewPage.setOnClickListener { onNewPageTapped() }

        loadCurrentPagesAsync()
        applyInitialTarget()
    }

    /** Pre-navigate the picker to the link's current target when editing. */
    private fun applyInitialTarget() {
        when (initialTargetKind) {
            TARGET_OTHER_NOTEBOOK -> {
                targetTab = TargetTab.OTHER
                otherKind = OtherKind.NOTEBOOK
                otherView = OtherView.BROWSE
                preNavigateToNotebookFolder(initialNotebookId)
            }
            TARGET_OTHER_NOTEBOOK_PAGE -> {
                targetTab = TargetTab.OTHER
                otherKind = OtherKind.PAGE
                val nbId = initialNotebookId
                if (nbId != null) {
                    preNavigateToNotebookFolder(nbId)
                    openOtherNotebookPages(nbId)
                }
            }
            // TARGET_CURRENT_PAGE / null → stay on the current-notebook page grid.
        }
    }

    /** Walk the index parent chain so [directoryStack] lands in the folder containing [notebookId]. */
    private fun preNavigateToNotebookFolder(notebookId: String?) {
        if (notebookId == null) return
        lifecycleScope.launch {
            val stack = withContext(Dispatchers.IO) {
                val nb = indexRepo.getNotebook(notebookId) ?: return@withContext null
                val path = ArrayDeque<ObjectEntity?>().apply { add(null) }
                val chain = mutableListOf<ObjectEntity>()
                var pid = nb.parentId
                while (pid != null) {
                    val folder = indexRepo.getFolder(pid) ?: break
                    chain.add(0, folder)
                    pid = folder.parentId
                }
                chain.forEach { path.add(it) }
                path
            } ?: return@launch
            directoryStack.clear()
            directoryStack.addAll(stack)
            loadBrowseAsync()
        }
    }

    private fun refreshChromeButtons() {
        binding.btnChromeNone.isSelected      = selectedChrome == LinkChrome.NONE
        binding.btnChromeUnderline.isSelected = selectedChrome == LinkChrome.UNDERLINE
        binding.btnChromeBox.isSelected       = selectedChrome == LinkChrome.DOTTED_CHEVRON
    }

    // ── Tab / kind / navigation transitions ────────────────────────────────────

    private fun switchTab(tab: TargetTab) {
        if (targetTab == tab) return
        targetTab = tab
        currentGridPage = 0
        if (tab == TargetTab.OTHER) {
            otherView = OtherView.BROWSE
            if (!browseLoaded) loadBrowseAsync() else render()
        } else {
            render()
        }
    }

    private fun switchKind(kind: OtherKind) {
        if (otherKind == kind && otherView == OtherView.BROWSE) return
        otherKind = kind
        // Changing the kind always returns to the folder/notebook browser.
        otherView = OtherView.BROWSE
        currentGridPage = 0
        render()
    }

    /** Back within the picker: pages→browse, then up the folder stack. Returns false at the top. */
    private fun handleInPickerBack(): Boolean {
        if (targetTab == TargetTab.OTHER && otherView == OtherView.PAGES) {
            otherView = OtherView.BROWSE
            otherNotebookId = null
            currentGridPage = 0
            render()
            return true
        }
        if (targetTab == TargetTab.OTHER && otherView == OtherView.BROWSE) {
            if (searchQuery.isNotEmpty()) { clearSearch(); return true }
            if (directoryStack.size > 1) {
                directoryStack.removeLast()
                currentGridPage = 0
                loadBrowseAsync()
                return true
            }
        }
        return false
    }

    private fun navigateIntoFolder(entity: ObjectEntity) {
        directoryStack.add(entity)
        currentGridPage = 0
        loadBrowseAsync()
    }

    private fun openOtherNotebookPages(notebookId: String) {
        val entity = browseItems.firstOrNull { it.entity.id == notebookId }?.entity
        openOtherNotebookPages(notebookId, entity?.name ?: "")
    }

    private fun openOtherNotebookPages(notebookId: String, name: String) {
        otherNotebookId = notebookId
        otherNotebookName = name
        otherView = OtherView.PAGES
        currentGridPage = 0
        loadOtherPagesAsync(notebookId)
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    private fun showSearchDialog() {
        SearchDialog.show(
            context = this,
            initialQuery = searchQuery,
            onSearch = { query ->
                searchQuery = query.trim()
                currentGridPage = 0
                loadBrowseAsync()
            },
            onCancel = { },
        )
    }

    private fun clearSearch() {
        if (searchQuery.isEmpty()) return
        searchQuery = ""
        currentGridPage = 0
        loadBrowseAsync()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadCurrentPagesAsync() {
        val path = notebookSoilPath ?: return
        lifecycleScope.launch {
            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path, KeySession.getFor(notebookId)) }
            if (targetTab == TargetTab.CURRENT) {
                val spec = gridSpec
                if (spec != null && spec.itemsPerPage > 0) {
                    val anchorIdx = initialCurrentPageId?.let { id -> pages.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 } ?: currentPageIndex
                    currentGridPage = anchorIdx / spec.itemsPerPage
                }
                render()
            }
        }
    }

    private fun loadBrowseAsync() {
        lifecycleScope.launch {
            val query = searchQuery
            browseItems = withContext(Dispatchers.IO) {
                if (query.isNotEmpty()) {
                    SearchEngine.search(query, indexRepo).map { result ->
                        val parent = result.folderLabel.substringAfterLast(" › ")
                        val label = if (result.folderLabel == "Notebooks") result.displayName
                                    else "$parent › ${result.displayName}"
                        BrowseItem.Notebook(result.entity, label)
                    }
                } else {
                    val parentId = directoryStack.last()?.id
                    val folders   = indexRepo.getFolders(parentId).sortedBy { it.name.lowercase() }
                    val notebooks = indexRepo.getNotebooks(parentId).sortedBy { it.name.lowercase() }
                    folders.map { BrowseItem.Folder(it) } +
                        notebooks.map { BrowseItem.Notebook(it, it.name) }
                }
            }
            browseLoaded = true
            if (targetTab == TargetTab.OTHER && otherView == OtherView.BROWSE) render()
        }
    }

    private fun loadOtherPagesAsync(forNotebookId: String) {
        val path = soilFile(this, forNotebookId).absolutePath
        lifecycleScope.launch {
            // Resolve the key before going to IO (dialog must run on the main thread).
            val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(forNotebookId) }
            val key = if (info.encrypted) {
                KeySession.getFor(forNotebookId)
                    ?: KeyResolver.resolveForOpen(this@LinkTargetPickerActivity, forNotebookId, info)
            } else null
            if (info.encrypted && key == null) {
                // User cancelled — stay on the browse list.
                otherView = OtherView.BROWSE
                android.widget.Toast.makeText(this@LinkTargetPickerActivity, "Notebook is locked", android.widget.Toast.LENGTH_SHORT).show()
                render()
                return@launch
            }
            val loaded = withContext(Dispatchers.IO) { loadPagesFromSoil(path, key) }
            if (otherNotebookId != forNotebookId) return@launch
            otherPages = loaded
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0 && initialNotebookId == forNotebookId) {
                val anchorIdx = initialNotebookPageId?.let { id -> otherPages.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 } ?: 0
                currentGridPage = anchorIdx / spec.itemsPerPage
            }
            render()
        }
    }

    private fun loadPagesFromSoil(path: String, passphrase: String? = null): List<PageEntry> {
        var db: com.notesprout.android.crypto.SoilRawDb? = null
        return try {
            db = SoilCrypto.openRaw(java.io.File(path), passphrase)
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

        return GridSpec(cols, rows, cardWidth, cardHeight, gutterPx, rowGapPx, labelHeightPx, paddingHPx, paddingVPx)
    }

    // ── Active-grid plumbing ────────────────────────────────────────────────────

    private fun activeSize(): Int = when {
        targetTab == TargetTab.CURRENT     -> pages.size
        otherView == OtherView.PAGES       -> otherPages.size
        else                               -> browseItems.size
    }

    private fun totalGridPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || activeSize() == 0) return 1
        return (activeSize() + perPage - 1) / perPage
    }

    /** Single render entry point: chrome + header chrome state + active grid + pagination. */
    private fun render() {
        if (gridSpec == null) return
        updateHeaderState()

        decodeJobs.forEach { it.cancel() }
        decodeJobs.clear()
        binding.gridContainer.removeAllViews()

        when {
            targetTab == TargetTab.CURRENT ->
                renderPageGrid(pages, highlightId = currentPageHighlightId()) { selectCurrentPage(it.id) }
            otherView == OtherView.PAGES ->
                renderPageGrid(otherPages, highlightId = otherPageHighlightId()) {
                    selectOtherNotebookPage(otherNotebookId ?: return@renderPageGrid, it.id)
                }
            else -> renderBrowseGrid()
        }

        updatePaginationControls()
        updateConfirmButton()
    }

    private fun currentPageHighlightId(): String? =
        if (pendingTargetKind == TARGET_CURRENT_PAGE) pendingPageId else null

    private fun otherPageHighlightId(): String? =
        if (pendingTargetKind == TARGET_OTHER_NOTEBOOK_PAGE && pendingNotebookId == otherNotebookId)
            pendingNotebookPageId else null

    private fun updateHeaderState() {
        // Session 1: CURRENT only. Session 3 extends this condition.
        binding.btnNewPage.visibility = if (targetTab == TargetTab.CURRENT) View.VISIBLE else View.GONE

        binding.btnTargetCurrent.isSelected = targetTab == TargetTab.CURRENT
        binding.btnTargetOther.isSelected   = targetTab == TargetTab.OTHER

        val inOther = targetTab == TargetTab.OTHER
        binding.otherKindRow.visibility = if (inOther) View.VISIBLE else View.GONE
        binding.btnKindNotebook.isSelected = otherKind == OtherKind.NOTEBOOK
        binding.btnKindPage.isSelected     = otherKind == OtherKind.PAGE

        val showBrowseBar = inOther && (otherView == OtherView.BROWSE || otherView == OtherView.PAGES)
        binding.otherBrowseBar.visibility = if (showBrowseBar) View.VISIBLE else View.GONE
        if (showBrowseBar) {
            val inBrowse = otherView == OtherView.BROWSE
            binding.btnBrowseClearSearch.visibility = if (inBrowse && searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnBrowseSearch.visibility = if (inBrowse) View.VISIBLE else View.GONE
            buildBreadcrumbs()
        }

        binding.tvTopBarTitle.text = when {
            inOther && otherView == OtherView.PAGES -> otherNotebookName.ifEmpty { "Link to…" }
            else -> "Link to…"
        }
    }

    // ── Breadcrumb bar ────────────────────────────────────────────────────────

    private fun buildBreadcrumbs() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        if (searchQuery.isNotEmpty()) {
            container.addView(AppCompatTextView(this).apply {
                text = "Search: \"$searchQuery\""
                setTextColor(inkBlackColor)
                textSize = 14f
                val density = resources.displayMetrics.density
                setPadding((8 * density).toInt(), 0, 0, 0)
            })
            binding.btnBrowseBack.isEnabled = false
            return
        }

        binding.btnBrowseBack.isEnabled = directoryStack.size > 1 || otherView == OtherView.PAGES

        val density = resources.displayMetrics.density
        val padH = (8 * density).toInt()
        val sepPad = (4 * density).toInt()

        directoryStack.forEachIndexed { index, entry ->
            if (index > 0) {
                container.addView(AppCompatTextView(this).apply {
                    text = "›"
                    setTextColor(inkLightColor)
                    textSize = 16f
                    setPadding(sepPad, 0, sepPad, 0)
                })
            }
            val label = if (index == 0) "Notebooks" else entry?.name ?: "Notebooks"
            container.addView(AppCompatTextView(this).apply {
                text = label
                setTextColor(inkBlackColor)
                textSize = 16f
                setPadding(padH, 0, padH, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    // Tapping a folder crumb while viewing pages returns to browse first.
                    if (otherView == OtherView.PAGES) {
                        otherView = OtherView.BROWSE
                        otherNotebookId = null
                        currentGridPage = 0
                    }
                    while (directoryStack.size > index + 1) directoryStack.removeLast()
                    currentGridPage = 0
                    loadBrowseAsync()
                }
            })
        }

        // When viewing a notebook's page list, append the notebook name as the final crumb.
        if (otherView == OtherView.PAGES && otherNotebookName.isNotEmpty()) {
            container.addView(AppCompatTextView(this).apply {
                text = "›"
                setTextColor(inkLightColor)
                textSize = 16f
                setPadding(sepPad, 0, sepPad, 0)
            })
            container.addView(AppCompatTextView(this).apply {
                text = otherNotebookName
                setTextColor(inkBlackColor)
                textSize = 16f
                setPadding(padH, 0, padH, 0)
            })
        }

        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ── Rendering: page grid (current or other notebook) ────────────────────────

    private fun renderPageGrid(list: List<PageEntry>, highlightId: String?, onTap: (PageEntry) -> Unit) {
        val spec = gridSpec ?: return
        if (list.isEmpty()) { renderEmptyState("No pages found."); return }

        val start     = currentGridPage * spec.itemsPerPage
        val end       = minOf(start + spec.itemsPerPage, list.size)
        val pageItems = list.subList(start, end)

        renderRows(pageItems.size, spec) { idx -> buildPageCard(pageItems[idx], spec, highlightId, onTap) }
    }

    private fun renderBrowseGrid() {
        val spec = gridSpec ?: return
        if (browseItems.isEmpty()) {
            renderEmptyState(if (searchQuery.isNotEmpty()) "No notebooks found." else "Empty.")
            return
        }
        val start = currentGridPage * spec.itemsPerPage
        val end   = minOf(start + spec.itemsPerPage, browseItems.size)
        val items = browseItems.subList(start, end)
        renderRows(items.size, spec) { idx -> buildBrowseCard(items[idx], spec) }
    }

    /** Lays out [count] cards in a centered grid, calling [buildCard] for each cell. */
    private fun renderRows(count: Int, spec: GridSpec, buildCard: (Int) -> View) {
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        val rowCount = (count + spec.cols - 1) / spec.cols
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
                if (itemIdx < count) {
                    row.addView(buildCard(itemIdx))
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
    }

    private fun renderEmptyState(message: String) {
        val tv = AppCompatTextView(this).apply {
            text = message
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
     * One page card: bordered snapshot card + "Page N" label. The card whose id matches
     * [highlightId] (the link's current target, when editing) is highlighted; a tap invokes [onTap].
     */
    private fun buildPageCard(
        entry: PageEntry, spec: GridSpec, highlightId: String?, onTap: (PageEntry) -> Unit,
    ): LinearLayout {
        val highlighted = entry.id == highlightId

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onTap(entry) }
        }

        val card = FrameLayout(this).apply {
            setBackgroundResource(
                if (highlighted) R.drawable.bg_page_card_current else R.drawable.shape_bordered
            )
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        val padPx   = if (highlighted) (3 * density + 0.5f).toInt() else pad1dp
        card.setPadding(padPx, padPx, padPx, padPx)

        val snapshotImage = AppCompatImageView(this).apply {
            scaleType  = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        card.addView(snapshotImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

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

        decodeSnapshotInto(entry.snapshot, snapshotImage, null)
        return group
    }

    /** One browse card: folder icon, or notebook cover (snapshot) with a fallback icon. */
    private fun buildBrowseCard(item: BrowseItem, spec: GridSpec): LinearLayout {
        val highlighted = item is BrowseItem.Notebook &&
            otherKind == OtherKind.NOTEBOOK &&
            pendingTargetKind == TARGET_OTHER_NOTEBOOK &&
            item.entity.id == pendingNotebookId

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setOnClickListener {
                when (item) {
                    is BrowseItem.Folder   -> navigateIntoFolder(item.entity)
                    is BrowseItem.Notebook ->
                        if (otherKind == OtherKind.NOTEBOOK) selectOtherNotebook(item.entity)
                        else openOtherNotebookPages(item.entity.id, item.entity.name)
                }
            }
        }

        val card = FrameLayout(this).apply {
            setBackgroundResource(
                if (highlighted) R.drawable.bg_page_card_current else R.drawable.shape_bordered
            )
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        val padPx   = if (highlighted) (3 * density + 0.5f).toInt() else pad1dp
        card.setPadding(padPx, padPx, padPx, padPx)

        val iconSize = (minOf(spec.cardWidthPx, spec.cardHeightPx) * 0.45f).toInt()
        when (item) {
            is BrowseItem.Folder -> {
                card.addView(AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_folder)
                }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            }
            is BrowseItem.Notebook -> {
                val cover = AppCompatImageView(this).apply {
                    scaleType  = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                card.addView(cover, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                val icon = AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_notebook)
                }
                card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

                val notebookObj = try {
                    Json.decodeFromString<NotebookObject>(item.entity.data)
                } catch (_: Exception) { null }

                if (notebookObj?.encrypted == true) {
                    icon.setImageResource(R.drawable.ic_lock_cover)
                } else {
                    decodeSnapshotInto(notebookObj?.snapshot, cover, icon)
                }
            }
        }

        val labelText = when (item) {
            is BrowseItem.Folder   -> item.entity.name
            is BrowseItem.Notebook -> item.label
        }
        val label = AppCompatTextView(this).apply {
            text      = labelText
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

    /** Decodes a base64 snapshot off-thread into [image]; hides [fallbackIcon] on success. */
    private fun decodeSnapshotInto(b64: String?, image: AppCompatImageView, fallbackIcon: View?) {
        if (b64.isNullOrEmpty()) return
        val job = lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                fallbackIcon?.visibility = View.GONE
            }
        }
        decodeJobs.add(job)
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigateGridPage(page: Int) {
        val clamped = page.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
        if (clamped == currentGridPage) return
        currentGridPage = clamped
        render()
    }

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

    // ── Selection (tap to select; OK to commit) ───────────────────────────────

    private fun selectCurrentPage(pageId: String) {
        pendingTargetKind     = TARGET_CURRENT_PAGE
        pendingPageId         = pageId
        pendingNotebookId     = null
        pendingNotebookPageId = null
        render()
    }

    private fun selectOtherNotebook(entity: ObjectEntity) {
        pendingTargetKind     = TARGET_OTHER_NOTEBOOK
        pendingNotebookId     = entity.id
        pendingPageId         = null
        pendingNotebookPageId = null
        render()
    }

    private fun selectOtherNotebookPage(notebookId: String, pageId: String) {
        pendingTargetKind     = TARGET_OTHER_NOTEBOOK_PAGE
        pendingNotebookId     = notebookId
        pendingNotebookPageId = pageId
        pendingPageId         = null
        render()
    }

    private fun updateConfirmButton() {
        binding.btnConfirm.isEnabled = pendingTargetKind != null
    }

    // ── In-picker page creation (Session 1: This Notebook) ────────────────────

    private fun onNewPageTapped() {
        // Session 1 handles CURRENT only; Session 3 adds the OTHER/PAGES branch.
        if (targetTab != TargetTab.CURRENT) return
        val selectedId = if (pendingTargetKind == TARGET_CURRENT_PAGE) pendingPageId else null
        if (selectedId != null) {
            ActionSheetDialog(this)
                .title("Insert Page")
                .addAction(R.drawable.ic_insert_page_before, "Insert Before") {
                    createCurrentPage(anchorPageId = selectedId, before = true)
                }
                .addAction(R.drawable.ic_insert_page_after, "Insert After") {
                    createCurrentPage(anchorPageId = selectedId, before = false)
                }
                .show()
        } else {
            createCurrentPage(anchorPageId = null, before = false)
        }
    }

    private fun createCurrentPage(anchorPageId: String?, before: Boolean) {
        val path = notebookSoilPath ?: return
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                insertBlankPageRaw(path, anchorPageId, before, w, h, KeySession.getFor(notebookId))
            } ?: run {
                android.widget.Toast.makeText(
                    this@LinkTargetPickerActivity, "Could not create page", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            currentNotebookPagesChanged = true
            pages = withContext(Dispatchers.IO) {
                loadPagesFromSoil(path, KeySession.getFor(notebookId))
            }
            selectCurrentPage(result.first)
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                currentGridPage = result.second / spec.itemsPerPage
            }
            render()
        }
    }

    // ── Result ────────────────────────────────────────────────────────────────

    private fun confirmPendingTarget() {
        when (pendingTargetKind) {
            TARGET_CURRENT_PAGE ->
                pendingPageId?.let { finishWithCurrentPage(it) }
            TARGET_OTHER_NOTEBOOK ->
                pendingNotebookId?.let { finishWithOtherNotebook(it) }
            TARGET_OTHER_NOTEBOOK_PAGE -> {
                val nb = pendingNotebookId; val pg = pendingNotebookPageId
                if (nb != null && pg != null) finishWithOtherNotebookPage(nb, pg)
            }
        }
    }

    private fun finishWithCurrentPage(pageId: String) {
        finishWithResult {
            putExtra(EXTRA_RESULT_TARGET_KIND, TARGET_CURRENT_PAGE)
            putExtra(EXTRA_RESULT_PAGE_ID, pageId)
        }
    }

    private fun finishWithOtherNotebook(notebookId: String) {
        finishWithResult {
            putExtra(EXTRA_RESULT_TARGET_KIND, TARGET_OTHER_NOTEBOOK)
            putExtra(EXTRA_RESULT_NOTEBOOK_ID, notebookId)
        }
    }

    private fun finishWithOtherNotebookPage(notebookId: String, pageId: String) {
        finishWithResult {
            putExtra(EXTRA_RESULT_TARGET_KIND, TARGET_OTHER_NOTEBOOK_PAGE)
            putExtra(EXTRA_RESULT_NOTEBOOK_ID, notebookId)
            putExtra(EXTRA_RESULT_PAGE_ID, pageId)
        }
    }

    private inline fun finishWithResult(extras: Intent.() -> Unit) {
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_CHROME, selectedChrome.name)
            extras()
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
