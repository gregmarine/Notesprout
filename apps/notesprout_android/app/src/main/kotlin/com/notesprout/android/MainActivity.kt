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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.checkpointTruncateAndClose
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.soilFile
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
import com.notesprout.android.databinding.ActivityMainBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
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

    // ── Cover image picker launcher ───────────────────────────────────────────

    private var onCoverImagePicked: ((Uri) -> Unit)? = null

    private val coverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onCoverImagePicked?.invoke(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // null = root level
        directoryStack.add(null)

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

        binding.btnClearSearch.setOnClickListener { exitSearchMode() }

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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
                val snapshotB64 = try {
                    Json.decodeFromString<NotebookObject>(item.entity.data).snapshot
                } catch (_: Exception) { null }

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

        // ── Label ─────────────────────────────────────────────────────────────
        val labelText = run {
            val entity = when (item) {
                is NotebookListItem.Folder   -> item.entity
                is NotebookListItem.Notebook -> item.entity
            }
            val displayName = entity.name
            if (isSearchMode && item is NotebookListItem.Notebook) {
                val result = searchResults.find { it.entity.id == entity.id }
                if (result != null) "${result.folderLabel} › ${result.displayName}" else displayName
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

    // ── Notebook opening ──────────────────────────────────────────────────────

    private fun openNotebook(entity: ObjectEntity) {
        if (isSearchMode) {
            // Navigate the stack to the notebook's parent folder so that returning from
            // NotebookActivity lands in the correct folder.
            lifecycleScope.launch {
                navigateStackToFolder(entity.parentId)
                isSearchMode = false
                currentSearchQuery = ""
                searchResults = emptyList()
                binding.btnClearSearch.visibility = View.GONE
                binding.btnSort.visibility = View.VISIBLE
                launchNotebookActivity(entity)
            }
            return
        }
        launchNotebookActivity(entity)
    }

    private fun launchNotebookActivity(entity: ObjectEntity) {
        val intent = Intent(this, NotebookActivity::class.java).apply {
            putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID,   entity.id)
            putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, entity.name)
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

    // ── Notebook creation ─────────────────────────────────────────────────────

    private fun createNotebook(name: String) {
        lifecycleScope.launch {
            try {
                validateNotebookName(name)?.let { error ->
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 1. Create the index entry — its id becomes the filename.
                val entity = withContext(Dispatchers.IO) {
                    repository.createNotebook(name, currentParentId)
                }

                // 2. Create the physical .soil file at its UUID path.
                val soilPath = soilFile(this@MainActivity, entity.id)

                withContext(Dispatchers.IO) {
                    val db = SQLiteDatabase.openOrCreateDatabase(soilPath, null)
                    try {
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

                        val insertSql =
                            """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data)
                               VALUES (?, ?, ?, 0, ?, ?, NULL, ?, ?)"""

                        val notebookId = UUID.randomUUID().toString()
                        val pageId     = UUID.randomUUID().toString()

                        val notebookDataJson = NotebookMetadata(
                            id             = notebookId,
                            title          = name,
                            cover          = "",
                            lastOpenedPage = pageId,
                        ).toJson()
                        db.execSQL(insertSql, arrayOf(
                            notebookId, "", "{}", now, now, "notebook", notebookDataJson
                        ))

                        db.execSQL(insertSql, arrayOf(
                            pageId, notebookId, bboxJson, now, now, "page",
                            """{"width":$screenW,"height":$screenH,"template":""}"""
                        ))

                        val layerId = UUID.randomUUID().toString()
                        db.execSQL(insertSql, arrayOf(
                            layerId, pageId, bboxJson, now, now, "layer",
                            """{"label":"Content","isLocked":false,"isVisible":true}"""
                        ))

                        db.rawQuery("PRAGMA incremental_vacuum",       null).use { it.moveToFirst() }
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    } finally {
                        db.close()
                    }

                    // Remove any 0-byte rollback journal from initialisation.
                    java.io.File("${soilPath.absolutePath}-journal").takeIf { it.exists() }?.delete()
                }

                Toast.makeText(this@MainActivity, "Notebook '$name' created", Toast.LENGTH_SHORT).show()

                // Rescan and navigate to the page containing the new notebook.
                scanAndRender()
                val spec = gridSpec
                if (spec != null && spec.itemsPerPage > 0) {
                    val idx = items.indexOfFirst {
                        it is NotebookListItem.Notebook && it.entity.id == entity.id
                    }
                    if (idx >= 0) navigatePage(idx / spec.itemsPerPage)
                }

                // Open the new notebook immediately.
                launchNotebookActivity(entity)

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Notebook context menu ─────────────────────────────────────────────────

    private fun showNotebookContextMenu(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title(entity.name)
            .addAction(R.drawable.ic_export,          "Export")          { startExportFromMain(entity) }
            .addAction(R.drawable.ic_copy_page,       "Copy Notebook")   { enterPickerMode(DestinationPickerState.CopyNotebook(entity)) }
            .addAction(R.drawable.ic_move_page,       "Move Notebook")   { enterPickerMode(DestinationPickerState.MoveNotebook(entity)) }
            .addAction(R.drawable.ic_polaroid,        "Set Cover")       { openCoverDialog(entity) }
            .addAction(R.drawable.ic_delete_notebook, "Delete Notebook") { showDeleteNotebookConfirmation(entity) }
            .show()
    }

    // ── Folder context menu ───────────────────────────────────────────────────

    private fun showFolderContextMenu(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title(entity.name)
            .addAction(R.drawable.ic_copy_plus,       "Copy Folder") { enterPickerMode(DestinationPickerState.CopyFolder(entity)) }
            .addAction(R.drawable.ic_move_page,       "Move Folder") { enterPickerMode(DestinationPickerState.MoveFolder(entity)) }
            .addAction(R.drawable.ic_folder_minus,    "Delete")      { showDeleteFolderConfirmation(entity) }
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
        binding.pickerToolbar.visibility        = if (inPicker) View.VISIBLE else View.GONE
        binding.pickerToolbarDivider.visibility = if (inPicker) View.VISIBLE else View.GONE
        if (inPicker) {
            updatePickerTitle()
            binding.btnNewNotebook.visibility   = View.GONE
            binding.btnSearch.visibility        = View.GONE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.GONE
        } else {
            binding.btnNewNotebook.visibility   = View.VISIBLE
            binding.btnSearch.visibility        = View.VISIBLE
            binding.btnClearSearch.visibility   = View.GONE
            binding.btnSort.visibility          = View.VISIBLE
        }
    }

    private fun updatePickerTitle() {
        val (title, confirmLabel) = when (destinationPickerState) {
            is DestinationPickerState.CopyNotebook -> "Copy notebook here" to "Copy here"
            is DestinationPickerState.MoveNotebook -> "Move notebook here" to "Move here"
            is DestinationPickerState.CopyFolder   -> "Copy folder here"   to "Copy here"
            is DestinationPickerState.MoveFolder   -> "Move folder here"   to "Move here"
            DestinationPickerState.None            -> "" to ""
        }
        binding.pickerTitle.text = title
        binding.btnPickerConfirm.text = confirmLabel
    }

    private fun confirmPickerDestination() {
        val state = destinationPickerState
        if (state == DestinationPickerState.None) return

        lifecycleScope.launch {
            val source: ObjectEntity = when (state) {
                is DestinationPickerState.CopyNotebook -> state.source
                is DestinationPickerState.MoveNotebook -> state.source
                is DestinationPickerState.CopyFolder   -> state.source
                is DestinationPickerState.MoveFolder   -> state.source
                DestinationPickerState.None            -> return@launch
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
                DestinationPickerState.None -> return@launch
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
                dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            } else {
                executePickerOperation(state, source, null)
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
                    // Delete conflicting entry first.
                    if (conflictId != null) {
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

                    when (state) {
                        is DestinationPickerState.MoveNotebook -> {
                            repository.moveObject(source.id, currentParentId)
                            true
                        }
                        is DestinationPickerState.CopyNotebook -> {
                            val sourceObj = try {
                                Json.decodeFromString<NotebookObject>(source.data)
                            } catch (_: Exception) { NotebookObject() }
                            val newEntity = repository.createNotebook(source.name, currentParentId)
                            if (sourceObj.snapshot != null) {
                                repository.updateNotebookSnapshot(newEntity.id, sourceObj.snapshot)
                            }
                            if (sourceObj.pageCount > 0) {
                                repository.updateNotebookPageCount(newEntity.id, sourceObj.pageCount)
                            }
                            val srcFile = soilFile(this@MainActivity, source.id)
                            if (srcFile.exists()) {
                                srcFile.copyTo(soilFile(this@MainActivity, newEntity.id), overwrite = true)
                            }
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

    // ── Recursive folder helpers ──────────────────────────────────────────────

    /** Soft-deletes the folder and all its descendants; deletes physical .soil files. */
    private suspend fun deleteFolderRecursively(folderId: String) {
        val children = repository.getChildren(folderId)
        for (child in children) {
            when (child.type) {
                ObjectType.NOTEBOOK -> {
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
                        Json.decodeFromString<NotebookObject>(child.data)
                    } catch (_: Exception) { NotebookObject() }
                    val newNotebook = repository.createNotebook(child.name, newFolder.id)
                    if (sourceObj.snapshot != null) {
                        repository.updateNotebookSnapshot(newNotebook.id, sourceObj.snapshot)
                    }
                    if (sourceObj.pageCount > 0) {
                        repository.updateNotebookPageCount(newNotebook.id, sourceObj.pageCount)
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
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun deleteFolder(entity: ObjectEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { deleteFolderRecursively(entity.id) }
            scanAndRender()
        }
    }

    // ── Cover dialog ──────────────────────────────────────────────────────────

    private fun openCoverDialog(entity: ObjectEntity) {
        val snapshotB64 = try {
            Json.decodeFromString<NotebookObject>(entity.data).snapshot
        } catch (_: Exception) { null }

        lifecycleScope.launch {
            val snapshot: Bitmap? = if (snapshotB64 != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(snapshotB64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
            } else {
                // Snapshot not in index yet — read from .soil and cache it.
                withContext(Dispatchers.IO) { loadAndCacheSnapshot(entity) }
            }

            val dialog = CoverDialog(
                activity           = this@MainActivity,
                lifecycleScope     = lifecycleScope,
                soilFilePath       = soilFile(this@MainActivity, entity.id).absolutePath,
                lastOpenedSnapshot = snapshot,
                onRequestImagePick = { callback ->
                    onCoverImagePicked = callback
                    coverPickerLauncher.launch("image/*")
                },
                onCoverChanged = { reloadCoverForNotebook(entity) },
            )
            dialog.show()
        }
    }

    /**
     * Reads the cover/snapshot from the .soil file and persists it to the index so
     * future list renders can use the cached value without opening the .soil.
     */
    private suspend fun loadAndCacheSnapshot(entity: ObjectEntity): Bitmap? {
        val file = soilFile(this, entity.id)
        if (!file.exists()) return null
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

            val snapshotB64 = try {
                val el = kotlinx.serialization.json.Json.parseToJsonElement(pageJson)
                el.jsonObject["snapshot"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) { "" }
            if (snapshotB64.isEmpty()) return null

            repository.updateNotebookSnapshot(entity.id, snapshotB64)

            val bytes = Base64.decode(snapshotB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("MainActivity", "loadAndCacheSnapshot failed for ${entity.id}", e)
            null
        } finally {
            db?.checkpointTruncateAndClose("MainActivity", file)
        }
    }

    /**
     * After a cover change in CoverDialog: reads the new cover from the .soil, persists the
     * base64 to the index, then rescans so the grid card shows the updated image.
     */
    private fun reloadCoverForNotebook(entity: ObjectEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = soilFile(this@MainActivity, entity.id)
                    if (!file.exists()) return@withContext
                    var db: android.database.sqlite.SQLiteDatabase? = null
                    try {
                        db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            file.absolutePath, null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                        )
                        // Read the explicit cover object if present.
                        val (notebookId, metaJson) = db.rawQuery(
                            "SELECT id, data FROM notebook WHERE type = 'notebook' LIMIT 1", null
                        ).use { c ->
                            if (!c.moveToFirst()) return@withContext
                            Pair(c.getString(0), c.getString(1))
                        }
                        val metadata = NotebookMetadata.fromJson(notebookId, metaJson)

                        val coverB64: String? = if (metadata.cover.isNotEmpty()) {
                            db.rawQuery(
                                "SELECT data FROM notebook WHERE parentId = ? AND type = 'cover' AND deletedAt IS NULL LIMIT 1",
                                arrayOf(notebookId)
                            ).use { c ->
                                if (c.moveToFirst()) {
                                    try {
                                        val obj = lenientJson
                                            .decodeFromString<com.notesprout.android.data.CoverObject>(c.getString(0))
                                        obj.image.takeIf { it.isNotEmpty() }
                                    } catch (_: Exception) { null }
                                } else null
                            }
                        } else null

                        if (coverB64 != null) {
                            repository.updateNotebookSnapshot(entity.id, coverB64)
                        }
                    } finally {
                        db?.checkpointTruncateAndClose("MainActivity", file)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "reloadCoverForNotebook failed for ${entity.id}", e)
                }
            }
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
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun deleteNotebook(entity: ObjectEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.softDeleteNotebook(entity.id)
                val file = soilFile(this@MainActivity, entity.id)
                // Delete .soil and any sibling artefacts (-wal, -shm, -journal).
                file.parentFile?.listFiles { f -> f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
            }
            scanAndRender()
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun startExportFromMain(entity: ObjectEntity) {
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
        val file = soilFile(this, entity.id)

        lifecycleScope.launch {
            val pdfFile = try {
                withContext(Dispatchers.IO) {
                    val db = androidx.room.Room.databaseBuilder(
                        applicationContext,
                        SoilDatabase::class.java,
                        file.absolutePath,
                    )
                        .addCallback(SoilDatabase.openCallback())
                        .build()
                    try {
                        NotebookExporter.export(
                            context    = this@MainActivity,
                            db         = db,
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
}
