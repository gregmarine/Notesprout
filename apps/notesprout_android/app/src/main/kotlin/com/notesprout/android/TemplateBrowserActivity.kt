package com.notesprout.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapFactory.Options
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import android.view.GestureDetector
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.core.Slog
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.TemplateObject
import com.notesprout.android.databinding.ActivityTemplateBrowserBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen template library browser and manager.
 *
 * **MODE_MANAGE** (launched from the MainActivity toolbar): browse folders + templates, create
 * template folders, import PNGs, tap to preview. No selection result.
 *
 * **MODE_PICK** (S5/S6 — constants defined now, implemented later): select a template and return
 * its id via [RESULT_TEMPLATE_ID]. When [EXTRA_COLLECT_NAME] is true (S6), also shows a name
 * field and returns [RESULT_NOTEBOOK_NAME].
 */
class TemplateBrowserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TemplateBrowser"

        /** Mode extra key. */
        const val EXTRA_MODE = "mode"
        /** Manage mode — no selection result. */
        const val MODE_MANAGE = 0
        /** Pick mode — returns a template id (S5/S6). */
        const val MODE_PICK = 1

        /** PICK only: also show a name field + CREATE (S6). Default false. */
        const val EXTRA_COLLECT_NAME = "collect_name"
        /** Optional title override. */
        const val EXTRA_TITLE = "title"

        /** Result: chosen template id. "" = Blank. */
        const val RESULT_TEMPLATE_ID = "result_template_id"
        /** Result: only when [EXTRA_COLLECT_NAME]. */
        const val RESULT_NOTEBOOK_NAME = "result_notebook_name"

        // Thumbnail sampling: cap inSampleSize at 4 — mirrors TemplateDialog.computeInSampleSize.
        private const val THUMB_PX = 1300
        private const val MAX_THUMBNAIL_SAMPLE = 4
    }

    // ── Grid spec ─────────────────────────────────────────────────────────────

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

    // ── Browse items ─────────────────────────────────────────────────────────

    private sealed class BrowseItem {
        abstract val entity: ObjectEntity
        data class Folder(override val entity: ObjectEntity) : BrowseItem()
        data class Template(override val entity: ObjectEntity) : BrowseItem()
    }

    // ── Template picker (local — does NOT touch the shared DestinationPickerState) ─────────────

    private sealed class TemplatePicker {
        object None : TemplatePicker()
        data class CopyTemplate(val source: ObjectEntity) : TemplatePicker()
        data class MoveTemplate(val source: ObjectEntity) : TemplatePicker()
        data class CopyFolder(val source: ObjectEntity) : TemplatePicker()
        data class MoveFolder(val source: ObjectEntity) : TemplatePicker()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var mode: Int = MODE_MANAGE

    // Directory navigation — null = root.
    private val directoryStack = ArrayDeque<ObjectEntity?>().apply { add(null) }
    private val currentParentId: String? get() = directoryStack.last()?.id

    private var browseItems: List<BrowseItem> = emptyList()
    private var currentGridPage: Int = 0
    private var gridSpec: GridSpec? = null

    private var picker: TemplatePicker = TemplatePicker.None

    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private lateinit var binding: ActivityTemplateBrowserBinding

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    /** In-memory thumbnail cache keyed by "${id}:${updatedAt}" per §2.4. */
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val decodeJobs = mutableListOf<Job>()

    // ── Swipe gesture (left/right to paginate) ────────────────────────────────

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float,
            ): Boolean {
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                return if (velocityX < 0) {
                    navigateGridPage(currentGridPage + 1); true
                } else {
                    navigateGridPage(currentGridPage - 1); true
                }
            }
        })
    }

    // ── PNG import launcher ───────────────────────────────────────────────────

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) performImport(uri) }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_MANAGE)
        val titleOverride = intent.getStringExtra(EXTRA_TITLE)
        if (titleOverride != null) binding.tvTopBarTitle.text = titleOverride

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBreadcrumbBack.setOnClickListener { navigateUpOneLevel() }

        binding.btnFirstPage.setOnClickListener { navigateGridPage(0) }
        binding.btnPrevPage.setOnClickListener  { navigateGridPage(currentGridPage - 1) }
        binding.btnNextPage.setOnClickListener  { navigateGridPage(currentGridPage + 1) }
        binding.btnLastPage.setOnClickListener  { navigateGridPage(totalGridPages() - 1) }

        binding.btnNewTemplateFolder.setOnClickListener { showNewTemplateFolderDialog() }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("image/png")) }

        binding.btnPickerConfirm.setOnClickListener { confirmPickerDestination() }
        binding.btnPickerCancel.setOnClickListener  { exitPicker() }

        binding.gridContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // While in picker: cancel picker if at root, else navigate up.
                if (picker != TemplatePicker.None) {
                    if (directoryStack.size <= 1) {
                        exitPicker()
                    } else {
                        navigateUpOneLevel()
                    }
                    return
                }
                if (!navigateUpOneLevel()) finish()
            }
        })

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    loadAndRender()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (gridSpec != null) loadAndRender()
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateIntoFolder(entity: ObjectEntity) {
        directoryStack.add(entity)
        currentGridPage = 0
        loadAndRender()
    }

    /** Returns true if navigation happened (consumed back press), false if already at root. */
    private fun navigateUpOneLevel(): Boolean {
        if (directoryStack.size <= 1) return false
        directoryStack.removeLast()
        currentGridPage = 0
        loadAndRender()
        return true
    }

    // ── Picker mode ───────────────────────────────────────────────────────────

    private fun enterPicker(state: TemplatePicker) {
        picker = state
        // Reset navigation to root (mirror MainActivity which resets to root when entering picker).
        directoryStack.clear()
        directoryStack.add(null)
        currentGridPage = 0
        applyPickerUI()
        loadAndRender()
    }

    private fun exitPicker() {
        picker = TemplatePicker.None
        directoryStack.clear()
        directoryStack.add(null)
        currentGridPage = 0
        applyPickerUI()
        loadAndRender()
    }

    private fun applyPickerUI() {
        val inPicker = picker != TemplatePicker.None
        binding.pickerToolbar.visibility        = if (inPicker) View.VISIBLE else View.GONE
        binding.pickerToolbarDivider.visibility = if (inPicker) View.VISIBLE else View.GONE
        if (inPicker) {
            val (title, confirmLabel) = when (picker) {
                is TemplatePicker.CopyTemplate -> "Copy template here" to "Copy here"
                is TemplatePicker.MoveTemplate -> "Move template here" to "Move here"
                is TemplatePicker.CopyFolder   -> "Copy folder here"   to "Copy here"
                is TemplatePicker.MoveFolder   -> "Move folder here"   to "Move here"
                TemplatePicker.None            -> "" to ""
            }
            binding.pickerTitle.text = title
            binding.btnPickerConfirm.text = confirmLabel
            // Hide action buttons in picker mode (mirror MainActivity).
            binding.btnNewTemplateFolder.visibility = View.GONE
            binding.btnImport.visibility            = View.GONE
        } else {
            binding.btnNewTemplateFolder.visibility = View.VISIBLE
            binding.btnImport.visibility            = View.VISIBLE
        }
    }

    private fun confirmPickerDestination() {
        val state = picker
        if (state == TemplatePicker.None) return

        lifecycleScope.launch {
            val source: ObjectEntity = when (state) {
                is TemplatePicker.CopyTemplate -> state.source
                is TemplatePicker.MoveTemplate -> state.source
                is TemplatePicker.CopyFolder   -> state.source
                is TemplatePicker.MoveFolder   -> state.source
                TemplatePicker.None            -> return@launch
            }

            // ── Guard: no-op move (already in same parent) ─────────────────────
            when (state) {
                is TemplatePicker.MoveTemplate,
                is TemplatePicker.MoveFolder -> {
                    if (currentParentId == source.parentId) {
                        Toast.makeText(this@TemplateBrowserActivity,
                            "Already in this folder", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                else -> {}
            }

            // ── Guard: self/descendant (folder copy/move only) ─────────────────
            when (state) {
                is TemplatePicker.CopyFolder,
                is TemplatePicker.MoveFolder -> {
                    if (isSelfOrDescendant(currentParentId, source.id)) {
                        val verb = if (state is TemplatePicker.CopyFolder) "copy" else "move"
                        Toast.makeText(this@TemplateBrowserActivity,
                            "Cannot $verb a folder into itself", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                else -> {}
            }

            // ── Conflict check at destination ──────────────────────────────────
            val isCopyTemplate = state is TemplatePicker.CopyTemplate
            val isCopyFolder   = state is TemplatePicker.CopyFolder
            val existingConflict: ObjectEntity? = withContext(Dispatchers.IO) {
                when (state) {
                    is TemplatePicker.CopyTemplate,
                    is TemplatePicker.MoveTemplate -> {
                        repository.getTemplates(currentParentId)
                            .find { it.name == source.name && it.id != source.id }
                    }
                    is TemplatePicker.CopyFolder,
                    is TemplatePicker.MoveFolder -> {
                        repository.getTemplateFolders(currentParentId)
                            .find { it.name == source.name && it.id != source.id }
                    }
                    TemplatePicker.None -> null
                }
            }

            if (existingConflict != null) {
                val itemType = when (state) {
                    is TemplatePicker.CopyTemplate,
                    is TemplatePicker.MoveTemplate -> "template"
                    else                           -> "folder"
                }
                val dialog = AlertDialog.Builder(this@TemplateBrowserActivity)
                    .setMessage("A $itemType named \"${source.name}\" already exists here. Replace it?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace") { _, _ ->
                        executePickerOperation(state, source, existingConflict.id)
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

    private fun executePickerOperation(
        state: TemplatePicker,
        source: ObjectEntity,
        conflictId: String?,
    ) {
        val isCopy = state is TemplatePicker.CopyTemplate || state is TemplatePicker.CopyFolder
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Delete conflicting entry first.
                    if (conflictId != null) {
                        when (state) {
                            is TemplatePicker.CopyTemplate,
                            is TemplatePicker.MoveTemplate -> repository.softDeleteTemplate(conflictId)
                            is TemplatePicker.CopyFolder,
                            is TemplatePicker.MoveFolder   -> repository.deleteTemplateFolderRecursively(conflictId)
                            TemplatePicker.None -> {}
                        }
                    }
                    // Execute the operation.
                    when (state) {
                        is TemplatePicker.MoveTemplate -> {
                            repository.moveObject(source.id, currentParentId)
                            true
                        }
                        is TemplatePicker.CopyTemplate -> {
                            repository.copyTemplate(source.id, currentParentId)
                            true
                        }
                        is TemplatePicker.MoveFolder -> {
                            repository.moveObject(source.id, currentParentId)
                            true
                        }
                        is TemplatePicker.CopyFolder -> {
                            repository.copyTemplateFolderRecursively(source.id, currentParentId)
                            true
                        }
                        TemplatePicker.None -> false
                    }
                } catch (e: Exception) {
                    Slog.d(TAG) { "executePickerOperation failed: ${e.message}" }
                    false
                }
            }
            if (success) {
                picker = TemplatePicker.None
                directoryStack.clear()
                directoryStack.add(null)
                currentGridPage = 0
                applyPickerUI()
                loadAndRender()
                Toast.makeText(this@TemplateBrowserActivity,
                    if (isCopy) "Copied." else "Moved.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@TemplateBrowserActivity,
                    if (isCopy) "Copy failed." else "Move failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Returns true if [folderId] is [sourceId] itself or has [sourceId] as an ancestor.
     * Mirrors MainActivity.isSelfOrDescendant, scoped to template folders.
     * Uses repository.getTemplate (backed by dao.getById) which is type-agnostic.
     */
    private suspend fun isSelfOrDescendant(folderId: String?, sourceId: String): Boolean {
        if (folderId == null) return false
        if (folderId == sourceId) return true
        var id: String? = folderId
        while (id != null) {
            val folder = withContext(Dispatchers.IO) { repository.getTemplate(id!!) } ?: break
            id = folder.parentId
            if (id == sourceId) return true
        }
        return false
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadAndRender() {
        lifecycleScope.launch {
            browseItems = withContext(Dispatchers.IO) {
                val parentId = currentParentId
                val folders   = repository.getTemplateFolders(parentId).sortedBy { it.name.lowercase() }
                if (picker != TemplatePicker.None) {
                    // In picker mode: only show folders (no template cards — folders are destinations).
                    folders.map { BrowseItem.Folder(it) }
                } else {
                    val templates = repository.getTemplates(parentId).sortedBy { it.name.lowercase() }
                    folders.map { BrowseItem.Folder(it) } +
                        templates.map { BrowseItem.Template(it) }
                }
            }
            render()
        }
    }

    // ── Grid spec ─────────────────────────────────────────────────────────────

    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density       = resources.displayMetrics.density
        val gutterPx      = (12 * density).toInt()
        val paddingHPx    = (16 * density).toInt()
        val paddingVPx    = (16 * density).toInt()
        val rowGapPx      = (6  * density).toInt()
        val labelHeightPx = (32 * density).toInt()

        // Mirror TemplateDialog: 4 columns when widthPixels >= 1500, else 2.
        val cols = if (resources.displayMetrics.widthPixels >= 1500) 4 else 2

        val aspectRatio = resources.displayMetrics.heightPixels.toFloat() /
            resources.displayMetrics.widthPixels.coerceAtLeast(1)

        val innerWidth  = availableWidth  - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx

        val cardWidth  = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx

        val rows = ((innerHeight + gutterPx) / (cellHeight + gutterPx)).coerceAtLeast(1)

        return GridSpec(cols, rows, cardWidth, cardHeight, gutterPx, rowGapPx, labelHeightPx, paddingHPx, paddingVPx)
    }

    // ── Pagination helpers ─────────────────────────────────────────────────────

    private fun totalGridPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || browseItems.isEmpty()) return 1
        return (browseItems.size + perPage - 1) / perPage
    }

    private fun navigateGridPage(page: Int) {
        val clamped = page.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
        if (clamped == currentGridPage) return
        currentGridPage = clamped
        render()
    }

    private fun updatePaginationControls() {
        val total   = totalGridPages()
        val display = currentGridPage + 1
        binding.tvPage.text = "$display/$total"
        val atFirst = currentGridPage == 0
        val atLast  = currentGridPage >= total - 1
        binding.btnFirstPage.isEnabled = !atFirst
        binding.btnPrevPage.isEnabled  = !atFirst
        binding.btnNextPage.isEnabled  = !atLast
        binding.btnLastPage.isEnabled  = !atLast
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render() {
        if (gridSpec == null) return
        buildBreadcrumbs()

        decodeJobs.forEach { it.cancel() }
        decodeJobs.clear()
        binding.gridContainer.removeAllViews()

        if (browseItems.isEmpty()) {
            val emptyMsg = when {
                picker != TemplatePicker.None && directoryStack.size <= 1 -> "No template folders yet."
                picker != TemplatePicker.None -> "Empty folder."
                directoryStack.size > 1 -> "Empty folder."
                else -> "No templates yet."
            }
            renderEmptyState(emptyMsg)
        } else {
            val spec  = gridSpec ?: return
            val start = currentGridPage * spec.itemsPerPage
            val end   = minOf(start + spec.itemsPerPage, browseItems.size)
            val items = browseItems.subList(start, end)
            renderRows(items.size, spec) { idx -> buildCard(items[idx], spec) }
        }

        updatePaginationControls()
    }

    private fun renderEmptyState(message: String) {
        val tv = AppCompatTextView(this).apply {
            text = message
            setTextColor(inkBlackColor)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        binding.gridContainer.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )
    }

    /** Lays out [count] cards in a centered grid, calling [buildCard] for each cell. */
    private fun renderRows(count: Int, spec: GridSpec, buildCard: (Int) -> View) {
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        val rowCount = (count + spec.cols - 1) / spec.cols
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
                if (itemIdx < count) {
                    row.addView(buildCard(itemIdx))
                } else {
                    val totalCellHeight = spec.cardHeightPx + spec.rowGapPx + spec.labelHeightPx
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.cardWidthPx, totalCellHeight))
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

    // ── Card builders ──────────────────────────────────────────────────────────

    private fun buildCard(item: BrowseItem, spec: GridSpec): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setOnClickListener {
                when (item) {
                    is BrowseItem.Folder   -> navigateIntoFolder(item.entity)
                    is BrowseItem.Template -> openTemplatePreview(item.entity)
                }
            }
        }

        // Long-press management actions — only in MODE_MANAGE (not MODE_PICK per spec).
        if (mode == MODE_MANAGE) {
            group.setOnLongClickListener {
                when (item) {
                    is BrowseItem.Folder   -> showTemplateFolderContextMenu(item.entity)
                    is BrowseItem.Template -> showTemplateContextMenu(item.entity)
                }
                true
            }
        }

        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
        }
        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        card.setPadding(pad1dp, pad1dp, pad1dp, pad1dp)
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        val iconSize = (minOf(spec.cardWidthPx, spec.cardHeightPx) * 0.45f).toInt()

        when (item) {
            is BrowseItem.Folder -> {
                card.addView(
                    AppCompatImageView(this).apply {
                        setImageResource(R.drawable.ic_folder)
                    },
                    FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                )
            }
            is BrowseItem.Template -> {
                val thumbImage = AppCompatImageView(this).apply {
                    scaleType  = ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE
                }
                val fallbackIcon = AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_template)
                }
                card.addView(thumbImage, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                card.addView(fallbackIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
                loadThumbnailInto(item.entity, thumbImage, fallbackIcon)
            }
        }

        val label = AppCompatTextView(this).apply {
            text      = item.entity.name
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
            textSize  = 14f
            setTextColor(inkBlackColor)
        }
        group.addView(label, LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
            it.topMargin = spec.rowGapPx
        })

        return group
    }

    // ── Thumbnail decode ──────────────────────────────────────────────────────

    private fun loadThumbnailInto(
        entity: ObjectEntity,
        image: AppCompatImageView,
        fallbackIcon: View,
    ) {
        val cacheKey = "${entity.id}:${entity.updatedAt}"
        val cached = thumbnailCache[cacheKey]
        if (cached != null) {
            image.setImageBitmap(cached)
            image.visibility = View.VISIBLE
            fallbackIcon.visibility = View.GONE
            return
        }

        val job = lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    val tObj = TemplateObject.fromJson(entity.data) ?: return@withContext null
                    if (tObj.image.isEmpty()) return@withContext null
                    val bytes = Base64.decode(tObj.image, Base64.DEFAULT)
                    // Decode bounds first, then sample.
                    val boundsOpts = Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                    val sample = computeThumbnailSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, THUMB_PX)
                    val decodeOpts = Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                } catch (e: Exception) {
                    Slog.d(TAG) { "loadThumbnailInto: decode failed for ${entity.id}: ${e.message}" }
                    null
                }
            }
            if (bitmap != null) {
                thumbnailCache[cacheKey] = bitmap
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                fallbackIcon.visibility = View.GONE
            }
        }
        decodeJobs.add(job)
    }

    /**
     * Mirrors TemplateDialog.computeInSampleSize: power-of-two downsampling so neither dimension
     * greatly exceeds [maxPx]. Capped at 4 to avoid thin template lines going sub-pixel.
     */
    private fun computeThumbnailSampleSize(w: Int, h: Int, maxPx: Int): Int {
        var sample = 1
        while (maxOf(w, h) / sample > maxPx) sample *= 2
        return sample.coerceIn(1, MAX_THUMBNAIL_SAMPLE)
    }

    // ── Breadcrumb bar ────────────────────────────────────────────────────────

    private fun buildBreadcrumbs() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        binding.btnBreadcrumbBack.isEnabled = directoryStack.size > 1

        val density = resources.displayMetrics.density
        val padH    = (8 * density).toInt()
        val sepPad  = (4 * density).toInt()

        directoryStack.forEachIndexed { index, entry ->
            if (index > 0) {
                container.addView(AppCompatTextView(this).apply {
                    text = "›"
                    setTextColor(inkLightColor)
                    textSize = 16f
                    setPadding(sepPad, 0, sepPad, 0)
                })
            }
            val label = if (index == 0) "Templates" else entry?.name ?: "Templates"
            container.addView(AppCompatTextView(this).apply {
                text = label
                setTextColor(inkBlackColor)
                textSize = 16f
                setPadding(padH, 0, padH, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    while (directoryStack.size > index + 1) directoryStack.removeLast()
                    currentGridPage = 0
                    loadAndRender()
                }
            })
        }

        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    // ── Template preview (full-screen) ────────────────────────────────────────

    private fun openTemplatePreview(entity: ObjectEntity) {
        val previewLayout = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@TemplateBrowserActivity, R.color.paperWhite))
        }

        val imageView = AppCompatImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(1, 1, 1, 1)
            setBackgroundResource(R.drawable.shape_bordered)
        }
        previewLayout.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).also {
            val density = resources.displayMetrics.density
            val margin  = (16 * density).toInt()
            it.topMargin    = margin + (56 * density).toInt() // leave room for close button
            it.bottomMargin = margin
            it.leftMargin   = margin
            it.rightMargin  = margin
        })

        // Close button — top-left, uses toolbar style.
        val closeBtn = androidx.appcompat.widget.AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_left)
            contentDescription = "Close preview"
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
        }
        val density = resources.displayMetrics.density
        val closeLp = FrameLayout.LayoutParams(
            (48 * density).toInt(),
            (48 * density).toInt(),
            Gravity.TOP or Gravity.START,
        ).apply {
            topMargin   = (4 * density).toInt()
            leftMargin  = (4 * density).toInt()
        }
        previewLayout.addView(closeBtn, closeLp)

        val previewDialog = AlertDialog.Builder(this)
            .setView(previewLayout)
            .create()

        closeBtn.setOnClickListener { previewDialog.dismiss() }
        // Tap outside also dismisses.
        previewLayout.setOnClickListener { previewDialog.dismiss() }

        previewDialog.show()
        previewDialog.window?.setElevation(0f)
        previewDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        previewDialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        // Decode the full-resolution image off-thread.
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    val tObj = TemplateObject.fromJson(entity.data) ?: return@withContext null
                    if (tObj.image.isEmpty()) return@withContext null
                    val bytes = Base64.decode(tObj.image, Base64.DEFAULT)
                    BitmapDecode.decodeSampled(bytes, BitmapDecode.MAX_DIMENSION, BitmapDecode.MAX_DIMENSION)
                } catch (e: Exception) {
                    Slog.d(TAG) { "openTemplatePreview: decode failed for ${entity.id}: ${e.message}" }
                    null
                }
            }
            if (!previewDialog.isShowing) return@launch
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_template)
            }
        }
    }

    // ── Template context menu ─────────────────────────────────────────────────

    private fun showTemplateContextMenu(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title(entity.name)
            .addAction(R.drawable.ic_copy_plus,       "Copy Template") { enterPicker(TemplatePicker.CopyTemplate(entity)) }
            .addAction(R.drawable.ic_move_page,       "Move Template") { enterPicker(TemplatePicker.MoveTemplate(entity)) }
            .addAction(R.drawable.ic_edit,            "Rename Template") { showRenameTemplateDialog(entity) }
            .addAction(R.drawable.ic_delete_notebook, "Delete Template") { showDeleteTemplateConfirmation(entity) }
            .show()
    }

    // ── Template folder context menu ──────────────────────────────────────────

    private fun showTemplateFolderContextMenu(entity: ObjectEntity) {
        ActionSheetDialog(this)
            .title(entity.name)
            .addAction(R.drawable.ic_copy_plus,    "Copy Folder")   { enterPicker(TemplatePicker.CopyFolder(entity)) }
            .addAction(R.drawable.ic_move_page,    "Move Folder")   { enterPicker(TemplatePicker.MoveFolder(entity)) }
            .addAction(R.drawable.ic_edit,         "Rename Folder") { showRenameTemplateFolderDialog(entity) }
            .addAction(R.drawable.ic_folder_minus, "Delete")        { showDeleteTemplateFolderConfirmation(entity) }
            .show()
    }

    // ── Rename template dialog ────────────────────────────────────────────────

    private fun showRenameTemplateDialog(entity: ObjectEntity) {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.setText(entity.name)
        dialogBinding.editNotebookName.setSelection(entity.name.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Template")
            .setView(dialogBinding.root)
            .setPositiveButton("Rename") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    val error = validateTemplateRename(name, entity)
                    if (error != null) {
                        Toast.makeText(this@TemplateBrowserActivity, error, Toast.LENGTH_SHORT).show()
                    } else if (name != entity.name) {
                        withContext(Dispatchers.IO) { repository.renameTemplate(entity.id, name) }
                        loadAndRender()
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

    // ── Rename template folder dialog ──────────────────────────────────────────

    private fun showRenameTemplateFolderDialog(entity: ObjectEntity) {
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
                    val error = validateTemplateFolderRename(name, entity)
                    if (error != null) {
                        Toast.makeText(this@TemplateBrowserActivity, error, Toast.LENGTH_SHORT).show()
                    } else if (name != entity.name) {
                        withContext(Dispatchers.IO) { repository.renameTemplateFolder(entity.id, name) }
                        loadAndRender()
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

    // ── Delete template confirmation ───────────────────────────────────────────

    private fun showDeleteTemplateConfirmation(entity: ObjectEntity) {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete \"${entity.name}\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.softDeleteTemplate(entity.id) }
                    loadAndRender()
                }
            }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    // ── Delete template folder confirmation ───────────────────────────────────

    private fun showDeleteTemplateFolderConfirmation(entity: ObjectEntity) {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete \"${entity.name}\"? This will permanently remove all template folders and templates inside it. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.deleteTemplateFolderRecursively(entity.id) }
                    loadAndRender()
                }
            }
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    // ── New template folder dialog ────────────────────────────────────────────

    private fun showNewTemplateFolderDialog() {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        dialogBinding.editNotebookName.setText("")
        dialogBinding.editNotebookName.hint = "Folder name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("New Template Folder")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editNotebookName.windowToken, 0)
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    val error = validateTemplateFolderName(name)
                    if (error != null) {
                        Toast.makeText(this@TemplateBrowserActivity, error, Toast.LENGTH_SHORT).show()
                    } else {
                        val entity = withContext(Dispatchers.IO) {
                            repository.createTemplateFolder(name, currentParentId)
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
     * Validates a proposed template folder name: non-empty, not `.`/`..`, character whitelist,
     * no duplicate among siblings in the current directory. Returns an error string or null.
     */
    private suspend fun validateTemplateFolderName(name: String): String? {
        if (name.isBlank()) return "Folder name cannot be empty"
        if (name == "." || name == "..") return "Invalid folder name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        val siblings = withContext(Dispatchers.IO) { repository.getTemplateFolders(currentParentId) }
        if (siblings.any { it.name.equals(name, ignoreCase = true) }) {
            return "A folder named \"$name\" already exists"
        }
        return null
    }

    /**
     * Validates a template rename. Dup-check against the template's own parent (not current nav),
     * excluding self. Returns an error string or null.
     */
    private suspend fun validateTemplateRename(name: String, entity: ObjectEntity): String? {
        if (name.isBlank()) return "Template name cannot be empty"
        if (name == "." || name == "..") return "Invalid template name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        val siblings = withContext(Dispatchers.IO) { repository.getTemplates(entity.parentId) }
        if (siblings.any { it.id != entity.id && it.name.equals(name, ignoreCase = true) }) {
            return "A template named \"$name\" already exists"
        }
        return null
    }

    /**
     * Validates a template folder rename. Dup-check against the folder's own parent, excluding self.
     * Returns an error string or null.
     */
    private suspend fun validateTemplateFolderRename(name: String, entity: ObjectEntity): String? {
        if (name.isBlank()) return "Folder name cannot be empty"
        if (name == "." || name == "..") return "Invalid folder name"
        if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]"))) {
            return "Name may only contain letters, numbers, spaces, and _ - ."
        }
        val siblings = withContext(Dispatchers.IO) { repository.getTemplateFolders(entity.parentId) }
        if (siblings.any { it.id != entity.id && it.name.equals(name, ignoreCase = true) }) {
            return "A folder named \"$name\" already exists"
        }
        return null
    }

    // ── PNG import ────────────────────────────────────────────────────────────

    private fun performImport(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 1. Read display name for sanitization.
                    val displayName = queryDisplayName(uri) ?: "template"
                    val rawName = sanitizeTemplateNameFromFile(displayName)

                    // 2. Read bytes.
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Cannot open URI")

                    // 3. Decode to get width/height.
                    val boundsOpts = Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                    val w = boundsOpts.outWidth
                    val h = boundsOpts.outHeight
                    if (w <= 0 || h <= 0) throw IllegalStateException("Not a valid image")

                    // 4. Base64-encode NO_WRAP.
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                    // 5. Uniqueness check — append (2), (3), … if needed.
                    val siblings = repository.getTemplates(currentParentId)
                    val finalName = makeUniqueName(rawName, siblings.map { it.name })

                    // 6. Persist to index.
                    repository.createTemplate(finalName, currentParentId, w, h, base64)

                    Slog.d(TAG) { "performImport: imported '$finalName' (${w}x${h})" }
                } catch (e: Exception) {
                    Slog.d(TAG) { "performImport: failed: ${e.message}" }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@TemplateBrowserActivity,
                            "Could not import image. Make sure it is a valid PNG.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@withContext
                }
            }
            // Re-render after successful import.
            loadAndRender()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }

    /**
     * Local copy of the sanitization defense from NotebookActivity.sanitizeTemplateFileName,
     * adapted to return just the name stem (no extension) for use as the template name.
     *
     * Per §2 rules: strip directory components, apply character whitelist [a-zA-Z0-9_\-. ],
     * reject `.`/`..`, drop extension. Each browser instance maintains its own copy so
     * NotebookActivity's copy can be removed separately in Session 5.
     */
    private fun sanitizeTemplateNameFromFile(rawName: String): String {
        val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')
        val stem = baseName.substringBeforeLast('.', baseName)
            .replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")
            .trim()
        return if (stem.isBlank() || stem == "." || stem == "..") "template" else stem
    }

    /**
     * Returns a name that is not already in [existingNames] (case-insensitive).
     * If [name] is unique, returns it as-is. Otherwise appends " (2)", " (3)", … until unique.
     */
    private fun makeUniqueName(name: String, existingNames: List<String>): String {
        if (existingNames.none { it.equals(name, ignoreCase = true) }) return name
        var n = 2
        while (existingNames.any { it.equals("$name ($n)", ignoreCase = true) }) n++
        return "$name ($n)"
    }
}
