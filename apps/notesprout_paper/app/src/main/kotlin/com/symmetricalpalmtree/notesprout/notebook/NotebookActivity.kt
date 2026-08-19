package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Bounds
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
import com.symmetricalpalmtree.notesprout.extension.CreatedObject
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesprout.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesprout.notebook.UndoRedoStack.Action
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * is [SelectionToolbar] (arc 4 / H2 — its contents from [SelectionActions.merge]); the object
 * providers behind it are [ObjectProviders], the provider-facing flows [ObjectActions], the cache
 * fill [ObjectRenderPass] (arc 4 / H4) — this screen owns only the page mutations they lead to. The
 * chrome geometry (exclusion rects, over-chrome test) is [NotebookChrome]; the Contents (arc 5 / C1)
 * is [ContentsFlow] behind the top-bar list button and the one-finger swipe-down.
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
    private lateinit var chrome: NotebookChrome
    private lateinit var contentsFlow: ContentsFlow
    private val repo by lazy { IndexRepository() }

    private var notebookId: String = ""
    private var opened = false
    private var closing = false
    private var selectionActive = false

    /** In-memory history (notebook-level, survives page turns; dies with the screen). */
    private val undo = UndoRedoStack()
    /** Serialises page/undo operations so overlapping gestures can't corrupt the page list. */
    private val pageOps = Mutex()
    /** Strokes currently on the visible page — the "you still have the strokes" mirror an erase needs.
     *  Insertion-ordered = writing (z) order: loaded in `"order"`, commits append — the order the
     *  recognizer must see a lasso's strokes in (H4). */
    private var liveStrokes: LinkedHashMap<String, Stroke> = LinkedHashMap()
    /** Content objects on the visible page (arc 4) — what [ObjectRenderer] draws and a delete captures.
     *  Insertion-ordered = z-order (loaded in `"order"`; a new object appends). */
    private var liveObjects: LinkedHashMap<String, PageObject> = LinkedHashMap()
    /** Rendered object bitmaps for this open notebook only, filled by [ObjectRenderPass]. */
    private val renderCache = ObjectRenderCache()
    /** Objects whose render failed on this page load — not retried until the next load or an edit. */
    private val renderFailed = HashSet<String>()
    /** The active lasso selection as last reported (bounds follow moves); null when none. */
    private var currentSelection: Selection? = null
    /** The object providers + their toolbar contributions, loaded once per open (refreshed on resume if the set changed). */
    private var providers: ObjectProviders = ObjectProviders.NONE
    private val renderPass by lazy { ObjectRenderPass(this) }
    private lateinit var objectActions: ObjectActions
    /** The background render pass in flight, and whether it must run once more when it ends. */
    private var renderJob: Job? = null
    private var renderAgain = false
    /** A provider's active action ids per object id (for exactly that payload) — spares a bind per re-selection. */
    private val activeIdsCache = HashMap<String, Pair<String, Set<String>>>()
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
            pageWidth = { if (opened) session.currentPage.width.toFloat() else 0f },
            dpi = { dpi() },
        ))
        objectActions = ObjectActions(this, { providers }, objectListener)

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
                override fun onParentOpened(providerKey: String?, action: ToolbarAction) {
                    if (providerKey != null && opened && !closing) objectActions.warm(action)
                }
                override fun onAction(providerKey: String?, action: ToolbarAction) {
                    val sel = currentSelection ?: return
                    if (providerKey == null || !opened || closing) return
                    // In writing order (liveStrokes is insertion-ordered = commit / z order), NOT the
                    // selection's Set order — an online recognizer reads strokes as a sequence, and a
                    // hash-ordered "Meeting Notes" came back as four characters.
                    val strokes = liveStrokes.values.filter { it.id in sel.strokeIds }
                    val objects = sel.contentIds.mapNotNull { liveObjects[it] }
                    val one = if (sel.strokeIds.isEmpty() && objects.size == 1) objects[0] else null
                    if (one == null && (strokes.isEmpty() || objects.isNotEmpty())) return   // mixed: core actions only
                    objectActions.perform(providerKey, action, strokes, sel.bounds, one, liveObjects.size)
                }
            },
        )
        binding.notebookName.text = name
        binding.pageIndicator.text = ""
        // Debug builds only (no-op in release): the ⋯ at the end of the top bar. Inside topBar, so the
        // existing exclusion rect covers it. It sees only the page's strokes + px size — never the session.
        NotebookDebugMenu.install(this, binding.topBarRow, provider = {
            if (!opened) null
            else RecognizeContext(paper.getStrokes(), session.currentPage.width.toFloat(), session.currentPage.height.toFloat())
        }, contents = { if (!opened || closing) null else ContentsSource.gather(this, session, providers) })   // arc 5 / C0 probe

        chrome = NotebookChrome(paper, binding.topBar, binding.bottomStrip, selectionToolbar) { !opened || contentsFlow.showing }
        contentsFlow = ContentsFlow(
            this, paper, { session }, { providers }, { session.currentIndex }, { opened && !closing },
            onShowingChanged = { binding.root.post { pushExclusions() } }, navigate = { index -> runPageOp { navigateTo(index) } },
            button = binding.btnContents, whenPenIdle = ::whenPenIdle,
        )
        binding.btnContents.setOnClickListener { contentsFlow.open() }.also { TooltipCompat.setTooltipText(binding.btnContents, binding.btnContents.contentDescription) }   // inside topBar: chrome release + exclusion cover it
        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            selectionActive = { selectionActive },
            overChrome = { chrome.overChrome(it) },
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
        renderCache.retain(liveObjects.keys)   // bitmaps of other pages / removed objects go (H5: the cache was unbounded)
        paper.loadStrokes(strokes)
        paper.notifyContentChanged()
        liveStrokes = strokes.associateByTo(LinkedHashMap()) { it.id }
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
        // Providers after the paper is live: their binds must not hold the "Opening…" popup up. The
        // toolbar shows Delete only until they arrive; the first render pass follows them.
        lifecycleScope.launch { loadProviders() }
    }

    /** Discover + describe the object providers (IO binds), then re-render and re-show whatever waited on them. */
    private suspend fun loadProviders() {
        val loaded = ObjectProviders.load(this)
        if (!opened || closing) return
        providers = loaded
        activeIdsCache.clear()
        renderFailed.clear()
        contentsFlow.refresh()   // arc 5: the Contents entry points exist only with an outline-capable provider AND a heading in the notebook
        scheduleRenderPass()
        currentSelection?.let { showSelectionToolbar(it) }
        objectActions.warmAtOpen()   // the recognizer's process starts + primes while the user is still writing (H5)
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
            // Captured in writing order (restore re-numbers `"order"` in list order — the recognizer reads it).
            val ids = strokeIds.toHashSet()
            val captured = liveStrokes.values.filter { it.id in ids }
            for (id in strokeIds) liveStrokes.remove(id)
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
            if (objectIds.isNotEmpty()) scheduleRenderPass()   // an object pushed against the right edge re-ellipsizes
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
         *  selected object under the tap → the provider's `describeEdit` → [ObjectEditDialog] (at once —
         *  the pen hovers after a tap, see [showSelectionToolbar]; dropped if the selection changed
         *  meanwhile) → Save → `applyEdit` → [objectListener]. Strokes selected, or a tap outside the
         *  object → nothing. */
        override fun onSelectionTapped(x: Float, y: Float) {
            Slog.d(TAG) { "selection tapped ${x.toInt()},${y.toInt()} (objects=${currentSelection?.contentIds?.size ?: 0})" }
            val sel = currentSelection ?: return
            if (sel.strokeIds.isNotEmpty() || sel.contentIds.size != 1 || !opened || closing) return
            val obj = liveObjects[sel.contentIds.first()] ?: return
            if (!obj.bounds.contains(x, y)) return
            val providerKey = ExtensionContract.parseIdentity(obj.providerIdentity)?.first ?: return
            objectActions.editTapped(providerKey, obj) { spec, onSave ->
                if (currentSelection != sel || closing) return@editTapped
                paper.releaseRender()
                ObjectEditDialog.show(this@NotebookActivity, spec, onSave)
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
        override fun onSwipeDown() { contentsFlow.open() }   // silent while not `available` (Q2 + item 9)
    }

    // ── Selection toolbar (arc 4 / H2) ───────────────────────────────────────

    /**
     * Show the toolbar for [sel] **at once** — not through [whenPenIdle]: a lasso ends with the pen up
     * and, on EMR panels, hovering right over the page, so `isPenActive` would hold the toolbar back
     * until the pen leaves hover range (H5 finding). g-paper is already presenting the selection box
     * frame at this point, so a chrome frame here breaks no frame silence. Contents = Delete + the
     * contributions that apply to the selection's shape (INK / one OBJECT of a provider's type / mixed →
     * core only); active sub-action ids come from the object's provider (async, dropped if the
     * selection changed meanwhile).
     */
    private fun showSelectionToolbar(sel: Selection) {
        if (!opened || closing) return
        val objects = sel.contentIds.mapNotNull { liveObjects[it] }
        val shape = SelectionActions.shapeOf(sel.strokeIds.size, objects.map { it.providerIdentity })
        val items = SelectionActions.merge(coreActions, providers.contributions, shape)
        if (shape !is SelectionActions.Shape.OneObject) { presentSelectionToolbar(sel, items, emptySet()); return }
        val obj = objects[0]
        activeIdsCache[obj.id]?.takeIf { it.first == obj.payload }?.let { presentSelectionToolbar(sel, items, it.second); return }
        lifecycleScope.launch {
            val ids = try {
                val got = providers.clientFor(this@NotebookActivity, shape.providerKey)?.activeActionIds(shape.typeId, obj.payload) ?: emptySet()
                activeIdsCache[obj.id] = obj.payload to got   // cached on success only — a failed call is asked again next time (H5)
                got
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "activeActionIds failed: ${e.message}" }; emptySet()
            }
            presentSelectionToolbar(sel, items, ids)
        }
    }

    private fun presentSelectionToolbar(sel: Selection, items: List<ToolbarItem>, active: Set<String>) {
        if (!opened || closing || currentSelection != sel) return
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
        session.writer.drain()   // queued creates / erases land before the page is read back (H5)
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
        renderCache.retain(liveObjects.keys)   // bitmaps of other pages / removed objects go (H5: the cache was unbounded)
        paper.loadStrokes(strokes)
        paper.notifyContentChanged()
        liveStrokes = strokes.associateByTo(LinkedHashMap()) { it.id }
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        session.saveLastOpened()
        renderFailed.clear()
        scheduleRenderPass()
        contentsFlow.refresh()
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

    // ── Content objects (arc 4 — H2 toolbar Delete, H4 provider actions) ──────

    /** The selection toolbar's Delete: the whole selection — strokes and/or objects — as one undoable
     *  step ([Action.ObjectsDeleted]). `clearSelection` dismisses → the toolbar hides. */
    private fun deleteSelection() = runPageOp {
        val sel = currentSelection ?: return@runPageOp
        val pageId = session.currentPage.id
        val strokes = liveStrokes.values.filter { it.id in sel.strokeIds }   // writing order (see onStrokesErased)
        for (s in strokes) liveStrokes.remove(s.id)
        val objects = sel.contentIds.mapNotNull { liveObjects.remove(it) }
        if (strokes.isEmpty() && objects.isEmpty()) return@runPageOp
        session.store.erase(strokes.map { it.id })
        session.objectStore.remove(objects.map { it.id })
        undo.record(Action.ObjectsDeleted(pageId, strokes, objects))
        paper.clearSelection()
        paper.removeStrokes(strokes.map { it.id })   // a data-in call: no erase callback comes back
        paper.notifyContentChanged()
        Slog.d(TAG) { "deleted selection: ${strokes.size} strokes, ${objects.size} objects" }
        if (objects.isNotEmpty()) contentsFlow.refresh()
    }

    // ── Object actions → page mutations (arc 4 / H4) ─────────────────────────

    /** What [ObjectActions] hands back; every mutation is one undo step under [pageOps]. */
    private val objectListener = object : ObjectActions.Listener {
        /** Create at the lasso box's top-left, soft-delete the ink, render (sizes the object to its
         *  image), then select it — one [Action.ObjectCreated]. Abandoned if the ink is gone meanwhile
         *  (erased, or the page turned during the consent dialog). */
        override suspend fun onCreated(providerKey: String, created: CreatedObject, strokes: List<Stroke>, bounds: Bounds) {
            pageOps.withLock {
                if (!opened || closing) return
                val live = strokes.filter { it.id in liveStrokes }
                if (live.isEmpty()) { Slog.d(TAG) { "created object dropped: its ink is gone" }; return }
                val page = session.currentPage
                val obj = PageObject(
                    id = UUID.randomUUID().toString(),
                    providerIdentity = ExtensionContract.objectIdentity(providerKey, created.typeId), payload = created.payload,
                    x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
                    order = (liveObjects.values.maxOfOrNull { it.order } ?: -1) + 1,
                )
                val ids = live.map { it.id }
                session.objectStore.create(page.id, obj)
                liveObjects[obj.id] = obj
                for (id in ids) liveStrokes.remove(id)
                session.store.erase(ids)
                paper.removeStrokes(ids)   // data-in: dismisses the selection (toolbar hides) and redraws — the object as a placeholder until rendered
                Slog.d(TAG) { "object created (${created.typeId}) from ${ids.size} strokes at ${bounds.left.toInt()},${bounds.top.toInt()}" }
                renderNow(listOf(obj))
                // Recorded with the *rendered* object (H5): a redo restores the row from this, and the
                // pre-render lasso box would have come back as stale bounds under a still-valid cache entry.
                undo.record(Action.ObjectCreated(page.id, liveObjects[obj.id] ?: obj, live))
                selectObject(obj.id)
                contentsFlow.refresh()
            }
        }

        /** Persist the new payload, re-render (may re-size), record [Action.ObjectEdited] with the final
         *  bounds, re-select so the toolbar's active ids and anchor follow. Stale (object gone / payload
         *  moved on) → nothing. */
        override suspend fun onPayloadChanged(obj: PageObject, payload: String) {
            pageOps.withLock {
                if (!opened || closing) return
                val before = liveObjects[obj.id] ?: return
                if (before.payload != obj.payload) return
                val page = session.currentPage
                val after = before.copy(payload = payload)
                session.objectStore.updatePayloadAndBounds(after.id, after.payload, after.x, after.y, after.width, after.height)
                liveObjects[after.id] = after
                renderFailed.remove(after.id)
                activeIdsCache.remove(after.id)
                renderNow(listOf(after))
                // `before` re-anchored at the final position (H5): a drag of the still-selected object
                // during the render round-trip is its own Action.Moved — an edit records payload + size only.
                val final = liveObjects[after.id] ?: after
                undo.record(Action.ObjectEdited(page.id, before.copy(x = final.x, y = final.y), final))
                if (currentSelection?.contentIds == setOf(after.id)) selectObject(after.id)
                Slog.d(TAG) { "object ${after.id} payload changed (${payload.length} chars)" }
            }
        }
    }

    /** Host-initiated selection of one object (no `onSelectionCreated` echo — the state is set here). */
    private fun selectObject(id: String) {
        val obj = liveObjects[id] ?: return
        paper.setSelection(emptySet(), setOf(id), obj.bounds)
        selectionActive = true
        val sel = Selection(emptySet(), setOf(id), obj.bounds)
        currentSelection = sel
        showSelectionToolbar(sel)
    }

    /** Render [objects] inline (IO) and apply — the create / apply / edit path, awaited under [pageOps].
     *  The frame is presented **at once** (H5): the user just tapped a toolbar button or Save — the pen
     *  is up (hovering), `releaseRender` already ran — so waiting for pen-idle only delayed the heading
     *  until the pen left hover range. */
    private suspend fun renderNow(objects: List<PageObject>) {
        val page = session.currentPage
        applyRenderResults(renderPass.render(objects, providers, page.width.toFloat(), dpi()), atOnce = true)
    }

    /**
     * The background cache fill (page load, provider change, a move): every live object without a
     * cached image for its (payload, width, dpi) that hasn't failed on this load → one pass; a trigger
     * during a pass queues exactly one more (the page may have changed under it). Never holds
     * [pageOps]; results for objects no longer on the page are dropped by [applyRenderResults].
     */
    private fun scheduleRenderPass() {
        if (!opened || closing) return
        if (renderJob?.isActive == true) { renderAgain = true; return }
        renderJob = lifecycleScope.launch {
            do {
                renderAgain = false
                val page = session.currentPage
                val d = dpi()
                val misses = liveObjects.values.filter {
                    it.id !in renderFailed && renderCache.get(it.id, it.payload, ObjectRenderer.renderWidth(page.width.toFloat(), it), d) == null
                }
                if (misses.isEmpty()) break
                val results = renderPass.render(misses, providers, page.width.toFloat(), d)
                if (!opened || closing) break
                applyRenderResults(results)
            } while (renderAgain)
        }
    }

    /** Main: cache the images, size each object to its image (persisted; anchored top-left), one frame —
     *  pen-idle for the background pass (the user may be writing), [atOnce] for the inline path. */
    private fun applyRenderResults(results: List<ObjectRenderPass.Result>, atOnce: Boolean = false) {
        var changed = false
        for (r in results) {
            val o = liveObjects[r.id] ?: continue
            if (o.payload != r.payload) continue   // edited while rendering — the next pass has it
            val bmp = r.bitmap
            if (bmp == null) { renderFailed.add(r.id); continue }
            renderCache.put(r.id, r.payload, r.maxWidth, r.dpi, bmp)
            changed = true
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            if (w != o.width || h != o.height) {
                val sized = o.copy(width = w, height = h)
                liveObjects[r.id] = sized
                session.objectStore.updatePayloadAndBounds(sized.id, sized.payload, sized.x, sized.y, sized.width, sized.height)
            }
        }
        if (!changed) return
        if (atOnce) { if (opened && !closing) paper.notifyContentChanged() }
        else whenPenIdle { if (opened && !closing) paper.notifyContentChanged() }
    }

    private fun dpi(): Float = resources.displayMetrics.densityDpi.toFloat()

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
     * Chrome rects the stylus must not ink under ([NotebookChrome]). Until the notebook is open
     * ([opened]) — and while the Contents dialog is up — the whole paper is excluded instead: no pen
     * input while the "Opening…" popup is up (ink written before the page is loaded would be dropped,
     * and the popup promises exactly that nothing is taken yet), none under the Contents (the Onyx raw
     * pen path bypasses the window stack).
     */
    private fun pushExclusions() {
        if (::chrome.isInitialized) chrome.pushExclusions()
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::chrome.isInitialized) {
            val tool = ev.getToolType(0)
            val finger = tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER
            if (finger && !paper.isPenActive && chrome.overChrome(ev)) paper.releaseRender()
        }
        // Observer only — never consumes, so pen ink and the toolbar buttons still see every event.
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
        // An extension installed / removed / disabled while the screen was away: cheap discovery compare, reload only on a change.
        if (opened && !closing) lifecycleScope.launch {
            val sig = ObjectProviders.signature(this@NotebookActivity)
            if (opened && !closing && sig != providers.signature) { Slog.d(TAG) { "extension set changed — reloading providers" }; loadProviders() }
        }
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

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(context: Context, notebookId: String, name: String): Intent =
            Intent(context, NotebookActivity::class.java).apply {
                putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(EXTRA_NOTEBOOK_NAME, name)
            }
    }
}
