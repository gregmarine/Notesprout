package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.InkColorCodec
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.EditCaps
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesprout.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesprout.notebook.UndoRedoStack.Action
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The notebook screen: full-bleed g-paper surface with the toolbar and page strip overlaying it.
 * Lifecycle, wiring, chrome and exclusion rects live here; the data lives in [NotebookSession] /
 * [StrokeStore] / [ObjectStore]; the cover in [CoverSnapshot]; the buttons in [NotebookToolbar];
 * content objects reach the paper through [ObjectRenderer] (arc 4); the floating selection toolbar
 * is [SelectionToolbar] (arc 4 / H2 — its contents from [SelectionActions.merge]).
 *
 * Immersive (system bars hidden, transient by swipe). The toolbar is TopGuard-padded because on
 * BOOX the status bar still overlays the window top.
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
    private var selectionActive = false

    /** In-memory history (notebook-level, survives page turns; dies with the screen). */
    private val undo = UndoRedoStack()
    /** Serialises page/undo operations so overlapping gestures can't corrupt the page list. */
    private val pageOps = Mutex()
    /** Strokes currently on the visible page — the "you still have the strokes" mirror an erase needs. */
    private var liveStrokes: MutableMap<String, Stroke> = mutableMapOf()
    /** Content objects on the visible page (arc 4) — what [ObjectRenderer] draws and a delete captures.
     *  Insertion-ordered = z-order (loaded in `"order"`; a new object appends). */
    private var liveObjects: LinkedHashMap<String, PageObject> = LinkedHashMap()
    /** Rendered object bitmaps for this open notebook only (H1: nobody fills it yet — placeholders). */
    private val renderCache = ObjectRenderCache()
    /** The active lasso selection as last reported (bounds follow moves); null when none. */
    private var currentSelection: Selection? = null
    /** Selection-toolbar contributions, fetched once per notebook open (H2: the debug fake; H4: providers). */
    private var contributions: List<Contribution> = emptyList()
    /** The core's own toolbar actions: Delete only (locked decision Q5). */
    private val coreActions: List<ToolbarAction> by lazy {
        listOf(ToolbarAction(SelectionActions.CORE_DELETE_ID, "Del", R.drawable.ic_trash, getString(R.string.selection_delete_hint), ActionApplies.ALL, 0))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""

        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()
        TopGuard.applyRootPadding(binding.topBar)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(listener)
        paper.addContentRenderer(ObjectRenderer(
            objects = { liveObjects.values },
            cache = renderCache,
            renderWidthOf = { o -> if (opened) (session.currentPage.width - o.x).toInt().coerceAtLeast(1) else o.width.toInt() },
            dpi = { resources.displayMetrics.densityDpi.toFloat() },
        ))

        toolbar = NotebookToolbar(
            binding.topBar, binding.btnBack, binding.btnPen, binding.btnEraser, binding.btnLasso, paper,
        ) { close() }
        selectionToolbar = SelectionToolbar(
            root = binding.root, paperView = paper.asView(),
            bar = binding.selectionToolbar.root, subBar = binding.selectionSubToolbar.root,
            band = {
                val top = NotebookToolbar.rectOf(binding.topBar)?.bottom
                val bottom = NotebookToolbar.rectOf(binding.bottomStrip)?.top
                if (top != null && bottom != null && bottom > top) top..bottom else null
            },
            releaseRender = { paper.releaseRender() },
            listener = object : SelectionToolbar.Listener {
                override fun onDelete() { deleteSelection() }
                override fun onAction(providerKey: String?, action: ToolbarAction) {
                    // H2: the debug fake logs the leaf; H4 dispatches to the owning provider.
                    FakeContributions.onLeafTapped(providerKey, action)
                }
            },
        )
        binding.notebookName.text = name
        binding.pageIndicator.text = ""
        // Debug builds only (no-op in release): the ⋯ at the end of the top bar. Inside topBar, so the
        // existing exclusion rect covers it. It sees only the page's strokes + px size — never the session.
        NotebookDebugMenu.install(
            this, binding.topBarRow,
            provider = {
                if (!opened) null
                else RecognizeContext(paper.getStrokes(), session.currentPage.width.toFloat(), session.currentPage.height.toFloat())
            },
            hooks = DebugHooks(insertTestObject = { insertTestObject() }),
        )

        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            selectionActive = { selectionActive },
            overChrome = { overChrome(it) },
            listener = gestureListener,
        )

        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!opened) pushExclusions()   // whole-paper block needs no chrome geometry — apply now, not a frame later
            binding.root.post { pushExclusions() }
        }

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
        val objects = session.objectStore.loadPage(page.id)
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        liveObjects = objects.associateByTo(LinkedHashMap()) { it.id }
        paper.loadStrokes(strokes)
        paper.notifyContentChanged()
        liveStrokes = strokes.associateBy { it.id }.toMutableMap()
        contributions = FakeContributions.contributions()   // H2: local fake; H4: ExtensionRegistry.objectProviders
        opened = true
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        // The "Opening…" popup (visible from the first frame) comes down only now — the page is on
        // the paper and strokes are being persisted — and the pen is let in at the same moment
        // (pushExclusions swaps the whole-paper block for the chrome rects). Deliberately NOT
        // pen-idle gated: isPenActive is true while the pen merely hovers, and a readiness popup
        // that lingers over paper that is already keeping ink would say the opposite of the truth.
        binding.openingOverlay.visibility = View.GONE
        pushExclusions()
        Slog.d(TAG) { "page ${page.id} loaded: ${strokes.size} strokes, ${objects.size} objects, ${page.width}x${page.height}" }
    }

    private fun failOpen(reason: String) {
        Log.w(TAG, "open failed: $reason")
        Toast.makeText(this, getString(R.string.notebook_open_failed, reason), Toast.LENGTH_LONG).show()
        BrowseState(this).lastOpenNotebookId = null
        finish()
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
            val captured = strokeIds.mapNotNull { liveStrokes.remove(it) }
            session.store.erase(strokeIds)
            if (captured.isNotEmpty()) undo.record(Action.Erased(pageId, captured))
        }
        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened) return
            val pageId = session.currentPage.id
            val ids = move.strokeIds.toList()
            val objectIds = move.contentIds.filter { it in liveObjects }
            session.store.move(ids, move.dx, move.dy)
            session.objectStore.move(objectIds, move.dx, move.dy)
            for (id in ids) liveStrokes[id]?.let { liveStrokes[id] = it.translated(move.dx, move.dy) }
            for (id in objectIds) liveObjects[id]?.let { liveObjects[id] = it.translated(move.dx, move.dy) }
            if (objectIds.isNotEmpty()) paper.notifyContentChanged()
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            undo.record(Action.Moved(pageId, ids, move.dx, move.dy, objectIds))
            currentSelection?.let { showSelectionToolbar(it) }   // re-anchor at the new place
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
        }
        /** g-paper 0.1.1: a sub-threshold stylus / finger tap inside the selection box. With exactly one
         *  selected object under the tap → its edit dialog (H2: spec from the debug fake, result logged;
         *  H4: the provider's `describeEdit` / `applyEdit`). Strokes selected, or a tap outside the
         *  object → nothing. */
        override fun onSelectionTapped(x: Float, y: Float) {
            Slog.d(TAG) { "selection tapped ${x.toInt()},${y.toInt()} (objects=${currentSelection?.contentIds?.size ?: 0})" }
            val sel = currentSelection ?: return
            if (sel.strokeIds.isNotEmpty() || sel.contentIds.size != 1) return
            val obj = liveObjects[sel.contentIds.first()] ?: return
            if (!obj.bounds.contains(x, y)) return
            val spec = FakeContributions.editSpec(obj)?.let(EditCaps::sanitize) ?: return
            whenPenIdle {
                if (currentSelection != sel || closing) return@whenPenIdle
                paper.releaseRender()
                ObjectEditDialog.show(this@NotebookActivity, spec) { text -> FakeContributions.onEditSaved(obj, text) }
            }
        }
        override fun onToolChanged(tool: Tool) { toolbar.sync(tool) }
    }

    // ── Page gestures → operations ─────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            if (session.currentIndex < session.pages.lastIndex) navigateTo(session.currentIndex + 1)
            else doInsert(after = true)   // swipe past the last page inserts a new one (phase-4 decision)
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

    // ── Selection toolbar (arc 4 / H2) ───────────────────────────────────────

    /**
     * Show the toolbar for [sel] once the pen is idle (frame-silence rule; a lasso that is dragged at
     * once never flickers chrome). Contents = Delete + the contributions that apply to the selection's
     * shape (INK / one OBJECT of a provider's type / mixed → core only); active sub-action ids come
     * from the object's provider. Dropped if the selection changed while the gate was closed.
     */
    private fun showSelectionToolbar(sel: Selection) = whenPenIdle {
        if (!opened || closing || currentSelection != sel) return@whenPenIdle
        val objects = sel.contentIds.mapNotNull { liveObjects[it] }
        val shape = SelectionActions.shapeOf(sel.strokeIds.size, objects.map { it.providerIdentity })
        val items = SelectionActions.merge(coreActions, contributions, shape)
        val active = if (shape is SelectionActions.Shape.OneObject) FakeContributions.activeActionIds(objects[0]) else emptySet()
        selectionToolbar.show(items, active, sel.bounds)
        binding.root.post { pushExclusions() }
    }

    /** Serialise every page/undo mutation; ignore while not open or once a close is under way. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (opened && !closing) runCatching { block() }.onFailure { Log.w(TAG, "page op failed", it) }
            }
        }
    }

    /** Swap the visible page: single EPD refresh (hold pixels, then load). Strokes + objects together. */
    private suspend fun navigateTo(index: Int) {
        val page = session.goTo(index)
        val strokes = session.store.loadPage(page.id)
        val objects = session.objectStore.loadPage(page.id)
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()
        paper.clearForContentSwap()
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        liveObjects = objects.associateByTo(LinkedHashMap()) { it.id }
        paper.loadStrokes(strokes)
        paper.notifyContentChanged()
        liveStrokes = strokes.associateBy { it.id }.toMutableMap()
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        session.saveLastOpened()
    }

    private suspend fun refreshToPage(pageId: String) {
        val idx = session.pages.indexOfFirst { it.id == pageId }
        if (idx >= 0) navigateTo(idx)
    }

    private suspend fun doInsert(after: Boolean) {
        val snap = session.insertBlank(after)
        undo.record(Action.Page(snap))
        navigateTo(session.currentIndex)   // load the freshly-inserted (blank) page onto the paper
    }

    private suspend fun doDelete() {
        val snap = session.deleteCurrent()
        undo.record(Action.Page(snap))
        navigateTo(session.currentIndex)
    }

    private suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        session.store.drain()
        revert(a)
        undo.pushRedo(a)
    }

    private suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        session.store.drain()
        reapply(a)
        undo.pushUndo(a)
    }

    /** Every replay is store → drain → reload the affected page (strokes + objects), so the DB stays
     *  the source of truth and the paper never desyncs. */
    private suspend fun revert(a: Action) {
        val store = session.store; val objects = session.objectStore
        when (a) {
            is Action.Drew -> store.remove(listOf(a.stroke.id))
            is Action.Erased -> store.restore(a.pageId, a.strokes)
            is Action.Moved -> { store.move(a.ids, -a.dx, -a.dy); objects.move(a.objectIds, -a.dx, -a.dy) }
            is Action.ObjectCreated -> { objects.remove(listOf(a.obj.id)); store.restore(a.pageId, a.removedStrokes) }
            is Action.ObjectsDeleted -> { store.restore(a.pageId, a.strokes); objects.restore(a.pageId, a.objects) }
            is Action.ObjectEdited -> a.before.let { objects.updatePayloadAndBounds(it.id, it.payload, it.x, it.y, it.width, it.height) }
            is Action.Page -> {
                session.reconcile(a.snapshot.before, a.snapshot.childIds, emptyList(), a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
                return
            }
        }
        session.writer.drain()
        refreshToPage(a.pageId)
    }

    private suspend fun reapply(a: Action) {
        val store = session.store; val objects = session.objectStore
        when (a) {
            is Action.Drew -> store.restore(a.pageId, listOf(a.stroke))
            is Action.Erased -> store.remove(a.strokes.map { it.id })
            is Action.Moved -> { store.move(a.ids, a.dx, a.dy); objects.move(a.objectIds, a.dx, a.dy) }
            is Action.ObjectCreated -> { objects.restore(a.pageId, listOf(a.obj)); store.remove(a.removedStrokes.map { it.id }) }
            is Action.ObjectsDeleted -> { store.remove(a.strokes.map { it.id }); objects.remove(a.objects.map { it.id }) }
            is Action.ObjectEdited -> a.after.let { objects.updatePayloadAndBounds(it.id, it.payload, it.x, it.y, it.width, it.height) }
            is Action.Page -> {
                session.reconcile(a.snapshot.after, emptyList(), a.snapshot.childIds, a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
                return
            }
        }
        session.writer.drain()
        refreshToPage(a.pageId)
    }

    // ── Content objects (arc 4 / H1 debug test object; H2 toolbar Delete) ──────

    /** Debug ⋯ "Insert test object": a placeholder-drawn object (`debug:box`, payload `test`) at the
     *  page centre — no provider exists for it, so it exercises store / render / select / move /
     *  delete / undo without an extension. One undoable step ([Action.ObjectCreated], no ink consumed). */
    private fun insertTestObject() = runPageOp {
        val page = session.currentPage
        val obj = PageObject(
            id = UUID.randomUUID().toString(), providerIdentity = TEST_OBJECT_IDENTITY, payload = "test",
            x = page.width / 2f - TEST_OBJECT_W / 2f, y = page.height / 2f - TEST_OBJECT_H / 2f,
            width = TEST_OBJECT_W, height = TEST_OBJECT_H,
            order = (liveObjects.values.maxOfOrNull { it.order } ?: -1) + 1,
        )
        session.objectStore.create(page.id, obj)
        liveObjects[obj.id] = obj
        undo.record(Action.ObjectCreated(page.id, obj, emptyList()))
        whenPenIdle { paper.notifyContentChanged() }
    }

    /** The selection toolbar's Delete: the whole selection — strokes and/or objects — as one undoable
     *  step ([Action.ObjectsDeleted]). `clearSelection` dismisses → the toolbar hides. */
    private fun deleteSelection() = runPageOp {
        val sel = currentSelection ?: return@runPageOp
        val pageId = session.currentPage.id
        val strokes = sel.strokeIds.mapNotNull { liveStrokes.remove(it) }
        val objects = sel.contentIds.mapNotNull { liveObjects.remove(it) }
        if (strokes.isEmpty() && objects.isEmpty()) return@runPageOp
        session.store.erase(strokes.map { it.id })
        session.objectStore.remove(objects.map { it.id })
        undo.record(Action.ObjectsDeleted(pageId, strokes, objects))
        paper.clearSelection()
        paper.removeStrokes(strokes.map { it.id })   // a data-in call: no erase callback comes back
        paper.notifyContentChanged()
        Slog.d(TAG) { "deleted selection: ${strokes.size} strokes, ${objects.size} objects" }
    }

    private fun showDeleteSheet() {
        if (!opened) return
        paper.releaseRender()
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_trash, getString(R.string.delete_page_action)) { confirmDeletePage() }
            .show()
    }

    private fun confirmDeletePage() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete_page_title)
            .setMessage(R.string.delete_page_body)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
            .setNegativeButton(R.string.cancel, null)
            .create()
        Dialogs.style(dialog)
        dialog.show()
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Chrome rects the stylus must not ink under. Until the notebook is open ([opened]) the whole
     * paper is excluded instead: no pen input while the "Opening…" popup is up (ink written before
     * the page is loaded would be dropped, and the popup promises exactly that nothing is taken yet).
     * Exclusion is applied to the hardware pen layer and filtered model-side by g-paper.
     */
    private fun pushExclusions() {
        if (!::paper.isInitialized || !::selectionToolbar.isInitialized) return
        val view = paper.asView()
        if (!opened) {
            paper.setExclusionRects(listOf(Rect(0, 0, maxOf(view.width, 1), maxOf(view.height, 1))))
            return
        }
        val paperLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val rects = (listOfNotNull(NotebookToolbar.rectOf(binding.topBar), NotebookToolbar.rectOf(binding.bottomStrip)) + selectionToolbar.rects())
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    /**
     * Frame-silence rule: never present an app frame while the pen is active (Ratta overlay ink
     * lags for every frame it must mask). Chrome text changes wait for the gate to open.
     */
    private fun setPageIndicator(n: Int, total: Int) {
        val text = getString(R.string.notebook_page_indicator, n, total)
        whenPenIdle { binding.pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        binding.root.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::paper.isInitialized) {
            val tool = ev.getToolType(0)
            val finger = tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER
            if (finger && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
        }
        // Observer only — never consumes, so pen ink and the toolbar buttons still see every event.
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun overChrome(ev: MotionEvent): Boolean {
        val top = NotebookToolbar.rectOf(binding.topBar)
        val bottom = NotebookToolbar.rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true) ||
            (::selectionToolbar.isInitialized && selectionToolbar.contains(x, y))
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
        renderCache.clear()
        // A destroy that isn't a normal close (e.g. finish() from failOpen after open) still seals.
        if (::session.isInitialized && session.isOpen && !closing) {
            closing = true
            val s = session
            appScope.launch { withContext(NonCancellable) { try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) } } }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotebookActivity"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        /** Phase-3 decisions: raw px, not dp (matches the reference pen and g-paper's defaults). */
        const val PEN_WIDTH_PX = 3f
        const val ERASER_RADIUS_PX = 15f

        /** The H1 debug test object: an identity no provider owns (draws as the placeholder), 200×100 px. */
        private const val TEST_OBJECT_IDENTITY = "debug:box"
        private const val TEST_OBJECT_W = 200f
        private const val TEST_OBJECT_H = 100f

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(context: Context, notebookId: String, name: String): Intent =
            Intent(context, NotebookActivity::class.java).apply {
                putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(EXTRA_NOTEBOOK_NAME, name)
            }
    }
}
