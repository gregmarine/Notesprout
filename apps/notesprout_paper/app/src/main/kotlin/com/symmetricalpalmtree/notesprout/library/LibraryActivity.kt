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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.crypto.KeyMaterial
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseMode
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesprout.data.prefs.SortField
import com.symmetricalpalmtree.notesprout.data.prefs.SortOrder
import com.symmetricalpalmtree.notesprout.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesprout.data.soilFile
import com.symmetricalpalmtree.notesprout.data.sidecarsOf
import com.symmetricalpalmtree.notesprout.databinding.ActivityLibraryBinding
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.NamerClient
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.SchemeField
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
    /** Covers fetched for pages already viewed in the current listing; cleared on every [refresh]. */
    private val coverCache = HashMap<String, ByteArray?>()
    private var grid: LibraryGrid? = null
    private var gridMeasured = false
    private var coldLaunch = false
    /**
     * The one trusted notebook namer, refreshed on every resume. Every naming entry point (New-folder
     * scheme field, folder long-press item, +Notebook prefill) is absent while this is null — nothing
     * hints at naming schemes without the extension.
     */
    private var namerRef: ProviderRef? = null
    /**
     * A namer call is in flight before a screen or dialog opens (+Notebook prefill, New-folder
     * `describeField`, long-press fetch); a second tap during that beat is dropped so the beat can never
     * stack two dialogs or two New-notebook screens.
     */
    private var namerBusy = false

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
        lifecycleScope.launch { refreshNamer() }
        if (gridMeasured) lifecycleScope.launch { refresh() }
    }

    private suspend fun refreshNamer() {
        val ref = try {
            ExtensionRegistry.notebookNamer(this)
        } catch (e: Exception) {   // a PackageManager hiccup must not take the screen down
            Slog.d(TAG) { "namer discovery failed: ${e.message}" }
            null
        }
        namerRef = ref
        // Pre-warm the namer's store while the library is idle so the first +Notebook tap never pays
        // the cold raw-key open (or the one-time KDF after a key wipe). Failure is silent — the client
        // opens it again itself, and that path decides what the user sees.
        if (ref != null) withContext(Dispatchers.IO) {
            runCatching { ExtensionStores.open(this@LibraryActivity, ref.packageName) }
                .onFailure { Slog.d(TAG) { "store pre-warm failed: ${it.message}" } }
        }
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
        binding.btnNewNotebook.setOnClickListener { launchNewNotebook() }
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

    /**
     * +Notebook. In a folder with a namer installed, the default name is resolved from the extension
     * **before** the New-notebook screen opens (≤ 2 s worst case; no feedback — the tap takes a beat).
     * The folder UUID and the folder's notebook names (already in memory) are all that cross. Null,
     * failure, or the library root → the screen opens without an extra and uses the core default.
     */
    private fun launchNewNotebook() {
        val ref = namerRef
        val fid = folderId
        if (ref == null || fid == null) {
            newNotebookLauncher.launch(NewNotebookActivity.intent(this, fid))
            return
        }
        if (namerBusy) return
        namerBusy = true
        val siblings = items.mapNotNull { (it as? CardItem.Notebook)?.summary?.name }
        lifecycleScope.launch {
            val name = try {
                NamerClient(this@LibraryActivity, ref).defaultName(fid, siblings)
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "defaultName failed: ${e.message}" }
                null
            } finally {
                namerBusy = false
            }
            // The beat is normally ~50 ms, but if the user has meanwhile left this folder or this
            // screen the tap no longer means "here" — drop it rather than create elsewhere.
            if (folderId != fid || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            newNotebookLauncher.launch(NewNotebookActivity.intent(this@LibraryActivity, fid, name))
        }
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
        // Covers may have changed since the last listing (a notebook was edited); re-fetch lazily.
        coverCache.clear()
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
        bindCurrentPage() // renders the pager after the cards are bound
    }

    /**
     * Bind only the current page's cards, fetching covers for the visible slice on demand (cached for
     * the life of this listing). Blobs are never read for off-page notebooks — the DAO listing is
     * blob-free and covers stay lazy, which is what keeps a 40-notebook folder cheap to render.
     */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val perPage = g.cardsPerPage
        val start = pageIndex * perPage
        val end = minOf(start + perPage, items.size)
        val missing = if (start < end) {
            (start until end)
                .mapNotNull { (items[it] as? CardItem.Notebook)?.summary?.id }
                .filter { it !in coverCache }
        } else emptyList()
        if (missing.isNotEmpty()) {
            // Fetch into a local map on IO, then merge on the main thread. bindCurrentPage runs on the
            // Main dispatcher and several can be in flight at once (a page tap racing onResume); writing
            // coverCache only here — never from the IO block — keeps the shared HashMap single-threaded.
            val fetched = withContext(Dispatchers.IO) {
                HashMap<String, ByteArray?>(missing.size).apply { for (id in missing) put(id, repo.cover(id)) }
            }
            coverCache.putAll(fetched)
        }
        g.bind(items, pageIndex, coverCache)
        // Page label after the bind, so the indicator can't advance before the cards it names appear.
        renderPager()
    }

    private suspend fun buildNormalItems(pinnedIds: Set<String>): List<CardItem> {
        val sf = sortPrefs.field
        val so = sortPrefs.order
        val sortedFolders = sortItems(repo.folders(folderId), sf, so)
        val sortedNotebooks = sortItems(repo.notebooks(folderId), sf, so)
        val out = mutableListOf<CardItem>()
        for (f in sortedFolders) out.add(CardItem.Folder(f))
        for (nb in sortedNotebooks) out.add(CardItem.Notebook(nb, pinned = nb.id in pinnedIds))
        return out
    }

    /** Pinned mode: flat grid of pinned notebooks in the current sort. */
    private suspend fun buildPinnedItems(pinnedIds: Set<String>): List<CardItem> {
        val summaries = pinnedIds.mapNotNull { repo.alive(it) }.filter { it.type == ObjectType.NOTEBOOK }
        val sorted = sortItems(summaries, sortPrefs.field, sortPrefs.order)
        return sorted.map { nb -> CardItem.Notebook(nb, pinned = true) }
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
            out.add(CardItem.Notebook(nb, pinned = nb.id in pinnedIds, subtitle = parentName(nb.parentId)))
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
        lifecycleScope.launch { bindCurrentPage() }
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
                .apply {
                    if (namerRef != null) {
                        addAction(R.drawable.ic_cursor_text, getString(R.string.action_naming)) { openSchemeDialog(s) }
                    }
                }
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

    /**
     * With a namer installed the scheme field is described **before** the dialog shows (failure →
     * the plain dialog, no field); without one the dialog is exactly the v0 dialog.
     */
    private fun showNewFolderDialog() {
        val ref = namerRef
        if (ref == null) { showNewFolderDialog(null, null); return }
        if (namerBusy) return
        namerBusy = true
        lifecycleScope.launch {
            val field = try {
                NamerClient(this@LibraryActivity, ref).describeField()
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "describeField failed: ${e.message}" }
                null
            } finally {
                namerBusy = false
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            showNewFolderDialog(if (field != null) ref else null, field)
        }
    }

    private fun showNewFolderDialog(namer: ProviderRef?, field: SchemeField?) {
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
        val schemeViews = if (namer != null && field != null) {
            SchemeDialogs.buildField(this, field, null).also { it.addTo(wrapper) }
        } else null
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_folder_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.new_notebook_create), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.setOnShowListener {
            val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            create.setOnClickListener {
                val name = input.text.toString().trim()
                val err = NewNotebookActivity.validateName(name)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val scheme = schemeViews?.input?.text?.toString()?.trim().orEmpty()
                val client = if (namer != null && scheme.isNotEmpty()) NamerClient(this, namer) else null
                // Disarmed while the coroutine runs: a namer bind can take a beat, and a second tap
                // would pass nameTaken again and create the folder twice.
                create.isClickable = false
                lifecycleScope.launch {
                    try {
                        if (repo.nameTaken(folderId, ObjectType.FOLDER, name)) {
                            Toast.makeText(this@LibraryActivity, R.string.new_folder_duplicate, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        if (client != null) {
                            // Validate before the folder exists so a bad scheme keeps the dialog up.
                            val err = try {
                                client.validate(scheme)
                            } catch (e: ExtensionCallException) {
                                Slog.d(TAG) { "validate failed: ${e.message}" }
                                getString(R.string.naming_unavailable)
                            }
                            if (err != null) {
                                Toast.makeText(this@LibraryActivity, err, Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                        }
                        val folder = repo.createFolder(name, folderId)
                        if (client != null) {
                            try {
                                client.save(folder.id, scheme)
                            } catch (e: ExtensionCallException) {
                                // The folder exists either way; the user can retry from long-press.
                                Slog.d(TAG) { "save scheme failed: ${e.message}" }
                                Toast.makeText(this@LibraryActivity, R.string.naming_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                        dialog.dismiss()
                        refresh()
                    } finally {
                        create.isClickable = true
                    }
                }
            }
        }
        dialog.show()
    }

    // ── Naming scheme (folder long-press) ────────────────────────────────────

    /** Field description + current scheme are fetched before the dialog shows; failure → toast, no dialog. */
    private fun openSchemeDialog(s: ObjectSummary) {
        val ref = namerRef ?: return
        if (namerBusy) return
        namerBusy = true
        val client = NamerClient(this, ref)
        lifecycleScope.launch {
            val fetched = try {
                client.describeField() to client.currentScheme(s.id)
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "scheme dialog open failed: ${e.message}" }
                Toast.makeText(this@LibraryActivity, R.string.naming_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            } finally {
                namerBusy = false
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            SchemeDialogs.showSchemeDialog(this@LibraryActivity, client, s.id, s.name, fetched.first, fetched.second)
        }
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
        KeyMaterial.invalidate(this, notebookId)
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

    companion object {
        private const val TAG = "LibraryActivity"
    }
}
