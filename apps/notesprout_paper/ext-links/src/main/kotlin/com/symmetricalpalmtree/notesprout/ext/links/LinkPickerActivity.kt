package com.symmetricalpalmtree.notesprout.ext.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.ext.links.databinding.ActivityLinkPickerBinding
import com.symmetricalpalmtree.notesprout.extension.CatalogEntry
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.ILinkCatalog
import com.symmetricalpalmtree.notesprout.extension.LinkChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The extension-owned link picker (arc 7 / L2; UI-rule tier 2) — where a link's *meaning* is chosen.
 *
 * Three modes in one screen (L2 Q1), a three-way toggle in this order: **This notebook** (a page of
 * the notebook the link lives in — the current page is already excluded by the host), **Notebook**
 * (browse the library, pick a notebook: the link opens it on its last-open page) and **Notebook
 * page** (the same browse, but a notebook drills into its pages). A second toggle chooses the chrome
 * — Underline (the default) or None. The grid is the library folder picker's shape: bordered cards,
 * measured columns, first/prev/next/last paging, the chosen card inverted (a disabled or greyed
 * control is invisible on e-ink; an inverted one is not).
 *
 * **The notebook the link lives in is hidden from both browse modes** — a link to its own notebook
 * is a no-op trap — and there is no search field (L2 Q3: the picker stays IME-free until L3).
 *
 * **Creating from inside the picker** (L3): the top bar carries the mode's own create buttons left of
 * OK — New page in "This notebook" and inside a drilled notebook, New folder + New notebook while
 * browsing the library ([PickerModel.createButtons]). A page lands before or after the chosen card
 * (or appended when nothing is chosen) and comes back selected; a folder is entered as soon as it
 * exists; a notebook is made by the **host's own New-notebook screen**, armed through the catalog so
 * nothing rides that Intent in either direction.
 *
 * Everything on screen comes from [PickSession] — the store/catalog binders the host lent for this
 * showing (rule 25). Every catalog call is a blocking Binder call and runs on IO with a "Loading…"
 * in the grid area; a revoked catalog (the showing is over) finishes the screen, any other failure
 * is an honest [Dialogs.problem] and the screen stays. OK composes the payload through [LinkPayload]
 * and parks a [LinkChoice] in the session for `takeResult` — **the payload never rides the Intent**;
 * Back (and system back) cancel. The pure decisions live in [PickerModel]; this file is the wiring.
 */
class LinkPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkPickerBinding

    /** The host's per-showing lens. Null after [PickSession] was cleared — the screen is over. */
    private var catalog: ILinkCatalog? = null
    private var currentNotebookId: String? = null
    private var editMode = false

    private var mode = PickerModel.Mode.THIS_NOTEBOOK
    private var chrome = ExtensionContract.LINK_CHROME_UNDERLINE
    private var selectedId: String? = null

    /** The folder browse stack, `(id, name)` deepest-last; empty = the library root. */
    private val folderStack = ArrayList<Pair<String, String>>()

    /** Non-null while "Notebook page" is drilled into a notebook's page list. */
    private var drillNotebookId: String? = null
    private var drillNotebookName: String? = null

    /** The rows the grid is showing, already filtered; [showingPages] drives the "Page n" fallback. */
    private var entries: List<PickerModel.Entry> = emptyList()
    private var showingPages = false
    private var loading = false

    /** One create call at a time (L3) — a second tap on e-ink is a second row, not a no-op. */
    private var creating = false

    /**
     * The host's New-notebook screen, launched for a result (L3). Registered as a field so it exists
     * before `onCreate` returns, the only time `registerForActivityResult` is legal; the Intent it
     * launches carries nothing — the catalog armed the screen and holds what it made.
     */
    private val newNotebookLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Any result code: the host parks a created notebook, or nothing, and we drain either way.
            takeCreatedNotebook()
        }

    /** Bumped by every [load]; a reply from an older token is a navigation the user already left. */
    private var loadToken = 0

    /** The notebook whose folder chain seeds the first browse (Edit prefill); consumed by [firstLoad]. */
    private var prefillPathId: String? = null

    private var grid: GridLayout? = null
    private var pageIndex = 0
    private var pageCount = 1
    private var columns = 2
    private var cardsPerPage = 1
    private var gridWidth = 0
    private var gridMeasured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        // No showing (the process was restarted while the picker was up) — nothing to browse.
        val lens = PickSession.catalog
        if (lens == null) {
            Slog.d(TAG) { "no live showing" }
            finish()
            return
        }
        catalog = lens
        currentNotebookId = PickSession.currentNotebookId
        editMode = intent.getBooleanExtra(ExtensionContract.EXTRA_LINK_EDIT, false)

        binding = ActivityLinkPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        // Every exit that isn't OK is a cancel — including system back and a process teardown.
        setResult(Activity.RESULT_CANCELED)

        binding.title.text =
            getString(if (editMode) R.string.links_picker_title_edit else R.string.links_picker_title)

        applyPrefill()
        wire()
        renderMode()
        renderChrome()

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (!gridMeasured && binding.gridContainer.width > 0 && binding.gridContainer.height > 0) {
                gridMeasured = true
                measureGrid()
                firstLoad()
            }
        }
    }

    /** Edit reopens on its own target; create starts on "This notebook" with the underline chrome. */
    private fun applyPrefill() {
        val decoded = if (editMode) PickSession.editPayload?.let { LinkPayload.decode(it) } else null
        val prefill = PickerModel.prefill(decoded)
        mode = prefill.mode
        chrome = prefill.chrome
        selectedId = prefill.selectedId
        drillNotebookId = prefill.drillNotebookId
        drillNotebookName = null
        // Whose folder chain the first load needs: a NOTEBOOK target browses to its parent folder,
        // a drilled NOTEBOOK_PAGE wants its name + the folder Up should land in.
        prefillPathId = prefill.drillNotebookId
            ?: prefill.selectedId.takeIf { prefill.mode == PickerModel.Mode.NOTEBOOK }
        Slog.d(TAG) { "open: edit=$editMode mode=${prefill.mode} chrome=${prefill.chrome} prefilled=${prefill.selectedId != null}" }
    }

    /**
     * The first grid fill. An Edit prefill first asks the host where its target notebook lives
     * (`pathTo`, the appended catalog method) and seeds the browse there — the target's card is then
     * on screen and inverted instead of hidden behind the library root (the L2 device finding).
     * Best-effort: an empty or malformed reply, or any failure, just opens the browse at the root as
     * before — never a dialog. A stale reply (the user already navigated) is dropped by [loadToken].
     */
    private fun firstLoad() {
        val lens = catalog ?: return
        val target = prefillPathId
        prefillPathId = null
        if (target == null) {
            load()
            return
        }
        val token = loadToken
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { lens.pathTo(target) }.getOrNull()
            }?.let { rows -> PickerModel.pathParts(rows.map { PickerModel.Entry(it.id, it.kind, it.label) }) }
            if (token != loadToken || isFinishing || isDestroyed) return@launch
            if (path != null) {
                folderStack.clear()
                folderStack.addAll(path.folders)
                if (drillNotebookId != null) drillNotebookName = path.notebookName
                Slog.d(TAG) { "prefill path: ${path.folders.size} folder(s)" }
            }
            load()
        }
    }

    private fun wire() {
        binding.btnBack.setOnClickListener { cancel() }
        binding.btnOk.setOnClickListener { confirm() }

        binding.btnModeThis.setOnClickListener { switchMode(PickerModel.Mode.THIS_NOTEBOOK) }
        binding.btnModeNotebook.setOnClickListener { switchMode(PickerModel.Mode.NOTEBOOK) }
        binding.btnModePage.setOnClickListener { switchMode(PickerModel.Mode.NOTEBOOK_PAGE) }

        binding.btnChromeUnderline.setOnClickListener { switchChrome(ExtensionContract.LINK_CHROME_UNDERLINE) }
        binding.btnChromeNone.setOnClickListener { switchChrome(ExtensionContract.LINK_CHROME_NONE) }

        binding.btnUp.setOnClickListener { goUp() }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }

        binding.btnNewPage.setOnClickListener { onNewPage() }
        binding.btnNewFolder.setOnClickListener { onNewFolder() }
        binding.btnNewNotebook.setOnClickListener { onNewNotebook() }

        listOf(
            binding.btnBack, binding.btnUp,
            binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast,
            binding.btnNewPage, binding.btnNewFolder, binding.btnNewNotebook,
        ).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    // ── Modes, chrome, browsing ──────────────────────────────────────────────

    private fun switchMode(next: PickerModel.Mode) {
        if (next == mode) return
        val reset = PickerModel.afterModeSwitch(next, chrome)
        mode = reset.mode
        selectedId = reset.selectedId
        drillNotebookId = reset.drillNotebookId
        drillNotebookName = null
        folderStack.clear()
        pageIndex = 0
        renderMode()
        load()
    }

    private fun switchChrome(next: Int) {
        if (next == chrome) return
        chrome = next
        renderChrome()
    }

    private fun goUp() {
        when {
            // Leaving a notebook's page list drops the page that was chosen inside it.
            drillNotebookId != null -> {
                drillNotebookId = null
                drillNotebookName = null
                selectedId = null
            }
            folderStack.isNotEmpty() -> folderStack.removeAt(folderStack.size - 1)
            else -> return
        }
        pageIndex = 0
        load()
    }

    private fun onCardTapped(entry: PickerModel.Entry) {
        when {
            mode == PickerModel.Mode.THIS_NOTEBOOK -> select(entry.id)
            drillNotebookId != null -> select(entry.id)
            entry.kind == ExtensionContract.CATALOG_FOLDER -> {
                folderStack.add(entry.id to entry.label)
                pageIndex = 0
                load()
            }
            entry.kind == ExtensionContract.CATALOG_NOTEBOOK ->
                if (mode == PickerModel.Mode.NOTEBOOK) {
                    select(entry.id)
                } else {
                    drillNotebookId = entry.id
                    drillNotebookName = entry.label
                    selectedId = null
                    pageIndex = 0
                    load()
                }
            else -> Unit
        }
    }

    private fun select(id: String) {
        selectedId = id
        renderGrid()
    }

    // ── The catalog ──────────────────────────────────────────────────────────

    /**
     * Read the rows this mode + browse position shows. Blocking Binder calls, so IO; the reply is
     * dropped when a newer [load] has started or the screen is going away.
     */
    private fun load() {
        val lens = catalog ?: return
        val token = ++loadToken
        val currentMode = mode
        val drill = drillNotebookId
        val folder = folderStack.lastOrNull()?.first ?: ""
        val ownNotebook = currentNotebookId
        loading = true
        renderBrowseBar()
        // Drill and folder depth both change through here, and both change what may be created.
        renderCreateButtons()
        renderGrid()

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching<List<CatalogEntry>> {
                    when {
                        currentMode == PickerModel.Mode.THIS_NOTEBOOK ->
                            if (ownNotebook.isNullOrBlank()) emptyList() else lens.listPages(ownNotebook)
                        drill != null -> lens.listPages(drill)
                        else -> lens.listFolder(folder)
                    }
                }
            }
            if (token != loadToken || isFinishing || isDestroyed) return@launch
            loading = false
            outcome
                .onSuccess { rows -> accept(currentMode, drill, rows) }
                .onFailure { failed(it) }
        }
    }

    private fun accept(loadedMode: PickerModel.Mode, drill: String?, rows: List<CatalogEntry>) {
        val mapped = rows.map { PickerModel.Entry(it.id, it.kind, it.label) }
        showingPages = loadedMode == PickerModel.Mode.THIS_NOTEBOOK || drill != null
        entries = if (showingPages) mapped else PickerModel.browseEntries(mapped, currentNotebookId)
        Slog.d(TAG) { "loaded ${entries.size} rows (pages=$showingPages)" }
        // An Edit's prefilled target may sit pages into the grid — open on the page that shows it,
        // or its highlight would read as "nothing selected".
        val chosenAt = selectedId?.let { id -> entries.indexOfFirst { it.id == id } } ?: -1
        if (chosenAt >= 0 && cardsPerPage > 0) pageIndex = chosenAt / cardsPerPage
        repaginate()
        renderGrid()
    }

    private fun failed(e: Throwable) {
        Slog.d(TAG) { "catalog failed: ${e.javaClass.simpleName}" }
        if (e is SecurityException) {
            // The showing was revoked — the host is no longer listening. Leave plain.
            finish()
            return
        }
        val message = when (e) {
            is IllegalArgumentException, is IllegalStateException ->
                e.message ?: getString(R.string.links_catalog_failed)
            else -> getString(R.string.links_catalog_failed)
        }
        Dialogs.problem(this, binding.title.text, message)
        renderGrid()
    }

    // ── Creating (L3) ────────────────────────────────────────────────────────

    /**
     * New page. The notebook is whichever one the grid is showing pages of; a chosen page card makes
     * the insert positional (the sheet asks before / after), otherwise the page is appended. The new
     * page comes back selected — [accept] already opens the grid on the page holding the selection.
     */
    private fun onNewPage() {
        if (creating) return
        val notebook = drillNotebookId ?: currentNotebookId
        if (notebook.isNullOrBlank()) return
        val anchor = selectedId?.takeIf { showingPages && it.isNotBlank() }
        if (anchor == null) {
            createPage(notebook, anchorPageId = "", before = false)
            return
        }
        CreateDialogs.insertPosition(this) { before -> createPage(notebook, anchor, before) }
    }

    private fun createPage(notebookId: String, anchorPageId: String, before: Boolean) {
        val lens = catalog ?: return
        if (creating) return
        creating = true
        loading = true
        // A mode switch mid-create has already started its own load and thrown the target away —
        // the page is still made, but selecting it would be a page id under the wrong destination.
        val token = ++loadToken
        renderGrid()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { lens.createPage(notebookId, anchorPageId, before) }
            }
            creating = false
            if (isFinishing || isDestroyed || token != loadToken) return@launch
            loading = false
            outcome
                .onSuccess { id ->
                    Slog.d(TAG) { "created page (anchored=${anchorPageId.isNotBlank()}, before=$before)" }
                    selectedId = id
                    load()
                }
                .onFailure { failed(it) }
        }
    }

    /**
     * New folder, in the folder the browse is standing in. A refusal keeps the prompt — and the text
     * the user typed — on screen behind the problem dialog; only success closes it, and then the
     * browse walks straight into the folder that now exists.
     */
    private fun onNewFolder() {
        if (creating) return
        CreateDialogs.folderName(this) { name, prompt -> createFolder(name, prompt) }
    }

    private fun createFolder(name: String, prompt: CreateDialogs.NamePrompt) {
        val lens = catalog ?: return
        if (creating) return
        creating = true
        prompt.busy(true)
        val parent = folderStack.lastOrNull()?.first ?: ""
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { lens.createFolder(parent, name) } }
            creating = false
            if (isFinishing || isDestroyed) return@launch
            prompt.busy(false)
            outcome
                .onSuccess { id ->
                    Slog.d(TAG) { "created folder (depth=${folderStack.size + 1})" }
                    prompt.close()
                    folderStack.add(id to name)
                    pageIndex = 0
                    load()
                }
                .onFailure { failed(it) }
        }
    }

    /**
     * New notebook — the host's own screen, not ours (L3 wizard Q2). `prepareNewNotebook` arms it
     * with the browsed folder and the naming scheme's default name; the launch carries no extras;
     * the result is drained back through the catalog. A missing or refused screen is the generic
     * failure — the host is the only package that can answer that action.
     */
    private fun onNewNotebook() {
        val lens = catalog ?: return
        if (creating) return
        creating = true
        val parent = folderStack.lastOrNull()?.first ?: ""
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { lens.prepareNewNotebook(parent) } }
            creating = false
            if (isFinishing || isDestroyed) return@launch
            outcome
                .onSuccess { launchNewNotebook() }
                .onFailure { failed(it) }
        }
    }

    private fun launchNewNotebook() {
        val intent = Intent(ExtensionContract.ACTION_LINK_NEW_NOTEBOOK_SCREEN)
            .setPackage(BuildConfig.HOST_PACKAGE)
        try {
            newNotebookLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Slog.d(TAG) { "new-notebook screen: ${e.javaClass.simpleName}" }
            Dialogs.problem(this, binding.title.text, getString(R.string.links_catalog_failed))
        } catch (e: SecurityException) {
            Slog.d(TAG) { "new-notebook screen: ${e.javaClass.simpleName}" }
            Dialogs.problem(this, binding.title.text, getString(R.string.links_catalog_failed))
        }
    }

    /**
     * What the host's screen made, or null when the user backed out of it. "Notebook" selects the new
     * card where it now sits in the browse; "Notebook page" drills into it — a fresh notebook has one
     * page, so the pick is one tap away.
     */
    private fun takeCreatedNotebook() {
        val lens = catalog ?: return
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { lens.takeCreatedNotebook() } }
            if (isFinishing || isDestroyed) return@launch
            outcome
                .onSuccess { entry ->
                    if (entry == null) {
                        Slog.d(TAG) { "new notebook: cancelled" }
                        return@onSuccess
                    }
                    Slog.d(TAG) { "new notebook: created (mode=$mode)" }
                    when (mode) {
                        PickerModel.Mode.NOTEBOOK -> selectedId = entry.id
                        PickerModel.Mode.NOTEBOOK_PAGE -> {
                            drillNotebookId = entry.id
                            drillNotebookName = entry.label
                            selectedId = null
                        }
                        // The button does not exist in "This notebook" — nothing to land.
                        else -> return@onSuccess
                    }
                    pageIndex = 0
                    load()
                }
                .onFailure { failed(it) }
        }
    }

    // ── OK ───────────────────────────────────────────────────────────────────

    private fun confirm() {
        val composition = PickerModel.compose(mode, chrome, selectedId, drillNotebookId)
        if (composition == null) {
            // Never a disabled OK button — on e-ink a disabled control is invisible.
            Dialogs.problem(this, binding.title.text, getString(R.string.links_pick_none))
            return
        }
        val choice = runCatching {
            LinkChoice(
                LinkPayload.encode(
                    composition.chrome,
                    composition.kind,
                    composition.notebookId,
                    composition.pageId,
                ),
                composition.chrome,
            )
        }.getOrNull()
        if (choice == null) {
            Slog.d(TAG) { "compose refused kind=${composition.kind}" }
            Dialogs.problem(this, binding.title.text, getString(R.string.links_catalog_failed))
            return
        }
        PickSession.result = choice
        setResult(ExtensionContract.RESULT_LINK_PICKED)
        Slog.d(TAG) { "picked kind=${composition.kind} chrome=${composition.chrome}" }
        finish()
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun renderMode() {
        toggle(binding.btnModeThis, mode == PickerModel.Mode.THIS_NOTEBOOK)
        toggle(binding.btnModeNotebook, mode == PickerModel.Mode.NOTEBOOK)
        toggle(binding.btnModePage, mode == PickerModel.Mode.NOTEBOOK_PAGE)
        renderBrowseBar()
        renderCreateButtons()
    }

    /**
     * The mode's create buttons (L3). The decision is [PickerModel.createButtons]; the one thing on
     * top of it is the showing's own shape — a link being made outside a notebook (no
     * `currentNotebookId`) has nowhere to put a page, so "This notebook" shows nothing at all.
     */
    private fun renderCreateButtons() {
        val show = PickerModel.createButtons(mode, drillNotebookId != null)
        val page = show.newPage && (drillNotebookId != null || !currentNotebookId.isNullOrBlank())
        binding.btnNewPage.visibility = if (page) View.VISIBLE else View.GONE
        binding.btnNewFolder.visibility = if (show.newFolder) View.VISIBLE else View.GONE
        binding.btnNewNotebook.visibility = if (show.newNotebook) View.VISIBLE else View.GONE
    }

    private fun renderChrome() {
        toggle(binding.btnChromeUnderline, chrome == ExtensionContract.LINK_CHROME_UNDERLINE)
        toggle(binding.btnChromeNone, chrome == ExtensionContract.LINK_CHROME_NONE)
    }

    /** Selected = filled inkBlack with a paperWhite label; unselected = the 1 dp bordered card. */
    private fun toggle(button: AppCompatButton, on: Boolean) {
        val l = button.paddingLeft
        val t = button.paddingTop
        val r = button.paddingRight
        val b = button.paddingBottom
        button.setBackgroundResource(if (on) R.drawable.btn_elevated_background else R.drawable.shape_bordered)
        button.setPadding(l, t, r, b)
        button.setTextColor(ContextCompat.getColor(this, if (on) R.color.paperWhite else R.color.inkBlack))
    }

    private fun renderBrowseBar() {
        val browsing = mode != PickerModel.Mode.THIS_NOTEBOOK
        binding.browseBar.visibility = if (browsing) View.VISIBLE else View.GONE
        if (!browsing) return
        binding.location.text = when {
            // A prefilled Edit drills in without ever seeing the notebook's card, so its name can be
            // unknown — the mode's own label stands in for it.
            drillNotebookId != null ->
                drillNotebookName?.takeIf { it.isNotBlank() } ?: getString(R.string.links_mode_notebook)
            folderStack.isEmpty() -> getString(R.string.links_location_root)
            else -> folderStack.last().second
        }
        binding.btnUp.visibility =
            if (drillNotebookId != null || folderStack.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun measureGrid() {
        val container = binding.gridContainer
        val density = resources.displayMetrics.density
        gridWidth = (container.width - container.paddingStart - container.paddingEnd).coerceAtLeast(1)
        columns = if (gridWidth / density >= WIDE_GRID_DP) 3 else 2
        val cardW = gridWidth / columns
        val cardH = (cardW * CARD_ASPECT).toInt().coerceAtLeast(1)
        val rows = (container.height / cardH).coerceAtLeast(1)
        cardsPerPage = columns * rows
    }

    private fun repaginate() {
        pageCount = if (entries.isEmpty()) 1 else (entries.size - 1) / cardsPerPage + 1
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        renderGrid()
    }

    private fun renderGrid() {
        val container = binding.gridContainer
        val g = grid ?: GridLayout(this).also {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            container.addView(it)
            grid = it
        }
        g.removeAllViews()
        g.columnCount = columns

        binding.loadingState.visibility = if (loading) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (!loading && entries.isEmpty()) View.VISIBLE else View.GONE
        binding.pager.visibility = if (!loading && pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.links_page_of, pageIndex + 1, pageCount)
        if (loading) return

        val cardW = if (columns > 0) gridWidth / columns else gridWidth
        val cardH = (cardW * CARD_ASPECT).toInt().coerceAtLeast(1)
        val start = pageIndex * cardsPerPage
        val end = minOf(start + cardsPerPage, entries.size)
        val inflater = LayoutInflater.from(this)
        for (i in start until end) {
            val entry = entries[i]
            val card = inflater.inflate(R.layout.card_pick, null)
            bindCard(card, entry, i + 1)
            card.layoutParams = GridLayout.LayoutParams().apply {
                width = cardW
                height = cardH
            }
            card.setOnClickListener { onCardTapped(entry) }
            g.addView(card)
        }
    }

    private fun bindCard(card: View, entry: PickerModel.Entry, position: Int) {
        val label = card.findViewById<TextView>(R.id.cardLabel)
        val icon = card.findViewById<ImageView>(R.id.cardIcon)
        label.text = if (showingPages) {
            PickerModel.pageLabel(entry, position) { n -> getString(R.string.links_page_n, n) }
        } else {
            entry.label
        }
        icon.visibility =
            if (!showingPages && entry.kind == ExtensionContract.CATALOG_FOLDER) View.VISIBLE else View.GONE

        val chosen = selectedId != null && entry.id == selectedId
        val pad = intArrayOf(card.paddingLeft, card.paddingTop, card.paddingRight, card.paddingBottom)
        card.setBackgroundResource(if (chosen) R.drawable.btn_elevated_background else R.drawable.shape_bordered)
        card.setPadding(pad[0], pad[1], pad[2], pad[3])
        val ink = ContextCompat.getColorStateList(
            this,
            if (chosen) R.color.paperWhite else R.color.inkBlack,
        )
        label.setTextColor(ink)
        icon.imageTintList = ink
    }

    companion object {
        private const val TAG = "LinkPickerActivity"

        /** Above this the grid takes a third column — the library folder picker's own threshold. */
        private const val WIDE_GRID_DP = 480f

        /** Card height as a multiple of its width — the library's card proportion. */
        private const val CARD_ASPECT = 1.4f
    }
}
