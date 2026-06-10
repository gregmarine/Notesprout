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
// GestureDetectorCompat is deprecated; GestureDetector works directly on minSdk 29+.
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.checkpointTruncateAndClose
import com.notesprout.android.data.loadNotebookCoverBitmap
import com.notesprout.android.search.SearchDialog
import com.notesprout.android.search.SearchEngine
import com.notesprout.android.search.SearchResult
import com.notesprout.android.sort.FolderSort
import com.notesprout.android.sort.SortDialog
import com.notesprout.android.sort.SortField
import com.notesprout.android.sort.SortOrder
import com.notesprout.android.sort.SortPreferences
import com.notesprout.android.sort.SortPreferencesManager
import com.notesprout.android.ui.DestinationPickerState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.notesprout.android.databinding.ActivityMainBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    }

    // ── Grid specification ────────────────────────────────────────────────────

    /**
     * Computed once after the gridContainer is measured.  Holds all pixel
     * dimensions needed to lay out cards for the current display.
     */
    private data class GridSpec(
        val cols: Int,
        val rows: Int,
        val cardWidthPx: Int,
        val cardHeightPx: Int,
        val gutterPx: Int,        // horizontal gap between columns AND vertical gap between rows
        val rowGapPx: Int,        // vertical gap between a card and its label
        val labelHeightPx: Int,
        val paddingHPx: Int,
        val paddingVPx: Int,
    ) {
        val itemsPerPage: Int get() = cols * rows
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding

    /** Unified list of folders then notebooks for the current directory, sorted alphabetically. */
    private var items: List<NotebookListItem> = emptyList()

    private var currentPage = 0
    private var gridSpec: GridSpec? = null

    /** True when onResume fired before gridSpec was ready; renderPage deferred. */
    private var pendingScan = false

    /**
     * Navigation stack — root is always notesDir(). Navigating into a folder
     * pushes onto this list; going back pops from it.
     */
    private val directoryStack: MutableList<File> = mutableListOf()

    /** The directory currently being displayed. */
    private val currentDirectory: File get() = directoryStack.last()

    private var sortPrefs: SortPreferences = SortPreferences()

    private var isSearchMode = false
    private var currentSearchQuery: String = ""

    /** In search mode, holds the current ranked results so cards can access folderLabel. */
    private var searchResults: List<SearchResult> = emptyList()

    private var destinationPickerState: DestinationPickerState = DestinationPickerState.None

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
                // Honour horizontal flings only when they dominate the vertical component.
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                return if (velocityX < 0) {
                    navigatePage(currentPage + 1)
                    true
                } else {
                    navigatePage(currentPage - 1)
                    true
                }
            }
        })
    }

    // ── Cover image picker launcher ───────────────────────────────────────────

    /** Set by openCoverDialog(); called when the image picker returns a URI. */
    private var onCoverImagePicked: ((Uri) -> Unit)? = null

    private val coverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onCoverImagePicked?.invoke(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — same pattern as NotebookActivity.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Root of the directory stack is always the notebooks directory.
        directoryStack.add(notesDir())

        sortPrefs = SortPreferencesManager.load(this)

        setupBottomBar()
        setupGridGestures()
        setupBackNavigation()

        binding.btnPickerCancel.setOnClickListener { exitPickerMode() }
        binding.btnPickerConfirm.setOnClickListener { confirmPickerDestination() }

        binding.btnSort.setOnClickListener {
            SortDialog(this, sortPrefs) { newPrefs ->
                sortPrefs = newPrefs
                SortPreferencesManager.save(this, newPrefs)
                currentPage = 0
                scanAndRender()
            }.show()
        }

        binding.btnSearch.setOnClickListener {
            SearchDialog.show(
                context = this,
                initialQuery = if (isSearchMode) currentSearchQuery else "",
                onSearch = { query -> enterSearchMode(query) },
                onCancel = { }
            )
        }

        binding.btnClearSearch.setOnClickListener {
            exitSearchMode()
        }

        // Compute the grid specification once the gridContainer has been laid out
        // (width/height are 0 until the first layout pass).
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    if (pendingScan) {
                        pendingScan = false
                        scanAndRender()
                    } else {
                        renderPage()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (gridSpec != null) {
            scanAndRender()
        } else {
            // Layout not measured yet — defer until the global layout listener fires.
            pendingScan = true
        }
    }

    // ── Back navigation ───────────────────────────────────────────────────────

    private fun navigateUpOneLevel() {
        if (directoryStack.size > 1) {
            directoryStack.removeLast()
            currentPage = 0
            scanAndRender()
        }
    }

    private fun enterSearchMode(query: String) {
        isSearchMode = true
        currentSearchQuery = query
        currentPage = 0
        binding.btnClearSearch.visibility = View.VISIBLE
        binding.btnSort.visibility = View.GONE
        scanAndRender()
    }

    private fun exitSearchMode() {
        isSearchMode = false
        currentSearchQuery = ""
        searchResults = emptyList()
        currentPage = 0
        binding.btnClearSearch.visibility = View.GONE
        binding.btnSort.visibility = View.VISIBLE
        scanAndRender()
    }

    private fun navigateStackToDirectory(targetDir: File) {
        val notes = notesDir()
        val segments = mutableListOf<File>()
        var current: File? = targetDir
        while (current != null) {
            segments.add(0, current)
            if (current == notes) break
            current = current.parentFile
        }
        directoryStack.clear()
        directoryStack.addAll(segments)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (destinationPickerState != DestinationPickerState.None) {
                    exitPickerMode()
                    return
                }
                if (isSearchMode) {
                    exitSearchMode()
                    return
                }
                if (directoryStack.size > 1) {
                    navigateUpOneLevel()
                } else {
                    // At root — let the system handle it (finish / go to home).
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
        binding.btnBreadcrumbBack.setOnClickListener { navigateUpOneLevel() }
    }

    // ── Grid gesture wiring ───────────────────────────────────────────────────

    private fun setupGridGestures() {
        binding.gridContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ── Grid specification computation ────────────────────────────────────────

    /**
     * Derives an adaptive [GridSpec] from the available pixel area.
     *
     * Column count is chosen by screen-width breakpoints (in dp):
     *   < 480dp  → 3 columns  (phone / small device)
     *   480–719dp → 4 columns  (mid-size tablet)
     *   ≥ 720dp  → 5 columns  (large tablet, e.g. BOOX NoteAir)
     *
     * Card aspect ratio mirrors the physical screen (portrait: height ÷ width).
     * Row count is derived from how many full rows fit the available height.
     */
    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density    = resources.displayMetrics.density
        val gutterPx   = (12 * density).toInt()   // gap between cards, horizontal and vertical
        val paddingHPx = (16 * density).toInt()   // left/right padding inside the grid area
        val paddingVPx = (16 * density).toInt()   // top/bottom padding inside the grid area
        val rowGapPx   = (6  * density).toInt()   // gap between card bottom edge and label
        val labelHeightPx = (32 * density).toInt() // one line of body text + breathing room

        // Column count: 3 on tablets/large e-ink devices, 2 on phone-form-factor devices.
        val screenWidthDp = availableWidth / density
        val cols = if (screenWidthDp >= 480f) 3 else 2

        // Portrait ratio: how tall a card should be relative to its width.
        val dm = resources.displayMetrics
        val aspectRatio = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)

        val innerWidth  = availableWidth  - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx

        val cardWidth  = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx

        // Number of complete rows that fit vertically (accounting for gutters between rows).
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

    /** Rescans the current directory (or runs a search) then renders the current page and breadcrumbs. */
    private fun scanAndRender() {
        if (isSearchMode) {
            searchResults = SearchEngine.search(currentSearchQuery, currentDirectory, notesDir())
            items = searchResults.map { NotebookListItem.Notebook(it.file) }
        } else {
            searchResults = emptyList()
            val dir = currentDirectory
            val folders: List<NotebookListItem.Folder>
            val notebooks: List<NotebookListItem.Notebook>
            if (dir.exists()) {
                folders = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    ?.map { NotebookListItem.Folder(it.name, it) }
                    ?: emptyList()
                notebooks = dir.listFiles { f -> f.isFile && f.extension == "soil" }
                    ?.map { NotebookListItem.Notebook(it) }
                    ?: emptyList()
            } else {
                folders = emptyList()
                notebooks = emptyList()
            }

            val pickerState = destinationPickerState
            if (pickerState != DestinationPickerState.None) {
                // Picker mode: folders only, filtered by self-exclusion for folder operations.
                val excludedCanonical: String? = when (pickerState) {
                    is DestinationPickerState.CopyFolder -> pickerState.source.canonicalPath
                    is DestinationPickerState.MoveFolder -> pickerState.source.canonicalPath
                    else -> null
                }
                val filtered = if (excludedCanonical != null) {
                    folders.filter { it.file.canonicalPath != excludedCanonical }
                } else {
                    folders
                }
                items = sortItems(filtered)
            } else {
                items = when (sortPrefs.folderSort) {
                    FolderSort.FOLDERS_FIRST   -> sortItems(folders) + sortItems(notebooks)
                    FolderSort.NOTEBOOKS_FIRST -> sortItems(notebooks) + sortItems(folders)
                    FolderSort.MIXED           -> sortItems(folders + notebooks)
                }
            }
        }

        // Clamp currentPage to valid range after a rescan.
        val total = totalPages()
        currentPage = currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))

        buildBreadcrumbs()
        renderPage()
    }

    private fun sortItems(items: List<NotebookListItem>): List<NotebookListItem> {
        fun nameOf(item: NotebookListItem): String = when (item) {
            is NotebookListItem.Folder   -> item.name.lowercase()
            is NotebookListItem.Notebook -> item.file.nameWithoutExtension.lowercase()
        }
        fun dateOf(item: NotebookListItem): Long = when (item) {
            is NotebookListItem.Folder   -> item.file.lastModified()
            is NotebookListItem.Notebook -> item.file.lastModified()
        }
        val comparator: Comparator<NotebookListItem> = when (sortPrefs.field) {
            SortField.NAME          -> Comparator { a, b -> nameOf(a).compareTo(nameOf(b)) }
            SortField.DATE_MODIFIED -> Comparator { a, b -> dateOf(a).compareTo(dateOf(b)) }
        }
        val ordered = if (sortPrefs.order == SortOrder.DESCENDING) comparator.reversed() else comparator
        return items.sortedWith(ordered)
    }

    // ── Folder navigation ─────────────────────────────────────────────────────

    private fun navigateIntoFolder(dir: File) {
        directoryStack.add(dir)
        currentPage = 0
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

        directoryStack.forEachIndexed { index, dir ->
            if (index > 0) {
                val separator = AppCompatTextView(this).apply {
                    text = "›"
                    setTextColor(inkLightColor)
                    textSize = 18f
                    setPadding(sepPad, 0, sepPad, 0)
                }
                container.addView(separator)
            }

            val label = if (index == 0) "Notebooks" else dir.name
            val chip = AppCompatTextView(this).apply {
                text = label
                setTextColor(inkBlackColor)
                textSize = 18f
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                // Pop the stack back to this depth, then reload.
                setOnClickListener {
                    while (directoryStack.size > index + 1) {
                        directoryStack.removeLast()
                    }
                    currentPage = 0
                    scanAndRender()
                }
            }
            container.addView(chip)
        }

        // Auto-scroll so the deepest (rightmost) entry is visible.
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

    /** Clears the grid container and populates it with the current page's cards. */
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
            isSearchMode -> "No notebooks found for \"$currentSearchQuery\""
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

        // Outer container: vertically stacked rows, centred inside gridContainer.
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        val rowCount = (pageItems.size + spec.cols - 1) / spec.cols
        for (rowIdx in 0 until rowCount) {
            if (rowIdx > 0) {
                // Vertical gutter between rows.
                gridLayout.addView(Space(this), LinearLayout.LayoutParams(0, spec.gutterPx))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
            }

            for (colIdx in 0 until spec.cols) {
                if (colIdx > 0) {
                    // Horizontal gutter between columns.
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.gutterPx, 0))
                }

                val itemIdx = rowIdx * spec.cols + colIdx
                if (itemIdx < pageItems.size) {
                    row.addView(buildCardGroup(pageItems[itemIdx], spec))
                } else {
                    // Empty placeholder keeps grid columns aligned on the last row.
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
        ).apply {
            topMargin = spec.paddingVPx  // matches the 16dp side gutters baked into card widths
        }
        binding.gridContainer.addView(gridLayout, containerLp)
    }

    /**
     * Builds a single card group for a [NotebookListItem].
     *
     * Folder cards show the folder icon centred; no cover load is attempted.
     * Notebook cards show the notebook icon as a fallback and async-load the cover.
     *
     * ```
     * [card FrameLayout — shape_bordered, 1dp padding]
     *   [icon — centred; for notebooks, replaced by cover when one loads]
     *   [coverImage — MATCH_PARENT, centerCrop; notebook only, shown when bitmap loads]
     * [rowGap]
     * [label TextView — below the card]
     * ```
     */
    private fun buildCardGroup(item: NotebookListItem, spec: GridSpec): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            when (item) {
                is NotebookListItem.Folder -> {
                    setOnClickListener { navigateIntoFolder(item.file) }
                    setOnLongClickListener { showFolderContextMenu(item.file); true }
                }
                is NotebookListItem.Notebook -> {
                    setOnClickListener { openNotebook(item.file) }
                    setOnLongClickListener { showNotebookContextMenu(item.file); true }
                }
            }
        }

        // ── Card ─────────────────────────────────────────────────────────────
        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        // 1dp padding insets children inside the border so they don't render over the rounded corners.
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
                // Cover image — fills the card, crops to fit; hidden until a bitmap loads.
                val coverImage = AppCompatImageView(this).apply {
                    scaleType  = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                card.addView(coverImage, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))

                // Fallback icon — centered, visible until a cover image is available.
                val icon = AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_notebook)
                }
                card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

                // Async cover load.
                val job = lifecycleScope.launch {
                    val bitmap = loadNotebookCoverBitmap(item.file)
                    if (bitmap != null) {
                        coverImage.setImageBitmap(bitmap)
                        coverImage.visibility = View.VISIBLE
                        icon.visibility       = View.GONE
                    }
                }
                coverLoadJobs.add(job)
            }
        }

        // ── Label (below the card) ────────────────────────────────────────────
        val labelText = run {
            val file = when (item) {
                is NotebookListItem.Folder   -> item.file
                is NotebookListItem.Notebook -> item.file
            }
            val displayName = when (item) {
                is NotebookListItem.Folder   -> item.name
                is NotebookListItem.Notebook -> item.file.nameWithoutExtension
            }
            val modified = Date(file.lastModified())
            val dateStr = android.text.format.DateFormat.getMediumDateFormat(this).format(modified)
            val timeStr = android.text.format.DateFormat.getTimeFormat(this).format(modified)
            if (isSearchMode && item is NotebookListItem.Notebook) {
                val result = searchResults.find { it.file == item.file }
                val parentName = result?.file?.parentFile?.name ?: ""
                if (result != null) "$parentName › ${result.displayName}" else "$displayName ($dateStr, $timeStr)"
            } else {
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

    // ── Notebook opening ──────────────────────────────────────────────────────

    private fun openNotebook(file: File) {
        if (isSearchMode) {
            // Navigate the directory stack to the notebook's parent folder so that
            // when the user returns from NotebookActivity they land in the right folder.
            val parent = file.parentFile ?: notesDir()
            navigateStackToDirectory(parent)
            isSearchMode = false
            currentSearchQuery = ""
            searchResults = emptyList()
            binding.btnClearSearch.visibility = View.GONE
            binding.btnSort.visibility = View.VISIBLE
        }
        val intent = Intent(this, NotebookActivity::class.java).apply {
            putExtra(NotebookActivity.EXTRA_NOTEBOOK_PATH, file.absolutePath)
        }
        startActivity(intent)
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
        val display = currentPage + 1   // 1-indexed for the user

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
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        val defaultName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        dialogBinding.editNotebookName.setText(defaultName)
        val dialog = AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                val error = validateNotebookName(name)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                } else {
                    createNotebook(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Keyboard must be requested before show().
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Flat styling: no shadow, same shape_bordered drawable used everywhere.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        // Focus + open keyboard.  postDelayed(100) gives the dialog window time to
        // become the active input connection (required for WindowInsetsController).
        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    // Fallback for API 29
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    // ── New folder dialog ─────────────────────────────────────────────────────

    private fun showNewFolderDialog() {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.setText("")
        dialogBinding.editNotebookName.hint = "Folder name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                val error = validateFolderName(name)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                } else {
                    val dir = File(currentDirectory, name)
                    if (!dir.mkdirs()) {
                        Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show()
                    } else {
                        navigateIntoFolder(dir)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

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

    /** Notebooks live in a dedicated "Notebooks" subdirectory of the app's private external storage. */
    private fun notesDir(): File = File(getExternalFilesDir(null)!!, "Notebooks")

    /**
     * Validates a proposed notebook name, returning a user-facing error message
     * or null if the name is safe to use. Shared gate for both creation hazards:
     *
     *  - **C-2 (path traversal):** a name containing `/`, `\`, or `..` would write
     *    the `.soil` file outside the notebooks directory. We whitelist the same
     *    safe filename charset as [NotebookExporter] (`[^a-zA-Z0-9_\-. ]`), which
     *    already excludes both separators; the explicit `.`/`..` check covers the
     *    dot-only names the charset would otherwise allow.
     *  - **C-1 (corruption):** reusing an existing name reopens that `.soil` and
     *    inserts a *second* `type='notebook'` row plus an orphan page/layer, mixing
     *    two logical notebooks in one file. Reject if the target file already exists.
     */
    private fun validateNotebookName(name: String): String? {
        if (name.isBlank()) return "Notebook name cannot be empty"
        if (name == "." || name == "..") return "Invalid notebook name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        if (File(currentDirectory, "$name.soil").exists()) {
            return "A notebook named \"$name\" already exists"
        }
        return null
    }

    private fun validateFolderName(name: String): String? {
        if (name.isBlank()) return "Folder name cannot be empty"
        if (name == "." || name == "..") return "Invalid folder name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        if (File(currentDirectory, name).exists()) {
            return "A folder named \"$name\" already exists"
        }
        return null
    }

    private fun createNotebook(name: String) {
        try {
            // Defensive re-validation: this is the actual write path, so never trust
            // that the caller validated. Bail before opening/creating any file.
            validateNotebookName(name)?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                return
            }

            val currentDir = currentDirectory
            if (!currentDir.exists()) {
                check(currentDir.mkdirs()) { "Failed to create directory at ${currentDir.absolutePath}" }
            }

            val soilFile = File(currentDir, "$name.soil")

            val db = SQLiteDatabase.openOrCreateDatabase(soilFile, null)
            try {
                // PRAGMAs that return result sets require rawQuery + moveToFirst().
                db.rawQuery("PRAGMA journal_mode = WAL",        null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_autocheckpoint = 100",  null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notebook (
                        id          TEXT    NOT NULL PRIMARY KEY,
                        parentId    TEXT    NOT NULL,
                        boundingBox TEXT    NOT NULL,
                        "order"     INTEGER NOT NULL DEFAULT 0,
                        createdAt   INTEGER NOT NULL,
                        updatedAt   INTEGER NOT NULL,
                        deletedAt   INTEGER,
                        type        TEXT    NOT NULL,
                        data        TEXT    NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
                        ON notebook(parentId, "order", deletedAt)
                    """.trimIndent()
                )

                // ── Bootstrap notebook metadata row + first page + layer ──────
                // Get physical screen dimensions for the page bounding box.
                val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds
                } else {
                    val dm = resources.displayMetrics
                    android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val screenW = screenBounds.width().toFloat()
                val screenH = screenBounds.height().toFloat()
                val bboxJson = """{"x":0.0,"y":0.0,"width":$screenW,"height":$screenH}"""
                val now = System.currentTimeMillis()

                // `order` is a reserved word in SQLite — must be double-quoted in SQL.
                // db.insert() with ContentValues builds the column list unquoted, so SQLite
                // silently rejects the INSERT (returns -1). Use execSQL with explicit quoting.
                val insertSql =
                    """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data)
                       VALUES (?, ?, ?, 0, ?, ?, NULL, ?, ?)"""

                // Pre-generate the first page UUID so the notebook metadata row can
                // record it as last_opened_page immediately at creation time.
                val notebookId = UUID.randomUUID().toString()
                val pageId     = UUID.randomUUID().toString()

                // 1. Notebook metadata row — must be inserted first; its UUID is the
                //    parentId for all page rows in this notebook.
                val notebookDataJson = NotebookMetadata(
                    id             = notebookId,
                    title          = name,
                    cover          = "",
                    lastOpenedPage = pageId,
                ).toJson()
                db.execSQL(insertSql, arrayOf(
                    notebookId, "", "{}", now, now, "notebook", notebookDataJson
                ))

                // 2. First page — parentId is the notebook metadata row's UUID.
                //    template:"" means no template (blank background).
                db.execSQL(insertSql, arrayOf(
                    pageId, notebookId, bboxJson, now, now, "page",
                    """{"width":$screenW,"height":$screenH,"template":""}"""
                ))

                // 3. Content layer for the first page.
                val layerId = UUID.randomUUID().toString()
                db.execSQL(insertSql, arrayOf(
                    layerId, pageId, bboxJson, now, now, "layer",
                    """{"label":"Content","isLocked":false,"isVisible":true}"""
                ))

                // Clean close: reclaim space and truncate WAL to zero bytes.
                db.rawQuery("PRAGMA incremental_vacuum",        null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",  null).use { it.moveToFirst() }
            } finally {
                db.close()
            }

            // Delete any 0-byte rollback journal left from database initialisation.
            File("${soilFile.absolutePath}-journal").takeIf { it.exists() }?.delete()

            Toast.makeText(this, "Notebook '$name' created", Toast.LENGTH_SHORT).show()

            // Rescan so the list is current when the user presses back.
            scanAndRender()
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                val idx = items.indexOfFirst {
                    it is NotebookListItem.Notebook && it.file.nameWithoutExtension == name
                }
                if (idx >= 0) navigatePage(idx / spec.itemsPerPage)
            }

            // Open the new notebook immediately — no need to tap it in the list.
            val intent = Intent(this, NotebookActivity::class.java).apply {
                putExtra(NotebookActivity.EXTRA_NOTEBOOK_PATH, soilFile.absolutePath)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Notebook context menu ─────────────────────────────────────────────────

    private fun showNotebookContextMenu(file: File) {
        ActionSheetDialog(this)
            .title(file.nameWithoutExtension)
            .addAction(R.drawable.ic_export, "Export") { startExportFromMain(file) }
            .addAction(R.drawable.ic_copy_page, "Copy Notebook") { enterPickerMode(DestinationPickerState.CopyNotebook(file)) }
            .addAction(R.drawable.ic_move_page, "Move Notebook") { enterPickerMode(DestinationPickerState.MoveNotebook(file)) }
            .addAction(R.drawable.ic_polaroid, "Set Cover") { openCoverDialog(file) }
            .addAction(R.drawable.ic_delete_notebook, "Delete Notebook") { showDeleteNotebookConfirmation(file) }
            .show()
    }

    // ── Folder context menu ───────────────────────────────────────────────────

    private fun showFolderContextMenu(file: File) {
        ActionSheetDialog(this)
            .title(file.name)
            .addAction(R.drawable.ic_copy_plus, "Copy Folder") { enterPickerMode(DestinationPickerState.CopyFolder(file)) }
            .addAction(R.drawable.ic_move_page, "Move Folder") { enterPickerMode(DestinationPickerState.MoveFolder(file)) }
            .addAction(R.drawable.ic_folder_minus, "Delete") { showDeleteFolderConfirmation(file) }
            .show()
    }

    // ── Destination picker mode ───────────────────────────────────────────────

    private fun enterPickerMode(state: DestinationPickerState) {
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
        binding.pickerToolbar.visibility = if (inPicker) View.VISIBLE else View.GONE
        binding.pickerToolbarDivider.visibility = if (inPicker) View.VISIBLE else View.GONE
        if (inPicker) {
            updatePickerTitle()
            binding.btnNewNotebook.visibility = View.GONE
            binding.btnSearch.visibility = View.GONE
            binding.btnClearSearch.visibility = View.GONE
            binding.btnSort.visibility = View.GONE
        } else {
            binding.btnNewNotebook.visibility = View.VISIBLE
            binding.btnSearch.visibility = View.VISIBLE
            binding.btnClearSearch.visibility = View.GONE
            binding.btnSort.visibility = View.VISIBLE
        }
    }

    private fun updatePickerTitle() {
        val (title, confirmLabel) = when (destinationPickerState) {
            is DestinationPickerState.CopyNotebook -> "Copy notebook here" to "Copy here"
            is DestinationPickerState.MoveNotebook -> "Move notebook here" to "Move here"
            is DestinationPickerState.CopyFolder   -> "Copy folder here" to "Copy here"
            is DestinationPickerState.MoveFolder   -> "Move folder here" to "Move here"
            DestinationPickerState.None            -> "" to ""
        }
        binding.pickerTitle.text = title
        binding.btnPickerConfirm.text = confirmLabel
    }

    private fun confirmPickerDestination() {
        val state = destinationPickerState
        if (state == DestinationPickerState.None) return

        val source: File = when (state) {
            is DestinationPickerState.CopyNotebook -> state.source
            is DestinationPickerState.MoveNotebook -> state.source
            is DestinationPickerState.CopyFolder   -> state.source
            is DestinationPickerState.MoveFolder   -> state.source
            DestinationPickerState.None            -> return
        }
        val destDir = currentDirectory

        // Validate destination before proceeding.
        when (state) {
            is DestinationPickerState.CopyNotebook,
            is DestinationPickerState.MoveNotebook -> {
                if (destDir.canonicalPath == source.parentFile?.canonicalPath) {
                    Toast.makeText(this, "Already in this folder", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            is DestinationPickerState.CopyFolder,
            is DestinationPickerState.MoveFolder -> {
                val sourceCanonical = source.canonicalPath
                if (destDir.canonicalPath == sourceCanonical ||
                    destDir.canonicalPath.startsWith(sourceCanonical + File.separator)) {
                    val verb = if (state is DestinationPickerState.CopyFolder) "copy" else "move"
                    Toast.makeText(this, "Cannot $verb a folder into itself", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            DestinationPickerState.None -> return
        }

        val destFile = File(destDir, source.name)
        if (destFile.exists()) {
            val itemType = when (state) {
                is DestinationPickerState.CopyNotebook,
                is DestinationPickerState.MoveNotebook -> "notebook"
                else -> "folder"
            }
            val displayName = when (state) {
                is DestinationPickerState.CopyNotebook,
                is DestinationPickerState.MoveNotebook -> source.nameWithoutExtension
                else -> source.name
            }
            val dialog = AlertDialog.Builder(this)
                .setMessage("A $itemType named \"$displayName\" already exists here. Replace it?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> executePickerOperation(state, source, destFile) }
                .create()
            dialog.show()
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        } else {
            executePickerOperation(state, source, destFile)
        }
    }

    private fun executePickerOperation(
        state: DestinationPickerState,
        source: File,
        dest: File,
    ) {
        val isCopy = state is DestinationPickerState.CopyNotebook ||
                state is DestinationPickerState.CopyFolder
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    when (state) {
                        is DestinationPickerState.MoveNotebook -> {
                            if (dest.exists()) dest.delete()
                            source.renameTo(dest)
                        }
                        is DestinationPickerState.CopyNotebook -> {
                            source.copyTo(dest, overwrite = true)
                            true
                        }
                        is DestinationPickerState.MoveFolder -> {
                            if (dest.exists()) dest.deleteRecursively()
                            source.renameTo(dest)
                        }
                        is DestinationPickerState.CopyFolder -> {
                            source.copyRecursively(dest, overwrite = true)
                            true
                        }
                        DestinationPickerState.None -> false
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

    private fun showDeleteFolderConfirmation(file: File) {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete \"${file.name}\"? This will permanently remove all notebooks and subfolders inside it. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteFolder(file) }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun deleteFolder(file: File) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { file.deleteRecursively() }
            if (!success) {
                Toast.makeText(this@MainActivity, "Some files could not be deleted.", Toast.LENGTH_SHORT).show()
            }
            scanAndRender()
        }
    }

    /**
     * Loads the last-opened page snapshot for [file], then opens [CoverDialog].
     * Cover changes trigger a per-card cover reload via the callback.
     */
    private fun openCoverDialog(file: File) {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { loadLastPageSnapshot(file) }
            val dialog = CoverDialog(
                activity           = this@MainActivity,
                lifecycleScope     = lifecycleScope,
                soilFilePath       = file.absolutePath,
                lastOpenedSnapshot = snapshot,
                onRequestImagePick = { callback ->
                    onCoverImagePicked = callback
                    coverPickerLauncher.launch("image/*")
                },
                onCoverChanged = {
                    // Reload the cover bitmap for this specific notebook card.
                    reloadCoverForNotebook(file)
                },
            )
            dialog.show()
        }
    }

    private fun startExportFromMain(file: File) {
        val tvMessage = android.widget.TextView(this).apply {
            text = "Exporting…"
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
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        lifecycleScope.launch {
            val pdfFile = try {
                withContext(Dispatchers.IO) {
                    val db = androidx.room.Room.databaseBuilder(
                        applicationContext,
                        com.notesprout.android.data.SoilDatabase::class.java,
                        file.absolutePath,
                    )
                        .addCallback(com.notesprout.android.data.SoilDatabase.openCallback())
                        .build()
                    try {
                        NotebookExporter.export(
                            context = this@MainActivity,
                            db = db,
                            onProgress = { current, total ->
                                handler.post { tvMessage.text = "Exporting page $current of $total…" }
                            },
                        )
                    } finally {
                        db.close()
                    }
                }
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            dialog.dismiss()
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity,
                "$packageName.fileprovider",
                pdfFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share PDF"))
        }
    }

    /**
     * Loads the last-opened page snapshot from [file]. Returns null on any error.
     *
     * Opens `OPEN_READWRITE` (not `OPEN_READONLY`) so [checkpointTruncateAndClose] can let SQLite
     * unlink `-wal`/`-shm` on close — a read-only WAL connection strands those sidecars.
     */
    private fun loadLastPageSnapshot(file: File): Bitmap? {
        var db: android.database.sqlite.SQLiteDatabase? = null
        return try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            val (notebookId, metaJson) = db.rawQuery(
                "SELECT id, data FROM notebook WHERE type = 'notebook' LIMIT 1", null
            ).use { c ->
                if (!c.moveToFirst()) return null
                Pair(c.getString(0), c.getString(1))
            }
            val metadata = NotebookMetadata.fromJson(notebookId, metaJson)
            val pageId = metadata.lastOpenedPage ?: return null
            if (pageId.isEmpty()) return null

            val pageJson = db.rawQuery(
                "SELECT data FROM notebook WHERE id = ? AND deletedAt IS NULL LIMIT 1",
                arrayOf(pageId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

            // Extract the snapshot field from the page data JSON.
            val snapshotB64 = try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(pageJson)
                element.jsonObject["snapshot"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) { "" }
            if (snapshotB64.isEmpty()) return null

            val bytes = Base64.decode(snapshotB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load last-page snapshot for ${file.name}", e)
            null
        } finally {
            db?.checkpointTruncateAndClose("MainActivity", file)
        }
    }

    /**
     * Cancels any in-flight cover load job for [file] and starts a fresh one that
     * reloads the bitmap and rebinds it to the matching card view in the current grid.
     *
     * This must run on the main thread.  The cover load is launched on IO via
     * [loadNotebookCoverBitmap] — the card view reference is safe because we only
     * update it from the main thread after the coroutine completes.
     */
    private fun reloadCoverForNotebook(file: File) {
        // Re-render the current grid page so the new cover is reflected.
        // This is the simplest correct approach — it cancels all in-flight jobs and
        // rebuilds all cards, picking up the updated cover from the .soil file.
        scanAndRender()
    }

    /**
     * Shows a confirmation AlertDialog before deleting [file].
     * The notebook name is included so the user knows exactly what they're deleting.
     */
    private fun showDeleteNotebookConfirmation(file: File) {
        val name   = file.nameWithoutExtension
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete notebook \"$name\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteNotebook(file) }
            .create()
        dialog.show()
        // Style after show() — window only exists once the dialog is displayed.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /**
     * Deletes a notebook's .soil file and any sibling SQLite artefacts
     * (e.g. name.soil-wal, name.soil-shm, name.soil-journal) on the IO dispatcher,
     * then refreshes the grid on the main thread.
     *
     * Guard note: NotebookActivity manages its own Room instance; there is no shared
     * database handle in MainActivity.  If a user somehow reaches this path while the
     * notebook is open in NotebookActivity (which the normal back-stack makes impossible),
     * deleting the file while Room holds it open is safe on Android — the process-level
     * file handle keeps data intact until NotebookActivity closes its instance.
     */
    private fun deleteNotebook(file: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Delete the .soil file itself plus any sibling artefacts whose names
                // start with the .soil filename (catches -wal, -shm, -journal suffixes).
                file.parentFile
                    ?.listFiles { f -> f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
            }
            // Re-scan on the main thread so the grid reflects the deletion.
            scanAndRender()
        }
    }

}
