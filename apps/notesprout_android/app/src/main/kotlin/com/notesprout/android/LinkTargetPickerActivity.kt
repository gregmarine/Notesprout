package com.notesprout.android

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.soilFile
import com.notesprout.android.databinding.ActivityLinkTargetPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Full-screen link-target picker (Session 2 — Current-notebook path only).
 *
 * Models [PageIndexActivity]'s thumbnail grid, but a tap on a page card **returns a link target**
 * (the chosen [LinkChrome] + the tapped page id) instead of navigating. The header carries a chrome
 * segmented control and a This-notebook / Other-notebook toggle; the Other half is stubbed/disabled
 * until Session 3. Used both to create a link (no initial selection) and to edit one
 * ([EXTRA_INITIAL_CHROME] / [EXTRA_INITIAL_PAGE_ID] pre-select the chrome + highlight the page).
 */
class LinkTargetPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID        = "notebook_id"
        const val EXTRA_NOTEBOOK_NAME      = "notebook_name"
        const val EXTRA_CURRENT_PAGE_INDEX = "current_page_index"
        /** Pre-selected chrome (a [LinkChrome] name) when editing; defaults to NONE. */
        const val EXTRA_INITIAL_CHROME     = "initial_chrome"
        /** Pre-selected current-notebook page id when editing a same-notebook page link. */
        const val EXTRA_INITIAL_PAGE_ID    = "initial_page_id"

        /** Result: chosen chrome (a [LinkChrome] name). */
        const val EXTRA_RESULT_CHROME      = "result_chrome"
        /** Result: chosen current-notebook page id (Session 2 always returns a current-notebook page). */
        const val EXTRA_RESULT_PAGE_ID     = "result_page_id"
    }

    // ── Grid specification (mirrors PageIndexActivity) ─────────────────────────

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

    private data class PageEntry(val id: String, val pageNumber: Int, val snapshot: String?)

    @Serializable
    private data class PageSnapshot(val snapshot: String? = null)

    private val pageCodec = Json { ignoreUnknownKeys = true }

    // ── State ─────────────────────────────────────────────────────────────────

    private var notebookId: String = ""
    private var notebookSoilPath: String? = null

    private lateinit var binding: ActivityLinkTargetPickerBinding
    private var pages: List<PageEntry> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentGridPage: Int = 0
    private var gridSpec: GridSpec? = null

    private var selectedChrome: LinkChrome = LinkChrome.NONE
    /** Page id to highlight (the link's current target) when editing; null when creating. */
    private var initialPageId: String? = null

    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }

    private val snapshotDecodeJobs = mutableListOf<Job>()

    // ── Swipe gesture (left/right to paginate) ────────────────────────────────

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false
                return if (velocityX < 0) {
                    navigateGridPage(currentGridPage + 1); true
                } else {
                    navigateGridPage(currentGridPage - 1); true
                }
            }
        })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityLinkTargetPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notebookId       = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: ""
        notebookSoilPath = if (notebookId.isNotEmpty()) soilFile(this, notebookId).absolutePath else null
        currentPageIndex = intent.getIntExtra(EXTRA_CURRENT_PAGE_INDEX, 0)
        initialPageId    = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID)
        selectedChrome   = intent.getStringExtra(EXTRA_INITIAL_CHROME)
            ?.let { runCatching { LinkChrome.valueOf(it) }.getOrNull() } ?: LinkChrome.NONE

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        binding.btnChromeNone.setOnClickListener      { selectedChrome = LinkChrome.NONE; refreshChromeButtons() }
        binding.btnChromeUnderline.setOnClickListener { selectedChrome = LinkChrome.UNDERLINE; refreshChromeButtons() }
        binding.btnChromeBox.setOnClickListener       { selectedChrome = LinkChrome.DOTTED_CHEVRON; refreshChromeButtons() }
        refreshChromeButtons()

        // Target toggle: only This-notebook is live in Session 2 (Other is built in Session 3).
        binding.btnTargetCurrent.isSelected = true

        binding.btnFirstPage.setOnClickListener { navigateGridPage(0) }
        binding.btnPrevPage.setOnClickListener  { navigateGridPage(currentGridPage - 1) }
        binding.btnNextPage.setOnClickListener  { navigateGridPage(currentGridPage + 1) }
        binding.btnLastPage.setOnClickListener  { navigateGridPage(totalGridPages() - 1) }

        binding.gridContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.gridContainer.width
                    val h = binding.gridContainer.height
                    if (w <= 0 || h <= 0) return
                    binding.gridContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    gridSpec = computeGridSpec(w, h)
                    if (pages.isNotEmpty()) renderGridPage()
                }
            }
        )

        loadPagesAsync()
    }

    private fun refreshChromeButtons() {
        binding.btnChromeNone.isSelected      = selectedChrome == LinkChrome.NONE
        binding.btnChromeUnderline.isSelected = selectedChrome == LinkChrome.UNDERLINE
        binding.btnChromeBox.isSelected       = selectedChrome == LinkChrome.DOTTED_CHEVRON
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadPagesAsync() {
        val path = notebookSoilPath ?: return
        lifecycleScope.launch {
            pages = withContext(Dispatchers.IO) { loadPagesFromSoil(path) }
            val spec = gridSpec
            if (spec != null && spec.itemsPerPage > 0) {
                // Open to the grid page containing the link's current target (edit) or the open page.
                val anchorIdx = initialPageId?.let { id -> pages.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 } ?: currentPageIndex
                currentGridPage = anchorIdx / spec.itemsPerPage
                renderGridPage()
            }
        }
    }

    private fun loadPagesFromSoil(path: String): List<PageEntry> {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            db.rawQuery(
                "SELECT id, data FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
                null
            ).use { c ->
                val result = mutableListOf<PageEntry>()
                var number = 1
                while (c.moveToNext()) {
                    val id       = c.getString(0)
                    val dataJson = c.getString(1)
                    val snapshot = try {
                        pageCodec.decodeFromString<PageSnapshot>(dataJson).snapshot
                    } catch (_: Exception) { null }
                    result.add(PageEntry(id, number++, snapshot))
                }
                result
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            db?.close()
        }
    }

    // ── Grid specification ────────────────────────────────────────────────────

    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val density       = resources.displayMetrics.density
        val gutterPx      = (12 * density).toInt()
        val paddingHPx    = (16 * density).toInt()
        val paddingVPx    = (16 * density).toInt()
        val rowGapPx      = (6  * density).toInt()
        val labelHeightPx = (32 * density).toInt()

        val screenWidthDp = availableWidth / density
        val cols = if (screenWidthDp >= 480f) 3 else 2

        val dm          = resources.displayMetrics
        val aspectRatio = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)

        val innerWidth  = availableWidth  - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx

        val cardWidth  = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx

        val rows = ((innerHeight + gutterPx) / (cellHeight + gutterPx)).coerceAtLeast(1)

        return GridSpec(cols, rows, cardWidth, cardHeight, gutterPx, rowGapPx, labelHeightPx, paddingHPx, paddingVPx)
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun totalGridPages(): Int {
        val perPage = gridSpec?.itemsPerPage ?: return 1
        if (perPage == 0 || pages.isEmpty()) return 1
        return (pages.size + perPage - 1) / perPage
    }

    private fun renderGridPage() {
        snapshotDecodeJobs.forEach { it.cancel() }
        snapshotDecodeJobs.clear()

        val spec = gridSpec ?: return
        binding.gridContainer.removeAllViews()

        if (pages.isEmpty()) {
            renderEmptyState()
            updatePaginationControls()
            return
        }

        val start     = currentGridPage * spec.itemsPerPage
        val end       = minOf(start + spec.itemsPerPage, pages.size)
        val pageItems = pages.subList(start, end)

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

        updatePaginationControls()
    }

    private fun renderEmptyState() {
        val tv = AppCompatTextView(this).apply {
            text = "No pages found."
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

    /**
     * One card group: bordered snapshot card + "Page N" label. The card whose id matches
     * [initialPageId] (the link's current target, when editing) is highlighted. A tap returns the
     * chosen chrome + this page id as the link target.
     */
    private fun buildCardGroup(entry: PageEntry, spec: GridSpec): LinearLayout {
        val highlighted = entry.id == initialPageId

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setOnClickListener { finishWithTarget(entry.id) }
        }

        val card = FrameLayout(this).apply {
            setBackgroundResource(
                if (highlighted) R.drawable.bg_page_card_current else R.drawable.shape_bordered
            )
        }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))

        val density = resources.displayMetrics.density
        val pad1dp  = (density + 0.5f).toInt()
        val padPx   = if (highlighted) (3 * density + 0.5f).toInt() else pad1dp
        card.setPadding(padPx, padPx, padPx, padPx)

        val snapshotImage = AppCompatImageView(this).apply {
            scaleType  = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        card.addView(snapshotImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val label = AppCompatTextView(this).apply {
            text      = "Page ${entry.pageNumber}"
            gravity   = Gravity.CENTER
            textSize  = 14f
            setTextColor(inkBlackColor)
        }
        group.addView(label, LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also {
            it.topMargin = spec.rowGapPx
        })

        if (!entry.snapshot.isNullOrEmpty()) {
            val job = lifecycleScope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(entry.snapshot, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    snapshotImage.setImageBitmap(bitmap)
                    snapshotImage.visibility = View.VISIBLE
                }
            }
            snapshotDecodeJobs.add(job)
        }

        return group
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigateGridPage(page: Int) {
        val clamped = page.coerceIn(0, (totalGridPages() - 1).coerceAtLeast(0))
        if (clamped == currentGridPage) return
        currentGridPage = clamped
        renderGridPage()
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

    // ── Result ────────────────────────────────────────────────────────────────

    private fun finishWithTarget(pageId: String) {
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_CHROME, selectedChrome.name)
            putExtra(EXTRA_RESULT_PAGE_ID, pageId)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
