package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.ImageCodec
import com.notesprout.android.core.TopGuard
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.CalendarExportPage
import com.notesprout.android.data.CalendarRepository
import com.notesprout.android.data.EventsRepository
import com.notesprout.android.data.events.EventType
import com.notesprout.android.data.ScratchpadPageContent
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.pageData
import com.notesprout.android.data.ShapeObject
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.index.updateColumns
import com.notesprout.android.data.toRow
import com.notesprout.android.data.ShapeType
import com.notesprout.android.data.StickyNoteObject
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TYPE_SHAPE
import com.notesprout.android.data.TYPE_STICKY_NOTE
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.index.CalendarEntity
import com.notesprout.android.data.index.toNotebookObject
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.insertCalendarPagesIntoNotebook
import com.notesprout.android.data.loadNotebookPageIds
import com.notesprout.android.data.soilFile
import com.notesprout.android.data.toBoundingBoxJson
import com.notesprout.android.data.toLinkObject
import com.notesprout.android.data.toStickyNoteObject
import com.notesprout.android.data.translate
import com.notesprout.android.data.deepCopy
import com.notesprout.android.databinding.ActivityCalendarBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.CalendarTemplateRenderer
import com.notesprout.android.notebook.CalendarTemplateRenderer.CalView
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.STICKY_NOTE_ICON_SIZE_DP
import com.notesprout.android.notebook.ShapeRecognizer
import com.notesprout.android.notebook.ToolPreferencesManager
import com.notesprout.android.notebook.ToolbarOverflowManager
import com.notesprout.android.notebook.PenColorPreferences
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import com.notesprout.android.notebook.PenColorPanelController
import com.notesprout.android.notebook.CustomColorDialog

/**
 * Calendar with handwriting on every view. Each view is a template-backed drawing canvas keyed by a
 * deterministic page id: month = one page per month, week = one page per week (Sunday), day = two
 * pages per day (AM / PM). Content lives in the `calendar` table of notesprout.db (encrypted at rest) and
 * shares the universal object model + clipboard with notebooks, scratch pad, sticky notes & shapes.
 *
 * The grid/timeline is drawn into a template bitmap behind the ink (see [CalendarTemplateRenderer]).
 * Stylus draws; finger-tap selects/navigates a day (reserved for future day-notes); finger-swipe
 * steps the period. Modeled on [ScratchpadActivity]'s canvas machinery.
 */
class CalendarActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_NOTEBOOK_ID        = "from_notebook_id"
        const val EXTRA_FROM_NOTEBOOK_NAME      = "from_notebook_name"

        /**
         * Result extra (String, page UUID): set when the user exports calendar page(s) into the
         * notebook they launched the calendar from and chooses "Open" — [NotebookActivity] reloads
         * its page list and navigates to this page.
         */
        const val EXTRA_RESULT_GOTO_PAGE_ID = "cal_result_goto_page_id"

        // One-finger period-step gesture thresholds. Kept identical to NotebookActivity's
        // page-turn gate so navigating the calendar feels the same as turning a page.
        private const val PAGE_SWIPE_MIN_DISTANCE_FRAC  = 0.30f  // min |dx| to qualify at all
        private const val PAGE_SWIPE_LONG_DISTANCE_FRAC = 0.50f  // |dx| that qualifies regardless of velocity
        private const val PAGE_SWIPE_MIN_VELOCITY_MULT  = 1.0f   // x scaledMinimumFlingVelocity

        // Last-position persistence (view + date restored on next open).
        private const val PREFS_CALENDAR = "calendar_state"
        private const val KEY_VIEW       = "last_view"
        private const val KEY_DATE       = "last_date"
        private const val KEY_CAL_YEAR   = "last_cal_year"
        private const val KEY_CAL_MONTH  = "last_cal_month"
        private const val KEY_DAY_HALF   = "last_day_half"

        fun launch(context: Context) {
            context.startActivity(Intent(context, CalendarActivity::class.java))
        }

        /** Intent for launching from a notebook (used with NotebookActivity's for-result launcher). */
        fun intentFromNotebook(context: Context, id: String, name: String): Intent =
            Intent(context, CalendarActivity::class.java)
                .putExtra(EXTRA_FROM_NOTEBOOK_ID, id)
                .putExtra(EXTRA_FROM_NOTEBOOK_NAME, name)
    }

    private lateinit var binding: ActivityCalendarBinding

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""
    private lateinit var overflowManager: ToolbarOverflowManager
    // True between a press inside the open overflow menu and its release, so the menu closes only
    // after the tapped item's click has been dispatched.
    private var pendingMenuTapClose = false
    private lateinit var drawingView: NotebookView

    /** Pen-colour swatch panel docked to [btnCalPen]. See [togglePenColorPanel]. */
    private lateinit var penColorPanel: PenColorPanelController
    private lateinit var repository: CalendarRepository
    private val eventsRepo: EventsRepository by lazy { EventsRepository() }
    // Events for the visible period, keyed by day — baked into the grid template. Reloaded on
    // period/view change and on return from the day window (events may have been added/removed).
    private var eventsByDay: Map<LocalDate, List<CalendarTemplateRenderer.DayEvent>> = emptyMap()
    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    /** Whether the in-flight page export should copy the view's writing (true) or only the grid template. */
    private var pendingExportIncludeContent = false

    // Navigation state
    private var currentView = CalView.MONTH
    private var selectedDate: LocalDate = LocalDate.now()
    private var calYear: Int = LocalDate.now().year
    private var calMonth: Int = LocalDate.now().monthValue
    private var dayHalf = 0  // 0 = AM (00–12), 1 = PM (12–24)

    // Canvas / page state
    private var currentPageId = ""
    private var currentLayerId = ""
    // Thread-safe: mutated from main-thread coroutines (navigation/erase) and IO save coroutines.
    private val persistedStrokeIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Serializes saveStrokes() so concurrent callers (rapid pen-lifts, onPause, navigation,
    // onDestroy) can't race the persistedStrokeIds read-modify-write and double-insert / drop.
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

    // Source notebook (non-null only when launched from a notebook)
    private var fromNotebookId: String? = null
    private var fromNotebookName: String? = null

    // Undo/redo — full-layer snapshot history (per page; reset on navigation)
    private var currentSnapshot: List<CalendarEntity> = emptyList()
    private val undoStack = ArrayDeque<List<CalendarEntity>>()
    private val redoStack = ArrayDeque<List<CalendarEntity>>()
    private val historyCap = 50

    // Send-to-notebook hand-off content awaiting a picker result
    private var pendingSendContent: NotesproutClipboard.ClipboardContent? = null

    // Sticky note editor state
    private var pendingStickyNote: StickyNoteRender? = null
    private var pendingStickyInitialCreate = false

    // Finger gesture tracking (calendar nav)
    private var calDownX = 0f
    private var calDownY = 0f
    private var calDownTime = 0L
    private var calMoved = false
    private var calMultiTouch = false
    private var calVelocityTracker: VelocityTracker? = null

    // Single-finger double-tap on a day cell (Month/Week) → open that day's full-page canvas.
    private var lastDayTapDate: LocalDate? = null
    private var lastDayTapTime = 0L

    // Single-finger long-press → that day's notebook list (see showDayNotebooks).
    private var calLongPressRunnable: Runnable? = null
    private var calLongPressFired = false
    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val doubleTapSlopPx by lazy { ViewConfiguration.get(this).scaledDoubleTapSlop }

    // Multi-finger double-tap: 2-finger = undo, 3-finger = redo (mirrors NotebookActivity).
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

    /** True while finger gestures are suppressed because the pen owns the surface. */
    private var fingerGesturesSuppressed = false

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

    private val exportNotebookPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val destId   = data.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_ID) ?: return@registerForActivityResult
        val destName = data.getStringExtra(NotebookPickerActivity.RESULT_NOTEBOOK_NAME) ?: ""
        beginExport(destId, destName, pendingExportIncludeContent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bounceIfIndexNotReady()) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Immersive: system bars are hidden, so their inset is 0. Reserve the guard
        // explicitly so the top bar isn't parked in the status bar's reveal zone.
        TopGuard.applyRootPadding(binding.root)

        repository = CalendarRepository(NotesproutIndex.db(), NotesproutIndex.calendarDao())

        fromNotebookId        = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_ID)
        fromNotebookName      = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_NAME)

        // Record the calendar on the surface stack, so a cold launch reopens it (see SurfaceStack).
        // No payload: the view + date come back from PREFS_CALENDAR below.
        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN) ?: UUID.randomUUID().toString()
        // Restore an in-flight sticky-editor session (see onSaveInstanceState).
        savedInstanceState?.getString(StickyNoteEditorTransfer.STATE_PENDING_NOTE)?.let {
            pendingStickyNote = StickyNoteEditorTransfer.decodeNote(it)
            pendingStickyInitialCreate = savedInstanceState.getBoolean(StickyNoteEditorTransfer.STATE_PENDING_CREATE, false)
        }
        SurfaceStack.attach(this, surfaceEntry())

        // Restore the last-used view + date so the calendar reopens where the user left off.
        // Falls back to today's month view (AM/PM by clock) on a fresh install.
        val today = LocalDate.now()
        val prefs = getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE)
        currentView = runCatching { CalView.valueOf(prefs.getString(KEY_VIEW, "") ?: "") }
            .getOrDefault(CalView.MONTH)
        selectedDate = runCatching { LocalDate.parse(prefs.getString(KEY_DATE, "") ?: "") }
            .getOrDefault(today)
        calYear = prefs.getInt(KEY_CAL_YEAR, selectedDate.year)
        calMonth = prefs.getInt(KEY_CAL_MONTH, selectedDate.monthValue)
        dayHalf = prefs.getInt(KEY_DAY_HALF, if (LocalTime.now().hour >= 12) 1 else 0)

        // Drawing view
        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        binding.calendarContent.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        binding.floatingSelectionToolbar.bringToFront()
        binding.calOverflowMenu.bringToFront()

        setupToolbar()
        setupOverflow()
        wireDrawingCallbacks()
        wireToolButtons()
        updateLassoButtonIcon()

        // Restore the pen's ink colour — one global value, the same way the active tool below is.
        drawingView.setPenColor(PenColorPreferences.load(this))
        penColorPanel = PenColorPanelController(
            root          = binding.calendarContent,
            panel         = binding.penColorPanel.root,
            anchor        = binding.btnCalPen,
            paletteGreyButton  = binding.penColorPanel.btnPaletteGrey,
            paletteColorButton = binding.penColorPanel.btnPaletteColor,
            swatchRow1    = binding.penColorPanel.penSwatchRow1,
            swatchRow2    = binding.penColorPanel.penSwatchRow2,
            customDivider = binding.penColorPanel.penCustomDivider,
            customRow     = binding.penColorPanel.penCustomRow,
            sideProvider  = { PenColorPanelController.Side.BELOW },
            boundsProvider   = { Rect(0, 0, binding.calendarContent.width, binding.calendarContent.height) },
            topGuardProvider = { 0 },
            onColorChosen     = { hex -> applyPenColor(hex) },
            onSlotEditRequested = { index, initial -> showCustomColorDialog(index, initial) },
            onPaletteChanged  = { PenColorPreferences.savePalette(this, it) },
            onVisibilityChanged = { pushPenPanelExclusion() },
        )
        applyPenTintToButton()
        PenColorPreferences.addListener(penColorListener)
        // Restore last-used tool
        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnCalPen.isSelected = false
                binding.btnCalEraser.isSelected = true
            }
            else -> {
                binding.btnCalPen.isSelected = true
                binding.btnCalEraser.isSelected = false
            }
        }

        binding.calendarContent.doOnLayout {
            lifecycleScope.launch { repository.ensureBootstrap(); navigateCanvas(firstLoad = true) }
        }
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCalHome.setOnClickListener { goToLibrary(newNotebook = false) }
        binding.btnCalNewNotebook.setOnClickListener { goToLibrary(newNotebook = true) }
        binding.btnMonthView.setOnClickListener { switchView(CalView.MONTH) }
        binding.btnWeekView.setOnClickListener { switchView(CalView.WEEK) }
        binding.btnDayView.setOnClickListener { switchView(CalView.DAY) }
        binding.btnToday.setOnClickListener { goToToday() }
        binding.btnPrev.setOnClickListener { stepBack() }
        binding.btnNext.setOnClickListener { stepForward() }
        binding.tvMonthYear.setOnClickListener { showMonthYearPicker() }
        binding.btnCalScratchpad.setOnClickListener { openScratchpad() }
        binding.btnCalTasks.setOnClickListener { TasksActivity.launch(this) }
        binding.btnCalSendPage.setOnClickListener { sendPageToNotebook() }
        binding.btnCalErasePage.setOnClickListener { confirmErasePage() }
        binding.btnCalUndo.setOnClickListener { undo() }
        binding.btnCalRedo.setOnClickListener { redo() }
        syncViewToggles()
        updateMonthYearLabel()
    }

    private fun syncViewToggles() {
        binding.btnMonthView.isSelected = currentView == CalView.MONTH
        binding.btnWeekView.isSelected  = currentView == CalView.WEEK
        binding.btnDayView.isSelected   = currentView == CalView.DAY
    }

    // ── Toolbar overflow (reuses the notebook's ToolbarOverflowManager over calLeftBar) ──────────

    private fun setupOverflow() {
        overflowManager = ToolbarOverflowManager(
            toolbar = binding.calLeftBar,
            overflowMenu = binding.calOverflowMenu,
            dividerOverflow = binding.dividerCalOverflow,
            btnOverflow = binding.btnCalOverflow,
        )
        binding.btnCalOverflow.setOnClickListener {
            if (overflowManager.isOverflowMenuOpen()) closeCalOverflowMenu() else openCalOverflowMenu()
        }
        // The text-toggle buttons (Today/Month/Week/Day) are wrap_content; the overflow manager sizes
        // by LayoutParams px, so pin their measured widths once before the first fit pass.
        binding.calLeftBar.doOnLayout {
            pinToggleWidths()
            overflowManager.initialize()
            overflowManager.recalc()
        }
        binding.calLeftBar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) { // bar width changed (rotation) — refit
                if (overflowManager.isOverflowMenuOpen()) closeCalOverflowMenu()
                overflowManager.recalc()
            }
        }
    }

    /**
     * Freezes the text toggles' natural widths into their LayoutParams, since the overflow manager
     * sizes by LayoutParams px and wrap_content reads as 0 there.
     *
     * Measured UNSPECIFIED rather than read from [View.getWidth]: a horizontal LinearLayout measures
     * each child against the space its siblings left over, so on a bar too narrow to fit everything
     * the toggle straddling the cut lands truncated (it's clipped at the edge anyway). Pinning that
     * width froze it permanently, and the button carried the stub into the overflow menu with no room
     * left to draw its label — "Week" showed as an empty pill on G6, where the cut fell on that button.
     */
    private fun pinToggleWidths() {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (btn in listOf(binding.btnToday, binding.btnMonthView, binding.btnWeekView, binding.btnDayView)) {
            val lp = btn.layoutParams as LinearLayout.LayoutParams
            if (lp.width >= 0) continue // already pinned — natural width doesn't change
            btn.measure(unspecified, unspecified)
            val w = btn.measuredWidth
            if (w > 0) { lp.width = w; btn.layoutParams = lp }
        }
    }

    private fun openCalOverflowMenu() {
        overflowManager.openOverflowMenu()
        binding.calOverflowMenu.bringToFront()
        // EPD: keep the pen from drawing under the menu overlay (also lets stylus taps reach its buttons).
        drawingView.setToolbarExclusion(
            Rect(0, 0, drawingView.asView().width, overflowManager.expectedOverflowMenuExtent()),
        )
        drawingView.releaseRender()
    }

    private fun closeCalOverflowMenu() {
        overflowManager.closeOverflowMenu()
        drawingView.setToolbarExclusion(null)
        drawingView.releaseRender()
    }

    private fun updateMonthYearLabel() {
        binding.tvMonthYear.text = if (currentView == CalView.DAY) {
            val monthName = Month.of(selectedDate.monthValue).getDisplayName(TextStyle.FULL, Locale.getDefault())
            val half = if (dayHalf == 0) "AM" else "PM"
            "${selectedDate.dayOfMonth} $monthName ${selectedDate.year} ($half)"
        } else {
            val monthName = Month.of(calMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
            "$monthName $calYear"
        }
    }

    private fun switchView(view: CalView) {
        if (currentView == view) return
        currentView = view
        calYear = selectedDate.year
        calMonth = selectedDate.monthValue
        syncViewToggles()
        updateMonthYearLabel()
        navigateCanvas()
    }

    private fun stepBack() {
        when (currentView) {
            CalView.MONTH -> { calMonth--; if (calMonth < 1) { calMonth = 12; calYear-- } }
            CalView.WEEK -> { selectedDate = selectedDate.minusDays(7); calYear = selectedDate.year; calMonth = selectedDate.monthValue }
            CalView.DAY -> {
                if (dayHalf == 1) dayHalf = 0
                else { dayHalf = 1; selectedDate = selectedDate.minusDays(1); calYear = selectedDate.year; calMonth = selectedDate.monthValue }
            }
        }
        updateMonthYearLabel()
        navigateCanvas()
    }

    private fun stepForward() {
        when (currentView) {
            CalView.MONTH -> { calMonth++; if (calMonth > 12) { calMonth = 1; calYear++ } }
            CalView.WEEK -> { selectedDate = selectedDate.plusDays(7); calYear = selectedDate.year; calMonth = selectedDate.monthValue }
            CalView.DAY -> {
                if (dayHalf == 0) dayHalf = 1
                else { dayHalf = 0; selectedDate = selectedDate.plusDays(1); calYear = selectedDate.year; calMonth = selectedDate.monthValue }
            }
        }
        updateMonthYearLabel()
        navigateCanvas()
    }

    private fun goToToday() {
        val today = LocalDate.now()
        selectedDate = today
        calYear = today.year
        calMonth = today.monthValue
        updateMonthYearLabel()
        navigateCanvas()
    }

    private fun showMonthYearPicker() {
        DayPickerDialog.show(this, selectedDate) { date ->
            selectedDate = date
            calYear = date.year
            calMonth = date.monthValue
            updateMonthYearLabel()
            navigateCanvas()
        }
    }

    // ── Page key / spec ────────────────────────────────────────────────────────

    private fun currentSpec() = CalendarTemplateRenderer.Spec(
        view = currentView, calYear = calYear, calMonth = calMonth,
        selectedDate = selectedDate, dayHalf = dayHalf, today = LocalDate.now(),
    )

    private fun pageKey(): String = when (currentView) {
        // Locale.ROOT: default-locale format() writes Eastern-Arabic digits on ar/fa/bn locales,
        // orphaning every month page written before a device-language switch (week/day keys use
        // ISO LocalDate.toString() and were never affected).
        CalView.MONTH -> "cal-month-%04d-%02d".format(java.util.Locale.ROOT, calYear, calMonth)
        CalView.WEEK -> "cal-week-${sundayOf(selectedDate)}"
        CalView.DAY -> "cal-day-$selectedDate-${if (dayHalf == 0) "AM" else "PM"}"
    }

    private fun sundayOf(date: LocalDate): LocalDate {
        val dow = date.dayOfWeek.value % 7
        return date.minusDays(dow.toLong())
    }

    // ── Canvas navigation / load ───────────────────────────────────────────────

    private fun navigateCanvas(firstLoad: Boolean = false) {
        lifecycleScope.launch {
            // Flush the leaving page on appScope so the write completes even if this Activity is
            // destroyed mid-navigation; join so we don't clear persistedStrokeIds before it lands.
            NotesproutApplication.appScope.launch { saveStrokes() }.join()
            if (isLassoMode) clearLassoSelectionForNavigation()
            if (isShapeTransformMode) { isShapeTransformMode = false; drawingView.exitShapeTransform(); hideShapeTransformButtons() }
            val (pid, lid) = repository.getOrCreatePageLayer(pageKey())
            currentPageId = pid
            currentLayerId = lid
            persistedStrokeIds.clear()
            if (!firstLoad) drawingView.clearForPageLoad()
            loadEventsForView()
            renderTemplateBitmap()
            loadCanvasContent()
            initHistory()
            if (isLassoMode) drawingView.setLassoMode(true)
        }
    }

    private fun renderTemplateBitmap() {
        val w = binding.calendarContent.width
        val h = binding.calendarContent.height
        currentTemplateBitmap = if (w > 0 && h > 0) {
            CalendarTemplateRenderer.render(currentSpec(), w, h, density, eventsByDay = eventsByDay)
        } else null
    }

    /** Load the visible period's events into [eventsByDay] (baked into the grid on the next render). */
    private suspend fun loadEventsForView() {
        val (start, end) = when (currentView) {
            CalView.MONTH -> {
                val firstDay = java.time.YearMonth.of(calYear, calMonth).atDay(1)
                val first = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong()) // grid's Sunday
                first to first.plusDays(41)                                             // 6×7 cells
            }
            CalView.WEEK -> sundayOf(selectedDate).let { it to it.plusDays(6) }
            CalView.DAY -> selectedDate to selectedDate
        }
        eventsByDay = eventsRepo.eventsForRange(start, end).mapValues { (_, rows) ->
            rows.map {
                CalendarTemplateRenderer.DayEvent(it.title, it.allDay, it.startMinute, iconFor(it.type))
            }
        }
    }

    /** Maps an event type name to its Month/Week cell glyph. */
    private fun iconFor(typeName: String): CalendarTemplateRenderer.EventIcon = when (EventType.fromName(typeName)) {
        EventType.BIRTHDAY -> CalendarTemplateRenderer.EventIcon.CAKE
        EventType.ANNIVERSARY -> CalendarTemplateRenderer.EventIcon.HEART
        EventType.VACATION -> CalendarTemplateRenderer.EventIcon.SUITCASE
        EventType.MEETING -> CalendarTemplateRenderer.EventIcon.PEOPLE
        EventType.APPOINTMENT -> CalendarTemplateRenderer.EventIcon.CLOCK
        EventType.OTHER -> CalendarTemplateRenderer.EventIcon.DOT
    }

    /** Re-render the template (e.g. selection changed) and rebuild the canvas with current content. */
    private fun refreshTemplate() {
        renderTemplateBitmap()
        lifecycleScope.launch { rebuildCanvas() }
    }

    private suspend fun loadCanvasContent() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val containerW = binding.calendarContent.width.toFloat()
        val containerH = binding.calendarContent.height.toFloat()

        val content = withContext(Dispatchers.IO) {
            if (containerW > 0 && containerH > 0) {
                val row = NotesproutIndex.calendarDao().getObjectById(pageId)
                val pd = row?.toNotebookObject()?.pageData()
                if (pd != null && (pd.width == 0f || pd.height == 0f)) {
                    repository.setPageSize(pageId, containerW, containerH)
                }
            }
            repository.loadPage(pageId, density)
        }

        val template = currentTemplateBitmap
        // GPU path: load objects first, then loadStrokesWithBitmap records the committed node from the
        // in-memory content (no off-thread buildRenderBitmap needed).
        drawingView.loadHeadings(content.headings)
        drawingView.loadTextObjects(content.textObjects)
        drawingView.loadLineObjects(content.lineObjects)
        drawingView.loadLinks(content.links)
        drawingView.loadStickyNotes(content.stickyNotes)
        drawingView.loadShapeObjects(content.shapeObjects)
        drawingView.loadStrokesWithBitmap(content.strokes, null, template)
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

    /** Capture the post-change layer state, pushing the prior state onto the undo stack. */
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
            // No eraseAll() — loadCanvasContent swaps the full render bitmap in one repaint,
            // avoiding the extra white-flash a separate eraseAll would cause on EPD.
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
                repository.persistMovedObjects(
                    currentLayerId, movedStrokes, movedHeadings, movedTextObjects,
                    movedLineObjects, movedLinks, movedStickyNotes, movedShapes, density,
                )
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
        binding.btnCalPen.setOnClickListener {
            val wasPenActive = binding.btnCalPen.isSelected
            if (isLassoMode) exitLassoMode()
            isEraserActive = false; isLassoEraserActive = false
            drawingView.setEraserMode(false); drawingView.setLassoEraserMode(false)
            binding.btnCalPen.isSelected = true
            binding.btnCalEraser.isSelected = false
            binding.btnCalLassoEraser.isSelected = false
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, ActiveTool.PEN)
            if (wasPenActive) togglePenColorPanel() else hidePenColorPanel()
        }
        binding.btnCalEraser.setOnClickListener {
            hidePenColorPanel()
            if (isLassoMode) exitLassoMode()
            isLassoEraserActive = false; drawingView.setLassoEraserMode(false)
            binding.btnCalLassoEraser.isSelected = false
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnCalEraser.isSelected = isEraserActive
            binding.btnCalPen.isSelected = !isEraserActive
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }
        binding.btnCalLassoEraser.setOnClickListener {
            hidePenColorPanel()
            if (isLassoMode) exitLassoMode()
            isEraserActive = false; drawingView.setEraserMode(false)
            isLassoEraserActive = !isLassoEraserActive
            drawingView.setLassoEraserMode(isLassoEraserActive)
            binding.btnCalLassoEraser.isSelected = isLassoEraserActive
            binding.btnCalPen.isSelected = !isLassoEraserActive
            binding.btnCalEraser.isSelected = false
            drawingView.releaseRender()
        }
        binding.btnCalLasso.setOnClickListener {
            hidePenColorPanel()
            if (!isLassoMode) enterLassoMode() else exitLassoMode()
            drawingView.releaseRender()
        }
        binding.btnCalStickyNote.setOnClickListener { lifecycleScope.launch { insertStickyNote() } }

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
        binding.btnCalLasso.isSelected = true
        binding.btnCalPen.isSelected = false
        binding.btnCalEraser.isSelected = false
        binding.btnCalLassoEraser.isSelected = false
    }

    private fun exitLassoMode() {
        isLassoMode = false
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
        drawingView.setLassoMode(false)
        binding.btnCalLasso.isSelected = false
        binding.btnCalPen.isSelected = !isEraserActive
        binding.btnCalEraser.isSelected = isEraserActive
        hideFloatingSelectionToolbar()
    }

    private fun clearLassoSelectionForNavigation() {
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.lassoSelectedIds = emptySet()
        drawingView.setLassoOverlay(null, null)
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
            strokes = strokes.map { it.deepCopy() },
            headings = headings.map { h ->
                HeadingStroke(h.id, RectF(h.boundingBox),
                    h.strokes.map { s -> s.deepCopy() },
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
        deleteIdsAndRefresh(allIds, returnToPen = true)
    }

    /**
     * Soft-delete [allIds], refresh the canvas, and clear the lasso selection.
     * When [returnToPen] is true and this was a smart-lasso session, step back out to the
     * pen tool once the delete completes — mirroring NotebookActivity's performLassoDelete.
     * Cut leaves [returnToPen] false so lasso mode persists for the paste workflow.
     */
    private fun deleteIdsAndRefresh(allIds: List<String>, returnToPen: Boolean = false) {
        val wasSmartLassoSession = isSmartLassoSession
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
            if (returnToPen && wasSmartLassoSession) {
                exitLassoMode()
                drawingView.enableDrawing()
            }
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
            binding.btnCalLasso.isSelected = true
            binding.btnCalPen.isSelected = false
            binding.btnCalEraser.isSelected = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
            pushHistory()
        }
    }

    /** Translate clipboard content by (dx,dy) and assign fresh UUIDs to every object. */
    /** Bundle a whole loaded page into a [NotesproutClipboard.ClipboardContent] so it can be run
     *  through [regenIds] (translate + fresh ids). The union box is only used by [regenIds]'s own
     *  bookkeeping; export serialization reads each object's own bbox. */
    private fun clipFromPage(c: ScratchpadPageContent): NotesproutClipboard.ClipboardContent {
        val box = RectF()
        c.strokes.forEach { box.union(it.boundingBox) }
        c.headings.forEach { box.union(it.boundingBox) }
        c.textObjects.forEach { box.union(it.boundingBox) }
        c.lineObjects.forEach { box.union(it.boundingBox) }
        c.links.forEach { box.union(it.boundingBox) }
        c.stickyNotes.forEach { box.union(it.boundingBox) }
        c.shapeObjects.forEach { box.union(it.boundingBox) }
        return NotesproutClipboard.ClipboardContent(
            strokes = c.strokes, headings = c.headings, boundingBox = box,
            textObjects = c.textObjects, lineObjects = c.lineObjects,
            links = c.links, stickyNotes = c.stickyNotes, shapeObjects = c.shapeObjects,
        )
    }

    private fun regenIds(clip: NotesproutClipboard.ClipboardContent, dx: Float, dy: Float): NotesproutClipboard.ClipboardContent {
        val newStrokes = clip.strokes.map { s ->
            s.copy(id = java.util.UUID.randomUUID().toString(), points = s.points.map { PointF(it.x + dx, it.y + dy) })
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy, h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s -> s.copy(id = java.util.UUID.randomUUID().toString(), points = s.points.map { PointF(it.x + dx, it.y + dy) }) },
                recognizedText = h.recognizedText, level = h.level,
            )
        }
        val newTexts = clip.textObjects.map { t ->
            TextRender(java.util.UUID.randomUUID().toString(),
                RectF(t.boundingBox.left + dx, t.boundingBox.top + dy, t.boundingBox.right + dx, t.boundingBox.bottom + dy), t.text, t.strokes)
        }
        val newLines = clip.lineObjects.map { l ->
            l.copy(id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy, l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                startX = l.startX + dx, startY = l.startY + dy, endX = l.endX + dx, endY = l.endY + dy)
        }
        val newLinks = clip.links.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newStickies = clip.stickyNotes.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newShapes = clip.shapeObjects.map { s ->
            s.copy(id = java.util.UUID.randomUUID().toString(), centerX = s.centerX + dx, centerY = s.centerY + dy,
                boundingBox = RectF(s.boundingBox.left + dx, s.boundingBox.top + dy, s.boundingBox.right + dx, s.boundingBox.bottom + dy))
        }
        return NotesproutClipboard.ClipboardContent(
            strokes = newStrokes, headings = newHeadings,
            boundingBox = RectF(clip.boundingBox.left + dx, clip.boundingBox.top + dy, clip.boundingBox.right + dx, clip.boundingBox.bottom + dy),
            textObjects = newTexts, lineObjects = newLines, links = newLinks, stickyNotes = newStickies, shapeObjects = newShapes,
        )
    }

    // ── Library navigation ────────────────────────────────────────────────────────

    /**
     * Jump to the notebook/folder library ([MainActivity]). Uses CLEAR_TOP + SINGLE_TOP so the
     * existing MainActivity (always the task root) is brought to front via onNewIntent, clearing
     * the calendar (and any notebook the calendar was opened from) off the stack.
     *
     * When [newNotebook] is true, MainActivity opens straight into the "pick a folder, then create"
     * new-notebook flow.
     */
    private fun goToLibrary(newNotebook: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (newNotebook) intent.putExtra(MainActivity.EXTRA_START_NEW_NOTEBOOK, true)
        startActivity(intent)
        finish()
    }

    // ── Scratch Pad ──────────────────────────────────────────────────────────────

    /** Open the scratch pad as its own window (matches the notebook's scratch pad launcher). */
    private fun openScratchpad() {
        startActivity(Intent(this, ScratchpadActivity::class.java))
    }

    // ── Erase page ──────────────────────────────────────────────────────────────

    private fun confirmErasePage() {
        if (currentLayerId.isEmpty()) return
        val hasContent = drawingView.getStrokes().isNotEmpty() || drawingView.getHeadings().isNotEmpty() ||
                drawingView.getTextObjects().isNotEmpty() || drawingView.getLineObjects().isNotEmpty() ||
                drawingView.getLinks().isNotEmpty() || drawingView.getStickyNotes().isNotEmpty() ||
                drawingView.getShapeObjects().isNotEmpty()
        if (!hasContent) { toast("Nothing to erase"); return }
        val label = when (currentView) {
            CalView.MONTH -> "this month"
            CalView.WEEK -> "this week"
            CalView.DAY -> "this ${if (dayHalf == 0) "AM" else "PM"} page"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Erase Page")
            .setMessage("Erase all writing on $label? This can be undone.")
            .setPositiveButton("Erase") { _, _ -> erasePage() }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun erasePage() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            withContext(Dispatchers.IO) {
                NotesproutIndex.calendarDao().softDeleteByParentId(layerId, System.currentTimeMillis())
            }
            persistedStrokeIds.clear()
            drawingView.eraseAll()
            loadCanvasContent()
            pushHistory()
        }
    }

    // ── Send to Notebook ────────────────────────────────────────────────────────

    private fun sendToNotebook() {
        val content = collectSelection(drawingView.lassoSelectedIds)
        if (content == null) { toast("Select objects first"); return }
        val fromId = fromNotebookId
        if (fromId != null) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Send to Notebook")
                .setPositiveButton(fromNotebookName ?: "This notebook") { _, _ ->
                    CalendarTransfer.pending = content
                    setResult(RESULT_OK)
                    finish()
                }
                .setNeutralButton("Other notebook…") { _, _ -> launchNotebookPicker(content, excludeId = fromId) }
                .setNegativeButton("Cancel", null)
                .show()
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
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
        // Hand the shared BOOX pen pipeline to the destination notebook cleanly before it opens its
        // own — otherwise this view's late onDestroy close is merely skipped by the ownership guard,
        // leaving a dangling raw-input session.
        drawingView.releaseForHandoff()
        startActivity(
            Intent(this, NotebookActivity::class.java)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, destId)
                .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, destName)
                .putExtra(NotebookActivity.EXTRA_PASTE_PENDING, true)
        )
        finish()
    }

    // ── Send page to notebook (export view as page) ──────────────────────────────

    /**
     * Export the current view (Month / Week, or both Day halves) into a notebook as new page(s).
     * The grid becomes each page's template; "With writing" also copies the view's content objects.
     * Neither mode creates a library template.
     */
    private fun sendPageToNotebook() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Send page to notebook")
            .setPositiveButton("With writing") { _, _ -> chooseExportDestination(includeContent = true) }
            .setNeutralButton("Template only") { _, _ -> chooseExportDestination(includeContent = false) }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    /** Pick the destination notebook (this-notebook prompt when launched from one; else the picker). */
    private fun chooseExportDestination(includeContent: Boolean) {
        val fromId = fromNotebookId
        if (fromId != null) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Send page to notebook")
                .setPositiveButton(fromNotebookName ?: "This notebook") { _, _ ->
                    beginExport(fromId, fromNotebookName ?: "This notebook", includeContent)
                }
                .setNeutralButton("Other notebook…") { _, _ -> launchExportPicker(includeContent, excludeId = fromId) }
                .setNegativeButton("Cancel", null)
                .show()
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        } else {
            launchExportPicker(includeContent, excludeId = null)
        }
    }

    private fun launchExportPicker(includeContent: Boolean, excludeId: String?) {
        pendingExportIncludeContent = includeContent
        val intent = Intent(this, NotebookPickerActivity::class.java)
        if (excludeId != null) intent.putExtra(NotebookPickerActivity.EXTRA_EXCLUDE_NOTEBOOK_ID, excludeId)
        exportNotebookPickerLauncher.launch(intent)
    }

    /** Resolve the destination key, load its pages, then prompt for the insert position. */
    private fun beginExport(destId: String, destName: String, includeContent: Boolean) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(destId) }
            val destPass: String? = if (info.encrypted) {
                KeySession.getFor(destId) ?: KeyResolver.resolveForOpen(this@CalendarActivity, destId, info)
            } else null
            if (info.encrypted && destPass == null) { toast("Notebook is locked"); return@launch }

            val destPath = soilFile(this@CalendarActivity, destId).absolutePath
            val pageIds = withContext(Dispatchers.IO) { loadNotebookPageIds(destPath, destPass) }
            if (pageIds == null) { toast("Couldn't open notebook"); return@launch }

            showInsertPositionPicker(destName, pageIds) { anchorPageId, before ->
                performExport(destId, destName, destPath, destPass, anchorPageId, before, includeContent)
            }
        }
    }

    /**
     * Position picker (mirrors the page-index copy/move flow): "End of notebook", or tap a page then
     * choose Before / After. [onChosen] is called with the anchor page id (null = append) + before.
     */
    private fun showInsertPositionPicker(
        destName: String,
        pageIds: List<String>,
        onChosen: (anchorPageId: String?, before: Boolean) -> Unit,
    ) {
        if (pageIds.isEmpty()) { onChosen(null, false); return }  // empty notebook → append

        val inkBlack = ContextCompat.getColor(this, R.color.inkBlack)
        val pad = (16 * density).toInt()
        val rowPadV = (14 * density).toInt()

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }

        var dlg: AlertDialog? = null

        fun addRow(label: String, bold: Boolean, onClick: () -> Unit) {
            if (list.childCount > 0) {
                list.addView(View(this).apply { setBackgroundColor(inkBlack) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()))
            }
            val tv = AppCompatTextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(inkBlack)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, rowPadV, pad, rowPadV)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            list.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        addRow("End of notebook", bold = true) { dlg?.dismiss(); onChosen(null, false) }
        pageIds.forEachIndexed { i, pid ->
            addRow("Page ${i + 1}", bold = false) {
                dlg?.dismiss()
                showBeforeAfterPicker(i + 1) { before -> onChosen(pid, before) }
            }
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("Insert into $destName")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun showBeforeAfterPicker(pageNumber: Int, onChosen: (before: Boolean) -> Unit) {
        val options = arrayOf("Insert before Page $pageNumber", "Insert after Page $pageNumber")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Page $pageNumber")
            .setItems(options) { _, which -> onChosen(which == 0) }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    /** The page key(s) + render spec(s) the current view exports (Day = AM + PM). */
    private fun exportSources(): List<Pair<String, CalendarTemplateRenderer.Spec>> {
        val today = LocalDate.now()
        return when (currentView) {
            CalView.MONTH -> listOf(
                "cal-month-%04d-%02d".format(java.util.Locale.ROOT, calYear, calMonth) to
                    CalendarTemplateRenderer.Spec(CalView.MONTH, calYear, calMonth, selectedDate, 0, today)
            )
            CalView.WEEK -> listOf(
                "cal-week-${sundayOf(selectedDate)}" to
                    CalendarTemplateRenderer.Spec(CalView.WEEK, calYear, calMonth, selectedDate, 0, today)
            )
            CalView.DAY -> listOf(
                "cal-day-$selectedDate-AM" to
                    CalendarTemplateRenderer.Spec(CalView.DAY, calYear, calMonth, selectedDate, 0, today),
                "cal-day-$selectedDate-PM" to
                    CalendarTemplateRenderer.Spec(CalView.DAY, calYear, calMonth, selectedDate, 1, today),
            )
        }
    }

    private fun templateNameFor(spec: CalendarTemplateRenderer.Spec): String {
        val monthName = Month.of(spec.calMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
        return when (spec.view) {
            CalView.MONTH -> "Calendar — $monthName ${spec.calYear}"
            CalView.WEEK -> "Calendar — Week of ${sundayOf(spec.selectedDate)}"
            CalView.DAY -> "Calendar — ${spec.selectedDate} ${if (spec.dayHalf == 0) "AM" else "PM"}"
        }
    }

    private fun encodeSnapshotBase64(bitmap: Bitmap): String =
        ImageCodec.encodeBase64(bitmap)

    private fun performExport(
        destId: String, destName: String, destPath: String, destPass: String?,
        anchorPageId: String?, before: Boolean, includeContent: Boolean,
    ) {
        val w = binding.calendarContent.width
        val h = binding.calendarContent.height
        if (w <= 0 || h <= 0) { toast("Calendar not ready"); return }

        // The calendar canvas sits *below* its 56dp toolbar (vertical layout), but a notebook page's
        // drawing area is full-screen with the toolbar overlaid on top. Reserve that toolbar height as
        // a blank top margin so the exported grid + writing clear the notebook's floating toolbar
        // instead of landing under it (the "off by exactly the toolbar size" alignment).
        val topOffset = (binding.root.height - h).coerceAtLeast(0)
        val topOffsetF = topOffset.toFloat()
        val pageH = h + topOffset

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes() }  // flush the visible canvas to the DB first

            val exportPages = exportSources().map { (key, spec) ->
                repository.getOrCreatePageLayer(key)
                val children = if (includeContent) {
                    val content = withContext(Dispatchers.IO) { repository.loadPage(key, density) }
                    val translated = regenIds(clipFromPage(content), 0f, topOffsetF)
                    repository.serializeForExport(translated, density)
                } else emptyList()
                val imageB64 = withContext(Dispatchers.Default) {
                    val grid = CalendarTemplateRenderer.render(spec, w, h, density, highlights = false)
                    val page = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888)
                    Canvas(page).apply {
                        drawColor(Color.WHITE)            // blank toolbar-height strip at top
                        drawBitmap(grid, 0f, topOffsetF, null)
                    }
                    grid.recycle()
                    val s = encodeSnapshotBase64(page)
                    page.recycle()
                    s
                }
                CalendarExportPage(templateNameFor(spec), imageB64, w, pageH, children)
            }

            val results = withContext(Dispatchers.IO) {
                insertCalendarPagesIntoNotebook(destPath, destPass, anchorPageId, before, exportPages)
            }
            if (results.isNullOrEmpty()) { toast("Couldn't add pages"); return@launch }

            showPostExportDialog(destId, destName, results.size, results.first().first)
        }
    }

    private fun showPostExportDialog(destId: String, destName: String, count: Int, firstPageId: String) {
        val noun = if (count == 1) "page" else "pages"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Added $count $noun to $destName")
            .setPositiveButton("Open $destName") { _, _ -> openExportedNotebook(destId, destName, firstPageId) }
            .setNegativeButton("Stay", null)
            .show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun openExportedNotebook(destId: String, destName: String, firstPageId: String) {
        if (destId == fromNotebookId) {
            // Launched from this same notebook — return to it and navigate to the new page.
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_GOTO_PAGE_ID, firstPageId))
            finish()
        } else {
            // Clean pen-pipeline handoff before the fresh notebook opens (see openNotebookWithPaste).
            drawingView.releaseForHandoff()
            startActivity(
                Intent(this, NotebookActivity::class.java)
                    .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, destId)
                    .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, destName)
                    .putExtra(NotebookActivity.EXTRA_INITIAL_PAGE_ID, firstPageId)
            )
            finish()
        }
    }

    // ── Sticky notes ────────────────────────────────────────────────────────────

    private suspend fun insertStickyNote() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageW = binding.calendarContent.width.takeIf { it > 0 } ?: return
        val pageH = binding.calendarContent.height.takeIf { it > 0 } ?: return
        val iconSizePx = STICKY_NOTE_ICON_SIZE_DP * density
        val left = ((pageW - iconSizePx) / 2f).coerceAtLeast(0f)
        val top = ((pageH - iconSizePx) / 2f).coerceAtLeast(0f)
        val bbox = RectF(left, top, left + iconSizePx, top + iconSizePx)
        val noteId = java.util.UUID.randomUUID().toString()
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
        editorLauncher.launch(StickyNoteEditorActivity.intent(this, note, StickyNoteEditorActivity.HOST_CALENDAR))
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

    // ── Shape conversion / transform ────────────────────────────────────────────

    private suspend fun convertStrokeToShape(stroke: LiveStroke, result: ShapeRecognizer.Result) {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val shapeId = java.util.UUID.randomUUID().toString()
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
        // Columnar in-place update, NOT updateObjectData — dead write for columnar shape rows
        // (reader prefers typed columns); see ScratchpadActivity.persistShapeTransform.
        NotesproutIndex.calendarDao().updateColumns(after.toRow("", 0, now, now, density))
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
        binding.btnCalLasso.setImageResource(
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
        val containerW = binding.calendarContent.width.toFloat()
        val containerH = binding.calendarContent.height.toFloat()
        val dpGap = 8f * density
        var x = selectionBox.centerX() - viewW / 2f
        var y = selectionBox.bottom + dpGap
        if (y + viewH > containerH) y = selectionBox.top - dpGap - viewH
        y = y.coerceIn(0f, (containerH - viewH).coerceAtLeast(0f))
        x = x.coerceIn(0f, (containerW - viewW).coerceAtLeast(0f))
        view.x = x
        view.y = y
    }

    // ── Touch dispatch ───────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Overflow-menu dismissal — any tool type, so a stylus outside the menu closes it too.
        if (::overflowManager.isInitialized && overflowManager.isOverflowMenuOpen()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val onBtn = isTouchInView(event, binding.btnCalOverflow)
                    val inMenu = isTouchInView(event, binding.calOverflowMenu)
                    val inBar = isTouchInView(event, binding.calendarToolbar)
                    drawingView.releaseRender()
                    pendingMenuTapClose = false
                    when {
                        onBtn || inMenu -> pendingMenuTapClose = inMenu // btn toggles itself; item closes on release
                        inBar -> closeCalOverflowMenu()                 // another toolbar button
                        else -> { closeCalOverflowMenu(); return true }  // consume — don't start a stroke
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pendingMenuTapClose) {
                        pendingMenuTapClose = false
                        val handled = super.dispatchTouchEvent(event) // let the tapped item's click land first
                        closeCalOverflowMenu()
                        return handled
                    }
                }
            }
        }
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            // Pen-activity gate: while the stylus owns the surface (and briefly after it lifts) a
            // finger contact is a palm, not a gesture — see [NotebookView.isPenActive]. Drop it
            // before any detector or EPD release can act on it and disturb the stroke in flight.
            if (drawingView.isPenActive) {
                if (!fingerGesturesSuppressed) {
                    fingerGesturesSuppressed = true
                    cancelFingerGestures()
                }
                return super.dispatchTouchEvent(event)
            }
            fingerGesturesSuppressed = false

            val inToolbar = isTouchInView(event, binding.calendarToolbar)
            val inMenu = binding.calOverflowMenu.visibility == View.VISIBLE &&
                    isTouchInView(event, binding.calOverflowMenu)
            val inFloating = binding.floatingSelectionToolbar.visibility == View.VISIBLE &&
                    isTouchInView(event, binding.floatingSelectionToolbar)
            // The pen colour panel is chrome, like the toolbar and the floating bar above it, and
            // must be excluded for the same reason: handleCalendarFingerGesture consumes the UP of a
            // finger tap (that is how double-tap-to-open-a-day works), so a swatch would receive the
            // DOWN — long enough to raise its tooltip — and never the UP that makes it a click. That
            // is why the panel needed a stylus here and worked with a finger everywhere else.
            val inPenPanel = ::penColorPanel.isInitialized &&
                    penColorPanel.containsScreenPoint(event.rawX.toInt(), event.rawY.toInt())
            if (event.actionMasked == MotionEvent.ACTION_DOWN && (inToolbar || inFloating || inMenu || inPenPanel)) drawingView.releaseRender()
            if (!inMenu && !inPenPanel && handleStickyNoteTapGesture(event)) return true
            if (!inToolbar && !inFloating && !inMenu && !inPenPanel) {
                handleMultiFingerDoubleTap(event)
                if (handleCalendarFingerGesture(event)) return true
            }
        }
        // Dismiss the pen colour panel on a touch outside it — except on btnCalPen itself, whose
        // own listener owns the toggle (handling it here too would close then immediately reopen).
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && ::penColorPanel.isInitialized && penColorPanel.isVisible) {
            val inPanel = penColorPanel.containsScreenPoint(event.rawX.toInt(), event.rawY.toInt())
            if (!inPanel && !isTouchInView(event, binding.btnCalPen)) hidePenColorPanel()
        }

        return super.dispatchTouchEvent(event)
    }

    /**
     * Multi-finger stationary double-tap: 2 fingers = undo, 3 fingers = redo. Ported from
     * [NotebookActivity]. Arms on the first pointer-down, evaluates on UP (or CANCEL for the
     * BOOX 3-finger interception case) when the centroid stayed within tap slop and the gesture
     * was short. Peak pointer count distinguishes the two cases.
     */
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
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx) mfTapMoved = true
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
                // On BOOX the Onyx SDK intercepts 3-finger touches and immediately cancels —
                // ACTION_UP never fires. Treat a cancel on an armed, stationary 3-finger gesture
                // as a valid tap completion so double-tap redo can work.
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

    /** Shared tap-completion logic for [handleMultiFingerDoubleTap]. */
    private fun evaluateMultiFingerTap(now: Long, threeOnly: Boolean = false) {
        when (if (threeOnly) 3 else mfTapPeakCount) {
            2 -> {
                val withinTime = twoFingerTapFirstTime != 0L &&
                    now - twoFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = twoFingerTapFirstTime != 0L && Math.hypot(
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
                val withinSlop = threeFingerTapFirstTime != 0L && Math.hypot(
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

    /** Finger gestures over the canvas: horizontal swipe steps the period; a double-tap opens a day. */
    /**
     * Abandon all in-flight finger-gesture state when the pen-activity gate closes. Resets fields
     * directly rather than routing a cancel through [handleMultiFingerDoubleTap], whose CANCEL
     * branch treats a cancel on an armed 3-finger gesture as a *completed* tap (the Onyx 3-finger
     * interception workaround) — exactly the false positive the gate exists to prevent.
     * Mirrors `NotebookActivity.cancelFingerGestures`.
     */
    private fun cancelFingerGestures() {
        // Single-finger nav swipe / day tap / long-press.
        calMoved = true
        calMultiTouch = true
        cancelDayNotebooksLongPress()
        calLongPressFired = false
        calVelocityTracker?.recycle()
        calVelocityTracker = null

        stickyNoteTapCandidate = null
        stickyNoteTapMoved = true

        mfTapArmed = false
        mfTapMoved = true
        mfTapPeakCount = 0
        twoFingerTapFirstTime = 0L
        threeFingerTapFirstTime = 0L
    }

    /**
     * True when a one-finger displacement is a deliberate period-step swipe. Mirrors
     * [NotebookActivity.evaluatePageFling]'s three guards so the calendar feels like the
     * notebook: horizontal dominance, ≥30% of screen width, and either fling velocity or
     * ≥50% of screen width. Direction is taken from `dx`, never from velocity, so a finger
     * decelerating at lift-off can't flip the sign.
     */
    private fun pageSwipeQualifies(dx: Float, dy: Float, velocityX: Float): Boolean {
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx <= absDy) return false
        val width = resources.displayMetrics.widthPixels.toFloat()
        if (absDx < PAGE_SWIPE_MIN_DISTANCE_FRAC * width) return false
        val minVel = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityX) >= minVel
        val longEnough = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width
        return fastEnough || longEnough
    }

    private fun handleCalendarFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                calDownX = event.x; calDownY = event.y; calDownTime = event.eventTime; calMoved = false; calMultiTouch = false
                calVelocityTracker?.recycle()
                calVelocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                armDayNotebooksLongPress(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { calMoved = true; calMultiTouch = true; cancelDayNotebooksLongPress() }
            MotionEvent.ACTION_MOVE -> {
                calVelocityTracker?.addMovement(event)
                if (!calMoved && hypot((event.x - calDownX).toDouble(), (event.y - calDownY).toDouble()) > touchSlopPx) calMoved = true
                if (calMoved) cancelDayNotebooksLongPress()
            }
            MotionEvent.ACTION_UP -> {
                cancelDayNotebooksLongPress()
                val velocityX = calVelocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                calVelocityTracker?.recycle()
                calVelocityTracker = null
                // The long-press already acted while the finger was down; swallow its UP so the
                // release doesn't also read as a swipe or a day tap.
                if (calLongPressFired) { calLongPressFired = false; return true }
                val dx = event.x - calDownX
                val dy = event.y - calDownY
                // !calMultiTouch guard: a 2-/3-finger gesture (undo/redo double-tap) sets the flag
                // on POINTER_DOWN, so it never registers as a single-finger navigation swipe.
                if (!calMultiTouch && pageSwipeQualifies(dx, dy, velocityX)) {
                    if (dx < 0) stepForward() else stepBack()
                    return true
                }
                val quick = event.eventTime - calDownTime <= ViewConfiguration.getLongPressTimeout()
                if (!calMoved && quick && event.pointerCount == 1) {
                    val loc = IntArray(2)
                    binding.calendarContent.getLocationInWindow(loc)
                    handleDayTap(event.x - loc[0], event.y - loc[1])
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                calMoved = true; cancelDayNotebooksLongPress(); calLongPressFired = false
                calVelocityTracker?.recycle()
                calVelocityTracker = null
            }
        }
        return false
    }

    // ── Long-press → that day's notebook list ────────────────────────────────────

    /**
     * Arm the long-press that opens [DayNotebooksDialog]. Fires while the finger is still down (the
     * standard feel) rather than on release, and is cancelled by movement past slop, a second
     * pointer, release, or the pen-activity gate.
     *
     * The date is resolved at DOWN: Month/Week hit-test the pressed **cell** (a press off the grid —
     * the notes band, say — resolves to nothing and never arms); Day view has no cell hit-test, so a
     * press anywhere on either half opens [selectedDate].
     */
    private fun armDayNotebooksLongPress(event: MotionEvent) {
        cancelDayNotebooksLongPress()
        calLongPressFired = false
        val date = if (currentView == CalView.DAY) selectedDate else {
            val loc = IntArray(2)
            binding.calendarContent.getLocationInWindow(loc)
            CalendarTemplateRenderer.hitTest(
                currentSpec(), event.x - loc[0], event.y - loc[1],
                binding.calendarContent.width, binding.calendarContent.height, density,
            ) ?: return
        }
        val runnable = Runnable {
            calLongPressRunnable = null
            calLongPressFired = true
            if (isLassoMode) exitLassoMode()
            DayNotebooksDialog.show(this, date)
        }
        calLongPressRunnable = runnable
        binding.calendarContent.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelDayNotebooksLongPress() {
        calLongPressRunnable?.let { binding.calendarContent.removeCallbacks(it) }
        calLongPressRunnable = null
    }

    private fun handleDayTap(localX: Float, localY: Float) {
        // Day timeline view: a double-tap anywhere opens this day's full-page canvas (no cell hit-test).
        if (currentView == CalView.DAY) {
            val nowD = SystemClock.uptimeMillis()
            if (selectedDate == lastDayTapDate && nowD - lastDayTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                lastDayTapDate = null
                openDayDetail(selectedDate)
            } else {
                lastDayTapDate = selectedDate
                lastDayTapTime = nowD
            }
            return
        }
        val w = binding.calendarContent.width
        val h = binding.calendarContent.height
        val date = CalendarTemplateRenderer.hitTest(currentSpec(), localX, localY, w, h, density) ?: return
        // A single tap no longer selects/navigates a day (it caused accidental day switches while
        // writing; day selection is the date picker's job now). Only a double-tap acts: it opens the
        // tapped day's full-page window.
        val now = SystemClock.uptimeMillis()
        if (date == lastDayTapDate && now - lastDayTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
            lastDayTapDate = null
            openDayDetail(date)
            return
        }
        lastDayTapDate = date
        lastDayTapTime = now
    }

    /** Open the full-page day canvas for [date], carrying the source-notebook identity through. */
    private fun openDayDetail(date: LocalDate) {
        startActivity(
            DayDetailActivity.intent(
                this, date,
                fromNotebookId = fromNotebookId,
                fromNotebookName = fromNotebookName,
            )
        )
    }

    // ── Sticky note tap gesture (ported from ScratchpadActivity) ─────────────────

    private fun handleStickyNoteTapGesture(event: MotionEvent): Boolean {
        val containerLoc = IntArray(2)
        binding.calendarContent.getLocationInWindow(containerLoc)
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
        SurfaceStack.markTop(this, surfaceEntry())
        drawingView.resumeDrawing()
        updateLassoButtonIcon()
        // Returning from the day window (or an event edit): events may have changed — reload and
        // re-bake the grid. Skipped on the very first resume (no page/canvas yet).
        if (currentPageId.isNotEmpty()) {
            lifecycleScope.launch { loadEventsForView(); refreshTemplate() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::overflowManager.isInitialized && overflowManager.isOverflowMenuOpen()) closeCalOverflowMenu()
        saveCalendarPosition()
        // appScope (not lifecycleScope): the save must complete even as the Activity is paused
        // and torn down. lifecycleScope would be cancelled at onDestroy, dropping the write.
        NotesproutApplication.appScope.launch { saveStrokes() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SurfaceStack.KEY_TOKEN, surfaceToken)
        // Sticky-editor session marker: the transfer singleton dies with the process, so the
        // pending note must survive here or a low-memory kill behind the open editor makes the
        // launcher callback drop the whole editing session on return.
        pendingStickyNote?.let {
            outState.putString(StickyNoteEditorTransfer.STATE_PENDING_NOTE, StickyNoteEditorTransfer.encodeNote(it))
            outState.putBoolean(StickyNoteEditorTransfer.STATE_PENDING_CREATE, pendingStickyInitialCreate)
        }
    }

    private fun surfaceEntry() = SurfaceEntry(surfaceToken, AppSurface.CALENDAR)

    /** Persist the current view + date so the next open restores this exact position. */
    private fun saveCalendarPosition() {
        getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE).edit()
            .putString(KEY_VIEW, currentView.name)
            .putString(KEY_DATE, selectedDate.toString())
            .putInt(KEY_CAL_YEAR, calYear)
            .putInt(KEY_CAL_MONTH, calMonth)
            .putInt(KEY_DAY_HALF, dayHalf)
            .apply()
    }

    override fun onDestroy() {
        // onCreate aborted before building any of this — see [bounceIfIndexNotReady]. Android
        // still calls onDestroy on a half-constructed Activity, and every teardown below assumes
        // state that was never created.
        if (indexBounced) { super.onDestroy(); return }
        PenColorPreferences.removeListener(penColorListener)
        super.onDestroy()
        // Final safety-net flush. appScope outlives this Activity so the write still lands;
        // releaseResources only recycles bitmaps (stroke data is untouched), so the async
        // getStrokes() read stays valid even though release runs right after.
        NotesproutApplication.appScope.launch { saveStrokes() }
        drawingView.releaseResources()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Pen colour ───────────────────────────────────────────────────────────

    private fun togglePenColorPanel() {
        if (penColorPanel.isVisible) {
            hidePenColorPanel()
        } else {
            // One exclusion rect per host, so the two popovers cannot both own it — and two stacked
            // popovers over one bar is not a state worth supporting anyway.
            closeCalOverflowMenu()
            drawingView.releaseRender()   // flush the EPD frame so the panel is visible on e-ink
            penColorPanel.show(
                PenColorPreferences.load(this),
                PenColorPreferences.loadPalette(this),
                PenColorPreferences.loadSlots(this),
            )
        }
    }

    private fun hidePenColorPanel() {
        if (!::penColorPanel.isInitialized || !penColorPanel.isVisible) return
        penColorPanel.hide()   // pushes the exclusion rect via onVisibilityChanged
    }

    /** Keep the pen from writing underneath the panel while it is open. */
    private fun pushPenPanelExclusion() {
        drawingView.setToolbarExclusion(penColorPanel.panelRectIn(drawingView.asView()))
    }

    /**
     * Persist the chosen ink. Arming the drawing view and re-tinting the button happen in
     * [penColorListener], so this host and every other live one react through one path.
     */
    private fun applyPenColor(hex: String) {
        PenColorPreferences.save(this, hex)
    }

    /**
     * Reacts to the global ink changing — from this surface or any other live one. The overlapping
     * surfaces (a sticky note or scratch pad floating over a notebook) are the reason this exists:
     * without it the host underneath kept showing a stale pen tint until it was reopened.
     */
    private val penColorListener: (String) -> Unit = { hex ->
        drawingView.setPenColor(hex)
        applyPenTintToButton()
    }

    private fun applyPenTintToButton() {
        PenColorPanelController.applyPenTint(binding.btnCalPen, PenColorPreferences.load(this))
    }

    /**
     * Mix a colour into custom slot [index]. Slots are positional and never shuffle, so a colour
     * stays exactly where the user put it — the whole point of slots over a recents list.
     */
    private fun showCustomColorDialog(index: Int, initial: String) {
        drawingView.releaseRender()   // flush the EPD frame so the dialog paints cleanly
        CustomColorDialog(
            context = this,
            initial = initial,
            onChosen = { hex ->
                PenColorPreferences.saveSlot(this, index, hex)
                applyPenColor(hex)
            },
        ).show()
    }

}
