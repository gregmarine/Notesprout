package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseMode
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseState
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesproutsn.data.sidecarsOf
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityLibraryBinding
import com.symmetricalpalmtree.notesproutsn.export.ExportActivity
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.ScratchPadEntry
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookActivity
import com.symmetricalpalmtree.notesproutsn.templates.TemplatesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: folders and notebooks as a **paginated, non-scrolling card grid**.
 *
 * Three things define it:
 *  - **Pages, not scroll.** The grid is measured against the real container once it exists and
 *    holds exactly the cards that fit ([LibraryGrid] / [GridMath]). E-ink hates smooth scrolling;
 *    a page turn is one clean refresh.
 *  - **Breadcrumbs, not a tree.** The top bar is the path. Any crumb jumps there, back goes up one
 *    level and exits at the root.
 *  - **Covers are lazy, one page at a time.** The DAO listing is blob-free, so entering a folder of
 *    forty notebooks reads forty rows and six covers, not forty covers.
 *
 * On top of that sit the two **modes** (R5): Pinned and Recents. A mode is a flat shelf of
 * notebooks with no path — the breadcrumbs give way to a title and a close button, and the create
 * buttons stand down because there is no folder to create into. The mode persists in
 * [BrowseState], so the shelf the user left is the shelf they come back to.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var browseState: BrowseState
    private lateinit var sortPrefs: SortPrefs
    private lateinit var recentsPrefs: RecentsPrefs
    private val repo by lazy { IndexRepository() }
    /** The Scratch Pad's entry button (arc 11) — GONE unless a trusted extension is installed. */
    private lateinit var scratchPad: ScratchPadEntry

    private var folderId: String? = null
    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<CardItem>()

    /** Which shelf is on screen. Restored from [BrowseState] on launch and written on every change. */
    private var mode: BrowseMode = BrowseMode.NORMAL

    /** Covers already fetched for this listing. Cleared on every [refresh] so an edit is picked up. */
    private val coverCache = HashMap<String, ByteArray?>()
    private var grid: LibraryGrid? = null
    private var gridMeasured = false
    private var coldLaunch = false

    /** The one-finger flip over the card grid — the pager buttons' gesture twin. */
    private val listSwipe = ListSwipe(
        region = { if (::binding.isInitialized) binding.gridContainer else null },
        onFlipNext = { goToPage(pageIndex + 1) },
        onFlipPrevious = { goToPage(pageIndex - 1) },
    )

    private val newNotebookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // The result callback runs BEFORE onResume, so the launch latch armed by the + tap is
        // still up here — release it first or the open of the just-created notebook is silently
        // dropped (S2 regression, user-caught). The round-trip the latch guards is over.
        launching = false
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(NewNotebookActivity.EXTRA_NOTEBOOK_ID)
            val name = result.data?.getStringExtra(NewNotebookActivity.EXTRA_NOTEBOOK_NAME)
            if (id != null && name != null) openNotebook(id, name)
            lifecycleScope.launch { refresh() }
        }
    }

    private val movePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) lifecycleScope.launch { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // TopGuard is 0 on Ratta, so the breadcrumb bar sits flush at the top edge. The inset pass
        // is still applied: it is how the *bottom* bar clears a navigation bar if one is present.
        TopGuard.applyInsetPadding(binding.root)

        browseState = BrowseState(this)
        sortPrefs = SortPrefs(this)
        recentsPrefs = RecentsPrefs(this)
        folderId = browseState.folderId
        mode = browseState.mode
        coldLaunch = savedInstanceState == null

        wireBars()
        // The Scratch Pad (arc 11). Built here because it registers an ActivityResult launcher, and
        // one may not be registered after STARTED. No handoff to make: the library hosts no paper.
        scratchPad = ScratchPadEntry(activity = this, button = binding.btnScratchPad)
        binding.btnScratchPad.setOnClickListener { scratchPad.open() }
        TooltipCompat.setTooltipText(binding.btnScratchPad, binding.btnScratchPad.contentDescription)
        DebugMenu.install(this, binding.bottomRight)

        // The grid cannot be sized until the band it lives in has been laid out.
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (gridMeasured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            gridMeasured = true
            grid = LibraryGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress).also {
                it.measure(this, w, h)
                Slog.d(TAG) { "grid measured ${w}x$h → ${it.cardsPerPage} cards/page" }
            }
            lifecycleScope.launch {
                repo.ensurePinnedListExists()
                // A remembered folder may have been deleted since; fall back to the root.
                folderId?.let { id -> if (repo.alive(id) == null) navigateTo(null, refreshNow = false) }
                if (coldLaunch) reopenLastNotebookIfNeeded()
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launching = false
        // Re-discovered on every resume: a package can be disabled or replaced under us. Guarded
        // because an IndexGuard bounce returns from onCreate but still gets this callback.
        if (::scratchPad.isInitialized) scratchPad.refresh()
        if (gridMeasured) lifecycleScope.launch { refresh() }
    }

    override fun onDestroy() {
        // The guard bounce still runs this callback, and a `lateinit` teardown would crash on the
        // way out of a task Android rebuilt after a background process kill.
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        // The pad's held bind must not outlive the screen that opened it, result or no result.
        if (::scratchPad.isInitialized) scratchPad.close()
        super.onDestroy()
    }

    /**
     * True from any launch out of the library until it is back on top — ONE latch for every door
     * (notebook card *and* +Notebook), because in the e-ink feedback gap the second tap is not
     * always on the same control: a card tap plus a + tap would otherwise each pass their own flag
     * and stack two screens (S2 review finding).
     */
    private var launching = false

    /**
     * The one door into [NotebookActivity]. E-ink gives a tap no feedback for hundreds of ms, so
     * users double-tap; without this latch each tap would stack its own NotebookActivity — two
     * concurrent SQLCipher writers on one `.soil` (the documented lock-crash family). Reset in
     * [onResume]: by then the second instance would already exist, so the latch has done its job.
     *
     * It is also where the wait becomes visible (P1): [OpeningOverlay] puts "Opening…" up *here*,
     * at tap time, and the launch runs only once that frame has been drawn — the notebook then
     * carries the same box from its own first frame until the page lands.
     */
    private fun openNotebook(id: String, name: String, viaLink: Boolean = false) {
        if (launching) return
        launching = true
        OpeningOverlay.showThen(this) { startActivity(NotebookActivity.intent(this, id, name, viaLink)) }
    }

    /**
     * +Notebook. The folder's naming scheme is resolved and expanded **before** the New-notebook
     * screen opens (two cheap index reads; no feedback — the tap takes a beat), and the result rides
     * in as a prefill. Latched like [openNotebook], because the resolve puts a gap between the tap
     * and the screen and a second tap in that gap would launch twice.
     *
     * Naming never blocks what the user asked for: no scheme, an unparseable one, or any failure at
     * all means the screen opens with its own timestamp default.
     */
    private fun launchNewNotebook() {
        if (launching) return
        launching = true
        val fid = folderId
        lifecycleScope.launch {
            val prefill = resolveAndExpand(fid)
            // The beat is normally a few ms, but if the user has meanwhile left this folder the tap
            // no longer means "here" — drop it rather than create somewhere else.
            if (folderId != fid || isFinishing || isDestroyed) {
                launching = false
                return@launch
            }
            newNotebookLauncher.launch(NewNotebookActivity.intent(this@LibraryActivity, fid, prefill))
        }
    }

    /**
     * The scheme governing [fid] (nearest ancestor, then the root), expanded against the folder's
     * live notebook names so `{n}` counts the right siblings — which [SchemePrefill] fetches only
     * when the scheme actually holds a counter; nothing else in the expansion reads them. Null when
     * there is no scheme, or when anything at all goes wrong: a naming scheme is never worth a
     * crash, and this runs in `lifecycleScope`, which has no handler.
     *
     * Only the two index reads live here — the rules are [SchemePrefill]'s, shared with the link
     * picker's New notebook (K3) so a scheme cannot mean two different things.
     */
    private suspend fun resolveAndExpand(fid: String?): String? = try {
        SchemePrefill.expand(repo.resolveScheme(fid), System.currentTimeMillis()) {
            repo.notebooks(fid).map { it.name }
        }
    } catch (e: Exception) {
        // The resolve is the caller's own read, so its failure is caught here rather than inside.
        Log.w(TAG, "scheme resolve failed — using the default", e)
        null
    }

    /**
     * A notebook was open when the process died (the id survives in [BrowseState]) — put it back
     * on top of the library, but only when its index row is still alive **and** its `.soil` exists
     * (never mint a ghost file). The id is read once and cleared regardless of outcome.
     */
    private fun reopenLastNotebookIfNeeded() {
        val id = browseState.lastOpenNotebookId ?: return
        browseState.lastOpenNotebookId = null
        // Reopen the way it was open (K4): a via-link notebook restored without the flag would
        // read as a fresh open, clear the persisted trail, and lose the mid-chain walk-back.
        val viaLink = browseState.lastOpenViaLink
        lifecycleScope.launch {
            val s = repo.alive(id) ?: return@launch
            if (s.type != ObjectType.NOTEBOOK) return@launch
            val exists = withContext(Dispatchers.IO) { soilFile(this@LibraryActivity, id).exists() }
            if (!exists) return@launch
            openNotebook(s.id, s.name, viaLink)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::browseState.isInitialized) browseState.folderId = folderId
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun wireBars() = with(binding) {
        // A mode button toggles its own shelf: tapping Pinned while pinned is up goes back to the
        // folder you were in, so the same button is always the way out of what it opened.
        btnPinned.setOnClickListener { setMode(if (mode == BrowseMode.PINNED) BrowseMode.NORMAL else BrowseMode.PINNED) }
        btnRecents.setOnClickListener { setMode(if (mode == BrowseMode.RECENTS) BrowseMode.NORMAL else BrowseMode.RECENTS) }
        btnCloseMode.setOnClickListener { setMode(BrowseMode.NORMAL) }
        btnTemplates.setOnClickListener { startActivity(TemplatesActivity.intent(this@LibraryActivity)) }
        btnSort.setOnClickListener { showSortSheet() }
        btnNewFolder.setOnClickListener { showNewFolderDialog() }
        btnNewNotebook.setOnClickListener { launchNewNotebook() }
        btnUp.setOnClickListener { navigateUp() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }

        listOf(btnPinned, btnRecents, btnCloseMode, btnTemplates, btnSort, btnNewFolder,
               btnNewNotebook, btnUp, btnFirst, btnPrev, btnNext, btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    /** Switch shelves. A no-op on the mode already showing, so a redundant tap costs no refresh. */
    private fun setMode(newMode: BrowseMode) {
        if (mode == newMode) return
        mode = newMode
        browseState.mode = newMode
        pageIndex = 0
        Slog.d(TAG) { "mode → $newMode" }
        lifecycleScope.launch { refresh() }
    }

    /**
     * The top bar and the create buttons, per mode. In a mode there is no path — the breadcrumbs
     * give way to the shelf's name and a close button, and New folder / New notebook stand down
     * because a shelf is not a place to create into.
     */
    private fun renderChrome() = with(binding) {
        val inMode = mode != BrowseMode.NORMAL
        breadcrumbScroll.visibility = if (inMode) View.GONE else View.VISIBLE
        modeTitle.visibility = if (inMode) View.VISIBLE else View.GONE
        btnCloseMode.visibility = if (inMode) View.VISIBLE else View.GONE
        btnNewFolder.visibility = if (inMode) View.GONE else View.VISIBLE
        btnNewNotebook.visibility = if (inMode) View.GONE else View.VISIBLE
        btnPinned.isSelected = mode == BrowseMode.PINNED
        btnRecents.isSelected = mode == BrowseMode.RECENTS

        if (inMode) {
            btnUp.visibility = View.GONE
            modeTitle.setText(
                if (mode == BrowseMode.PINNED) R.string.mode_title_pinned else R.string.mode_title_recents
            )
        } else {
            renderBreadcrumb()
        }
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        lifecycleScope.launch {
            val ancestry = repo.ancestry(folderId)
            val container = binding.breadcrumbContainer
            val rootLabel = getString(R.string.library_root)
            container.removeAllViews()
            // Long-press any crumb — the root included — to set that folder's default notebook name.
            // The root has no card to long-press, so this is its only way in.
            container.addView(
                crumb(rootLabel, ink, onClick = { navigateTo(null) },
                    onLongClick = { openSchemeDialog(null, rootLabel) })
            )
            for (ref in ancestry) {
                container.addView(separator(ink))
                container.addView(
                    crumb(ref.name, ink, onClick = { navigateTo(ref.id) },
                        onLongClick = { openSchemeDialog(ref.id, ref.name) })
                )
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
    }

    private fun crumb(label: String, color: Int, onClick: () -> Unit, onLongClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
            // true: a long-press that already did its work must not also navigate on release.
            setOnLongClickListener { onLongClick(); true }
        }
    }

    /** The scheme editor, for a folder card, a breadcrumb, or the root ([folderId] null). */
    private fun openSchemeDialog(folderId: String?, folderName: String) =
        SchemeDialog.open(this, repo, folderId, folderName)

    private fun separator(color: Int): TextView = TextView(this).apply {
        text = " / "
        textSize = 16f
        setTextColor(color)
    }

    private fun renderPager() {
        // INVISIBLE, not GONE: the pager keeps its slot so the bar's other controls never shift.
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private suspend fun refresh() {
        renderChrome()
        coverCache.clear()
        // One read of the pinned list per refresh: every card's badge and the sheet's Pin/Unpin row
        // come from it, so no card ever asks the index on its own.
        val pinnedIds = repo.pinnedNotebookIds()
        items = when (mode) {
            BrowseMode.NORMAL -> normalItems(pinnedIds.toSet())
            BrowseMode.PINNED -> pinnedItems(pinnedIds)
            BrowseMode.RECENTS -> recentItems(pinnedIds.toSet())
        }

        binding.emptyState.setText(
            when (mode) {
                BrowseMode.NORMAL -> R.string.library_empty
                BrowseMode.PINNED -> R.string.library_pinned_empty
                BrowseMode.RECENTS -> R.string.library_recents_empty
            }
        )
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridMath.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    private suspend fun normalItems(pinnedIds: Set<String>): List<CardItem> {
        val all = repo.folders(folderId) + repo.notebooks(folderId)
        return SortRules.foldersFirst(all, sortPrefs.field, sortPrefs.order).map {
            if (it.type == ObjectType.FOLDER) CardItem.Folder(it)
            else CardItem.Notebook(it, pinned = it.id in pinnedIds)
        }
    }

    /**
     * The pinned shelf, in the **library's current sort**, not pin order. The membership edge does
     * carry a `sortOrder`, but making it the display order would give the user a second, invisible
     * arrangement to reason about; a shelf that obeys the sort control on screen is the honest one.
     */
    private suspend fun pinnedItems(pinnedIds: List<String>): List<CardItem> {
        val summaries = pinnedIds.mapNotNull { repo.alive(it) }
        return SortRules.sort(summaries, sortPrefs.field, sortPrefs.order)
            .map { CardItem.Notebook(it, pinned = true) }
    }

    /**
     * The recents shelf: **stored order, never re-sorted** ([RecentsAssembly]) — this is a history,
     * and Name ↑ would turn "what I was just working on" into an alphabet. The second line is the
     * parent folder rather than a date, because "where is it" is the useful thing here; the folder
     * names are memoised so a run of notebooks from one folder is a single lookup.
     *
     * Reading the shelf is also when the store is swept: ids whose rows are gone are pruned out of
     * the prefs for good, so the list cannot accumulate ghosts.
     */
    private suspend fun recentItems(pinnedIds: Set<String>): List<CardItem> {
        val entries = recentsPrefs.entries()
        val alive = LinkedHashMap<String, ObjectSummary>(entries.size)
        for (e in entries) {
            if (e.id in alive) continue
            val s = repo.alive(e.id) ?: continue
            if (s.type != ObjectType.NOTEBOOK) continue
            alive[e.id] = s
        }
        val order = RecentsAssembly.visibleIds(entries, alive.keys)
        val folderNames = HashMap<String, String>()
        val cards = order.mapNotNull { id ->
            val s = alive[id] ?: return@mapNotNull null
            val parent = s.parentId
            val subtitle = if (parent == null) getString(R.string.recents_parent_root)
            else folderNames.getOrPut(parent) { repo.alive(parent)?.name ?: getString(R.string.recents_parent_root) }
            CardItem.Notebook(s, pinned = id in pinnedIds, subtitle = subtitle)
        }
        recentsPrefs.pruneDeleted(alive.keys)
        return cards
    }

    /**
     * Bind this page's cards, fetching only the covers it needs. Several of these can be in flight
     * at once (a page tap racing `onResume`), so the blobs are read into a *local* map on IO and
     * merged into [coverCache] back on Main — the shared map is only ever written single-threaded.
     */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val range = GridMath.pageRange(pageIndex, g.cardsPerPage, items.size)
        val missing = range
            .mapNotNull { (items[it] as? CardItem.Notebook)?.summary?.id }
            .filter { it !in coverCache }
        if (missing.isNotEmpty()) {
            val fetched = withContext(Dispatchers.IO) {
                HashMap<String, ByteArray?>(missing.size).apply { missing.forEach { put(it, repo.cover(it)) } }
            }
            coverCache.putAll(fetched)
        }
        g.bind(items, pageIndex, coverCache)
        // After the bind: the indicator must never name a page before its cards are on screen.
        renderPager()
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun navigateTo(id: String?, refreshNow: Boolean = true) {
        folderId = id
        pageIndex = 0
        browseState.folderId = id
        if (refreshNow) lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        val current = folderId ?: return
        lifecycleScope.launch {
            val ancestry = repo.ancestry(current)
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        lifecycleScope.launch { bindCurrentPage() }
    }

    /** Back peels one layer at a time: out of a mode, then up a folder, then out of the app. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = when {
        mode != BrowseMode.NORMAL -> setMode(BrowseMode.NORMAL)
        folderId != null -> navigateUp()
        else -> @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── Cards ────────────────────────────────────────────────────────────────

    private fun onCardTap(item: CardItem) = when (item) {
        is CardItem.Folder -> navigateTo(item.summary.id)
        is CardItem.Notebook -> openNotebook(item.summary.id, item.summary.name)
    }

    /**
     * The action sheet. A **notebook's** sheet is raised one beat later than a folder's: whether it
     * offers **Export…** depends on whether a trusted exporter extension is installed *right now*
     * (arc 15 / E1), and discovery is a package query on IO. It re-runs at every open rather than
     * being cached — a package can be disabled or replaced under a standing library — and the row
     * is **GONE**, never disabled, when there is none: a control that cannot work is invisible on
     * e-ink, and a sheet that grew a row after it was already up would move the user's finger.
     */
    private fun onCardLongPress(item: CardItem) {
        if (item !is CardItem.Notebook) { showCardSheet(item, canExport = false); return }
        // The IO beat between the long-press and the sheet is an e-ink feedback gap like any other
        // (arc-15 review): a second long-press in it would stack a second sheet, and a card tap in
        // it would pop the sheet over a departing library. One latch closes the gap; a sheet that
        // is up is modal, so nothing needs to guard past the show.
        if (sheetPending || launching) return
        sheetPending = true
        lifecycleScope.launch {
            val canExport = try {
                ExtensionRegistry.exporters(this@LibraryActivity).isNotEmpty()
            } finally {
                sheetPending = false
            }
            if (isFinishing || isDestroyed || launching) return@launch
            showCardSheet(item, canExport)
        }
    }

    /** True while a notebook sheet's exporter discovery is in flight — long-press to show. */
    private var sheetPending = false

    private fun showCardSheet(item: CardItem, canExport: Boolean) {
        val s = item.summary
        val isFolder = item is CardItem.Folder
        val sheet = ActionSheetDialog(this).title(s.name)
        // Only notebooks pin — the pinned list is a shelf of things to write in, not of places.
        // The current state comes from the listing's own pinned read, not a fresh index query.
        (item as? CardItem.Notebook)?.let { nb ->
            val label = if (nb.pinned) R.string.action_unpin else R.string.action_pin
            sheet.addAction(R.drawable.ic_pinned, getString(label)) { togglePin(s.id, nb.pinned) }
        }
        // Only folders hold a naming scheme — it is a rule about what is created *inside* something.
        if (isFolder) {
            sheet.addAction(R.drawable.ic_cursor_text, getString(R.string.action_naming)) {
                openSchemeDialog(s.id, s.name)
            }
        }
        sheet
            .addAction(R.drawable.ic_edit, getString(R.string.action_rename)) { showRenameDialog(s) }
            .addAction(R.drawable.ic_move_folder, getString(R.string.action_move)) { showMovePicker(s) }
        // Notebooks only, and only with an exporter installed. Export always works from the sealed,
        // cold file — which is exactly what the library context guarantees and the notebook screen
        // could not, which is why this is the only entry point.
        if (canExport) {
            sheet.addAction(R.drawable.ic_download, getString(R.string.action_export)) { showExport(s) }
        }
        sheet
            .addAction(R.drawable.ic_trash, getString(R.string.action_delete)) {
                if (isFolder) confirmDeleteFolder(s) else confirmDeleteNotebook(s)
            }
            .show()
    }

    /** Identity travels as id + name — never a `File`. Latched with every other door out (S2). */
    private fun showExport(s: ObjectSummary) {
        if (launching) return
        launching = true
        startActivity(ExportActivity.intent(this, s.id, s.name))
    }

    /**
     * Pin membership is an index list edge ([IndexRepository.pin] / [IndexRepository.unpin]), not a
     * pref — it belongs to the library, travels with it, and is scrubbed by a delete. The refresh
     * is what makes the badge (and this row's own label) agree with it again.
     */
    private fun togglePin(notebookId: String, currentlyPinned: Boolean) {
        lifecycleScope.launch {
            if (currentlyPinned) repo.unpin(notebookId) else repo.pin(notebookId)
            refresh()
        }
    }

    // ── New folder / rename ──────────────────────────────────────────────────

    /**
     * New folder — the dialog, its validation order and its words all live in [NewFolderFlow],
     * shared with the link picker (K3). The library's own half is what happens afterwards: the new
     * folder is a card in the listing on screen, so the grid is rebuilt.
     */
    private fun showNewFolderDialog() = NewFolderFlow.show(this, repo, folderId) { refresh() }

    private fun showRenameDialog(s: ObjectSummary) {
        // Same re-entry guard as the New-folder accept — a double-fired rename is harmless today
        // (same name, same row), but the guard keeps the whole dialog family one shape.
        var accepting = false
        NameDialog.show(
            this,
            titleRes = R.string.rename_title,
            confirmRes = R.string.action_rename,
            initial = s.name,
        ) { name, dismiss ->
            if (accepting) return@show
            if (name == s.name) { dismiss(); return@show }
            val problem = NameRules.validate(name)
            if (problem != null) {
                Dialogs.problem(this, R.string.name_problem_title, NameDialog.problemMessage(this, problem))
                return@show
            }
            accepting = true
            lifecycleScope.launch {
                try {
                    // Excluding the item itself: re-casing its own name is a rename, not a collision.
                    if (repo.nameTaken(s.parentId, s.type, name, s.id)) {
                        val msg = if (s.type == ObjectType.NOTEBOOK) R.string.rename_duplicate_notebook else R.string.rename_duplicate_folder
                        Dialogs.problem(this@LibraryActivity, R.string.name_problem_title, getString(msg, name))
                        return@launch
                    }
                    repo.rename(s.id, name)
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDeleteNotebook(s: ObjectSummary) = confirm(
        titleRes = R.string.delete_notebook_title,
        bodyRes = R.string.delete_notebook_body,
        name = s.name,
    ) {
        lifecycleScope.launch {
            repo.deleteNotebook(s.id)
            recentsPrefs.remove(s.id)
            withContext(Dispatchers.IO) { purgeNotebookFile(s.id) }
            refresh()
        }
    }

    private fun confirmDeleteFolder(s: ObjectSummary) = confirm(
        titleRes = R.string.delete_folder_title,
        bodyRes = R.string.delete_folder_body,
        name = s.name,
    ) {
        lifecycleScope.launch {
            val removed = repo.deleteFolderRecursive(s.id)
            removed.forEach { recentsPrefs.remove(it) }
            withContext(Dispatchers.IO) { removed.forEach { purgeNotebookFile(it) } }
            // Standing inside the folder that just went: step out to where it used to be.
            if (folderId == s.id) navigateTo(s.parentId) else refresh()
        }
    }

    private fun confirm(titleRes: Int, bodyRes: Int, name: String, onConfirm: () -> Unit) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(titleRes, name))
                .setMessage(bodyRes)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * The file half of a delete. The index row is soft-deleted (and its pinned edges scrubbed) by
     * the repository; the bytes are a hard delete — file, SQLite sidecars, and the cached raw key,
     * which must go or a future notebook that happened to reuse the id would be opened with a key
     * derived from a file that no longer exists. IO thread.
     */
    private fun purgeNotebookFile(notebookId: String) {
        val file = soilFile(this, notebookId)
        val ok = !file.exists() || file.delete()
        sidecarsOf(file).forEach { it.delete() }
        KeyMaterial.invalidate(this, notebookId)
        Slog.d(TAG) { "purged $notebookId (file removed: $ok)" }
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    private fun showMovePicker(s: ObjectSummary) =
        movePickerLauncher.launch(FolderPickerActivity.intent(this, s.id, s.type, s.name, s.parentId))

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun showSortSheet() {
        val field = sortPrefs.field
        val order = sortPrefs.order
        fun tick(f: SortField, o: SortOrder) = if (field == f && order == o) R.drawable.ic_check else null
        ActionSheetDialog(this)
            .title(getString(R.string.cd_sort))
            .addAction(tick(SortField.NAME, SortOrder.ASC), getString(R.string.sort_name_asc)) {
                applySort(SortField.NAME, SortOrder.ASC)
            }
            .addAction(tick(SortField.NAME, SortOrder.DESC), getString(R.string.sort_name_desc)) {
                applySort(SortField.NAME, SortOrder.DESC)
            }
            .addAction(tick(SortField.MODIFIED, SortOrder.ASC), getString(R.string.sort_modified_asc)) {
                applySort(SortField.MODIFIED, SortOrder.ASC)
            }
            .addAction(tick(SortField.MODIFIED, SortOrder.DESC), getString(R.string.sort_modified_desc)) {
                applySort(SortField.MODIFIED, SortOrder.DESC)
            }
            .show()
    }

    private fun applySort(field: SortField, order: SortOrder) {
        sortPrefs.field = field
        sortPrefs.order = order
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    /** Observer only — the grid's cards keep every tap and long-press (see [ListSwipe]). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        listSwipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val TAG = "LibraryActivity"
    }
}
