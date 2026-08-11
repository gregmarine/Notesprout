package com.notesprout.android

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.data.NotebookCompactor
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphraseCache
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.crypto.SoilMigrator
import com.notesprout.android.core.Slog
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.NotebookMeta
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.NotebookMetaStore
import com.notesprout.android.data.PageData
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.SoilSchema
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.PINNED_LIST_ID
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.index.notebookMeta
import com.notesprout.android.data.index.templateObject
import com.notesprout.android.data.recents.RecentsManager
import com.notesprout.android.data.recents.ResolvedRecent
import com.notesprout.android.data.recents.TemplateRecentsManager
import com.notesprout.android.data.soilFile
import com.notesprout.android.search.SearchDialog
import com.notesprout.android.search.SearchEngine
import com.notesprout.android.search.SearchResult
import com.notesprout.android.sort.FolderSort
import com.notesprout.android.state.AppStateManager
import com.notesprout.android.state.NotebookOpenFailure
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.AppViewState
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import com.notesprout.android.sort.SortDialog
import com.notesprout.android.sort.SortField
import com.notesprout.android.sort.SortOrder
import com.notesprout.android.sort.SortPreferences
import com.notesprout.android.sort.SortPreferencesManager
import com.notesprout.android.ui.DestinationPickerState
import com.notesprout.android.databinding.ActivityMainBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Nil UUID used as the parentId for root-level objects (notebook pages).
         * Defined as a constant to avoid magic strings in notebook creation.
         */
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

        /**
         * Boolean intent extra: launch straight into the "pick a folder for a new notebook" picker
         * (used by [CalendarActivity]'s New Notebook button). Consumed once, then removed.
         */
        const val EXTRA_START_NEW_NOTEBOOK = "start_new_notebook"

        /**
         * Boolean intent extra, paired with [EXTRA_START_NEW_NOTEBOOK]: when the flow ends by
         * opening the new notebook, put the **Today** dashboard back underneath it.
         *
         * [TodayActivity] cannot run this flow itself — choosing the destination folder is a mode of
         * *this* screen's grid, and the dashboard deliberately has no browsing — so it hands over and
         * is popped on the way (`CLEAR_TOP` onto the root library). Without this the notebook's back
         * step lands in the library, which is right for the library's own button and wrong for the
         * dashboard: opening an *existing* notebook from there comes straight back.
         *
         * Consumed once, like [EXTRA_START_NEW_NOTEBOOK], and deliberately **not** saved instance
         * state. This Activity is recreated on rotation (it declares no `configChanges`), so rotating
         * mid-flow loses the flag and the notebook opens over the library — the behaviour before this
         * existed, and no worse. Keeping it on the Intent instead would survive that, but the Intent
         * outlives the flow: the next notebook created from the library's *own* button would then be
         * sent to the dashboard too.
         */
        const val EXTRA_RETURN_TO_TODAY = "return_to_today"

        /** One-time flag (in the notesprout_onboarding prefs) that the Phase-4 bulk-encrypt offer was shown. */
        private const val KEY_CONVERSION_OFFERED = "conversion_offered"

        private val lenientJson = Json { ignoreUnknownKeys = true }
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

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding

    private var items: List<NotebookListItem> = emptyList()
    private var currentPage = 0
    private var gridSpec: GridSpec? = null
    private var pendingScan = false

    /** Set when launched with [EXTRA_START_NEW_NOTEBOOK] but the grid isn't laid out / restored yet. */
    private var pendingNewNotebookPicker = false

    /** Set by [EXTRA_RETURN_TO_TODAY]; consumed when the new notebook opens. */
    private var returnToTodayAfterCreate = false

    /**
     * Navigation stack — null represents the root level; a non-null ObjectEntity represents a
     * subfolder.  Navigating into a folder pushes onto this list; going back pops from it.
     */
    private val directoryStack: ArrayDeque<ObjectEntity?> = ArrayDeque()

    /** The folder currently being displayed, or null at root. */
    private val currentFolder: ObjectEntity? get() = directoryStack.last()

    /** The index parentId for queries against the current level (null = root). */
    private val currentParentId: String? get() = currentFolder?.id

    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private var sortPrefs: SortPreferences = SortPreferences()

    private var isSearchMode = false
    private var currentSearchQuery: String = ""

    private var searchResults: List<SearchResult> = emptyList()

    private var destinationPickerState: DestinationPickerState = DestinationPickerState.None

    private var isPinnedMode = false
    private var pinnedResults: List<SearchResult> = emptyList()
    private var pinnedListName: String = "Pinned"

    // Recents browse mode — peer of pinned/search/picker, mutually exclusive with all of them.
    // Never persisted to AppStateManager (same as search mode).
    private var isRecentsMode = false
    private var recentsResults: List<ResolvedRecent> = emptyList()

    // false while the async state-restore coroutine is running on first launch; guards the layout
    // listener and onResume from triggering a premature scan before the stack is rebuilt.
    private var isStateRestored = true

    // ── Color cache ───────────────────────────────────────────────────────────

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    // ── Cover load jobs (cancelled on each re-render) ─────────────────────────

    private val coverLoadJobs = mutableListOf<Job>()

    // ── Gesture detector (swipe left/right to change page) ───────────────────

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
                    navigatePage(currentPage + 1); true
                } else {
                    navigatePage(currentPage - 1); true
                }
            }
        })
    }

    // ── New notebook launcher (S6: launched from TemplateBrowserActivity) ─────

    private val newNotebookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(TemplateBrowserActivity.RESULT_NOTEBOOK_NAME)?.trim().orEmpty()
        val templateId = data.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_ID).orEmpty()
        val scopeString = data.getStringExtra(TemplateBrowserActivity.RESULT_KEY_SCOPE).orEmpty()
        val scope: KeyScope? = when (scopeString) {
            "GLOBAL"   -> KeyScope.GLOBAL
            "NOTEBOOK" -> KeyScope.NOTEBOOK
            else       -> null
        }
        if (name.isBlank()) return@registerForActivityResult
        lifecycleScope.launch {
            // Duplicate-in-target-folder check (browser only did format validation).
            val siblings = withContext(Dispatchers.IO) { repository.getNotebooks(currentParentId) }
            if (siblings.any { it.name.equals(name, ignoreCase = true) }) {
                Toast.makeText(this@MainActivity, "A notebook named \"$name\" already exists", Toast.LENGTH_SHORT).show()
                return@launch
            }
            createNotebook(name, templateId, scope)
        }
    }

    // ── Import launcher ───────────────────────────────────────────────────────

    private val importSoilLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImportFromUri(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing has opened the index if Android rebuilt this task itself — see IndexGuard.
        if (!IndexGuard.ready(this)) return

        // The encrypted index must be prepared before any index access. This guard began here and now
        // lives in one place — every index-touching screen needs it, not just the deep-link entry.
        if (bounceIfIndexNotReady()) return

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15 (targetSdk 35) enforces edge-to-edge. Pad the root view by the
        // system bar insets so content doesn't draw under the status/nav bars.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // null = root level
        directoryStack.add(null)

        sortPrefs = SortPreferencesManager.load(this)

        val savedViewState = AppStateManager.load(this)
        val savedSurfaces  = SurfaceStack.load(this)
        val coldLaunch = savedInstanceState == null
        val hasNonDefaultState = savedViewState.folderId != null ||
                savedViewState.pinnedMode ||
                savedViewState.recentsMode ||
                (savedViewState.searchMode && savedViewState.searchQuery.isNotEmpty()) ||
                (coldLaunch && savedSurfaces.isNotEmpty())
        if (hasNonDefaultState) {
            isStateRestored = false
            lifecycleScope.launch { restoreSavedBrowseState(savedViewState, savedSurfaces, coldLaunch) }
        }

        setupBottomBar()
        setupGridGestures()
        setupBackNavigation()

        binding.btnPickerCancel.setOnClickListener { exitPickerMode() }
        binding.btnPickerConfirm.setOnClickListener { confirmPickerDestination() }

        binding.btnPinned.setOnClickListener { enterPinnedMode() }
        binding.btnPinnedCancel.setOnClickListener { exitPinnedMode() }

        binding.btnRecents.setOnClickListener { enterRecentsMode() }
        binding.btnRecentsCancel.setOnClickListener { exitRecentsMode() }

        // Lives in the bottom bar, which is never swapped out by search / pinned / recents mode,
        // so a single button covers every browse mode.
        binding.btnScratchpad.setOnClickListener { launchScratchpad() }

        binding.btnSort.setOnClickListener {
            SortDialog(this, sortPrefs) { newPrefs ->
                sortPrefs = newPrefs
                SortPreferencesManager.save(this, newPrefs)
                currentPage = 0
                scanAndRender()
            }.show()
        }

        val openSearch = View.OnClickListener {
            SearchDialog.show(
                context = this,
                initialQuery = if (isSearchMode) currentSearchQuery else "",
                onSearch = { query -> enterSearchMode(query) },
                onCancel = { }
            )
        }
        binding.btnSearch.setOnClickListener(openSearch)
        binding.btnSearchInToolbar.setOnClickListener(openSearch)

        binding.btnClearSearch.setOnClickListener { exitSearchMode() }

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    if (!isStateRestored) {
                        // State restore coroutine will trigger the render when complete.
                        return
                    }
                    if (pendingScan) {
                        pendingScan = false
                        when {
                            isRecentsMode -> renderRecentsList()
                            isPinnedMode  -> lifecycleScope.launch { renderPinnedList() }
                            else          -> scanAndRender()
                        }
                    } else {
                        renderPage()
                    }
                    tryStartPendingNewNotebookPicker()
                }
            }
        )

        // Handle .soil open-with / share-to on cold launch only; config-change recreations
        // must not re-trigger the import pipeline.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
            handleNewNotebookIntent(intent)
            maybeOfferBulkEncryption()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        handleNewNotebookIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The library is on screen, so nothing is stacked on it. Skipped while a restore is still in
        // flight — that coroutine is about to stack surfaces on top of us, and they record themselves.
        if (isStateRestored) SurfaceStack.reset(this)
        showNotebookOpenFailureIfAny()
        if (!isStateRestored) {
            pendingScan = true
            return
        }
        if (gridSpec != null) {
            when {
                isRecentsMode -> renderRecentsList()
                isPinnedMode  -> lifecycleScope.launch { renderPinnedList() }
                else          -> resumeNormalBrowse()
            }
        } else {
            pendingScan = true
        }
    }

    /**
     * Normal-mode resume render, with a return-to-folder sync for the NotebookActivity recents
     * switch flow: that flow persists the *switched* notebook's folder while this activity is still
     * sitting in the original folder, so when the switched notebook closes we must re-navigate the
     * stack to the persisted folder before rendering.
     *
     * Narrow by design — only fires when no special mode is active and the persisted browse folder
     * actually differs from the current one (the normal close path leaves them equal → plain scan).
     */
    private fun resumeNormalBrowse() {
        val persisted = AppStateManager.load(this).folderId
        if (destinationPickerState == DestinationPickerState.None &&
            !isSearchMode &&
            persisted != currentParentId
        ) {
            lifecycleScope.launch {
                navigateStackToFolder(persisted)
                currentPage = 0
                scanAndRender()
            }
        } else {
            scanAndRender()
        }
    }

    override fun onStop() {
        super.onStop()
        NotesproutApplication.appScope.launch {
            NotesproutIndex.checkpointAndVacuum()
        }
    }

    // ── Back navigation ───────────────────────────────────────────────────────

    private fun navigateUpOneLevel() {
        if (directoryStack.size > 1) {
            directoryStack.removeLast()
            currentPage = 0
            AppStateManager.save(this, AppViewState(currentParentId, false))
            scanAndRender()
        }
    }

    private fun enterSearchMode(query: String) {
        clearRecentsMode()
        isSearchMode = true
        currentSearchQuery = query
        currentPage = 0
        val st = AppStateManager.load(this)
        AppStateManager.save(this, st.copy(searchMode = true, searchQuery = query, recentsMode = false, pinnedMode = false))
        applySearchModeUI()
        closeOverflowToolbar()
        scanAndRender()
    }

    private fun applySearchModeUI() {
        val inSearch = isSearchMode
        binding.searchToolbar.visibility        = if (inSearch) View.VISIBLE else View.GONE
        binding.searchToolbarDivider.visibility = if (inSearch) View.VISIBLE else View.GONE
        binding.breadcrumbBar.visibility        = if (inSearch) View.GONE   else View.VISIBLE
        binding.breadcrumbDivider.visibility    = if (inSearch) View.GONE   else View.VISIBLE
        binding.btnMore.visibility              = if (inSearch) View.GONE   else View.VISIBLE
        if (inSearch) {
            binding.searchTitle.text = "Search: $currentSearchQuery"
            binding.btnClearSearch.visibility = View.VISIBLE
        }
    }

    private fun exitSearchMode() {
        isSearchMode = false
        currentSearchQuery = ""
        searchResults = emptyList()
        currentPage = 0
        val st = AppStateManager.load(this)
        AppStateManager.save(this, st.copy(searchMode = false, searchQuery = ""))
        applySearchModeUI()
        scanAndRender()
    }

    private fun enterPinnedMode() {
        clearRecentsMode()
        if (isSearchMode) {
            isSearchMode = false
            currentSearchQuery = ""
            searchResults = emptyList()
        }
        isPinnedMode = true
        currentPage = 0
        AppStateManager.save(this, AppViewState(currentParentId, true))
        applyPinnedModeUI()
        lifecycleScope.launch { renderPinnedList() }
    }

    private fun exitPinnedMode() {
        isPinnedMode = false
        pinnedResults = emptyList()
        currentPage = 0
        AppStateManager.save(this, AppViewState(currentParentId, false))
        applyPinnedModeUI()
        scanAndRender()
    }

    private fun applyPinnedModeUI() {
        val inPinned = isPinnedMode
        binding.pinnedToolbar.visibility        = if (inPinned) View.VISIBLE else View.GONE
        binding.pinnedToolbarDivider.visibility = if (inPinned) View.VISIBLE else View.GONE
        binding.breadcrumbBar.visibility        = if (inPinned) View.GONE   else View.VISIBLE
        binding.breadcrumbDivider.visibility    = if (inPinned) View.GONE   else View.VISIBLE
        if (inPinned) {
            binding.btnNewNotebook.visibility   = View.GONE
            binding.btnNewFolder.visibility     = View.GONE
            binding.btnMore.visibility          = View.GONE
            binding.btnSearch.visibility        = View.GONE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.GONE
            binding.btnPinned.visibility        = View.GONE
            binding.btnRecents.visibility       = View.GONE
            closeOverflowToolbar()
        } else {
            binding.btnNewNotebook.visibility   = View.VISIBLE
            binding.btnNewFolder.visibility     = View.VISIBLE
            binding.btnMore.visibility          = View.VISIBLE
            binding.btnSearch.visibility        = View.VISIBLE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.VISIBLE
            binding.btnPinned.visibility        = View.VISIBLE
            binding.btnRecents.visibility       = View.VISIBLE
        }
    }

    private suspend fun renderPinnedList() {
        val (listName, notebooks, allFolders) = withContext(Dispatchers.IO) {
            val listEntity = repository.getPinnedList()
            val name = listEntity?.name ?: "Pinned"
            val nbs = repository.getNotebooksInList(PINNED_LIST_ID)
            val folders = repository.getAllFolders()
            Triple(name, nbs, folders)
        }
        pinnedListName = listName
        pinnedResults = notebooks.map { entity ->
            val segments = mutableListOf<String>()
            var currentId: String? = entity.parentId
            while (currentId != null) {
                val folder = allFolders.find { it.id == currentId } ?: break
                segments.add(0, folder.name)
                currentId = folder.parentId
            }
            segments.add(0, "Notebooks")
            SearchResult(
                entity      = entity,
                displayName = entity.name,
                folderLabel = segments.joinToString(" › "),
                score       = 0,
            )
        }
        items = pinnedResults.map { NotebookListItem.Notebook(it.entity) }
        val total = totalPages()
        currentPage = currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))
        renderPage()
    }

    // ── Recents mode ──────────────────────────────────────────────────────────

    private fun enterRecentsMode() {
        if (isSearchMode) {
            isSearchMode = false
            currentSearchQuery = ""
            searchResults = emptyList()
        }
        isRecentsMode = true
        currentPage = 0
        val st = AppStateManager.load(this)
        AppStateManager.save(this, st.copy(recentsMode = true, searchMode = false, searchQuery = "", pinnedMode = false))
        applyRecentsModeUI()
        renderRecentsList()
    }

    private fun exitRecentsMode() {
        isRecentsMode = false
        currentPage = 0
        val st = AppStateManager.load(this)
        AppStateManager.save(this, st.copy(recentsMode = false))
        applyRecentsModeUI()
        scanAndRender()
    }

    /** Silently drops recents mode when transitioning into another exclusive mode. */
    private fun clearRecentsMode() {
        if (!isRecentsMode) return
        isRecentsMode = false
        binding.recentsToolbar.visibility        = View.GONE
        binding.recentsToolbarDivider.visibility = View.GONE
        binding.breadcrumbBar.visibility         = View.VISIBLE
        binding.breadcrumbDivider.visibility     = View.VISIBLE
    }

    private fun applyRecentsModeUI() {
        val inRecents = isRecentsMode
        binding.recentsToolbar.visibility        = if (inRecents) View.VISIBLE else View.GONE
        binding.recentsToolbarDivider.visibility = if (inRecents) View.VISIBLE else View.GONE
        binding.breadcrumbBar.visibility         = if (inRecents) View.GONE   else View.VISIBLE
        binding.breadcrumbDivider.visibility     = if (inRecents) View.GONE   else View.VISIBLE
        if (inRecents) {
            binding.btnNewNotebook.visibility = View.GONE
            binding.btnNewFolder.visibility   = View.GONE
            binding.btnMore.visibility        = View.GONE
            binding.btnSearch.visibility      = View.GONE
            binding.btnClearSearch.visibility = View.GONE
            binding.btnSort.visibility        = View.GONE
            binding.btnPinned.visibility      = View.GONE
            binding.btnRecents.visibility     = View.GONE
            closeOverflowToolbar()
        } else {
            binding.btnNewNotebook.visibility = View.VISIBLE
            binding.btnNewFolder.visibility   = View.VISIBLE
            binding.btnMore.visibility        = View.VISIBLE
            binding.btnSearch.visibility      = View.VISIBLE
            binding.btnClearSearch.visibility = View.GONE
            binding.btnSort.visibility        = View.VISIBLE
            binding.btnPinned.visibility      = View.VISIBLE
            binding.btnRecents.visibility     = View.VISIBLE
        }
    }

    /**
     * Resolves the recents store against the index (newest-first, stale entries pruned) and
     * renders the results as notebook cards. Self-launches a coroutine so the synchronous call
     * sites (layout listener, onResume, enterRecentsMode) need no change.
     */
    private fun renderRecentsList() {
        lifecycleScope.launch {
            val resolved = RecentsManager.resolve(this@MainActivity)
            val entities = withContext(Dispatchers.IO) {
                resolved.mapNotNull { repository.getNotebook(it.notebookId) }
            }
            // The user may have left recents mode while we were resolving off-thread.
            if (!isRecentsMode) return@launch
            recentsResults = resolved
            items = entities.map { NotebookListItem.Notebook(it) }
            val total = totalPages()
            currentPage = currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))
            renderPage()
        }
    }

    /**
     * Reconstructs the directoryStack by walking up the parentId chain from [folderId] to root.
     * Must be called from a coroutine (performs index reads).
     */
    private suspend fun navigateStackToFolder(folderId: String?) {
        if (folderId == null) {
            directoryStack.clear()
            directoryStack.add(null)
            return
        }
        val path = mutableListOf<ObjectEntity>()
        var currentId: String? = folderId
        while (currentId != null) {
            val folder = repository.getFolder(currentId) ?: break
            path.add(0, folder)
            currentId = folder.parentId
        }
        directoryStack.clear()
        directoryStack.add(null)
        directoryStack.addAll(path)
    }

    private suspend fun restoreSavedBrowseState(
        state: AppViewState,
        surfaces: List<SurfaceEntry>,
        coldLaunch: Boolean = false,
    ) {
        // Reopen the surfaces only on cold launch (process start). On a warm restart — MainActivity
        // recreated while a notebook sat on top, user then closes it — coldLaunch is false and we just
        // restore the browse state behind it instead.
        if (coldLaunch) restoreSurfaces(surfaces)

        navigateStackToFolder(state.folderId)
        if (state.folderId != null && currentParentId != state.folderId) {
            // Folder was deleted — clear the stale entry so we don't retry next launch.
            AppStateManager.save(this@MainActivity, AppViewState(null, false))
        }
        when {
            state.pinnedMode -> {
                isPinnedMode = true
                applyPinnedModeUI()
            }
            state.recentsMode -> {
                isRecentsMode = true
                applyRecentsModeUI()
            }
            state.searchMode && state.searchQuery.isNotEmpty() -> {
                isSearchMode = true
                currentSearchQuery = state.searchQuery
                applySearchModeUI()
            }
        }
        isStateRestored = true
        if (gridSpec != null) {
            when {
                isPinnedMode  -> renderPinnedList()
                isRecentsMode -> renderRecentsList()
                else          -> scanAndRender()
            }
            tryStartPendingNewNotebookPicker()
        }
        // If gridSpec is still null, the layout listener will handle the render now that
        // isStateRestored is true.
    }

    /**
     * Rebuild the surfaces the user had open, over the library we're already building — the whole
     * chain, not just the top one, so a scratch pad opened from a notebook (or from the calendar)
     * comes back *over that screen*, and stepping out of it lands where it did before the app died.
     *
     * The calendar and the scratch pad restore their own position (view/date, page) when they open,
     * so only the notebook and the day window need identity passed in. The **source notebook** is
     * passed back down too, so a restored calendar / day window / scratch pad still has the
     * Send-to-Notebook target it was opened with — the notebook directly beneath it, or for a day
     * window, the one beneath its calendar.
     *
     * Anything unresolvable (deleted notebook, unparseable date) is dropped; the rest of the chain
     * still comes back.
     *
     * Note this can put an **encrypted** notebook back underneath: it unlocks when the user steps
     * down to it, not at launch, but its `.soil` does get opened for a screen they aren't looking at
     * yet. That's the price of landing them where they actually were.
     */
    private suspend fun restoreSurfaces(surfaces: List<SurfaceEntry>) {
        val intents = mutableListOf<Intent>()
        // The notebook directly beneath the entry being rebuilt, and the one beneath the calendar
        // (which is what a day window above it was opened from) — see CalendarActivity.openDayDetail.
        var below: ObjectEntity? = null
        var calendarBelow: ObjectEntity? = null

        for (entry in surfaces) {
            val notebook = if (entry.surface == AppSurface.NOTEBOOK) {
                entry.notebookId
                    ?.let { id -> withContext(Dispatchers.IO) { repository.getNotebook(id) } }
                    ?.takeIf { it.deletedAt == null }
            } else null
            val from = below

            val intent: Intent? = when (entry.surface) {
                AppSurface.NOTEBOOK -> notebook?.let {
                    Intent(this, NotebookActivity::class.java).apply {
                        putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID,   it.id)
                        putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, it.name)
                    }
                }
                AppSurface.CALENDAR -> {
                    calendarBelow = from
                    if (from == null) Intent(this, CalendarActivity::class.java)
                    else CalendarActivity.intentFromNotebook(this, from.id, from.name)
                }
                AppSurface.DAY_WINDOW -> entry.dayDate
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.let { date ->
                        val source = calendarBelow
                        DayDetailActivity.intent(
                            this, date,
                            fromNotebookId        = source?.id,
                            fromNotebookName      = source?.name,
                            view = entry.dayView,
                        )
                    }
                AppSurface.SCRATCHPAD -> Intent(this, ScratchpadActivity::class.java).apply {
                    if (from != null) {
                        putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_ID,        from.id)
                        putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_NAME,      from.name)
                    }
                }
                // Tasks carries no per-launch state — the screen reads the table fresh every time,
                // so restoring it needs nothing beyond the intent itself. The dashboard is the same,
                // and more so: everything on it is derived from "now".
                AppSurface.TASKS -> TasksActivity.intent(this)
                AppSurface.TODAY -> TodayActivity.intent(this)
                // A routine that has since been deleted or rolled over resolves to nothing; the
                // screen itself also re-checks and steps out if the row has gone.
                AppSurface.ROUTINE -> entry.routineId?.let { RoutineActivity.intent(this, it) }
            }
            if (intent != null) intents += intent
            below = notebook
        }

        // Drop the dead process's entries — the Activities we're about to launch record themselves,
        // and anything we couldn't resolve should not be retried on the next launch.
        SurfaceStack.reset(this)
        if (intents.isNotEmpty()) startActivities(intents.toTypedArray())
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isRecentsMode) {
                    exitRecentsMode(); return
                }
                if (isPinnedMode) {
                    exitPinnedMode(); return
                }
                if (destinationPickerState != DestinationPickerState.None) {
                    exitPickerMode(); return
                }
                if (isSearchMode) {
                    exitSearchMode(); return
                }
                if (directoryStack.size > 1) {
                    navigateUpOneLevel()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ── Bottom bar wiring ─────────────────────────────────────────────────────

    private fun setupBottomBar() {
        binding.btnFirstPage.setOnClickListener { navigatePage(0) }
        binding.btnPrevPage.setOnClickListener  { navigatePage(currentPage - 1) }
        binding.btnNextPage.setOnClickListener  { navigatePage(currentPage + 1) }
        binding.btnLastPage.setOnClickListener  { navigatePage(totalPages() - 1) }

        binding.btnNewNotebook.setOnClickListener    { showNewNotebookDialog() }
        binding.btnNewFolder.setOnClickListener      { showNewFolderDialog() }
        binding.btnMore.setOnClickListener           { toggleOverflowToolbar() }
        binding.btnCalendar.setOnClickListener       {
            closeOverflowToolbar()
            CalendarActivity.launch(this)
        }
        // Lives in surfaceButtonsGroup on the wide variants and in the overflow row on sw360dp —
        // one id in every variant, so this wiring is layout-agnostic. Same for btnTasks, which the
        // dashboard displaced from the bar entirely (see the layout comments).
        binding.btnToday.setOnClickListener          {
            closeOverflowToolbar()
            TodayActivity.launch(this)
        }
        binding.btnTasks.setOnClickListener          {
            closeOverflowToolbar()
            TasksActivity.launch(this)
        }
        binding.btnImport.setOnClickListener         {
            closeOverflowToolbar()
            importSoilLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
        binding.btnTemplates.setOnClickListener      {
            closeOverflowToolbar()
            startActivity(
                Intent(this, TemplateBrowserActivity::class.java)
                    .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_MANAGE)
            )
        }
        binding.btnEncryption.setOnClickListener     {
            closeOverflowToolbar()
            startActivity(Intent(this, EncryptionSettingsActivity::class.java))
        }
        binding.btnHwr.setOnClickListener {
            closeOverflowToolbar()
            startActivity(Intent(this, HwrSettingsActivity::class.java))
        }
        binding.btnBackup.setOnClickListener {
            closeOverflowToolbar()
            startActivity(Intent(this, BackupSettingsActivity::class.java))
        }
        // TEMP: legacy-ts compaction sweep — remove after all devices compacted (see BACKLOG.md).
        binding.btnCompact.setOnClickListener {
            closeOverflowToolbar()
            showCompactNotebooksDialog()
        }
        binding.btnBreadcrumbBack.setOnClickListener { navigateUpOneLevel() }
    }

    // ── Grid gesture wiring ───────────────────────────────────────────────────

    private fun setupGridGestures() {
        binding.gridContainer.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) closeOverflowToolbar()
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ── Grid specification computation ────────────────────────────────────────

    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density    = resources.displayMetrics.density
        val gutterPx   = (12 * density).toInt()
        val paddingHPx = (16 * density).toInt()
        val paddingVPx = (16 * density).toInt()
        val rowGapPx   = (6  * density).toInt()
        val labelHeightPx = (32 * density).toInt()

        val screenWidthDp = availableWidth / density
        val cols = if (screenWidthDp >= 480f) 3 else 2

        val dm = resources.displayMetrics
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

    // ── Directory scanning ────────────────────────────────────────────────────

    /** Queries the index for the current level, sorts results, then renders. */
    private fun scanAndRender() {
        lifecycleScope.launch {
            if (isSearchMode) {
                val results = withContext(Dispatchers.IO) {
                    SearchEngine.search(currentSearchQuery, repository)
                }
                searchResults = results
                items = results.map { NotebookListItem.Notebook(it.entity) }
            } else {
                searchResults = emptyList()
                val allChildren = withContext(Dispatchers.IO) {
                    repository.getChildren(currentParentId)
                }

                val pickerState = destinationPickerState
                if (pickerState != DestinationPickerState.None) {
                    val excludedId: String? = when (pickerState) {
                        is DestinationPickerState.CopyFolder -> pickerState.source.id
                        is DestinationPickerState.MoveFolder -> pickerState.source.id
                        else -> null
                    }
                    val folders = allChildren
                        .filter { it.type == ObjectType.FOLDER }
                        .filter { excludedId == null || it.id != excludedId }
                        .map { NotebookListItem.Folder(it) }
                    items = sortItems(folders)
                } else {
                    val folders   = allChildren.filter { it.type == ObjectType.FOLDER }
                        .map { NotebookListItem.Folder(it) }
                    val notebooks = allChildren.filter { it.type == ObjectType.NOTEBOOK }
                        .map { NotebookListItem.Notebook(it) }
                    items = when (sortPrefs.folderSort) {
                        FolderSort.FOLDERS_FIRST   -> sortItems(folders) + sortItems(notebooks)
                        FolderSort.NOTEBOOKS_FIRST -> sortItems(notebooks) + sortItems(folders)
                        FolderSort.MIXED           -> sortItems(folders + notebooks)
                    }
                }
            }

            val total = totalPages()
            currentPage = currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))

            buildBreadcrumbs()
            renderPage()
        }
    }

    private fun sortItems(items: List<NotebookListItem>): List<NotebookListItem> {
        fun nameOf(item: NotebookListItem): String = when (item) {
            is NotebookListItem.Folder   -> item.entity.name.lowercase()
            is NotebookListItem.Notebook -> item.entity.name.lowercase()
        }
        fun dateOf(item: NotebookListItem): Long = when (item) {
            is NotebookListItem.Folder   -> item.entity.updatedAt
            is NotebookListItem.Notebook -> item.entity.updatedAt
        }
        val comparator: Comparator<NotebookListItem> = when (sortPrefs.field) {
            SortField.NAME          -> Comparator { a, b -> nameOf(a).compareTo(nameOf(b)) }
            SortField.DATE_MODIFIED -> Comparator { a, b -> dateOf(a).compareTo(dateOf(b)) }
        }
        val ordered = if (sortPrefs.order == SortOrder.DESCENDING) comparator.reversed() else comparator
        return items.sortedWith(ordered)
    }

    // ── Folder navigation ─────────────────────────────────────────────────────

    private fun navigateIntoFolder(entity: ObjectEntity) {
        directoryStack.add(entity)
        currentPage = 0
        AppStateManager.save(this, AppViewState(entity.id, false))
        scanAndRender()
    }

    // ── Breadcrumb bar ────────────────────────────────────────────────────────

    private fun buildBreadcrumbs() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        val atRoot = directoryStack.size <= 1
        val navVisibility = if (atRoot) View.INVISIBLE else View.VISIBLE
        binding.btnBreadcrumbBack.visibility = navVisibility
        binding.breadcrumbBackDivider.visibility = navVisibility

        if (atRoot) return

        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (12 * density).toInt()
        val sepPad = (6 * density).toInt()

        directoryStack.forEachIndexed { index, entry ->
            if (index > 0) {
                val separator = AppCompatTextView(this).apply {
                    text = "›"
                    setTextColor(inkLightColor)
                    textSize = 18f
                    setPadding(sepPad, 0, sepPad, 0)
                }
                container.addView(separator)
            }

            val label = if (index == 0) "Notebooks" else entry?.name ?: "Notebooks"
            val chip = AppCompatTextView(this).apply {
                text = label
                setTextColor(inkBlackColor)
                textSize = 18f
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    while (directoryStack.size > index + 1) directoryStack.removeLast()
                    currentPage = 0
                    AppStateManager.save(this@MainActivity, AppViewState(currentParentId, false))
                    scanAndRender()
                }
            }
            container.addView(chip)
        }

        binding.breadcrumbScrollView.post {
            binding.breadcrumbScrollView.fullScroll(View.FOCUS_RIGHT)
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun totalPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || items.isEmpty()) return 1
        return (items.size + perPage - 1) / perPage
    }

    private fun renderPage() {
        coverLoadJobs.forEach { it.cancel() }
        coverLoadJobs.clear()

        val spec = gridSpec ?: return
        binding.gridContainer.removeAllViews()

        if (items.isEmpty()) {
            renderEmptyState()
        } else {
            renderGrid(spec)
        }

        updatePaginationControls()
    }

    private fun renderEmptyState() {
        val msg = when {
            destinationPickerState != DestinationPickerState.None -> "No folders here. Create one below."
            isRecentsMode -> "No recent notebooks"
            isSearchMode -> "No notebooks found for \"$currentSearchQuery\""
            isPinnedMode -> "$pinnedListName is currently empty"
            directoryStack.size > 1 -> "Empty folder."
            else -> "No notebooks yet. Tap + to create one."
        }
        val tv = AppCompatTextView(this).apply {
            text = msg
            setTextColor(inkLightColor)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        binding.gridContainer.addView(tv, lp)
    }

    private fun renderGrid(spec: GridSpec) {
        val start     = currentPage * spec.itemsPerPage
        val end       = minOf(start + spec.itemsPerPage, items.size)
        val pageItems = items.subList(start, end)

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
                    val placeholder = Space(this)
                    val totalCellHeight = spec.cardHeightPx + spec.rowGapPx + spec.labelHeightPx
                    row.addView(placeholder, LinearLayout.LayoutParams(spec.cardWidthPx, totalCellHeight))
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

    private fun buildCardGroup(item: NotebookListItem, spec: GridSpec): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            when (item) {
                is NotebookListItem.Folder   -> {
                    setOnClickListener     { navigateIntoFolder(item.entity) }
                    setOnLongClickListener { showFolderContextMenu(item.entity); true }
                }
                is NotebookListItem.Notebook -> {
                    setOnClickListener     { openNotebook(item.entity) }
                    setOnLongClickListener { showNotebookContextMenu(item.entity); true }
                }
            }
        }

        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        card.setPadding(pad1dp, pad1dp, pad1dp, pad1dp)

        val iconSize = (minOf(spec.cardWidthPx, spec.cardHeightPx) * 0.45f).toInt()

        when (item) {
            is NotebookListItem.Folder -> {
                val icon = AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_folder)
                }
                card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            }
            is NotebookListItem.Notebook -> {
                val coverImage = AppCompatImageView(this).apply {
                    scaleType  = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                card.addView(coverImage, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))

                val icon = AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_notebook)
                }
                card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

                // Read snapshot from the index — no .soil file access during list rendering.
                val notebookObj = try {
                    item.entity.notebookMeta()
                } catch (_: Exception) { null }

                if (notebookObj?.encrypted == true && notebookObj.keyScope != KeyScope.GLOBAL) {
                    // Private (NOTEBOOK-scope) encryption: show lock icon; never decode a snapshot.
                    // GLOBAL-scope covers fall through and render — the index is encrypted at rest.
                    icon.setImageResource(R.drawable.ic_lock_cover)
                } else {
                    val snapshotB64 = notebookObj?.snapshot
                    if (snapshotB64 != null) {
                        val job = lifecycleScope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                try {
                                    val bytes = Base64.decode(snapshotB64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (_: Exception) { null }
                            }
                            if (bitmap != null) {
                                coverImage.setImageBitmap(bitmap)
                                coverImage.visibility = View.VISIBLE
                                icon.visibility       = View.GONE
                            }
                        }
                        coverLoadJobs.add(job)
                    }
                }
            }
        }

        // ── Label ─────────────────────────────────────────────────────────────
        // Recents cards stack two lines: "folder › notebook" over the last-opened date/time.
        val recentsResult = if (isRecentsMode && item is NotebookListItem.Notebook) {
            recentsResults.find { it.notebookId == item.entity.id }
        } else null
        if (recentsResult != null) {
            group.addView(
                buildRecentsLabel(recentsResult, spec),
                LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
                    it.topMargin = spec.rowGapPx
                },
            )
            return group
        }

        val labelText = run {
            val entity = when (item) {
                is NotebookListItem.Folder   -> item.entity
                is NotebookListItem.Notebook -> item.entity
            }
            val displayName = entity.name
            if (isSearchMode && item is NotebookListItem.Notebook) {
                val result = searchResults.find { it.entity.id == entity.id }
                if (result != null) {
                    val parent = result.folderLabel.substringAfterLast(" › ")
                    "$parent › ${result.displayName}"
                } else displayName
            } else if (isPinnedMode && item is NotebookListItem.Notebook) {
                val result = pinnedResults.find { it.entity.id == entity.id }
                if (result != null) {
                    val parent = result.folderLabel.substringAfterLast(" › ")
                    "$parent › ${result.displayName}"
                } else displayName
            } else {
                val modified = Date(entity.updatedAt)
                val dateStr = android.text.format.DateFormat.getMediumDateFormat(this).format(modified)
                val timeStr = android.text.format.DateFormat.getTimeFormat(this).format(modified)
                "$displayName ($dateStr, $timeStr)"
            }
        }
        val label = AppCompatTextView(this).apply {
            text      = labelText
            maxLines  = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
            textSize  = 14f
            setTextColor(inkBlackColor)
        }
        group.addView(label, LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
            it.topMargin = spec.rowGapPx
        })

        return group
    }

    /**
     * Two-line label for a recents card: "folder › notebook" over the last-opened date/time.
     * Both lines are single-line + ellipsized to stay within [GridSpec.labelHeightPx].
     */
    private fun buildRecentsLabel(result: ResolvedRecent, spec: GridSpec): LinearLayout {
        val parent = result.folderPath.substringAfterLast(" › ")
        val opened = Date(result.timestamp)
        val dateStr = android.text.format.DateFormat.getMediumDateFormat(this).format(opened)
        val timeStr = android.text.format.DateFormat.getTimeFormat(this).format(opened)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            addView(AppCompatTextView(this@MainActivity).apply {
                text      = "$parent › ${result.notebookName}"
                maxLines  = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity   = Gravity.CENTER
                textSize  = 13f
                setTextColor(inkBlackColor)
            })
            addView(AppCompatTextView(this@MainActivity).apply {
                text      = "$dateStr, $timeStr"
                maxLines  = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity   = Gravity.CENTER
                textSize  = 11f
                setTextColor(inkLightColor)
            })
        }
    }

    // ── Notebook opening ──────────────────────────────────────────────────────

    private fun openNotebook(entity: ObjectEntity) {
        launchNotebookActivity(entity)
    }

    private fun launchScratchpad() {
        startActivity(Intent(this, ScratchpadActivity::class.java))
    }

    /** Debounce for notebook-card taps — see [launchNotebookActivity]. */
    private var lastNotebookLaunchAt = 0L

    private fun launchNotebookActivity(entity: ObjectEntity) {
        // E-ink refresh lag invites double-taps; two rapid taps used to stack two activities on
        // the same .soil (benign for data — insert-only ink — but thoroughly confusing on screen).
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotebookLaunchAt < 800) return
        lastNotebookLaunchAt = now
        // Tap-time "Opening…" overlay: shown here, launched once its frame is on screen, and the
        // destination notebook keeps it up until the first page renders. See OpeningOverlay.
        com.notesprout.android.core.OpeningOverlay.showThen(this) {
            startActivity(
                Intent(this, NotebookActivity::class.java).apply {
                    putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID,   entity.id)
                    putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, entity.name)
                }
            )
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigatePage(page: Int) {
        val clamped = page.coerceIn(0, (totalPages() - 1).coerceAtLeast(0))
        if (clamped == currentPage) return
        currentPage = clamped
        renderPage()
    }

    private fun updatePaginationControls() {
        val total   = totalPages()
        val display = currentPage + 1

        binding.tvPage.text = "$display/$total"

        val atFirst = currentPage == 0
        val atLast  = currentPage >= total - 1

        binding.btnFirstPage.isEnabled = !atFirst
        binding.btnPrevPage.isEnabled  = !atFirst
        binding.btnNextPage.isEnabled  = !atLast
        binding.btnLastPage.isEnabled  = !atLast
    }

    // ── New notebook dialog ───────────────────────────────────────────────────

    private fun showNewNotebookDialog() {
        val intent = Intent(this, TemplateBrowserActivity::class.java)
            .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK)
            .putExtra(TemplateBrowserActivity.EXTRA_COLLECT_NAME, true)
            .putExtra(TemplateBrowserActivity.EXTRA_TITLE, "New Notebook")
        currentParentId?.let { intent.putExtra(TemplateBrowserActivity.EXTRA_TARGET_PARENT_ID, it) }
        newNotebookLauncher.launch(intent)
    }

    // ── New folder dialog ─────────────────────────────────────────────────────

    /**
     * A notebook stepped back here because its `.soil` would not open ([NotebookOpenFailure]).
     * Explain it rather than leaving the user with a screen that just bounced them home.
     *
     * The copy is **deliberately verbose while dogfooding** — it names the likely cause, states
     * plainly that the file was left intact (the important reassurance, since the last time this
     * class of failure went unhandled it destroyed a notebook), and offers the raw exception chain
     * behind a second tap for reporting. Trim this to a one-liner before a public release.
     */
    private fun showNotebookOpenFailureIfAny() {
        val report = NotebookOpenFailure.take() ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Couldn't open “${report.notebookName}”")
            .setMessage(
                report.diagnosis +
                    "\n\nYour notebook file has not been changed, deleted, or rewritten — it is " +
                    "still on disk exactly as it was. Everything else in your library is unaffected."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Details") { _, _ -> showOpenFailureDetail(report) }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    /** The raw exception chain behind the failure, for copying into a bug report while dogfooding. */
    private fun showOpenFailureDetail(report: NotebookOpenFailure.Report) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Technical detail")
            .setMessage(report.technical)
            .setPositiveButton("OK", null)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun showNewFolderDialog() {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.setText("")
        dialogBinding.editNotebookName.hint = "Folder name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    val error = validateFolderName(name)
                    if (error != null) {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    } else {
                        val entity = withContext(Dispatchers.IO) {
                            repository.createFolder(name, currentParentId)
                        }
                        navigateIntoFolder(entity)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
            }
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    /**
     * DEBUG ONLY — re-key a notebook to a throwaway passphrase to exercise [NotebookRecovery].
     *
     * Reproduces the real failure exactly: the file is re-encrypted (new salt, new passphrase) while
     * the index still says GLOBAL **and the cached raw key is deliberately left in place**. That is
     * what makes the next open take KeyResolver's skip-verify shortcut, hand KeyOpener a stale key,
     * fail past [SelfHealingKeyFactory]'s passphrase retry, and land in the recovery dialog — the
     * same chain that stranded notebook 0e5161f1. Enter the throwaway passphrase there to recover.
     *
     * Never ship an entry point to this: it makes a notebook unopenable by ordinary means.
     */
    private fun showBreakKeyingDialog(entity: ObjectEntity, encInfo: EncryptionInfo) {
        AlertDialog.Builder(this)
            .setTitle("Break Keying (debug)")
            .setMessage(
                "Re-encrypts \"${entity.name}\" with a throwaway passphrase, leaving the index and the " +
                "cached key untouched — so the next open fails the way a restored-from-backup notebook does.\n\n" +
                "You'll need the passphrase you set here to recover it. Debug builds only."
            )
            .setPositiveButton("Break It") { _, _ ->
                lifecycleScope.launch {
                    val rogue = PassphrasePrompt.promptForPassphrase(
                        this@MainActivity,
                        title = "Throwaway Passphrase",
                        message = "Set the passphrase the notebook will be re-encrypted with. Remember it — " +
                            "it's what you'll type into the recovery dialog.",
                        confirm = true,
                    ) ?: return@launch
                    val current = KeyResolver.resolveCurrentKeyForRekey(this@MainActivity, entity.id, encInfo)
                        ?: return@launch
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { SoilMigrator.rekeyInPlace(soilFile(this@MainActivity, entity.id), current, rogue) }
                    }.isSuccess
                    // NOTE: deliberately NOT calling KeyMaterial.invalidate — the stale cached key is
                    // the whole point of the repro.
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Keying broken. Open it to test recovery." else "Break failed — notebook unchanged.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
    }

    // ── Rename notebook dialog ────────────────────────────────────────────────

    private fun showRenameNotebookDialog(entity: ObjectEntity) {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.setText(entity.name)
        dialogBinding.editNotebookName.setSelection(entity.name.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Notebook")
            .setView(dialogBinding.root)
            .setPositiveButton("Rename") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                val error = validateNotebookName(name)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                } else if (name != entity.name) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repository.renameNotebook(entity.id, name) }
                        refreshActiveView()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
            }
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    // ── Rename folder dialog ──────────────────────────────────────────────────

    private fun showRenameFolderDialog(entity: ObjectEntity) {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.hint = "Folder name"
        dialogBinding.editNotebookName.setText(entity.name)
        dialogBinding.editNotebookName.setSelection(entity.name.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Folder")
            .setView(dialogBinding.root)
            .setPositiveButton("Rename") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    val error = validateFolderRename(name, entity)
                    if (error != null) {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    } else if (name != entity.name) {
                        withContext(Dispatchers.IO) { repository.renameFolder(entity.id, name) }
                        refreshActiveView()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
            }
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    /** Re-renders whichever browse view is currently active after a mutation. */
    private fun refreshActiveView() {
        when {
            isRecentsMode -> renderRecentsList()
            isPinnedMode  -> lifecycleScope.launch { renderPinnedList() }
            else          -> scanAndRender()
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates a proposed notebook name. UUID filenames never collide, so no file-existence
     * check is needed. Returns a user-facing error string, or null if valid.
     */
    private fun validateNotebookName(name: String): String? {
        if (name.isBlank()) return "Notebook name cannot be empty"
        if (name == "." || name == "..") return "Invalid notebook name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        return null
    }

    private suspend fun validateFolderName(name: String): String? {
        if (name.isBlank()) return "Folder name cannot be empty"
        if (name == "." || name == "..") return "Invalid folder name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        val siblings = withContext(Dispatchers.IO) { repository.getFolders(currentParentId) }
        if (siblings.any { it.name.equals(name, ignoreCase = true) }) {
            return "A folder named \"$name\" already exists"
        }
        return null
    }

    /**
     * Validates a folder rename. Checks duplicates against the folder's own siblings (its actual
     * parent, which may differ from the current browse folder) and excludes the folder itself.
     */
    private suspend fun validateFolderRename(name: String, entity: ObjectEntity): String? {
        if (name.isBlank()) return "Folder name cannot be empty"
        if (name == "." || name == "..") return "Invalid folder name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        val siblings = withContext(Dispatchers.IO) { repository.getFolders(entity.parentId) }
        if (siblings.any { it.id != entity.id && it.name.equals(name, ignoreCase = true) }) {
            return "A folder named \"$name\" already exists"
        }
        return null
    }

    // ── Notebook creation ─────────────────────────────────────────────────────

    private suspend fun createNotebook(name: String, libraryTemplateId: String = "", scope: KeyScope? = null) {
        try {
            validateNotebookName(name)?.let { error ->
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                return
            }

            // Resolve the encryption key before touching the index — abort cleanly on cancel.
            val key: String? = if (scope != null) {
                KeyResolver.resolveForConvertToEncrypted(this, scope) ?: return
            } else null

            // 1. Create the index entry — its id becomes the filename.
            val entity = withContext(Dispatchers.IO) {
                repository.createNotebook(name, currentParentId)
            }

            // Load the chosen library template (if any) before opening the .soil.
            data class SeedTemplate(val width: Int, val height: Int, val name: String, val image: String)
            val seed: SeedTemplate? = if (libraryTemplateId.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val e = repository.getTemplate(libraryTemplateId)
                    val t = e?.templateObject()
                    if (e != null && t != null && t.image.isNotEmpty())
                        SeedTemplate(t.width, t.height, e.name, t.image) else null
                }
            } else null

            // 2. Create the physical .soil file at its UUID path.
            val soilPath = soilFile(this@MainActivity, entity.id)

            // Screen bounds are needed on the main thread before the IO block.
            val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                val dm = resources.displayMetrics
                android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }

            withContext(Dispatchers.IO) {
                // Open with SQLCipher for encrypted notebooks, standard SQLite otherwise.
                // Both DB types expose identical execSQL / rawQuery APIs.
                val exec: (String, Array<Any?>?) -> Unit
                val pragma: (String) -> Unit
                val closeDb: () -> Unit
                if (key != null) {
                    val db = SoilCrypto.createRawEncrypted(soilPath, key)
                    exec = { sql, args -> if (args != null) db.execSQL(sql, args) else db.execSQL(sql) }
                    pragma = { sql -> db.rawQuery(sql, null).use { it.moveToFirst() } }
                    closeDb = { db.close() }
                } else {
                    val db = SoilCrypto.createRawPlaintext(soilPath)
                    exec = { sql, args -> if (args != null) db.execSQL(sql, args) else db.execSQL(sql) }
                    pragma = { sql -> db.rawQuery(sql, null).use { it.moveToFirst() } }
                    closeDb = { db.close() }
                }

                try {
                    pragma("PRAGMA journal_mode = WAL")
                    pragma("PRAGMA wal_autocheckpoint = 100")
                    pragma("PRAGMA auto_vacuum = INCREMENTAL")

                    exec(SoilSchema.CREATE_NOTEBOOK_TABLE, null)
                    exec(SoilSchema.CREATE_NOTEBOOK_INDEX, null)
                    // Meta table for undo/redo persistence inside encrypted .soil files (P2.S3).
                    // Plaintext notebooks never write to this table; Room migration 1→2 adds it
                    // to existing notebooks. id = 0 is the only row.
                    exec(
                        "CREATE TABLE IF NOT EXISTS undo_redo_state " +
                        "(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
                        null
                    )
                    // Self-describing export metadata (S1). Single row; encrypted at rest for free.
                    exec(
                        "CREATE TABLE IF NOT EXISTS notebook_meta " +
                        "(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
                        null
                    )

                    val screenW = screenBounds.width().toFloat()
                    val screenH = screenBounds.height().toFloat()
                    val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()
                    val now = System.currentTimeMillis()

                    // Columnar (Phase 2b): notebook/page/template/layer write typed columns, data = "".
                    // text = notebook.title / template.name / layer.label; refId = notebook.lastOpenedPage
                    // / page.template; flags = layer bits; blob = template image bytes.
                    val insertSql =
                        """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data, text, refId, flags, blob)
                           VALUES (?, ?, ?, 0, ?, ?, NULL, ?, '', ?, ?, ?, ?)"""

                    val notebookId = UUID.randomUUID().toString()
                    val pageId     = UUID.randomUUID().toString()

                    exec(insertSql, arrayOf(notebookId, "", "{}", now, now, "notebook", name, pageId, null, null))

                    val firstPageTemplate = if (seed != null) UUID.randomUUID().toString() else ""
                    exec(insertSql, arrayOf(pageId, notebookId, bboxJson, now, now, "page", null, firstPageTemplate, null, null))
                    if (seed != null) {
                        val tmplBbox = BoundingBox(0f, 0f, seed.width.toFloat(), seed.height.toFloat()).toJson()
                        exec(insertSql, arrayOf(
                            firstPageTemplate, notebookId, tmplBbox, now, now, "template",
                            seed.name, null, null, com.notesprout.android.data.templateImageBlob(seed.image),
                        ))
                    }

                    val layerId = UUID.randomUUID().toString()
                    exec(insertSql, arrayOf(
                        layerId, pageId, bboxJson, now, now, "layer",
                        "Content", null, com.notesprout.android.data.LAYER_FLAGS_DEFAULT, null,
                    ))

                    val folderPath = repository.getFolderAncestry(currentParentId)
                    val initialMeta = NotebookMeta(
                        notebookId     = entity.id,
                        name           = name,
                        createdAt      = entity.createdAt,
                        updatedAt      = entity.updatedAt,
                        encrypted      = scope != null,
                        keyScope       = scope,
                        cover          = null,
                        folderPath     = folderPath,
                        appVersionCode = BuildConfig.VERSION_CODE,
                    )
                    exec(
                        "INSERT OR REPLACE INTO notebook_meta (id, json) VALUES (0, ?)",
                        arrayOf(initialMeta.toJson())
                    )

                    pragma("PRAGMA incremental_vacuum")
                    pragma("PRAGMA wal_checkpoint(TRUNCATE)")
                } finally {
                    closeDb()
                }

                // Remove any 0-byte rollback journal from initialisation.
                java.io.File("${soilPath.absolutePath}-journal").takeIf { it.exists() }?.delete()
            }

            // Record encryption state in the index (also clears snapshot for encrypted notebooks).
            if (scope != null) {
                withContext(Dispatchers.IO) {
                    repository.setEncryptionState(entity.id, encrypted = true, keyScope = scope)
                }
                // Derive + cache the raw key now so the notebook's first open is a fast raw-key open.
                if (key != null) com.notesprout.android.crypto.KeyOpener.warm(this, entity.id, soilPath, scope, key)
            }

            Toast.makeText(this@MainActivity, "Notebook '$name' created", Toast.LENGTH_SHORT).show()

            if (libraryTemplateId.isNotEmpty()) {
                TemplateRecentsManager.recordUse(this@MainActivity, libraryTemplateId)
            }

            // Rescan and navigate to the page containing the new notebook.
            scanAndRender()
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                val idx = items.indexOfFirst {
                    it is NotebookListItem.Notebook && it.entity.id == entity.id
                }
                if (idx >= 0) navigatePage(idx / spec.itemsPerPage)
            }

            // For notebook-scoped encryption, seed the single-use cache so the immediate open
            // doesn't prompt — the user just typed the passphrase twice to set it.
            if (scope == KeyScope.NOTEBOOK && key != null) {
                PassphraseCache.storeOnce(entity.id, key)
            }

            // Started from the Today dashboard: rebuild it under the notebook, so backing out of
            // the notebook returns where the "+" was tapped rather than to the library. Started
            // first, and on its own — the dashboard reads everything fresh from "now", so a new
            // instance is indistinguishable from the one that was popped getting here.
            if (returnToTodayAfterCreate) {
                returnToTodayAfterCreate = false
                startActivity(TodayActivity.intent(this@MainActivity))
            }

            // Open the new notebook immediately.
            launchNotebookActivity(entity)

        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Notebook context menu ─────────────────────────────────────────────────

    private fun showNotebookContextMenu(entity: ObjectEntity) {
        lifecycleScope.launch {
            val pinned = withContext(Dispatchers.IO) { repository.isNotebookPinned(entity.id) }
            val encInfo = withContext(Dispatchers.IO) { repository.getEncryptionInfo(entity.id) }
            val excluded = try { entity.notebookMeta().excludeFromBackup } catch (_: Exception) { false }
            val pinIcon  = if (pinned) R.drawable.ic_pinned_off else R.drawable.ic_pinned
            val pinLabel = if (pinned) "Unpin Notebook" else "Pin Notebook"
            val menu = ActionSheetDialog(this@MainActivity)
                .title(entity.name)
                .addAction(pinIcon,                       pinLabel)          {
                    lifecycleScope.launch {
                        val nowPinned = withContext(Dispatchers.IO) { repository.togglePin(entity.id) }
                        Toast.makeText(this@MainActivity,
                            if (nowPinned) "Pinned." else "Unpinned.",
                            Toast.LENGTH_SHORT).show()
                        if (isPinnedMode) renderPinnedList()
                    }
                }
                .addAction(R.drawable.ic_export,          "Export")          { startExportFromMain(entity) }
                .addAction(R.drawable.ic_copy_page,       "Copy Notebook")   { enterPickerMode(DestinationPickerState.CopyNotebook(entity)) }
                .addAction(R.drawable.ic_move_page,       "Move Notebook")   { enterPickerMode(DestinationPickerState.MoveNotebook(entity)) }
                .addAction(R.drawable.ic_edit,            "Rename Notebook") { showRenameNotebookDialog(entity) }
            if (!encInfo.encrypted) {
                menu.addAction(R.drawable.ic_lock,     "Encrypt Notebook") { showEncryptNotebookDialog(entity) }
            } else {
                // No decrypt-to-plaintext under encrypt-everything — scope is toggled between the
                // device-global and a private notebook passphrase, both still encrypted.
                menu.addAction(R.drawable.ic_edit,     "Change Passphrase") { showChangePassphraseDialog(entity, encInfo) }
                menu.addAction(R.drawable.ic_lock,     "Change Encryption Scope") { showChangeScopeDialog(entity, encInfo) }
            }
            val backupLabel = if (excluded) "Include in Backup" else "Exclude from Backup"
            menu.addAction(R.drawable.ic_backup, backupLabel) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.setNotebookExcludedFromBackup(entity.id, !excluded)
                    }
                }
            }
            menu.addAction(R.drawable.ic_delete_notebook, "Delete Notebook") { showDeleteNotebookConfirmation(entity) }
            if (BuildConfig.DEBUG && encInfo.encrypted) {
                menu.addAction(R.drawable.ic_lock, "Break Keying (debug)") { showBreakKeyingDialog(entity, encInfo) }
            }
            menu.show()
        }
    }

    // ── Phase 4: one-time bulk-convert offer ──────────────────────────────────

    /**
     * On a normal cold launch (after onboarding), if any plaintext notebooks remain, offer once to
     * bulk-encrypt them under the global passphrase. Guarded by a one-time flag so it never nags; if
     * the user declines they can still run it from Encryption settings. Skipped for .soil deep-links
     * so the offer never collides with an import flow.
     */
    private fun maybeOfferBulkEncryption() {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) return
        val prefs = getSharedPreferences("notesprout_onboarding", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONVERSION_OFFERED, false)) return
        lifecycleScope.launch {
            val hasGlobal = withContext(Dispatchers.IO) {
                com.notesprout.android.crypto.PassphraseStore.getGlobalPassphrase(this@MainActivity) != null
            }
            if (!hasGlobal) return@launch
            // If a sweep is already mid-flight, the Encryption-settings Resume banner owns it.
            if (com.notesprout.android.crypto.GlobalConversion.hasMarker(this@MainActivity)) return@launch
            val plaintextIds = withContext(Dispatchers.IO) { repository.getPlaintextNotebookIds() }
            prefs.edit().putBoolean(KEY_CONVERSION_OFFERED, true).apply()
            if (plaintextIds.isEmpty()) return@launch

            val count = plaintextIds.size
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Encrypt your notebooks?")
                .setMessage(
                    "You have $count unencrypted notebook${if (count == 1) "" else "s"}. " +
                    "Encrypt ${if (count == 1) "it" else "them"} now with your global passphrase? " +
                    "You can also do this later from Encryption settings."
                )
                .setPositiveButton("Encrypt Now") { _, _ -> runBulkConversion() }
                .setNegativeButton("Later", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setElevation(0f)
                dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
            dialog.show()
        }
    }

    private fun runBulkConversion() {
        lifecycleScope.launch {
            val globalPass = withContext(Dispatchers.IO) {
                com.notesprout.android.crypto.PassphraseStore.getGlobalPassphrase(this@MainActivity)
            } ?: return@launch
            val cancelSignal = java.util.concurrent.atomic.AtomicBoolean(false)

            val tvMessage = android.widget.TextView(this@MainActivity).apply {
                text = "Encrypting…"
                setPadding(64, 48, 64, 48)
                setTextColor(android.graphics.Color.BLACK)
                textSize = 16f
            }
            val progress = AlertDialog.Builder(this@MainActivity)
                .setView(tvMessage)
                .setNegativeButton("Cancel") { _, _ -> cancelSignal.set(true) }
                .setCancelable(false)
                .create()
            progress.show()
            progress.window?.setElevation(0f)
            progress.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

            val result = try {
                com.notesprout.android.crypto.GlobalConversion.start(
                    context = this@MainActivity,
                    repository = repository,
                    globalPassphrase = globalPass,
                    onProgress = { done, total ->
                        withContext(Dispatchers.Main) { tvMessage.text = "Encrypting $done / $total…" }
                    },
                    cancelSignal = cancelSignal,
                )
            } catch (e: Exception) {
                com.notesprout.android.crypto.GlobalConversion.Result.Failed(e.message ?: "unknown error")
            } finally {
                progress.dismiss()
            }

            val msg = when (result) {
                is com.notesprout.android.crypto.GlobalConversion.Result.Complete -> buildString {
                    append("Encrypted ${result.converted} notebook${if (result.converted == 1) "" else "s"}.")
                    if (result.skipped > 0) append(" ${result.skipped} couldn't be encrypted and were left as-is.")
                }
                is com.notesprout.android.crypto.GlobalConversion.Result.Cancelled ->
                    "Paused. ${result.converted} encrypted, ${result.remaining} remaining. Resume from Encryption settings."
                is com.notesprout.android.crypto.GlobalConversion.Result.Failed ->
                    "Encryption failed: ${result.message}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            scanAndRender()
        }
    }

    // ── Notebook encrypt / decrypt ────────────────────────────────────────────

    private fun showEncryptNotebookDialog(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title("Encrypt Notebook")
            .addAction(null, "Encrypt (Global Passphrase)") {
                lifecycleScope.launch { encryptNotebook(entity, KeyScope.GLOBAL) }
            }
            .addAction(null, "Encrypt (Notebook Passphrase)") {
                lifecycleScope.launch { encryptNotebook(entity, KeyScope.NOTEBOOK) }
            }
            .show()
    }

    private suspend fun encryptNotebook(entity: ObjectEntity, scope: KeyScope) {
        val key = KeyResolver.resolveForConvertToEncrypted(this, scope) ?: return

        val tvMessage = android.widget.TextView(this).apply {
            text = "Encrypting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        try {
            val file = soilFile(this, entity.id)
            withContext(Dispatchers.IO) { SoilMigrator.encryptInPlace(file, key) }
            withContext(Dispatchers.IO) { repository.setEncryptionState(entity.id, encrypted = true, keyScope = scope) }
            com.notesprout.android.crypto.KeyOpener.warm(this, entity.id, file, scope, key)
            dialog.dismiss()
            scanAndRender()
        } catch (e: Exception) {
            dialog.dismiss()
            Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Notebook re-key: change passphrase / change scope ────────────────────

    private fun showChangePassphraseDialog(entity: ObjectEntity, encInfo: EncryptionInfo) {
        if (encInfo.keyScope == KeyScope.GLOBAL) {
            AlertDialog.Builder(this)
                .setTitle("Change Passphrase")
                .setMessage("Global notebooks share a device passphrase. To change it, use the More (…) menu → Encryption.")
                .setPositiveButton("Open Encryption Settings") { _, _ ->
                    startActivity(Intent(this, EncryptionSettingsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                }
            return
        }
        lifecycleScope.launch { changePassphrase(entity, encInfo) }
    }

    private suspend fun changePassphrase(entity: ObjectEntity, encInfo: EncryptionInfo) {
        val oldKey = KeyResolver.resolveCurrentKeyForRekey(this, entity.id, encInfo) ?: return
        val newKey = KeyResolver.resolveForConvertToEncrypted(this, KeyScope.NOTEBOOK) ?: return

        val tvMessage = android.widget.TextView(this).apply {
            text = "Updating…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        try {
            val file = soilFile(this, entity.id)
            withContext(Dispatchers.IO) { SoilMigrator.rekeyInPlace(file, oldKey, newKey) }
            // Salt changed on re-key — the old cached raw key is stale.
            com.notesprout.android.crypto.KeyMaterial.invalidate(this, entity.id)
            // Invalidate any in-process session so the old key isn't reused.
            if (KeySession.entry?.notebookId == entity.id) KeySession.clear()
            dialog.dismiss()
            Toast.makeText(this, "Passphrase updated.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            dialog.dismiss()
            Toast.makeText(this, "Passphrase change failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showChangeScopeDialog(entity: ObjectEntity, encInfo: EncryptionInfo) {
        val (newScopeLabel, message) = when (encInfo.keyScope) {
            KeyScope.NOTEBOOK -> "Switch to Global Passphrase" to
                "\"${entity.name}\" will be re-keyed to use the device global passphrase. You will need the current notebook passphrase to proceed."
            KeyScope.GLOBAL -> "Switch to Notebook Passphrase" to
                "\"${entity.name}\" will be re-keyed to use a dedicated passphrase. You will need the current global passphrase and a new notebook passphrase."
            null -> return
        }
        AlertDialog.Builder(this)
            .setTitle("Change Encryption Scope")
            .setMessage(message)
            .setPositiveButton(newScopeLabel) { _, _ ->
                lifecycleScope.launch { changeScope(entity, encInfo) }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
    }

    private suspend fun changeScope(entity: ObjectEntity, encInfo: EncryptionInfo) {
        val currentScope = encInfo.keyScope ?: return
        // GLOBAL is already cached in memory, so switching a global notebook to a private passphrase
        // doesn't need the global re-entered — mirrors the export flow. Only a NOTEBOOK-scope source
        // has an uncached passphrase worth prompting for.
        val oldKey = if (currentScope == KeyScope.GLOBAL)
            PassphraseStore.getGlobalPassphrase(this)
        else
            KeyResolver.resolveCurrentKeyForRekey(this, entity.id, encInfo)
        if (oldKey == null) return
        val newScope = when (currentScope) {
            KeyScope.NOTEBOOK -> KeyScope.GLOBAL
            KeyScope.GLOBAL   -> KeyScope.NOTEBOOK
        }
        val newKey = KeyResolver.resolveForConvertToEncrypted(this, newScope) ?: return

        val tvMessage = android.widget.TextView(this).apply {
            text = "Updating…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        try {
            val file = soilFile(this, entity.id)
            withContext(Dispatchers.IO) { SoilMigrator.rekeyInPlace(file, oldKey, newKey) }
            withContext(Dispatchers.IO) { repository.setEncryptionState(entity.id, encrypted = true, keyScope = newScope) }
            // Salt changed on re-key — drop the stale cached key, then warm the new one for GLOBAL.
            com.notesprout.android.crypto.KeyMaterial.invalidate(this, entity.id)
            com.notesprout.android.crypto.KeyOpener.warm(this, entity.id, file, newScope, newKey)
            if (KeySession.entry?.notebookId == entity.id) KeySession.clear()
            dialog.dismiss()
            scanAndRender()
            val scopeLabel = if (newScope == KeyScope.GLOBAL) "global" else "notebook"
            Toast.makeText(this, "Scope changed to $scopeLabel passphrase.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            dialog.dismiss()
            Toast.makeText(this, "Scope change failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Folder context menu ───────────────────────────────────────────────────

    private fun showFolderContextMenu(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title(entity.name)
            .addAction(R.drawable.ic_copy_plus,       "Copy Folder") { enterPickerMode(DestinationPickerState.CopyFolder(entity)) }
            .addAction(R.drawable.ic_move_page,       "Move Folder") { enterPickerMode(DestinationPickerState.MoveFolder(entity)) }
            .addAction(R.drawable.ic_edit,            "Rename Folder") { showRenameFolderDialog(entity) }
            .addAction(R.drawable.ic_folder_minus,    "Delete")      { showDeleteFolderConfirmation(entity) }
            .show()
    }

    // ── Destination picker mode ───────────────────────────────────────────────

    private fun enterPickerMode(state: DestinationPickerState) {
        clearRecentsMode()
        if (isSearchMode) {
            isSearchMode = false
            currentSearchQuery = ""
            searchResults = emptyList()
        }
        destinationPickerState = state
        applyPickerModeUI()
        currentPage = 0
        scanAndRender()
    }

    private fun exitPickerMode() {
        destinationPickerState = DestinationPickerState.None
        currentPage = 0
        applyPickerModeUI()
        scanAndRender()
    }

    private fun applyPickerModeUI() {
        val inPicker = destinationPickerState != DestinationPickerState.None
        binding.pickerToolbar.visibility        = if (inPicker) View.VISIBLE else View.GONE
        binding.pickerToolbarDivider.visibility = if (inPicker) View.VISIBLE else View.GONE
        if (inPicker) {
            updatePickerTitle()
            binding.btnNewNotebook.visibility   = View.GONE
            binding.btnMore.visibility          = View.GONE
            binding.btnSearch.visibility        = View.GONE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.GONE
            binding.btnPinned.visibility        = View.GONE
            binding.btnRecents.visibility       = View.GONE
            binding.surfaceButtonsGroup.visibility = View.GONE
            closeOverflowToolbar()
        } else {
            binding.btnNewNotebook.visibility   = View.VISIBLE
            binding.btnNewFolder.visibility     = View.VISIBLE
            binding.btnMore.visibility          = View.VISIBLE
            binding.btnSearch.visibility        = View.VISIBLE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.VISIBLE
            binding.btnPinned.visibility        = View.VISIBLE
            binding.btnRecents.visibility       = View.VISIBLE
            binding.surfaceButtonsGroup.visibility = View.VISIBLE
        }
    }

    private fun updatePickerTitle() {
        val (title, confirmLabel) = when (destinationPickerState) {
            is DestinationPickerState.NewNotebook    -> "New notebook here"   to "Create here"
            is DestinationPickerState.CopyNotebook   -> "Copy notebook here"  to "Copy here"
            is DestinationPickerState.MoveNotebook   -> "Move notebook here"  to "Move here"
            is DestinationPickerState.CopyFolder     -> "Copy folder here"    to "Copy here"
            is DestinationPickerState.MoveFolder     -> "Move folder here"    to "Move here"
            is DestinationPickerState.ImportNotebook -> "Place notebook here" to "Confirm"
            DestinationPickerState.None              -> "" to ""
        }
        binding.pickerTitle.text = title
        binding.btnPickerConfirm.text = confirmLabel
    }

    private fun confirmPickerDestination() {
        val state = destinationPickerState
        if (state == DestinationPickerState.None) return

        // Import picker: separate branch — no "source entity", just the in-flight import context.
        if (state is DestinationPickerState.ImportNotebook) {
            confirmImportPickerDestination(state)
            return
        }

        // New-notebook picker: the current folder is the destination. Exit the picker (keeping the
        // navigated-to folder as currentParent) and hand off to the normal new-notebook flow.
        if (state is DestinationPickerState.NewNotebook) {
            exitPickerMode()
            showNewNotebookDialog()
            return
        }

        lifecycleScope.launch {
            val source: ObjectEntity = when (state) {
                is DestinationPickerState.CopyNotebook -> state.source
                is DestinationPickerState.MoveNotebook -> state.source
                is DestinationPickerState.CopyFolder   -> state.source
                is DestinationPickerState.MoveFolder   -> state.source
                else                                   -> return@launch
            }

            // Validate destination.
            when (state) {
                is DestinationPickerState.CopyNotebook,
                is DestinationPickerState.MoveNotebook -> {
                    if (currentParentId == source.parentId) {
                        Toast.makeText(this@MainActivity, "Already in this folder", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                is DestinationPickerState.CopyFolder,
                is DestinationPickerState.MoveFolder -> {
                    if (isSelfOrDescendant(currentParentId, source.id)) {
                        val verb = if (state is DestinationPickerState.CopyFolder) "copy" else "move"
                        Toast.makeText(this@MainActivity, "Cannot $verb a folder into itself", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                else -> return@launch
            }

            // Check for name conflict in the target folder.
            val existingChild = withContext(Dispatchers.IO) {
                repository.getChildren(currentParentId).find {
                    it.name == source.name && it.id != source.id
                }
            }

            if (existingChild != null) {
                val itemType = when (state) {
                    is DestinationPickerState.CopyNotebook,
                    is DestinationPickerState.MoveNotebook -> "notebook"
                    else -> "folder"
                }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setMessage("A $itemType named \"${source.name}\" already exists here. Replace it?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace") { _, _ ->
                        executePickerOperation(state, source, existingChild.id)
                    }
                    .create()
                dialog.show()
                dialog.window?.setElevation(0f)
                dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            } else {
                executePickerOperation(state, source, null)
            }
        }
    }

    private fun confirmImportPickerDestination(state: DestinationPickerState.ImportNotebook) {
        val targetParentId = currentParentId
        destinationPickerState = DestinationPickerState.None
        applyPickerModeUI()

        lifecycleScope.launch {
            val conflict = withContext(Dispatchers.IO) {
                repository.getNotebooks(targetParentId).find { it.name == state.displayName }
            }
            if (conflict != null) {
                showImportNameConflictDialog(
                    state.manifest, state.tempFile, state.displayName,
                    targetParentId, state.resolvedId, conflict.id, state.enteredPass
                )
            } else {
                executeImport(state.manifest, state.tempFile, state.displayName, targetParentId, state.resolvedId, state.enteredPass)
            }
        }
    }

    /** Returns true if [folderId] is [sourceId] itself or has [sourceId] as an ancestor. */
    private suspend fun isSelfOrDescendant(folderId: String?, sourceId: String): Boolean {
        if (folderId == null) return false
        if (folderId == sourceId) return true
        var id = folderId
        while (id != null) {
            val folder = repository.getFolder(id) ?: break
            id = folder.parentId
            if (id == sourceId) return true
        }
        return false
    }

    private fun executePickerOperation(
        state: DestinationPickerState,
        source: ObjectEntity,
        conflictId: String?,
    ) {
        val isCopy = state is DestinationPickerState.CopyNotebook ||
                state is DestinationPickerState.CopyFolder
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // The conflicting entry is only deleted AFTER the operation succeeds (below).
                    // Deleting it up front destroyed the user's notebook even when the copy/move
                    // then failed or was interrupted.
                    when (state) {
                        is DestinationPickerState.MoveNotebook -> {
                            repository.moveObject(source.id, currentParentId)
                            refreshNotebookMeta(source.id)
                            true
                        }
                        is DestinationPickerState.CopyNotebook -> {
                            val sourceObj = try {
                                source.notebookMeta()
                            } catch (_: Exception) { NotebookObject() }
                            val newEntity = repository.createNotebook(source.name, currentParentId)
                            // Encrypted notebooks never expose a plaintext snapshot.
                            if (sourceObj.snapshot != null && !sourceObj.encrypted) {
                                repository.updateNotebookSnapshot(newEntity.id, sourceObj.snapshot)
                            }
                            if (sourceObj.pageCount > 0) {
                                repository.updateNotebookPageCount(newEntity.id, sourceObj.pageCount)
                            }
                            // Propagate encryption state so the copy opens with the same passphrase.
                            if (sourceObj.encrypted) {
                                repository.setEncryptionState(newEntity.id, encrypted = true, keyScope = sourceObj.keyScope)
                            }
                            val srcFile = soilFile(this@MainActivity, source.id)
                            if (srcFile.exists()) {
                                srcFile.copyTo(soilFile(this@MainActivity, newEntity.id), overwrite = true)
                            }
                            refreshNotebookMeta(newEntity.id)
                            true
                        }
                        is DestinationPickerState.MoveFolder -> {
                            repository.moveObject(source.id, currentParentId)
                            true
                        }
                        is DestinationPickerState.CopyFolder -> {
                            copyFolderRecursively(source.id, currentParentId)
                            true
                        }
                        else -> false
                    }.also { ok ->
                        // Operation committed — now retire the replaced entry (same semantics as a
                        // user-confirmed delete).
                        if (ok && conflictId != null) {
                            when (state) {
                                is DestinationPickerState.CopyNotebook,
                                is DestinationPickerState.MoveNotebook -> {
                                    repository.softDeleteNotebook(conflictId)
                                    soilFile(this@MainActivity, conflictId).delete()
                                }
                                is DestinationPickerState.CopyFolder,
                                is DestinationPickerState.MoveFolder -> {
                                    deleteFolderRecursively(conflictId)
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Picker operation failed", e)
                    false
                }
            }
            if (success) {
                destinationPickerState = DestinationPickerState.None
                applyPickerModeUI()
                currentPage = 0
                scanAndRender()
                Toast.makeText(this@MainActivity, if (isCopy) "Copied." else "Moved.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    if (isCopy) "Copy failed." else "Move failed.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ── Recursive folder helpers ──────────────────────────────────────────────

    /** Soft-deletes the folder and all its descendants; deletes physical .soil files. */
    /**
     * Best-effort refresh of [notebook_meta] after a move or copy — no prompt ever.
     * Plaintext: always opens. GLOBAL encrypted: opens only when the key is already cached.
     * NOTEBOOK encrypted: skipped (meta self-heals on next open/close).
     */
    private suspend fun refreshNotebookMeta(notebookId: String) = runCatching {
        val info = repository.getEncryptionInfo(notebookId)
        val key: String? = when {
            !info.encrypted -> null
            info.keyScope == KeyScope.GLOBAL ->
                com.notesprout.android.crypto.PassphraseStore.getGlobalPassphrase(this)
                    ?: return@runCatching
            else -> return@runCatching
        }
        val soilPath = soilFile(this, notebookId).absolutePath
        val builder = SoilDatabase.builder(this, soilPath)
        if (key != null) builder.openHelperFactory(com.notesprout.android.crypto.SoilCrypto.roomFactory(key))
        val db = builder.build()
        try {
            NotebookMetaStore.refresh(db, repository, notebookId)
        } finally {
            db.close()
        }
    }.onFailure { Slog.d("MainActivity") { "refreshNotebookMeta failed for $notebookId: ${it.message}" } }

    // ── TEMP: legacy-ts compaction sweep (remove after all devices compacted — see BACKLOG.md) ──

    /** Confirmation for the one-off "compact my whole library" migration button. */
    private fun showCompactNotebooksDialog() {
        AlertDialog.Builder(this)
            .setTitle("Compact Notebooks")
            .setMessage(
                "Rewrites stored notebooks and the index to drop legacy per-point timestamps and " +
                "re-encode images as WEBP, reclaiming the freed space. Unencrypted and " +
                "globally-unlocked notebooks are done now; notebook-scoped encrypted ones compact " +
                "themselves next time you open them."
            )
            .setPositiveButton("Compact") { _, _ -> lifecycleScope.launch { runCompactNotebooksSweep() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * One-off bulk compaction: opens every notebook that can be unlocked without a prompt
     * (plaintext or GLOBAL scope with a cached passphrase), strips legacy stroke `ts`, transcodes
     * PNG images to WEBP, VACUUMs, and flags the shrunk file for the next backup. Then compacts the
     * global index the same way. NOTEBOOK-scope encrypted notebooks are skipped — they self-compact
     * at seal the next time they are opened.
     */
    private suspend fun runCompactNotebooksSweep() {
        val tvMessage = android.widget.TextView(this).apply {
            text = "Compacting notebooks…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        var compacted = 0
        var bytesFreed = 0L
        var skippedEncrypted = 0
        var errors = 0
        var indexColumnarRows = 0
        var indexWebpImages = 0
        var calScratchStrokes = 0

        withContext(Dispatchers.IO) {
            val notebooks = repository.getAllNotebooks()
            for ((i, nb) in notebooks.withIndex()) {
                runCatching {
                    val info = repository.getEncryptionInfo(nb.id)
                    val key: String? = when {
                        !info.encrypted -> null
                        info.keyScope == KeyScope.GLOBAL ->
                            com.notesprout.android.crypto.PassphraseStore.getGlobalPassphrase(this@MainActivity)
                                ?: run { skippedEncrypted++; return@runCatching }
                        else -> { skippedEncrypted++; return@runCatching }
                    }
                    val file = soilFile(this@MainActivity, nb.id)
                    if (!file.exists()) return@runCatching
                    val before = file.length()
                    val builder = SoilDatabase.builder(this@MainActivity, file.absolutePath)
                    if (key != null) builder.openHelperFactory(com.notesprout.android.crypto.SoilCrypto.roomFactory(key))
                    val db = builder.build()
                    val result = try {
                        NotebookCompactor.compact(db, resources.displayMetrics.density)
                    } finally { db.close() }
                    if (result.changed) {
                        compacted++
                        bytesFreed += (before - file.length()).coerceAtLeast(0L)
                        repository.touchNotebook(nb.id)
                    }
                }.onFailure { errors++; Slog.d("MainActivity") { "compact sweep failed for ${nb.id}: ${it.message}" } }
                withContext(Dispatchers.Main) { tvMessage.text = "Compacting notebooks…\n${i + 1} / ${notebooks.size}" }
            }
            // Finally, backfill the global index to columnar + transcode its images to WEBP.
            withContext(Dispatchers.Main) { tvMessage.text = "Compacting index…" }
            runCatching {
                val r = NotebookCompactor.compactIndex()
                indexColumnarRows = r.columnarRows; indexWebpImages = r.webpImages
            }.onFailure { errors++; Slog.d("MainActivity") { "index compaction failed: ${it.message}" } }
            // …and bulk-convert the calendar + scratchpad legacy strokes to binary (Phase 3 backlog).
            withContext(Dispatchers.Main) { tvMessage.text = "Compacting calendar & scratch pad…" }
            runCatching { calScratchStrokes = NotebookCompactor.compactCalendarScratchpadStrokes() }
                .onFailure { errors++; Slog.d("MainActivity") { "calendar/scratchpad compaction failed: ${it.message}" } }
        }

        dialog.dismiss()
        val freedMb = "%.1f".format(bytesFreed / (1024.0 * 1024.0))
        val summary = StringBuilder("Compacted $compacted notebook${if (compacted == 1) "" else "s"} — freed $freedMb MB.")
        if (indexColumnarRows > 0)
            summary.append("\n\nConverted $indexColumnarRows index row${if (indexColumnarRows == 1) "" else "s"} to columnar.")
        if (indexWebpImages > 0)
            summary.append("\n\nConverted $indexWebpImages index image${if (indexWebpImages == 1) "" else "s"} to WEBP.")
        if (calScratchStrokes > 0)
            summary.append("\n\nConverted $calScratchStrokes calendar/scratch-pad stroke${if (calScratchStrokes == 1) "" else "s"} to binary.")
        if (skippedEncrypted > 0)
            summary.append("\n\n$skippedEncrypted encrypted notebook${if (skippedEncrypted == 1) "" else "s"} skipped — they compact when you open them.")
        if (errors > 0)
            summary.append("\n\n$errors notebook${if (errors == 1) "" else "s"} could not be processed.")
        AlertDialog.Builder(this)
            .setTitle("Compaction Complete")
            .setMessage(summary.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private suspend fun deleteFolderRecursively(folderId: String) {
        val children = repository.getChildren(folderId)
        for (child in children) {
            when (child.type) {
                ObjectType.NOTEBOOK -> {
                    repository.scrubNotebookFromAllLists(child.id)
                    repository.softDeleteNotebook(child.id)
                    soilFile(this, child.id).delete()
                }
                ObjectType.FOLDER -> deleteFolderRecursively(child.id)
            }
        }
        repository.softDeleteFolder(folderId)
    }

    /** Creates a new subtree under [destParentId] mirroring [sourceFolderId], copying .soil files. */
    private suspend fun copyFolderRecursively(sourceFolderId: String, destParentId: String?) {
        val sourceFolder = repository.getFolder(sourceFolderId) ?: return
        val newFolder    = repository.createFolder(sourceFolder.name, destParentId)
        val children     = repository.getChildren(sourceFolderId)
        for (child in children) {
            when (child.type) {
                ObjectType.NOTEBOOK -> {
                    val sourceObj = try {
                        child.notebookMeta()
                    } catch (_: Exception) { NotebookObject() }
                    val newNotebook = repository.createNotebook(child.name, newFolder.id)
                    if (sourceObj.snapshot != null && !sourceObj.encrypted) {
                        repository.updateNotebookSnapshot(newNotebook.id, sourceObj.snapshot)
                    }
                    if (sourceObj.pageCount > 0) {
                        repository.updateNotebookPageCount(newNotebook.id, sourceObj.pageCount)
                    }
                    if (sourceObj.encrypted) {
                        repository.setEncryptionState(newNotebook.id, encrypted = true, keyScope = sourceObj.keyScope)
                    }
                    val srcFile = soilFile(this, child.id)
                    if (srcFile.exists()) {
                        srcFile.copyTo(soilFile(this, newNotebook.id), overwrite = true)
                    }
                }
                ObjectType.FOLDER -> copyFolderRecursively(child.id, newFolder.id)
            }
        }
    }

    // ── Delete folder ─────────────────────────────────────────────────────────

    private fun showDeleteFolderConfirmation(entity: ObjectEntity) {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete \"${entity.name}\"? This will permanently remove all notebooks and subfolders inside it. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteFolder(entity) }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun deleteFolder(entity: ObjectEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { deleteFolderRecursively(entity.id) }
            scanAndRender()
        }
    }

    // ── Delete notebook ───────────────────────────────────────────────────────

    private fun showDeleteNotebookConfirmation(entity: ObjectEntity) {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete notebook \"${entity.name}\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteNotebook(entity) }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun deleteNotebook(entity: ObjectEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.scrubNotebookFromAllLists(entity.id)
                repository.softDeleteNotebook(entity.id)
                // Drop any cached raw key for this notebook (RAM + Keystore).
                com.notesprout.android.crypto.KeyMaterial.invalidate(this@MainActivity, entity.id)
                val file = soilFile(this@MainActivity, entity.id)
                // Delete .soil and any sibling artefacts (-wal, -shm, -journal).
                file.parentFile?.listFiles { f -> f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
            }
            scanAndRender()
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Open the export screen for [entity]. Format, options and destination are all chosen there —
     * see [ExportActivity].
     */
    private fun startExportFromMain(entity: ObjectEntity) {
        startActivity(ExportActivity.intentFor(this, entity.id, entity.name))
    }

    /**
     * Handle an incoming .soil URI from ACTION_VIEW or ACTION_SEND.
     * Called from onCreate (cold launch) and onNewIntent (app already open).
     */
    /**
     * Handle [EXTRA_START_NEW_NOTEBOOK] (from the calendar's New Notebook button): enter the
     * folder-picker so the user chooses where the notebook lands, then the normal new-notebook
     * flow. Consumed once — the extra is removed so config-change recreations don't re-trigger it.
     */
    private fun handleNewNotebookIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_START_NEW_NOTEBOOK, false)) return
        intent.removeExtra(EXTRA_START_NEW_NOTEBOOK)
        returnToTodayAfterCreate = intent.getBooleanExtra(EXTRA_RETURN_TO_TODAY, false)
        intent.removeExtra(EXTRA_RETURN_TO_TODAY)
        pendingNewNotebookPicker = true
        tryStartPendingNewNotebookPicker()
    }

    /** Enters the new-notebook folder picker once the grid is laid out and browse state restored. */
    private fun tryStartPendingNewNotebookPicker() {
        if (!pendingNewNotebookPicker || gridSpec == null || !isStateRestored) return
        pendingNewNotebookPicker = false
        enterPickerMode(DestinationPickerState.NewNotebook)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        startImportFromUri(uri)
    }

    private fun startImportFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            // IO: a slow document provider must not jank/ANR the main thread for a name lookup.
            val uriDisplayName = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
            }
            val fallbackName = uriDisplayName
                ?.removeSuffix(".soil")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Imported Notebook"

            // Copy the incoming content:// URI to a local temp file so SQLite can open it.
            val (tempFile, kind) = try {
                withContext(Dispatchers.IO) {
                    val importDir = java.io.File(cacheDir, "imported_notebooks")
                        .also { it.deleteRecursively(); it.mkdirs() }
                    val tempFile = java.io.File(importDir, "incoming.soil")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { input.copyTo(it) }
                    }
                    tempFile to SoilCrypto.probe(tempFile)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (kind == SoilFileKind.Invalid) {
                runCatching { tempFile.delete() }
                Toast.makeText(this@MainActivity, "Not a valid notebook file", Toast.LENGTH_LONG).show()
                return@launch
            }

            // For encrypted files, prompt for the passphrase now (before reading meta).
            val enteredPass: String? = if (kind == SoilFileKind.Encrypted) {
                val pass = KeyResolver.resolveForImportRead(this@MainActivity, tempFile)
                if (pass == null) {
                    runCatching { tempFile.delete() }
                    return@launch
                }
                pass
            } else null

            val manifest = try {
                withContext(Dispatchers.IO) {
                    NotebookImporter.readManifest(tempFile, fallbackName, enteredPass)
                }
            } catch (e: ImportException) {
                runCatching { tempFile.delete() }
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: Exception) {
                runCatching { tempFile.delete() }
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            val displayName = manifest.meta?.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName

            // The manifest is untrusted input and its notebookId becomes a Garden filename via
            // soilFile(). An id that isn't a plain UUID-shaped token (e.g. "../…") could escape
            // the Garden directory — such files import under a fresh id instead.
            val manifestId = manifest.meta?.notebookId?.takeIf(::isSafeImportId)

            val collision = manifestId?.let { id ->
                withContext(Dispatchers.IO) {
                    repository.getNotebook(id)?.takeIf { it.deletedAt == null }
                }
            }

            if (collision != null) {
                showImportCollisionDialog(manifest, tempFile, displayName, collision.id, enteredPass)
            } else {
                val resolvedId = manifestId ?: UUID.randomUUID().toString()
                showImportPlacementDialog(manifest, tempFile, displayName, resolvedId, enteredPass)
            }
        }
    }

    /** True when an id read from an imported file's manifest is safe to use as a Garden filename
     *  and an index primary key: UUID-alphabet only — no path separators, dots, or quotes. */
    private fun isSafeImportId(id: String): Boolean =
        id.length in 1..64 && id.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' }

    private fun showImportCollisionDialog(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
        enteredPass: String? = null,
    ) {
        ActionSheetDialog(this)
            .title("\"$displayName\" already exists")
            .addAction(null, "Replace existing notebook") {
                // Replacing swaps the .soil under the same id — refuse while a NotebookActivity
                // holds it open (its live connection would keep writing to the unlinked file and
                // silently lose every edit made after the swap).
                if (com.notesprout.android.core.OpenNotebooks.isOpen(existingId)) {
                    runCatching { tempFile.delete() }
                    Toast.makeText(this, "That notebook is currently open — close it and import again.", Toast.LENGTH_LONG).show()
                } else {
                    lifecycleScope.launch { executeReplace(manifest, tempFile, displayName, existingId, enteredPass) }
                }
            }
            .addAction(null, "Keep both") {
                val freshId = UUID.randomUUID().toString()
                showImportPlacementDialog(manifest, tempFile, displayName, freshId, enteredPass)
            }
            .addAction(null, "Cancel") {
                runCatching { tempFile.delete() }
            }
            .show()
    }

    private fun showImportPlacementDialog(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        resolvedId: String,
        enteredPass: String? = null,
    ) {
        val folderLabel = manifest.meta?.folderPath
            ?.joinToString(" / ") { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: "Top level"

        AlertDialog.Builder(this)
            .setMessage("Place \"$displayName\" in its original folder?\n\n$folderLabel")
            .setPositiveButton("Notebook's folders") { _, _ ->
                lifecycleScope.launch {
                    // Walk folderPath to resolve parentId, then check for name conflict.
                    // Strictly create-only: an unsafe id, or any collision with an existing row
                    // (soft-deleted folder, non-folder id), stops the descent and the notebook
                    // lands one level up — imported ancestry never mutates the user's own rows.
                    val (parentId, conflict) = withContext(Dispatchers.IO) {
                        var pid: String? = null
                        run {
                            manifest.meta?.folderPath?.forEach { ref ->
                                if (!isSafeImportId(ref.id)) return@run
                                val entity = repository.importFolderCreateOnly(ref.id, ref.name, pid)
                                    ?: return@run
                                pid = entity.id
                            }
                        }
                        val conflict = repository.getNotebooks(pid).find { it.name == displayName }
                        pid to conflict
                    }
                    if (conflict != null) {
                        showImportNameConflictDialog(manifest, tempFile, displayName, parentId, resolvedId, conflict.id, enteredPass)
                    } else {
                        executeImport(manifest, tempFile, displayName, parentId, resolvedId, enteredPass)
                    }
                }
            }
            .setNeutralButton("Choose folder…") { _, _ ->
                enterPickerMode(DestinationPickerState.ImportNotebook(manifest, tempFile, resolvedId, displayName, enteredPass))
            }
            .setNegativeButton("Cancel") { _, _ ->
                runCatching { tempFile.delete() }
            }
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
    }

    private fun showImportNameConflictDialog(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        conflictId: String,
        enteredPass: String? = null,
    ) {
        AlertDialog.Builder(this)
            .setMessage("A notebook named \"$displayName\" already exists here. Replace it or keep both?")
            .setPositiveButton("Replace") { _, _ ->
                // The existing notebook is retired only AFTER the import commits (see
                // retireReplacedNotebook) — cancelling any later step must leave it untouched.
                if (com.notesprout.android.core.OpenNotebooks.isOpen(conflictId)) {
                    runCatching { tempFile.delete() }
                    Toast.makeText(this, "That notebook is currently open — close it and import again.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    executeImport(manifest, tempFile, displayName, parentId, resolvedId, enteredPass,
                        replaceVictimId = conflictId)
                }
            }
            .setNeutralButton("Keep both") { _, _ ->
                val dedupedName = "$displayName Copy"
                lifecycleScope.launch { executeImport(manifest, tempFile, dedupedName, parentId, resolvedId, enteredPass) }
            }
            .setNegativeButton("Cancel") { _, _ ->
                runCatching { tempFile.delete() }
            }
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
    }

    private suspend fun executeImport(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        enteredPass: String? = null,
        replaceVictimId: String? = null,
    ) {
        if (enteredPass != null) {
            showKeyingChooserForImport(manifest, tempFile, displayName, parentId, resolvedId, enteredPass, replaceVictimId)
            return
        }
        // Plaintext source: encrypt-on-import — never land as plaintext. Prompt for the scope.
        showKeyingChooserForPlaintextImport(manifest, tempFile, displayName, parentId, resolvedId, replaceVictimId)
    }

    /**
     * Retire the notebook a committed import replaced — same semantics as a user-confirmed
     * delete. Runs only after the import fully succeeded, never before.
     */
    private suspend fun retireReplacedNotebook(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            repository.scrubNotebookFromAllLists(id)
            repository.softDeleteNotebook(id)
            com.notesprout.android.crypto.KeyMaterial.invalidate(this@MainActivity, id)
            val file = soilFile(this@MainActivity, id)
            file.parentFile?.listFiles { f -> f.name.startsWith(file.name) }?.forEach { it.delete() }
        }
    }

    private suspend fun executeReplace(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
        enteredPass: String? = null,
    ) {
        if (enteredPass != null) {
            showKeyingChooserForReplace(manifest, tempFile, displayName, existingId, enteredPass)
            return
        }
        // Plaintext source: encrypt-on-import — never land as plaintext. Prompt for the scope.
        showKeyingChooserForPlaintextReplace(manifest, tempFile, displayName, existingId)
    }

    private fun showKeyingChooserForImport(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        enteredPass: String,
        replaceVictimId: String? = null,
    ) {
        ActionSheetDialog(this)
            .title("Import encrypted notebook")
            .addAction(null, "Keep existing passphrase") {
                lifecycleScope.launch {
                    val globalPass = withContext(Dispatchers.IO) {
                        PassphraseStore.getGlobalPassphrase(this@MainActivity)
                    }
                    val scope = if (enteredPass == globalPass) KeyScope.GLOBAL else KeyScope.NOTEBOOK
                    doImportEncrypted(manifest, tempFile, displayName, parentId, resolvedId, enteredPass, enteredPass, scope, replaceVictimId)
                }
            }
            .addAction(null, "Use this device's global") {
                lifecycleScope.launch {
                    val globalPass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.GLOBAL)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doImportEncrypted(manifest, tempFile, displayName, parentId, resolvedId, enteredPass, globalPass, KeyScope.GLOBAL, replaceVictimId)
                }
            }
            .addAction(null, "New notebook passphrase") {
                lifecycleScope.launch {
                    val newPass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.NOTEBOOK)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doImportEncrypted(manifest, tempFile, displayName, parentId, resolvedId, enteredPass, newPass, KeyScope.NOTEBOOK, replaceVictimId)
                }
            }
            .addAction(null, "Cancel") {
                runCatching { tempFile.delete() }
            }
            .show()
    }

    private fun showKeyingChooserForReplace(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
        enteredPass: String,
    ) {
        ActionSheetDialog(this)
            .title("Import encrypted notebook")
            .addAction(null, "Keep existing passphrase") {
                lifecycleScope.launch {
                    val globalPass = withContext(Dispatchers.IO) {
                        PassphraseStore.getGlobalPassphrase(this@MainActivity)
                    }
                    val scope = if (enteredPass == globalPass) KeyScope.GLOBAL else KeyScope.NOTEBOOK
                    doReplaceEncrypted(manifest, tempFile, displayName, existingId, enteredPass, enteredPass, scope)
                }
            }
            .addAction(null, "Use this device's global") {
                lifecycleScope.launch {
                    val globalPass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.GLOBAL)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doReplaceEncrypted(manifest, tempFile, displayName, existingId, enteredPass, globalPass, KeyScope.GLOBAL)
                }
            }
            .addAction(null, "New notebook passphrase") {
                lifecycleScope.launch {
                    val newPass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.NOTEBOOK)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doReplaceEncrypted(manifest, tempFile, displayName, existingId, enteredPass, newPass, KeyScope.NOTEBOOK)
                }
            }
            .addAction(null, "Cancel") {
                runCatching { tempFile.delete() }
            }
            .show()
    }

    /** A plaintext `.soil` is never imported as-is under encrypt-everything: choose whether to key it
     *  to the device global passphrase or a private notebook passphrase, then encrypt it on the way in. */
    private fun showKeyingChooserForPlaintextImport(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        replaceVictimId: String? = null,
    ) {
        ActionSheetDialog(this)
            .title("Encrypt imported notebook")
            .addAction(null, "Use this device's global") {
                lifecycleScope.launch {
                    val pass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.GLOBAL)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doImportPlaintextEncrypting(manifest, tempFile, displayName, parentId, resolvedId, pass, KeyScope.GLOBAL, replaceVictimId)
                }
            }
            .addAction(null, "New notebook passphrase") {
                lifecycleScope.launch {
                    val pass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.NOTEBOOK)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doImportPlaintextEncrypting(manifest, tempFile, displayName, parentId, resolvedId, pass, KeyScope.NOTEBOOK, replaceVictimId)
                }
            }
            .addAction(null, "Cancel") {
                runCatching { tempFile.delete() }
            }
            .show()
    }

    private fun showKeyingChooserForPlaintextReplace(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
    ) {
        ActionSheetDialog(this)
            .title("Encrypt imported notebook")
            .addAction(null, "Use this device's global") {
                lifecycleScope.launch {
                    val pass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.GLOBAL)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doReplacePlaintextEncrypting(manifest, tempFile, displayName, existingId, pass, KeyScope.GLOBAL)
                }
            }
            .addAction(null, "New notebook passphrase") {
                lifecycleScope.launch {
                    val pass = KeyResolver.resolveForConvertToEncrypted(this@MainActivity, KeyScope.NOTEBOOK)
                        ?: run { runCatching { tempFile.delete() }; return@launch } // prompt cancelled — drop the cached import copy
                    doReplacePlaintextEncrypting(manifest, tempFile, displayName, existingId, pass, KeyScope.NOTEBOOK)
                }
            }
            .addAction(null, "Cancel") {
                runCatching { tempFile.delete() }
            }
            .show()
    }

    /** Encrypt the plaintext temp in place with the chosen key, then hand off to the (encrypted)
     *  import path — so the notebook lands in Garden already encrypted at the requested scope. */
    private suspend fun doImportPlaintextEncrypting(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        passphrase: String,
        scope: KeyScope,
        replaceVictimId: String? = null,
    ) {
        val modal = showImportingModal()
        try {
            withContext(Dispatchers.IO) {
                SoilMigrator.encryptInPlace(tempFile, passphrase)
                NotebookImporter.importEncrypted(
                    this@MainActivity, repository, tempFile, manifest, displayName, parentId, resolvedId,
                    enteredPass = passphrase, finalPass = passphrase, scope = scope,
                )
            }
        } catch (e: Exception) {
            modal.dismiss()
            Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        replaceVictimId?.let { retireReplacedNotebook(it) }
        modal.dismiss()
        Toast.makeText(this@MainActivity, "Imported “$displayName”", Toast.LENGTH_SHORT).show()
        scanAndRender()
    }

    private suspend fun doReplacePlaintextEncrypting(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
        passphrase: String,
        scope: KeyScope,
    ) {
        val modal = showImportingModal()
        try {
            withContext(Dispatchers.IO) {
                SoilMigrator.encryptInPlace(tempFile, passphrase)
                NotebookImporter.replaceEncrypted(
                    this@MainActivity, repository, tempFile, manifest, displayName, existingId,
                    enteredPass = passphrase, finalPass = passphrase, scope = scope,
                )
            }
        } catch (e: Exception) {
            modal.dismiss()
            Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        modal.dismiss()
        Toast.makeText(this@MainActivity, "Replaced “$displayName”", Toast.LENGTH_SHORT).show()
        scanAndRender()
    }

    private suspend fun doImportEncrypted(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        enteredPass: String,
        finalPass: String,
        scope: KeyScope,
        replaceVictimId: String? = null,
    ) {
        val modal = showImportingModal()
        val name = try {
            withContext(Dispatchers.IO) {
                NotebookImporter.importEncrypted(
                    this@MainActivity, repository, tempFile, manifest, displayName,
                    parentId, resolvedId, enteredPass, finalPass, scope,
                )
                displayName
            }
        } catch (e: Exception) {
            modal.dismiss()
            Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        replaceVictimId?.let { retireReplacedNotebook(it) }
        modal.dismiss()
        Toast.makeText(this@MainActivity, "Imported “$name”", Toast.LENGTH_SHORT).show()
        scanAndRender()
    }

    private suspend fun doReplaceEncrypted(
        manifest: ImportManifest,
        tempFile: java.io.File,
        displayName: String,
        existingId: String,
        enteredPass: String,
        finalPass: String,
        scope: KeyScope,
    ) {
        val modal = showImportingModal()
        val name = try {
            withContext(Dispatchers.IO) {
                NotebookImporter.replaceEncrypted(
                    this@MainActivity, repository, tempFile, manifest, displayName,
                    existingId, enteredPass, finalPass, scope,
                )
                displayName
            }
        } catch (e: Exception) {
            modal.dismiss()
            Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        modal.dismiss()
        Toast.makeText(this@MainActivity, "Replaced “$name”", Toast.LENGTH_SHORT).show()
        scanAndRender()
    }

    private fun showImportingModal(): AlertDialog {
        val tvMessage = TextView(this).apply {
            text = "Importing…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        return dialog
    }

    private fun toggleOverflowToolbar() {
        val visible = binding.overflowToolbar.visibility == View.VISIBLE
        binding.overflowToolbar.visibility        = if (visible) View.GONE else View.VISIBLE
        binding.overflowToolbarDivider.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun closeOverflowToolbar() {
        binding.overflowToolbar.visibility        = View.GONE
        binding.overflowToolbarDivider.visibility = View.GONE
    }
}
