package com.symmetricalpalmtree.notesprout.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.crypto.DerivedKeyStore
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseMode
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesprout.data.prefs.SortField
import com.symmetricalpalmtree.notesprout.data.prefs.SortOrder
import com.symmetricalpalmtree.notesprout.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesprout.data.soilFile
import com.symmetricalpalmtree.notesprout.data.sidecarsOf
import com.symmetricalpalmtree.notesprout.databinding.ActivityLibraryBinding
import com.symmetricalpalmtree.notesprout.notebook.NotebookActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var browseState: BrowseState
    private lateinit var sortPrefs: SortPrefs
    private lateinit var recentsPrefs: RecentsPrefs
    private val repo by lazy { IndexRepository() }

    private var folderId: String? = null
    private var mode: BrowseMode = BrowseMode.NORMAL
    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<CardItem>()
    private var grid: LibraryGrid? = null
    private var gridMeasured = false
    private var coldLaunch = false

    private val newNotebookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(NewNotebookActivity.EXTRA_NOTEBOOK_ID)
            val name = result.data?.getStringExtra(NewNotebookActivity.EXTRA_NOTEBOOK_NAME)
            if (id != null && name != null) {
                recentsPrefs.record(id)
                startActivity(NotebookActivity.intent(this, id, name))
            }
            lifecycleScope.launch { refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        browseState = BrowseState(this)
        sortPrefs = SortPrefs(this)
        recentsPrefs = RecentsPrefs(this)
        folderId = browseState.folderId
        mode = browseState.mode
        coldLaunch = savedInstanceState == null

        wireBars()
        DebugMenu.install(this, binding.breadcrumbBar)

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (!gridMeasured && binding.gridContainer.width > 0 && binding.gridContainer.height > 0) {
                gridMeasured = true
                grid = LibraryGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress)
                grid!!.measure(this, binding.gridContainer.width, binding.gridContainer.height)
                lifecycleScope.launch {
                    repo.ensurePinnedListExists()
                    if (folderId != null && repo.alive(folderId!!) == null) {
                        folderId = null
                        browseState.folderId = null
                    }
                    if (coldLaunch) reopenLastNotebookIfNeeded()
                    refresh()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (gridMeasured) lifecycleScope.launch { refresh() }
    }

    private fun wireBars() {
        // A mode button toggles its mode on/off; while in a mode the other button switches to it.
        binding.btnPinned.setOnClickListener {
            setMode(if (mode == BrowseMode.PINNED) BrowseMode.NORMAL else BrowseMode.PINNED)
        }
        binding.btnRecents.setOnClickListener {
            setMode(if (mode == BrowseMode.RECENTS) BrowseMode.NORMAL else BrowseMode.RECENTS)
        }
        binding.btnCloseMode.setOnClickListener { setMode(BrowseMode.NORMAL) }

        binding.btnSort.setOnClickListener { showSortSheet() }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }
        binding.btnNewNotebook.setOnClickListener {
            newNotebookLauncher.launch(NewNotebookActivity.intent(this, folderId))
        }
        binding.btnUp.setOnClickListener { navigateUp() }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }

        listOf(binding.btnPinned, binding.btnRecents, binding.btnCloseMode, binding.btnSort,
               binding.btnNewFolder, binding.btnNewNotebook, binding.btnUp, binding.btnFirst,
               binding.btnPrev, binding.btnNext, binding.btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    private fun setMode(newMode: BrowseMode) {
        if (mode == newMode) return
        mode = newMode
        browseState.mode = newMode
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    /**
     * Cold-launch reopen: if a notebook was open when the app last died/closed, relaunch it on top of
     * the library — but only when its index row is still alive **and** its `.soil` exists (never mint a
     * ghost file). The id is read once and cleared regardless of outcome.
     */
    private fun reopenLastNotebookIfNeeded() {
        val id = browseState.lastOpenNotebookId ?: return
        browseState.lastOpenNotebookId = null
        lifecycleScope.launch {
            val s = repo.alive(id) ?: return@launch
            val exists = withContext(Dispatchers.IO) { soilFile(this@LibraryActivity, id).exists() }
            if (!exists) return@launch
            startActivity(NotebookActivity.intent(this@LibraryActivity, s.id, s.name))
        }
    }

    private suspend fun refresh() {
        renderChrome()
        val pinnedIds = repo.pinnedNotebookIds().toSet()
        items = when (mode) {
            BrowseMode.NORMAL -> buildNormalItems(pinnedIds)
            BrowseMode.PINNED -> buildPinnedItems(pinnedIds)
            BrowseMode.RECENTS -> buildRecentsItems(pinnedIds)
        }

        val total = items.size
        binding.emptyState.setText(
            when (mode) {
                BrowseMode.NORMAL -> R.string.library_empty
                BrowseMode.PINNED -> R.string.library_pinned_empty
                BrowseMode.RECENTS -> R.string.library_recents_empty
            }
        )
        binding.emptyState.visibility = if (total == 0) View.VISIBLE else View.GONE

        val perPage = grid?.cardsPerPage ?: 1
        pageCount = if (total == 0) 1 else (total - 1) / perPage + 1
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        renderPager()
        grid?.bind(items, pageIndex)
    }

    private suspend fun buildNormalItems(pinnedIds: Set<String>): List<CardItem> {
        val sf = sortPrefs.field
        val so = sortPrefs.order
        val sortedFolders = sortItems(repo.folders(folderId), sf, so)
        val sortedNotebooks = sortItems(repo.notebooks(folderId), sf, so)
        val out = mutableListOf<CardItem>()
        for (f in sortedFolders) out.add(CardItem.Folder(f))
        for (nb in sortedNotebooks) {
            val cover = withContext(Dispatchers.IO) { repo.cover(nb.id) }
            out.add(CardItem.Notebook(nb, cover, pinned = nb.id in pinnedIds))
        }
        return out
    }

    /** Pinned mode: flat grid of pinned notebooks in the current sort. */
    private suspend fun buildPinnedItems(pinnedIds: Set<String>): List<CardItem> {
        val summaries = pinnedIds.mapNotNull { repo.alive(it) }.filter { it.type == ObjectType.NOTEBOOK }
        val sorted = sortItems(summaries, sortPrefs.field, sortPrefs.order)
        return sorted.map { nb ->
            val cover = withContext(Dispatchers.IO) { repo.cover(nb.id) }
            CardItem.Notebook(nb, cover, pinned = true)
        }
    }

    /** Recents mode: newest-first, parent-folder subtitle, dead ids pruned on read. */
    private suspend fun buildRecentsItems(pinnedIds: Set<String>): List<CardItem> {
        val folderNames = HashMap<String?, String>()
        suspend fun parentName(parentId: String?): String {
            if (parentId == null) return getString(R.string.recents_parent_root)
            return folderNames.getOrPut(parentId) { repo.summary(parentId)?.name ?: getString(R.string.recents_parent_root) }
        }
        val out = mutableListOf<CardItem>()
        val aliveIds = mutableSetOf<String>()
        for (entry in recentsPrefs.entries()) {
            val nb = repo.alive(entry.notebookId)?.takeIf { it.type == ObjectType.NOTEBOOK } ?: continue
            aliveIds.add(nb.id)
            val cover = withContext(Dispatchers.IO) { repo.cover(nb.id) }
            out.add(CardItem.Notebook(nb, cover, pinned = nb.id in pinnedIds, subtitle = parentName(nb.parentId)))
        }
        recentsPrefs.pruneDeleted(aliveIds)
        return out
    }

    /** Top bar (breadcrumb vs mode title + close) and bottom-bar affordances that don't apply in a mode. */
    private fun renderChrome() {
        val inMode = mode != BrowseMode.NORMAL
        binding.breadcrumbScroll.visibility = if (inMode) View.GONE else View.VISIBLE
        binding.modeTitle.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.btnCloseMode.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.btnNewFolder.visibility = if (inMode) View.GONE else View.VISIBLE
        binding.btnNewNotebook.visibility = if (inMode) View.GONE else View.VISIBLE

        if (inMode) {
            binding.btnUp.visibility = View.GONE
            binding.modeTitle.setText(
                if (mode == BrowseMode.PINNED) R.string.library_pinned_title else R.string.library_recents_title
            )
        } else {
            renderBreadcrumb()
        }
    }

    private fun sortItems(list: List<ObjectSummary>, field: SortField, order: SortOrder): List<ObjectSummary> {
        val comparator: Comparator<ObjectSummary> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        return if (order == SortOrder.DESC) list.sortedWith(comparator.reversed()) else list.sortedWith(comparator)
    }

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        val inkBlack = ContextCompat.getColor(this, R.color.inkBlack)

        lifecycleScope.launch {
            val ancestry = repo.ancestry(folderId)
            container.removeAllViews()

            val rootCrumb = makeCrumbView(getString(R.string.library_root), inkBlack) {
                navigateTo(null)
            }
            container.addView(rootCrumb)

            for (ref in ancestry) {
                container.addView(makeSeparator(inkBlack))
                val crumb = makeCrumbView(ref.name, inkBlack) {
                    navigateTo(ref.id)
                }
                container.addView(crumb)
            }
        }

        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
    }

    private fun makeCrumbView(label: String, color: Int, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSeparator(color: Int): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = " / "
            textSize = 16f
            setTextColor(color)
        }
    }

    private fun navigateTo(id: String?) {
        folderId = id
        pageIndex = 0
        browseState.folderId = id
        lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        lifecycleScope.launch {
            if (folderId == null) return@launch
            val ancestry = repo.ancestry(folderId)
            val parent = if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null
            navigateTo(parent)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        renderPager()
        grid?.bind(items, pageIndex)
    }

    private fun renderPager() {
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = "${pageIndex + 1} / $pageCount"
    }

    // ── Card interactions ────────────────────────────────────────────────────

    private fun onCardTap(item: CardItem) {
        when (item) {
            is CardItem.Folder -> navigateTo(item.summary.id)
            is CardItem.Notebook -> {
                recentsPrefs.record(item.summary.id)
                startActivity(NotebookActivity.intent(this, item.summary.id, item.summary.name))
            }
        }
    }

    private fun onCardLongPress(item: CardItem) {
        val s = item.summary
        when (item) {
            is CardItem.Folder -> ActionSheetDialog(this)
                .title(s.name)
                .addAction(R.drawable.ic_edit, getString(R.string.action_rename)) { showRenameDialog(s) }
                .addAction(R.drawable.ic_move_page, getString(R.string.action_move)) { showMovePicker(s) }
                .addAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteFolder(s) }
                .show()
            is CardItem.Notebook -> ActionSheetDialog(this)
                .title(s.name)
                .addAction(
                    R.drawable.ic_pinned,
                    getString(if (item.pinned) R.string.action_unpin else R.string.action_pin)
                ) { togglePin(s, item.pinned) }
                .addAction(R.drawable.ic_edit, getString(R.string.action_rename)) { showRenameDialog(s) }
                .addAction(R.drawable.ic_move_page, getString(R.string.action_move)) { showMovePicker(s) }
                .addAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteNotebook(s) }
                .show()
        }
    }

    private fun togglePin(s: ObjectSummary, pinned: Boolean) {
        lifecycleScope.launch {
            if (pinned) repo.unpin(s.id) else repo.pin(s.id)
            refresh()
        }
    }

    // ── New folder ───────────────────────────────────────────────────────────

    private fun showNewFolderDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = getString(R.string.new_folder_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
            background = ContextCompat.getDrawable(this@LibraryActivity, R.drawable.shape_bordered)
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_folder_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.new_notebook_create), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val err = NewNotebookActivity.validateName(name)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    if (repo.nameTaken(folderId, ObjectType.FOLDER, name)) {
                        Toast.makeText(this@LibraryActivity, R.string.new_folder_duplicate, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    repo.createFolder(name, folderId)
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    private fun showRenameDialog(s: ObjectSummary) {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(s.name)
            selectAll()
            hint = getString(R.string.rename_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
            background = ContextCompat.getDrawable(this@LibraryActivity, R.drawable.shape_bordered)
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.action_rename), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name == s.name) { dialog.dismiss(); return@setOnClickListener }
                val err = NewNotebookActivity.validateName(name)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    if (repo.nameTaken(s.parentId, s.type, name, s.id)) {
                        val msgRes = if (s.type == ObjectType.NOTEBOOK) R.string.rename_duplicate_notebook else R.string.rename_duplicate_folder
                        Toast.makeText(this@LibraryActivity, getString(msgRes, name), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    repo.rename(s.id, name)
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDeleteNotebook(s: ObjectSummary) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_notebook_title, s.name))
            .setMessage(getString(R.string.delete_notebook_body))
            .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                lifecycleScope.launch {
                    recentsPrefs.remove(s.id)
                    repo.deleteNotebook(s.id)
                    withContext(Dispatchers.IO) { deleteNotebookFiles(s.id) }
                    refresh()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
    }

    private fun confirmDeleteFolder(s: ObjectSummary) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_folder_title, s.name))
            .setMessage(getString(R.string.delete_folder_body))
            .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                lifecycleScope.launch {
                    val nbIds = repo.deleteFolderRecursive(s.id)
                    for (id in nbIds) {
                        recentsPrefs.remove(id)
                        withContext(Dispatchers.IO) { deleteNotebookFiles(id) }
                    }
                    if (folderId == s.id) navigateTo(s.parentId)
                    else refresh()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
    }

    private fun deleteNotebookFiles(notebookId: String) {
        val file = soilFile(this, notebookId)
        file.delete()
        sidecarsOf(file).forEach { it.delete() }
        DerivedKeyStore.remove(this, notebookId)
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    private fun showMovePicker(item: ObjectSummary) {
        val intent = FolderPickerActivity.intent(this, item.id, item.type, item.name, item.parentId)
        movePickerLauncher.launch(intent)
    }

    private val movePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch { refresh() }
        }
    }

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun showSortSheet() {
        val sf = sortPrefs.field
        val so = sortPrefs.order
        val check = R.drawable.ic_check
        ActionSheetDialog(this)
            .title(getString(R.string.cd_sort))
            .addAction(
                if (sf == SortField.NAME && so == SortOrder.ASC) check else null,
                getString(R.string.sort_name_asc)
            ) { applySort(SortField.NAME, SortOrder.ASC) }
            .addAction(
                if (sf == SortField.NAME && so == SortOrder.DESC) check else null,
                getString(R.string.sort_name_desc)
            ) { applySort(SortField.NAME, SortOrder.DESC) }
            .addAction(
                if (sf == SortField.MODIFIED && so == SortOrder.ASC) check else null,
                getString(R.string.sort_modified_asc)
            ) { applySort(SortField.MODIFIED, SortOrder.ASC) }
            .addAction(
                if (sf == SortField.MODIFIED && so == SortOrder.DESC) check else null,
                getString(R.string.sort_modified_desc)
            ) { applySort(SortField.MODIFIED, SortOrder.DESC) }
            .show()
    }

    private fun applySort(field: SortField, order: SortOrder) {
        sortPrefs.field = field
        sortPrefs.order = order
        lifecycleScope.launch { refresh() }
    }

    // ── Back press ───────────────────────────────────────────────────────────

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (mode != BrowseMode.NORMAL) {
            setMode(BrowseMode.NORMAL)
        } else if (folderId != null) {
            navigateUp()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::browseState.isInitialized) browseState.folderId = folderId
    }
}
