package com.symmetricalpalmtree.notesproutsn.ink

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.InkChunks
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.notebook.InkSelectionBar
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack
import com.symmetricalpalmtree.notesproutsn.screen.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The skeleton of an **ink-on-rows extension screen** (arc 11 / J4 as the Scratch Pad's, copied for
 * the calendar at arc 23 / Y1, **one class here** since the arc-23 sweep) — the tier-2 shape:
 * full-bleed g-paper in the extension's own process, two thin chrome bars, `:sn-screen`'s
 * [PageGestures] / [PaperChrome] / [UndoRedoStack] / [InkSelectionBar], and a page whose rows live
 * in the host's extension store, lent for the showing. **The extension writes nothing to disk
 * itself, ever.**
 *
 * This module is its only legal home: it needs `PaperView` from `:sn-screen` **and** the contract
 * from `:extension-api`, and `:ext-ink` is the one module that has both.
 *
 * What a subclass keeps is what is *its own* — the pad's page list and pager, the calendar's
 * navigation, template bake, picker and double-tap. What lives here is everything that was
 * byte-equivalent between the two:
 *
 * - the **page-op lock** and [runPageOp]: every page/undo/flush mutation is serialised, so two
 *   overlapping gestures cannot tangle the page and a debounced save can never run inside a swap;
 * - the **undo/redo replay** shape ([doUndo] / [doRedo]) with its record-clears-redo generation
 *   check, the put-the-entry-back-on-failure rule, and the [followReplay] hook the calendar's
 *   organizer follows the document with;
 * - [showProblem] — **one problem dialog at a time**, or a hand that keeps writing would stack one
 *   per stroke;
 * - [restoreToolAfterReceive] and [toolBeforeReceive]: the tool a received placement took away
 *   comes back **pen-idle**, and only if the lasso is still armed, so a tool the user picked
 *   meanwhile wins;
 * - the **debounced save** ([scheduleSave], bounded — what it leaves behind the next debounce picks
 *   up) against every **leave** flush (unbounded — there is no next debounce);
 * - [chromeBand] and [surfaceSize], the chrome-tap `releaseRender` in [dispatchTouchEvent];
 * - [onPause] / [exit] / [finishWithHandoff] / [onDestroy], **in their exact order**;
 * - [send] — the copy that parks chunks for the host to drain.
 *
 * **`HostCallerCheck.enforceActivity` stays the first statement of the concrete `onCreate`**, before
 * anything is inflated, and the subclass assigns [paper], [chrome], [gestures] and [selectionBar]
 * there. Nothing in this class runs before that check, because nothing in this class is an
 * `onCreate`.
 *
 * **The EPD handoff order is g-paper's law.** The caller releases (`releaseForHandoff()`)
 * immediately before launching us, we reclaim in [onResume] (`resumeDrawing`), and **every** exit
 * goes through [finishWithHandoff] — `releaseForHandoff()` and then `finish()`. The returning
 * caller reclaims the pipeline in its own `onResume`, which runs *before* our window's visibility
 * close, and a close landing after that reclaim tears the caller's live session down. A failure
 * there is fixed in g-paper, never worked around here.
 *
 * **Back awaits the flush.** The host's result callback runs `end()` → unbind → revoke the moment we
 * finish, so a save still in flight would hit a revoked binder: [exit] flushes under the page-op
 * lock first and only then hands off and finishes, and `onPause`'s flush runs `NonCancellable` on a
 * scope that outlives the Activity.
 *
 * **Frame silence:** no app frame while `paper.isPenActive`. Chrome text goes through
 * [PenIdle.whenIdle]; the frames that do not are each consumer's recorded exceptions, ledgered with
 * their justifications in that consumer's doc — the selection bar's show at lasso completion (and
 * its re-anchor after a move, and over a received placement), the "Opening…" box's hide when the
 * page lands, and a problem dialog at a pen-up or a chrome tap.
 *
 * [A] is the consumer's undo action type: the pad's `ScratchAction` (which wraps an [InkAction]
 * alongside its page-level one) or the calendar's bare [InkAction].
 */
abstract class InkScreenActivity<A : Any> : AppCompatActivity() {

    /** The surface. The subclass creates it in `onCreate` and assigns it here. */
    protected lateinit var paper: PaperView
    protected lateinit var chrome: PaperChrome
    protected lateinit var gestures: PageGestures
    protected lateinit var selectionBar: InkSelectionBar

    /** In-memory, screen-level history: it survives page turns and dies with the screen. */
    protected val undo = UndoRedoStack<A>()

    /** Serialises every page/undo/flush operation. */
    protected val pageOps = Mutex()

    protected var opened = false
    protected var closing = false
    protected var selectionActive = false
    protected var currentSelection: Selection? = null

    /**
     * The tool armed before a **received** placement selected what arrived. Put back pen-idle when
     * that selection is dismissed; null the rest of the time.
     */
    protected var toolBeforeReceive: Tool? = null

    private var problemShowing = false

    // ── What the consumer supplies ───────────────────────────────────────────

    protected abstract val logTag: String

    /** The inflated root, or **null** before it exists — a refused caller never inflates one. */
    protected abstract val screenRoot: View?
    protected abstract val topBarView: View?
    protected abstract val bottomBarView: View?
    protected abstract val openingOverlay: View?

    /** The page being written on, or null before the document is built. */
    protected abstract val inkPage: InkPage?

    protected abstract val storeFailedTitleRes: Int
    protected abstract val storeFailedBodyRes: Int
    protected abstract val nothingToSendTitleRes: Int
    protected abstract val nothingToSendBodyRes: Int

    /** The result code a Send leaves with; the host drains on the bind it is still holding. */
    protected abstract val sendResultCode: Int

    /** Park what Send picked, in the consumer's own session object. */
    protected abstract fun parkOutgoing(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float)

    /** Record a stroke-level edit, wrapped in the consumer's action type. */
    protected abstract fun record(action: InkAction)

    /** Keep the consumer's toolbar honest about the armed tool. */
    protected abstract fun syncTool(tool: Tool)

    /** Put the showing page back on the paper (the host-responsibilities page-swap order). */
    protected abstract fun showPage()

    /** Reverse [action] against the consumer's document — it lands on the action's page and flushes. */
    protected abstract suspend fun revert(action: A)

    /** [revert]'s mirror. */
    protected abstract suspend fun reapply(action: A)

    /**
     * Run after a replay has landed and before the page is shown. The calendar's organizer follows
     * the document here, or the toggles, the pager, the picker and a double-tap would all act on
     * the page the navigation still believed was showing. The pad has nothing to follow.
     */
    protected open fun followReplay() {}

    // ── The save debounce ────────────────────────────────────────────────────

    // The debounce is the one bounded flush — what it leaves behind, the next debounce picks up.
    // Every leave path (a page swap, onPause, exit) flushes until clean: there is no next one.
    private val saveRunnable = Runnable { runPageOp { inkPage?.flushUntilClean(maxPasses = InkDocument.MAX_FLUSH_PASSES) } }

    /** Debounced: a hand writing a line would otherwise write a statement per stroke. */
    protected fun scheduleSave() {
        val root = screenRoot ?: return
        root.removeCallbacks(saveRunnable)
        root.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    // ── g-paper → the document ───────────────────────────────────────────────

    /**
     * The listener both screens attach. Every arm is guarded on `opened`/`closing`: the surface
     * accepts no ink until the page is truly on it, and nothing may run against the document once
     * the store is on its way back to the host.
     */
    protected val paperListener: PaperListener = object : PaperListener {

        override fun onStrokeCommitted(stroke: Stroke) {
            if (!opened || closing) return
            val page = inkPage ?: return
            // A page has no ceiling (arc 22 / X2): every committed stroke is taken.
            page.addStroke(stroke)
            record(InkAction.Drew(page.pageId, stroke))
            scheduleSave()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened || closing) return
            inkPage?.erase(strokeIds)?.let { record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened || closing) return
            inkPage?.move(move.strokeIds, move.dx, move.dy)?.let { record(it); scheduleSave() }
            // The selection survives a move, at its new position — keep our copy honest, then bring
            // the bar back to where the box now is (the drag is over; this fires at lift).
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            currentSelection?.let { selectionBar.show(it.bounds); pushExclusions() }
        }

        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown immediately, not through the pen-idle gate: a lasso ends with the pen still
            // hovering (`isPenActive` counts proximity plus a tail), so an idle-gated bar would
            // arrive long after the selection it belongs to. The engine has already presented the
            // selection box — this frame is part of that same presentation.
            selectionBar.show(selection.bounds)
            pushExclusions()
        }

        /** The pen is dragging the box — the bar would be dragged over, and it never follows live. */
        override fun onSelectionDragStarted() {
            selectionBar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionBar.hide()
            pushExclusions()
            restoreToolAfterReceive()
        }

        override fun onToolChanged(tool: Tool) = syncTool(tool)
    }

    // ── Page operations ──────────────────────────────────────────────────────

    /** Serialise every page/undo/flush mutation; ignore anything while not open or once closing. */
    protected fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StoreUnavailable) {
                    Log.w(logTag, "store unavailable", e)
                    showProblem(storeFailedTitleRes, storeFailedBodyRes)
                } catch (t: Throwable) {
                    Log.w(logTag, "page op failed", t)
                }
            }
        }
    }

    protected suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        val g = undo.generation
        try {
            revert(a)
        } catch (t: Throwable) {
            // Failed (or cancelled) mid-replay: put the entry back so the history never silently
            // loses a step. The store ops are idempotent, so a retry converges.
            undo.pushUndo(a)
            throw t
        }
        // A pen-up landing mid-replay recorded a fresh edit, which cleared redo — honour
        // record-clears-redo rather than re-populating redo with the entry we just undid.
        if (undo.generation == g) undo.pushRedo(a)
        followReplay()
        showPage()
    }

    protected suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        try {
            reapply(a)
        } catch (t: Throwable) {
            undo.pushRedo(a)
            throw t
        }
        undo.pushUndo(a)
        followReplay()
        showPage()
    }

    protected fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toList()
        if (ids.isEmpty()) { paper.clearSelection(); return }
        inkPage?.erase(ids)?.let { record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    // ── A received placement ─────────────────────────────────────────────────

    /**
     * Show what a `receiveInk` placed, as the selection the pen can drag into place at once: the
     * **lasso is armed before `setSelection`** (a selection under the pen can neither be dragged nor
     * dismissed) and the state is set by hand, because a host-initiated selection never echoes
     * `onSelectionCreated`.
     *
     * The write lands **after** the tool change, never before it (the notebook's O2 lesson): a tool
     * change dismisses any live selection, and that dismissal runs [restoreToolAfterReceive] —
     * which would consume [toolBeforeReceive] and put the pen back under the selection we are about
     * to make.
     *
     * [ids] is what the host says it placed; [arrived] what of it is on the page (the box's source).
     */
    protected fun showArrivedSelection(ids: Set<String>, arrived: List<Stroke>) {
        var box = arrived.first().bounds
        for (i in 1 until arrived.size) box = box.union(arrived[i].bounds)
        val prior = paper.tool
        if (prior != Tool.LASSO) {
            paper.tool = Tool.LASSO
            syncTool(Tool.LASSO)   // a host-initiated tool change is never echoed back
            toolBeforeReceive = prior
        }
        val selection = Selection(ids, emptySet(), box)
        paper.setSelection(ids, emptySet(), box)
        selectionActive = true
        currentSelection = selection
        selectionBar.show(box)
        pushExclusions()
    }

    /**
     * Put back the tool a received placement took away — **only if the lasso is still armed**, so a
     * tool the user picked while the selection was up wins, and **pen-idle**, because this is a
     * chrome frame like any other. One shot: the field is cleared whichever way it goes.
     */
    protected fun restoreToolAfterReceive() {
        val prior = toolBeforeReceive ?: return
        toolBeforeReceive = null
        if (paper.tool != Tool.LASSO) return
        whenPenIdle {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenPenIdle
            paper.tool = prior
            syncTool(prior)
        }
    }

    protected fun whenPenIdle(action: () -> Unit) {
        val root = screenRoot ?: return
        PenIdle.whenIdle(paper, root, action)
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    protected fun showProblem(titleRes: Int, bodyRes: Int) {
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

    /** A screen that opened nothing is explained, not toasted — then it leaves the way every exit does. */
    protected fun failOpen() {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(storeFailedTitleRes)
                .setMessage(storeFailedBodyRes)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── Send (here → notebook) ───────────────────────────────────────────────

    /** The top bar's Send: this whole page, in writing order. */
    protected fun sendPage() = send(null)

    /** The selection bar's Send: what the lasso caught. The ids are read **now** — the selection can
     *  die (a tap-away, a flip) between the tap and the page-op lock, and what the user pointed at is
     *  what they meant to send. */
    protected fun sendSelection() {
        val ids = currentSelection?.strokeIds?.toHashSet() ?: return
        send(ids)
    }

    /**
     * Park [ids] (or the whole page when null) for the host to drain, and leave with
     * [sendResultCode].
     *
     * **Send is a copy** — the screen keeps its ink, and nothing goes on its undo stack. The page is
     * **flushed first**, under the same lock every other page op takes, so what the screen keeps and
     * what the notebook gets are the same ink. An empty pick is a dialog, never silence: a tap that
     * did nothing reads as broken on e-ink.
     *
     * The chunking is the contract's own ([InkChunks]), so the host's `takeOutgoing` loop and the
     * extension's parked list can never disagree about what one Binder call holds. The
     * whole-transfer caps are the host's to enforce as it drains — a page over them comes back cut,
     * and the host says so.
     */
    protected fun send(ids: Set<String>?) {
        if (!opened || closing) return
        runPageOp {
            val page = inkPage ?: return@runPageOp
            page.flushUntilClean()
            val picked = if (ids == null) page.strokes else page.strokes.filter { it.id in ids }
            val wire = InkWire.toWireStrokes(picked)
            if (wire.isEmpty()) {
                showProblem(nothingToSendTitleRes, nothingToSendBodyRes)
                return@runPageOp
            }
            val chunks = InkChunks.chunk(wire)
            parkOutgoing(chunks, page.pageWidth, page.pageHeight)
            Slog.d(logTag) { "send: ${wire.size} strokes in ${chunks.size} chunks" }
            // Nothing more may run against the document: the host drains and then revokes the store.
            closing = true
            screenRoot?.removeCallbacks(saveRunnable)
            finishWithHandoff(sendResultCode)
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    protected fun pushExclusions() {
        if (::chrome.isInitialized) chrome.pushExclusions()
    }

    /** The free band between the two bars, in root coordinates. Null until both are laid out. */
    protected fun chromeBand(): IntRange? {
        val top = topBarView ?: return null
        val bottom = bottomBarView ?: return null
        if (top.height == 0 || bottom.height == 0) return null
        return top.bottom..bottom.top
    }

    /** The paper surface in px — a page stored with no size of its own takes it. Before the first
     *  layout the screen itself is the honest answer: these screens are full-bleed and portrait-locked. */
    protected fun surfaceSize(): Pair<Float, Float> {
        val v = paper.asView()
        if (v.width > 0 && v.height > 0) return v.width.toFloat() to v.height.toFloat()
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observer only — consumes nothing.
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
    }

    override fun onPause() {
        super.onPause()
        if (!opened || closing) return
        val page = inkPage ?: return
        // A durability point while backgrounded, on a scope that outlives this Activity: our own
        // is cancelled at ON_DESTROY, and a half-written page is the one thing worth surviving that.
        screenRoot?.removeCallbacks(saveRunnable)
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page.flushUntilClean() }.onFailure { Log.w(logTag, "pause flush failed", it) } }
            }
        }
    }

    /**
     * Every exit — Back, the top bar's Back, the store-failure dialog, a door to another extension
     * ([resultCode] says which; the host answers it) — flushes and then hands the pipeline off. **The flush is awaited before `finish()`**: the host's result callback runs
     * `end()` → unbind → revoke immediately, and a save left in flight would hit a revoked binder.
     */
    protected fun exit(resultCode: Int = Activity.RESULT_CANCELED) {
        if (closing) return
        closing = true
        screenRoot?.removeCallbacks(saveRunnable)
        val page = inkPage ?: run { finishWithHandoff(resultCode); return }
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page.flushUntilClean() }.onFailure { Log.w(logTag, "final flush failed", it) } }
            }
            if (!isFinishing && !isDestroyed) finishWithHandoff(resultCode)
        }
    }

    /**
     * `releaseForHandoff()` and then `finish()` — the whole of this screen's half of the EPD
     * handoff, and the reason no exit here calls `finish()` on its own. See the class note.
     *
     * [resultCode] is [sendResultCode] when ink is parked for the host to drain, and
     * `RESULT_CANCELED` for every other exit; the host finishes the bind either way.
     */
    protected fun finishWithHandoff(resultCode: Int = Activity.RESULT_CANCELED) {
        if (::paper.isInitialized) paper.releaseForHandoff()
        setResult(resultCode)
        Slog.d(logTag) { "finishing (handoff released, result=$resultCode)" }
        finish()
    }

    override fun onDestroy() {
        screenRoot?.removeCallbacks(saveRunnable)
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    private companion object {

        /** Quiet time before the page's op log is written. */
        const val SAVE_DEBOUNCE_MS = 800L

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
