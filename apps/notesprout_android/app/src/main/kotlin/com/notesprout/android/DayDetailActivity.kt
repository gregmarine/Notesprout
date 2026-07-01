package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.ViewTreeObserver
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.CalendarRepository
import com.notesprout.android.data.DayHistoryRepository
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.PageData
import com.notesprout.android.data.ScratchpadPageContent
import com.notesprout.android.data.ShapeObject
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.ShapeType
import com.notesprout.android.data.StickyNoteObject
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TYPE_SHAPE
import com.notesprout.android.data.TYPE_STICKY_NOTE
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.TemplateData
import com.notesprout.android.data.index.CalendarEntity
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TemplateObject as IndexTemplateObject
import com.notesprout.android.data.recents.ResolvedRecent
import com.notesprout.android.data.toBoundingBoxJson
import com.notesprout.android.data.toLinkObject
import com.notesprout.android.data.toStickyNoteObject
import com.notesprout.android.data.translate
import com.notesprout.android.databinding.ActivityDayDetailBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.STICKY_NOTE_ICON_SIZE_DP
import com.notesprout.android.notebook.ShapeRecognizer
import com.notesprout.android.notebook.ToolPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A full-screen, single-page handwriting canvas "into" one calendar day, opened by single-finger
 * double-tap on a day in [CalendarActivity]'s Month / Week view. Behaves like the scratch pad: one
 * page, freely writable, with an optional ruling template. Content is stored in the `calendar`
 * table (plaintext) under the key `cal-daynote-YYYY-MM-DD`, sharing the universal object model +
 * clipboard with notebooks, the scratch pad, sticky notes & shapes.
 *
 * Canvas machinery (drawing callbacks, save, per-page undo/redo, lasso, sticky notes, shape
 * convert/transform, snapshot) is ported from [CalendarActivity]; period navigation is dropped, and
 * a template picker + shape-insert secondary toolbar + Send-to-Notebook are added.
 */
class DayDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DATE                    = "day_detail_date"
        const val EXTRA_FROM_NOTEBOOK_ID        = "from_notebook_id"
        const val EXTRA_FROM_NOTEBOOK_NAME      = "from_notebook_name"
        const val EXTRA_FROM_NOTEBOOK_ENCRYPTED = "from_notebook_encrypted"

        fun intent(
            context: Context, date: LocalDate,
            fromNotebookId: String?, fromNotebookName: String?, fromNotebookEncrypted: Boolean,
        ): Intent = Intent(context, DayDetailActivity::class.java)
            .putExtra(EXTRA_DATE, date.toString())
            .putExtra(EXTRA_FROM_NOTEBOOK_ID, fromNotebookId)
            .putExtra(EXTRA_FROM_NOTEBOOK_NAME, fromNotebookName)
            .putExtra(EXTRA_FROM_NOTEBOOK_ENCRYPTED, fromNotebookEncrypted)
    }

    private lateinit var binding: ActivityDayDetailBinding
    private lateinit var drawingView: NotebookView
    private lateinit var repository: CalendarRepository
    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }
    private val dayHistoryRepo: DayHistoryRepository by lazy { DayHistoryRepository() }

    private var selectedDate: LocalDate = LocalDate.now()

    // View mode — Note (editable canvas) / Notebooks (activity lists) / History (past years).
    private enum class ViewMode { NOTE, NOTEBOOKS, HISTORY }
    private enum class NbSub { OPENED, EDITED, CREATED }
    private enum class HistSub { NOTES, OPENED, EDITED, CREATED }
    private var viewMode = ViewMode.NOTE
    private var notebooksSub = NbSub.OPENED
    private var historySub = HistSub.NOTES
    private var historyYear = LocalDate.now().year - 1
    // Years (descending) with any data for this month/day — drives the History year stepper.
    private var historyYears: List<Int> = emptyList()
    private var historyYearsLoaded = false
    // Bumped per History▸Notes load so a slow render can't paint into a since-changed view.
    private var historyNoteToken = 0

    // Notebooks card-grid state (Notebooks mode; History Opened/Edited/Created reuse in Session 4).
    private var listCards: List<ResolvedRecent> = emptyList()
    private var listPage = 0
    private var gridSpec: GridSpec? = null
    // Bumped on each fresh load so a slow query can't render into a since-changed grid.
    private var listRenderToken = 0
    // Cover-load coroutines, cancelled on each grid re-render (mirrors MainActivity).
    private val coverLoadJobs = mutableListOf<Job>()
    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    /** Card grid layout for the Notebooks list — mirrors MainActivity's notebook grid. */
    private data class GridSpec(
        val cols: Int, val rows: Int,
        val cardWidthPx: Int, val cardHeightPx: Int,
        val gutterPx: Int, val rowGapPx: Int, val labelHeightPx: Int,
        val paddingHPx: Int, val paddingVPx: Int,
    ) {
        val itemsPerPage: Int get() = cols * rows
    }

    // Canvas / page state
    private var currentPageId = ""
    private var currentLayerId = ""
    private var currentTemplateId = ""
    // Thread-safe: mutated from main-thread coroutines (load/erase) and IO save coroutines.
    private val persistedStrokeIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Serializes saveStrokes() so concurrent callers can't race the persistedStrokeIds
    // read-modify-write and double-insert / drop strokes.
    private val saveMutex = Mutex()
    private var currentTemplateBitmap: Bitmap? = null

    // Tool state
    private var isEraserActive = false
    private var isLassoEraserActive = false
    private var isLassoMode = false
    private var isSmartLassoSession = false
    private var isShapeTransformMode = false
    private val selectedObjectIds = mutableSetOf<String>()

    private var shapeConvertJob: Job? = null
    private var shapeConvertStroke: LiveStroke? = null
    private var shapeConvertResult: ShapeRecognizer.Result? = null

    // Source notebook (non-null only when the calendar was launched from a notebook)
    private var fromNotebookId: String? = null
    private var fromNotebookName: String? = null
    private var fromNotebookEncrypted = false

    // Undo/redo — full-layer snapshot history (single page)
    private var currentSnapshot: List<CalendarEntity> = emptyList()
    private val undoStack = ArrayDeque<List<CalendarEntity>>()
    private val redoStack = ArrayDeque<List<CalendarEntity>>()
    private val historyCap = 50

    // Send-to-notebook hand-off content awaiting a picker result
    private var pendingSendContent: NotesproutClipboard.ClipboardContent? = null

    // Sticky note editor state
    private var pendingStickyNote: StickyNoteRender? = null
    private var pendingStickyInitialCreate = false

    // Multi-finger double-tap: 2-finger = undo, 3-finger = redo (mirrors CalendarActivity).
    private var mfTapPeakCount = 0
    private var mfTapArmed = false
    private var mfTapMoved = false
    private var mfTapDownTime = 0L
    private var mfTapCentroidStartX = 0f
    private var mfTapCentroidStartY = 0f
    private var twoFingerTapFirstTime = 0L
    private var twoFingerTapFirstX = 0f
    private var twoFingerTapFirstY = 0f
    private var threeFingerTapFirstTime = 0L
    private var threeFingerTapFirstX = 0f
    private var threeFingerTapFirstY = 0f

    // Sticky note finger-tap state
    private var stickyNoteTapCandidate: StickyNoteRender? = null
    private var stickyNoteTapDownX = 0f
    private var stickyNoteTapDownY = 0f
    private var stickyNoteTapDownTime = 0L
    private var stickyNoteTapMoved = false

    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val doubleTapSlopPx by lazy { ViewConfiguration.get(this).scaledDoubleTapSlop }
    private val density get() = resources.displayMetrics.density

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val out = StickyNoteEditorTransfer.output
        StickyNoteEditorTransfer.input  = null
        StickyNoteEditorTransfer.output = null
        val pending = pendingStickyNote ?: return@registerForActivityResult
        pendingStickyNote = null
        val wasInitialCreate = pendingStickyInitialCreate
        pendingStickyInitialCreate = false

        val afterRender = if (out != null) {
            StickyNoteRender(
                id = pending.id, boundingBox = pending.boundingBox,
                strokes = out.strokes, headings = out.headings, textObjects = out.textObjects,
                lines = out.lines, shapes = out.shapes,
                contentWidth = out.contentWidth, contentHeight = out.contentHeight,
            )
        } else pending

        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return@registerForActivityResult
        if (out != null) {
            val afterData  = afterRender.toStickyNoteObject(density).toJson()
            val beforeData = pending.toStickyNoteObject(density).toJson()
            if (afterData != beforeData) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotesproutIndex.calendarDao().updateData(pending.id, afterData, System.currentTimeMillis())
                    }
                    val updatedNotes = drawingView.getStickyNotes().map { if (it.id == afterRender.id) afterRender else it }
                    drawingView.loadStickyNotes(updatedNotes)
                    rebuildCanvas()
                    pushHistory()
                    if (wasInitialCreate) selectStickyNoteIcon(afterRender)
                }
                return@registerForActivityResult
            }
        }
        if (wasInitialCreate) selectStickyNoteIcon(afterRender)
    }

    private val notebookPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val content = pendingSendContent
        pendingSendContent = null
        if (result.resultCode != RESULT_OK || data == null || content == null) return@registerForActivityResult
        val destId   = data.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_ID) ?: return@registerForActivityResult
        val destName = data.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_NAME) ?: ""
        openNotebookWithPaste(destId, destName, content)
    }

    private val templatePickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val templateId = result.data?.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_ID) ?: return@registerForActivityResult
        onLibraryTemplatePicked(templateId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityDayDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = CalendarRepository(NotesproutIndex.db(), NotesproutIndex.calendarDao())

        fromNotebookId        = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_ID)
        fromNotebookName      = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_NAME)
        fromNotebookEncrypted = intent.getBooleanExtra(EXTRA_FROM_NOTEBOOK_ENCRYPTED, false)
        selectedDate = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE) ?: "") }
            .getOrDefault(LocalDate.now())

        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        binding.dayContent.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        binding.floatingSelectionToolbar.bringToFront()
        binding.dayShapeInsertToolbar.bringToFront()

        updateDateLabel()
        setupToolbar()
        setupViewToggles()
        setupNotebooksList()
        wireDrawingCallbacks()
        wireToolButtons()
        updateLassoButtonIcon()
        applyViewMode()

        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnDayPen.isSelected = false
                binding.btnDayEraser.isSelected = true
            }
            else -> {
                binding.btnDayPen.isSelected = true
                binding.btnDayEraser.isSelected = false
            }
        }

        binding.dayContent.doOnLayout {
            lifecycleScope.launch { repository.ensureBootstrap(); loadDayPage() }
        }
    }

    private fun pageKey(): String = "cal-daynote-$selectedDate"

    private fun updateDateLabel() { binding.tvDayDate.text = dayDateText() }

    private fun dayDateText(): String {
        val dow = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val monthName = Month.of(selectedDate.monthValue).getDisplayName(TextStyle.FULL, Locale.getDefault())
        return "$dow, ${selectedDate.dayOfMonth} $monthName ${selectedDate.year}"
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnDayBack.setOnClickListener { finish() }
        binding.btnDayScratchpad.setOnClickListener { startActivity(Intent(this, ScratchpadActivity::class.java)) }
        binding.btnDayUndo.setOnClickListener { undo() }
        binding.btnDayRedo.setOnClickListener { redo() }
        binding.btnDayTemplate.setOnClickListener { showTemplatePicker() }
    }

    // ── View modes (Note / Notebooks / History) ──────────────────────────────────

    private fun setupViewToggles() {
        binding.btnDayViewNote.setOnClickListener { switchViewMode(ViewMode.NOTE) }
        binding.btnDayViewNotebooks.setOnClickListener { switchViewMode(ViewMode.NOTEBOOKS) }
        binding.btnDayViewHistory.setOnClickListener { switchViewMode(ViewMode.HISTORY) }

        binding.btnDaySubOpened.setOnClickListener { notebooksSub = NbSub.OPENED; applyViewMode() }
        binding.btnDaySubEdited.setOnClickListener { notebooksSub = NbSub.EDITED; applyViewMode() }
        binding.btnDaySubCreated.setOnClickListener { notebooksSub = NbSub.CREATED; applyViewMode() }

        binding.btnDayHistNotes.setOnClickListener { historySub = HistSub.NOTES; applyViewMode() }
        binding.btnDayHistOpened.setOnClickListener { historySub = HistSub.OPENED; applyViewMode() }
        binding.btnDayHistEdited.setOnClickListener { historySub = HistSub.EDITED; applyViewMode() }
        binding.btnDayHistCreated.setOnClickListener { historySub = HistSub.CREATED; applyViewMode() }

        // Stepper walks only years-with-data (◀ older, ▶ newer); no-op at the ends.
        binding.btnDayYearPrev.setOnClickListener { stepHistoryYear(older = true) }
        binding.btnDayYearNext.setOnClickListener { stepHistoryYear(older = false) }
    }

    private fun switchViewMode(mode: ViewMode) {
        if (mode == viewMode) return
        // Leaving Note mode: settle the canvas and flush ink so nothing is lost.
        if (viewMode == ViewMode.NOTE) {
            if (isShapeTransformMode) exitShapeTransformMode(clearSelection = true)
            if (isLassoMode) exitLassoMode()
            hideShapeInsertToolbar()
            hideFloatingSelectionToolbar()
            NotesproutApplication.appScope.launch { saveStrokes() }
        }
        viewMode = mode
        // History picker is keyed to this day's month/day (static for the activity) — load once,
        // choosing the default year, then paint.
        if (mode == ViewMode.HISTORY && !historyYearsLoaded) loadHistoryYearsThenApply()
        else applyViewMode()
    }

    /** Load years-with-data for this month/day once, pick the default year, then repaint. */
    private fun loadHistoryYearsThenApply() {
        lifecycleScope.launch {
            val years = withContext(Dispatchers.IO) {
                dayHistoryRepo.yearsWithData(selectedDate.monthValue, selectedDate.dayOfMonth)
            }
            historyYears = years
            historyYearsLoaded = true
            historyYear = pickDefaultHistoryYear(years)
            applyViewMode()
        }
    }

    /** Default = current−1 if it has data, else the newest year-with-data ≤ current−1, else newest. */
    private fun pickDefaultHistoryYear(years: List<Int>): Int {
        val target = LocalDate.now().year - 1
        return when {
            years.isEmpty() -> target
            target in years -> target
            else -> years.firstOrNull { it <= target } ?: years.first()
        }
    }

    /** Step to the adjacent year-with-data. [older] = smaller year (◀); else larger (▶). */
    private fun stepHistoryYear(older: Boolean) {
        if (historyYears.isEmpty()) return
        val idx = historyYears.indexOf(historyYear).takeIf { it >= 0 } ?: 0
        // historyYears is descending: idx 0 = newest, so older → +1, newer → −1.
        val newIdx = (idx + if (older) 1 else -1).coerceIn(0, historyYears.lastIndex)
        if (newIdx == idx) return
        historyYear = historyYears[newIdx]
        applyViewMode()
    }

    /** Reflect [viewMode] + sub-state into toolbar visibility, content swap, and toggle chrome. */
    private fun applyViewMode() {
        val isNote = viewMode == ViewMode.NOTE
        val isHistoryNotes = viewMode == ViewMode.HISTORY && historySub == HistSub.NOTES

        // Drawing tools live only in Note mode.
        binding.dayToolsGroup.isVisible = isNote
        binding.dayToolsDivider.isVisible = isNote

        // Editable canvas only in Note mode; the list / read-only note surfaces are set per branch below.
        drawingView.asView().isVisible = isNote

        // Secondary control bar.
        binding.daySubBarContainer.isVisible = !isNote
        binding.daySubNotebooks.isVisible = viewMode == ViewMode.NOTEBOOKS
        binding.daySubHistory.isVisible = viewMode == ViewMode.HISTORY
        binding.tvDayYear.text = if (historyYearsLoaded && historyYears.isEmpty()) "—" else historyYear.toString()

        // Toggle chrome.
        binding.btnDayViewNote.isSelected = viewMode == ViewMode.NOTE
        binding.btnDayViewNotebooks.isSelected = viewMode == ViewMode.NOTEBOOKS
        binding.btnDayViewHistory.isSelected = viewMode == ViewMode.HISTORY
        binding.btnDaySubOpened.isSelected = notebooksSub == NbSub.OPENED
        binding.btnDaySubEdited.isSelected = notebooksSub == NbSub.EDITED
        binding.btnDaySubCreated.isSelected = notebooksSub == NbSub.CREATED
        binding.btnDayHistNotes.isSelected = historySub == HistSub.NOTES
        binding.btnDayHistOpened.isSelected = historySub == HistSub.OPENED
        binding.btnDayHistEdited.isSelected = historySub == HistSub.EDITED
        binding.btnDayHistCreated.isSelected = historySub == HistSub.CREATED

        // Content swap: Note → canvas; History▸Notes → read-only bitmap; everything else → card grid.
        when {
            isNote -> {
                binding.dayListContainer.isVisible = false
                binding.historyNoteImage.isVisible = false
            }
            isHistoryNotes -> renderHistoryNote()
            else -> {
                binding.historyNoteImage.isVisible = false
                binding.dayListContainer.isVisible = true
                binding.dayListPanel.isVisible = true
                binding.tvDayPlaceholder.isVisible = false
                renderList(resetPage = true)
            }
        }
        drawingView.releaseRender()
    }

    /** [historyYear] applied to the selected month/day; null when invalid (e.g. Feb 29 non-leap). */
    private fun historyDate(): LocalDate? =
        runCatching { LocalDate.of(historyYear, selectedDate.monthValue, selectedDate.dayOfMonth) }.getOrNull()

    // ── History ▸ Notes (read-only day-note bitmap from a past year) ───────────────

    /**
     * Render the chosen year's `cal-daynote-…` page as a static bitmap into [historyNoteImage].
     * While it loads (or when absent / invalid) a centered empty message shows via the list
     * container's placeholder. A [historyNoteToken] guards against a slow render painting after
     * the year/sub toggle moved on.
     */
    private fun renderHistoryNote() {
        val token = ++historyNoteToken
        binding.historyNoteImage.setImageDrawable(null)
        binding.historyNoteImage.isVisible = false
        binding.dayListPanel.isVisible = false
        binding.dayListContainer.isVisible = true
        binding.tvDayPlaceholder.isVisible = true
        binding.tvDayPlaceholder.text = ""

        val date = historyDate()
        if (date == null) { binding.tvDayPlaceholder.text = emptyNoteMessage(); return }
        val containerW = binding.dayContent.width
        val containerH = binding.dayContent.height
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { renderDayNoteBitmap(date, containerW, containerH) }
            if (token != historyNoteToken) { bitmap?.recycle(); return@launch }
            if (bitmap != null) {
                binding.historyNoteImage.setImageBitmap(bitmap)
                binding.historyNoteImage.isVisible = true
                binding.dayListContainer.isVisible = false
            } else {
                binding.tvDayPlaceholder.text = emptyNoteMessage()
            }
        }
    }

    /** Compose the day note for [date] (template + content) to a bitmap, or null if it has none. */
    private suspend fun renderDayNoteBitmap(date: LocalDate, containerW: Int, containerH: Int): Bitmap? {
        val pageId = dayHistoryRepo.dayNotePageId(date) ?: return null
        val dao = NotesproutIndex.calendarDao()
        val pageRow = dao.getObjectById(pageId) ?: return null
        val pd = PageData.fromJson(pageRow.data)
        // Prefer the page's authored size; fall back to the current container.
        val w = pd.width.toInt().takeIf { it > 0 } ?: containerW.takeIf { it > 0 } ?: return null
        val h = pd.height.toInt().takeIf { it > 0 } ?: containerH.takeIf { it > 0 } ?: return null
        val template = loadTemplateBitmapFor(pageRow, w, h)
        return try {
            val content = repository.loadPage(pageId, density)
            NotebookExporter.renderContentBitmap(w, h, template, content, this)
        } finally {
            template?.recycle()
        }
    }

    /** Decode a page row's template image to a bitmap sized to [reqW]×[reqH]; null if none. */
    private suspend fun loadTemplateBitmapFor(pageRow: CalendarEntity, reqW: Int, reqH: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val tid = PageData.fromJson(pageRow.data).template.takeIf { it.isNotEmpty() } ?: return@withContext null
            val tRow = repository.getTemplateById(tid) ?: return@withContext null
            val b64 = TemplateData.fromJson(tRow.data)?.image?.takeIf { it.isNotEmpty() } ?: return@withContext null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapDecode.decodeSampled(bytes, reqW, reqH)
        }

    private fun emptyNoteMessage(): String {
        val md = "${selectedDate.dayOfMonth} ${Month.of(selectedDate.monthValue).getDisplayName(TextStyle.FULL, Locale.getDefault())}"
        return if (historyYearsLoaded && historyYears.isEmpty()) "No day note for $md" else "No day note for $md, $historyYear"
    }

    // ── Notebooks card grid (paginated; reused by History Opened/Edited/Created in Session 4) ──

    private fun setupNotebooksList() {
        binding.btnDayListFirst.setOnClickListener { listPage = 0; renderGridPage() }
        binding.btnDayListPrev.setOnClickListener { listPage -= 1; renderGridPage() }
        binding.btnDayListNext.setOnClickListener { listPage += 1; renderGridPage() }
        binding.btnDayListLast.setOnClickListener {
            val ipp = (gridSpec?.itemsPerPage ?: 1).coerceAtLeast(1)
            listPage = (ceil(listCards.size.toDouble() / ipp).toInt() - 1).coerceAtLeast(0)
            renderGridPage()
        }
    }

    /** The (kind, date) the card grid should show for the current mode + sub-toggle; null when the
     *  active view isn't a card grid (Note, History▸Notes, or an invalid history date). */
    private data class ListQuery(val kind: DayHistoryRepository.Kind, val date: LocalDate)

    private fun currentListQuery(): ListQuery? = when (viewMode) {
        ViewMode.NOTE -> null
        ViewMode.NOTEBOOKS -> ListQuery(
            when (notebooksSub) {
                NbSub.OPENED -> DayHistoryRepository.Kind.OPENED
                NbSub.EDITED -> DayHistoryRepository.Kind.EDITED
                NbSub.CREATED -> DayHistoryRepository.Kind.CREATED
            },
            selectedDate,
        )
        ViewMode.HISTORY -> {
            val d = historyDate()
            when {
                d == null || historySub == HistSub.NOTES -> null
                historySub == HistSub.OPENED -> ListQuery(DayHistoryRepository.Kind.OPENED, d)
                historySub == HistSub.EDITED -> ListQuery(DayHistoryRepository.Kind.EDITED, d)
                else -> ListQuery(DayHistoryRepository.Kind.CREATED, d)
            }
        }
    }

    /**
     * Load and render the card grid for the current sub-toggle + target day ([currentListQuery]),
     * shared by Notebooks and History▸Opened/Edited/Created. [resetPage] true when the toggle/day/year
     * changed; false when merely refreshing (e.g. returning from an opened notebook) so the reader
     * stays on the same page.
     */
    private fun renderList(resetPage: Boolean) {
        val token = ++listRenderToken
        if (resetPage) listPage = 0
        val query = currentListQuery()
        if (query == null) {
            listCards = emptyList()
            renderGridWhenMeasured(token)
            return
        }
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) { dayHistoryRepo.notebooksFor(query.date, query.kind) }
            if (token != listRenderToken) return@launch
            listCards = cards
            renderGridWhenMeasured(token)
        }
    }

    /**
     * Compute the grid spec against the (possibly just-shown) host and render. The host starts GONE,
     * so on first entry it may not be measured yet; a self-removing [ViewTreeObserver.OnGlobalLayoutListener]
     * waits until it reports real dimensions (mirrors MainActivity's grid bootstrap). This is more
     * robust than `doOnLayout`, whose fallback only fires on a bounds *change* of this exact view and
     * so raced the GONE→VISIBLE pass on first entry.
     */
    private fun renderGridWhenMeasured(token: Int) {
        val host = binding.dayListRows
        if (host.width > 0 && host.height > 0) {
            gridSpec = computeGridSpec(host.width, host.height)
            renderGridPage()
            return
        }
        host.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val w = host.width
                val h = host.height
                if (w <= 0 || h <= 0) return
                host.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (token != listRenderToken) return
                gridSpec = computeGridSpec(w, h)
                renderGridPage()
            }
        })
    }

    private fun computeGridSpec(availableWidth: Int, availableHeight: Int): GridSpec {
        val d = density
        val gutterPx = (12 * d).toInt()
        val paddingHPx = (16 * d).toInt()
        val paddingVPx = (16 * d).toInt()
        val rowGapPx = (6 * d).toInt()
        val labelHeightPx = (32 * d).toInt()

        val screenWidthDp = availableWidth / d
        val cols = if (screenWidthDp >= 480f) 3 else 2

        val dm = resources.displayMetrics
        val aspectRatio = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)

        val innerWidth = availableWidth - 2 * paddingHPx
        val innerHeight = availableHeight - 2 * paddingVPx
        val cardWidth = (innerWidth - (cols - 1) * gutterPx) / cols
        val cardHeight = (cardWidth * aspectRatio).toInt()
        val cellHeight = cardHeight + rowGapPx + labelHeightPx
        val rows = ((innerHeight + gutterPx) / (cellHeight + gutterPx)).coerceAtLeast(1)

        return GridSpec(cols, rows, cardWidth, cardHeight, gutterPx, rowGapPx, labelHeightPx, paddingHPx, paddingVPx)
    }

    private fun renderGridPage() {
        coverLoadJobs.forEach { it.cancel() }
        coverLoadJobs.clear()
        val host = binding.dayListRows
        host.removeAllViews()
        val spec = gridSpec
        val cards = listCards

        if (cards.isEmpty() || spec == null) {
            host.addView(
                AppCompatTextView(this).apply {
                    text = emptyListMessage()
                    setTextColor(inkLightColor)
                    textSize = 14f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ),
            )
            updateListPagination(if (cards.isEmpty()) 0 else 1)
            return
        }

        val ipp = spec.itemsPerPage
        val totalPages = ceil(cards.size.toDouble() / ipp).toInt()
        listPage = listPage.coerceIn(0, totalPages - 1)
        val start = listPage * ipp
        val end = minOf(start + ipp, cards.size)
        val pageItems = cards.subList(start, end)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val rowCount = (pageItems.size + spec.cols - 1) / spec.cols
        for (rowIdx in 0 until rowCount) {
            if (rowIdx > 0) grid.addView(Space(this), LinearLayout.LayoutParams(0, spec.gutterPx))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (colIdx in 0 until spec.cols) {
                if (colIdx > 0) row.addView(Space(this), LinearLayout.LayoutParams(spec.gutterPx, 0))
                val itemIdx = rowIdx * spec.cols + colIdx
                if (itemIdx < pageItems.size) {
                    row.addView(buildCardGroup(pageItems[itemIdx], spec))
                } else {
                    val totalCellHeight = spec.cardHeightPx + spec.rowGapPx + spec.labelHeightPx
                    row.addView(Space(this), LinearLayout.LayoutParams(spec.cardWidthPx, totalCellHeight))
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        host.addView(grid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = spec.paddingVPx })

        updateListPagination(totalPages)
    }

    private fun buildCardGroup(entry: ResolvedRecent, spec: GridSpec): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { openNotebookFromList(entry.notebookId, entry.notebookName) }
        }

        val card = FrameLayout(this).apply { setBackgroundResource(R.drawable.shape_bordered) }
        group.addView(card, LinearLayout.LayoutParams(spec.cardWidthPx, spec.cardHeightPx))
        val pad1dp = (density + 0.5f).toInt()
        card.setPadding(pad1dp, pad1dp, pad1dp, pad1dp)

        val iconSize = (minOf(spec.cardWidthPx, spec.cardHeightPx) * 0.45f).toInt()
        val coverImage = AppCompatImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        card.addView(coverImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val icon = AppCompatImageView(this).apply { setImageResource(R.drawable.ic_notebook) }
        card.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        loadCoverInto(entry.notebookId, coverImage, icon)

        group.addView(
            buildCardLabel(entry, spec),
            LinearLayout.LayoutParams(spec.cardWidthPx, spec.labelHeightPx).also { it.topMargin = spec.rowGapPx },
        )
        return group
    }

    /** Two-line card label: "folder › notebook" over the activity date/time (mirrors recents). */
    private fun buildCardLabel(entry: ResolvedRecent, spec: GridSpec): LinearLayout {
        val parent = entry.folderPath.substringAfterLast(" › ")
        val d = Date(entry.timestamp)
        val dateStr = DateFormat.getMediumDateFormat(this).format(d)
        val timeStr = DateFormat.getTimeFormat(this).format(d)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(AppCompatTextView(this@DayDetailActivity).apply {
                text = "$parent › ${entry.notebookName}"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(inkBlackColor)
            })
            addView(AppCompatTextView(this@DayDetailActivity).apply {
                text = "$dateStr, $timeStr"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(inkLightColor)
            })
        }
    }

    /** Populate a card cover: snapshot bitmap when present, lock icon when encrypted, else the
     *  default notebook icon. Job tracked so it's cancelled on the next grid re-render. */
    private fun loadCoverInto(notebookId: String, cover: AppCompatImageView, icon: AppCompatImageView) {
        val job = lifecycleScope.launch {
            val info = dayHistoryRepo.coverFor(notebookId)
            if (info.encrypted) { icon.setImageResource(R.drawable.ic_lock_cover); return@launch }
            val b64 = info.snapshotB64 ?: return@launch
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                cover.setImageBitmap(bitmap)
                cover.visibility = View.VISIBLE
                icon.visibility = View.GONE
            }
        }
        coverLoadJobs.add(job)
    }

    private fun updateListPagination(totalPages: Int) {
        binding.tvDayListPageIndicator.text = if (totalPages > 0) "${listPage + 1} / $totalPages" else ""
        binding.btnDayListFirst.isEnabled = listPage > 0
        binding.btnDayListPrev.isEnabled = listPage > 0
        binding.btnDayListNext.isEnabled = listPage < totalPages - 1
        binding.btnDayListLast.isEnabled = listPage < totalPages - 1
    }

    private fun emptyListMessage(): String {
        val what = if (viewMode == ViewMode.HISTORY) when (historySub) {
            HistSub.OPENED -> "opened"; HistSub.EDITED -> "edited"; else -> "created"
        } else when (notebooksSub) {
            NbSub.OPENED -> "opened"; NbSub.EDITED -> "edited"; NbSub.CREATED -> "created"
        }
        val where = if (viewMode == ViewMode.HISTORY) "in $historyYear" else "on this day"
        return "No notebooks $what $where"
    }

    private fun openNotebookFromList(notebookId: String, notebookName: String) {
        startActivity(
            Intent(this, NotebookActivity::class.java)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, notebookName)
        )
    }

    // ── Page load ────────────────────────────────────────────────────────────────

    private suspend fun loadDayPage() {
        val (pid, lid) = repository.getOrCreatePageLayer(pageKey())
        currentPageId = pid
        currentLayerId = lid
        persistedStrokeIds.clear()
        loadTemplateBitmap()
        loadCanvasContent()
        initHistory()
    }

    private suspend fun loadTemplateBitmap() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val view = drawingView.asView()
        val reqW = view.width.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
        val reqH = view.height.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
        val (templateId, bitmap) = withContext(Dispatchers.IO) {
            val pageRow = NotesproutIndex.calendarDao().getObjectById(pageId)
            val tid = pageRow?.let { PageData.fromJson(it.data).template } ?: ""
            if (tid.isEmpty()) return@withContext "" to null
            val tRow = repository.getTemplateById(tid) ?: return@withContext "" to null
            val b64 = TemplateData.fromJson(tRow.data)?.image?.takeIf { it.isNotEmpty() }
                ?: return@withContext "" to null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            tid to BitmapDecode.decodeSampled(bytes, reqW, reqH)
        }
        currentTemplateId = templateId
        currentTemplateBitmap = bitmap
    }

    private suspend fun loadCanvasContent() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val containerW = binding.dayContent.width.toFloat()
        val containerH = binding.dayContent.height.toFloat()

        val content = withContext(Dispatchers.IO) {
            if (containerW > 0 && containerH > 0) {
                val row = NotesproutIndex.calendarDao().getObjectById(pageId)
                val pd = row?.let { PageData.fromJson(it.data) }
                if (pd != null && (pd.width == 0f || pd.height == 0f)) {
                    repository.setPageSize(pageId, containerW, containerH)
                }
            }
            repository.loadPage(pageId, density)
        }

        val template = currentTemplateBitmap
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(
                content.strokes, template, content.headings, content.textObjects,
                content.lineObjects, content.links,
                stickyNotes = content.stickyNotes, shapeObjects = content.shapeObjects,
            )
        }
        drawingView.loadHeadings(content.headings)
        drawingView.loadTextObjects(content.textObjects)
        drawingView.loadLineObjects(content.lineObjects)
        drawingView.loadLinks(content.links)
        drawingView.loadStickyNotes(content.stickyNotes)
        drawingView.loadShapeObjects(content.shapeObjects)
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(content.strokes, bitmap, template)
        } else {
            drawingView.setTemplate(template)
            drawingView.loadStrokes(content.strokes)
        }
        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(content.strokes.map { it.id })
    }

    /** Rebuild the render bitmap from the drawing view's current in-memory lists + template. */
    private suspend fun rebuildCanvas() {
        val strokes = drawingView.getStrokes()
        val headings = drawingView.getHeadings()
        val texts = drawingView.getTextObjects()
        val lines = drawingView.getLineObjects()
        val links = drawingView.getLinks()
        val stickyNotes = drawingView.getStickyNotes()
        val shapes = drawingView.getShapeObjects()
        val template = currentTemplateBitmap
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, template, headings, texts, lines, links, stickyNotes = stickyNotes, shapeObjects = shapes)
        }
        if (bitmap != null) drawingView.loadStrokesWithBitmap(strokes, bitmap, template)
        else { drawingView.setTemplate(template); drawingView.loadStrokes(strokes) }
    }

    // ── Templates ──────────────────────────────────────────────────────────────

    private fun showTemplatePicker() {
        DayTemplateDialog(
            activity = this,
            lifecycleScope = lifecycleScope,
            dao = NotesproutIndex.calendarDao(),
            currentTemplateId = currentTemplateId,
            onConfirm = { templateId, _ -> applyTemplate(templateId) },
            onBrowseLibrary = {
                templatePickLauncher.launch(
                    Intent(this, TemplateBrowserActivity::class.java)
                        .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK)
                )
            },
        ).show()
    }

    /** A library template was picked from the browser — copy it into the calendar table, then apply. */
    private fun onLibraryTemplatePicked(libraryTemplateId: String) {
        if (libraryTemplateId.isEmpty()) { applyTemplate(""); return }
        lifecycleScope.launch {
            val newId = withContext(Dispatchers.IO) {
                val entity = indexRepo.getTemplate(libraryTemplateId) ?: return@withContext null
                val tObj = IndexTemplateObject.fromJson(entity.data) ?: return@withContext null
                if (tObj.image.isEmpty()) return@withContext null
                repository.insertTemplateRow(entity.name, tObj.width, tObj.height, tObj.image)
            } ?: run { toast("Couldn't load template"); return@launch }
            applyTemplate(newId)
        }
    }

    /** Set the page's template (id "" = Blank), reload its bitmap from the DB, and repaint. */
    private fun applyTemplate(templateId: String) {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.setPageTemplate(pageId, templateId) }
            loadTemplateBitmap()
            rebuildCanvas()
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private suspend fun saveStrokes() = saveMutex.withLock {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return@withLock
        val currentStrokes = drawingView.getStrokes()
        val alreadyPersisted = persistedStrokeIds.toHashSet()
        val newStrokes = currentStrokes.filter { it.id !in alreadyPersisted }
        if (newStrokes.isEmpty()) return@withLock
        repository.saveStrokes(layerId, newStrokes)
        persistedStrokeIds.addAll(newStrokes.map { it.id })
    }

    // ── Undo / redo (full-layer snapshot history) ───────────────────────────────

    private suspend fun initHistory() {
        currentSnapshot = repository.snapshotLayer(currentLayerId)
        undoStack.clear()
        redoStack.clear()
    }

    private fun pushHistory() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            val snap = repository.snapshotLayer(layerId)
            undoStack.addLast(currentSnapshot)
            while (undoStack.size > historyCap) undoStack.removeFirst()
            currentSnapshot = snap
            redoStack.clear()
        }
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            redoStack.addLast(currentSnapshot)
            val prev = undoStack.removeLast()
            currentSnapshot = prev
            withContext(Dispatchers.IO) { repository.restoreLayer(layerId, prev) }
            loadCanvasContent()
        }
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            undoStack.addLast(currentSnapshot)
            val next = redoStack.removeLast()
            currentSnapshot = next
            withContext(Dispatchers.IO) { repository.restoreLayer(layerId, next) }
            loadCanvasContent()
        }
    }

    // ── Drawing callbacks ──────────────────────────────────────────────────────

    private fun wireDrawingCallbacks() {
        drawingView.onStrokeErased = { strokeId ->
            persistedStrokeIds.remove(strokeId)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { NotesproutIndex.calendarDao().softDelete(strokeId, System.currentTimeMillis()) }
                pushHistory()
            }
        }
        drawingView.onHeadingErased = { h -> softDeleteAndRebuild(h.id) }
        drawingView.onTextErased = { t -> softDeleteAndRebuild(t.id) }
        drawingView.onLineErased = { l -> softDeleteAndRebuild(l.id) }
        drawingView.onLinkErased = { l -> softDeleteAndRebuild(l.id) }
        drawingView.onStickyNoteErased = { n -> softDeleteAndRebuild(n.id) }
        drawingView.onShapeErased = { s -> softDeleteAndRebuild(s.id) }

        drawingView.onShapeRecognized = { stroke, result ->
            lifecycleScope.launch(Dispatchers.IO) { convertStrokeToShape(stroke, result) }
        }
        drawingView.onShapeTransformed = { before, after ->
            lifecycleScope.launch(Dispatchers.IO) { persistShapeTransform(before, after) }
        }
        drawingView.onShapeTransformTapOutside = { exitShapeTransformMode(clearSelection = true) }
        drawingView.onShapeTransformDragStarted = { hideShapeTransformButtons() }
        drawingView.onShapeTransformMoved = { newBbox ->
            val pad = 8f * density
            drawingView.setLassoOverlay(null, RectF(newBbox).apply { inset(-pad, -pad) })
            showShapeTransformButtons(newBbox)
        }

        drawingView.onPenLifted = {
            lifecycleScope.launch {
                // Persist on appScope so a stroke survives even if the user leaves immediately
                // after lifting; join so pushHistory snapshots the layer with the new stroke in it.
                NotesproutApplication.appScope.launch { saveStrokes() }.join()
                pushHistory()
            }
        }

        drawingView.onSnapshotReady = { snapshot ->
            val pageId = currentPageId
            if (pageId.isNotEmpty()) lifecycleScope.launch { withContext(Dispatchers.IO) { persistSnapshot(pageId, snapshot) } }
        }

        wireLassoCallbacks()
    }

    private fun softDeleteAndRebuild(id: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { NotesproutIndex.calendarDao().softDelete(id, System.currentTimeMillis()) }
            rebuildCanvas()
            pushHistory()
        }
    }

    private fun wireLassoCallbacks() {
        drawingView.onLassoComplete = lasso@{ drawnPath, startPoint ->
            drawnPath.lineTo(startPoint.x, startPoint.y)
            drawnPath.close()
            selectedObjectIds.clear()
            val strokeSnapshot = drawingView.getStrokes()
            val headingSnapshot = drawingView.getHeadings()
            val textSnapshot = drawingView.getTextObjects()
            val lineSnapshot = drawingView.getLineObjects()
            val linkSnapshot = drawingView.getLinks()
            val stickySnapshot = drawingView.getStickyNotes()
            val shapeSnapshot = drawingView.getShapeObjects()
            lifecycleScope.launch(Dispatchers.Default) {
                val lassoBounds = RectF()
                drawnPath.computeBounds(lassoBounds, true)
                val minPx = 10f * density
                if (lassoBounds.width() < minPx && lassoBounds.height() < minPx) return@launch
                val clipRect = Rect(
                    (lassoBounds.left - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.top - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.right + 1f).toInt(),
                    (lassoBounds.bottom + 1f).toInt(),
                )
                val lassoRegion = Region().apply { setPath(drawnPath, Region(clipRect)) }
                val hitIds = mutableSetOf<String>()
                val unionBounds = RectF()
                for (stroke in strokeSnapshot) {
                    if (!RectF.intersects(lassoBounds, stroke.boundingBox)) continue
                    for (pt in stroke.points) {
                        if (lassoRegion.contains(pt.x.toInt(), pt.y.toInt())) {
                            hitIds.add(stroke.id); unionBounds.union(stroke.boundingBox); break
                        }
                    }
                }
                fun hitBox(id: String, box: RectF) {
                    if (!RectF.intersects(lassoBounds, box)) return
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, box)) { hitIds.add(id); unionBounds.union(box) }
                }
                headingSnapshot.forEach { hitBox(it.id, it.boundingBox) }
                textSnapshot.forEach { hitBox(it.id, it.boundingBox) }
                lineSnapshot.forEach { hitBox(it.id, it.boundingBox) }
                linkSnapshot.forEach { hitBox(it.id, it.boundingBox) }
                stickySnapshot.forEach { hitBox(it.id, it.boundingBox) }
                shapeSnapshot.forEach { hitBox(it.id, it.boundingBox) }
                withContext(Dispatchers.Main) {
                    if (hitIds.isEmpty()) return@withContext
                    selectedObjectIds.clear(); selectedObjectIds.addAll(hitIds)
                    drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                    val pad = 8f * density
                    unionBounds.inset(-pad, -pad)
                    drawingView.setLassoOverlay(null, unionBounds)
                    updateFloatingSelectionToolbar(unionBounds)
                }
            }
        }

        drawingView.onLassoTapToDismiss = {
            val hadActiveSelection = selectedObjectIds.isNotEmpty()
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession && hadActiveSelection) { exitLassoMode(); drawingView.enableDrawing() }
        }

        drawingView.onLassoTap = tap@{ tapX, tapY ->
            if (selectedObjectIds.size == 1) {
                val selShape = drawingView.getShapeObjects().find { it.id == selectedObjectIds.first() }
                if (selShape != null && selShape.boundingBox.contains(tapX, tapY)) { enterShapeTransformMode(selShape); return@tap }
            }
            if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) performLassoPaste(tapX, tapY)
        }

        drawingView.onDragStarted = { hideFloatingSelectionToolbar() }
        drawingView.onLassoSelectionCleared = { hideFloatingSelectionToolbar() }

        drawingView.onStrokesMoved = { _, movedStrokes, _, movedHeadings, _, movedTextObjects, _, movedLineObjects, _, movedLinks, _, movedStickyNotes, _, movedShapes ->
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                val dao = NotesproutIndex.calendarDao()
                withContext(Dispatchers.IO) {
                    for (s in movedStrokes) {
                        val bbox = s.boundingBox
                        dao.updateObjectData(s.id, BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(), s.toStrokeData(now).toJson(), now)
                    }
                    for (h in movedHeadings) dao.updateObjectData(h.id, h.boundingBox.toBoundingBoxJson(), HeadingObject(h.strokes, h.recognizedText, h.level).toJson(), now)
                    for (t in movedTextObjects) dao.updateObjectData(t.id, t.boundingBox.toBoundingBoxJson(), TextObject(text = t.text, strokes = t.strokes).toJson(), now)
                    for (l in movedLineObjects) dao.updateObjectData(l.id, l.boundingBox.toBoundingBoxJson(), LineObject(l.style, l.orientation, l.strokeWidthDp, l.dotSpacingPx / density).toJson(), now)
                    for (lk in movedLinks) dao.updateObjectData(lk.id, lk.boundingBox.toBoundingBoxJson(), lk.toLinkObject(density).toJson(), now)
                    for (n in movedStickyNotes) dao.updateObjectData(n.id, n.boundingBox.toBoundingBoxJson(), n.toStickyNoteObject(density).toJson(), now)
                    for (sh in movedShapes) dao.updateObjectData(sh.id, sh.boundingBox.toBoundingBoxJson(), sh.toShapeObject(density).toJson(), now)
                }
                val newBox = RectF()
                movedStrokes.forEach { newBox.union(it.boundingBox) }
                movedHeadings.forEach { newBox.union(it.boundingBox) }
                movedTextObjects.forEach { newBox.union(it.boundingBox) }
                movedLineObjects.forEach { newBox.union(it.boundingBox) }
                movedLinks.forEach { newBox.union(it.boundingBox) }
                movedStickyNotes.forEach { newBox.union(it.boundingBox) }
                movedShapes.forEach { newBox.union(it.boundingBox) }
                val pad = 8f * density
                newBox.inset(-pad, -pad)
                updateFloatingSelectionToolbar(newBox)
                pushHistory()
            }
        }

        drawingView.onSmartLassoComplete = { hitIds, unionBounds ->
            if (!isLassoMode) { enterLassoMode(); isSmartLassoSession = true }
            selectedObjectIds.clear(); selectedObjectIds.addAll(hitIds)
            drawingView.lassoSelectedIds = selectedObjectIds.toSet()
            val pad = 8f * density
            val paddedBounds = RectF(unionBounds).also { it.inset(-pad, -pad) }
            lifecycleScope.launch {
                rebuildCanvas()
                drawingView.setLassoOverlay(null, paddedBounds)
                updateFloatingSelectionToolbar(paddedBounds)
            }
        }

        drawingView.onScribbleEraseComplete = { erasedObjectIds, erasedHeadings, erasedTextObjects, erasedLineObjects, erasedLinks, erasedStickyNotes, erasedShapes ->
            if (erasedObjectIds.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.softDeleteObjects(erasedObjectIds) }
                    val erasedSet = erasedObjectIds.toSet()
                    val hIds = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                    val tIds = erasedTextObjects.mapTo(mutableSetOf()) { it.id }
                    val lIds = erasedLineObjects.mapTo(mutableSetOf()) { it.id }
                    val lkIds = erasedLinks.mapTo(mutableSetOf()) { it.id }
                    val sIds = erasedStickyNotes.mapTo(mutableSetOf()) { it.id }
                    val shIds = erasedShapes.mapTo(mutableSetOf()) { it.id }
                    val updatedStrokes = drawingView.getStrokes().filter { it.id !in erasedSet }
                    drawingView.setStrokeListSilently(updatedStrokes)
                    drawingView.loadHeadings(drawingView.getHeadings().filter { it.id !in hIds })
                    drawingView.loadTextObjects(drawingView.getTextObjects().filter { it.id !in tIds })
                    drawingView.loadLineObjects(drawingView.getLineObjects().filter { it.id !in lIds })
                    drawingView.loadLinks(drawingView.getLinks().filter { it.id !in lkIds })
                    drawingView.loadStickyNotes(drawingView.getStickyNotes().filter { it.id !in sIds })
                    drawingView.loadShapeObjects(drawingView.getShapeObjects().filter { it.id !in shIds })
                    persistedStrokeIds.removeAll(erasedSet)
                    rebuildCanvas()
                    pushHistory()
                }
            }
        }
    }

    // ── Tool buttons ───────────────────────────────────────────────────────────

    private fun wireToolButtons() {
        binding.btnDayPen.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            hideShapeInsertToolbar()
            isEraserActive = false; isLassoEraserActive = false
            drawingView.setEraserMode(false); drawingView.setLassoEraserMode(false)
            binding.btnDayPen.isSelected = true
            binding.btnDayEraser.isSelected = false
            binding.btnDayLassoEraser.isSelected = false
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, ActiveTool.PEN)
        }
        binding.btnDayEraser.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            hideShapeInsertToolbar()
            isLassoEraserActive = false; drawingView.setLassoEraserMode(false)
            binding.btnDayLassoEraser.isSelected = false
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnDayEraser.isSelected = isEraserActive
            binding.btnDayPen.isSelected = !isEraserActive
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }
        binding.btnDayLassoEraser.setOnClickListener {
            if (isLassoMode) exitLassoMode()
            hideShapeInsertToolbar()
            isEraserActive = false; drawingView.setEraserMode(false)
            isLassoEraserActive = !isLassoEraserActive
            drawingView.setLassoEraserMode(isLassoEraserActive)
            binding.btnDayLassoEraser.isSelected = isLassoEraserActive
            binding.btnDayPen.isSelected = !isLassoEraserActive
            binding.btnDayEraser.isSelected = false
            drawingView.releaseRender()
        }
        binding.btnDayLasso.setOnClickListener {
            hideShapeInsertToolbar()
            if (!isLassoMode) enterLassoMode() else exitLassoMode()
            drawingView.releaseRender()
        }
        binding.btnDayStickyNote.setOnClickListener { hideShapeInsertToolbar(); lifecycleScope.launch { insertStickyNote() } }
        binding.btnDayInsertShape.setOnClickListener {
            if (binding.dayShapeInsertToolbar.visibility == View.VISIBLE) hideShapeInsertToolbar() else showShapeInsertToolbar()
        }

        binding.btnInsertShapeRectangle.setOnClickListener { insertShape(ShapeType.RECTANGLE) }
        binding.btnInsertShapeEllipse.setOnClickListener { insertShape(ShapeType.ELLIPSE) }
        binding.btnInsertShapeTriangle.setOnClickListener { insertShape(ShapeType.TRIANGLE) }
        binding.btnInsertShapeDiamond.setOnClickListener { insertShape(ShapeType.DIAMOND) }
        binding.btnInsertShapeTrapezoid.setOnClickListener { insertShape(ShapeType.TRAPEZOID) }
        binding.btnInsertShapePentagon.setOnClickListener { insertShape(ShapeType.PENTAGON) }
        binding.btnInsertShapeHexagon.setOnClickListener { insertShape(ShapeType.HEXAGON) }
        binding.btnInsertShapeStar.setOnClickListener { insertShape(ShapeType.STAR) }
        binding.btnInsertShapeArch.setOnClickListener { insertShape(ShapeType.ARCH) }
        binding.btnInsertShapeLine.setOnClickListener { insertShape(ShapeType.LINE) }
        binding.btnInsertShapeArrow.setOnClickListener { insertShape(ShapeType.ARROW) }

        binding.btnLassoCopy.setOnClickListener { performLassoCopy() }
        binding.btnLassoCut.setOnClickListener { performLassoCut() }
        binding.btnLassoDelete.setOnClickListener { performLassoDelete() }
        binding.btnLassoSendToNotebook.setOnClickListener { sendToNotebook() }
        binding.btnConvertShape.setOnClickListener {
            val stroke = shapeConvertStroke ?: return@setOnClickListener
            val result = shapeConvertResult ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) { convertStrokeToShape(stroke, result) }
        }
        binding.btnShapeAspectLock.setOnClickListener {
            val updated = drawingView.toggleShapeAspectLock() ?: return@setOnClickListener
            updateShapeTransformToggle(updated)
        }
        binding.btnShapeTransformDone.setOnClickListener { exitShapeTransformMode(clearSelection = false) }
    }

    private fun enterLassoMode() {
        isLassoMode = true
        if (isEraserActive) { isEraserActive = false; drawingView.setEraserMode(false) }
        if (isLassoEraserActive) { isLassoEraserActive = false; drawingView.setLassoEraserMode(false) }
        drawingView.setLassoMode(true)
        binding.btnDayLasso.isSelected = true
        binding.btnDayPen.isSelected = false
        binding.btnDayEraser.isSelected = false
        binding.btnDayLassoEraser.isSelected = false
    }

    private fun exitLassoMode() {
        isLassoMode = false
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        drawingView.setLassoMode(false)
        binding.btnDayLasso.isSelected = false
        binding.btnDayPen.isSelected = !isEraserActive
        binding.btnDayEraser.isSelected = isEraserActive
        hideFloatingSelectionToolbar()
    }

    // ── Lasso operations ───────────────────────────────────────────────────────

    private fun collectSelection(ids: Set<String>): NotesproutClipboard.ClipboardContent? {
        if (ids.isEmpty()) return null
        return buildClip(
            drawingView.getStrokes().filter { it.id in ids },
            drawingView.getHeadings().filter { it.id in ids },
            drawingView.getTextObjects().filter { it.id in ids },
            drawingView.getLineObjects().filter { it.id in ids },
            drawingView.getLinks().filter { it.id in ids },
            drawingView.getStickyNotes().filter { it.id in ids },
            drawingView.getShapeObjects().filter { it.id in ids },
        )
    }

    private fun buildClip(
        strokes: List<LiveStroke>, headings: List<HeadingStroke>, textObjects: List<TextRender>,
        lineObjects: List<LineRender>, links: List<com.notesprout.android.data.LinkRender>,
        stickyNotes: List<StickyNoteRender>, shapes: List<ShapeRender>,
    ): NotesproutClipboard.ClipboardContent? {
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() &&
            links.isEmpty() && stickyNotes.isEmpty() && shapes.isEmpty()) return null
        val box = RectF()
        strokes.forEach { box.union(it.boundingBox) }
        headings.forEach { box.union(it.boundingBox) }
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        links.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }
        shapes.forEach { box.union(it.boundingBox) }
        return NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { LiveStroke(it.id, it.points.map { pt -> PointF(pt.x, pt.y) }) },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> LiveStroke(s.id, s.points.map { PointF(it.x, it.y) }) },
                    recognizedText = h.recognizedText, level = h.level)
            },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = links.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
            shapeObjects = shapes.map { it.copy(boundingBox = RectF(it.boundingBox)) },
        )
    }

    private fun performLassoCopy() {
        val content = collectSelection(drawingView.lassoSelectedIds) ?: return
        NotesproutClipboard.content = content
        updateLassoButtonIcon()
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
    }

    private fun performLassoCut() {
        val ids = drawingView.lassoSelectedIds
        val content = collectSelection(ids) ?: return
        NotesproutClipboard.content = content
        updateLassoButtonIcon()
        val allIds = (content.strokes.map { it.id } + content.headings.map { it.id } + content.textObjects.map { it.id } +
                content.lineObjects.map { it.id } + content.links.map { it.id } + content.stickyNotes.map { it.id } +
                content.shapeObjects.map { it.id })
        deleteIdsAndRefresh(allIds)
    }

    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val allIds = (drawingView.getStrokes().filter { it.id in ids }.map { it.id } +
                drawingView.getHeadings().filter { it.id in ids }.map { it.id } +
                drawingView.getTextObjects().filter { it.id in ids }.map { it.id } +
                drawingView.getLineObjects().filter { it.id in ids }.map { it.id } +
                drawingView.getLinks().filter { it.id in ids }.map { it.id } +
                drawingView.getStickyNotes().filter { it.id in ids }.map { it.id } +
                drawingView.getShapeObjects().filter { it.id in ids }.map { it.id })
        if (allIds.isEmpty()) return
        deleteIdsAndRefresh(allIds)
    }

    private fun deleteIdsAndRefresh(allIds: List<String>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.softDeleteObjects(allIds) }
            val erasedSet = allIds.toSet()
            drawingView.setStrokeListSilently(drawingView.getStrokes().filter { it.id !in erasedSet })
            drawingView.loadHeadings(drawingView.getHeadings().filter { it.id !in erasedSet })
            drawingView.loadTextObjects(drawingView.getTextObjects().filter { it.id !in erasedSet })
            drawingView.loadLineObjects(drawingView.getLineObjects().filter { it.id !in erasedSet })
            drawingView.loadLinks(drawingView.getLinks().filter { it.id !in erasedSet })
            drawingView.loadStickyNotes(drawingView.getStickyNotes().filter { it.id !in erasedSet })
            drawingView.loadShapeObjects(drawingView.getShapeObjects().filter { it.id !in erasedSet })
            persistedStrokeIds.removeAll(erasedSet)
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            rebuildCanvas()
            pushHistory()
        }
    }

    private fun performLassoPaste(tapX: Float, tapY: Float) {
        val clip = NotesproutClipboard.content ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val dx = tapX - clip.boundingBox.centerX()
        val dy = tapY - clip.boundingBox.centerY()
        val translated = regenIds(clip, dx, dy)

        lifecycleScope.launch {
            val existingStrokes = drawingView.getStrokes()
            withContext(Dispatchers.IO) { repository.insertObjects(layerId, translated, density) }
            translated.strokes.forEach { persistedStrokeIds.add(it.id) }

            val allStrokes = existingStrokes + translated.strokes
            drawingView.loadHeadings(drawingView.getHeadings() + translated.headings)
            drawingView.loadTextObjects(drawingView.getTextObjects() + translated.textObjects)
            drawingView.loadLineObjects(drawingView.getLineObjects() + translated.lineObjects)
            drawingView.loadLinks(drawingView.getLinks() + translated.links)
            drawingView.loadStickyNotes(drawingView.getStickyNotes() + translated.stickyNotes)
            drawingView.loadShapeObjects(drawingView.getShapeObjects() + translated.shapeObjects)
            drawingView.setStrokeListSilently(allStrokes)
            rebuildCanvas()

            val newBox = RectF()
            translated.strokes.forEach { newBox.union(it.boundingBox) }
            translated.headings.forEach { newBox.union(it.boundingBox) }
            translated.textObjects.forEach { newBox.union(it.boundingBox) }
            translated.lineObjects.forEach { newBox.union(it.boundingBox) }
            translated.links.forEach { newBox.union(it.boundingBox) }
            translated.stickyNotes.forEach { newBox.union(it.boundingBox) }
            translated.shapeObjects.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            translated.strokes.forEach { selectedObjectIds.add(it.id) }
            translated.headings.forEach { selectedObjectIds.add(it.id) }
            translated.textObjects.forEach { selectedObjectIds.add(it.id) }
            translated.lineObjects.forEach { selectedObjectIds.add(it.id) }
            translated.links.forEach { selectedObjectIds.add(it.id) }
            translated.stickyNotes.forEach { selectedObjectIds.add(it.id) }
            translated.shapeObjects.forEach { selectedObjectIds.add(it.id) }
            isSmartLassoSession = false
            if (!isLassoMode) enterLassoMode()
            binding.btnDayLasso.isSelected = true
            binding.btnDayPen.isSelected = false
            binding.btnDayEraser.isSelected = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
            pushHistory()
        }
    }

    private fun regenIds(clip: NotesproutClipboard.ClipboardContent, dx: Float, dy: Float): NotesproutClipboard.ClipboardContent {
        val newStrokes = clip.strokes.map { s ->
            s.copy(id = UUID.randomUUID().toString(), points = s.points.map { PointF(it.x + dx, it.y + dy) })
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy, h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s -> s.copy(id = UUID.randomUUID().toString(), points = s.points.map { PointF(it.x + dx, it.y + dy) }) },
                recognizedText = h.recognizedText, level = h.level,
            )
        }
        val newTexts = clip.textObjects.map { t ->
            TextRender(UUID.randomUUID().toString(),
                RectF(t.boundingBox.left + dx, t.boundingBox.top + dy, t.boundingBox.right + dx, t.boundingBox.bottom + dy), t.text, t.strokes)
        }
        val newLines = clip.lineObjects.map { l ->
            l.copy(id = UUID.randomUUID().toString(),
                boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy, l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                startX = l.startX + dx, startY = l.startY + dy, endX = l.endX + dx, endY = l.endY + dy)
        }
        val newLinks = clip.links.map { it.translate(dx, dy, newId = UUID.randomUUID().toString()) }
        val newStickies = clip.stickyNotes.map { it.translate(dx, dy, newId = UUID.randomUUID().toString()) }
        val newShapes = clip.shapeObjects.map { s ->
            s.copy(id = UUID.randomUUID().toString(), centerX = s.centerX + dx, centerY = s.centerY + dy,
                boundingBox = RectF(s.boundingBox.left + dx, s.boundingBox.top + dy, s.boundingBox.right + dx, s.boundingBox.bottom + dy))
        }
        return NotesproutClipboard.ClipboardContent(
            strokes = newStrokes, headings = newHeadings,
            boundingBox = RectF(clip.boundingBox.left + dx, clip.boundingBox.top + dy, clip.boundingBox.right + dx, clip.boundingBox.bottom + dy),
            textObjects = newTexts, lineObjects = newLines, links = newLinks, stickyNotes = newStickies, shapeObjects = newShapes,
        )
    }

    // ── Send to Notebook ────────────────────────────────────────────────────────

    private fun sendToNotebook() {
        val content = collectSelection(drawingView.lassoSelectedIds)
        if (content == null) { toast("Select objects first"); return }
        val fromId = fromNotebookId
        if (fromId != null) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Send to Notebook")
                .setPositiveButton(fromNotebookName ?: "This notebook") { _, _ -> openNotebookWithPaste(fromId, fromNotebookName ?: "", content) }
                .setNeutralButton("Other notebook…") { _, _ -> launchNotebookPicker(content, excludeId = fromId) }
                .setNegativeButton("Cancel", null)
                .show()
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        } else {
            launchNotebookPicker(content, excludeId = null)
        }
    }

    private fun launchNotebookPicker(content: NotesproutClipboard.ClipboardContent, excludeId: String?) {
        pendingSendContent = content
        val intent = Intent(this, NotebookPickerActivity::class.java)
        if (excludeId != null) intent.putExtra(NotebookPickerActivity.EXTRA_EXCLUDE_NOTEBOOK_ID, excludeId)
        notebookPickerLauncher.launch(intent)
    }

    private fun openNotebookWithPaste(destId: String, destName: String, content: NotesproutClipboard.ClipboardContent) {
        CalendarTransfer.pending = content
        startActivity(
            Intent(this, NotebookActivity::class.java)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, destId)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, destName)
                .putExtra(NotebookActivity.EXTRA_PASTE_PENDING, true)
        )
        finish()
    }

    // ── Sticky notes ────────────────────────────────────────────────────────────

    private suspend fun insertStickyNote() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageW = binding.dayContent.width.takeIf { it > 0 } ?: return
        val pageH = binding.dayContent.height.takeIf { it > 0 } ?: return
        val iconSizePx = STICKY_NOTE_ICON_SIZE_DP * density
        val left = ((pageW - iconSizePx) / 2f).coerceAtLeast(0f)
        val top = ((pageH - iconSizePx) / 2f).coerceAtLeast(0f)
        val bbox = RectF(left, top, left + iconSizePx, top + iconSizePx)
        val noteId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            NotesproutIndex.calendarDao().insertObject(
                CalendarEntity(
                    id = noteId, parentId = layerId, boundingBox = bbox.toBoundingBoxJson(),
                    sortOrder = 0, createdAt = now, updatedAt = now,
                    type = TYPE_STICKY_NOTE, data = StickyNoteObject().toJson(),
                )
            )
        }
        val noteRender = StickyNoteRender(id = noteId, boundingBox = bbox)
        drawingView.loadStickyNotes(drawingView.getStickyNotes() + noteRender)
        rebuildCanvas()
        pushHistory()
        openStickyNote(noteRender, initialCreate = true)
    }

    private fun openStickyNote(note: StickyNoteRender, initialCreate: Boolean) {
        StickyNoteEditorTransfer.input = StickyNoteEditorTransfer.Content(
            strokes = note.strokes, headings = note.headings, textObjects = note.textObjects,
            lines = note.lines, shapes = note.shapes, contentWidth = note.contentWidth, contentHeight = note.contentHeight,
        )
        StickyNoteEditorTransfer.output = null
        pendingStickyNote = note
        pendingStickyInitialCreate = initialCreate
        editorLauncher.launch(Intent(this, StickyNoteEditorActivity::class.java))
    }

    private fun stickyNoteAt(x: Float, y: Float): StickyNoteRender? =
        drawingView.getStickyNotes().lastOrNull { it.boundingBox.contains(x, y) }

    private fun selectStickyNoteIcon(note: StickyNoteRender) {
        if (!isLassoMode) enterLassoMode()
        selectedObjectIds.clear()
        selectedObjectIds.add(note.id)
        drawingView.lassoSelectedIds = selectedObjectIds.toSet()
        val pad = 8f * density
        val selBox = RectF(note.boundingBox).apply { inset(-pad, -pad) }
        drawingView.setLassoOverlay(null, selBox)
        updateFloatingSelectionToolbar(selBox)
    }

    // ── Shape insert / conversion / transform ────────────────────────────────────

    private fun showShapeInsertToolbar() {
        if (isLassoMode) exitLassoMode()
        val btnLoc = IntArray(2).also { binding.btnDayInsertShape.getLocationOnScreen(it) }
        val contentLoc = IntArray(2).also { binding.dayContent.getLocationOnScreen(it) }
        val btnCx = (btnLoc[0] - contentLoc[0]) + binding.btnDayInsertShape.width / 2f
        val gap = 8f * density
        binding.dayShapeInsertToolbar.visibility = View.VISIBLE
        binding.btnDayInsertShape.isSelected = true
        binding.dayShapeInsertToolbar.post {
            val w = binding.dayShapeInsertToolbar.measuredWidth.toFloat()
            val contentW = binding.dayContent.width.toFloat()
            binding.dayShapeInsertToolbar.x = (btnCx - w / 2f).coerceIn(0f, (contentW - w).coerceAtLeast(0f))
            binding.dayShapeInsertToolbar.y = gap
        }
    }

    private fun hideShapeInsertToolbar() {
        binding.dayShapeInsertToolbar.visibility = View.GONE
        binding.btnDayInsertShape.isSelected = false
    }

    private fun insertShape(type: ShapeType) {
        hideShapeInsertToolbar()
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageW = binding.dayContent.width.takeIf { it > 0 } ?: return
        val pageH = binding.dayContent.height.takeIf { it > 0 } ?: return
        lifecycleScope.launch {
            val isOpenShape = type == ShapeType.LINE || type == ShapeType.ARROW || type == ShapeType.ARCH
            val width: Float; val height: Float; val aspectLocked: Boolean
            if (isOpenShape) {
                val openWidth = pageW * 0.5f
                width = openWidth
                height = if (type == ShapeType.ARCH) openWidth * 0.5f else 1f
                aspectLocked = false
            } else {
                val closedSize = STICKY_NOTE_ICON_SIZE_DP * density
                width = closedSize
                height = closedSize
                aspectLocked = type == ShapeType.ELLIPSE || type == ShapeType.RECTANGLE || type == ShapeType.STAR
            }
            val shapeObj = ShapeObject(
                type = type, centerX = pageW / 2f, centerY = pageH / 2f,
                width = width, height = height, rotationDeg = 0f,
                strokeWidthDp = 2f, aspectLocked = aspectLocked, pointCount = 5,
            )
            val shapeId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val shapeRender = ShapeRender.from(shapeId, shapeObj, density)
            withContext(Dispatchers.IO) {
                NotesproutIndex.calendarDao().insertObject(
                    CalendarEntity(id = shapeId, parentId = layerId, boundingBox = shapeRender.boundingBox.toBoundingBoxJson(),
                        sortOrder = 0, createdAt = now, updatedAt = now, type = TYPE_SHAPE, data = shapeObj.toJson())
                )
            }
            drawingView.loadShapeObjects(drawingView.getShapeObjects() + shapeRender)
            rebuildCanvas()
            if (!isLassoMode) enterLassoMode()
            isSmartLassoSession = true
            val pad = 8f * density
            val selBox = RectF(shapeRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(shapeId), selBox)
            selectedObjectIds.clear(); selectedObjectIds.add(shapeId)
            updateFloatingSelectionToolbar(selBox)
            pushHistory()
        }
    }

    private suspend fun convertStrokeToShape(stroke: LiveStroke, result: ShapeRecognizer.Result) {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val shapeId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val shapeObj = ShapeObject(
            type = result.type, centerX = result.centerX, centerY = result.centerY,
            width = result.width, height = result.height, rotationDeg = result.rotationDeg,
            strokeWidthDp = stroke.strokeWidth / density, aspectLocked = result.aspectLocked, pointCount = result.pointCount,
        )
        val shapeRender = ShapeRender.from(shapeId, shapeObj, density)
        val wasInDb = stroke.id in persistedStrokeIds
        val dao = NotesproutIndex.calendarDao()
        if (wasInDb) dao.softDelete(stroke.id, now)
        dao.insertObject(
            CalendarEntity(id = shapeId, parentId = layerId, boundingBox = shapeRender.boundingBox.toBoundingBoxJson(),
                sortOrder = 0, createdAt = now, updatedAt = now, type = TYPE_SHAPE, data = shapeObj.toJson())
        )
        withContext(Dispatchers.Main) {
            val erasedSet = setOf(stroke.id)
            drawingView.setStrokeListSilently(drawingView.getStrokes().filter { it.id !in erasedSet })
            persistedStrokeIds.removeAll(erasedSet)
            drawingView.loadShapeObjects(drawingView.getShapeObjects() + shapeRender)
            rebuildCanvas()
            if (!isLassoMode) enterLassoMode()
            isSmartLassoSession = true
            val pad = 8f * density
            val selBox = RectF(shapeRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(shapeId), selBox)
            selectedObjectIds.clear(); selectedObjectIds.add(shapeId)
            updateFloatingSelectionToolbar(selBox)
            pushHistory()
        }
    }

    private suspend fun persistShapeTransform(before: ShapeRender, after: ShapeRender) {
        if (before.centerX == after.centerX && before.centerY == after.centerY &&
            before.width == after.width && before.height == after.height &&
            before.rotationDeg == after.rotationDeg && before.aspectLocked == after.aspectLocked) return
        val now = System.currentTimeMillis()
        NotesproutIndex.calendarDao().updateObjectData(after.id, after.boundingBox.toBoundingBoxJson(), after.toShapeObject(density).toJson(), now)
        withContext(Dispatchers.Main) {
            drawingView.loadShapeObjects(drawingView.getShapeObjects().map { if (it.id == after.id) after else it })
            rebuildCanvas()
            if (isLassoMode && after.id in selectedObjectIds) {
                val pad = 8f * density
                val selBox = RectF(after.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            pushHistory()
        }
    }

    private fun enterShapeTransformMode(shape: ShapeRender) {
        isShapeTransformMode = true
        drawingView.setLassoOverlay(null, null)
        drawingView.enterShapeTransform(shape)
        hideFloatingSelectionToolbar()
        showShapeTransformButtons(shape.boundingBox, shape)
        val shapesExcluded = drawingView.getShapeObjects().filter { it.id != shape.id }
        val strokes = drawingView.getStrokes()
        val headings = drawingView.getHeadings()
        val texts = drawingView.getTextObjects()
        val lines = drawingView.getLineObjects()
        val links = drawingView.getLinks()
        val stickyNotes = drawingView.getStickyNotes()
        val template = currentTemplateBitmap
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, template, headings, texts, lines, links, stickyNotes = stickyNotes, shapeObjects = shapesExcluded)
            }
            if (bitmap != null && isShapeTransformMode) drawingView.loadStrokesWithBitmap(strokes, bitmap, template)
        }
    }

    private fun exitShapeTransformMode(clearSelection: Boolean = false) {
        val finalRender = drawingView.getShapeTransformWorkingRender()
        isShapeTransformMode = false
        drawingView.exitShapeTransform()
        hideShapeTransformButtons()
        if (clearSelection || finalRender == null) {
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession) { exitLassoMode(); drawingView.enableDrawing() }
        } else {
            val pad = 8f * density
            val selBox = RectF(finalRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            updateFloatingSelectionToolbar(selBox)
        }
        if (finalRender != null) {
            drawingView.loadShapeObjects(drawingView.getShapeObjects().map { if (it.id == finalRender.id) finalRender else it })
        }
        lifecycleScope.launch { rebuildCanvas() }
    }

    private fun showShapeTransformButtons(selBox: RectF, shape: ShapeRender? = null) {
        val working = shape ?: drawingView.getShapeTransformWorkingRender()
        if (working != null) updateShapeTransformToggle(working)
        binding.btnLassoCopy.visibility = View.GONE
        binding.btnLassoCut.visibility = View.GONE
        binding.btnLassoDelete.visibility = View.GONE
        binding.btnLassoSendToNotebook.visibility = View.GONE
        binding.btnConvertShape.visibility = View.GONE
        binding.shapeConvertDivider.visibility = View.GONE
        binding.btnShapeAspectLock.visibility = View.VISIBLE
        binding.btnShapeTransformDone.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post { positionFloatingToolbar(selBox) }
    }

    private fun hideShapeTransformButtons() {
        binding.btnShapeAspectLock.visibility = View.GONE
        binding.btnShapeTransformDone.visibility = View.GONE
        binding.btnLassoCopy.visibility = View.VISIBLE
        binding.btnLassoCut.visibility = View.VISIBLE
        binding.btnLassoDelete.visibility = View.VISIBLE
        binding.btnLassoSendToNotebook.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    private fun updateShapeTransformToggle(shape: ShapeRender) {
        val isLocked = shape.aspectLocked
        binding.btnShapeAspectLock.text = if (shape.type == ShapeType.ELLIPSE) {
            if (isLocked) "Circle" else "Oval"
        } else {
            if (isLocked) "Locked" else "Lock ratio"
        }
        binding.btnShapeAspectLock.isSelected = isLocked
    }

    // ── Floating selection toolbar ───────────────────────────────────────────────

    private fun updateLassoButtonIcon() {
        binding.btnDayLasso.setImageResource(
            if (NotesproutClipboard.hasContent()) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
        )
    }

    private fun updateFloatingSelectionToolbar(selectionBox: RectF) {
        val selStrokes = drawingView.getStrokes().filter { it.id in drawingView.lassoSelectedIds }
        val isSingleStrokeOnly = drawingView.lassoSelectedIds.size == 1 && selStrokes.size == 1
        shapeConvertJob?.cancel()
        if (isSingleStrokeOnly) {
            val stroke = selStrokes.first()
            if (shapeConvertStroke?.id != stroke.id) {
                binding.btnConvertShape.visibility = View.GONE
                binding.shapeConvertDivider.visibility = View.GONE
                shapeConvertStroke = stroke
                shapeConvertResult = null
                shapeConvertJob = lifecycleScope.launch(Dispatchers.IO) {
                    val result = ShapeRecognizer.recognize(stroke.points, density)
                    withContext(Dispatchers.Main) {
                        if (shapeConvertStroke?.id == stroke.id) {
                            shapeConvertResult = result
                            val visible = if (result != null) View.VISIBLE else View.GONE
                            binding.btnConvertShape.visibility = visible
                            binding.shapeConvertDivider.visibility = visible
                        }
                    }
                }
            }
        } else {
            shapeConvertStroke = null
            shapeConvertResult = null
            binding.btnConvertShape.visibility = View.GONE
            binding.shapeConvertDivider.visibility = View.GONE
        }
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post { positionFloatingToolbar(selectionBox) }
    }

    private fun hideFloatingSelectionToolbar() {
        binding.floatingSelectionToolbar.visibility = View.GONE
    }

    private fun positionFloatingToolbar(selectionBox: RectF) {
        val view = binding.floatingSelectionToolbar
        val viewW = view.measuredWidth.toFloat()
        val viewH = view.measuredHeight.toFloat()
        val containerW = binding.dayContent.width.toFloat()
        val containerH = binding.dayContent.height.toFloat()
        val dpGap = 8f * density
        var x = selectionBox.centerX() - viewW / 2f
        var y = selectionBox.bottom + dpGap
        if (y + viewH > containerH) y = selectionBox.top - dpGap - viewH
        y = y.coerceIn(0f, (containerH - viewH).coerceAtLeast(0f))
        x = x.coerceIn(0f, (containerW - viewW).coerceAtLeast(0f))
        view.x = x
        view.y = y
    }

    // ── Snapshot persistence ─────────────────────────────────────────────────────

    private suspend fun persistSnapshot(pageId: String, snapshot: String) {
        val dao = NotesproutIndex.calendarDao()
        val row = dao.getObjectById(pageId) ?: return
        val updated = PageData.fromJson(row.data).copy(snapshot = snapshot).toJson()
        dao.updateData(pageId, updated, System.currentTimeMillis())
    }

    // ── Touch dispatch ───────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Canvas gestures (sticky-tap, multi-finger undo/redo, EPD release) apply only to the
        // editable Note canvas; in Notebooks/History the list handles its own touches.
        if (viewMode == ViewMode.NOTE && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            val inToolbar = isTouchInView(event, binding.dayToolbar)
            val inFloating = binding.floatingSelectionToolbar.visibility == View.VISIBLE &&
                    isTouchInView(event, binding.floatingSelectionToolbar)
            val inShapeToolbar = binding.dayShapeInsertToolbar.visibility == View.VISIBLE &&
                    isTouchInView(event, binding.dayShapeInsertToolbar)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (inToolbar || inFloating || inShapeToolbar) drawingView.releaseRender()
                // Dismiss the shape-insert popup when tapping outside it (and outside its trigger button).
                if (binding.dayShapeInsertToolbar.visibility == View.VISIBLE &&
                    !inShapeToolbar && !isTouchInView(event, binding.btnDayInsertShape)) {
                    hideShapeInsertToolbar()
                }
            }
            if (handleStickyNoteTapGesture(event)) return true
            if (!inToolbar && !inFloating && !inShapeToolbar) {
                handleMultiFingerDoubleTap(event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun handleMultiFingerDoubleTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mfTapPeakCount = 1
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val count = event.pointerCount
                if (count > mfTapPeakCount) mfTapPeakCount = count
                if (count >= 4) {
                    mfTapArmed = false
                    mfTapMoved = true
                    return
                }
                if (!mfTapArmed) {
                    mfTapArmed = true
                    mfTapDownTime = event.eventTime
                }
                mfTapCentroidStartX = (0 until count).map { event.getX(it) }.average().toFloat()
                mfTapCentroidStartY = (0 until count).map { event.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mfTapArmed && !mfTapMoved && event.pointerCount >= 2) {
                    val count = event.pointerCount
                    val cx = (0 until count).map { event.getX(it) }.average().toFloat()
                    val cy = (0 until count).map { event.getY(it) }.average().toFloat()
                    val dx = cx - mfTapCentroidStartX
                    val dy = cy - mfTapCentroidStartY
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx) mfTapMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mfTapArmed && !mfTapMoved) {
                    val duration = event.eventTime - mfTapDownTime
                    if (duration <= ViewConfiguration.getLongPressTimeout()) {
                        evaluateMultiFingerTap(event.eventTime)
                    }
                }
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mfTapArmed && !mfTapMoved && mfTapPeakCount == 3) {
                    evaluateMultiFingerTap(SystemClock.uptimeMillis(), threeOnly = true)
                } else {
                    twoFingerTapFirstTime = 0L
                    threeFingerTapFirstTime = 0L
                }
                mfTapArmed = false
                mfTapMoved = false
            }
        }
    }

    private fun evaluateMultiFingerTap(now: Long, threeOnly: Boolean = false) {
        when (if (threeOnly) 3 else mfTapPeakCount) {
            2 -> {
                val withinTime = twoFingerTapFirstTime != 0L &&
                    now - twoFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = twoFingerTapFirstTime != 0L && hypot(
                    (mfTapCentroidStartX - twoFingerTapFirstX).toDouble(),
                    (mfTapCentroidStartY - twoFingerTapFirstY).toDouble(),
                ) <= doubleTapSlopPx
                if (withinTime && withinSlop) {
                    twoFingerTapFirstTime = 0L
                    drawingView.releaseRender()
                    undo()
                } else {
                    twoFingerTapFirstTime = now
                    twoFingerTapFirstX = mfTapCentroidStartX
                    twoFingerTapFirstY = mfTapCentroidStartY
                }
            }
            3 -> {
                val withinTime = threeFingerTapFirstTime != 0L &&
                    now - threeFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = threeFingerTapFirstTime != 0L && hypot(
                    (mfTapCentroidStartX - threeFingerTapFirstX).toDouble(),
                    (mfTapCentroidStartY - threeFingerTapFirstY).toDouble(),
                ) <= doubleTapSlopPx
                if (withinTime && withinSlop) {
                    threeFingerTapFirstTime = 0L
                    drawingView.releaseRender()
                    redo()
                } else {
                    threeFingerTapFirstTime = now
                    threeFingerTapFirstX = mfTapCentroidStartX
                    threeFingerTapFirstY = mfTapCentroidStartY
                }
            }
        }
    }

    private fun isTouchInView(event: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX; val y = event.rawY
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    private fun handleStickyNoteTapGesture(event: MotionEvent): Boolean {
        val containerLoc = IntArray(2)
        binding.dayContent.getLocationInWindow(containerLoc)
        val localX = event.x - containerLoc[0]
        val localY = event.y - containerLoc[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stickyNoteTapDownX = localX; stickyNoteTapDownY = localY
                stickyNoteTapDownTime = event.eventTime; stickyNoteTapMoved = false
                stickyNoteTapCandidate = stickyNoteAt(localX, localY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { stickyNoteTapMoved = true; stickyNoteTapCandidate = null }
            MotionEvent.ACTION_MOVE -> {
                if (!stickyNoteTapMoved && stickyNoteTapCandidate != null) {
                    if (hypot((localX - stickyNoteTapDownX).toDouble(), (localY - stickyNoteTapDownY).toDouble()) > touchSlopPx) {
                        stickyNoteTapMoved = true; stickyNoteTapCandidate = null
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val candidate = stickyNoteTapCandidate
                stickyNoteTapCandidate = null
                val isTap = candidate != null && !stickyNoteTapMoved && event.pointerCount == 1 &&
                        event.eventTime - stickyNoteTapDownTime <= ViewConfiguration.getLongPressTimeout()
                if (isTap && candidate != null && candidate.boundingBox.contains(localX, localY)) {
                    if (isLassoMode) exitLassoMode()
                    openStickyNote(candidate, initialCreate = false)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { stickyNoteTapMoved = true; stickyNoteTapCandidate = null }
        }
        return false
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (viewMode == ViewMode.NOTE) drawingView.enableDrawing()
        // Returning from an opened notebook: refresh the card grid (it may have new activity) but
        // keep the reader on the same page. History▸Notes is a static bitmap — nothing to refresh.
        else if (!(viewMode == ViewMode.HISTORY && historySub == HistSub.NOTES)) renderList(resetPage = false)
        updateLassoButtonIcon()
    }

    override fun onPause() {
        super.onPause()
        // appScope (not lifecycleScope): the save must complete even as the Activity is paused
        // and torn down. lifecycleScope would be cancelled at onDestroy, dropping the write.
        NotesproutApplication.appScope.launch { saveStrokes() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final safety-net flush on appScope (outlives this Activity). releaseResources only
        // recycles bitmaps, so the async getStrokes() read stays valid.
        NotesproutApplication.appScope.launch { saveStrokes() }
        drawingView.releaseResources()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
