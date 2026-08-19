package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
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
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.InkColorCodec
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.ext.scratchpad.ScratchUndo.Action
import com.symmetricalpalmtree.notesprout.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.notebook.PageGestures
import com.symmetricalpalmtree.notesprout.notebook.PaperChrome
import com.symmetricalpalmtree.notesprout.notebook.UndoRedoStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The extension-owned scratch pad screen (arc 6 / S1; UI-rule tier 2). The host launches it with an
 * `ActivityResultLauncher` after `begin(store)` on the held bind and `releaseForHandoff()` on its own
 * paper; it verifies its caller **first thing** ([HostCallerCheck.enforceActivity] — a plain
 * `am start` is refused), reads only the recorded `EXTRA_*` and returns only the recorded `RESULT_*`;
 * ink never rides the Intent. Everything the screen holds is what the host lent for this showing
 * ([ScratchSession.store]) — the pages live in the host's extension store through [ScratchDocument].
 *
 * The notebook screen's shape, shared through `:paper-screen`: full-bleed g-paper, the overlay chrome
 * (exclusion rects via [PaperChrome]; the whole paper excluded under the "Opening…" popup), the finger
 * gestures ([PageGestures] — flip / insert / undo / redo / long-press delete), fixed pen / eraser /
 * lasso, pad-level [UndoRedoStack] cleared on close, page ops serialised under [pageOps]. Saves: a
 * debounced [SAVE_DEBOUNCE_MS] after every edit + on page leave / `onPause` / close (Back awaits the
 * flush before finishing — the host's `end()` revokes the store right after the result). A stroke that
 * would push the page past the store's value cap is refused and removed from the paper, said once per
 * page visit ([pageFullWarned]). Any store failure → `scratch_store_unavailable` → finish.
 */
class ScratchPadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScratchPadBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: ScratchToolbar
    private lateinit var selectionToolbar: ScratchSelectionToolbar
    private lateinit var chrome: PaperChrome
    private lateinit var pageGestures: PageGestures
    private var document: ScratchDocument? = null

    private var sendEnabled = false
    private var opened = false
    private var closing = false
    private var selectionActive = false
    private var currentSelection: Selection? = null
    /** The page id the "page full" dialog was last shown for (once per page visit). */
    private var pageFullWarned: String? = null
    /** The page last put on the paper — a change of page resets [pageFullWarned]. */
    private var shownPageId: String? = null

    private val undo = UndoRedoStack<Action>()
    private val pageOps = Mutex()
    private val saveRunnable = Runnable { runPageOp { document?.flush() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        sendEnabled = intent.getBooleanExtra(ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED, false)
        binding = ActivityScratchPadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()
        TopGuard.applyRootPadding(binding.topBar)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId} send=$sendEnabled" }
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(listener)

        toolbar = ScratchToolbar(binding, paper, sendEnabled, onBack = { close() }, onSend = { sendPage() })
        selectionToolbar = ScratchSelectionToolbar(
            binding, paper.asView(), sendEnabled,
            releaseRender = { paper.releaseRender() }, onDelete = { deleteSelection() }, onSend = { sendSelection() },
        )
        listOf(binding.btnPrev, binding.btnNext).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
        binding.btnPrev.setOnClickListener { paper.releaseRender(); runPageOp { flip(-1) } }   // inside bottomStrip: exclusion covers it
        binding.btnNext.setOnClickListener { paper.releaseRender(); runPageOp { flip(+1) } }
        binding.pageIndicator.text = ""
        ScratchDebugMenu.install(this, binding.topBarRow) { document }

        chrome = PaperChrome(paper, binding.topBar, binding.bottomStrip, { selectionToolbar.rects() }, { x, y -> selectionToolbar.contains(x, y) }) { !opened }
        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            selectionActive = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!opened) pushExclusions()
            binding.root.post { pushExclusions() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { close() }
        })

        val store = ScratchSession.store
        if (store == null) { storeUnavailable("no store held — the host did not begin()"); return }
        val doc = ScratchDocument(ScratchStore(store))
        document = doc
        lifecycleScope.launch { open(doc) }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun open(doc: ScratchDocument) {
        val t0 = System.currentTimeMillis()
        try {
            doc.load()
        } catch (e: StoreUnavailable) {
            storeUnavailable("load: ${e.message}"); return
        }
        if (isFinishing) return
        awaitLayout(paper.asView())
        if (isFinishing) return
        showPage(doc)
        opened = true
        // The "Opening…" popup comes down only now — the page is on the paper — and the pen is let in
        // at the same moment (pushExclusions swaps the whole-paper block for the chrome rects).
        binding.openingOverlay.visibility = View.GONE
        pushExclusions()
        Slog.d(TAG) { "opened: page ${doc.currentIndex + 1}/${doc.ids.size}, ${doc.strokes.size} strokes, ${doc.pageBytes} B in ${System.currentTimeMillis() - t0} ms" }
    }

    /** Wait until the paper has a size (the page size of a new page is the surface size). */
    private suspend fun awaitLayout(view: View) {
        if (view.width > 0 && view.height > 0) return
        suspendCancellableCoroutine { cont ->
            val l = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (v.width > 0 && v.height > 0) { v.removeOnLayoutChangeListener(this); if (cont.isActive) cont.resume(Unit) }
                }
            }
            view.addOnLayoutChangeListener(l)
            cont.invokeOnCancellation { view.removeOnLayoutChangeListener(l) }
        }
    }

    /** Put the document's current page on the paper (page size, strokes, indicator). */
    private fun showPage(doc: ScratchDocument) {
        val v = paper.asView()
        doc.ensurePageSize(v.width, v.height)
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()
        paper.setPageSize(doc.pageWidth.toInt(), doc.pageHeight.toInt())
        paper.loadStrokes(doc.strokes.values.toList())
        setPageIndicator(doc.currentIndex + 1, doc.ids.size)
        if (shownPageId != doc.currentId) { shownPageId = doc.currentId; pageFullWarned = null }
    }

    // ── g-paper → document ───────────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            val doc = document ?: return
            if (!opened) return
            if (!doc.add(stroke)) {
                paper.removeStrokes(listOf(stroke.id))   // refused: the page is full — nothing written
                pageFull(doc.currentId)
                return
            }
            undo.record(Action.Drew(doc.currentId, stroke))
            scheduleSave()
        }
        override fun onStrokesErased(strokeIds: List<String>) {
            val doc = document ?: return
            if (!opened) return
            val taken = doc.remove(strokeIds)
            if (taken.isNotEmpty()) { undo.record(Action.Erased(doc.currentId, taken)); scheduleSave() }
        }
        override fun onSelectionMoved(move: SelectionMove) {
            val doc = document ?: return
            if (!opened) return
            val ids = move.strokeIds.toList()
            doc.translate(ids, move.dx, move.dy)
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            undo.record(Action.Moved(doc.currentId, ids, move.dx, move.dy))
            currentSelection?.let { showSelectionToolbar(it) }   // re-anchor at the new place
            scheduleSave()
        }
        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            showSelectionToolbar(selection)
        }
        override fun onSelectionDragStarted() { selectionToolbar.hide() }
        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
            binding.root.post { pushExclusions() }
        }
        override fun onToolChanged(tool: Tool) { toolbar.sync(tool) }
    }

    /** At once — not pen-idle: a lasso ends with the pen hovering (the notebook's H5 rule). */
    private fun showSelectionToolbar(sel: Selection) {
        if (!opened || closing) return
        selectionToolbar.show(sel.bounds)
        binding.root.post { pushExclusions() }
    }

    /** The selection toolbar's Delete: the selected strokes as one undoable step. */
    private fun deleteSelection() = runPageOp {
        val doc = document ?: return@runPageOp
        val sel = currentSelection ?: return@runPageOp
        val taken = doc.remove(sel.strokeIds)
        if (taken.isEmpty()) return@runPageOp
        undo.record(Action.Erased(doc.currentId, taken))
        paper.clearSelection()
        paper.removeStrokes(taken.map { it.id })   // a data-in call: no erase callback comes back
        scheduleSave()
        Slog.d(TAG) { "deleted selection: ${taken.size} strokes" }
    }

    // S1 (user decision Q4): the Send buttons show when opened from a notebook but do nothing yet.
    private fun sendPage() { Slog.d(TAG) { "send page — S2" } }
    private fun sendSelection() { Slog.d(TAG) { "send selection — S2" } }

    // ── Page gestures → operations ─────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            val doc = document ?: return@runPageOp
            if (doc.currentIndex < doc.ids.lastIndex) flip(+1) else doInsert(after = true)   // past the last page inserts
        }
        override fun onFlipPrevious() = runPageOp { flip(-1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onDeleteRequested() { showDeleteSheet() }
    }

    /** Serialise every page / undo / save mutation; ignore while not open or once a close is under way. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: StoreUnavailable) {
                    storeUnavailable("page op: ${e.message}")
                } catch (e: PageFullException) {
                    document?.let { pageFull(it.currentId) }   // a replay pushed the page over the cap — kept in memory
                } catch (e: Exception) {
                    Log.w(TAG, "page op failed", e)
                }
            }
        }
    }

    /** The strip's arrows / a swipe: a no-op at a bound (never disabled). */
    private suspend fun flip(delta: Int) {
        val doc = document ?: return
        val target = doc.currentIndex + delta
        if (target !in doc.ids.indices) return
        doc.goTo(doc.ids[target])
        showPage(doc)
    }

    private suspend fun doInsert(after: Boolean) {
        val doc = document ?: return
        undo.record(doc.insert(after))
        showPage(doc)
    }

    private suspend fun doDelete() {
        val doc = document ?: return
        undo.record(doc.deleteCurrent())
        showPage(doc)
    }

    private suspend fun doUndo() {
        val doc = document ?: return
        val a = undo.popUndo() ?: return
        if (doc.revert(a)) undo.pushRedo(a)
        showPage(doc)
        scheduleSave()
    }

    private suspend fun doRedo() {
        val doc = document ?: return
        val a = undo.popRedo() ?: return
        if (doc.reapply(a)) undo.pushUndo(a)
        showPage(doc)
        scheduleSave()
    }

    private fun showDeleteSheet() {
        if (!opened || closing) return
        paper.releaseRender()
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_trash, getString(R.string.scratch_delete_page)) { confirmDeletePage() }
            .show()
    }

    private fun confirmDeletePage() {
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.scratch_delete_confirm)
            .setPositiveButton(R.string.scratch_delete_page) { _, _ -> runPageOp { doDelete() } }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
    }

    // ── Saves + failures ─────────────────────────────────────────────────────

    private fun scheduleSave() {
        binding.root.removeCallbacks(saveRunnable)
        binding.root.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    private fun pageFull(pageId: String) {
        if (pageFullWarned == pageId) return
        pageFullWarned = pageId
        paper.releaseRender()
        Dialogs.problem(this, R.string.scratch_title, getString(R.string.scratch_page_full))
    }

    /** The host's store is gone (revoked, dead binder, a failed write): say so once and finish. */
    private fun storeUnavailable(reason: String) {
        if (closing) return
        closing = true
        Log.w(TAG, "store unavailable: $reason")
        if (::paper.isInitialized) paper.releaseRender()
        setResult(Activity.RESULT_CANCELED)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.scratch_title)
            .setMessage(R.string.scratch_store_unavailable)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .create()
        Dialogs.style(dialog)
        if (!isFinishing && !isDestroyed) dialog.show() else finish()
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun pushExclusions() {
        if (::chrome.isInitialized) chrome.pushExclusions()
    }

    /** Frame-silence rule: chrome text changes wait for the pen to go idle. */
    private fun setPageIndicator(n: Int, total: Int) {
        val text = getString(R.string.scratch_page_indicator, n, total)
        whenPenIdle { binding.pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        binding.root.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** EPD chrome-release on a finger landing on chrome; the gesture detector observes every event. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::chrome.isInitialized) {
            val tool = ev.getToolType(0)
            val finger = tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER
            if (finger && !paper.isPenActive && chrome.overChrome(ev)) paper.releaseRender()
        }
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
    }

    override fun onPause() {
        super.onPause()
        // Durability point while away (home, a process kill next): the pending debounce fires now.
        if (opened && !closing && document?.dirty == true) {
            binding.root.removeCallbacks(saveRunnable)
            runPageOp { document?.flush() }
        }
    }

    /** Back: flush the current page **before** finishing — the host's `end()` revokes the store right
     *  after the result arrives. Idempotent. */
    private fun close() {
        if (closing) return
        closing = true
        binding.root.removeCallbacks(saveRunnable)
        val doc = document
        if (!opened || doc == null) { setResult(Activity.RESULT_CANCELED); finish(); return }
        lifecycleScope.launch {
            pageOps.withLock {
                try { doc.flush() } catch (e: Exception) { Log.w(TAG, "flush on close failed", e) }
            }
            setResult(Activity.RESULT_CANCELED)
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(saveRunnable)
        if (::paper.isInitialized) paper.release()
        undo.clear()
        // A destroy that isn't a normal close (the system finished us): last-chance flush on a scope
        // that outlives the screen — the host still holds the store until its result callback runs.
        val doc = document
        if (opened && !closing && doc != null && doc.dirty) {
            closing = true
            appScope.launch { withContext(NonCancellable) { try { doc.flush() } catch (e: Exception) { Log.w(TAG, "flush on destroy failed", e) } } }
        }
        document = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScratchPadActivity"
        /** The notebook's fixed tools (raw px, not dp). */
        const val PEN_WIDTH_PX = 3f
        const val ERASER_RADIUS_PX = 15f
        const val SAVE_DEBOUNCE_MS = 800L

        /** Outlives the Activity so a flush in flight always completes. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
