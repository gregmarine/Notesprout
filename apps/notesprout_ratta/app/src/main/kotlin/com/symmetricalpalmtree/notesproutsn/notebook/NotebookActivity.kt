package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseState
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The notebook screen: a full-bleed g-paper surface with the toolbar and the name strip overlaying
 * it. Lifecycle, wiring, chrome and exclusion rects live here; the data lives in [NotebookSession]
 * / [StrokeStore]; the cover in [CoverSnapshot]; the buttons in [NotebookToolbar]; the selection's
 * floating bar in [SelectionToolbar]; the finger vocabulary in [PageGestures] and the history in
 * [UndoRedoStack].
 *
 * Immersive (system bars hidden, transient by swipe); chrome sits flush at the top edge — the top
 * guard is 0 on Ratta hardware (`core/TopGuard.kt` holds that decision).
 */
class NotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: NotebookToolbar
    private lateinit var selectionToolbar: SelectionToolbar
    private lateinit var session: NotebookSession
    private lateinit var pageGestures: PageGestures
    private val repo by lazy { IndexRepository() }

    private var notebookId: String = ""
    private var opened = false
    private var closing = false

    /** True while a lasso selection is up — the gesture detector stands down on it. */
    private var selectionActive = false

    /**
     * The live selection, kept because a delete needs its stroke ids after the fact. Updated in
     * place on a move (the engine keeps the selection alive at its new position) and dropped the
     * moment the engine says it is gone. Never read as "is there a selection" — [selectionActive]
     * is that flag.
     */
    private var currentSelection: Selection? = null

    /** In-memory, notebook-level history: it survives page turns and dies with the screen. */
    private val undo = UndoRedoStack()

    /** Serialises page/undo operations so two overlapping gestures can't tangle the page list. */
    private val pageOps = Mutex()

    /** The strokes on the visible page — the "you still have them" mirror an erase undo needs. */
    private var liveStrokes: MutableMap<String, Stroke> = mutableMapOf()

    /**
     * The page whose strokes are on the paper — written on Main only, at the two places
     * `loadStrokes` runs. The g-paper callbacks stamp their rows with THIS, never with
     * `session.currentPage`: the session's `pages`/`currentIndex` mutate on IO mid-flip (`goTo`
     * advances the index before the swap reaches the paper), so a pen-up racing a flip would
     * otherwise persist ink to the destination page — and a torn read of the pair can crash.
     * What the user inked is the page they were looking at.
     */
    private var displayedPageId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""

        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        // Both pen-gesture recognisers are simply on (P1) — armed before the listener is attached,
        // because the engine reads them as it wires itself up. Order is load-bearing.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.setPaperListener(listener)

        // The toolbar owns all pen/eraser configuration — fixed values, no panels, no prefs.
        toolbar = NotebookToolbar(binding, paper) { close() }
        selectionToolbar = SelectionToolbar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            onDelete = { currentSelection?.let { deleteSelection(it) } },
        )
        binding.notebookName.text = name
        binding.pageIndicator.text = ""

        // Debug builds only (the release twin installs nothing): the ⋯ at the end of the button row.
        // The provider hands it the visible page and nothing else — and the strokes go over in
        // WRITING ORDER, which is what `liveStrokes` (a LinkedHashMap filled by load then by commit)
        // preserves. A recognizer reads ink as a sequence; a hashed order is nonsense to it.
        NotebookDebugMenu.install(this, binding.topBarRow, provider = { recognizeContext() })

        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { overChrome(it) },
            listener = gestureListener,
        )

        // Chrome moved/appeared/disappeared: re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { close() }
        })

        BrowseState(this).lastOpenNotebookId = notebookId
        RecentsPrefs(this).record(notebookId)

        session = NotebookSession(this, notebookId, repo)
        // The surface accepts no ink until the page is truly loaded (pushExclusions blocks it all
        // while !opened) — set that up before the first layout pass can even happen.
        pushExclusions()
        lifecycleScope.launch { openSession() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openSession() {
        try {
            val alive = withContext(Dispatchers.IO) { repo.alive(notebookId) }
            if (alive == null) { failOpen("not in the library"); return }
            when (val r = session.open()) {
                is NotebookSession.OpenResult.Failed -> { failOpen(r.reason); return }
                NotebookSession.OpenResult.Ok -> Unit
            }
            if (isFinishing || closing) { sealAbandonedOpen(); return }
            val page = session.currentPage
            val strokes = session.store.loadPage(page.id)
            paper.setPageSize(page.width, page.height)
            paper.setTemplate(session.template)
            paper.loadStrokes(strokes)
            liveStrokes = strokes.associateBy { it.id }.toMutableMap()
            displayedPageId = page.id
            opened = true
            pushExclusions()   // swap the block-all rect for the real chrome rects
            // The page is on the paper — take the "Opening…" box down. Deliberately **not**
            // pen-idle-gated: `isPenActive` counts hover, and the user's pen is already over the
            // glass on the way to writing, which would hold the box up over the page they asked for.
            // This is a boundary frame, not a frame during writing (nothing has been drawn yet).
            binding.openingOverlay.root.visibility = View.GONE
            setPageIndicator(session.currentIndex + 1, session.pages.size)
            Slog.d(TAG) { "page ${page.id} loaded: ${strokes.size} strokes, ${page.width}x${page.height}" }
            warmUpRecognizer()
        } catch (t: Throwable) {
            // Back during the open window cancels this scope; the session may have opened its
            // handle between our suspensions. Nothing else will ever seal it (close() early-exited
            // on session.isOpen==false, and the onDestroy fallback is disabled by `closing`).
            if (::session.isInitialized && session.isOpen && !opened) sealAbandonedOpen()
            if (t is kotlinx.coroutines.CancellationException) throw t
            // Anything else mid-open is a failed open, not a crash — explain and leave.
            Log.e(TAG, "open crashed", t)
            failOpen(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Warm the recognizer extension once the page has landed — fire-and-forget, never in the open's
     * critical path (this is launched after the overlay is down, and nothing waits on it).
     *
     * One `status()` bind is the whole job: it starts the extension's process, whose `onCreate`
     * builds the client from an **already-present** model and primes the engine off the Binder
     * thread, so the session's first real recognition doesn't pay ML Kit's lazy model load. It can
     * never trigger a download — only `prepare()` may, and that lives behind the consent dialog —
     * so opening a notebook never asks the user for anything. No recognizer installed, or an
     * extension that doesn't answer, is a non-event: nothing is shown, ever.
     */
    private fun warmUpRecognizer() {
        lifecycleScope.launch {
            try {
                val ref = ExtensionRegistry.handwritingRecognizer(this@NotebookActivity) ?: return@launch
                val status = RecognizerClient(this@NotebookActivity, ref).status()
                Slog.d(TAG) { "recognizer warm-up: ${ref.packageName} status=$status" }
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "recognizer warm-up skipped: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }
    }

    /**
     * What is on the paper right now, for a recognize call: the visible page's size and its strokes.
     * The size comes from the page matching [displayedPageId], not from `session.currentPage` — the
     * session's index advances on IO before a flip reaches the paper, so `currentPage` can already
     * be the destination (or, mid-mutation, out of range of `pages`).
     *
     * The strokes are `liveStrokes.values` — a LinkedHashMap filled by the page load and then by
     * each commit, so its order **is** writing order. A recognizer reads ink as a sequence, and a
     * hashed order would be nonsense to it; never source this from a set.
     */
    private fun recognizeContext(): RecognizeContext? {
        if (!opened || !::session.isInitialized) return null
        val page = session.pages.firstOrNull { it.id == displayedPageId } ?: return null
        return RecognizeContext(liveStrokes.values.toList(), page.width.toFloat(), page.height.toFloat())
    }

    /** Seal a session the screen will never use — on [appScope], because our own scope is dying. */
    private fun sealAbandonedOpen() {
        val s = session
        appScope.launch { withContext(NonCancellable) { runCatching { s.seal() } } }
    }

    /** A tap that opened nothing must be explained, not toasted (e-ink rule) — dialog, then leave. */
    private fun failOpen(reason: String) {
        Log.w(TAG, "open failed: $reason")
        // The box must come down before the dialog goes up — it shields every touch under it, and
        // an OK button that cannot be tapped is a dead screen.
        binding.openingOverlay.root.visibility = View.GONE
        BrowseState(this).lastOpenNotebookId = null
        if (isFinishing || isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.notebook_open_failed_title)
                .setMessage(reason)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
        ).show()
    }

    // ── g-paper → store ──────────────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!opened) return
            val pageId = displayedPageId
            session.store.commit(pageId, stroke)
            liveStrokes[stroke.id] = stroke
            undo.record(Action.Drew(pageId, stroke))
        }
        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened) return
            val pageId = displayedPageId
            // The mirror is the only place the geometry still exists once the engine drops it.
            val captured = strokeIds.mapNotNull { liveStrokes.remove(it) }
            session.store.erase(strokeIds)
            if (captured.isNotEmpty()) undo.record(Action.Erased(pageId, captured))
        }
        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened) return
            val pageId = displayedPageId
            val ids = move.strokeIds.toList()
            session.store.move(ids, move.dx, move.dy)
            for (id in ids) liveStrokes[id]?.let { liveStrokes[id] = it.translated(move.dx, move.dy) }
            undo.record(Action.Moved(pageId, ids, move.dx, move.dy))
            // The selection survives a move, at its new position — keep our copy honest.
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            // The drag is over (this fires at lift), so bring the bar back where the box now is.
            currentSelection?.let { selectionToolbar.show(it.bounds) }
        }
        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown immediately, **not** through the pen-idle gate: a lasso ends with the pen still
            // hovering over the glass (`isPenActive` counts proximity + a 350 ms tail), so an
            // idle-gated bar would arrive long after the selection it belongs to — the R3 panel
            // lesson. The engine has already presented the selection box, so this frame is part of
            // that same presentation, not a repaint during writing.
            selectionToolbar.show(selection.bounds)
        }
        /** The pen is dragging the box — the bar would be dragged over, and it never follows live. */
        override fun onSelectionDragStarted() { selectionToolbar.hide() }
        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
        }
        override fun onToolChanged(tool: Tool) { toolbar.sync(tool) }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the notebook grows where you write.
            if (session.currentIndex < session.pages.lastIndex) navigateTo(session.currentIndex + 1)
            else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp {
            if (session.currentIndex > 0) navigateTo(session.currentIndex - 1)
        }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onDeleteRequested() { showDeleteSheet() }
    }

    /** Serialise every page/undo mutation; ignore anything while not open or once closing. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (opened && !closing) runCatching { block() }.onFailure { Log.w(TAG, "page op failed", it) }
            }
        }
    }

    /**
     * Swap the visible page. The order is the host-responsibilities page-swap law:
     * `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize`/`setTemplate` →
     * `loadStrokes`, which is a single EPD refresh. Any selection goes first, because a data-in
     * call would dismiss it anyway and it belongs to the page we are leaving.
     */
    private suspend fun navigateTo(index: Int) {
        val page = session.goTo(index)
        val strokes = session.store.loadPage(page.id)
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()   // idempotent — clearSelection fires onSelectionDismissed too
        paper.clearForContentSwap()
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        paper.loadStrokes(strokes)
        liveStrokes = strokes.associateBy { it.id }.toMutableMap()
        displayedPageId = page.id
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        session.saveLastOpened()
    }

    /** Show whatever the rows now say about [pageId] — the replay path's only way back to the paper. */
    private suspend fun refreshToPage(pageId: String) {
        val idx = session.pages.indexOfFirst { it.id == pageId }
        if (idx >= 0) navigateTo(idx)
    }

    private suspend fun doInsert(after: Boolean) {
        val snap = session.insertBlank(after)
        undo.record(Action.Page(snap))
        navigateTo(session.currentIndex)   // put the freshly-inserted blank page on the paper
    }

    private suspend fun doDelete() {
        val snap = session.deleteCurrent()
        undo.record(Action.Page(snap))
        navigateTo(session.currentIndex)
    }

    private suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        val g = undo.generation
        try {
            session.store.drain()   // the queued writes are part of the state being reversed
            revert(a)
        } catch (t: Throwable) {
            // Failed (or cancelled) mid-replay: put the entry back so the history never silently
            // loses a step. The store ops are per-row and reconcile is idempotent, so retrying
            // converges; the .soil is never left changed with no entry able to reverse it.
            undo.pushUndo(a)
            throw t
        }
        // A pen-up landing mid-replay recorded a fresh edit, which cleared redo — honour the
        // record-clears-redo invariant rather than re-populating redo with the undone entry.
        if (undo.generation == g) undo.pushRedo(a)
    }

    private suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        try {
            session.store.drain()
            reapply(a)
        } catch (t: Throwable) {
            undo.pushRedo(a)
            throw t
        }
        undo.pushUndo(a)
    }

    /**
     * Every replay mutates the store first and *then* reloads the affected page: the `.soil` is the
     * source of truth, so what the paper shows after an undo is what a reopen would show.
     */
    private suspend fun revert(a: Action) {
        when (a) {
            is Action.Drew -> { session.store.remove(listOf(a.stroke.id)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Erased -> { session.store.restore(a.pageId, a.strokes); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Deleted -> { session.store.restore(a.pageId, a.strokes); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Moved -> { session.store.move(a.ids, -a.dx, -a.dy); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Page -> {
                session.reconcile(a.snapshot.before, a.snapshot.strokeIds, emptyList(), a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
            }
        }
    }

    private suspend fun reapply(a: Action) {
        when (a) {
            is Action.Drew -> { session.store.restore(a.pageId, listOf(a.stroke)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Erased -> { session.store.remove(a.strokes.map { it.id }); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Deleted -> { session.store.remove(a.strokes.map { it.id }); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Moved -> { session.store.move(a.ids, a.dx, a.dy); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Page -> {
                session.reconcile(a.snapshot.after, emptyList(), a.snapshot.strokeIds, a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    /**
     * Delete the selected strokes. Order matters: capture the geometry from [liveStrokes] *first*
     * (it is the only place it still exists once the engine drops them), then tell the engine, then
     * the rows. `removeStrokes` dismisses the selection itself — every data-in call does — so there
     * is no [PaperView.clearSelection] here; `onSelectionDismissed` clears our copy.
     */
    private fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toList()
        if (ids.isEmpty()) return
        val pageId = displayedPageId
        val strokes = ids.mapNotNull { liveStrokes[it] }
        paper.removeStrokes(ids)
        session.store.erase(ids)
        ids.forEach { liveStrokes.remove(it) }
        // Nothing captured means nothing to put back — record no history rather than a lying entry.
        if (strokes.isNotEmpty()) undo.record(Action.Deleted(pageId, strokes))
        else Log.w(TAG, "selection delete: no geometry for ${ids.size} ids — not undoable")
        Slog.d(TAG) { "selection delete: ${ids.size} strokes" }
    }

    /** Long-press asks; it never deletes. Sheet → confirm dialog → the actual op. */
    private fun showDeleteSheet() {
        if (!opened) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire, so
        // we are outside the pen-active window the R3 rule protects (a release inside it can cost
        // a live stroke).
        paper.releaseRender()
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_trash, getString(R.string.delete_page_action)) { confirmDeletePage() }
            .show()
    }

    private fun confirmDeletePage() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Both bars plus the selection toolbar while it is up, translated into the paper view's
     *  coordinates, so the stylus can never ink under chrome. */
    private fun pushExclusions() {
        if (!::paper.isInitialized) return
        if (!opened) {
            // The toolbar arms the pen from the first frame, but the page isn't on the paper yet —
            // a stroke committed now would hit the listener's `opened` guard, never reach the
            // store, and be silently wiped by loadStrokes. Block the whole surface until then.
            paper.setExclusionRects(listOf(BLOCK_ALL))
            return
        }
        val paperLoc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
        val rects = listOfNotNull(rectOf(binding.topBar), rectOf(binding.bottomStrip), selectionToolbar.rect())
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    /**
     * The free band between the two chrome bars, in the root's coordinates — where a floating bar
     * may be placed. Null until both have been laid out.
     */
    private fun chromeBand(): IntRange? {
        val top = binding.topBar
        val bottom = binding.bottomStrip
        if (top.height == 0 || bottom.height == 0) return null
        return top.bottom..bottom.top
    }

    /**
     * Frame-silence rule: never present an app frame while the pen is active (Ratta overlay ink
     * lags for every frame it must mask). Chrome text changes wait for the gate to open.
     */
    private fun setPageIndicator(n: Int, total: Int) {
        val text = getString(R.string.page_indicator, n, total)
        whenPenIdle { binding.pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        binding.root.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observer only — consumes nothing.
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        if (::paper.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Both bars and the selection toolbar — the floating bar is chrome like any other. */
    private fun overChrome(ev: MotionEvent): Boolean {
        val top = rectOf(binding.topBar)
        val bottom = rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) ||
            (bottom?.contains(x, y) == true) ||
            (::selectionToolbar.isInitialized && selectionToolbar.contains(x, y))
    }

    private fun rectOf(v: View): Rect? {
        if (v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
    }

    override fun onStop() {
        super.onStop()
        if (!opened || closing || !session.isOpen) return
        // Cheap durability point while backgrounded: cover + last-open page. Ink is already in rows.
        val p = paper; val s = session; val id = notebookId
        appScope.launch {
            try {
                // Under the page-op mutex: saveLastOpened reads currentPage, which a still-running
                // insert/delete mutates on IO.
                pageOps.withLock {
                    if (!closing) CoverSnapshot.capture(p, id, repo)
                    if (!closing && s.isOpen) s.saveLastOpened()
                }
            } catch (e: Exception) { Log.w(TAG, "onStop persist failed", e) }
        }
    }

    /**
     * Normal close: cover → last-open page → meta → drain writes + seal, on an application-scoped
     * NonCancellable coroutine (each step guarded), then finish. Idempotent.
     */
    private fun close() {
        if (closing) return
        closing = true
        undo.clear()   // in-memory history dies with the screen
        BrowseState(this).lastOpenNotebookId = null
        if (!::session.isInitialized || !session.isOpen) { finish(); return }
        val p = paper; val s = session; val id = notebookId
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        appScope.launch {
            withContext(NonCancellable) {
                // The page-op mutex first: an insert/delete that passed the `closing` check before
                // it flipped may still be inside its transaction — sealing under it would fail the
                // transaction silently (runPageOp swallows) or split the .soil from its index
                // mirror. New ops can't start (`closing` is set), so this only waits, never races.
                pageOps.withLock {
                    if (opened) try { CoverSnapshot.capture(p, id, repo) } catch (e: Exception) { Log.w(TAG, "cover failed", e) }
                    try { s.saveLastOpened() } catch (e: Exception) { Log.w(TAG, "saveLastOpened failed", e) }
                    try { s.refreshMeta(versionCode) } catch (e: Exception) { Log.w(TAG, "refreshMeta failed", e) }
                    try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) }
                }
            }
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        if (::paper.isInitialized) paper.release()
        // A destroy that isn't a normal close (e.g. finish() out of failOpen) still seals.
        if (::session.isInitialized && session.isOpen && !closing) {
            closing = true
            undo.clear()
            val s = session
            appScope.launch {
                withContext(NonCancellable) {
                    pageOps.withLock { try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) } }
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotebookActivity"
        /** Covers any screen; deliberately not MAX_VALUE (engine-side rect math must not overflow). */
        private val BLOCK_ALL = Rect(0, 0, 100_000, 100_000)
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(context: Context, notebookId: String, notebookName: String): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
    }
}
