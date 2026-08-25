package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import com.symmetricalpalmtree.notesproutsn.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack
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
 * The extension-owned Scratch Pad screen (arc 11 / J4; UI-rule tier 2) — the notebook's shape, in
 * the extension's own process, built from `:sn-screen`: full-bleed g-paper, two thin chrome bars,
 * [PageGestures] for the finger vocabulary, [PaperChrome] for the exclusion rects,
 * [UndoRedoStack] for the history and [SelectionAnchor] (through [ScratchSelectionToolbar]) for the
 * floating bar. The pages and their persistence are [ScratchDocument]'s; the store is the host's,
 * lent for this showing — **the extension writes nothing to disk itself, ever**.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be, the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * **The EPD handoff is the arc's headline risk and this screen is one half of it.** Two paper
 * surfaces in two processes: the notebook calls `releaseForHandoff()` immediately before launching
 * us, we reclaim in [onResume] (`resumeDrawing`), and **every** exit here goes through
 * [finishWithHandoff] — `releaseForHandoff()` and then `finish()`. That ordering is load-bearing:
 * the returning caller reclaims the pipeline in its own `onResume`, which runs *before* our
 * window's visibility close, and a close landing after that reclaim tears the caller's live session
 * down. g-paper's ownership guards are process-local statics, so the departing side's release must
 * be its full teardown. A failure here goes to g-paper, never a host workaround.
 *
 * **Back awaits the flush.** The host's result callback runs `end()` → unbind → revoke the moment
 * we finish, so a save still in flight would hit a revoked binder. [exit] flushes under the page-op
 * lock first and only then hands off and finishes.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The page indicator waits for the gate
 * ([ScratchToolbar]); the four frames that do not are the notebook's own recorded exceptions, in
 * their scratch-pad form — the delete confirm at a long-press, the selection bar's show at lasso
 * completion (and its own re-anchor after a move), the "Opening…" box's hide when the page lands,
 * and a refused stroke's problem dialog at a pen-up.
 */
class ScratchPadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScratchPadBinding
    private lateinit var paper: PaperView
    private lateinit var chrome: PaperChrome
    private lateinit var toolbar: ScratchToolbar
    private lateinit var selectionToolbar: ScratchSelectionToolbar
    private lateinit var gestures: PageGestures
    private lateinit var document: ScratchDocument

    /** In-memory, pad-level history: it survives page turns and dies with the screen. */
    private val undo = UndoRedoStack<ScratchAction>()

    /** Serialises every page/undo/flush operation, so two overlapping gestures can't tangle the
     *  page list — and so a debounced save can never run inside a page swap. */
    private val pageOps = Mutex()

    private var opened = false
    private var closing = false
    private var selectionActive = false
    private var currentSelection: Selection? = null

    /** One problem dialog at a time: a hand that keeps writing on a full page would otherwise
     *  stack one per stroke. */
    private var problemShowing = false

    private val saveRunnable = Runnable { runPageOp { document.flushUntilClean() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivityScratchPadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        // Both pen-gesture recognisers on, and armed BEFORE the listener attaches (the engine reads
        // them as it wires itself up). They match the notebook deliberately: a pad one tap away
        // that lassoed differently would read as a bug.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.setPaperListener(listener)

        toolbar = ScratchToolbar(
            paper = paper,
            bottomBar = binding.bottomBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            pageIndicator = binding.pageIndicator,
            onBack = { exit() },
            // No-op at a bound, never disabled: a greyed control is invisible on e-ink.
            onPrevPage = { runPageOp { flipTo(document.pageIndex - 1) } },
            onNextPage = { runPageOp { flipTo(document.pageIndex + 1) } },
        )
        selectionToolbar = ScratchSelectionToolbar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            onDelete = { currentSelection?.let { deleteSelection(it) } },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { selectionToolbar.rects() },
            extraContains = { x, y -> selectionToolbar.contains(x, y) },
            // The surface accepts no ink until the page is truly on it: a stroke committed now
            // would be dropped by the load's `loadStrokes` with nowhere to have been recorded.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        // Chrome moved/appeared/disappeared: re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        val store = ScratchSession.store
        if (store == null) {
            // `begin` never ran, or the host tore the session down under us. Nothing to show, and
            // nothing that could be saved — say so and leave through the handoff like every exit.
            Log.w(TAG, "no store for this showing")
            failOpen()
            return
        }
        document = ScratchDocument(ScratchStore(store)) { surfaceSize() }
        lifecycleScope.launch { openDocument() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openDocument() {
        try {
            document.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scratch store unavailable", e)
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        document.adoptSurfaceSize()
        showPage(firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to writing,
        // which would hold the box up over the page the user asked for. A boundary frame, not a
        // frame during writing — nothing has been drawn yet.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${document.currentPageId} loaded: ${document.strokes.size} strokes, ${document.pageCount} pages" }
        if (document.isUnreadable) showProblem(R.string.scratch_unreadable_title, R.string.scratch_unreadable_body)
    }

    /** A pad that opened nothing is explained, not toasted — then it leaves the way every exit does. */
    private fun failOpen() {
        binding.openingOverlay.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.scratch_store_failed_title)
                .setMessage(R.string.scratch_store_failed_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── g-paper → the document ───────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!opened || closing) return
            when (document.addStroke(stroke)) {
                ScratchDocument.Add.OK -> {
                    undo.record(ScratchAction.Drew(document.currentPageId, stroke))
                    scheduleSave()
                }
                ScratchDocument.Add.PAGE_FULL ->
                    refuse(stroke, R.string.scratch_page_full_title, R.string.scratch_page_full_body)
                ScratchDocument.Add.UNREADABLE ->
                    refuse(stroke, R.string.scratch_unreadable_title, R.string.scratch_unreadable_body)
            }
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened || closing) return
            document.erase(strokeIds)?.let { undo.record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened || closing) return
            document.move(move.strokeIds, move.dx, move.dy)?.let { undo.record(it); scheduleSave() }
            // The selection survives a move, at its new position — keep our copy honest, then bring
            // the bar back to where the box now is (the drag is over; this fires at lift).
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            currentSelection?.let { selectionToolbar.show(it.bounds); pushExclusions() }
        }

        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown immediately, not through the pen-idle gate: a lasso ends with the pen still
            // hovering (`isPenActive` counts proximity plus a tail), so an idle-gated bar would
            // arrive long after the selection it belongs to. The engine has already presented the
            // selection box — this frame is part of that same presentation.
            selectionToolbar.show(selection.bounds)
            pushExclusions()
        }

        /** The pen is dragging the box — the bar would be dragged over, and it never follows live. */
        override fun onSelectionDragStarted() {
            selectionToolbar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
            pushExclusions()
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
    }

    /**
     * Put a refused stroke back off the paper and say why. Posted rather than called inline: a
     * data-in call from inside the engine's own commit callback is asking for re-entrancy, and the
     * pen has just lifted, so one frame here is a boundary frame.
     */
    private fun refuse(stroke: Stroke, titleRes: Int, bodyRes: Int) {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            paper.removeStrokes(listOf(stroke.id))
            showProblem(titleRes, bodyRes)
        }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the pad grows where you write.
            if (document.pageIndex < document.pageCount - 1) flipTo(document.pageIndex + 1)
            else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp { flipTo(document.pageIndex - 1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onPageSheetRequested() = confirmDeletePage()
        // The pad implements only what it has: SN's other callbacks (Contents, Recents, the trail
        // walk-back, link follow) stay the no-op defaults `PageGestures.Listener` already gives.
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
                } catch (e: PageFullException) {
                    showProblem(R.string.scratch_page_full_title, R.string.scratch_page_full_body)
                } catch (e: StoreUnavailable) {
                    Log.w(TAG, "store unavailable", e)
                    showProblem(R.string.scratch_store_failed_title, R.string.scratch_store_failed_body)
                } catch (t: Throwable) {
                    Log.w(TAG, "page op failed", t)
                }
            }
        }
    }

    private suspend fun flipTo(index: Int) {
        if (index < 0 || index >= document.pageCount) return   // no-op at a bound
        document.goToIndex(index)
        showPage()
    }

    private suspend fun doInsert(after: Boolean) {
        undo.record(document.insert(after))
        showPage()
    }

    private suspend fun doDelete() {
        undo.record(document.deleteCurrent())
        showPage()
    }

    private suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        val g = undo.generation
        try {
            document.revert(a)
        } catch (t: Throwable) {
            // Failed (or cancelled) mid-replay: put the entry back so the history never silently
            // loses a step. The store ops are idempotent, so a retry converges.
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
     * Put the document's current page on the paper. The order is the host-responsibilities page-swap
     * law: `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` /
     * `setTemplate` → `loadStrokes`, which is a single EPD refresh. Any selection goes first,
     * because a data-in call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean = false) {
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()   // idempotent — clearSelection fires onSelectionDismissed too
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(document.pageWidth.toInt(), document.pageHeight.toInt())
        paper.setTemplate(null)   // the pad is plain paper: no templates, ever
        paper.loadStrokes(document.strokes)
        toolbar.setPage(document.pageNumber, document.pageCount)
        if (!firstLoad && document.isUnreadable) {
            showProblem(R.string.scratch_unreadable_title, R.string.scratch_unreadable_body)
        }
    }

    private fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toList()
        if (ids.isEmpty()) { paper.clearSelection(); return }
        document.erase(ids)?.let { undo.record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    /**
     * Long-press asks; it never acts. One question rather than a one-row sheet — the pad has a
     * single page action, and a sheet whose only row leads to a confirm would be two taps for one
     * decision. The bare wording is SN's notebook one: undo puts the page **and its ink** back.
     *
     * The last page is emptied, never removed ([ScratchPages.delete]) — the pad always has a page.
     */
    private fun confirmDeletePage() {
        if (!opened || closing) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire.
        paper.releaseRender()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
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

    // ── Saving ───────────────────────────────────────────────────────────────

    /** Debounced: a hand writing a line of text would otherwise re-encode the page on every stroke. */
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

    /** The paper surface in px — a page stored with no size of its own takes it. Before the first
     *  layout the screen itself is the honest answer: the pad is full-bleed and portrait-locked. */
    private fun surfaceSize(): Pair<Float, Float> {
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
        if (!opened || closing || !::document.isInitialized) return
        // A durability point while backgrounded, on a scope that outlives this Activity: our own
        // is cancelled at ON_DESTROY, and a half-written page is the one thing worth surviving that.
        binding.root.removeCallbacks(saveRunnable)
        val doc = document
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { doc.flushUntilClean() }.onFailure { Log.w(TAG, "pause flush failed", it) } }
            }
        }
    }

    /**
     * Every exit — Back, the top bar's Back, the store-failure dialog — flushes and then hands the
     * pipeline off. **The flush is awaited before `finish()`**: the host's result callback runs
     * `end()` → unbind → revoke immediately, and a save left in flight would hit a revoked binder.
     */
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

    /**
     * `releaseForHandoff()` and then `finish()` — the whole of the pad's half of the EPD handoff,
     * and the reason no exit here calls `finish()` on its own. See the class note.
     */
    private fun finishWithHandoff() {
        if (::paper.isInitialized) paper.releaseForHandoff()
        // J4 has no transfers: every exit is a plain return. RESULT_SCRATCH_SEND arrives in J5.
        setResult(Activity.RESULT_CANCELED)
        Slog.d(TAG) { "finishing (handoff released)" }
        finish()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(saveRunnable)
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ScratchPadActivity"

        /** Quiet time before a page is re-encoded and written. */
        const val SAVE_DEBOUNCE_MS = 800L

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
