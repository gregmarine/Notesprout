package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
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
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateToken
import com.symmetricalpalmtree.notesproutsn.databinding.ViewTemplateBrowserBinding
import com.symmetricalpalmtree.notesproutsn.library.FolderPickerActivity
import com.symmetricalpalmtree.notesproutsn.library.GridMath
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import com.symmetricalpalmtree.notesproutsn.library.SortRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The template browser** (arc 13 / G3) — one component, three hosts.
 *
 * G1 built this inside `TemplatesActivity`. G3's whole job is that it is no longer *inside*
 * anything: the same breadcrumbs, the same paginated non-scrolling grid, the same sort sheet and
 * the same long-press management now serve the Templates screen, the New Notebook screen and the
 * notebook's page-paper picker. A second copy of this logic — for the New Notebook screen, say —
 * is exactly the `RattaNotebookView` sibling-copy trap in a new place, so there is one.
 *
 * What a host supplies is the two things that genuinely differ:
 *
 *  - **[onPick]** — what a tap on a paper card means. The Templates screen shrugs (it is a library,
 *    not a picker), New Notebook ticks it and waits for Create, the notebook applies it and leaves.
 *    Folders are not picks; a folder is entered.
 *  - **[selection]** — which card, if any, is the paper in force. Two ways to say it, because the
 *    two callers know different things: New Notebook holds a [TemplatePick] and ticks by **card
 *    id**, while the notebook holds a page whose `.soil` row carries a **token** and ticks whatever
 *    card draws that paper. A null on both ticks nothing, which is the honest answer for a template
 *    row that has vanished (the unknown-stays-unknown rule).
 *
 * Everything else — the two kinds and no third, the sentinels that are not rows, the reserved
 * **Default** folder, the three shelves, and what may be long-pressed — is the browser's own and
 * the same wherever it is shown.
 *
 * **The shelves** (G5) are three flat, mutually exclusive views that cut across the folder tree:
 * Pinned, Recents and Search. A shelf has no path, so the breadcrumbs give way to its title and a
 * close ✕, and the three controls that act on a folder (Sort excepted) stand down. Nothing about a
 * shelf persists: the browser opens in the tree, at the root, every time and in every host — a
 * shelf is a glance you take, not a place to live, and a picker that opened onto one would have no
 * visible way back to the paper the page is actually using.
 */
class TemplateBrowser(
    private val activity: AppCompatActivity,
    private val binding: ViewTemplateBrowserBinding,
    private val onPick: (TemplatePick) -> Unit,
    private val selection: () -> Selection = { Selection() },
) {

    /** The paper in force, said either way round — see the class note. */
    data class Selection(val cardId: String? = null, val token: String? = null)

    private val repo = IndexRepository()
    private val sortPrefs = SortPrefs.templates(activity)

    /**
     * Import / export / re-fit (G4). It registers its own two `ActivityResultLauncher`s, so it is
     * built here and the "construct the browser in `onCreate`" rule covers it too.
     */
    private val transfer = TemplateTransfer(
        activity = activity,
        repo = repo,
        currentFolder = { folderId },
        onChanged = { reload() },
    )

    /**
     * The three flat views that cut across the tree (G5): Pinned, Recents, Search. Its own
     * component, beside this one rather than inside it — this class is about *where you are*, and
     * a shelf has no where.
     */
    private val shelf = TemplateShelfView(activity, repo, sortPrefs, onChanged = { reload() })

    /** null = the templates root · [ListIds.TEMPLATE_DEFAULT_ID] = the reserved folder · else a row. */
    private var folderId: String? = null

    /** The pinned ids, read once per [refresh] — every badge and the sheet's Pin/Unpin row come
     *  from this, so no card ever asks the index on its own. */
    private var pinnedIds: Set<String> = emptySet()

    /** Whether the host asked for its own close ✕ ([showCloseButton]) — shown from everywhere. */
    private var hostCloseWanted = false

    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<TemplateCard>()

    /**
     * The one-finger flip over the card grid — the pager buttons' gesture twin. Armed by the grid
     * container alone, which is what lets this browser sit inside New Notebook beside a name field
     * without a drag across that field turning the page.
     */
    private val listSwipe = ListSwipe(
        region = { binding.gridContainer },
        onFlipNext = { goToPage(pageIndex + 1) },
        onFlipPrevious = { goToPage(pageIndex - 1) },
    )
    private var grid: TemplateCardGrid? = null
    private var gridMeasured = false

    /** The page a miniature stands for — this device's portrait page, in pixels. */
    private val pageWidthPx: Int
    private val pageHeightPx: Int

    /** Registered here, so every host gets the Move picker by constructing the browser in
     *  `onCreate` — the one lifecycle rule this component imposes on the screens that hold it. */
    private val movePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) reload()
    }

    init {
        val metrics = activity.resources.displayMetrics
        pageWidthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = maxOf(metrics.widthPixels, metrics.heightPixels)
        wire()
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (gridMeasured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            gridMeasured = true
            grid = TemplateCardGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress).also {
                it.measure(activity, w, h)
                Slog.d(TAG) { "grid measured ${w}x$h → ${it.cardsPerPage} cards/page" }
            }
            reload()
        }
    }

    private fun wire() = with(binding) {
        btnSort.setOnClickListener { showSortSheet() }
        btnNewFolder.setOnClickListener { showNewFolderDialog() }
        btnImport.setOnClickListener { transfer.startImport() }
        // A shelf button toggles its own shelf: tapping Pinned while Pinned is up returns to the
        // folder you were in, so the same button is always the way out of what it opened.
        btnPinned.setOnClickListener { shelf.toggle(TemplateShelfView.Mode.PINNED) }
        btnRecents.setOnClickListener { shelf.toggle(TemplateShelfView.Mode.RECENTS) }
        btnSearch.setOnClickListener { shelf.openSearchDialog() }
        btnCloseShelf.setOnClickListener { shelf.close() }
        btnUp.setOnClickListener { navigateUp() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }

        listOf(btnSort, btnNewFolder, btnImport, btnPinned, btnRecents, btnSearch, btnCloseShelf,
               btnUp, btnFirst, btnPrev, btnNext, btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    // ── Host API ─────────────────────────────────────────────────────────────

    /**
     * The host forwards its `dispatchTouchEvent` here so a one-finger swipe over the grid flips the
     * page. Observer only — it consumes nothing, so cards keep their taps and long-presses.
     */
    fun onDispatchTouchEvent(ev: MotionEvent) = listSwipe.onTouchEvent(ev)

    /** Re-read the folder and redraw. Safe before the grid has measured — the measure reloads. */
    fun reload() {
        activity.lifecycleScope.launch { refresh() }
    }

    /** Redraw the visible page only — what a host calls when its [selection] changed and nothing
     *  in the database did (New Notebook ticking a different card). */
    fun refreshSelection() {
        activity.lifecycleScope.launch { bindCurrentPage() }
    }

    /**
     * Offer the host's own way out by tap (the Templates screen; the other two hosts have their own
     * headers). It is the bar's **only ✕**, and it is offered from everywhere — a shelf, a folder,
     * the root — because it means one thing that never changes: leave the screen. Stepping back
     * **one** layer is the head arrow's job ([TemplateBrowser] wires btnCloseShelf and btnUp there),
     * and the two read apart because one is an arrow at the head and one is a ✕ in the corner.
     */
    fun showCloseButton(onClose: () -> Unit) {
        hostCloseWanted = true
        binding.btnClose.setOnClickListener { onClose() }
        TooltipCompat.setTooltipText(binding.btnClose, binding.btnClose.contentDescription)
        binding.btnClose.visibility = View.VISIBLE
    }

    /** The host's `onSaveInstanceState` / `onCreate`, passed through to [TemplateTransfer] — the
     *  only browser state that must survive the host being killed behind a system picker. */
    fun saveState(outState: Bundle) = transfer.saveState(outState)

    fun restoreState(savedInstanceState: Bundle?) = transfer.restoreState(savedInstanceState)

    /**
     * Back, offered to the host: **true** when the browser consumed it by stepping out of a folder.
     * Back peels one layer at a time — a screen that dropped the user out of a five-deep folder tree
     * would make the tree not worth using.
     */
    fun onBackPressed(): Boolean {
        // The shelf is the outermost layer: it is what is on screen, and the folder underneath it
        // is where back will land next. Peeling both at once would drop the user two levels for one
        // press, which is the thing this method exists to prevent.
        if (shelf.isOpen) { shelf.close(); return true }
        if (folderId == null) return false
        navigateUp()
        return true
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private val inDefaults: Boolean get() = folderId == ListIds.TEMPLATE_DEFAULT_ID

    private suspend fun refresh() {
        renderChrome()
        // One read of the pinned list per refresh, whichever view is up: every badge and the
        // management sheet's Pin/Unpin row come from it. Unfiltered — resolving which of those ids
        // still stands for something is the shelf's job, and only the Pinned shelf has to care.
        pinnedIds = repo.pinnedTemplateIds().toSet()
        items = when {
            shelf.isOpen -> shelf.cards(pinnedIds)
            inDefaults -> TemplateLibrary.defaultCards(
                activity.getString(R.string.template_lined),
                activity.getString(R.string.template_dotted),
                activity.getString(R.string.template_grid),
            )
            folderId == null -> TemplateLibrary.rootCards(
                activity.getString(R.string.template_blank),
                activity.getString(R.string.template_default_folder),
                sortedRows(null),
            )
            else -> TemplateLibrary.rowCards(sortedRows(folderId))
        }

        binding.emptyState.setText(shelf.emptyTextRes())
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridMath.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    /** The rows in one folder, folders before templates, in the screen's own sort. */
    private suspend fun sortedRows(parentId: String?): List<ObjectSummary> = SortRules.foldersFirst(
        repo.templateFolders(parentId) + repo.templates(parentId),
        sortPrefs.field,
        sortPrefs.order,
        folderType = ObjectType.TEMPLATE_FOLDER,
    )

    /**
     * Bind this page's cards, rendering only the miniatures it needs — off Main, because a page of
     * cards is a page of bitmaps. A static template's stored pixels are read here too, and only
     * here: the listing itself is blob-free.
     *
     * The tick rides on the **same** read. An imported card's token is a digest of its bytes
     * ([TemplateToken.ofImage]), and those bytes are already in hand for the miniature — so
     * answering "is this the paper the page is using" costs nothing beyond what the card cost
     * anyway. Re-reading them to tick would double every page turn's IO.
     */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val range = GridMath.pageRange(pageIndex, g.cardsPerPage, items.size)
        val visible = range.map { items[it] }
        val chosen = selection()
        val work = withContext(Dispatchers.IO) {
            val art = HashMap<String, Bitmap?>(visible.size)
            val ticked = HashSet<String>(4)
            // A pick host that ticks by TOKEN has to compare an imported card's digest, and the
            // digest is of the bytes — so those cards pay for their pixels on every bind. A host
            // that ticks by CARD ID (New Notebook) never needs them, and a tap there only moves the
            // tick: without the cache probe below, every tap would re-read every visible template's
            // blob out of SQLCipher to hand it to a thumbnail that is already drawn.
            val needsToken = chosen.token != null
            for (card in visible) {
                // Only an imported picture needs its bytes; a built-in paper is drawn from
                // arithmetic, so its miniature costs no read at all.
                val image = (card as? TemplateCard.Static)?.takeIf { it.isImage }
                    ?.takeIf { needsToken || !TemplateThumbnails.isCached(card, g.cardWidth) }
                    ?.let { runCatching { repo.templateImage(it.id) }.getOrNull() }
                art[card.id] = TemplateThumbnails.bitmap(
                    card, g.cardWidth, pageWidthPx, pageHeightPx, dpi(), image,
                )
                if (isChosen(card, chosen, image)) ticked.add(card.id)
            }
            art to ticked
        }
        g.bind(items, pageIndex, work.first, work.second, pinnedIds)
        renderPager()
    }

    /**
     * Whether [card] is the paper in force. By id first — a host that knows which card it picked
     * needs no pixels to say so — then by token, which is how a *page* recognises its own paper
     * without ever having heard of the library row it came from (it never did: the pixels were
     * copied into the `.soil` at apply time).
     */
    private fun isChosen(card: TemplateCard, chosen: Selection, image: ByteArray?): Boolean {
        if (chosen.cardId != null && chosen.cardId == card.id) return true
        val token = chosen.token ?: return false
        return tokenOf(card, image) == token
    }

    /** The `.soil` token [card] would be filed under, or null for a place (folders draw no paper). */
    private fun tokenOf(card: TemplateCard, image: ByteArray?): String? = when (card) {
        is TemplateCard.Blank -> ""
        is TemplateCard.BuiltIn -> TemplateToken.of(card.kind)
        is TemplateCard.Static -> card.baseKind?.let { TemplateToken.of(it) }
            ?: image?.let { TemplateToken.ofImage(it, card.fit) }
        is TemplateCard.Folder, is TemplateCard.Defaults -> null
    }

    private fun dpi(): Float = activity.resources.displayMetrics.densityDpi.toFloat()

    // ── Chrome ───────────────────────────────────────────────────────────────

    /**
     * Inside **Default** the contents are fixed — three built-in papers, in one order, forever — so
     * Sort, New folder and Import have nothing to act on and all three stand down. GONE, never
     * `isEnabled = false`: a disabled control is invisible on e-ink and reads as a broken one.
     */
    private fun renderChrome() = with(binding) {
        val inShelf = shelf.isOpen
        // A shelf has no folder to create into or import into either — and unlike Default it has no
        // path at all, so btnUp and the breadcrumbs go with them. Sort stays: the pinned and search
        // shelves are ordered by it, and it is the one control that still has something to act on.
        val fixed = inDefaults || inShelf
        // `inDefaults && !inShelf`, not `inDefaults`: opening a shelf does not clear the folder you
        // opened it from, so gating on Default alone hid Sort on a shelf raised from inside it —
        // a shelf that IS ordered by the sort prefs, with no way to change them.
        //
        // The **search** shelf is the exception, and the one shelf the sort does not order: since
        // arc 20 it is ranked by relevance, so a Sort control there could only fight the ordering it
        // cannot change. GONE, never disabled — invisible on e-ink either way.
        val searching = shelf.mode == TemplateShelfView.Mode.SEARCH
        btnSort.visibility = if ((inDefaults && !inShelf) || searching) View.GONE else View.VISIBLE
        btnNewFolder.visibility = if (fixed) View.GONE else View.VISIBLE
        // Import goes too: the Default folder is the app's paper, and the arc reserves it against
        // anything landing inside. A button that could only refuse itself is not a button.
        btnImport.visibility = if (fixed) View.GONE else View.VISIBLE
        btnPinned.isSelected = shelf.mode == TemplateShelfView.Mode.PINNED
        btnRecents.isSelected = shelf.mode == TemplateShelfView.Mode.RECENTS
        btnSearch.isSelected = shelf.mode == TemplateShelfView.Mode.SEARCH

        breadcrumbScroll.visibility = if (inShelf) View.GONE else View.VISIBLE
        shelfTitle.visibility = if (inShelf) View.VISIBLE else View.GONE
        btnCloseShelf.visibility = if (inShelf) View.VISIBLE else View.GONE
        btnClose.visibility = if (hostCloseWanted) View.VISIBLE else View.GONE
        if (inShelf) {
            btnUp.visibility = View.GONE
            shelfTitle.text = shelf.title()
        } else {
            renderBreadcrumb()
        }
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(activity, R.color.inkBlack)
        activity.lifecycleScope.launch {
            // The Default folder has no row, so it is appended by hand rather than walked to.
            val ancestry = if (inDefaults) emptyList() else repo.ancestry(folderId, ObjectType.TEMPLATE_FOLDER)
            val container = binding.breadcrumbContainer
            container.removeAllViews()
            container.addView(crumb(activity.getString(R.string.templates_title), ink) { navigateTo(null) })
            for (ref in ancestry) {
                container.addView(separator(ink))
                container.addView(crumb(ref.name, ink) { navigateTo(ref.id) })
            }
            if (inDefaults) {
                container.addView(separator(ink))
                container.addView(crumb(activity.getString(R.string.template_default_folder), ink) {})
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
    }

    private fun crumb(label: String, color: Int, onClick: () -> Unit): TextView {
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun separator(color: Int): TextView = TextView(activity).apply {
        text = " / "
        textSize = 16f
        setTextColor(color)
    }

    private fun renderPager() {
        // INVISIBLE, not GONE: the pager keeps its slot so the bar's other controls never shift.
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = activity.getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun navigateTo(id: String?) {
        folderId = id
        // Going somewhere is being in the tree. Nothing reaches here from a shelf today (a shelf
        // holds no folder cards and hides btnUp), but a shelf left standing over a breadcrumb
        // navigation would show one place and list another. reset(), not close(): the reload
        // below is the redraw, and close()'s own would race it over the same fields.
        shelf.reset()
        pageIndex = 0
        reload()
    }

    private fun navigateUp() {
        val current = folderId ?: return
        // Default hangs off the root and has no row to walk from.
        if (current == ListIds.TEMPLATE_DEFAULT_ID) { navigateTo(null); return }
        activity.lifecycleScope.launch {
            val ancestry = repo.ancestry(current, ObjectType.TEMPLATE_FOLDER)
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        activity.lifecycleScope.launch { bindCurrentPage() }
    }

    // ── Cards ────────────────────────────────────────────────────────────────

    /** A folder is entered; anything that is paper is a pick, and what a pick *means* is the
     *  host's. Two kinds and no third, so this has exactly two outcomes. */
    private fun onCardTap(item: TemplateCard) {
        when (item) {
            is TemplateCard.Folder -> navigateTo(item.summary.id)
            is TemplateCard.Defaults -> navigateTo(ListIds.TEMPLATE_DEFAULT_ID)
            else -> TemplatePick.of(item)?.let(onPick)
        }
    }

    /**
     * The management sheet.
     *
     * A **static template** and a **folder** are rows, and get the full sheet. A **built-in paper**
     * is the app's own and cannot be renamed, moved, duplicated, re-fitted, exported or deleted —
     * but it *can* be pinned, so as of G5 it long-presses to exactly that one row.
     *
     * That reverses G1's "the built-in papers do not long-press at all", on the user's call, and
     * for G1's own reason: the rule was written because the sentinel's only candidate row then was
     * *Template options…*, which opened the abandoned G2, and **a row that opens nothing is worse
     * than no row**. Pin is a row that does something. **Blank and the Default folder still do not
     * long-press** — Blank is not pinnable (it is already the first card at the root, forever) and
     * a folder is a place.
     */
    private fun onCardLongPress(item: TemplateCard) {
        val sheet = ActionSheetDialog(activity).title(item.name)
        when (item) {
            is TemplateCard.BuiltIn -> sheet.addPinRow(item.id).show()

            is TemplateCard.Folder -> sheet
                .addAction(R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameDialog(item.summary) }
                .addAction(R.drawable.ic_move_folder, activity.getString(R.string.action_move)) { showMovePicker(item.summary) }
                .addAction(R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteFolder(item.summary) }
                .show()

            is TemplateCard.Static -> sheet
                .addPinRow(item.id)
                .addAction(R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameDialog(item.summary) }
                .addAction(R.drawable.ic_move_folder, activity.getString(R.string.action_move)) { showMovePicker(item.summary) }
                // Duplicate lands a copy beside the original, in the original's folder — which a
                // shelf is not standing in and does not show. In the tree the new card appears
                // under your finger; on a shelf it would look like a row that did nothing, so it
                // stands down there with New folder and Import.
                .also { if (!shelf.isOpen) it.addAction(R.drawable.ic_copy, activity.getString(R.string.action_duplicate)) { duplicate(item.summary) } }
                // Fit is only a question for imported pixels — a static row carrying a base kind is
                // drawn from arithmetic and already fills the page exactly.
                .also { if (item.isImage) it.addAction(R.drawable.ic_aspect_ratio, activity.getString(R.string.action_fit)) { transfer.chooseFit(item.summary) } }
                .addAction(R.drawable.ic_download, activity.getString(R.string.action_export)) { transfer.export(item.summary) }
                .addAction(R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteTemplate(item.summary) }
                .show()

            else -> Unit
        }
    }

    /**
     * Pin / Unpin, worded from [pinnedIds] — the listing's own read, not a fresh index query, so
     * the sheet can never disagree with the badge the user is looking at.
     */
    private fun ActionSheetDialog.addPinRow(cardId: String): ActionSheetDialog {
        val pinned = cardId in pinnedIds
        val label = if (pinned) R.string.action_unpin else R.string.action_pin
        return addAction(R.drawable.ic_pinned, activity.getString(label)) { togglePin(cardId, pinned) }
    }

    private fun togglePin(cardId: String, currentlyPinned: Boolean) {
        activity.lifecycleScope.launch {
            if (currentlyPinned) repo.unpinTemplate(cardId) else repo.pinTemplate(cardId)
            Slog.d(TAG) { "template ${if (currentlyPinned) "unpinned" else "pinned"}" }
            refresh()
        }
    }

    // ── New folder / rename ──────────────────────────────────────────────────

    private fun showNewFolderDialog() {
        // The library's NewFolderFlow carries a naming scheme with it — a rule about what notebooks
        // get called inside a folder, which means nothing here. Same dialog, same validation order
        // (charset → reserved → duplicate), one field.
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.new_folder_title,
            confirmRes = R.string.new_notebook_create,
            hintRes = R.string.new_folder_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            if (rejectName(name, folderId)) return@show
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    if (repo.nameTaken(folderId, ObjectType.TEMPLATE_FOLDER, name)) {
                        Dialogs.problem(
                            activity, R.string.name_problem_title,
                            activity.getString(R.string.new_template_folder_duplicate, name),
                        )
                        return@launch
                    }
                    repo.createTemplateFolder(name, folderId)
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    private fun showRenameDialog(s: ObjectSummary) {
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.rename_title,
            confirmRes = R.string.action_rename,
            initial = s.name,
        ) { name, dismiss ->
            if (accepting) return@show
            if (name == s.name) { dismiss(); return@show }
            if (rejectName(name, s.parentId)) return@show
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    // Excluding the row itself: re-casing its own name is a rename, not a collision.
                    if (repo.nameTaken(s.parentId, s.type, name, s.id)) {
                        val msg = if (s.type == ObjectType.TEMPLATE) R.string.rename_duplicate_template
                                  else R.string.rename_duplicate_template_folder
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(msg, name))
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

    /**
     * The name rules, in order, with their dialogs: the family charset first, then the reserved
     * root name. True when the name was refused and the dialog must stay open.
     */
    private fun rejectName(name: String, parentId: String?): Boolean {
        NameRules.validate(name)?.let { problem ->
            Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
            return true
        }
        if (TemplateLibrary.isReservedName(parentId, name)) {
            Dialogs.problem(
                activity, R.string.name_problem_title,
                activity.getString(R.string.template_name_reserved, TemplateLibrary.RESERVED_ROOT_NAME),
            )
            return true
        }
        return false
    }

    // ── Duplicate / delete / move ────────────────────────────────────────────

    /**
     * A copy beside the original, named by the pure rule ([TemplateLibrary.duplicateName]) against
     * the sibling names read in the same breath — a static template is *baked*, so duplicating one
     * is how you keep a version before editing the name or moving it somewhere else.
     */
    private fun duplicate(s: ObjectSummary) {
        activity.lifecycleScope.launch {
            val taken = (repo.templates(s.parentId) + repo.templateFolders(s.parentId)).map { it.name }.toSet()
            val name = TemplateLibrary.duplicateName(s.name, taken)
            val row = repo.duplicateTemplate(s.id, name)
            if (row == null) {
                Dialogs.problem(
                    activity,
                    R.string.template_duplicate_gone_title,
                    R.string.template_duplicate_gone_body,
                )
            }
            refresh()
        }
    }

    private fun confirmDeleteTemplate(s: ObjectSummary) = confirm(
        titleRes = R.string.delete_template_title,
        bodyRes = R.string.delete_template_body,
        name = s.name,
    ) {
        activity.lifecycleScope.launch {
            // The pin edge goes with the row (deleteTemplate scrubs it); the recents entry is in
            // prefs and has to be removed by hand. The shelf's own read would prune it eventually,
            // but "eventually" here means "the next time the user opens Recents", and until then
            // the id is a live pointer at a dead row.
            repo.deleteTemplate(s.id)
            shelf.forget(listOf(s.id))
            refresh()
        }
    }

    private fun confirmDeleteFolder(s: ObjectSummary) = confirm(
        titleRes = R.string.delete_template_folder_title,
        bodyRes = R.string.delete_template_folder_body,
        name = s.name,
    ) {
        activity.lifecycleScope.launch {
            // The ids first: the cascade soft-deletes them, and afterwards there is no way to ask
            // the index which templates were inside. Their pin edges are scrubbed by the cascade
            // itself; their recents entries are prefs, and this is the only chance to name them.
            val inside = templateIdsUnder(s.id)
            val removed = repo.deleteTemplateFolderRecursive(s.id)
            shelf.forget(inside)
            Slog.d(TAG) { "deleted folder ${s.id} with $removed templates" }
            // Standing inside the folder that just went: step out to where it used to be.
            if (folderId == s.id) navigateTo(s.parentId) else refresh()
        }
    }

    /**
     * Every static template under [folderId], at any depth — read **before** a recursive delete,
     * because afterwards the rows are soft-deleted and the listing calls can no longer see them.
     * Cycle-guarded the way the repository's own cascade is: a corrupt `parentId` costs a bounded
     * walk rather than a hang.
     */
    private suspend fun templateIdsUnder(folderId: String): List<String> {
        val found = mutableListOf<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add(folderId) }
        while (stack.isNotEmpty()) {
            val fid = stack.removeLast()
            if (!seen.add(fid)) continue
            found += repo.templates(fid).map { it.id }
            repo.templateFolders(fid).forEach { stack.add(it.id) }
        }
        return found
    }

    private fun confirm(titleRes: Int, bodyRes: Int, name: String, onConfirm: () -> Unit) {
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(titleRes, name))
                .setMessage(bodyRes)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /** The library's move picker, walking template folders instead of notebook ones. */
    private fun showMovePicker(s: ObjectSummary) = movePickerLauncher.launch(
        FolderPickerActivity.intent(
            activity, s.id, s.type, s.name, s.parentId,
            browseFolderType = ObjectType.TEMPLATE_FOLDER,
            rootLabel = activity.getString(R.string.templates_title),
        )
    )

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun showSortSheet() {
        val field = sortPrefs.field
        val order = sortPrefs.order
        fun tick(f: SortField, o: SortOrder) = if (field == f && order == o) R.drawable.ic_check else null
        ActionSheetDialog(activity)
            .title(activity.getString(R.string.cd_sort))
            .addAction(tick(SortField.NAME, SortOrder.ASC), activity.getString(R.string.sort_name_asc)) {
                applySort(SortField.NAME, SortOrder.ASC)
            }
            .addAction(tick(SortField.NAME, SortOrder.DESC), activity.getString(R.string.sort_name_desc)) {
                applySort(SortField.NAME, SortOrder.DESC)
            }
            .addAction(tick(SortField.MODIFIED, SortOrder.ASC), activity.getString(R.string.sort_modified_asc)) {
                applySort(SortField.MODIFIED, SortOrder.ASC)
            }
            .addAction(tick(SortField.MODIFIED, SortOrder.DESC), activity.getString(R.string.sort_modified_desc)) {
                applySort(SortField.MODIFIED, SortOrder.DESC)
            }
            .show()
    }

    private fun applySort(field: SortField, order: SortOrder) {
        sortPrefs.field = field
        sortPrefs.order = order
        pageIndex = 0
        reload()
    }

    private companion object {
        const val TAG = "TemplateBrowser"
    }
}
