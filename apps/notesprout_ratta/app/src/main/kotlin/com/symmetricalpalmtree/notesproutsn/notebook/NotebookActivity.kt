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
 * / [StrokeStore]; the cover in [CoverSnapshot]; the buttons and panels in [NotebookToolbar]; the
 * finger vocabulary in [PageGestures] and the history in [UndoRedoStack].
 *
 * Immersive (system bars hidden, transient by swipe); chrome sits flush at the top edge — the top
 * guard is 0 on Ratta hardware (`core/TopGuard.kt` holds that decision).
 */
class NotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: NotebookToolbar
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
        // The two pen-gesture recognisers, armed from their remembered state before the listener is
        // attached (both default on — R5). The lasso panel is what writes them afterwards.
        val toolPrefs = ToolPrefs(this)
        paper.smartLassoEnabled = toolPrefs.smartLasso
        paper.scribbleEraseEnabled = toolPrefs.scribbleErase
        paper.setPaperListener(listener)

        // The toolbar owns all pen/eraser/recogniser configuration (defaults + ToolPrefs).
        toolbar = NotebookToolbar(binding, paper, toolPrefs) { close() }
        binding.notebookName.text = name
        binding.pageIndicator.text = ""

        // Stand-down is wider than Paper's: a lasso selection *or* an open tool panel takes the
        // contact away from the page gestures (the panel's own dismiss owns that touch).
        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive || toolbar.panelOpen },
            overChrome = { overChrome(it) },
            listener = gestureListener,
        )

        // Chrome moved/appeared/disappeared (incl. panel toggles — they change topBar's height):
        // re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { close() }
        })

        BrowseState(this).lastOpenNotebookId = notebookId
        RecentsPrefs(this).record(notebookId)

        session = NotebookSession(this, notebookId, repo)
        lifecycleScope.launch { openSession() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openSession() {
        val alive = withContext(Dispatchers.IO) { repo.alive(notebookId) }
        if (alive == null) { failOpen("not in the library"); return }
        when (val r = session.open()) {
            is NotebookSession.OpenResult.Failed -> { failOpen(r.reason); return }
            NotebookSession.OpenResult.Ok -> Unit
        }
        if (isFinishing) { session.seal(); return }
        val page = session.currentPage
        val strokes = session.store.loadPage(page.id)
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        paper.loadStrokes(strokes)
        liveStrokes = strokes.associateBy { it.id }.toMutableMap()
        opened = true
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        Slog.d(TAG) { "page ${page.id} loaded: ${strokes.size} strokes, ${page.width}x${page.height}" }
    }

    /** A tap that opened nothing must be explained, not toasted (e-ink rule) — dialog, then leave. */
    private fun failOpen(reason: String) {
        Log.w(TAG, "open failed: $reason")
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
            val pageId = session.currentPage.id
            session.store.commit(pageId, stroke)
            liveStrokes[stroke.id] = stroke
            undo.record(Action.Drew(pageId, stroke))
        }
        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened) return
            val pageId = session.currentPage.id
            // The mirror is the only place the geometry still exists once the engine drops it.
            val captured = strokeIds.mapNotNull { liveStrokes.remove(it) }
            session.store.erase(strokeIds)
            if (captured.isNotEmpty()) undo.record(Action.Erased(pageId, captured))
        }
        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened) return
            val pageId = session.currentPage.id
            val ids = move.strokeIds.toList()
            session.store.move(ids, move.dx, move.dy)
            for (id in ids) liveStrokes[id]?.let { liveStrokes[id] = it.translated(move.dx, move.dy) }
            undo.record(Action.Moved(pageId, ids, move.dx, move.dy))
            // The selection survives a move, at its new position — keep our copy honest.
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
        }
        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
        }
        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
        }
        /** A sub-threshold tap inside the selection box — the one place the selection has a menu. */
        override fun onSelectionTapped(x: Float, y: Float) { showSelectionSheet() }
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
        paper.clearForContentSwap()
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        paper.loadStrokes(strokes)
        liveStrokes = strokes.associateBy { it.id }.toMutableMap()
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
        session.store.drain()   // the queued writes are part of the state being reversed
        revert(a)
        undo.pushRedo(a)
    }

    private suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        session.store.drain()
        reapply(a)
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
     * A tap inside the selection box opens the selection's menu. One row today: delete.
     *
     * **No confirm dialog.** The tap landed inside the box the user just drew, the row says exactly
     * what it does, and the delete comes straight back with undo — the same reasoning that stripped
     * the page-delete confirm's warning body in R4. A second dialog here would only be ceremony.
     *
     * **Frame silence.** [PaperView.releaseRender] is called ungated and a dialog is put on screen,
     * which is an app frame — the same recorded exception family as the panel close at stylus
     * pen-up: a single chrome frame at a *stroke boundary*, in direct response to a deliberate act.
     * g-paper escrows this callback to pen-up for a stylus and past `PEN_ACTIVE_TAIL_MS` for a
     * finger (palm-gated), so the contact that asked for the menu is already over when we paint.
     */
    private fun showSelectionSheet() {
        if (!opened || closing) return
        val sel = currentSelection ?: return
        paper.releaseRender()
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_trash, getString(R.string.delete_selection_action)) { deleteSelection(sel) }
            .show()
    }

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
        val pageId = session.currentPage.id
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

    /** Both bars (the top bar's rect grows over any open panel — panels are its children),
     *  translated into the paper view's coordinates, so the stylus can never ink under chrome. */
    private fun pushExclusions() {
        if (!::paper.isInitialized) return
        val paperLoc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
        val rects = listOfNotNull(rectOf(binding.topBar), rectOf(binding.bottomStrip))
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
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

    /** True while a stylus contact is on the glass (Ratta delivers stylus MotionEvents alongside
     *  the firmware ink). Not hover — contact. */
    private var stylusContactDown = false

    /** A stylus landed on the page with a panel open — close it at that contact's UP. */
    private var stylusDismissArmed = false

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. Anything landing
     *  on the *page* while a tool panel is open dismisses the panel — the paper is the "anywhere
     *  else" of that panel (a tap on the panel itself is over chrome: the panel is a child of the
     *  top bar, so [overChrome] already covers it). A finger dismisses immediately. A stylus
     *  dismisses at its **pen-up**: the stroke is committed synchronously by then, and waiting for
     *  full pen-idle instead would hold the panel for as long as the pen *hovers* (`isPenActive`
     *  counts proximity + a 350 ms tail — an eye-check finding: the panel felt stuck). The close
     *  is posted so the engine's commit for this event runs first; this is the one deliberate
     *  frame-silence exception — a single chrome frame at a stroke boundary, once per dismissal. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observer only — consumes nothing. Fed *before* the dismiss block below on purpose: a
        // finger DOWN that is about to close a panel is seen while `panelOpen` is still true, so
        // the detector stands down and the whole sequence is discarded rather than half-read.
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        if (::paper.isInitialized) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!stylus && !paper.isPenActive) {
                        if (overChrome(ev)) {
                            paper.releaseRender()
                        } else if (::toolbar.isInitialized && toolbar.panelOpen) {
                            paper.releaseRender()
                            toolbar.closePanels()
                        }
                    } else if (stylus) {
                        stylusContactDown = true
                        if (::toolbar.isInitialized && toolbar.panelOpen && !overChrome(ev)) {
                            stylusDismissArmed = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stylus) {
                    stylusContactDown = false
                    if (stylusDismissArmed) {
                        stylusDismissArmed = false
                        binding.root.post {
                            // A new contact may have landed before the post ran — never repaint
                            // chrome under a live stroke; that contact's own UP re-arms nothing,
                            // so fall back to the idle gate.
                            if (::toolbar.isInitialized && toolbar.panelOpen) {
                                if (stylusContactDown) {
                                    whenPenIdle {
                                        if (toolbar.panelOpen) { paper.releaseRender(); toolbar.closePanels() }
                                    }
                                } else {
                                    paper.releaseRender()
                                    toolbar.closePanels()
                                }
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun overChrome(ev: MotionEvent): Boolean {
        val top = rectOf(binding.topBar)
        val bottom = rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true)
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
                if (!closing) CoverSnapshot.capture(p, id, repo)
                if (!closing) s.saveLastOpened()
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
                if (opened) try { CoverSnapshot.capture(p, id, repo) } catch (e: Exception) { Log.w(TAG, "cover failed", e) }
                try { s.saveLastOpened() } catch (e: Exception) { Log.w(TAG, "saveLastOpened failed", e) }
                try { s.refreshMeta(versionCode) } catch (e: Exception) { Log.w(TAG, "refreshMeta failed", e) }
                try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) }
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
            appScope.launch { withContext(NonCancellable) { try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) } } }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotebookActivity"
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
