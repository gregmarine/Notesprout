package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityLinkPickerBinding
import com.symmetricalpalmtree.notesproutsn.library.CardItem
import com.symmetricalpalmtree.notesproutsn.library.GridMath
import com.symmetricalpalmtree.notesproutsn.library.LibraryGrid
import com.symmetricalpalmtree.notesproutsn.library.NewFolderFlow
import com.symmetricalpalmtree.notesproutsn.library.NewNotebookActivity
import com.symmetricalpalmtree.notesproutsn.library.SchemePrefill
import com.symmetricalpalmtree.notesproutsn.library.SortRules
import com.symmetricalpalmtree.notesproutsn.notebook.LinkPickerModel.PickMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Where a link points** (arc 6 / K2) — the one screen behind both the selection toolbar's Link
 * and its Edit. It answers a single question and returns a single string: the link payload
 * ([LinkPayload]) the notebook screen writes onto the row.
 *
 * Three shelves, in the mode row's order:
 *  1. **This notebook** — the open notebook's pages, minus the one being written on (a link to
 *     here navigates nowhere). Numbers still count the *whole* notebook, so the page after the
 *     excluded one is the number a user would count to on paper ([LinkPickerModel.pageCards]).
 *  2. **Notebook** — the library, browsed exactly as the library browses it (folders navigate,
 *     notebooks select). The open notebook is hidden.
 *  3. **Notebook page** — the same browse, but a notebook opens into *its* pages.
 *
 * Two things make it more than a list:
 *  - **Page previews.** Every page card shows the page in miniature, rendered async behind a
 *    placeholder ([PagePreview]); the current notebook's come from the live session through
 *    [LinkPickerRelay], a browsed notebook's from a single read-only open ([ForeignPageSource]) —
 *    at most one at a time, sealed the moment the drill is left. Nothing here ever opens the
 *    current notebook's `.soil`: it is already open, and one file never has two connections.
 *  - **Heading page names** (the og rule): a page's card reads "4 · Meeting notes" when it has a
 *    topmost heading — loose or wrapped in a link — plain "Page 4" when it has none ([PageLabels]).
 *
 * K3 adds the other half of "where does this point": the target may not exist yet. Whichever grid is
 * on screen carries its own create — **New page** on a page grid, **New notebook** and **New
 * folder** on a browse ([LinkPickerModel.createButtons]) — and the created thing becomes the
 * selection, so a create and a pick are one gesture. Picker creations are deliberately **not
 * undoable** (the og rule); the notebook screen clears its own undo stack when a page landed in it.
 *
 * Nothing on this screen is ever disabled or greyed — on e-ink both are invisible. Every control
 * stays live; an OK with nothing chosen **explains** ([Dialogs.problem]) rather than doing nothing.
 */
class LinkPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkPickerBinding
    private lateinit var showing: LinkPickerRelay.Showing
    private lateinit var sortPrefs: SortPrefs
    private val repo by lazy { IndexRepository() }

    /** The edit prefill's payload, or null for a create — also which title the bar carries. */
    private var editing = false

    private var mode = PickMode.THIS_NOTEBOOK
    private var chrome = LinkPayload.CHROME_UNDERLINE

    private var selectedNotebookId: String? = null
    private var selectedPageId: String? = null

    /** Where the browse currently stands. Kept across mode switches — walking back to the same
     *  folder to try the other kind of target is the common move. */
    private var browseFolderId: String? = null

    /** The notebook whose pages are on screen in [PickMode.NOTEBOOK_PAGE], with its open. */
    private var drilledNotebookId: String? = null
    private var drilledNotebookName = ""
    private var foreign: ForeignPageSource? = null

    private var pageIndex = 0
    private var pageCount = 1
    private var browseItems = emptyList<CardItem>()
    private var pageItems = emptyList<Pair<PickerPage, Int>>()

    private var libraryGrid: LibraryGrid? = null
    private var pageGrid: PageCardGrid? = null
    private var gridMeasured = false

    private val coverCache = HashMap<String, ByteArray?>()

    /**
     * What a page card shows, once it has been read: the miniature and the heading title, cached
     * **per showing** (the locked decision) — never persisted, gone with the screen, so a preview
     * is always of the notebook as it is now and there is no staleness machinery to be wrong.
     *
     * Bitmaps are big, so the cache is bounded: past [maxCachedPreviews] it is dropped whole and
     * the visible cards re-render. Deliberately not an LRU — pages are browsed in order, a page's
     * worth of cards costs a few hundred ms, and a simple cap cannot leak.
     */
    private class PreviewEntry(val bitmap: Bitmap?, val title: String?)

    private val previewCache = HashMap<String, PreviewEntry>()
    private var maxCachedPreviews = DEFAULT_MAX_CACHED_PREVIEWS

    private var density = 1f
    private var scaledDensity = 1f

    /** One create at a time. E-ink gives a tap no feedback for hundreds of ms, so a second tap in
     *  that gap would insert a second page nobody asked for. Released in `finally`. */
    private var creatingPage = false

    /**
     * The New-notebook door's latch — the library's `launching` shape. The scheme resolve puts a
     * beat between the tap and the screen, and a second tap in it would stack two New-notebook
     * screens. Released at the TOP of the result callback, which runs before `onResume` (S2), and
     * on the stale-folder drop.
     */
    private var launchingCreate = false

    private val newNotebookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        launchingCreate = false        // before anything can bail — the S2 trap
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val id = result.data?.getStringExtra(NewNotebookActivity.EXTRA_NOTEBOOK_ID)
            ?: return@registerForActivityResult
        lifecycleScope.launch {
            // The row is written before the result comes back; a miss means it went away again
            // under us — degrade silently, the browse below is still correct.
            val summary = repo.alive(id) ?: return@launch
            when (mode) {
                PickMode.NOTEBOOK -> {
                    selectedNotebookId = id
                    refresh(jumpToSelection = true)
                }
                PickMode.NOTEBOOK_PAGE -> {
                    // A brand-new notebook has exactly one page: drilling straight in makes the
                    // whole target one more tap, not a browse plus a drill plus a tap.
                    openDrill(summary)
                    pageIndex = 0
                    refresh()
                }
                PickMode.THIS_NOTEBOOK -> Unit   // the button is not on screen in this mode
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        // Every exit but OK is a cancel — set once, so no path can forget it.
        setResult(Activity.RESULT_CANCELED)

        // The relay is armed immediately before the launch and dies with the notebook screen's
        // session. Null means this process was rebuilt underneath us: there is no live notebook to
        // show pages of, and the caller's pending capture is gone too (it says so on its side).
        val relay = LinkPickerRelay.showing
        if (relay == null) {
            Slog.d(TAG) { "no relay — host recreated under the picker; nothing to show" }
            finish()
            return
        }
        showing = relay

        binding = ActivityLinkPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        sortPrefs = SortPrefs(this)
        val dm = resources.displayMetrics
        density = dm.density
        scaledDensity = dm.scaledDensity

        val prefill = intent.getStringExtra(EXTRA_INITIAL_PAYLOAD)
        editing = prefill != null
        val decoded = prefill?.let { LinkPayload.decode(it) }
        mode = LinkPickerModel.modeFor(decoded)
        chrome = LinkPickerModel.chromeFor(decoded)

        wireBars()

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (gridMeasured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            gridMeasured = true
            libraryGrid = LibraryGrid(binding.gridContainer, ::onBrowseCardTap).also { it.measure(this, w, h) }
            pageGrid = PageCardGrid(binding.gridContainer, ::onPageCardTap).also { it.measure(this, w, h) }
            maxCachedPreviews = ((pageGrid?.cardsPerPage ?: 1) * CACHED_PAGES_OF_PREVIEWS)
                .coerceAtLeast(DEFAULT_MAX_CACHED_PREVIEWS)
            Slog.d(TAG) { "grid measured ${w}x$h → ${pageGrid?.cardsPerPage} cards/page" }
            lifecycleScope.launch {
                applyPrefill(decoded)
                refresh(jumpToSelection = true)
            }
        }
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        super.onDestroy()
        // The foreign open must never outlive the screen — an unsealed connection strands its WAL
        // sidecar for the whole process (the R6 lesson). sealAsync runs off the dead scope.
        foreign?.sealAsync()
        foreign = null
    }

    // ── Prefill ──────────────────────────────────────────────────────────────

    /**
     * Put the picker where the link already points. A prefill is **cosmetic**: a target that has
     * been deleted since, or a payload we cannot read at all, silently falls back to a fresh
     * picker — the user asked to retarget a link, not to be told about a byte string.
     */
    private suspend fun applyPrefill(decoded: LinkPayload.Decoded?) {
        if (decoded == null) return
        when (decoded.kind) {
            LinkPayload.KIND_PAGE -> selectedPageId = decoded.pageId
            LinkPayload.KIND_NOTEBOOK -> {
                val target = aliveNotebook(decoded.notebookId) ?: return
                browseFolderId = target.parentId
                selectedNotebookId = target.id
            }
            LinkPayload.KIND_NOTEBOOK_PAGE -> {
                val target = aliveNotebook(decoded.notebookId) ?: return
                browseFolderId = target.parentId      // where Up lands when the drill is left
                openDrill(target)
                selectedPageId = decoded.pageId
            }
        }
    }

    /** The index row for a link's notebook target, when it is still alive, still a notebook, and
     *  not the one we are linking *from*. Null for every "gone" — the picker just opens at root. */
    private suspend fun aliveNotebook(id: String?): ObjectSummary? {
        val summary = id?.let { repo.alive(it) } ?: return null
        if (summary.type != ObjectType.NOTEBOOK || summary.id == showing.notebookId) return null
        return summary
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun wireBars() = with(binding) {
        btnCancel.setOnClickListener { finish() }
        btnOk.setOnClickListener { onOk() }
        btnUp.setOnClickListener { goUp() }
        btnModeThisNotebook.setOnClickListener { setMode(PickMode.THIS_NOTEBOOK) }
        btnModeNotebook.setOnClickListener { setMode(PickMode.NOTEBOOK) }
        btnModeNotebookPage.setOnClickListener { setMode(PickMode.NOTEBOOK_PAGE) }
        btnStyleUnderline.setOnClickListener { setChrome(LinkPayload.CHROME_UNDERLINE) }
        btnStyleNone.setOnClickListener { setChrome(LinkPayload.CHROME_NONE) }
        btnNewPage.setOnClickListener { onNewPage() }
        btnNewNotebook.setOnClickListener { onNewNotebook() }
        btnNewFolder.setOnClickListener { onNewFolder() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }
        listOf(btnUp, btnFirst, btnPrev, btnNext, btnLast, btnNewPage, btnNewNotebook, btnNewFolder)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    /** The mode latches, the style latches, and the top bar's title + path — everything that
     *  answers "where am I and what am I choosing". */
    private suspend fun renderChrome() = with(binding) {
        btnModeThisNotebook.isSelected = mode == PickMode.THIS_NOTEBOOK
        btnModeNotebook.isSelected = mode == PickMode.NOTEBOOK
        btnModeNotebookPage.isSelected = mode == PickMode.NOTEBOOK_PAGE
        renderStyle()

        // GONE, never disabled — a disabled control is invisible on e-ink, so a button that cannot
        // apply to the grid on screen leaves the row entirely.
        val creates = LinkPickerModel.createButtons(mode, drilledNotebookId != null)
        btnNewPage.visibility = if (creates.newPage) View.VISIBLE else View.GONE
        val browseCreates = if (creates.newNotebookAndFolder) View.VISIBLE else View.GONE
        btnNewNotebook.visibility = browseCreates
        btnNewFolder.visibility = browseCreates

        val ink = ContextCompat.getColor(this@LinkPickerActivity, R.color.inkBlack)
        breadcrumbContainer.removeAllViews()
        breadcrumbContainer.addView(
            label(getString(if (editing) R.string.link_edit_action else R.string.link_picker_title), ink)
        )
        when {
            mode == PickMode.THIS_NOTEBOOK -> btnUp.visibility = View.GONE
            drilledNotebookId != null -> {
                breadcrumbContainer.addView(label(drilledNotebookName, ink))
                btnUp.visibility = View.VISIBLE
            }
            else -> {
                breadcrumbContainer.addView(crumb(getString(R.string.library_root), ink) { navigateTo(null) })
                for (ref in repo.ancestry(browseFolderId)) {
                    breadcrumbContainer.addView(separator(ink))
                    breadcrumbContainer.addView(crumb(ref.name, ink) { navigateTo(ref.id) })
                }
                btnUp.visibility = if (browseFolderId == null) View.GONE else View.VISIBLE
            }
        }
        // The current folder is the crumb the user needs — scroll to the end like the library does
        // (K5 review: the picker's copy had drifted and showed the START of a deep path).
        breadcrumbScroll.post { breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun renderStyle() = with(binding) {
        btnStyleUnderline.isSelected = chrome == LinkPayload.CHROME_UNDERLINE
        btnStyleNone.isSelected = chrome == LinkPayload.CHROME_NONE
    }

    private fun label(text: String, color: Int): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding((4 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
    }

    private fun crumb(text: String, color: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun separator(color: Int): TextView = TextView(this).apply {
        text = " / "
        textSize = 14f
        setTextColor(color)
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    /** True while a page grid is on screen: This-notebook, or a drilled foreign notebook. */
    private fun showingPages(): Boolean = mode == PickMode.THIS_NOTEBOOK || drilledNotebookId != null

    /** Whose pages the grid is showing. Never a second open of the current notebook's `.soil`. */
    private fun activeSource(): PickerPageSource? =
        if (mode == PickMode.THIS_NOTEBOOK) showing.source else foreign

    private suspend fun refresh(jumpToSelection: Boolean = false) {
        renderChrome()
        if (showingPages()) refreshPages(jumpToSelection) else refreshBrowse(jumpToSelection)
    }

    private suspend fun refreshPages(jumpToSelection: Boolean) {
        // The other grid is a sibling in the same container — clearing it is its own empty bind.
        libraryGrid?.bind(emptyList(), 0, emptyMap())
        browseItems = emptyList()

        val source = activeSource()
        val all = source?.pages().orEmpty()
        // Only the *current* page is excluded, and only in its own notebook: a foreign notebook has
        // no "current" page. Numbering is over the full list either way.
        val exclude = if (mode == PickMode.THIS_NOTEBOOK) showing.currentPageId else null
        pageItems = LinkPickerModel.pageCards(all, exclude)

        showEmpty(
            pageItems.isEmpty(),
            if (mode == PickMode.THIS_NOTEBOOK) R.string.link_picker_no_pages
            else R.string.link_picker_no_foreign_pages,
        )
        pageCount = GridMath.pageCount(pageItems.size, pageGrid?.cardsPerPage ?: 1)
        pageIndex =
            if (jumpToSelection) selectedPage(pageItems.indexOfFirst { it.first.id == selectedPageId })
            else GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    private suspend fun refreshBrowse(jumpToSelection: Boolean) {
        pageGrid?.bind(emptyList(), 0, null) { _, _, _ -> }
        pageItems = emptyList()
        coverCache.clear()

        val pinned = repo.pinnedNotebookIds().toSet()
        // Kind-scoped hiding: only the *notebook* listing drops the current notebook, so a folder
        // can never disappear because it happens to share an id (it cannot, but the rule is the
        // point — "hide the current notebook" must never become "hide something else").
        val folders = repo.folders(browseFolderId)
        val notebooks = repo.notebooks(browseFolderId).filter { it.id != showing.notebookId }
        browseItems = SortRules.foldersFirst(folders + notebooks, sortPrefs.field, sortPrefs.order).map {
            if (it.type == ObjectType.FOLDER) CardItem.Folder(it)
            else CardItem.Notebook(it, pinned = it.id in pinned)
        }

        showEmpty(browseItems.isEmpty(), R.string.link_picker_no_notebooks)
        pageCount = GridMath.pageCount(browseItems.size, libraryGrid?.cardsPerPage ?: 1)
        pageIndex =
            if (jumpToSelection) selectedPage(browseItems.indexOfFirst { it.summary.id == selectedNotebookId })
            else GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    /** The grid page holding a prefilled selection — a chosen card sitting on page 3 must not read
     *  as "nothing is selected". Nothing selected (or not found) stays on the first page. */
    private fun selectedPage(itemIndex: Int): Int {
        if (itemIndex < 0) return 0
        val perPage = if (showingPages()) pageGrid?.cardsPerPage ?: 1 else libraryGrid?.cardsPerPage ?: 1
        return GridMath.clampPage(LinkPickerModel.gridPageOf(itemIndex, perPage), pageCount)
    }

    private fun showEmpty(empty: Boolean, messageRes: Int) = with(binding.emptyState) {
        setText(messageRes)
        visibility = if (empty) View.VISIBLE else View.GONE
    }

    /**
     * Bind the visible slice. Browse fetches the covers for that slice only (blob-free listing, so
     * a folder of forty notebooks reads forty rows and one page of covers); pages carry their own
     * async previews. The pager is written **after** the cards, never naming a page that is not up.
     */
    private suspend fun bindCurrentPage() {
        if (showingPages()) {
            pageGrid?.bind(pageItems, pageIndex, selectedPageId, ::bindPageCard)
        } else {
            val grid = libraryGrid ?: return
            val range = GridMath.pageRange(pageIndex, grid.cardsPerPage, browseItems.size)
            val missing = range
                .mapNotNull { (browseItems[it] as? CardItem.Notebook)?.summary?.id }
                .filter { it !in coverCache }
            if (missing.isNotEmpty()) {
                val fetched = withContext(Dispatchers.IO) {
                    HashMap<String, ByteArray?>(missing.size).apply { missing.forEach { put(it, repo.cover(it)) } }
                }
                coverCache.putAll(fetched)
            }
            grid.bind(browseItems, pageIndex, coverCache, selectedNotebookId)
        }
        renderPager()
    }

    // ── Page previews ────────────────────────────────────────────────────────

    /**
     * Fill one page card: its number now, its miniature and heading title when they arrive.
     *
     * The card is never empty-looking — the label reads "Page 4" from the moment it is bound, and
     * the bordered band stands in for the paper. That placeholder is what carries a foreign
     * notebook's cold open (a KDF is ~a second); there is deliberately no spinner and no dialog,
     * because a slow *browse* is not an error.
     *
     * Late results check the card's page-id tag before painting. A rebind inflates fresh cards, so
     * the guard cannot actually fire today — it is what keeps that true if cards are ever recycled.
     */
    private fun bindPageCard(card: View, page: PickerPage, position: Int) {
        val labelView = card.findViewById<TextView>(R.id.pageLabel)
        val imageView = card.findViewById<ImageView>(R.id.pagePreview)
        val cached = previewCache[page.id]
        labelView.text = labelFor(position, cached?.title)
        imageView.setImageBitmap(cached?.bitmap)
        if (cached != null) return

        val source = activeSource() ?: return
        val width = pageGrid?.cardWidth ?: return
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { source.content(page.id) } ?: return@launch
            val (w, h) = PreviewMath.renderSize(width, page.width, page.height)
            val entry = withContext(Dispatchers.Default) {
                PreviewEntry(
                    bitmap = PagePreview.render(
                        page, content, w, h, density, HeadingRenderer.basePaint(scaledDensity),
                    ),
                    title = PageLabels.titleOf(content),
                )
            }
            if (previewCache.size >= maxCachedPreviews) previewCache.clear()
            previewCache[page.id] = entry
            if (card.tag != page.id) return@launch
            labelView.text = labelFor(position, entry.title)
            imageView.setImageBitmap(entry.bitmap)
        }
    }

    private fun labelFor(position: Int, title: String?): String =
        if (title.isNullOrEmpty()) getString(R.string.link_page_label, position)
        else getString(R.string.link_page_label_titled, position, title)

    // ── Taps ─────────────────────────────────────────────────────────────────

    /** Folders navigate. A notebook selects in [PickMode.NOTEBOOK] (tapping it again lets it go)
     *  and opens into its pages in [PickMode.NOTEBOOK_PAGE]. */
    private fun onBrowseCardTap(item: CardItem) {
        when (item) {
            is CardItem.Folder -> navigateTo(item.summary.id)
            is CardItem.Notebook -> when (mode) {
                PickMode.NOTEBOOK -> {
                    selectedNotebookId = if (selectedNotebookId == item.summary.id) null else item.summary.id
                    lifecycleScope.launch { bindCurrentPage() }
                }
                PickMode.NOTEBOOK_PAGE -> lifecycleScope.launch {
                    openDrill(item.summary)
                    pageIndex = 0
                    refresh()
                }
                PickMode.THIS_NOTEBOOK -> Unit    // the browse is not on screen in this mode
            }
        }
    }

    private fun onPageCardTap(page: PickerPage) {
        selectedPageId = if (selectedPageId == page.id) null else page.id
        lifecycleScope.launch { bindCurrentPage() }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun setMode(newMode: PickMode) {
        if (mode == newMode) return
        mode = newMode
        // A target chosen for one kind of link means nothing for another: page ids and notebook
        // ids are not interchangeable, and a half-carried selection would compose a wrong payload.
        selectedNotebookId = null
        selectedPageId = null
        leaveDrill()
        pageIndex = 0
        Slog.d(TAG) { "mode → $newMode" }
        lifecycleScope.launch { refresh() }
    }

    private fun setChrome(newChrome: Int) {
        if (chrome == newChrome) return
        chrome = newChrome
        renderStyle()
    }

    private fun navigateTo(folderId: String?) {
        browseFolderId = folderId
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    /** Up: out of a drilled notebook first, then one folder. */
    private fun goUp() {
        if (drilledNotebookId != null) {
            leaveDrill()
            pageIndex = 0
            lifecycleScope.launch { refresh() }
            return
        }
        val current = browseFolderId ?: return
        lifecycleScope.launch {
            val ancestry = repo.ancestry(current)
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null)
        }
    }

    /** Open a browsed notebook's pages. At most one foreign open exists at a time — the previous
     *  one is sealed here, not left to the destroy. */
    private fun openDrill(summary: ObjectSummary) {
        leaveDrill()
        drilledNotebookId = summary.id
        drilledNotebookName = summary.name
        selectedNotebookId = summary.id     // half the KIND_NOTEBOOK_PAGE target, already known
        foreign = ForeignPageSource(this, summary.id)
        Slog.d(TAG) { "drilled into ${summary.id}" }
    }

    /** Leave the drilled notebook: seal its file and drop the page choice — a page id means
     *  nothing outside the notebook it came from. */
    private fun leaveDrill() {
        if (drilledNotebookId == null) return
        foreign?.sealAsync()
        foreign = null
        drilledNotebookId = null
        drilledNotebookName = ""
        selectedNotebookId = null
        selectedPageId = null
        // Previews from that notebook can never be shown again without a fresh open.
        previewCache.clear()
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        lifecycleScope.launch { bindCurrentPage() }
    }

    private fun renderPager() {
        // INVISIBLE, never GONE: the bar must not change height when a listing grows past one page.
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    /** Back peels one layer at a time: out of a drilled notebook, then up a folder, then cancel.
     *  The browse folder is remembered across modes, so the folder arm is mode-gated — Back in the
     *  This-notebook grid is a cancel, not a navigation of a browse that is not on screen. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            drilledNotebookId != null -> goUp()
            mode != PickMode.THIS_NOTEBOOK && browseFolderId != null -> goUp()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ── Create (K3) ──────────────────────────────────────────────────────────

    /**
     * New page, in whichever notebook's grid is on screen. A selected card is the **anchor** — the
     * sheet then asks which side of it — and nothing selected simply appends, because there is no
     * "before or after" question to ask about a page nobody named. The anchor may sit on another
     * grid page: it is a selection, not a position on screen.
     */
    private fun onNewPage() {
        val anchor = selectedPageId
        if (anchor == null) { createPage(null, before = false); return }
        ActionSheetDialog(this)
            .addAction(null, getString(R.string.link_insert_before)) { createPage(anchor, before = true) }
            .addAction(null, getString(R.string.link_insert_after)) { createPage(anchor, before = false) }
            .show()
    }

    /**
     * Run the create against the grid's own creator — the live session for the current notebook (its
     * `.soil` is never opened twice), the drilled notebook's single open otherwise — and make the
     * new page the target: it is what the user was reaching for.
     */
    private fun createPage(anchorId: String?, before: Boolean) {
        if (creatingPage) return
        creatingPage = true
        lifecycleScope.launch {
            try {
                val created = when {
                    mode == PickMode.THIS_NOTEBOOK -> showing.createPage(anchorId, before)
                    else -> foreign?.createPage(anchorId, before)
                }
                if (created == null) {
                    Dialogs.problem(
                        this@LinkPickerActivity,
                        R.string.link_new_page_failed_title,
                        R.string.link_new_page_failed_body,
                    )
                    return@launch
                }
                Slog.d(TAG) { "created page ${created.id} (before=$before, anchored=${anchorId != null})" }
                selectedPageId = created.id
                refresh(jumpToSelection = true)
            } finally {
                creatingPage = false
            }
        }
    }

    /**
     * New notebook — the real New-notebook screen, prefilled from the browse folder's naming scheme
     * exactly as the library's +Notebook is ([SchemePrefill]), so a folder means the same thing
     * whichever door a notebook is created through.
     */
    private fun onNewNotebook() {
        if (launchingCreate) return
        launchingCreate = true
        val fid = browseFolderId
        lifecycleScope.launch {
            val prefill = try {
                SchemePrefill.expand(repo.resolveScheme(fid), System.currentTimeMillis()) {
                    repo.notebooks(fid).map { it.name }
                }
            } catch (e: Exception) {
                // Naming never blocks the create: the screen falls back to its timestamp default.
                Log.w(TAG, "scheme resolve failed — using the default", e)
                null
            }
            // The resolve is a beat: if the browse has moved on meanwhile, the tap no longer means
            // "here" — drop it rather than create somewhere else (the library's stale-folder rule).
            if (browseFolderId != fid || isFinishing || isDestroyed) {
                launchingCreate = false
                return@launch
            }
            newNotebookLauncher.launch(NewNotebookActivity.intent(this@LinkPickerActivity, fid, prefill))
        }
    }

    /** New folder — the library's own dialog ([NewFolderFlow]: name + scheme, one validation order),
     *  then navigate into it, because a folder is a place to keep looking, never a target. */
    private fun onNewFolder() = NewFolderFlow.show(this, repo, browseFolderId) { folder ->
        navigateTo(folder.id)
    }

    // ── OK ───────────────────────────────────────────────────────────────────

    private fun onOk() {
        val payload = LinkPickerModel.composeOk(
            mode = mode,
            chrome = chrome,
            currentNotebookId = showing.notebookId,
            selectedNotebookId = selectedNotebookId,
            selectedPageId = selectedPageId,
        )
        if (payload == null) {
            // Never a dead button and never a disabled one: say what is missing.
            Dialogs.problem(
                this,
                R.string.link_pick_none_title,
                when (mode) {
                    PickMode.THIS_NOTEBOOK -> R.string.link_pick_none_page
                    PickMode.NOTEBOOK -> R.string.link_pick_none_notebook
                    PickMode.NOTEBOOK_PAGE -> R.string.link_pick_none_notebook_page
                },
            )
            return
        }
        Slog.d(TAG) { "picked $mode target, chrome=$chrome" }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PAYLOAD, payload))
        finish()
    }

    companion object {
        private const val TAG = "LinkPickerActivity"

        /** The link's existing payload, for an edit. Only ids ride the Intent — never content. */
        const val EXTRA_INITIAL_PAYLOAD = "initialPayload"

        /** The composed payload, on RESULT_OK. */
        const val EXTRA_RESULT_PAYLOAD = "resultPayload"

        /** Roughly how many grid pages of previews are kept before the cache is dropped whole. */
        private const val CACHED_PAGES_OF_PREVIEWS = 3
        private const val DEFAULT_MAX_CACHED_PREVIEWS = 24

        fun intent(context: Context, initialPayload: String?): Intent =
            Intent(context, LinkPickerActivity::class.java)
                .putExtra(EXTRA_INITIAL_PAYLOAD, initialPayload)
    }
}
