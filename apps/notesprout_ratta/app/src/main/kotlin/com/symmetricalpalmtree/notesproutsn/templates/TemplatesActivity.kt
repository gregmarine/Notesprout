package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityTemplatesBinding
import com.symmetricalpalmtree.notesproutsn.library.FolderPickerActivity
import com.symmetricalpalmtree.notesproutsn.library.GridMath
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import com.symmetricalpalmtree.notesproutsn.library.SortRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Templates** — the template library (arc 13 / G1). The library screen's shape applied to paper:
 * breadcrumbs, the same paginated non-scrolling card grid, the same sort sheet, the same long-press
 * management. What is different is what a card *is*.
 *
 * Two kinds and no third. A **generator** (Lined / Dotted / Grid) is a recipe the app draws; a
 * **static template** is pixels the library keeps. The generators, the **Generated** folder that
 * holds them, and the **Blank** card at the root are hardcoded sentinel ids ([TemplateLibrary]) —
 * not rows. Nothing is seeded at bootstrap, nothing about them can be deleted or renamed, an index
 * restored from a backup needs no repair, and there is no migration. The database is asked only
 * about folders and templates the user actually made.
 *
 * Cards are **true miniatures**: the card is the page, scaled honestly at the page's own aspect
 * ([TemplateThumbnails]). Density is what tells two variants apart, so the card has to show it.
 *
 * In G1 there is nothing to pick yet — this browses, creates and manages. G3 turns the same grid
 * into the one browser behind New Notebook and a page's paper as well.
 */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplatesBinding
    private lateinit var sortPrefs: SortPrefs
    private val repo by lazy { IndexRepository() }

    /** null = the templates root · [ListIds.TEMPLATE_GENERATED_ID] = the reserved folder · else a row. */
    private var folderId: String? = null

    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<TemplateCard>()
    private var grid: TemplateCardGrid? = null
    private var gridMeasured = false

    /** The page a miniature stands for — this device's portrait page, in pixels. */
    private var pageWidthPx = 0
    private var pageHeightPx = 0

    private val movePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) lifecycleScope.launch { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        sortPrefs = SortPrefs.templates(this)
        val metrics = resources.displayMetrics
        pageWidthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = maxOf(metrics.widthPixels, metrics.heightPixels)

        wireBars()

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (gridMeasured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            gridMeasured = true
            grid = TemplateCardGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress).also {
                it.measure(this, w, h)
                Slog.d(TAG) { "grid measured ${w}x$h → ${it.cardsPerPage} cards/page" }
            }
            lifecycleScope.launch { refresh() }
        }
    }

    private fun wireBars() = with(binding) {
        btnClose.setOnClickListener { finish() }
        btnSort.setOnClickListener { showSortSheet() }
        btnNewFolder.setOnClickListener { showNewFolderDialog() }
        btnUp.setOnClickListener { navigateUp() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }

        listOf(btnClose, btnSort, btnNewFolder, btnUp, btnFirst, btnPrev, btnNext, btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private val inGenerated: Boolean get() = folderId == ListIds.TEMPLATE_GENERATED_ID

    private suspend fun refresh() {
        renderChrome()
        items = when {
            inGenerated -> TemplateLibrary.generatedCards(
                getString(R.string.template_lined),
                getString(R.string.template_dotted),
                getString(R.string.template_grid),
            )
            folderId == null -> TemplateLibrary.rootCards(
                getString(R.string.template_blank),
                getString(R.string.template_generated_folder),
                sortedRows(null),
            )
            else -> TemplateLibrary.rowCards(sortedRows(folderId))
        }

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
     */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val range = GridMath.pageRange(pageIndex, g.cardsPerPage, items.size)
        val visible = range.map { items[it] }
        val art = withContext(Dispatchers.IO) {
            HashMap<String, Bitmap?>(visible.size).apply {
                for (card in visible) {
                    // Only an imported picture needs its bytes; a generator (and a saved variant of
                    // one) is drawn from arithmetic, so its miniature costs no read at all.
                    val image = (card as? TemplateCard.Static)?.takeIf { it.isImage }
                        ?.let { runCatching { repo.templateImage(it.id) }.getOrNull() }
                    put(
                        card.id,
                        TemplateThumbnails.bitmap(card, g.cardWidth, pageWidthPx, pageHeightPx, dpi(), image),
                    )
                }
            }
        }
        g.bind(items, pageIndex, art)
        renderPager()
    }

    private fun dpi(): Float = resources.displayMetrics.densityDpi.toFloat()

    // ── Chrome ───────────────────────────────────────────────────────────────

    /**
     * Inside **Generated** the contents are fixed — three generators, in one order, forever — so
     * neither Sort nor New folder has anything to act on and both stand down. GONE, never
     * `isEnabled = false`: a disabled control is invisible on e-ink and reads as a broken one.
     */
    private fun renderChrome() = with(binding) {
        val fixed = inGenerated
        btnSort.visibility = if (fixed) View.GONE else View.VISIBLE
        btnNewFolder.visibility = if (fixed) View.GONE else View.VISIBLE
        renderBreadcrumb()
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        lifecycleScope.launch {
            // The Generated folder has no row, so it is appended by hand rather than walked to.
            val ancestry = if (inGenerated) emptyList() else repo.ancestry(folderId, ObjectType.TEMPLATE_FOLDER)
            val container = binding.breadcrumbContainer
            container.removeAllViews()
            container.addView(crumb(getString(R.string.templates_title), ink) { navigateTo(null) })
            for (ref in ancestry) {
                container.addView(separator(ink))
                container.addView(crumb(ref.name, ink) { navigateTo(ref.id) })
            }
            if (inGenerated) {
                container.addView(separator(ink))
                container.addView(crumb(getString(R.string.template_generated_folder), ink) {})
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
    }

    private fun crumb(label: String, color: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

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

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun navigateTo(id: String?) {
        folderId = id
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        val current = folderId ?: return
        // Generated hangs off the root and has no row to walk from.
        if (current == ListIds.TEMPLATE_GENERATED_ID) { navigateTo(null); return }
        lifecycleScope.launch {
            val ancestry = repo.ancestry(current, ObjectType.TEMPLATE_FOLDER)
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        lifecycleScope.launch { bindCurrentPage() }
    }

    /** Back peels one layer: up a folder, then out of the screen. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (folderId != null) navigateUp() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── Cards ────────────────────────────────────────────────────────────────

    private fun onCardTap(item: TemplateCard) {
        when (item) {
            is TemplateCard.Folder -> navigateTo(item.summary.id)
            is TemplateCard.Generated -> navigateTo(ListIds.TEMPLATE_GENERATED_ID)
            // Nothing to pick yet: G3 gives a tap its meaning, in all three hosts at once.
            else -> Unit
        }
    }

    /**
     * The management sheet. Only the two card kinds that *are* rows have one — a sentinel cannot be
     * renamed, moved or deleted, so it does not long-press at all. The generator's own
     * **Template options…** row lands with the options screen it opens (G2).
     */
    private fun onCardLongPress(item: TemplateCard) {
        val sheet = ActionSheetDialog(this).title(item.name)
        when (item) {
            is TemplateCard.Folder -> sheet
                .addAction(R.drawable.ic_edit, getString(R.string.action_rename)) { showRenameDialog(item.summary) }
                .addAction(R.drawable.ic_move_folder, getString(R.string.action_move)) { showMovePicker(item.summary) }
                .addAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteFolder(item.summary) }
                .show()

            is TemplateCard.Static -> sheet
                .addAction(R.drawable.ic_edit, getString(R.string.action_rename)) { showRenameDialog(item.summary) }
                .addAction(R.drawable.ic_move_folder, getString(R.string.action_move)) { showMovePicker(item.summary) }
                .addAction(R.drawable.ic_copy, getString(R.string.action_duplicate)) { duplicate(item.summary) }
                .addAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteTemplate(item.summary) }
                .show()

            else -> Unit
        }
    }

    // ── New folder / rename ──────────────────────────────────────────────────

    private fun showNewFolderDialog() {
        // The library's NewFolderFlow carries a naming scheme with it — a rule about what notebooks
        // get called inside a folder, which means nothing here. Same dialog, same validation order
        // (charset → reserved → duplicate), one field.
        var accepting = false
        NameDialog.show(
            this,
            titleRes = R.string.new_folder_title,
            confirmRes = R.string.new_notebook_create,
            hintRes = R.string.new_folder_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            if (rejectName(name, folderId)) return@show
            accepting = true
            lifecycleScope.launch {
                try {
                    if (repo.nameTaken(folderId, ObjectType.TEMPLATE_FOLDER, name)) {
                        Dialogs.problem(
                            this@TemplatesActivity, R.string.name_problem_title,
                            getString(R.string.new_template_folder_duplicate, name),
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
            this,
            titleRes = R.string.rename_title,
            confirmRes = R.string.action_rename,
            initial = s.name,
        ) { name, dismiss ->
            if (accepting) return@show
            if (name == s.name) { dismiss(); return@show }
            if (rejectName(name, s.parentId)) return@show
            accepting = true
            lifecycleScope.launch {
                try {
                    // Excluding the row itself: re-casing its own name is a rename, not a collision.
                    if (repo.nameTaken(s.parentId, s.type, name, s.id)) {
                        val msg = if (s.type == ObjectType.TEMPLATE) R.string.rename_duplicate_template
                                  else R.string.rename_duplicate_template_folder
                        Dialogs.problem(this@TemplatesActivity, R.string.name_problem_title, getString(msg, name))
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
            Dialogs.problem(this, R.string.name_problem_title, NameDialog.problemMessage(this, problem))
            return true
        }
        if (TemplateLibrary.isReservedName(parentId, name)) {
            Dialogs.problem(
                this, R.string.name_problem_title,
                getString(R.string.template_name_reserved, TemplateLibrary.RESERVED_ROOT_NAME),
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
        lifecycleScope.launch {
            val taken = (repo.templates(s.parentId) + repo.templateFolders(s.parentId)).map { it.name }.toSet()
            val name = TemplateLibrary.duplicateName(s.name, taken)
            val row = repo.duplicateTemplate(s.id, name)
            if (row == null) {
                Dialogs.problem(
                    this@TemplatesActivity,
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
        lifecycleScope.launch {
            repo.deleteTemplate(s.id)
            refresh()
        }
    }

    private fun confirmDeleteFolder(s: ObjectSummary) = confirm(
        titleRes = R.string.delete_template_folder_title,
        bodyRes = R.string.delete_template_folder_body,
        name = s.name,
    ) {
        lifecycleScope.launch {
            val removed = repo.deleteTemplateFolderRecursive(s.id)
            Slog.d(TAG) { "deleted folder ${s.id} with $removed templates" }
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

    /** The library's move picker, walking template folders instead of notebook ones. */
    private fun showMovePicker(s: ObjectSummary) = movePickerLauncher.launch(
        FolderPickerActivity.intent(
            this, s.id, s.type, s.name, s.parentId,
            browseFolderType = ObjectType.TEMPLATE_FOLDER,
            rootLabel = getString(R.string.templates_title),
        )
    )

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

    companion object {
        private const val TAG = "TemplatesActivity"

        fun intent(context: Context): Intent = Intent(context, TemplatesActivity::class.java)
    }
}
