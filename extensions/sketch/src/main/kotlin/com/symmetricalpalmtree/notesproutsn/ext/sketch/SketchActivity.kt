package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.RasterPatch
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.sketch.databinding.ActivitySketchBinding
import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost
import com.symmetricalpalmtree.notesproutsn.extension.PngHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchPageState
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.PaperScreenActivity
import com.symmetricalpalmtree.notesproutsn.ink.awaitPenIdle
import com.symmetricalpalmtree.notesproutsn.notebook.CollapsedChrome
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
 * The extension-owned **Sketch** screen (arc 43 / K5; UI-rule tier 2) — a raster page beside the
 * notebook's ink, in the extension's own process, built on `:ext-ink`'s [PaperScreenActivity]: the
 * chrome bars and their toggle, the collapsed corner button, the free band, the stylus-vs-finger
 * dispatch, the pen-idle gate and **the EPD handoff** are all there. What is here is what a *raster
 * page* is.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be — the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * ## What is different from every other paper screen
 *
 * - **There is no store and there are no rows.** The pixels live in the host's `.soil` and cross the
 *   seam as chunked PNG ([SketchSaver] out, [loadPage] in). Nothing here writes to disk, ever.
 * - **A mark is not an object.** `pageMode = RASTER` is set once, before any content: the engine
 *   composites each mark into one page-sized image at pen-up and drops the stroke, so
 *   `onStrokeCommitted` still fires and is **deliberately ignored** — storing that stroke would be
 *   storing a row for something that is already pixels.
 * - **Undo is pixels — and, since K5b, one page.** The before-image of everything one contact
 *   changed is read on a 64 px grid ([RasterTiles]) between `onRasterWillChange` and `onPenLifted`,
 *   and taken back with `swapPageRaster`, which leaves the arrays holding the other side — so one
 *   entry is its own redo. The history is bounded by **bytes** as well as by count
 *   ([SketchEdit.UNDO_BUDGET_BYTES]). The one entry that holds no pixels is a page insert or delete
 *   ([SketchEdit.Structural]): it holds the host's *name* for that edit, and replaying it is a
 *   Binder call that runs the notebook's own undo arm ([applyStructural]).
 * - **Undo and redo are gestures only** (decision 12): the two- and three-finger stationary
 *   double-taps every SN paper screen has. No arrows — an arrow that cannot be redrawn when its
 *   state changes (which is every moment the pen is armed on this panel) is an arrow that lies.
 * - **The screen turns, inserts and deletes its own pages** (decision 7, as the user amended it on
 *   2026-09-15 — K5b) and the notebook catches up when it closes. At either edge of a *turn* the
 *   host answers the same page and the screen stays put, silently ([PageTurn]); a swipe past the
 *   **last** page makes one instead, and a two-finger swipe makes one either side, exactly as the
 *   notebook and the Scratch Pad do. An insert or a delete is **the notebook's** structural edit —
 *   the snapshot that makes it reversible goes on the notebook's undo stack, never on this one —
 *   while this screen's *pixel* history is re-indexed around it ([UndoRedoStack.remap]) and keeps a
 *   name for it, so **this screen's own undo gesture reverses a page too** (the user's follow-up
 *   decision, 2026-09-15: nobody should have to go back to the notebook to take back a delete).
 * - **Plain white paper, always** (decision 10): no template ever crosses the seam.
 *
 * ## Frame silence
 *
 * No app frame while `paper.isPenActive`. The page indicator waits for the gate ([SketchToolbar]),
 * and so does the debounced save's copy. The frames that do not are the notebook's recorded
 * exceptions in their sketch form: the "Opening…" box's hide when the page lands, a problem dialog
 * at a deliberate tap (a failed bake, a failed final flush), and the chrome flip at a finger
 * double-tap (arc 33's exception 6, never pen-idle-gated).
 */
class SketchActivity : PaperScreenActivity() {

    private lateinit var binding: ActivitySketchBinding
    private lateinit var toolbar: SketchToolbar
    private lateinit var saver: SketchSaver

    /**
     * This sitting's history — pixels, so it is bounded by bytes as well as by count. It survives a
     * page turn (each entry carries the page it happened on) and dies with the screen.
     */
    private val undo = UndoRedoStack<SketchEdit>(
        cost = { it.bytes },
        budgetBytes = SketchEdit.UNDO_BUDGET_BYTES,
    )

    /** Serialises every page / undo / bake / flush operation. */
    private val pageOps = Mutex()

    /** The page on the glass, as the host last described it. Null before the first load. */
    private var currentPage: SketchPageState? = null

    /** The open contact's before-image, from its first change to the pen lifting. */
    private var openEdit: RasterEditBuilder? = null

    /**
     * Main-thread nanoseconds spent reading the open contact's before-image — the cost of every
     * `onRasterWillChange` it received, summed. Reported with the entry (K6's measurement: what a
     * corner-to-corner hairline costs at pen-up before and after g-paper reports per run).
     */
    private var openEditReadNanos = 0L

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    /** **Two** tools on the mini toolbar, not the usual four (arc 43 / K2 grew the parameter for
     *  exactly this): the pencil and the rubber are all this surface answers. The pencil wears
     *  `ic_pen` — Tabler's pencil glyph, and the user's call. */
    override fun collapsedTools(): List<Tool> = listOf(Tool.PEN, Tool.ERASER)

    /** Back · Bring in ink · Show pages — the top bar's three doors, **mirrored**, so the row shows
     *  exactly what the bar shows and a tap performs the bar button's own click. Three is past
     *  `CollapsedTools.INLINE_MAX`, so they sit behind `…`. */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_pencil_down, binding.btnBringInk),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_page, binding.btnShowPages),
    )

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivitySketchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // **Before any content**: the mode drops what the view holds, exactly as a page turn does,
        // and it is never flipped under ink.
        paper.pageMode = PageMode.RASTER
        // No pen gestures on a sketch: a hatch is not a scribble and a closed shading loop is not a
        // selection. Armed before the listener attaches — the engine reads them as it wires up.
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(paperListener)
        Slog.d(TAG) { "engine=${paper.engineId}" }

        saver = SketchSaver(
            copyPage = { paper.getPageRaster() },
            awaitPenIdle = { paper.awaitPenIdle() },
        )

        toolbar = SketchToolbar(
            paper = paper,
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPencil = binding.btnPencil,
            btnEraser = binding.btnEraser,
            btnBringInk = binding.btnBringInk,
            btnShowPages = binding.btnShowPages,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            pageIndicator = binding.pageIndicator,
            onBack = { exit(Activity.RESULT_CANCELED) },
            onBringInk = { bringInInk() },
            onShowPages = { exit(SketchContract.RESULT_SKETCH_SHOW_PAGES) },
            onPrevPage = { turnPage(SketchContract.PAGE_PREV) },
            onNextPage = { turnPage(SketchContract.PAGE_NEXT) },
            onToolTapped = { dismissCollapsed() },
            onSynced = { syncCollapsed() },   // arc 36: the corner button repaints with the bar
            // Release builds never attach it: the door is compiled out with the branch.
            onIndicatorLongPress = if (BuildConfig.DEBUG) ({ fillTestPattern() }) else null,
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it: a mark composited now would
            // be thrown away by the load that follows, with nowhere to have been recorded.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { false },   // nothing on this surface claims finger input
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit(Activity.RESULT_CANCELED) }
        })
        pushExclusions()

        if (SketchSession.host == null) {
            // `begin` never ran, or the host tore the showing down under us. Nothing to show, and
            // nothing that could be saved — say so and leave through the handoff like every exit.
            Log.w(TAG, "no host binder for this showing")
            failOpen()
            return
        }
        SketchSession.flushHook = flushHook
        SketchSession.beginListener = beginListener
        lifecycleScope.launch { openPage() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openPage() {
        val state = callHost { it.current() }.getOrElse {
            Log.w(TAG, "the host would not answer current(): ${it.javaClass.simpleName}")
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        loadPage(state, firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to drawing,
        // which would hold the box up over the page the user asked for.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${state.pageIndex + 1}/${state.pageCount} open (${state.sketchBytes} B)" }
    }

    /**
     * Put the page [state] describes on the paper: pull its PNG out of the host's read window, decode
     * it, and load it — the host-responsibilities page-swap order with `loadPageRaster` where
     * `loadStrokes` would be (`clearForContentSwap` → `setPageSize` → the content call, one EPD
     * refresh, no blank flash).
     *
     * **The header guard runs before the decode** ([PngHeader]) and a mismatch starts the page blank
     * with a line in the log rather than handing a foreign blob's idea of its own size to the
     * allocator. The engine copies the bitmap in, so the decode is let go of the instant it returns:
     * a page-sized bitmap held one turn longer than it is needed is ~9.5 MB on a device that kills
     * processes for less.
     */
    private suspend fun loadPage(state: SketchPageState, firstLoad: Boolean) {
        val png = readSketch(state)
        val bitmap = withContext(Dispatchers.IO) { RasterImage.decode(png, state.width, state.height) }
        if (isFinishing || isDestroyed) { bitmap?.recycle(); return }
        // A contact that never got its pen-up (the panel slept mid-sweep) leaves a half-gathered
        // entry tagged with the page being left. Carried across the turn it would go on collecting
        // the next page's cells under the old page's key. That gathering is one movement of the hand
        // that cannot be taken back now; the honest loss.
        openEdit = null
        dismissCollapsed()   // a floating row never survives a content swap
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(state.width, state.height)
        paper.setTemplate(null)   // plain white always (decision 10) — no template ever crosses
        // Silent since g-paper 0.1.33 (K6): a page we loaded ourselves is our own news, so no
        // will-change/changed pair arrives and nothing here has to swallow one.
        paper.loadPageRaster(bitmap)
        bitmap?.recycle()
        currentPage = state
        saver.pageKey = state.pageKey
        saver.markClean()
        toolbar.setPage(state.pageIndex + 1, state.pageCount)
    }

    /** The read window, chunk by chunk — an empty answer is a page with no sketch, which is the
     *  window's shape for it (one empty chunk) and not a failure. */
    private suspend fun readSketch(state: SketchPageState): ByteArray {
        if (!state.hasSketch) return ByteArray(0)
        return callHost { host ->
            val chunks = ArrayList<ByteArray>(state.sketchChunks)
            for (i in 0 until state.sketchChunks) chunks += host.readSketchChunk(i)
            ByteChunks.join(chunks)
        }.getOrElse {
            Log.w(TAG, "the page's sketch could not be read: ${it.javaClass.simpleName}")
            ByteArray(0)
        }
    }

    // ── Page turns ───────────────────────────────────────────────────────────

    /**
     * Ask the host to move one page, and follow it.
     *
     * **This page's pixels go first.** The host's read window is what the screen is about to read
     * back, and a save left in flight would land on the page it just left — the contract says so and
     * this is the line that obeys it. A flush that fails parks its bytes (they are re-pushed at
     * `end()`); the turn goes ahead rather than trapping the hand on one page.
     *
     * **At the edge the host answers the same page** and the screen stays put: no dialog, no toast,
     * and the arrows never disable.
     */
    private fun turnPage(direction: Int) = runPageOp { turnPageNow(direction) }

    /** [turnPage]'s body, already inside the page-op lock — so the swipe-past-the-last-page gesture
     *  can decide between a turn and an insert without taking the lock twice. */
    private suspend fun turnPageNow(direction: Int) {
        val from = currentPage ?: return
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the turn; its pixels are parked")
        val to = callHost { it.requestPage(direction) }.getOrElse {
            Log.w(TAG, "the host would not turn the page: ${it.javaClass.simpleName}")
            hostGone()
            return
        }
        if (PageTurn.isEdge(from.pageKey, to.pageKey)) {
            Slog.d(TAG) { "page turn refused at the edge (page ${from.pageIndex + 1}/${from.pageCount})" }
            return
        }
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "turned to page ${to.pageIndex + 1}/${to.pageCount} (${to.sketchBytes} B)" }
    }

    // ── Page insert and delete (K5b) ────────────────────────────────────

    /**
     * Ask the host to make a page on the side [direction] names, and land on it.
     *
     * **This page's pixels go first**, exactly as a turn flushes them and for the same reason: the
     * host's read window is about to move, and a save left in flight would land on the page being
     * left.
     *
     * The insert is the **notebook's** structural edit — the host records the snapshot that makes it
     * reversible on the notebook's own undo stack — but this screen's history gains a *name* for it
     * ([rememberStructural]), so the undo gesture here reverses it too.
     *
     * The history also needs the **arithmetic**: a page inserted at position `p` pushes every later
     * page one along, and every entry recorded against one of those pages is now a page out of step
     * with the replay's walk ([PageTurn.reindexAfterInsert]).
     */
    private suspend fun insertPageNow(direction: Int) {
        if (currentPage == null) return
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the insert; its pixels are parked")
        val to = callHost { it.insertPage(direction) }.getOrElse {
            Log.w(TAG, "the host would not insert a page: ${it.javaClass.simpleName}")
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            return
        }
        val at = to.pageIndex
        undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        // Recorded AFTER the re-index, so the entry is never shifted by its own insert — and
        // through `record`, which clears the redo side: a new edit forks the history here exactly
        // as it does on the host's ledger, and the two must fork together.
        rememberStructural(SketchEdit.PageInserted(to.structuralToken, to.pageKey, at), to)
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "inserted page ${to.pageIndex + 1}/${to.pageCount}" }
    }

    /**
     * Ask the host to delete the page on the glass, and land on whatever it answers with.
     *
     * **The doomed page IS flushed first** — which K5b's first half did not do, and the second half
     * changes deliberately. While a delete could only be taken back from the notebook, encoding a
     * page image for a row about to be soft-deleted was work for nothing. Now the same gesture that
     * deletes the page can put it back, and what comes back is whatever was last **saved**: the save
     * is debounced by seconds, so a mark made shortly before the long-press would otherwise be the
     * one thing the undo could not return. A few hundred milliseconds on a path the person has just
     * spent a dialog on is the cheaper side of that trade. A flush that fails parks its bytes and the
     * delete goes ahead, exactly as a turn's does.
     *
     * After the flush the timers come down and the page is marked clean, so no debounced save can
     * fire into the gap. A push already in the air when the row goes cannot be prevented from
     * here: it arrives at a host that no longer has the page and is refused with
     * `IllegalArgumentException("Unknown page")`, which [SketchSaver] parks. `end()` re-pushes the
     * park once, is refused again, and drops it with a line — which is correct: those pixels were
     * for a page the person deleted. The park is cleared here for the ordinary case, where something
     * was already sitting in it.
     *
     * Deleting the notebook's **only** page answers a fresh blank page (the session's own rule) and
     * this simply loads whatever comes back.
     */
    private suspend fun deletePageNow() {
        val here = currentPage ?: return
        val gone = here.pageKey
        val at = here.pageIndex
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the delete; its pixels are parked")
        saver.cancelTimers()
        saver.markClean()
        val to = callHost { it.deletePage(gone) }.getOrElse {
            Log.w(TAG, "the host would not delete the page: ${it.javaClass.simpleName}")
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            return
        }
        // Whatever was owed for the page that has gone is owed no longer.
        SketchSession.pending.clear(gone)
        undo.remap(dropPixelsOf(gone, at))
        // The page can be asked back for from here (K5b's second half) — its entry names the host's
        // record of the delete, and the host still holds it.
        rememberStructural(SketchEdit.PageDeleted(to.structuralToken, gone, at), to)
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "deleted a page; now page ${to.pageIndex + 1}/${to.pageCount} (${to.sketchBytes} B)" }
    }

    /**
     * Put a structural edit into this screen's history so its own undo gesture can reach it (K5b).
     *
     * The entry is only a **name** for the edit the host made and recorded on the notebook's own
     * stack ([SketchEdit.Structural]); a host that minted no name for it is one this screen cannot
     * ask to replay it, so nothing is recorded and the notebook's own undo is the only way back —
     * which is where it was before this half of K5b, and is said out loud rather than left as an
     * entry that would silently fail at the gesture.
     */
    private fun rememberStructural(edit: SketchEdit.Structural, landedOn: SketchPageState) {
        if (edit.token.isEmpty()) {
            Log.w(TAG, "the host named no page edit; it can only be undone from the notebook")
            return
        }
        undo.record(edit)
        Slog.d(TAG) { "page history: ${edit.javaClass.simpleName} at ${landedOn.pageIndex + 1}/${landedOn.pageCount}" }
    }

    /**
     * The history's re-index for a page that has **gone** — deleted, or an insert taken back (K5b).
     *
     * Two rules in one pass, and the difference between them is the whole care here:
     *
     * - **Pixel entries for that page are dropped, by KEY.** Their before-images belong to a row
     *   that is no longer on the paper, and the key is the only thing that certainly names the page
     *   that went (a recorded index can be a step stale). Their page may come back — but its pixels
     *   come back with it, out of the `.soil`, which is the copy that matters.
     * - **Structural entries are never dropped, not even the ones naming that same page.** They
     *   mirror the notebook's own stack one for one, and dropping one out of the middle would leave
     *   the two histories out of step — so "insert a page, then delete it" keeps both entries, and
     *   undoing twice does exactly what it says: the page comes back, then it goes again.
     */
    private fun dropPixelsOf(gone: String, at: Int): (SketchEdit) -> SketchEdit? = { edit ->
        if (edit is SketchEdit.RasterChanged && edit.pageKey == gone) null
        else edit.withIndex(PageTurn.reindexAfterDelete(edit.pageIndex, at))
    }

    /**
     * The delete door — a one-finger long-press on the paper, the Scratch Pad's shape exactly: **one
     * question rather than a one-row sheet**, because this screen has a single page action and a
     * sheet whose only row leads to a confirm would be two taps for one decision.
     *
     * The body is **content-aware**: the host is asked what else the page is carrying
     * ([ISketchHost.pageContent]) and the dialog names only that. A page with nothing on it but the
     * sketch gets no body at all — the user's call, and the right one: the sketch is on the glass in
     * front of the person about to delete it, so warning them about it would be explaining the
     * obvious while the real warning (handwriting they cannot see from here, a document they cannot
     * see at all) is the thing worth saying.
     */
    private fun confirmDeletePage() {
        if (!opened || closing) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire.
        paper.releaseRender()
        runPageOp { askAboutDelete() }
    }

    private suspend fun askAboutDelete() {
        val here = currentPage ?: return
        val bits = callHost { it.pageContent(here.pageKey) }.getOrElse {
            Log.w(TAG, "the page's content could not be read: ${it.javaClass.simpleName}")
            hostGone()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        val ink = bits and SketchContract.PAGE_HAS_INK != 0
        val document = bits and SketchContract.PAGE_HAS_DOCUMENT != 0
        val body = when {
            ink && document -> R.string.delete_page_body_both
            ink -> R.string.delete_page_body_ink
            document -> R.string.delete_page_body_document
            else -> 0
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.delete_page_title)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { deletePageNow() } }
            .setNegativeButton(R.string.cancel, null)
        if (body != 0) builder.setMessage(body)
        Dialogs.style(builder.create()).show()
    }

    // ── g-paper → the page ───────────────────────────────────────────────────

    private val paperListener: PaperListener = object : PaperListener {

        /**
         * **Deliberately ignored.** On a raster page the engine has already composited the mark into
         * the page image and dropped the object by the time this fires; it reports the stroke only so
         * that timestamps and counts come from one place. Storing it would be storing a row for
         * something that is pixels, and this screen has no rows at all. The undo entry for the same
         * mark is made from the pixels that were there **before** it — see [onRasterWillChange] and
         * [onPenLifted].
         */
        override fun onStrokeCommitted(stroke: Stroke) = Unit

        /**
         * The page image is **about to** change — the one moment the pixels that are there can still
         * be read, and so the one moment an undo entry can be made of them.
         *
         * The rect is fed to the open contact's builder, which decides how much of it actually needs
         * reading: a rubbing sweep crosses the same cells dozens of times and the grid keeps each
         * one once ([RasterTiles]).
         */
        override fun onRasterWillChange(rect: Rect) {
            if (!opened || closing) return
            val state = currentPage ?: return
            val builder = openEdit
                ?: RasterEditBuilder(state.pageKey, state.pageIndex, state.width, state.height).also {
                    openEdit = it
                    openEditReadNanos = 0L
                }
            val t0 = System.nanoTime()
            builder.touch(rect.left, rect.top, rect.right, rect.bottom) { cell ->
                // The engine's array, straight into the tile — no copy. It goes back to the engine
                // as it stands, and the swap leaves it holding the other side of the edit.
                paper.readPageRaster(Rect(cell.left, cell.top, cell.left + cell.width, cell.top + cell.height))?.pixels
            }
            openEditReadNanos += System.nanoTime() - t0
        }

        /**
         * The page image changed — a mark composited at pen-up, or one batch of a rubbing sweep.
         * This is a raster page's news that anything happened, so it is what arms the save. The rect
         * is not read: one image is written whole.
         */
        override fun onRasterChanged(rect: Rect) {
            if (!opened || closing) return
            saver.markDirty()
            saver.schedule()
        }

        /**
         * The pen left the paper: one movement of the hand is over, so the entry for it is written
         * down. A contact too big to take back records nothing and says so once — it cannot happen
         * with a page-aligned grid, and an undo that half-restores a sweep would be worse than one
         * that admits it cannot.
         */
        override fun onPenLifted() {
            closeOpenEdit()
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
    }

    /** Close the open contact's before-image into one history entry. Idempotent. */
    private fun closeOpenEdit() {
        val builder = openEdit ?: return
        openEdit = null
        if (builder.tooBig) {
            Log.w(TAG, "that contact covered more than the history can hold; it cannot be taken back")
            return
        }
        val edit = builder.build() ?: return
        undo.record(edit)
        val readMs = openEditReadNanos / 1_000_000
        Slog.d(TAG) { "undo entry: ${edit.tiles.size} tiles, ${edit.bytes} B, read $readMs ms on the main thread (${undo.undoBytes} B held)" }
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the notebook grows where you draw (K5b, the
            // notebook's and the pad's own rule). The count comes from the host's last answer, and
            // the host is asked either way, so a stale count costs one extra Binder call at worst.
            val here = currentPage ?: return@runPageOp
            if (here.pageIndex < here.pageCount - 1) turnPageNow(SketchContract.PAGE_NEXT)
            else insertPageNow(SketchContract.PAGE_NEXT)
        }
        override fun onFlipPrevious() = turnPage(SketchContract.PAGE_PREV)
        // K5b: a two-finger swipe makes a page on the side it went — the notebook's gesture, on the
        // notebook's pages, recorded on the notebook's undo stack.
        override fun onInsertAfter() = runPageOp { insertPageNow(SketchContract.PAGE_NEXT) }
        override fun onInsertBefore() = runPageOp { insertPageNow(SketchContract.PAGE_PREV) }
        override fun onUndo() = runPageOp { doReplay(undoing = true) }
        override fun onRedo() = runPageOp { doReplay(undoing = false) }
        // K5b: the long-press asks; it never acts — the pad's shape (one question, not a one-row
        // sheet), with a body that names what else goes with the page.
        override fun onPageSheetRequested() = confirmDeletePage()
        // Arc 33: a finger double-tap hides / shows the chrome. Nothing on this surface answers a
        // single tap, so there is no collision rule here.
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
        // Everything else stays the no-op default: no Contents, no trail, no selection.
    }

    // ── Undo and redo ────────────────────────────────────────────────────────

    /**
     * Take one edit back — or put it back — by swapping pixels, **and never by reloading the page.**
     *
     * That last part is the one thing about this to get right. The entry's pixels go onto the paper
     * by a swap, and a reload afterwards would read the page's stored row — which still holds the
     * image as it was *before* the swap, because the save that writes the swapped page has not
     * happened yet — and put it straight back. An undo undone by its own page load, and one that
     * would read as the gesture simply not working.
     *
     * Three things about the swap are load-bearing:
     *
     * - **The page turn comes first.** An edit made on another page is walked back to through
     *   [PageTurn], flushing the page being left, so the image the swap lands on is the current one.
     *   A page that cannot be reached (the walk hit an edge, or the index has moved under us) drops
     *   the entry with a line rather than stamping a before-image onto the wrong page.
     * - **The pen-idle gate is waited on.** The swap presents a frame, and a frame presented while
     *   the pen is on the glass is withheld by the ink pipeline and never appears.
     * - **A mark that landed while it waited makes the swap wrong, so it is not made.** The entry
     *   goes back **beneath** what landed since ([UndoRedoStack.pushUndoBeneath]) — the order the
     *   history is true in — and the gesture simply has to be repeated, which now takes the new mark
     *   first.
     */
    private suspend fun doReplay(undoing: Boolean) {
        val edit = (if (undoing) undo.popUndo() else undo.popRedo()) ?: return
        val generation = undo.generation
        val applied = try {
            when (edit) {
                // Pixels: swapped here, on this screen, with nothing crossing the seam.
                is SketchEdit.RasterChanged -> applyEdit(edit, generation, undoing)
                // A page: the host's edit and the host's replay — this screen only names it (K5b).
                is SketchEdit.Structural -> applyStructural(edit, generation, undoing)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Failed mid-replay: put the entry back so the history never silently loses a step.
            // **The one put-back path**, for both kinds — a replay that cannot land throws rather
            // than restoring the entry itself, so there is exactly one line that decides this.
            if (undoing) undo.pushUndo(edit) else undo.pushRedo(edit)
            throw t
        }
        if (!applied) return
        if (undoing) undo.pushRedo(edit) else undo.pushUndo(edit)
    }

    /** Returns whether the swap actually landed — false means the entry was put back, or dropped. */
    private suspend fun applyEdit(edit: SketchEdit.RasterChanged, generation: Int, undoing: Boolean): Boolean {
        val here = currentPage ?: return false
        if (edit.pageKey != here.pageKey && !walkTo(edit)) {
            Log.w(TAG, "an edit's page could not be reached; the entry was dropped")
            return false
        }
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        // The tiles are disjoint (the grid guarantees it), so order cannot matter — but the engine's
        // rule for overlapping patches is "reverse read order to undo, read order to redo", and
        // saying it here costs nothing and keeps the call honest against a future that overlaps.
        val tiles = if (undoing) edit.tiles.asReversed() else edit.tiles
        paper.swapPageRaster(
            tiles.map { RasterPatch(Rect(it.left, it.top, it.left + it.width, it.top + it.height), it.pixels) },
        )
        saver.markDirty()
        saver.schedule()
        Slog.d(TAG) { "${if (undoing) "undo" else "redo"}: ${edit.tiles.size} tiles swapped" }
        return true
    }

    /**
     * Take back — or put back — one **page** insert or delete (K5b, the user's follow-up decision of
     * 2026-09-15: his own undo gesture on this screen has to reverse a page delete, so he never has
     * to go back to the notebook to undo one).
     *
     * Nothing is swapped here. The entry is a name for an edit the **host** made and recorded on the
     * notebook's own undo stack, so the replay is a Binder call that runs the notebook's own undo
     * arm — the page comes back with its handwriting, its document and its sketch, at the position
     * it had — and the host moves its own stack in step. One history, seen from two screens.
     *
     * The order at the top is an ordinary turn's, because that is what this is about to be:
     *
     * - **This page's pixels go first.** The window is about to move to another page, and a save
     *   left in flight would land on the page being left. A flush that fails parks its bytes and the
     *   replay goes ahead rather than trapping the hand.
     * - **The pen-idle gate is waited on**, and a mark that landed while it waited makes the replay
     *   something the person did not ask for — so the entry goes back **beneath** what landed
     *   ([UndoRedoStack.pushUndoBeneath]) and the gesture simply has to be repeated. [applyEdit]'s
     *   rule exactly, for the same reason and in the same words.
     *
     * A token the host no longer knows (the showing was rebuilt underneath us, or the ledger's bound
     * let the oldest edit go) is an `IllegalArgumentException` and **drops the entry**: this screen
     * cannot name that edit any more, and the notebook's own undo still can. Anything else is a real
     * failure — it is said in a dialog and thrown, so [doReplay]'s one catch puts the entry back.
     */
    private suspend fun applyStructural(edit: SketchEdit.Structural, generation: Int, undoing: Boolean): Boolean {
        if (currentPage == null) return false
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the page replay; its pixels are parked")
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the page replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        val to = callHost { if (undoing) it.undoPage(edit.token) else it.redoPage(edit.token) }.getOrElse { t ->
            if (t is IllegalArgumentException) {
                Log.w(TAG, "the host no longer knows that page edit; the entry was dropped")
                return false
            }
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            throw t
        }
        // Whether the page this entry is about is on the paper afterwards decides the arithmetic:
        // undoing a delete and redoing an insert bring it back, the other two take it away.
        //
        // **The entry's own index is the position, in both directions** — deliberately, rather than
        // the host's answer in the one direction that offers one. The two are the same number when
        // nothing has drifted, and taking it from one place is what makes an undo and the redo after
        // it exact inverses ([PageTurn.reindexAfterInsert] then `reindexAfterDelete` at the same
        // position is the identity): an entry re-indexed by two different numbers would leave the
        // history a page out after a round trip. The entry being replayed is already off the stack,
        // so it is never shifted by its own position.
        //
        // **A replay never drops pixel entries — only a fresh delete does** ([dropPixelsOf] is the
        // delete door's). Undoing an insert takes the page away, but every mark made on it since is
        // sitting on the REDO side, one step above this entry, waiting for the redo that brings the
        // page back — dropping them here is what lost the drawing in "insert, draw, undo, undo,
        // redo, redo" (the user's Nomad walk, 2026-09-15). And redoing a delete has nothing left to
        // drop: the delete itself dropped them, and a mark made after its undo clears the redo side
        // the delete entry was on. An entry for a page that is not on the paper cannot be replayed
        // in any case — its walk fails and drops it then, with a line.
        val at = edit.pageIndex
        val back = if (undoing) edit is SketchEdit.PageDeleted else edit is SketchEdit.PageInserted
        if (back) undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        else undo.remap { it.withIndex(PageTurn.reindexAfterDelete(it.pageIndex, at)) }
        loadPage(to, firstLoad = false)
        Slog.d(TAG) {
            "${if (undoing) "undo" else "redo"} of ${edit.javaClass.simpleName}: " +
                "now page ${to.pageIndex + 1}/${to.pageCount} (${to.sketchBytes} B)"
        }
        return true
    }

    /**
     * Walk back to the page [edit] was made on, one `requestPage` at a time, bounded by the distance
     * it recorded ([PageTurn.maxSteps]) and stopped early by an edge. The page being left is flushed
     * first, exactly as an ordinary turn flushes it.
     */
    private suspend fun walkTo(edit: SketchEdit.RasterChanged): Boolean {
        var here = currentPage ?: return false
        val direction = PageTurn.directionTowards(here.pageIndex, edit.pageIndex) ?: return false
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the replay's turn; its pixels are parked")
        var steps = PageTurn.maxSteps(here.pageIndex, edit.pageIndex)
        var moved = false
        while (steps-- > 0 && here.pageKey != edit.pageKey) {
            val next = callHost { it.requestPage(direction) }.getOrNull() ?: break
            if (PageTurn.isEdge(here.pageKey, next.pageKey)) break   // the edge: it is not that way
            here = next
            moved = true
        }
        // Whatever happened, the screen follows where the host's target actually ended up. A walk
        // that gave up halfway has already moved the host's window, and leaving the two disagreeing
        // about which page is showing would make the next turn read an edge that is not there.
        if (moved) loadPage(here, firstLoad = false)
        return here.pageKey == edit.pageKey
    }

    // ── "Bring in ink" (decision 8) ──────────────────────────────────────────

    /**
     * The page's own handwriting, composited onto the sketch — **as drawn, in black pen**, one undo
     * entry, and one way only: pixels never become strokes again.
     *
     * The door opens and closes **its own** [RasterEditBuilder] around the `addStrokes`, because a
     * composited bake produces no pen-up and `onPenLifted` is what closes an ordinary contact's. The
     * engine reports the bake's change like any other, so the builder fills itself through
     * [onRasterWillChange] while the call runs.
     *
     * A page with no bare ink answers **0 chunks** — a legal answer, not a refusal — and gets an
     * alert that says so. A page dense past the transfer caps arrives cut; what arrived is baked.
     */
    private fun bringInInk() {
        if (!opened || closing) return
        runPageOp {
            val state = currentPage ?: return@runPageOp
            val count = callHost { it.requestInk(state.pageKey) }.getOrElse {
                Log.w(TAG, "the page's ink could not be staged: ${it.javaClass.simpleName}")
                showProblem(R.string.sketch_bring_ink_failed_title, R.string.sketch_bring_ink_failed_body)
                return@runPageOp
            }
            if (count == 0) {
                showProblem(R.string.sketch_no_ink_title, R.string.sketch_no_ink_body)
                return@runPageOp
            }
            val wire = callHost { host ->
                val all = ArrayList<WireStroke>()
                for (i in 0 until count) all += host.readInkChunk(i)
                all
            }.getOrElse {
                Log.w(TAG, "the page's ink could not be read: ${it.javaClass.simpleName}")
                showProblem(R.string.sketch_bring_ink_failed_title, R.string.sketch_bring_ink_failed_body)
                return@runPageOp
            }
            val strokes = InkBake.toBakedStrokes(wire)
            if (strokes.isEmpty()) {
                showProblem(R.string.sketch_no_ink_title, R.string.sketch_no_ink_body)
                return@runPageOp
            }
            composite(state, strokes)
            Slog.d(TAG) { "brought in ${strokes.size} stroke(s) over $count chunk(s)" }
        }
    }

    /**
     * Composite [strokes] onto the page as **one** history entry — the bake door's body, and the
     * debug fill door's. Any contact still gathering is closed first: its tiles are its own movement
     * of the hand, not this one's.
     */
    private fun composite(state: SketchPageState, strokes: List<Stroke>) {
        closeOpenEdit()
        openEdit = RasterEditBuilder(state.pageKey, state.pageIndex, state.width, state.height)
        try {
            paper.addStrokes(strokes)
        } finally {
            closeOpenEdit()
        }
        saver.markDirty()
        saver.schedule()
    }

    /**
     * **Debug only — a walk aid, never in a release build.** A long press on the page indicator
     * composites a fixed diagonal lattice of pencil strokes, recorded as one undo entry exactly like
     * a bake.
     *
     * It exists because adb cannot draw: the Supernote's firmware ink is invisible to `screencap`
     * and there is no gesture a shell can inject that puts graphite on a raster page. Without this
     * door an agent-driven walk can prove that a save *runs*, but never that a non-blank page
     * round-trips — which is the one thing worth proving about a surface whose drawing has no other
     * copy. The branch is `if (BuildConfig.DEBUG)` at the one call site, so release never compiles
     * the listener in.
     */
    private fun fillTestPattern() {
        if (!BuildConfig.DEBUG) return
        // Through the page-op lock like every other thing that touches the paper: a walk taps this
        // while a turn or a replay may still be in flight.
        runPageOp { fillTestPatternNow() }
    }

    private fun fillTestPatternNow() {
        val state = currentPage ?: return
        val w = state.width.toFloat()
        val h = state.height.toFloat()
        val strokes = ArrayList<Stroke>(TEST_PATTERN_LINES)
        for (i in 0 until TEST_PATTERN_LINES) {
            val t = (i + 1f) / (TEST_PATTERN_LINES + 1f)
            val points = ArrayList<StrokePoint>(TEST_PATTERN_POINTS)
            for (p in 0 until TEST_PATTERN_POINTS) {
                val u = p / (TEST_PATTERN_POINTS - 1f)
                points += StrokePoint(
                    x = (w * 0.1f) + u * (w * 0.8f),
                    y = (h * t * 0.8f) + (h * 0.1f) + u * (h * 0.08f),
                    pressure = 0.5f,
                )
            }
            strokes += Stroke(
                id = "test-$i-${System.nanoTime()}",
                points = points,
                color = SketchToolbar.PENCIL_COLOR,
                width = SketchToolbar.PENCIL_WIDTH_PX,
                style = StrokeStyle.PENCIL,
            )
        }
        composite(state, strokes)
        Log.w(TAG, "debug fill door: ${strokes.size} test strokes composited")
    }

    // ── Page operations ──────────────────────────────────────────────────────

    /** Serialise every page / undo / bake / flush operation; ignore anything while not open or once
     *  closing. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "page op failed", t)
                }
            }
        }
    }

    // ── The host binder ──────────────────────────────────────────────────────

    /** One Binder call, off the main thread, with every failure captured — the caller decides what a
     *  failure means, because "the host is gone" and "that page has no ink" arrive the same way. */
    private suspend fun <T> callHost(block: (ISketchHost) -> T): Result<T> {
        val host = SketchSession.host ?: return Result.failure(IllegalStateException("no showing"))
        return try {
            Result.success(withContext(Dispatchers.IO) { block(host) })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** The notebook is no longer reachable: say so, then leave through the handoff like every exit.
     *  The park keeps whatever pixels never landed — `end()` is where they get their last try. */
    private fun hostGone() {
        if (closing || isFinishing || isDestroyed) return
        closing = true
        saver.cancelTimers()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_host_gone_title)
                .setMessage(R.string.sketch_host_gone_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    /** A screen that opened nothing is explained, not toasted — then it leaves the way every exit
     *  does. */
    private fun failOpen() {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_host_gone_title)
                .setMessage(R.string.sketch_host_gone_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── The showing's two hooks ──────────────────────────────────────────────

    private val flushHook = object : SketchSession.FlushHook {
        override fun flushBlocking() = saver.flushBlocking()
        override fun pushBlocking(pageKey: String, png: ByteArray) = saver.pushBlocking(pageKey, png)
    }

    /**
     * The host restarted. A page key is the page's own id, so it is still the same page on the other
     * side — there is no key to re-check the way the document editor has to. What is owed is
     * whatever was parked, and whatever is on the glass that the new host has never seen.
     */
    private val beginListener = SketchSession.BeginListener {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            Slog.d(TAG) { "host reconnected — re-offering what is owed" }
            saver.retryParked()
            saver.saveNow()
        }
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    /**
     * Every exit — Back, the top bar's Back, Show pages — **awaits the flush** and only then hands
     * the pipeline off and finishes. The host's result callback runs `end()` → unbind → revoke the
     * moment we finish, and a save left in flight would hit a revoked binder.
     *
     * A flush that fails is a drawing with no other copy, so it is a **dialog**, not a log line:
     * Try again, or Leave anyway — and "leave anyway" is not "lose", because the pixels stay parked
     * and `end()` gets one more try at them on the binder that is still valid.
     */
    private fun exit(resultCode: Int) {
        if (closing) return
        closing = true
        dismissCollapsed()
        saver.cancelTimers()
        leaveWhenFlushed(resultCode)
    }

    private fun leaveWhenFlushed(resultCode: Int) {
        appScope.launch {
            val ok = withContext(NonCancellable) { saver.flushForExit() }
            if (isFinishing || isDestroyed) return@launch
            if (ok) finishWithHandoff(resultCode) else askAboutUnsaved(resultCode)
        }
    }

    private fun askAboutUnsaved(resultCode: Int) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_not_saved_title)
                .setMessage(R.string.sketch_not_saved_body)
                .setPositiveButton(R.string.sketch_try_again) { _, _ -> leaveWhenFlushed(resultCode) }
                .setNegativeButton(R.string.sketch_leave_anyway) { _, _ -> finishWithHandoff(resultCode) }
                .setCancelable(false)
                .create()
        ).show()
    }

    override fun onScreenPaused() {
        if (!opened || closing) return
        // A durability point while backgrounded, on the saver's own scope — ours is cancelled at
        // ON_DESTROY, and a half-drawn page is the one thing worth surviving that.
        saver.saveNow()
    }

    override fun onScreenDestroyed() {
        // A refused caller (`HostCallerCheck` finished us before anything was built) still gets this
        // callback: nothing below exists then, and a `lateinit` throw here kills the whole extension
        // process — with the LEGITIMATE face in it (walked on the Nomad, K5).
        if (!::saver.isInitialized) return
        saver.cancelTimers()
        // The hooks belong to this screen and to nothing else: the service must not call into a
        // destroyed Activity's saver at `end()`.
        SketchSession.flushHook = null
        SketchSession.beginListener = null
    }

    private companion object {
        const val TAG = "SketchActivity"

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** The debug fill door's lattice — enough to be unmistakable in a `screencap`, few enough to
         *  composite in one call. */
        const val TEST_PATTERN_LINES = 12
        const val TEST_PATTERN_POINTS = 24
    }
}
