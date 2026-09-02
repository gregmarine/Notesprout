package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.calendar.databinding.ActivityCalendarBinding
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.InkChunks
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkWire
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.resume

/**
 * The extension-owned Calendar screen (arc 23 / Y1; UI-rule tier 2) — the pad's shape, in the
 * extension's own process, built from `:sn-screen`: full-bleed g-paper, two thin chrome bars,
 * [PageGestures] for the finger vocabulary, [PaperChrome] for the exclusion rects, [UndoRedoStack]
 * for the history and the floating bar through [CalendarSelectionToolbar]. The page and its
 * persistence are [CalendarDocument]'s; the store is the host's, lent for this showing — **the
 * extension writes nothing to disk itself, ever**.
 *
 * **The grid is the page's template.** [CalendarGeometry] lays it out at the page's own size under
 * the two bars, [CalendarTemplate] paints it, and g-paper sets it behind the ink — so a store
 * carried to another screen keeps grid and ink registered (the pad's 1:1 rule). It is re-baked on
 * every navigation and on `onResume`, because today's ring moves. The page rect is anchored
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
 * **The EPD handoff is the pad's, kept whole.** Two paper surfaces in two processes: the notebook
 * calls `releaseForHandoff()` immediately before launching us, we reclaim in [onResume]
 * (`resumeDrawing`), and **every** exit here goes through [finishWithHandoff] — `releaseForHandoff()`
 * and then `finish()`. A failure there goes to g-paper, never a host workaround.
 *
 * **Back awaits the flush.** The host's result callback runs `end()` → unbind → revoke the moment we
 * finish, so a save still in flight would hit a revoked binder. [exit] flushes under the page-op
 * lock first and only then hands off and finishes; so does every page leave and `onPause`.
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
class CalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var paper: PaperView
    private lateinit var chrome: PaperChrome
    private lateinit var toolbar: CalendarToolbar
    private lateinit var selectionToolbar: CalendarSelectionToolbar
    private lateinit var gestures: PageGestures
    private lateinit var document: CalendarDocument
    private lateinit var palette: CalendarTemplate.Palette

    /** Where the organizer is looking and what each control does to it — the anchor rule, pure. */
    private val nav = CalendarNavigation()

    /** In-memory, calendar-level history: it survives page turns and dies with the screen. */
    private val undo = UndoRedoStack<InkAction>()

    /** Serialises every page/undo/flush operation, so two overlapping gestures can't tangle the
     *  page — and so a debounced save can never run inside a page swap. */
    private val pageOps = Mutex()

    /** True when the calendar was opened from a notebook — the two Send buttons exist only then. */
    private var sendEnabled = false

    /** True when the host says this launch follows a `receiveInk` (Y3's host half). */
    private var openReceived = false

    /** The tool armed before a received placement selected itself (Y3). Put back **pen-idle** when
     *  that selection is dismissed, and only if the lasso is still armed. Null the rest of the time. */
    private var toolBeforeReceive: Tool? = null

    /** The day the showing template was baked for — re-baked when it is no longer today. */
    private var bakedToday: LocalDate? = null

    private var opened = false
    private var closing = false
    private var selectionActive = false
    private var currentSelection: Selection? = null
    private var problemShowing = false

    private val saveRunnable = Runnable { runPageOp { document.flushUntilClean() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        sendEnabled = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED, false)
        openReceived = intent.getBooleanExtra(ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED, false)
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
        paper.setPaperListener(listener)

        toolbar = CalendarToolbar(
            paper = paper,
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnSend = binding.btnSend,
            btnToday = binding.btnToday,
            btnMonth = binding.btnMonth,
            btnWeek = binding.btnWeek,
            btnDay = binding.btnDay,
            btnPrev = binding.btnPrev,
            btnNext = binding.btnNext,
            title = binding.title,
            onBack = { exit() },
            onSend = { sendPage() },
            onPrev = { runPageOp { step(forward = false) } },
            onNext = { runPageOp { step(forward = true) } },
            onToday = { runPageOp { showMove(nav.todayMove(LocalDate.now(), nowHour())) } },
            onView = { kind -> runPageOp { nav.toggled(kind)?.let { showMove(it) } } },
            onTitle = { showPicker() },
            sendEnabled = sendEnabled,
        )
        selectionToolbar = CalendarSelectionToolbar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            onSend = { sendSelection() },
            sendEnabled = sendEnabled,
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { selectionToolbar.rects() },
            extraContains = { x, y -> selectionToolbar.contains(x, y) },
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
        document = CalendarDocument(CalendarStore(store)) { surfaceSize() }
        lifecycleScope.launch { openDocument(CalendarStore(store)) }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openDocument(store: CalendarStore) {
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
            document.show(move.target)
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
        Slog.d(TAG) { "page ${document.target.kind}/${document.target.date}/${document.target.half} loaded: ${document.strokes.size} strokes" }
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
     * creates a page the user did not already have — every date has a page, minted or not.
     *
     * The **lasso is armed before `setSelection`** (a selection under the pen can neither be dragged
     * nor dismissed) and the state is set by hand, because a host-initiated selection never echoes
     * `onSelectionCreated`.
     */
    private fun consumeReceived() {
        val received = CalendarSession.received ?: return
        CalendarSession.received = null
        if (!openReceived) {
            // The host did not launch us for a placement, so this record is not ours to apply.
            // Not reachable while `begin` clears the session — which is the point of checking.
            Slog.d(TAG) { "received placement dropped: this launch did not ask for one" }
            return
        }
        if (received.target != document.target) {
            Slog.d(TAG) {
                "received placement dropped: page ${received.target.kind}/${received.target.date}/${received.target.half} is not showing"
            }
            return
        }
        val ids = received.strokeIds.toHashSet()
        val arrived = document.strokes.filter { it.id in ids }
        if (arrived.isEmpty()) return

        undo.record(InkAction.Pasted(document.pageId, arrived, arrived.map { document.orderOf(it.id) ?: 0L }))

        var box = arrived.first().bounds
        for (i in 1 until arrived.size) box = box.union(arrived[i].bounds)
        // The write lands AFTER the tool change, never before it (the notebook's O2 lesson, kept
        // here for the same reason): a tool change dismisses any live selection, and that dismissal
        // runs `restoreToolAfterReceive` — which would consume this very field and put the pen back
        // under the selection we are about to make.
        val prior = paper.tool
        if (prior != Tool.LASSO) {
            paper.tool = Tool.LASSO
            toolbar.sync(Tool.LASSO)   // a host-initiated tool change is never echoed back
            toolBeforeReceive = prior
        }
        val selection = Selection(ids, emptySet(), box)
        paper.setSelection(ids, emptySet(), box)
        selectionActive = true
        currentSelection = selection
        selectionToolbar.show(box)
        pushExclusions()
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

    /** A calendar that opened nothing is explained, not toasted — then it leaves the way every exit does. */
    private fun failOpen() {
        binding.openingOverlay.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.calendar_store_failed_title)
                .setMessage(R.string.calendar_store_failed_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── g-paper → the document ───────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!opened || closing) return
            document.addStroke(stroke)
            undo.record(InkAction.Drew(document.pageId, stroke))
            scheduleSave()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened || closing) return
            document.erase(strokeIds)?.let { undo.record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened || closing) return
            document.move(move.strokeIds, move.dx, move.dy)?.let { undo.record(it); scheduleSave() }
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            currentSelection?.let { selectionToolbar.show(it.bounds); pushExclusions() }
        }

        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown immediately, not through the pen-idle gate: a lasso ends with the pen still
            // hovering — this frame is part of the engine's own presentation of the box.
            selectionToolbar.show(selection.bounds)
            pushExclusions()
        }

        override fun onSelectionDragStarted() {
            selectionToolbar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
            pushExclusions()
            restoreToolAfterReceive()
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
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

    /** Serialise every page/undo/flush mutation; ignore anything while not open or once closing. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StoreUnavailable) {
                    Log.w(TAG, "store unavailable", e)
                    showProblem(R.string.calendar_store_failed_title, R.string.calendar_store_failed_body)
                } catch (t: Throwable) {
                    Log.w(TAG, "page op failed", t)
                }
            }
        }
    }

    /**
     * The one road every navigation takes: put the move's page on the paper, then record that it
     * landed. The order matters — [CalendarNavigation.shown] is what moves the anchor, and a show
     * that threw (a store gone out from under us) must leave the organizer exactly where it was.
     */
    private suspend fun showMove(m: CalendarNavigation.Move, firstLoad: Boolean = false) {
        document.show(m.target)
        nav.shown(m)
        showPage(firstLoad)
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
        val t = document.target
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

    /** The hour the clock says, for the half a Day page opens on. */
    private fun nowHour(): Int = LocalTime.now().hour

    private suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        val g = undo.generation
        try {
            document.revert(a)
        } catch (t: Throwable) {
            undo.pushUndo(a)
            throw t
        }
        // A pen-up landing mid-replay recorded a fresh edit, which cleared redo — honour
        // record-clears-redo rather than re-populating redo with the entry we just undid.
        if (undo.generation == g) undo.pushRedo(a)
        showPage()
    }

    private suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        try {
            document.reapply(a)
        } catch (t: Throwable) {
            undo.pushRedo(a)
            throw t
        }
        undo.pushUndo(a)
        showPage()
    }

    /**
     * Put the document's page on the paper. The order is the host-responsibilities page-swap law:
     * `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` / `setTemplate`
     * → `loadStrokes`, which is a single EPD refresh. Any selection goes first, because a data-in
     * call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean = false) {
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(document.pageWidth.toInt(), document.pageHeight.toInt())
        paper.setTemplate(bakeTemplate())
        paper.loadStrokes(document.strokes)
        toolbar.setTitle(titleOf(document.target))
        // The latch says what is on the paper. It rides this frame; it is never one of its own.
        toolbar.setView(document.target.kind)
    }

    /** The page's grid at the page's own size, under the bars as they are laid out now — the three
     *  layouts dispatched by the showing page's kind. */
    private fun bakeTemplate(): android.graphics.Bitmap {
        val today = LocalDate.now()
        bakedToday = today
        val density = resources.displayMetrics.density
        val t = document.target
        val notes = getString(R.string.calendar_notes_label)
        return when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, today, density, palette, notes)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette, notes)
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, today, density, palette, notes)
        }
    }

    private fun monthGeometry() = CalendarGeometry.month(
        document.pageWidth.toInt(), document.pageHeight.toInt(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
    )

    private fun weekGeometry() = CalendarGeometry.week(
        document.pageWidth.toInt(), document.pageHeight.toInt(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
    )

    private fun dayGeometry() = CalendarGeometry.day(
        document.pageWidth.toInt(), document.pageHeight.toInt(),
        resources.displayMetrics.density, binding.topBar.height, binding.bottomBar.height,
    )

    private fun titleOf(t: CalendarTarget): String = when (t.kind) {
        CalendarTarget.KIND_MONTH -> CalendarDates.monthTitle(t.localDate)
        CalendarTarget.KIND_WEEK -> CalendarDates.weekTitle(t.localDate)
        else -> CalendarDates.dayTitle(t.localDate, t.half)
    }

    private fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toList()
        if (ids.isEmpty()) { paper.clearSelection(); return }
        document.erase(ids)?.let { undo.record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    private fun showProblem(titleRes: Int, bodyRes: Int) {
        if (isFinishing || isDestroyed || problemShowing) return
        problemShowing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener { problemShowing = false }
                .create()
        ).show()
    }

    /**
     * Put back the tool a received placement took away (Y3) — **only if the lasso is still armed**,
     * so a tool the user picked while the selection was up wins, and **pen-idle**, because this is a
     * chrome frame like any other. One shot: the field is cleared whichever way it goes.
     */
    private fun restoreToolAfterReceive() {
        val prior = toolBeforeReceive ?: return
        toolBeforeReceive = null
        if (paper.tool != Tool.LASSO) return
        whenPenIdle {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenPenIdle
            paper.tool = prior
            toolbar.sync(prior)
        }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        binding.root.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    // ── Send (calendar → notebook) ───────────────────────────────────────────

    /** The top bar's Send: this whole page, in writing order. */
    private fun sendPage() = send(null)

    /** The selection bar's Send: what the lasso caught. The ids are read **now** — the selection can
     *  die between the tap and the page-op lock, and what the user pointed at is what they meant. */
    private fun sendSelection() {
        val ids = currentSelection?.strokeIds?.toHashSet() ?: return
        send(ids)
    }

    /**
     * Park [ids] (or the whole page when null) for the host to drain, and leave with
     * [ExtensionContract.RESULT_CALENDAR_SEND]. **Send is a copy** — the page keeps its ink, and
     * nothing goes on the undo stack. The page is flushed first, under the same lock every other page
     * op takes. An empty pick is a dialog, never silence.
     */
    private fun send(ids: Set<String>?) {
        if (!opened || closing) return
        runPageOp {
            document.flushUntilClean()
            val picked = if (ids == null) document.strokes else document.strokes.filter { it.id in ids }
            val wire = InkWire.toWireStrokes(picked)
            if (wire.isEmpty()) {
                showProblem(R.string.calendar_nothing_to_send_title, R.string.calendar_nothing_to_send_body)
                return@runPageOp
            }
            CalendarSession.outbound = InkChunks.chunk(wire)
            CalendarSession.outboundPageWidth = document.pageWidth
            CalendarSession.outboundPageHeight = document.pageHeight
            Slog.d(TAG) { "send: ${wire.size} strokes in ${CalendarSession.outbound.size} chunks" }
            closing = true
            binding.root.removeCallbacks(saveRunnable)
            finishWithHandoff(ExtensionContract.RESULT_CALENDAR_SEND)
        }
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /** Debounced: a hand writing a line would otherwise write a statement per stroke. */
    private fun scheduleSave() {
        binding.root.removeCallbacks(saveRunnable)
        binding.root.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun pushExclusions() {
        if (::chrome.isInitialized) chrome.pushExclusions()
    }

    /** The free band between the two bars, in root coordinates. Null until both are laid out. */
    private fun chromeBand(): IntRange? {
        val top = binding.topBar
        val bottom = binding.bottomBar
        if (top.height == 0 || bottom.height == 0) return null
        return top.bottom..bottom.top
    }

    /** The paper surface in px — a page with no size of its own takes it. Before the first layout
     *  the screen itself is the honest answer: the calendar is full-bleed and portrait-locked. */
    private fun surfaceSize(): Pair<Float, Float> {
        val v = paper.asView()
        if (v.width > 0 && v.height > 0) return v.width.toFloat() to v.height.toFloat()
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (opened && ::gestures.isInitialized) gestures.onTouchEvent(ev)
        if (::chrome.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !paper.isPenActive && chrome.overChrome(ev)) paper.releaseRender()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
        // A date rolled over while the screen sat in the background: the ring moves with it. Only
        // when it did — a resume is otherwise not a frame.
        if (opened && !closing && bakedToday != null && bakedToday != LocalDate.now()) {
            paper.setTemplate(bakeTemplate())
        }
    }

    override fun onPause() {
        super.onPause()
        if (!opened || closing || !::document.isInitialized) return
        binding.root.removeCallbacks(saveRunnable)
        val doc = document
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { doc.flushUntilClean() }.onFailure { Log.w(TAG, "pause flush failed", it) } }
            }
        }
    }

    /** Every exit flushes and then hands the pipeline off — **the flush is awaited before `finish()`**. */
    private fun exit() {
        if (closing) return
        closing = true
        binding.root.removeCallbacks(saveRunnable)
        if (!::document.isInitialized) { finishWithHandoff(); return }
        val doc = document
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { doc.flushUntilClean() }.onFailure { Log.w(TAG, "final flush failed", it) } }
            }
            if (!isFinishing && !isDestroyed) finishWithHandoff()
        }
    }

    /** `releaseForHandoff()` and then `finish()` — the whole of this screen's half of the EPD handoff. */
    private fun finishWithHandoff(resultCode: Int = Activity.RESULT_CANCELED) {
        if (::paper.isInitialized) paper.releaseForHandoff()
        setResult(resultCode)
        Slog.d(TAG) { "finishing (handoff released, result=$resultCode)" }
        finish()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(saveRunnable)
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CalendarActivity"

        /** Quiet time before the page's op log is written. */
        const val SAVE_DEBOUNCE_MS = 800L

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
