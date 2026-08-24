package com.symmetricalpalmtree.notesproutsn.notebook

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
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.SnClipboard
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseState
import com.symmetricalpalmtree.notesproutsn.data.prefs.LinkTrail
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix
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
 *
 * **Over the ~800-line rule, with reason (N3):** this file is the single integration seam between
 * the engine's callbacks, the selection/heading/link flows, undo replay and the lifecycle — all of
 * which share tightly-coupled Main-thread state (`liveStrokes`/`liveHeadings`/`liveLinks`/
 * `displayedPageId`/`loadingCommits`/`selectionActive`). Everything separable already lives in
 * collaborators (session, stores, renderers, toolbars, gestures, dialogs); splitting what remains
 * would scatter that shared state behind accessors without reducing the coupling that makes it delicate.
 */
class NotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: NotebookToolbar
    private lateinit var selectionToolbar: SelectionToolbar
    private lateinit var lassoPopup: LassoPopup
    private lateinit var session: NotebookSession
    private lateinit var pageGestures: PageGestures
    private lateinit var contentsFlow: ContentsFlow
    private lateinit var linkPickFlow: LinkPickFlow
    private lateinit var followFlow: LinkFollowFlow
    private val repo by lazy { IndexRepository() }

    /** The global clipboard's one index row (arc 7) — the payload, read and written only here. */
    private val clipStore by lazy { ClipStore() }

    private var notebookId: String = ""
    private var opened = false
    private var closing = false

    /** Arrived by following a link (K4): the persisted trail survives and both Backs walk it. */
    private var viaLink = false

    /**
     * The follow's target page, overriding the notebook's remembered `refId` for this open only —
     * read from the Intent **only on a fresh create** (locked K4): Android redelivers the original
     * Intent on a recreate, and re-applying the override would land a rebuilt via-link notebook on
     * the link's target instead of where the user actually was (the Paper quirk, fixed here).
     * Consumed by [openSession]; a dead target falls back to `refId` silently — the honest dialog
     * was the tapping side's job.
     */
    private var initialPageId: String? = null

    /** True while a lasso selection is up — the gesture detector stands down on it. */
    private var selectionActive = false

    /**
     * The live selection, kept because a delete needs its stroke ids after the fact. Updated in
     * place on a move (the engine keeps the selection alive at its new position) and dropped the
     * moment the engine says it is gone. Never read as "is there a selection" — [selectionActive]
     * is that flag.
     */
    private var currentSelection: Selection? = null

    /**
     * The selection a just-created object (a converted heading, a wrapped link) wants as the
     * *successor* of the selection its creation is about to dismiss — held as the action rather
     * than the object, because the two kinds land on different bars. Set immediately before the
     * creation's `removeStrokes`, consumed inside `onSelectionDismissed` (see the note there for
     * why the timing is load-bearing), and drained defensively right after in case none fired.
     */
    private var pendingSelection: (() -> Unit)? = null

    /**
     * Whether the contact now in flight is the one that took the lasso popup down (arc 8) —
     * rewritten at every ACTION_DOWN, read by `onPaperTapped` so a dismissal is never also a paste.
     * See [dismissLassoPopupOnContact].
     */
    private var tapDismissedPopup = false

    /** In-memory, notebook-level history: it survives page turns and dies with the screen. */
    private val undo = UndoRedoStack()

    /** Serialises page/undo operations so two overlapping gestures can't tangle the page list. */
    private val pageOps = Mutex()

    /** The strokes on the visible page — the "you still have them" mirror an erase undo needs. */
    private var liveStrokes: MutableMap<String, Stroke> = mutableMapOf()

    /** The headings on the visible page — the working copy [headingRenderer] paints from. */
    private var liveHeadings: MutableMap<String, Heading> = linkedMapOf()

    /** The links on the visible page — the working copy [linkRenderer] paints from (K1). Each one
     *  carries the content it wraps, which is why it is the only place a wrapped stroke exists. */
    private var liveLinks: MutableMap<String, PageLink> = linkedMapOf()

    /** Draws [liveHeadings] into the committed layer, below the ink (N2). */
    private lateinit var headingRenderer: HeadingRenderer

    /** Draws [liveLinks] — composites plus chrome — below the ink and below the headings (K1). */
    private lateinit var linkRenderer: LinkRenderer

    /**
     * The page whose strokes are on the paper — written on Main only, at the two places
     * `loadStrokes` runs. The g-paper callbacks stamp their rows with THIS, never with
     * `session.currentPage`: the session's `pages`/`currentIndex` mutate on IO mid-flip (`goTo`
     * advances the index before the swap reaches the paper), so a pen-up racing a flip would
     * otherwise persist ink to the destination page — and a torn read of the pair can crash.
     * What the user inked is the page they were looking at.
     */
    private var displayedPageId: String = ""

    /**
     * Armed (on Main) for the duration of `navigateTo`'s suspending loads: target page id plus a
     * buffer `onStrokeCommitted` adds to when a pen-up for that page lands mid-load. The rebuild
     * merges the buffer so the fresh stroke survives `loadStrokes` instead of vanishing until the
     * next flip. Null whenever no load is in flight.
     */
    private var loadingCommits: Pair<String, MutableList<Stroke>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""
        viaLink = intent.getBooleanExtra(EXTRA_VIA_LINK, false)
        if (savedInstanceState == null) initialPageId = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID)

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

        // The page's headings live in g-paper's committed layer via this renderer — registered
        // before any load so the first re-record already knows about them.
        val dm = resources.displayMetrics
        headingRenderer = HeadingRenderer(dm.density, dm.scaledDensity)
        paper.addContentRenderer(headingRenderer)
        // The links go in after the headings: same layer, and a link's composite already holds the
        // headings it wrapped — registration order is what puts a link's own chrome on top of them.
        linkRenderer = LinkRenderer(dm.density, dm.scaledDensity)
        paper.addContentRenderer(linkRenderer)

        // The toolbar owns all pen/eraser configuration — fixed values, no panels, no prefs.
        // Its Back goes through backPressed(), never straight to close(): in a via-link notebook
        // BOTH Backs walk the trail (the Paper L4 funnel — its top-bar Back initially didn't).
        toolbar = NotebookToolbar(
            binding = binding,
            paper = paper,
            onBack = { backPressed() },
            // A second tap on the armed lasso opens the clipboard popup (arc 8) — and stays P1's
            // silent no-op when there is nothing of ours on the clipboard.
            onLassoReTap = { if (lassoPopup.isShowing) hideLassoPopup() else showLassoPopup() },
            // Arming a different tool takes the popup with it: it belongs to the lasso.
            onToolTapped = { if (lassoPopup.isShowing) hideLassoPopup() },
        )
        lassoPopup = LassoPopup(
            root = binding.root,
            bar = binding.lassoPopup,
            anchor = binding.btnLasso,
            bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onPaste = { hideLassoPopup(); doObjectPaste(tapX = null, tapY = null) },
            onClear = { hideLassoPopup(); doClipboardClear() },
        )
        // Must exist before RESUMED (it registers an ActivityResult launcher); the lambdas it
        // holds only fire from the toolbar, which the `opened` flag already gates.
        linkPickFlow = LinkPickFlow(
            activity = this,
            session = { session },
            displayedPageId = { displayedPageId },
            applyCreate = { sel, payload -> createLinkFromSelection(sel, payload) },
            applyEdit = { linkId, before, after -> applyLinkEdit(linkId, before, after) },
            createPage = { anchorId, before -> pickerCreatePage(anchorId, before) },
            // A page created from the picker invalidates every Structural snapshot in the stack
            // (they name a page list that no longer exists), so the history goes rather than lie.
            onPagesChanged = {
                undo.clear()
                setPageIndicator(session.currentIndex + 1, session.pages.size)
                contentsFlow.refresh()
            },
        )
        selectionToolbar = SelectionToolbar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            subBar = binding.selectionSubToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            onLevelPicked = { onLevelPicked(it) },
            onLink = { beginLinkPick() },
            onEditLink = { beginLinkEdit() },
            onUnlink = { unlinkSelection() },
            onCopy = { cut -> doObjectCopy(cut) },
        )
        binding.notebookName.text = name
        binding.pageIndicator.text = ""

        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { overChrome(it) },
            listener = gestureListener,
        )

        contentsFlow = ContentsFlow(
            activity = this,
            paper = paper,
            session = { session },
            // displayedPageId, never session.currentIndex — the R6 torn-read rule applied to the
            // highlight: what the user sees is the page whose strokes are on the paper.
            currentPageIndex = { session.pages.indexOfFirst { it.id == displayedPageId }.coerceAtLeast(0) },
            alive = { opened && !closing },
            onShowingChanged = { pushExclusions() },
            // By page id, resolved at tap time under the page-op lock (refreshToPage no-ops if the
            // page died while the dialog was up); a snapshot index would go stale under a page op
            // that committed mid-gather. Current page → no reload.
            navigate = { pageId -> if (pageId != displayedPageId) runPageOp { refreshToPage(pageId) } },
            button = binding.btnContents,
            whenPenIdle = ::whenPenIdle,
        )

        // Chrome moved/appeared/disappeared: re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }

        followFlow = LinkFollowFlow(
            activity = this,
            session = { session },
            displayedPageId = { displayedPageId },
            liveLinks = { liveLinks.values },
            alive = { opened && !closing },
            navigateToPage = { pageId -> runPageOp { refreshToPage(pageId) } },
            closeAndLaunch = { target -> close { startActivity(target) } },
            editLink = { link -> linkPickFlow.beginEdit(link) },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backPressed() }
        })

        BrowseState(this).let {
            it.lastOpenNotebookId = notebookId
            // A cold restore must reopen this notebook the same way it was open — losing the
            // via-link flag would clear the persisted trail and take the walk-back with it.
            it.lastOpenViaLink = viaLink
        }
        RecentsPrefs(this).record(notebookId)
        // Any fresh, non-via-link open starts a new story: the old trail would walk back into it.
        // Gated like the initial-page consume above: a recreate or a post-process-death task
        // rebuild is not a fresh open, and clearing there would strand a mid-story walk-back —
        // the trail is persisted precisely to survive that death (K5 review).
        if (!viaLink && savedInstanceState == null) LinkTrail(this).clear()

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
            // The follow's target page overrides the remembered one — once. A target that died in
            // the race falls back to refId silently (one arrival semantic; the pre-checks on the
            // tapping side carry the honesty).
            initialPageId?.let { want ->
                initialPageId = null
                val idx = session.pages.indexOfFirst { it.id == want }
                if (idx >= 0 && idx != session.currentIndex) session.goTo(idx)
            }
            // One blob-free row read, before the page can be long-pressed: the sheet decides
            // whether a Paste row exists synchronously, and this is where the process-wide header
            // gets rehydrated (SN's index only opens at Bootstrap — see SnClipboard).
            SnClipboard.ensureLoaded()
            // …and the same read decides whether the lasso button wears its clipboard mark: the
            // clipboard survives a force-stop, so a notebook opened tomorrow must still say that a
            // tap will paste.
            toolbar.showClipboardLoaded(SnClipboard.hasObjects)
            val page = session.currentPage
            val strokes = session.store.loadPage(page.id)
            val headings = remeasureForDevice(session.headings.loadPage(page.id))
            val links = withUnderlineBand(session.links.loadPage(page.id))
            val linkBitmaps = linkRenderer.prebuild(links)   // raster off Main, in the load phase
            paper.setPageSize(page.width, page.height)
            paper.setTemplate(session.template)
            // Renderers before loadStrokes: the load's re-record is the frame that paints them, and
            // a link's composite must exist by then — building it later, behind a pen-idle gate, is
            // the arc's standing hover-repaint trap (chrome invisible while the pen hovers).
            liveHeadings = headings.associateByTo(linkedMapOf()) { it.id }
            headingRenderer.headings = headings
            liveLinks = links.associateByTo(linkedMapOf()) { it.id }
            linkRenderer.update(links, linkBitmaps)
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
            contentsFlow.refresh()
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
            // A navigateTo to this same page may be mid-load (an undo replay's refresh): its row
            // read can miss this commit, and its loadStrokes would wipe the stroke off the glass.
            // The buffer hands it to the rebuild instead of losing it until the next flip.
            loadingCommits?.let { (loadingPageId, buffer) ->
                if (loadingPageId == pageId) buffer.add(stroke)
            }
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
            // Headings that rode the same drag: reposition rows + working copy, then tell the
            // engine to re-record — the component only ghosted/live-dragged them; the host owns
            // where they actually are now (the ContentRenderer contract).
            val headingIds = move.contentIds.filter { liveHeadings.containsKey(it) }
            val linkIds = move.contentIds.filter { liveLinks.containsKey(it) }
            if (headingIds.isNotEmpty()) {
                session.headings.move(headingIds, move.dx, move.dy)
                for (id in headingIds) liveHeadings[id]?.let { liveHeadings[id] = it.translated(move.dx, move.dy) }
                headingRenderer.headings = liveHeadings.values.toList()
            }
            if (linkIds.isNotEmpty()) {
                // The store shifts the row AND its wrapped children; the working copy's `translated`
                // does the same in memory, so the composite (translation-invariant) is reused as-is.
                session.links.move(linkIds, move.dx, move.dy)
                for (id in linkIds) liveLinks[id]?.let { liveLinks[id] = it.translated(move.dx, move.dy) }
                linkRenderer.update(liveLinks.values.toList())
            }
            // One drag is one re-record, whatever kinds rode along.
            if (headingIds.isNotEmpty() || linkIds.isNotEmpty()) paper.notifyContentChanged()
            undo.record(Action.Moved(pageId, ids, move.dx, move.dy, headingIds, linkIds))
            // The selection survives a move, at its new position — keep our copy honest.
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            // The drag is over (this fires at lift), so bring the bar back where the box now is.
            currentSelection?.let { showSelectionToolbar(it) }
        }
        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // A selection and the clipboard popup are two answers to the same button — the newer
            // one wins. (The outline's pen-down already took it down; this covers a smart-lasso
            // selection, which never touches the surface as a lasso contact.)
            hideLassoPopup()
            // Shown immediately, **not** through the pen-idle gate: a lasso ends with the pen still
            // hovering over the glass (`isPenActive` counts proximity + a 350 ms tail), so an
            // idle-gated bar would arrive long after the selection it belongs to — the R3 panel
            // lesson. The engine has already presented the selection box, so this frame is part of
            // that same presentation, not a repaint during writing.
            showSelectionToolbar(selection)
        }
        /**
         * A sub-threshold tap inside the selection box: on a selected heading it opens the edit
         * dialog (the one tap-to-edit path). The engine already palm-gated and escrowed the tap.
         */
        override fun onSelectionTapped(x: Float, y: Float) {
            if (!opened) return
            val sel = currentSelection ?: return
            val h = sel.contentIds.asSequence().mapNotNull { liveHeadings[it] }
                .firstOrNull { it.bounds.contains(x, y) } ?: return
            // Ungated for the SelectionToolbar-button reason: the tap has to show its result and
            // the dialog repaints over the page; there is no live stroke at a tap's pen-up.
            paper.releaseRender()
            HeadingEditDialog.show(this@NotebookActivity, h) { raw -> applyHeadingEdit(h.id, raw) }
        }
        /**
         * A sub-threshold pen tap on bare paper with the lasso armed and nothing selected (0.1.5):
         * **paste here** — the pasted set lands centred on the tap ([ObjectPlacement.centredOn]).
         *
         * Silent when the clipboard holds no objects of ours (a page, or nothing): neither the
         * button's mark nor the popup was offering a paste, so there is no failed expectation to
         * explain — the O1 phase-start decision. The engine never fires this for a finger, for a
         * palm, or for the tap that dismissed a selection.
         */
        override fun onPaperTapped(x: Float, y: Float) {
            if (!opened || closing) return
            // A contact spent taking the popup down is not a placement — the same rule the engine
            // applies to the tap that dismisses a selection. The user taps again to paste.
            if (tapDismissedPopup) return
            if (!SnClipboard.hasObjects) return
            doObjectPaste(tapX = x, tapY = y)
        }
        /**
         * The eraser tool swept a heading or a link whole (0.1.4): the host deletes — nothing
         * vanishes by itself. One batched call per gesture; scribble erase never reports content.
         * A link erases **whole**, wrapped content and all (the locked K1 model) — the eraser can
         * never reach inside one.
         */
        override fun onContentErased(contentIds: List<String>) {
            if (!opened) return
            val pageId = displayedPageId
            val headingIds = contentIds.filter { liveHeadings.containsKey(it) }
            val links = contentIds.mapNotNull { liveLinks[it] }
            if (headingIds.isEmpty() && links.isEmpty()) return
            if (headingIds.isNotEmpty()) {
                session.headings.erase(headingIds)
                headingIds.forEach { liveHeadings.remove(it) }
                headingRenderer.headings = liveHeadings.values.toList()
            }
            if (links.isNotEmpty()) {
                session.links.remove(links)
                links.forEach { liveLinks.remove(it.id) }
                linkRenderer.update(liveLinks.values.toList())
            }
            paper.notifyContentChanged()
            // One sweep is one entry. A link's restore needs its full snapshot (row + wrapped
            // children), so anything with a link in it is recorded as a Deleted covering both
            // kinds rather than two entries the user would have to undo twice.
            if (links.isNotEmpty()) undo.record(Action.Deleted(pageId, emptyList(), headingIds, links))
            else undo.record(Action.HeadingDeleted(pageId, headingIds))
            // Wrapped headings are out of the outline while wrapped (their parent is the link, not
            // the page), so erasing a link that holds one changes the Contents just as a loose one does.
            if (headingIds.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
            Slog.d(TAG) { "eraser removed ${headingIds.size} headings, ${links.size} links" }
        }
        /** The pen is dragging the box — the bar would be dragged over, and it never follows live. */
        override fun onSelectionDragStarted() { selectionToolbar.hide() }
        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
            // A conversion's successor selection must be injected HERE, inside the engine's
            // `clearSelection` — it checks for a successor *after* this callback, and only ends a
            // smart-lasso session (restoring PEN) when there is none. Injected any later, the
            // restore has already fired and the new heading sits selected under a PEN tool that
            // can neither drag nor tap it (eye-check #5 round-2 finding). The engine then owns the
            // PEN restore at this selection's own dismissal, exactly like any smart-lasso session.
            pendingSelection?.let { select ->
                pendingSelection = null
                select()
            }
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
        override fun onPageSheetRequested() { showPageSheet() }
        override fun onSwipeDown() { contentsFlow.open() }   // silently a no-op while unavailable
        override fun onSwipeUp() { followFlow.walkBack(onEmpty = {}) }   // empty trail: silent
        override fun onFingerTap(x: Float, y: Float) {
            // Gesture coordinates are the window's; link bounds are page px = paper-view px.
            val loc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
            followFlow.followAt(x - loc[0], y - loc[1])
        }
    }

    /** Both Backs — the toolbar button and the system back — funnel here: in a via-link notebook
     *  they walk the trail (Paper L4's rule); otherwise, or with the trail empty, they close.
     *  Only a screen that is actually open walks the trail: while the session is still opening
     *  (or once closing) `walkBack`'s alive/busy door would swallow the press silently, leaving
     *  Back dead for the whole opening window — fall through to `close()`, which settles the
     *  half-open session (K5 review). */
    private fun backPressed() {
        if (viaLink && opened && !closing) followFlow.walkBack(onEmpty = { close() }) else close()
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
        // goTo and the loads suspend, and a pen-up can land in those windows (everything runs on
        // Main, but every suspension frees the thread). A commit for the TARGET page made mid-load
        // (only possible when the target is the displayed page — an undo replay's refresh) is
        // persisted yet absent from the read, and loadStrokes would silently take it off the glass
        // until the next flip. onStrokeCommitted buffers such commits; they merge into the rebuild.
        val targetId = session.pages[index.coerceIn(0, session.pages.lastIndex)].id
        val lateCommits = mutableListOf<Stroke>()
        loadingCommits = targetId to lateCommits
        val page: PageRef
        val strokes: List<Stroke>
        val headings: List<Heading>
        val links: List<PageLink>
        val linkBitmaps: Map<String, android.graphics.Bitmap>
        try {
            page = session.goTo(index)
            strokes = session.store.loadPage(page.id)
            headings = remeasureForDevice(session.headings.loadPage(page.id))
            links = withUnderlineBand(session.links.loadPage(page.id))
            // Composites raster off Main here, inside the buffered-commit window — never in the
            // display block below, where a link-heavy page would stall the flip frame (K5 review).
            linkBitmaps = linkRenderer.prebuild(links)
        } finally {
            loadingCommits = null
        }
        val allStrokes = strokes + lateCommits.filter { late -> strokes.none { it.id == late.id } }
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionToolbar.hide()   // idempotent — clearSelection fires onSelectionDismissed too
        hideLassoPopup()          // it belongs to the page being left, like every other floating bar
        paper.clearForContentSwap()
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        // Renderers before loadStrokes: the swap's single re-record paints the new page's headings
        // and links — the composites have to be built by then (the hover-repaint trap).
        liveHeadings = headings.associateByTo(linkedMapOf()) { it.id }
        headingRenderer.headings = headings
        liveLinks = links.associateByTo(linkedMapOf()) { it.id }
        linkRenderer.update(links, linkBitmaps)
        paper.loadStrokes(allStrokes)
        liveStrokes = allStrokes.associateBy { it.id }.toMutableMap()
        displayedPageId = page.id
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        session.saveLastOpened()
        // One line covers every flip, insert, delete AND every undo/redo replay (they all end in
        // refreshToPage → here) — the Contents gate stays honest without a per-action sprinkle.
        contentsFlow.refresh()
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
        // Drain first: a stroke commit still queued on the writer would otherwise land AFTER the
        // delete's liveContentIds snapshot and transaction — a permanently live orphan row under a
        // soft-deleted page, invisible to the recorded snapshot and to redo's reconcile.
        session.store.drain()
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
            // revive, not a tail-append: the rows still hold their geometry, and putting them back
            // IN PLACE preserves both the pre-erase z-order and the page's writing order — which a
            // later lasso-convert reads as a sequence (the arc-3 ML Kit trap).
            is Action.Erased -> { session.store.revive(a.strokes.map { it.id }); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Deleted -> {
                session.store.revive(a.strokes.map { it.id })
                session.headings.restore(a.headingIds)
                session.links.restore(a.pageId, a.links)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.Moved -> {
                session.store.move(a.ids, -a.dx, -a.dy)
                session.headings.move(a.headingIds, -a.dx, -a.dy)
                session.links.move(a.linkIds, -a.dx, -a.dy)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingCreated -> {
                // Reverse the conversion whole: heading row down, the consumed ink back — revived
                // IN PLACE so its writing order survives (a later re-recognize reads a sequence).
                session.headings.erase(listOf(a.heading.id))
                session.store.revive(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingDeleted -> { session.headings.restore(a.headingIds); session.store.drain(); refreshToPage(a.pageId) }
            // The mirror image of Deleted: a paste's rows are ones it CREATED, so undoing it takes
            // them away — a link whole, wrapped children and all.
            is Action.ObjectsPasted -> {
                session.store.remove(a.strokeIds)
                session.headings.erase(a.headingIds)
                session.links.remove(a.links)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingTextEdited -> { session.headings.updateContent(a.before); session.store.drain(); refreshToPage(a.pageId) }
            is Action.HeadingLevelChanged -> { session.headings.updateContent(a.before); session.store.drain(); refreshToPage(a.pageId) }
            // Undo of a wrap IS an unlink; undo of an unlink is a re-wrap in place (K1).
            is Action.LinkCreated -> { session.links.unlink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkUnlinked -> { session.links.relink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkEdited -> { session.links.updatePayload(a.linkId, a.before); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Page -> {
                session.reconcile(a.snapshot.before, a.snapshot.objectIds, emptyList(), a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
            }
            // The mirror image of Page: a paste's objectIds are rows it CREATED, so undoing it
            // soft-deletes them along with the page they hang under.
            is Action.PagePasted -> {
                session.reconcile(a.snapshot.before, emptyList(), a.snapshot.objectIds, a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
            }
        }
    }

    private suspend fun reapply(a: Action) {
        when (a) {
            is Action.Drew -> { session.store.revive(listOf(a.stroke.id)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Erased -> { session.store.remove(a.strokes.map { it.id }); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Deleted -> {
                session.store.remove(a.strokes.map { it.id })
                session.headings.erase(a.headingIds)
                session.links.remove(a.links)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.Moved -> {
                session.store.move(a.ids, a.dx, a.dy)
                session.headings.move(a.headingIds, a.dx, a.dy)
                session.links.move(a.linkIds, a.dx, a.dy)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingCreated -> {
                session.headings.restore(listOf(a.heading.id))
                session.store.remove(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingDeleted -> { session.headings.erase(a.headingIds); session.store.drain(); refreshToPage(a.pageId) }
            is Action.ObjectsPasted -> {
                session.store.revive(a.strokeIds)
                session.headings.restore(a.headingIds)
                session.links.restore(a.pageId, a.links)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingTextEdited -> { session.headings.updateContent(a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.HeadingLevelChanged -> { session.headings.updateContent(a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkCreated -> { session.links.relink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkUnlinked -> { session.links.unlink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkEdited -> { session.links.updatePayload(a.linkId, a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Page -> {
                session.reconcile(a.snapshot.after, emptyList(), a.snapshot.objectIds, a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
            is Action.PagePasted -> {
                session.reconcile(a.snapshot.after, a.snapshot.objectIds, emptyList(), a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    /**
     * Delete the selection's strokes, headings **and links** in one tap = one undo entry. Order
     * matters: capture stroke geometry from [liveStrokes] *first* (it is the only place it still
     * exists once the engine drops them), update the content working copies *before*
     * `removeStrokes` (its re-record is the frame that drops them all), then the rows. A link is
     * captured whole — the [PageLink] carries the children whose rows go down with it, and is the
     * only thing that can put them back. `removeStrokes` dismisses the selection itself — every
     * data-in call does — so [PaperView.clearSelection] is only needed when no stroke was in the
     * selection; `onSelectionDismissed` clears our copy either way.
     */
    private fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val pageId = displayedPageId
        val ids = sel.strokeIds.toList()
        val headingIds = sel.contentIds.filter { liveHeadings.containsKey(it) }
        val links = sel.contentIds.mapNotNull { liveLinks[it] }
        if (ids.isEmpty() && headingIds.isEmpty() && links.isEmpty()) return
        val strokes = ids.mapNotNull { liveStrokes[it] }
        if (headingIds.isNotEmpty()) {
            session.headings.erase(headingIds)
            headingIds.forEach { liveHeadings.remove(it) }
            headingRenderer.headings = liveHeadings.values.toList()
        }
        if (links.isNotEmpty()) {
            session.links.remove(links)
            links.forEach { liveLinks.remove(it.id) }
            linkRenderer.update(liveLinks.values.toList())
        }
        if (ids.isNotEmpty()) {
            paper.removeStrokes(ids)
            session.store.erase(ids)
            ids.forEach { liveStrokes.remove(it) }
        } else {
            paper.clearSelection()
        }
        // Unconditional: removeStrokes only re-records when it actually dropped a stroke, so the
        // content removals must not ride on it. Both calls land in one Main block → one frame.
        if (headingIds.isNotEmpty() || links.isNotEmpty()) paper.notifyContentChanged()
        // Nothing captured means nothing to put back — record no history rather than a lying entry.
        if (strokes.isNotEmpty() || headingIds.isNotEmpty() || links.isNotEmpty()) {
            undo.record(Action.Deleted(pageId, strokes, headingIds, links))
        } else Log.w(TAG, "selection delete: no geometry for ${ids.size} ids — not undoable")
        if (headingIds.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
        Slog.d(TAG) {
            "selection delete: ${strokes.size} strokes, ${headingIds.size} headings, ${links.size} links"
        }
    }

    // ── Headings (N2) ────────────────────────────────────────────────────────

    /** Re-hand the working copy to the renderer and ask for one re-record. */
    private fun syncHeadingRenderer() {
        headingRenderer.headings = liveHeadings.values.toList()
        paper.notifyContentChanged()
    }

    /**
     * Boxes were measured with the WRITING device's text metrics and stored in page px — a font
     * scale change or a different-density device (the `.soil` is portable: Nomad ↔ Manta) makes the
     * stored size disagree with what this device draws, which would ellipsize every heading and
     * leave stale hit/selection bounds. Re-measure at load, in memory only: position is authored
     * (kept), size is derived (recomputed). Rows are corrected whenever the heading is next
     * written anyway.
     *
     * Loose page headings only — a **wrapped** heading never comes through here (it is a child of
     * its link, not of the page) and is deliberately left at its stored size: it is baked into the
     * link's composite, whose pixels have to match the bounds the link was wrapped at (K1).
     */
    private fun remeasureForDevice(headings: List<Heading>): List<Heading> {
        if (headings.isEmpty()) return headings
        val dm = resources.displayMetrics
        return headings.map { h ->
            val (w, hh) = HeadingRenderer.measure(h.text, dm.density, dm.scaledDensity)
            if (w == h.width && hh == h.height) h else h.copy(width = w, height = hh)
        }
    }

    /**
     * The links' sibling of [remeasureForDevice]: grow an under-sized underline band to what this
     * build reserves ([PageLink.withUnderlineBand]) so a link wrapped before the band widened does
     * not keep drawing its line against the ink. In memory only, only ever wider; the row is
     * corrected whenever the link is next written (a move persists the grown bounds). Runs before
     * `prebuild`, so the composite is rastered at the size the renderer will ask for.
     */
    private fun withUnderlineBand(links: List<PageLink>): List<PageLink> =
        if (links.isEmpty()) links
        else links.map { it.withUnderlineBand(resources.displayMetrics.density) }

    /**
     * The bar with the right mode: pure strokes → CONVERT's H + Link, one heading alone → CHANGE's H
     * with its level lit + Link, one link alone → Edit + Unlink, anything mixed → Delete plus Link
     * while no link is in it. A link anywhere in a mixed selection takes Link away — the no-nesting
     * rule (K1), read off the working copy rather than trusted from the engine's id set.
     */
    private fun showSelectionToolbar(sel: Selection) {
        val lone = sel.strokeIds.isEmpty() && sel.contentIds.size == 1
        val loneHeading = if (lone) liveHeadings[sel.contentIds.first()] else null
        val hasLink = sel.contentIds.any { liveLinks.containsKey(it) }
        val mode = when {
            loneHeading != null -> SelectionMode.HEADING
            lone && hasLink -> SelectionMode.LINK
            hasLink -> SelectionMode.MIXED_WITH_LINK
            sel.contentIds.isEmpty() && sel.strokeIds.isNotEmpty() -> SelectionMode.STROKES
            else -> SelectionMode.MIXED
        }
        selectionToolbar.show(sel.bounds, mode, loneHeading?.level)
    }

    /** The single selected link, or null — resolved at tap time, never captured into a callback
     *  (the selection can move, die or change kind between the bar going up and a button landing). */
    private fun loneSelectedLink(): PageLink? {
        val sel = currentSelection ?: return null
        if (sel.strokeIds.isNotEmpty() || sel.contentIds.size != 1) return null
        return liveLinks[sel.contentIds.first()]
    }

    /** An H1–H6 tap in the sub-row: CONVERT on a pure-stroke selection, CHANGE on a lone heading. */
    private fun onLevelPicked(level: Int) {
        if (!opened || closing) return
        val sel = currentSelection ?: return
        val loneHeading =
            if (sel.strokeIds.isEmpty() && sel.contentIds.size == 1) liveHeadings[sel.contentIds.first()] else null
        when {
            loneHeading != null -> changeHeadingLevel(loneHeading.id, level)
            sel.contentIds.isEmpty() && sel.strokeIds.isNotEmpty() -> startConvert(sel, level)
        }
    }

    /**
     * CONVERT: recognize the lassoed ink, then bake it into a heading. Everything the creation
     * needs is captured NOW — the recognition runs async and the selection may die (tap-away, a
     * flip) before it answers; the captured strokes are what the user pointed at. The stroke list
     * comes from [liveStrokes] filtered by the selection's id set, which preserves **writing
     * order** (a LinkedHashMap filled by load then by commit) — never iterate the Set itself.
     */
    private fun startConvert(sel: Selection, level: Int) {
        val pageId = displayedPageId
        val strokes = liveStrokes.values.filter { it.id in sel.strokeIds }
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        // The writing area is the SELECTION box, not the page — Paper's proven recipe (its H action
        // passes `bounds.width/height`, and its page pipeline recognizes per line with the line's
        // box). ML Kit reads the area as the scale of the writing; a page-sized area under a
        // one-line title made it guess fragments ("Heading" → "o" — eye-check #5 finding).
        HeadingConvert.run(
            this, strokes, bounds.width, bounds.height,
            onRecognized = { title ->
                createHeadingFromConversion(pageId, strokes.map { it.id }, bounds, level, title)
            },
        )
    }

    /**
     * The success half of CONVERT: one heading row up, the consumed ink soft-deleted — recorded as
     * **one undo step** ([Action.HeadingCreated]). The box anchors at the lassoed ink's top-left
     * and takes the measured size (free growth — never clamped to the page). On failure this is
     * simply never called: the ink stays untouched (the locked failure path).
     */
    private fun createHeadingFromConversion(
        pageId: String,
        strokeIds: List<String>,
        inkBounds: Bounds,
        level: Int,
        title: String,
    ) {
        if (!opened || closing) return
        val dm = resources.displayMetrics
        val text = HeadingPrefix.applyLevel(title, level)
        val (w, h) = HeadingRenderer.measure(text, dm.density, dm.scaledDensity)
        val heading = Heading(
            id = java.util.UUID.randomUUID().toString(), text = text, level = level,
            x = inkBounds.left, y = inkBounds.top, width = w, height = h, order = 0,
        )
        session.store.erase(strokeIds)
        session.headings.create(pageId, heading)
        undo.record(Action.HeadingCreated(pageId, heading, strokeIds))
        contentsFlow.refresh()   // before the flipped-away return — the rows changed either way
        Slog.d(TAG) { "converted ${strokeIds.size} strokes → heading level $level" }
        if (pageId != displayedPageId) return   // the user flipped away mid-recognize; rows are right
        strokeIds.forEach { liveStrokes.remove(it) }
        liveHeadings[heading.id] = heading
        headingRenderer.headings = liveHeadings.values.toList()
        // The successor selection rides the dismissal `removeStrokes` is about to perform — see
        // `onSelectionDismissed`. Injecting it there keeps a smart-lasso session alive across the
        // conversion, so the engine restores PEN when the *heading's* selection is dismissed, not
        // in the middle of it.
        pendingSelection = { selectAsHeading(heading) }
        paper.removeStrokes(strokeIds)
        // No dismissal fired (the selection had already died mid-recognize) — select directly.
        pendingSelection?.let { pendingSelection = null; it() }
        // removeStrokes only re-records when it dropped something; if the captured ids went stale
        // mid-recognize (scribble-erased under the overlay) the heading still has to paint. Both
        // calls land in one Main block → one frame.
        paper.notifyContentChanged()
    }

    /** CHANGE: re-prefix + re-measure, top-left kept — a heading grows from its anchor. */
    private fun changeHeadingLevel(id: String, level: Int) {
        val before = liveHeadings[id] ?: return
        if (before.level == level) return
        val dm = resources.displayMetrics
        val text = HeadingPrefix.applyLevel(before.text, level)
        val (w, h) = HeadingRenderer.measure(text, dm.density, dm.scaledDensity)
        val after = before.copy(text = text, level = level, width = w, height = h)
        session.headings.updateContent(after)
        liveHeadings[id] = after
        syncHeadingRenderer()
        undo.record(Action.HeadingLevelChanged(displayedPageId, before, after))
        selectAsHeading(after)
    }

    /**
     * Save from the edit dialog. [raw] is the hash-free field text, trimmed by the dialog: empty
     * means **delete** (the locked decision — the dialog never second-guesses it); anything else is
     * re-prefixed at the heading's current level and re-measured in place.
     */
    private fun applyHeadingEdit(id: String, raw: String) {
        if (!opened || closing) return
        val before = liveHeadings[id] ?: return
        val pageId = displayedPageId
        if (raw.isEmpty()) {
            session.headings.erase(listOf(id))
            liveHeadings.remove(id)
            paper.clearSelection()
            syncHeadingRenderer()
            undo.record(Action.HeadingDeleted(pageId, listOf(id)))
            contentsFlow.refresh()
            Slog.d(TAG) { "empty save deleted heading" }
            return
        }
        val text = HeadingPrefix.applyLevel(raw, before.level)
        if (text == before.text) return
        val dm = resources.displayMetrics
        val (w, h) = HeadingRenderer.measure(text, dm.density, dm.scaledDensity)
        val after = before.copy(text = text, width = w, height = h)
        session.headings.updateContent(after)
        liveHeadings[id] = after
        syncHeadingRenderer()
        undo.record(Action.HeadingTextEdited(pageId, before, after))
        selectAsHeading(after)
    }

    /**
     * Land the selection on [h] after a create/edit/level change — its box moved or resized, so the
     * old selection frame is stale. `setSelection` is host-initiated (no `onSelectionCreated` echo),
     * so the flags and the bar are set here by hand.
     */
    private fun selectAsHeading(h: Heading) {
        paper.setSelection(emptySet(), setOf(h.id), h.bounds)
        selectionActive = true
        currentSelection = Selection(emptySet(), setOf(h.id), h.bounds)
        selectionToolbar.show(h.bounds, SelectionMode.HEADING, h.level)
    }

    // ── Links (K1/K2) ────────────────────────────────────────────────────────

    /** Re-hand the working copy to the renderer and ask for one re-record — K2's payload edit is
     *  the one link mutation that touches nothing else (K1 changes share their frame or reload). */
    private fun syncLinkRenderer() {
        linkRenderer.update(liveLinks.values.toList())
        paper.notifyContentChanged()
    }

    /** Link on the selection toolbar: capture the selection NOW (it may not survive the picker
     *  round trip) and hand it to the flow. Eligibility was the bar's call; use-time re-checks
     *  live in [createLinkFromSelection]. */
    private fun beginLinkPick() {
        if (!opened || closing) return
        val sel = currentSelection ?: return
        linkPickFlow.beginCreate(sel)
    }

    /** Edit on a lone selected link: the flow captures the link and prefills the picker. */
    private fun beginLinkEdit() {
        if (!opened || closing) return
        val link = loneSelectedLink() ?: return
        linkPickFlow.beginEdit(link)
    }

    /**
     * The picker's New page, in **this** notebook (K3): under the page-op lock like every other
     * structural edit, refused once the screen is leaving. The paper never moves — the user is still
     * looking at the page they were writing on ([NotebookSession.insertAt]) — and no undo entry is
     * recorded: picker creations are not undoable (the og rule), and the stack is cleared wholesale
     * on the picker's return instead. Null is the picker's cue to explain.
     */
    private suspend fun pickerCreatePage(anchorId: String?, before: Boolean): PageRef? {
        if (!opened || closing) return null
        return pageOps.withLock {
            if (!opened || closing) null
            else runCatching { session.insertAt(anchorId, before) }
                .onFailure { Log.w(TAG, "picker page create failed", it) }
                .getOrNull()
        }
    }

    /**
     * The picker's Edit result: rewrite the payload — row, working copy, chrome — and record one
     * [Action.LinkEdited]. Bounds and children are untouched (the composite is reused; only the
     * live-drawn chrome can change), so this is the pure `syncLinkRenderer` frame. The caller
     * already dropped an unchanged payload. Re-selecting the link re-anchors the bar — a recorded
     * frame-silence exception (the post-edit re-anchor).
     */
    private fun applyLinkEdit(linkId: String, before: String, after: String) {
        if (!opened || closing) return
        val cur = liveLinks[linkId] ?: return   // page changed under a lost result — nothing to edit
        session.links.updatePayload(linkId, after)
        val updated = cur.copy(payload = after, chrome = LinkPayload.chromeOf(after))
        liveLinks[linkId] = updated
        syncLinkRenderer()
        undo.record(Action.LinkEdited(displayedPageId, linkId, before, after))
        selectAsLink(updated)
        Slog.d(TAG) { "link $linkId payload edited" }
    }

    /**
     * Wrap [sel] in a link to [payload]'s target — one link row up, its content **re-parented**
     * page → link. Nothing is copied and no id changes: the wrapped strokes and headings keep their
     * page-absolute geometry, which is why undo is simply an unlink and why the composite is
     * pixel-identical to what was there before.
     *
     * Everything is taken from the captured [sel] rather than the live selection: the caller may
     * have suspended (the debug path inserts a page first) and the selection can die in that window
     * — the same capture discipline the heading convert follows. The stroke list comes from
     * [liveStrokes] filtered by the id set, which preserves **writing order** — never iterate the
     * Set itself.
     */
    private fun createLinkFromSelection(sel: Selection, payload: String) {
        if (!opened || closing) return
        // No nesting (locked K1): the bar already hides Link on such a selection, but a captured
        // Selection is not the bar's — it is re-checked against the working copy at use time.
        if (sel.contentIds.any { liveLinks.containsKey(it) }) return
        val pageId = displayedPageId
        val strokes = liveStrokes.values.filter { it.id in sel.strokeIds }
        val headings = sel.contentIds.mapNotNull { liveHeadings[it] }
        val bounds = PageLink.unionBounds(
            strokes, headings, resources.displayMetrics.density,
        ) ?: return   // nothing of the captured selection is still on the page
        val link = PageLink(
            id = java.util.UUID.randomUUID().toString(),
            payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
            order = 0,   // the store lands it at MAX(order)+1 among the page's links
            strokes = strokes, headings = headings,
        )
        session.links.create(pageId, link)
        undo.record(Action.LinkCreated(pageId, link))
        contentsFlow.refresh()   // a wrapped heading leaves the outline with its new parent
        val strokeIds = strokes.map { it.id }
        strokeIds.forEach { liveStrokes.remove(it) }
        headings.forEach { liveHeadings.remove(it.id) }
        liveLinks[link.id] = link
        headingRenderer.headings = liveHeadings.values.toList()
        linkRenderer.update(liveLinks.values.toList())
        // The successor selection rides the dismissal `removeStrokes` is about to perform — see
        // `onSelectionDismissed`. Injecting it there keeps the smart-lasso session alive across the
        // wrap, so the engine restores PEN when the *link's* selection is dismissed, not mid-wrap.
        pendingSelection = { selectAsLink(link) }
        if (strokeIds.isNotEmpty()) paper.removeStrokes(strokeIds) else paper.clearSelection()
        // No dismissal fired — select directly.
        pendingSelection?.let { pendingSelection = null; it() }
        // Unconditional, for the conversion's reason: removeStrokes only re-records when it dropped
        // something, and a heading-only wrap still has to paint. One Main block → one frame.
        paper.notifyContentChanged()
        Slog.d(TAG) { "wrapped ${strokeIds.size} strokes + ${headings.size} headings → link" }
    }

    /** Land the selection on a freshly wrapped [l] — the link is what the user now has in hand.
     *  `setSelection` is host-initiated (no `onSelectionCreated` echo), so flags and bar are set here. */
    private fun selectAsLink(l: PageLink) {
        paper.setSelection(emptySet(), setOf(l.id), l.bounds)
        selectionActive = true
        currentSelection = Selection(emptySet(), setOf(l.id), l.bounds)
        selectionToolbar.show(l.bounds, SelectionMode.LINK, null)
    }

    /**
     * Unwrap the selected link: its content goes back to being page content, the row is
     * soft-deleted. The reload **is** the sync — the `.soil` is the source of truth (the SN replay
     * rule), and it also dismisses the selection, which is what lets the engine restore PEN.
     */
    private fun unlinkSelection() {
        val link = loneSelectedLink() ?: return
        val pageId = displayedPageId
        runPageOp {
            session.links.unlink(pageId, link)
            undo.record(Action.LinkUnlinked(pageId, link))
            session.store.drain()
            refreshToPage(pageId)
        }
    }

    // ── The object clipboard (arc 8) ─────────────────────────────────────────

    /**
     * Copy — or cut, which is a copy **and then** the ordinary [deleteSelection], so undo puts the
     * ink back exactly as the bar's own Delete would, in one entry.
     *
     * Three orderings carry the whole thing:
     *  - **Drain first.** A stroke commit still queued on the shared writer would land after the
     *    capture's row read and be silently missing from the payload (the arc-7 standing trap).
     *  - **Write, then delete.** A cut whose clipboard write failed must not delete: the user would
     *    be left with neither the ink nor a clipboard holding it.
     *  - **Re-arm the lasso.** Dismissing a selection ends the smart-lasso session and restores
     *    `Tool.PEN` (g-paper's documented behaviour), so without this the placement tap that
     *    follows a copy would *ink the page*. A host-initiated tool change ends the session cleanly
     *    and never echoes `onToolChanged`, which is why the button state is synced by hand.
     *
     * The selection is captured before the first suspension: it can die (a tap-away, a flip) while
     * the capture is in flight, and what the user pointed at is what they meant to copy — the same
     * discipline the heading convert and the link wrap follow.
     */
    private fun doObjectCopy(cut: Boolean) {
        if (!opened || closing) return
        val sel = currentSelection ?: return
        val pageId = displayedPageId
        val topIds = sel.strokeIds.toList() +
            sel.contentIds.filter { liveHeadings.containsKey(it) || liveLinks.containsKey(it) }
        if (topIds.isEmpty()) return
        runPageOp {
            session.store.drain()
            val env = runCatching { session.captureObjects(topIds) }
                .onFailure { Log.w(TAG, "selection capture failed", it) }
                .getOrNull()
            if (env == null) {
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_capture_failed)
                return@runPageOp
            }
            val write = runCatching { withContext(Dispatchers.IO) { clipStore.write(env) } }
                .onFailure { Log.w(TAG, "clipboard write failed", it) }
            val header = write.getOrNull()
            if (header == null) {
                // Over the payload cap, or the write threw. Nothing landed either way, so whatever
                // was on the clipboard still stands — and the message says which of the two it was.
                val message =
                    if (write.isSuccess) R.string.clip_objects_too_large else R.string.clip_objects_write_failed
                Dialogs.problem(this, R.string.clip_failed_title, message)
                return@runPageOp
            }
            SnClipboard.set(header)
            toolbar.showClipboardLoaded(true)
            if (cut) {
                if (displayedPageId != pageId) {
                    // The page moved under the capture (only reachable through a race): the copy
                    // stands, but deleting from a page the user is no longer looking at would be a
                    // silent edit somewhere else. Explain rather than guess.
                    Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_cut_moved)
                    return@runPageOp
                }
                deleteSelection(sel)
            } else {
                paper.clearSelection()
            }
            armLasso()
            toast(getString(if (cut) R.string.objects_cut_toast else R.string.objects_copied_toast))
        }
    }

    /**
     * Paste the clipboard's objects onto the visible page. ([tapX], [tapY]) is the pen tap that
     * asked for it, in paper coordinates — the set lands centred there; null is the popup's Paste,
     * which has no tap to aim at and lands at the **source** coordinates, so pasting into a
     * same-size page reproduces the original layout exactly. Both clamp onto the page.
     *
     * The pasted content lands **selected**, bar up, so the pen can drag it straight into place.
     * That is a chrome frame presented at a tap's pen-up — a deliberate act's visible result, the
     * selection toolbar's own frame-silence exception applied to the act that created the selection
     * (nothing is being written at a tap's pen-up).
     */
    private fun doObjectPaste(tapX: Float?, tapY: Float?) {
        if (!opened || closing) return
        val pageId = displayedPageId
        runPageOp {
            val env = withContext(Dispatchers.IO) { clipStore.readEnvelope() }
            if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS || env.rows.isEmpty()) {
                // Gone, foreign, or a kind this surface does not paste. Stop advertising a Paste
                // that can only fail — and retire the index row too, or `ensureLoaded` reads the
                // still-valid header back at the next open and fails again, forever (the B3 lesson).
                retireClipboard()
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@runPageOp
            }
            session.store.drain()
            val page = session.pages.firstOrNull { it.id == pageId } ?: return@runPageOp
            val written = runCatching {
                session.pasteObjects(env, pageId) { box ->
                    if (tapX != null && tapY != null) {
                        ObjectPlacement.centredOn(box, tapX, tapY, page.width.toFloat(), page.height.toFloat())
                    } else {
                        ObjectPlacement.atSource(box, page.width.toFloat(), page.height.toFloat())
                    }
                }
            }.onFailure { Log.w(TAG, "object paste failed", it) }
            val plan = written.getOrNull()
            if (plan == null || plan.isEmpty) {
                // A payload that decoded but carries nothing this build can place is retired, like
                // an unreadable one — it can only ever fail again. A write that *threw* is not:
                // that is this attempt failing (a full disk, an IO error), and throwing the user's
                // clipboard away over it would turn a retry into a loss.
                if (written.isSuccess) retireClipboard()
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@runPageOp
            }
            // The user may have flipped away while the write was in flight; the rows are correct
            // either way, and the next load will show them.
            if (pageId != displayedPageId) { contentsFlow.refresh(); return@runPageOp }

            // In-memory corrections a page load would make too: heading boxes re-measured for THIS
            // device, and any under-sized underline band grown. Rows are corrected when next written.
            val headings = remeasureForDevice(plan.headings)
            val links = withUnderlineBand(plan.links)
            // Composites off Main, before the frame that paints them (the hover-repaint trap).
            val linkBitmaps = linkRenderer.prebuild(links)

            headings.forEach { liveHeadings[it.id] = it }
            links.forEach { liveLinks[it.id] = it }
            plan.strokes.forEach { liveStrokes[it.id] = it }
            headingRenderer.headings = liveHeadings.values.toList()
            linkRenderer.update(liveLinks.values.toList(), linkBitmaps)
            if (plan.strokes.isNotEmpty()) paper.addStrokes(plan.strokes)
            // Unconditional: addStrokes only re-records when it actually added ink, and a
            // heading-or-link-only paste still has to paint. One Main block → one frame.
            paper.notifyContentChanged()
            undo.record(
                Action.ObjectsPasted(pageId, plan.strokes.map { it.id }, headings.map { it.id }, links)
            )
            if (headings.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()

            // Land it selected, bar up — the pen drags it into place from here.
            var box: Bounds? = null
            for (s in plan.strokes) box = box?.union(s.bounds) ?: s.bounds
            for (h in headings) box = box?.union(h.bounds) ?: h.bounds
            for (l in links) box = box?.union(l.bounds) ?: l.bounds
            box?.let { bounds ->
                val contentIds = (headings.map { it.id } + links.map { it.id }).toSet()
                val strokeIds = plan.strokes.mapTo(HashSet()) { it.id }
                val selection = Selection(strokeIds, contentIds, bounds)
                paper.setSelection(strokeIds, contentIds, bounds)
                selectionActive = true
                currentSelection = selection
                showSelectionToolbar(selection)
            }
            toast(getString(R.string.objects_pasted_toast))
            Slog.d(TAG) {
                "pasted ${plan.strokes.size} strokes, ${headings.size} headings, ${links.size} links"
            }
        }
    }

    /**
     * The popup's Clear: the clipboard goes, in memory **and** in the index. Clearing only the
     * mirror would hide it for this session and let the next notebook open read the row back — the
     * B3 lesson, which is why [ClipStore.clear] exists at all.
     */
    private fun doClipboardClear() {
        if (!opened || closing) return
        lifecycleScope.launch {
            retireClipboard()
            toast(getString(R.string.clipboard_cleared_toast))
        }
    }

    /** Retire the clipboard row and everything that advertises it. Never throws. */
    private suspend fun retireClipboard() {
        SnClipboard.set(null)
        toolbar.showClipboardLoaded(false)
        runCatching { withContext(Dispatchers.IO) { clipStore.clear(System.currentTimeMillis()) } }
            .onFailure { Log.w(TAG, "clipboard clear failed", it) }
    }

    /**
     * Arm the lasso from the host side, so the very next pen tap places rather than inks. A tool
     * assignment is never echoed as `onToolChanged` (it is not component-initiated), so the button
     * is synced by hand — and it ends any smart-lasso session cleanly, which is the whole point.
     */
    private fun armLasso() {
        paper.tool = Tool.LASSO
        toolbar.sync(Tool.LASSO)
    }

    /** Open the clipboard popup, or keep P1's silent no-op when there is nothing of ours to offer. */
    private fun showLassoPopup() {
        if (!opened || closing) return
        if (!SnClipboard.hasObjects) return
        if (lassoPopup.show()) pushExclusions()
    }

    private fun hideLassoPopup() {
        if (!::lassoPopup.isInitialized || !lassoPopup.isShowing) return
        lassoPopup.hide()
        pushExclusions()
    }

    // ── The page sheet: copy / cut / paste / delete ──────────────────────────

    /**
     * Long-press asks; it never acts. Copy and Cut confirm with a toast (something happened);
     * Paste opens a second sheet for the placement; Delete keeps its confirm dialog.
     *
     * **Paste is absent, never disabled**, when the clipboard holds no page — a greyed control is
     * invisible on e-ink (the standing rule), and a sheet whose row count *is* its content can
     * simply be one row shorter.
     */
    private fun showPageSheet() {
        if (!opened) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire, so
        // we are outside the pen-active window the R3 rule protects (a release inside it can cost
        // a live stroke).
        paper.releaseRender()
        val sheet = ActionSheetDialog(this)
            .addAction(R.drawable.ic_copy, getString(R.string.copy_page_action)) { runPageOp { doCopy(cut = false) } }
            .addAction(R.drawable.ic_cut, getString(R.string.cut_page_action)) { runPageOp { doCopy(cut = true) } }
        if (SnClipboard.hasPage) {
            sheet.addAction(R.drawable.ic_clipboard, getString(R.string.paste_page_action)) { showPasteSheet() }
        }
        sheet.addAction(R.drawable.ic_trash, getString(R.string.delete_page_action)) { confirmDeletePage() }
        sheet.show()
    }

    /** Where the pasted page goes. Opened from a row of the page sheet, so the pen is demonstrably
     *  idle — this rides the long-press sheet's frame-silence exception, it is not a new one. */
    private fun showPasteSheet() {
        if (!opened) return
        ActionSheetDialog(this)
            .addAction(R.drawable.ic_page_prev, getString(R.string.paste_before_action)) { runPageOp { doPaste(before = true) } }
            .addAction(R.drawable.ic_page_next, getString(R.string.paste_after_action)) { runPageOp { doPaste(before = false) } }
            .show()
    }

    /**
     * Copy — or cut, which is a copy followed by the ordinary delete, so undo puts the page *and*
     * its ink back exactly as a Delete page would.
     *
     * The drain is the arc's standing trap: a stroke commit still queued on the shared writer would
     * land after the capture's row read and be silently missing from the payload.
     *
     * The capture and the write are wrapped (B3 review): a throw on the way — a full disk, an index
     * IO error — would otherwise unwind into `runPageOp`'s `runCatching` and make Copy a tap that
     * did nothing, while a *stale* clipboard quietly stood ready to paste the wrong page. Anything
     * that explains why a tap didn't work is a problem dialog, not a log line.
     */
    private suspend fun doCopy(cut: Boolean) {
        session.store.drain()
        val env = runCatching { session.capturePage() }
            .onFailure { Log.w(TAG, "page capture failed", it) }
            .getOrNull()
        if (env == null) {
            Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_capture_failed)
            return
        }
        val write = runCatching { withContext(Dispatchers.IO) { clipStore.write(env) } }
            .onFailure { Log.w(TAG, "clipboard write failed", it) }
        val header = write.getOrNull()
        if (header == null) {
            // Over the payload cap, or the write threw. Either way nothing landed, so whatever was
            // on the clipboard still stands — and the message says which of the two it was.
            val message = if (write.isSuccess) R.string.clip_too_large else R.string.clip_write_failed
            Dialogs.problem(this, R.string.clip_failed_title, message)
            return
        }
        SnClipboard.set(header)
        // One slot, kind wins (arc 8): a page copy takes the objects' place, so the lasso's mark
        // has to stop promising a paste it no longer holds.
        toolbar.showClipboardLoaded(false)
        hideLassoPopup()
        if (cut) {
            val snap = session.deleteCurrent()
            undo.record(Action.Page(snap))
            navigateTo(session.currentIndex)
        }
        toast(getString(if (cut) R.string.page_cut_toast else R.string.page_copied_toast))
    }

    /** Paste the clipboard's page beside this one and land on it. */
    private suspend fun doPaste(before: Boolean) {
        val env = withContext(Dispatchers.IO) { clipStore.readEnvelope() }
        if (env == null || env.kind != ClipEnvelope.KIND_PAGE ||
            env.rows.none { it.type == SoilSchema.TYPE_PAGE }
        ) {
            // The row is gone, foreign, or claims a page it does not carry — stop advertising a
            // Paste that cannot work. Checked here rather than left to `pasteAt`, whose throw is a
            // caller-bug assertion and would be swallowed by `runPageOp` into a silent no-op.
            //
            // The index row goes too (B3 review): clearing only the in-memory mirror would hide the
            // dead Paste for this session and then let `ensureLoaded` read its still-valid header
            // back at the next notebook open, failing again — forever.
            SnClipboard.set(null)
            runCatching { withContext(Dispatchers.IO) { clipStore.clear(System.currentTimeMillis()) } }
                .onFailure { Log.w(TAG, "clipboard clear failed", it) }
            Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_failed)
            return
        }
        session.store.drain()
        // The anchor page's number as it will read once the paste has landed — the indicator the
        // user is looking at when the toast arrives (PageMath.anchorNumberAfterPaste).
        val anchor = PageMath.anchorNumberAfterPaste(session.currentIndex, before)
        val snap = session.pasteAt(env, before)
        undo.record(Action.PagePasted(snap))
        navigateTo(session.currentIndex)
        toast(getString(if (before) R.string.pasted_before_toast else R.string.pasted_after_toast, anchor))
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
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

    private fun goImmersive() = Immersive.apply(window, binding.root)

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
        if (::contentsFlow.isInitialized && contentsFlow.showing) {
            // The Contents dialog is up: the Ratta ink daemon draws firmware ink beneath any
            // Android window, so the whole paper is one exclusion rect until it dismisses.
            // (The small transient dialogs deliberately don't do this — a persistent full-height
            // panel is where a pen plausibly lands.)
            paper.setExclusionRects(listOf(BLOCK_ALL))
            return
        }
        val paperLoc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
        val rects = (
            listOfNotNull(rectOf(binding.topBar), rectOf(binding.bottomStrip)) +
                selectionToolbar.rects() + lassoPopup.rects()
            )
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
     *  result shows. Done here because the buttons consume the touch. Palm-gated.
     *
     *  It is also where the lasso popup's outside-tap dismissal lives (arc 8) — the one place that
     *  sees every contact, pen and finger alike, before anything else consumes it. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observer only — consumes nothing.
        if (opened && ::pageGestures.isInitialized) pageGestures.onTouchEvent(ev)
        if (::paper.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dismissLassoPopupOnContact(ev)
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Any contact that starts outside the popup — and outside the lasso button that toggles it —
     * takes it down: an outside tap, the start of a finger gesture, a pen going to write.
     *
     * [tapDismissedPopup] latches whether *this* contact is the one that did it, and is rewritten at
     * every ACTION_DOWN so it can never go stale. `onPaperTapped` reads it and declines to paste:
     * a contact spent on a dismissal is spent, the same rule g-paper applies to the tap that
     * dismisses a selection. The lasso button is excluded or its own re-tap would close the popup
     * here and immediately reopen it in [NotebookToolbar].
     */
    private fun dismissLassoPopupOnContact(ev: MotionEvent) {
        if (!::lassoPopup.isInitialized) { tapDismissedPopup = false; return }
        val x = ev.x.toInt(); val y = ev.y.toInt()
        val onToggle = rectOf(binding.btnLasso)?.contains(x, y) == true
        tapDismissedPopup = lassoPopup.isShowing && !onToggle && !lassoPopup.contains(x, y)
        if (tapDismissedPopup) hideLassoPopup()
    }

    /** Both bars, the selection toolbar and the lasso popup — a floating bar is chrome like any other. */
    private fun overChrome(ev: MotionEvent): Boolean {
        val top = rectOf(binding.topBar)
        val bottom = rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) ||
            (bottom?.contains(x, y) == true) ||
            (::selectionToolbar.isInitialized && selectionToolbar.contains(x, y)) ||
            (::lassoPopup.isInitialized && lassoPopup.contains(x, y))
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
     *
     * [andThen] is the follow-out's launch (K4): it runs **strictly after the seal** — one live
     * session per `.soil` family-wide, and the target may be this very notebook's neighbour — and
     * before [finish], so the stack stays Library → Notebook with no gap. The fast A→B→swipe-up
     * seal/reopen race is closed by this ordering (the arc's standing trap).
     */
    private fun close(andThen: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        undo.clear()   // in-memory history dies with the screen
        // A Dialog outliving its finishing Activity is a window leak — take the Contents down now.
        if (::contentsFlow.isInitialized) contentsFlow.dismissIfShowing()
        // The relay's source closes over the session about to be sealed — drop it with the screen.
        if (::linkPickFlow.isInitialized) linkPickFlow.close()
        BrowseState(this).lastOpenNotebookId = null
        if (!::session.isInitialized || !session.isOpen) { andThen?.invoke(); finish(); return }
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
            andThen?.invoke()
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        // A destroy that bypassed close() (config-change recreate, "don't keep activities") would
        // otherwise leak the Contents dialog's window — the exact hazard close() documents.
        if (::contentsFlow.isInitialized) contentsFlow.dismissIfShowing()
        if (::linkPickFlow.isInitialized) linkPickFlow.close()
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

        /** Host-internal (K4): set only by a follow / walk-back — the trail survives and both
         *  Backs walk it. Never crosses to any other component. */
        const val EXTRA_VIA_LINK = "viaLink"

        /** Host-internal (K4): the follow's target page, overriding the notebook's own `refId`
         *  for this open only. Applied once — see [initialPageId]. */
        const val EXTRA_INITIAL_PAGE_ID = "initialPageId"

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(
            context: Context,
            notebookId: String,
            notebookName: String,
            viaLink: Boolean = false,
            initialPageId: String? = null,
        ): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
                .putExtra(EXTRA_VIA_LINK, viaLink)
                .putExtra(EXTRA_INITIAL_PAGE_ID, initialPageId)
    }
}
