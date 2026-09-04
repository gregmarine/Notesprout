package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.calendar.databinding.ActivityCalendarBinding
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkPage
import com.symmetricalpalmtree.notesproutsn.ink.InkScreenActivity
import com.symmetricalpalmtree.notesproutsn.notebook.InkSelectionBar
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
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
 * **The grid is the page's template.** [CalendarGeometry] lays it out at the page's own size under
 * the two bars, [CalendarTemplate] paints it, and g-paper sets it behind the ink — so a store
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

    /** The day the showing template was baked for — re-baked when it is no longer today. */
    private var bakedToday: LocalDate? = null

    /** What the template on the paper was baked from — the page, the day, the page size, the
     *  bars' measured heights and (arc 24 / Z4) **the marks that were drawn into it**. A [showPage]
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
        val top: Int,
        val bottom: Int,
        val marks: Map<LocalDate, List<DayMark>>,
    )

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.calendar_store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.calendar_store_failed_body
    override val nothingToSendTitleRes: Int get() = R.string.calendar_nothing_to_send_title
    override val nothingToSendBodyRes: Int get() = R.string.calendar_nothing_to_send_body
    override val sendResultCode: Int get() = ExtensionContract.RESULT_CALENDAR_SEND

    override fun parkOutgoing(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float) =
        CalendarSession.park(chunks, pageWidth, pageHeight)

    /** The calendar has no page-level action, so its stack is `:ext-ink`'s four kinds unwrapped. */
    override fun record(action: InkAction) = undo.record(action)

    override fun syncTool(tool: Tool) = toolbar.sync(tool)

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
        paper.setPaperListener(paperListener)

        toolbar = CalendarToolbar(
            paper = paper,
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
            onEvents = { openEvents() },
            onPrev = { runPageOp { step(forward = false) } },
            onNext = { runPageOp { step(forward = true) } },
            onToday = { runPageOp { showMove(nav.todayMove(LocalDate.now(), nowHour())) } },
            onView = { kind -> runPageOp { nav.toggled(kind)?.let { showMove(it) } } },
            onTitle = { showPicker() },
            // The pad is the host's to open: leave with the result that asks for it (flushed first,
            // like every exit), and the host brings the calendar back — at its bookmark — afterwards.
            onScratchPad = { exit(ExtensionContract.RESULT_CALENDAR_OPEN_SCRATCH_PAD) },
            sendEnabled = sendEnabled,
            scratchPadAvailable = scratchPadAvailable,
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
            extraRects = { selectionBar.rects() },
            extraContains = { x, y -> selectionBar.contains(x, y) },
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
            // The bars' real heights are what the grid is laid out under — wait for the first layout
            // rather than guessing from a dimen (a chrome dimen names a part; the bar is the whole).
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
        if (width > 0 && height > 0 && binding.topBar.height > 0 && binding.bottomBar.height > 0) return
        suspendCancellableCoroutine { cont ->
            val l = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (v.width > 0 && v.height > 0 && binding.topBar.height > 0 && binding.bottomBar.height > 0) {
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
        // A double-tap on a day cell opens that day. `onFingerTap` is deliberately NOT overridden:
        // a single tap selects nothing here (the wizard's call), so the calendar hears only the
        // double. No long-press, no inserts, no swipe-down either: the calendar has only what it
        // has, and the rest stay the no-op defaults `PageGestures.Listener` already gives.
        override fun onFingerDoubleTap(x: Float, y: Float) = runPageOp { openDay(x, y) }
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
     * A double-tap at ([x], [y]): the day under it, opened as a Day page. Page coordinates are view
     * coordinates 1:1, so the raw point goes straight at the geometry the template was painted
     * from. A tap that names no day — the spare Week cell, a band, a margin, a hairline — and a
     * double-tap on a Day page are both **nothing**, silently: there is no wrong page to land on.
     */
    private suspend fun openDay(x: Float, y: Float) {
        val t = document?.target ?: return
        val day = when (t.kind) {
            CalendarTarget.KIND_MONTH -> monthGeometry().hitTest(x, y, t.localDate)
            CalendarTarget.KIND_WEEK -> weekGeometry().hitTest(x, y, t.localDate)
            else -> null
        } ?: return
        nav.dayAt(day)?.let { showMove(it) }
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
            doc.target, today, doc.pageWidth.toInt(), doc.pageHeight.toInt(),
            binding.topBar.height, binding.bottomBar.height, doc.marks,
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

    /** The page's grid at the page's own size, under the bars as they are laid out now — the three
     *  layouts dispatched by the showing page's kind, each with the page's own marks (arc 24 / Z4;
     *  a Day page takes the one day's list, both halves from the same read). */
    private fun bakeTemplate(t: CalendarTarget): android.graphics.Bitmap {
        val today = LocalDate.now()
        bakedToday = today
        val density = resources.displayMetrics.density
        val notes = getString(R.string.calendar_notes_label)
        val marks = document?.marks.orEmpty()
        return when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, today, density, palette, notes, marks)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette, notes, marks[t.localDate].orEmpty())
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, today, density, palette, notes, marks)
        }
    }

    private fun monthGeometry() = CalendarGeometry.month(
        pageWidthPx(), pageHeightPx(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
    )

    private fun weekGeometry() = CalendarGeometry.week(
        pageWidthPx(), pageHeightPx(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
    )

    private fun dayGeometry() = CalendarGeometry.day(
        pageWidthPx(), pageHeightPx(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
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
        // when it did — a resume is otherwise not a frame.
        if (opened && !closing && bakedToday != null && bakedToday != LocalDate.now()) {
            applyTemplate(force = true)
        }
    }

    private companion object {
        const val TAG = "CalendarActivity"
    }
}
