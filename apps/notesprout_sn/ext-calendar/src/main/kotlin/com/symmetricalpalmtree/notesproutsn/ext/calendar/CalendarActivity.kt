package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.calendar.databinding.ActivityCalendarBinding
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.InkChunks
import com.symmetricalpalmtree.notesproutsn.ink.InkWire
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkPage
import com.symmetricalpalmtree.notesproutsn.ink.InkScreenActivity
import com.symmetricalpalmtree.notesproutsn.notebook.CollapsedChrome
import com.symmetricalpalmtree.notesproutsn.notebook.InkSelectionBar
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.EraserBar
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.resume

/**
 * The extension-owned Calendar screen (arc 23 / Y1; UI-rule tier 2) — the pad's shape, in the
 * extension's own process, built from `:sn-screen` and, since the arc-23 sweep, on `:ext-ink`'s
 * [InkScreenActivity]: **the whole tier-2 skeleton is there** — full-bleed g-paper, the page-op
 * lock, the undo/redo replay (with this screen's [followReplay] hook), the debounced save against
 * every leave flush, the chrome band and exclusions, the EPD handoff and Send. This class is what
 * is the **calendar's own**: navigation, the template bake, the day picker and the double-tap. The
 * page and its persistence are [CalendarDocument]'s; the store is the host's, lent for this showing
 * — **the extension writes nothing to disk itself, ever**.
 *
 * **The grid is the page's template.** [CalendarGeometry] lays it out at the page's own size — the
 * **full page** since arc 33 / F4, the bars floating over it — [CalendarTemplate] paints it, and
 * g-paper sets it behind the ink — so a store
 * carried to another screen keeps grid and ink registered (the pad's 1:1 rule). It is re-baked on
 * every navigation and on `onResume`, because today's ring moves — and since arc 24 / Z4 **the
 * day's events are in it too**: [CalendarDocument] loads the page's marks in the same IO hop as its
 * strokes, [BakeKey] carries them structurally, and the events screen's return re-reads them for a
 * page that never moved. The page rect is anchored
 * top-left and the page *is* the whole surface, so **a finger's view coordinates are page
 * coordinates 1:1** — the double-tap hit-tests the raw point against the same geometry the
 * template was painted from, and nothing is scaled.
 *
 * **Navigation is [CalendarNavigation]'s, and every route ends in [showMove].** The pager steps the
 * period (buttons, or a finger swipe through the notebook's own guards) and its title opens the day
 * picker; Today lands on today in the showing view; the three word toggles change the view; a finger
 * double-tap on a Month or Week cell opens that day's Day page. The screen holds no navigation rule
 * of its own — it hit-tests, asks for a [CalendarNavigation.Move], shows it, and reports that it
 * landed.
 *
 * **A received placement** (arc 23 / Y3) is the notebook's lasso, sent across before this screen was
 * launched and already in the store when it opens. It is **consumed once** — the record is cleared
 * before anything can fail — and the page it names is the page this showing opens on, ahead of the
 * bookmark: the placement is the reason the screen is up. It lands **selected with the lasso armed**
 * so the pen can drag it into a cell at once, as **one** [InkAction.Pasted] step, and the tool the
 * user had comes back pen-idle when that selection is dismissed. Its coordinates are the notebook
 * page's, **1:1** — no cell-fitting; the selection is what makes placing it one gesture (the
 * planner's call).
 *
 * **The anchor is why the toggles feel like one organizer.** The three views are three
 * magnifications of the same day, so the state carries the day being looked at rather than the
 * period showing: Month → Week → Day walks down to that day, and back up again from it. A page
 * opened or stepped to that *contains today* anchors on today, so the first toggle out of this month
 * is this week. The rule and its tests live in [CalendarNavigation].
 *
 * Every navigation writes the bookmark (`state` rows) and nothing else — **rows are minted on the
 * first stroke, never on open**, so browsing an empty year leaves the store exactly as it was.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be, the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * **The EPD handoff is the pad's, kept whole**, and so is **Back awaits the flush** — both are
 * [InkScreenActivity]'s class note, and a failure in the handoff goes to g-paper, never a host
 * workaround.
 *
 * Undo is **calendar-level, in memory, per showing** (the pad's rule): an action names its page,
 * and replaying one recorded on another page navigates there first ([CalendarDocument.revert]).
 *
 * Frame silence: no app frame while `paper.isPenActive`. The title waits for the gate
 * ([CalendarToolbar]); the frames that do not are the pad's recorded exceptions in their calendar
 * form — the selection bar's show at lasso completion (and its re-anchor after a move, and over a
 * received placement, which is the same frame at the same kind of boundary), the "Opening…" box's
 * hide when the page lands, and a problem dialog at a chrome tap.
 */
class CalendarActivity : InkScreenActivity<InkAction>() {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var toolbar: CalendarToolbar
    private lateinit var palette: CalendarTemplate.Palette
    private var document: CalendarDocument? = null

    /** Where the organizer is looking and what each control does to it — the anchor rule, pure. */
    private val nav = CalendarNavigation()

    /**
     * The Events screen (arc 24 / Z2), launched **in this process** — it is not a point and the host
     * knows nothing about it. Registered as a property, because a launcher must be registered before
     * the Activity is STARTED and a field initializer runs during construction.
     *
     * On the way back the screen names the day it ended on, and the calendar follows it in the view
     * it is in (the locked "Return" decision) — then re-bakes by force, because an event may have
     * been added or deleted and the grid's marks are baked into the template. A result that names no
     * day (a crash, a kill) moves nothing.
     */
    private val eventsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ended = result.data?.getStringExtra(EventsActivity.EXTRA_ENDED_ON)?.let(CalendarDates::parse)
            // Not yet open: only reachable if the process was rebuilt under the child screen, and
            // the calendar is about to open on its bookmark anyway.
            if (ended == null || !opened || closing || isFinishing || isDestroyed) return@registerForActivityResult
            runPageOp { showMove(nav.picked(ended, LocalDate.now(), nowHour()), forceBake = true) }
        }

    /** True when the calendar was opened from a notebook — the two Send buttons exist only then. */
    private var sendEnabled = false

    /** True when the host says this launch follows a `receiveInk` (Y3's host half). */
    private var openReceived = false

    /** What the template on the paper was baked from — the page, the day, the page size and
     *  (arc 24 / Z4) **the marks that were drawn into it**. A [showPage]
     *  whose key is unchanged (an undo or redo on the showing page) reloads the strokes and nothing
     *  else: no page-sized bitmap, no extra EPD frames. */
    private var bakeKey: BakeKey? = null
    private var baked: android.graphics.Bitmap? = null

    /**
     * [marks] is compared **structurally**, not by a hash: a hash can collide, and a collision here
     * is a page that silently keeps showing an event the person just deleted. A `Map` of a handful
     * of small data classes costs nothing to compare against the page-sized bitmap it decides.
     */
    private data class BakeKey(
        val target: CalendarTarget,
        val today: LocalDate,
        val width: Int,
        val height: Int,
        val marks: Map<LocalDate, List<DayMark>>,
    )

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    // The collapsed chrome (arc 36 / C2) — the corner tool button and its two rows.
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.calendar_store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.calendar_store_failed_body
    override val nothingToSendTitleRes: Int get() = R.string.calendar_nothing_to_send_title
    override val nothingToSendBodyRes: Int get() = R.string.calendar_nothing_to_send_body
    override val sendResultCode: Int get() = ExtensionContract.RESULT_CALENDAR_SEND

    override fun parkOutgoing(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float) =
        CalendarSession.park(chunks, pageWidth, pageHeight)

    /** A whole-page send parks its page too (`outgoingTarget`, arc 31 / HV4 — HV5's papered send
     *  reads it); a selection send parks null, which is the answer "the page you are showing". */
    override fun parkOutgoing(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float, wholePage: Boolean) {
        CalendarSession.park(chunks, pageWidth, pageHeight)
        CalendarSession.parkTarget(if (wholePage) document?.target else null)
    }

    /**
     * A whole page always has something to send (arc 31 / HV5): the host inserts a **new page
     * papered with this view's grid**, rendered back through `ICalendar.render` on the bind it is
     * still holding, and writes whatever ink there is on top. A blank week is a blank week's paper,
     * which is a thing to want. A selection send keeps the refusal — an empty lasso is empty.
     */
    override val emptyPageSendCarriesPaper: Boolean get() = true

    /**
     * A Day's whole-page Send carries **both halves, AM then PM** (arc 35 / HA1), whichever one is
     * showing: the other half is read off the store rows (the showing page was flushed by the
     * caller, the other half is not open and its rows are what it is), chunked the same way, and
     * the two are re-parked in landing order — AM as the page the host drains first, PM queued
     * behind it for `advanceOutgoing`. An unminted half parks no chunks at the showing page's size:
     * its paper is still a page, the HV5 rule per half. Month and Week park nothing more. A store
     * that fails here leaves the single-page send exactly as it was parked.
     */
    override suspend fun parkCompanionPages(page: InkPage) {
        val doc = document ?: return
        val shown = doc.target
        if (shown.kind != CalendarTarget.KIND_DAY) return
        val store = CalendarSession.store ?: return
        val otherHalf = if (shown.half == CalendarTarget.HALF_AM) CalendarTarget.HALF_PM else CalendarTarget.HALF_AM
        val other = CalendarTarget.of(shown.kind, shown.localDate, otherHalf)
        val otherChunks = try {
            withContext(Dispatchers.IO) {
                val stored = CalendarStore(store).readPage(other)
                InkChunks.chunk(InkWire.toWireStrokes(stored.strokes.map { it.second }))
            }
        } catch (e: Exception) {
            Log.w(TAG, "the day's other half could not be read; sending the showing half alone", e)
            return
        }
        val shownPage = CalendarSession.OutboundPage(CalendarSession.outbound, CalendarSession.outboundPageWidth, CalendarSession.outboundPageHeight, shown)
        val otherPage = CalendarSession.OutboundPage(otherChunks, page.pageWidth, page.pageHeight, other)
        val (first, second) = if (shown.half == CalendarTarget.HALF_AM) shownPage to otherPage else otherPage to shownPage
        CalendarSession.park(first.chunks, first.width, first.height)
        CalendarSession.parkTarget(first.target)
        CalendarSession.queueAfterCurrent(listOf(second))
        Slog.d(TAG) { "send: both halves of ${shown.date} parked, AM first (${first.chunks.size} + ${second.chunks.size} chunks)" }
    }

    // ── Export (arc 31 / HV4) ────────────────────────────────────────────────

    /**
     * The Export door: park the page on screen as `outgoingTarget`'s answer and leave with
     * `RESULT_CALENDAR_EXPORT` — flushed first, like every exit, so what the host renders on its
     * own bind is the ink that is on the glass. The host reads the target on the bind it still
     * holds, opens its Export screen in calendar mode and brings the calendar back afterwards.
     * Nothing crosses on the result: no pixels, no date.
     */
    private fun exportPage() {
        if (!opened || closing) return
        val target = document?.target ?: return
        CalendarSession.parkTarget(target)
        Slog.d(TAG) { "export: leaving with ${target.kind}/${target.date}/${target.half}" }
        exit(ExtensionContract.RESULT_CALENDAR_EXPORT)
    }

    /** Both doors open (the notebook door with an exporter installed): one button, two rows. */
    private fun sendOrExport() {
        if (!opened || closing || isFinishing || isDestroyed) return
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_pen_down, getString(R.string.cd_calendar_send_page)) { sendPage() }
            .addAction(R.drawable.ic_download, getString(R.string.calendar_export_action)) { exportPage() }
            .show()
    }

    /** The calendar has no page-level action, so its stack is `:ext-ink`'s four kinds unwrapped. */
    override fun record(action: InkAction) = undo.record(action)

    override fun syncTool(tool: Tool) = toolbar.sync(tool)

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    /**
     * Back, then every door and action on the top bar in bar order (decision 5): Today · Month ·
     * Week · Day · the out-door · Events · Scratch Pad. No pager — a swipe steps the period while
     * the chrome is collapsed.
     *
     * Every entry **mirrors** its bar button, so none of this is copied: the armed view's latch,
     * the out-door's Send-or-Export glyph and its absence when neither door is open are all read
     * off the bar at each open, and a tap performs the bar button's own click.
     */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_star, binding.btnToday),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_month, binding.btnMonth),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_week, binding.btnWeek),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_day, binding.btnDay),
        // The out-door's Send-or-Export glyph and wording were decided by [CalendarToolbar]
        // before this is read; mirroring reads them off the button.
        CollapsedChrome.Entry.mirroring(R.drawable.ic_pen_down, binding.btnSend),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_event, binding.btnEvents),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_sketching, binding.btnScratchPad),
    )

    override fun showPage() = showPage(firstLoad = false)

    override suspend fun revert(action: InkAction) {
        document?.revert(action)
    }

    override suspend fun reapply(action: InkAction) {
        document?.reapply(action)
    }

    /** A replay may have navigated the document to the action's page ([CalendarDocument.revert] /
     *  `reapply` land there first); the organizer follows, or the toggles, the pager, the picker and
     *  a double-tap would all act on the page the navigation still believed was showing. */
    override fun followReplay() {
        val doc = document ?: return
        nav.landed(doc.target, LocalDate.now(), nowHour())?.let { nav.shown(it) }
    }

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        sendEnabled = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED, false)
        openReceived = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED, false)
        val scratchPadAvailable = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE, false)
        val exportEnabled = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_EXPORT_ENABLED, false)
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge
        palette = CalendarTemplate.Palette(
            ink = ContextCompat.getColor(this, R.color.inkBlack),
            light = ContextCompat.getColor(this, R.color.inkLight),
        )

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        // Both pen-gesture recognisers on, and armed BEFORE the listener attaches. They match the
        // notebook deliberately: a calendar one tap away that lassoed differently would read as a bug.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        // Arc 49 / P2: the calendar page goes direct to the Supernote panel as the notebook's does
        // (g-paper Phase 42) — the template (the grid, the timeline, the light out-of-month cells)
        // is part of the committed picture and shows on the glass as a dither of its greys, the
        // live pen, the point eraser and the lasso's trail are painted by the app. Where the panel
        // refuses to open the page stays the ink daemon's with every overlay law intact, so this
        // is set unconditionally. Every panel post is cut around PaperChrome's exclusion rects.
        paper.directInk = true
        paper.setPaperListener(paperListener)

        toolbar = CalendarToolbar(
            paper = paper,
            onSynced = { syncCollapsed() },   // arc 36: the corner button repaints with the bar
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnSend = binding.btnSend,
            btnEvents = binding.btnEvents,
            btnScratchPad = binding.btnScratchPad,
            btnToday = binding.btnToday,
            btnMonth = binding.btnMonth,
            btnWeek = binding.btnWeek,
            btnDay = binding.btnDay,
            btnPrev = binding.btnPrev,
            btnNext = binding.btnNext,
            title = binding.title,
            onBack = { exit() },
            onSend = { sendPage() },
            onExport = { exportPage() },
            onSendOrExport = { sendOrExport() },
            onEvents = { openEvents() },
            onPrev = { runPageOp { step(forward = false) } },
            onNext = { runPageOp { step(forward = true) } },
            onToday = { runPageOp { showMove(nav.todayMove(LocalDate.now(), nowHour())) } },
            onView = { kind -> runPageOp { nav.toggled(kind)?.let { showMove(it) } } },
            onTitle = { showPicker() },
            // The pad is the host's to open: leave with the result that asks for it (flushed first,
            // like every exit), and the host brings the calendar back — at its bookmark — afterwards.
            onScratchPad = { exit(ExtensionContract.RESULT_CALENDAR_OPEN_SCRATCH_PAD) },
            // A second tap on the armed eraser toggles its sub-bar — Point · Lasso (arc 29 / LE3);
            // arming a different tool takes the bar with it.
            onEraserReTap = { toggleEraserBar() },
            onToolTapped = { hideEraserBar() },
            sendEnabled = sendEnabled,
            scratchPadAvailable = scratchPadAvailable,
            exportEnabled = exportEnabled,
        )
        // After the toolbar: a pick lands on `toolbar.arm` (a host-set tool is never echoed back
        // as `onToolChanged`, so the buttons are synced by hand).
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { hideEraserBar(); toolbar.arm(it) },
        )
        selectionBar = InkSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            deleteHint = getString(R.string.delete_selection_action),
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            sendHint = if (sendEnabled) getString(R.string.cd_calendar_send_selection) else null,
            onSend = { sendSelection() },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        // Arc 33 / F3: the calendar opens in the chrome state the host handed over and echoes the
        // final one on the way out (the skeleton's). Its own double-tap routes by zone
        // (`CalendarDoubleTap`): cells open a day, the Notes band and the whole Day page toggle.
        // The grid is full page either way (F4) — hiding the bars only uncovers what is already
        // drawn there.
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        val store = CalendarSession.store
        if (store == null) {
            Log.w(TAG, "no store for this showing")
            failOpen()
            return
        }
        document = CalendarDocument(CalendarStore(store), EventStore(store)) { surfaceSize() }
        lifecycleScope.launch { openDocument(CalendarStore(store)) }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openDocument(store: CalendarStore) {
        val doc = document ?: return
        try {
            // The surface's real size is what the grid is laid out on — wait for the first layout
            // rather than guessing from the display metrics. The root only (arc 33 / F3): a launch
            // with the chrome hidden has `GONE` bars that never get a height, and a wait on theirs
            // would hang on "Opening…" forever.
            binding.root.awaitLaidOut()
            val bookmark = withContext(Dispatchers.IO) { store.open() }
            // A launch that follows a `receiveInk` opens on the page the ink landed on, not on the
            // bookmark: the placement is the reason this screen is up. `opening` passes whatever it
            // is given through unchanged — only the anchor is derived — so the target is exactly the
            // one the host named. Otherwise the bookmark is honoured whatever kind it names, and an
            // unreadable one comes back null: the first-run answer is today's Month.
            val placed = if (openReceived) CalendarSession.received?.target else null
            val move = nav.opening(placed ?: bookmark?.target, LocalDate.now(), nowHour())
            doc.show(move.target)
            nav.shown(move)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "calendar store unavailable", e)
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        showPage(firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // Deliberately NOT pen-idle-gated: a boundary frame, nothing has been drawn yet.
        binding.openingOverlay.visibility = View.GONE
        // Counts only — never a title: an event's words are the person's own.
        Slog.d(TAG) {
            "page ${doc.target.kind}/${doc.target.date}/${doc.target.half} loaded: " +
                "${doc.strokes.size} strokes, ${doc.marks.size} marked day(s)"
        }
        consumeReceived()
    }

    /**
     * The one-shot handover of a `receiveInk` placement (Y3) — the ink is already in the store and
     * already on the paper (it came in with the page [openDocument] just showed); what is left is
     * to say so.
     *
     * **Consumed once**: the record is cleared before anything can fail, so a placement whose page
     * is no longer the one showing (only reachable through a host restart mid-showing) is dropped
     * rather than re-applied at the next open — and it is only applied at all when the launch
     * Intent's [ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED] says the host sent one.
     *
     * One undo step, an [InkAction.Pasted] that removes and restores exactly what arrived at the
     * orders it arrived at. There is no page branch here as there is on the pad: a placement never
     * creates a page the user did not already have — every date has a page, minted or not. The
     * selection it lands as is the skeleton's ([InkScreenActivity.showArrivedSelection]).
     */
    private fun consumeReceived() {
        val doc = document ?: return
        val received = CalendarSession.received ?: return
        CalendarSession.received = null
        if (!openReceived) {
            // The host did not launch us for a placement, so this record is not ours to apply.
            // Not reachable while `begin` clears the session — which is the point of checking.
            Slog.d(TAG) { "received placement dropped: this launch did not ask for one" }
            return
        }
        if (received.target != doc.target) {
            Slog.d(TAG) {
                "received placement dropped: page ${received.target.kind}/${received.target.date}/${received.target.half} is not showing"
            }
            return
        }
        val ids = received.strokeIds.toHashSet()
        val arrived = doc.strokes.filter { it.id in ids }
        if (arrived.isEmpty()) return

        undo.record(InkAction.Pasted(doc.pageId, arrived, arrived.map { doc.orderOf(it.id) ?: 0L }))
        showArrivedSelection(ids, arrived)
        Slog.d(TAG) { "received ${arrived.size} strokes" }
    }

    private suspend fun View.awaitLaidOut() {
        if (width > 0 && height > 0) return
        suspendCancellableCoroutine { cont ->
            val l = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            addOnLayoutChangeListener(l)
            cont.invokeOnCancellation { removeOnLayoutChangeListener(l) }
        }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp { step(forward = true) }
        override fun onFlipPrevious() = runPageOp { step(forward = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        // A double-tap routes by zone (arc 33 / F4): a day cell opens that day, the Notes band and
        // a Day page toggle the chrome. `onFingerTap` is deliberately NOT overridden:
        // a single tap selects nothing here (the wizard's call), so the calendar hears only the
        // double. No long-press, no inserts, no swipe-down either: the calendar has only what it
        // has, and the rest stay the no-op defaults `PageGestures.Listener` already gives.
        override fun onFingerDoubleTap(x: Float, y: Float) = runPageOp { doubleTap(x, y) }
    }

    /**
     * The one road every navigation takes: put the move's page on the paper, then record that it
     * landed. The order matters — [CalendarNavigation.shown] is what moves the anchor, and a show
     * that threw (a store gone out from under us) must leave the organizer exactly where it was.
     */
    private suspend fun showMove(m: CalendarNavigation.Move, firstLoad: Boolean = false, forceBake: Boolean = false) {
        val doc = document ?: return
        // [forceBake] is only ever set by the events screen's return, and that is exactly the case
        // where the page may not have moved while its marks did: ask for them again.
        doc.show(m.target, refreshMarks = forceBake)
        nav.shown(m)
        showPage(firstLoad, forceBake)
    }

    /** One period forward or back in the showing view — the pager's buttons and the finger swipe. */
    private suspend fun step(forward: Boolean) = showMove(nav.stepped(forward, LocalDate.now(), nowHour()))

    /**
     * A finger double-tap, routed by zone (arc 33 / F4, decision 2): a Month/Week cell → that day as
     * a Day page; the Notes band → the chrome toggle; a Day page → the toggle anywhere; the header,
     * the side margins, a hairline, the spare Week cell → nothing, silently. Page coordinates are
     * view coordinates 1:1. Inside `runPageOp` so it is serialised against a page swap.
     */
    private suspend fun doubleTap(x: Float, y: Float) {
        val t = document?.target ?: return
        val decision = CalendarDoubleTap.decide(
            kind = t.kind, x = x, y = y, date = t.localDate,
            month = if (t.kind == CalendarTarget.KIND_MONTH) monthGeometry() else null,
            week = if (t.kind == CalendarTarget.KIND_WEEK) weekGeometry() else null,
        )
        when (decision) {
            is CalendarDoubleTap.Decision.OpenDay -> nav.dayAt(decision.date)?.let { showMove(it) }
            CalendarDoubleTap.Decision.Toggle -> toggleChrome()
            CalendarDoubleTap.Decision.Nothing -> Unit
        }
    }

    /** The pager title's day picker. A dialog raised at a chrome tap — the ledgered exception, not
     *  a new one — and the pick itself is a page op like every other navigation. */
    private fun showPicker() {
        if (!opened || closing || isFinishing || isDestroyed) return
        DayPickerDialog.show(this, nav.anchor) { day ->
            runPageOp { showMove(nav.picked(day, LocalDate.now(), nowHour())) }
        }
    }

    /**
     * The Events door (arc 24 / Z2). The day it opens on is the **first day of the period showing**
     * — the 1st of a month, a week's Sunday, or the day itself ([EventsLaunch], a locked decision).
     *
     * **No `releaseForHandoff` here**, deliberately: the events screen carries no paper, and M3's
     * measured answer for a non-drawing child screen is stop-behind. Z3's note surface is the first
     * second-paper-surface question and it is that phase's to probe.
     */
    private fun openEvents() {
        if (!opened || closing || isFinishing || isDestroyed) return
        val day = EventsLaunch.launchDay(nav.kind, nav.target.localDate)
        Slog.d(TAG) { "events: opening $day from kind ${nav.kind}" }
        eventsLauncher.launch(
            Intent(this, EventsActivity::class.java)
                .putExtra(EventsActivity.EXTRA_DAY, CalendarDates.format(day)),
        )
    }

    /** The hour the clock says, for the half a Day page opens on. */
    private fun nowHour(): Int = LocalTime.now().hour

    /**
     * Put the document's page on the paper. The order is the host-responsibilities page-swap law:
     * `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` / `setTemplate`
     * → `loadStrokes`, which is a single EPD refresh. Any selection goes first, because a data-in
     * call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean, forceBake: Boolean = false) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()
        hideEraserBar()   // a floating bar never survives a content swap
        dismissCollapsed()   // and neither do the corner button's rows (arc 36 / C2)
        if (!firstLoad) paper.clearForContentSwap()
        applyTemplate(force = forceBake)
        paper.loadStrokes(doc.strokes)
        toolbar.setTitle(titleOf(doc.target))
        // The latch says what is on the paper. It rides this frame; it is never one of its own.
        toolbar.setView(doc.target.kind)
    }

    /**
     * Put the showing page's size and template on the paper — baked only when something the bake
     * depends on has changed ([BakeKey]), or when [force]d (a date rolled over under the screen).
     * `setPageSize` and `setTemplate` are each an EPD repaint, and a bake is a page-sized bitmap
     * rasterized with forty-odd labelled cells: an undo that changes neither pays for neither. The
     * replaced bitmap is recycled — g-paper holds only the one it was last given.
     */
    private fun applyTemplate(force: Boolean) {
        val doc = document ?: return
        val today = LocalDate.now()
        val key = BakeKey(
            doc.target, today, doc.pageWidth.toInt(), doc.pageHeight.toInt(), doc.marks,
        )
        if (!force && key == bakeKey && baked != null) return
        val fresh = bakeTemplate(doc.target)
        val old = baked
        bakeKey = key
        baked = fresh
        paper.setPageSize(key.width, key.height)
        paper.setTemplate(fresh)
        old?.recycle()
    }

    /** The page's grid at the page's own size, full page — the three
     *  layouts dispatched by the showing page's kind, each with the page's own marks (arc 24 / Z4;
     *  a Day page takes the one day's list, both halves from the same read). */
    private fun bakeTemplate(t: CalendarTarget): android.graphics.Bitmap {
        val today = LocalDate.now()
        val density = resources.displayMetrics.density
        val notes = getString(R.string.calendar_notes_label)
        val marks = document?.marks.orEmpty()
        return when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, today, density, palette, notes, marks)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette, marks[t.localDate].orEmpty())
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, today, density, palette, notes, marks)
        }
    }

    private fun monthGeometry() = CalendarGeometry.month(
        pageWidthPx(), pageHeightPx(), resources.displayMetrics.density,
    )

    private fun weekGeometry() = CalendarGeometry.week(
        pageWidthPx(), pageHeightPx(), resources.displayMetrics.density,
    )

    private fun dayGeometry() = CalendarGeometry.day(
        pageWidthPx(), pageHeightPx(), resources.displayMetrics.density,
    )

    private fun pageWidthPx(): Int = (document?.pageWidth ?: 0f).toInt()
    private fun pageHeightPx(): Int = (document?.pageHeight ?: 0f).toInt()

    private fun titleOf(t: CalendarTarget): String = when (t.kind) {
        CalendarTarget.KIND_MONTH -> CalendarDates.monthTitle(t.localDate)
        CalendarTarget.KIND_WEEK -> CalendarDates.weekTitle(t.localDate)
        else -> CalendarDates.dayTitle(t.localDate, t.half)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // A date rolled over while the screen sat in the background: the ring moves with it. Only
        // when it did — a resume is otherwise not a frame. The day the showing template was baked
        // for is already part of the bake key, so this asks the key rather than a second field
        // that could disagree with it, and the plain re-apply is enough: a changed `today` is a
        // changed key, and a changed key is a bake.
        val key = bakeKey ?: return
        if (opened && !closing && key.today != LocalDate.now()) applyTemplate(force = false)
    }

    private companion object {
        const val TAG = "CalendarActivity"
    }
}
