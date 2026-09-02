package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
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
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.SnClipboard
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseState
import com.symmetricalpalmtree.notesproutsn.data.prefs.LinkTrail
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.SnapPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix
import com.symmetricalpalmtree.notesproutsn.extension.CalendarClient
import com.symmetricalpalmtree.notesproutsn.extension.CalendarEntry
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.DocumentEditorEntry
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.extension.ScratchPadClient
import com.symmetricalpalmtree.notesproutsn.extension.ScratchPadEntry
import com.symmetricalpalmtree.notesproutsn.extension.TagManagerEntry
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import com.symmetricalpalmtree.notesproutsn.extension.TransferCaps
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePick
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePicks
import com.symmetricalpalmtree.notesproutsn.templates.TemplateRecents
import com.symmetricalpalmtree.notesproutsn.templates.TemplatesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    private lateinit var recentsFlow: RecentsFlow
    private lateinit var linkPickFlow: LinkPickFlow
    private lateinit var followFlow: LinkFollowFlow
    /** Snap-to-guide's durable preference (arc 9). `paper.snapToGuides` is the live copy. */
    private lateinit var snapPrefs: SnapPrefs
    /** The Scratch Pad's entry button (arc 11) — the host half of the EPD handoff lives in it. */
    private lateinit var scratchPad: ScratchPadEntry
    /** The Calendar's entry button (arc 23 / Y3) — the pad's shape, the same handoff inside it. */
    private lateinit var calendar: CalendarEntry
    /** The Document editor's entry button (arc 19 / M3) — the fifth extension point's door. */
    private lateinit var documentEntry: DocumentEditorEntry
    /** The `.soil` half of that door: the four hooks the editor's callback binder reaches back
     *  through, and (since M6) the host's memory of which page the editor is on. */
    private lateinit var documentHooks: DocumentHostHooks
    /** The open-time seed and the editor's silent recognitions (arc 19 / M6). */
    private lateinit var documentSeedFlow: DocumentSeedFlow
    /** The tag manager's entry (arc 21 / W2) — the sixth point's door, and the owner of `btnTags`. */
    private lateinit var tagEntry: TagManagerEntry
    /** The three tag doors that button opens (arc 21 / W2). */
    private lateinit var tagsPopup: TagsPopup
    private val repo by lazy { IndexRepository() }

    /** The global clipboard's one index row (arc 7) — the payload, read and written only here. */
    private val clipStore by lazy { ClipStore() }

    /**
     * The page-paper picker (arc 13 / G3) — the template library, opened full-screen from the page
     * sheet's **Page template** row and answering with a [TemplatePick].
     *
     * **No `releaseForHandoff`.** It is chrome, not a paper surface: nothing over there draws ink,
     * so the EPD pipeline stays here and the notebook's session, undo stack and unsaved page are
     * untouched while it is up. A cancel (or a result this build cannot read) changes nothing —
     * decoding to null is deliberately the same answer as backing out, so a picker that came back
     * garbled can never wipe the paper the page already had.
     */
    private val templatePickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pick = TemplatePick.decode(result.data?.getStringExtra(TemplatesActivity.EXTRA_PICK))
            ?: return@registerForActivityResult
        if (opened && !closing) runPageOp { doChangeTemplate(pick) }
    }

    private var notebookId: String = ""

    /**
     * The notebook's display name (arc 19 / M3) — the same string the bottom strip shows, kept as a
     * field because the document editor's header asks for it from a **Binder thread**, where
     * reading `binding.notebookName` would be a View touched off Main. `@Volatile` for the
     * publication, not for any update: nothing rewrites it until M8's rename-from-title.
     */
    @Volatile
    private var notebookName: String = ""

    private var opened = false
    private var closing = false

    /**
     * The document hooks' `alive` gate (arc 19 / M11): flipped immediately before this screen's
     * session seal is committed, on every seal path. Deliberately NOT `closing` — the editor's
     * teardown flush must still land while the screen is closing (flush-before-seal, the M4
     * invariant), and the reconnect wait must run while the session is still opening (`opened`
     * false). `@Volatile`: written on the seal coroutine, read on Binder threads.
     */
    @Volatile
    private var documentWritesClosed = false

    /**
     * M8 — the text-document latch: the canvas has been loaded in this incarnation, so this screen
     * is an ordinary notebook for the rest of its life ([TextDocRouting] holds the rules). One-way:
     * set by [loadCanvas], carried across a recreate in the saved state, and never cleared. It is
     * what keeps a later editor showing from sealing a notebook whose pages are on the glass.
     */
    private var canvasShown = false

    /** M8 — saved state said the editor was showing, so [DocumentEditorEntry.reconnect] has already
     *  run in `onCreate` and the open must not launch a second showing over the same one. */
    private var documentShowingRestored = false

    /** A showing that ended while [openSession] was still on IO — see [TextDocRouting.parkClose].
     *  A class rather than a bare `Int?` so that "no result yet" and "a result whose editor never
     *  said how it ended" stay two different answers. [endedOn] rides along because the replay's
     *  canvas load must land on the page the editor ended on, and `resetTarget()` has cleared the
     *  hooks' copy by the time the open re-decides (the M11 review find). */
    private class ParkedClose(val mode: Int?, val endedOn: String?)

    private var pendingCloseAfterOpen: ParkedClose? = null

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
     * The tool that was armed before ink came back from an extension selected it (arc 11 / J5, and
     * the calendar's transfer too since arc 23 / Y3 — **one** field for both, because only one
     * transfer can have just landed). Put back **pen-idle** when that selection is dismissed, and
     * only if the lasso is still armed — a tool the user picked meanwhile wins. Null the rest of
     * the time.
     */
    private var toolBeforeTransferPaste: Tool? = null

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
    private val undo = UndoRedoStack<Action>()

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
        notebookName = name
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
        // Snap-to-guide (arc 9): the margin guides are one toolbar thick, so content snapped to a
        // page margin lands exactly where the chrome ends. Armed from the remembered preference —
        // the toggle lives on the selection bar, but the setting outlives every selection. The
        // margin itself is set from the bar's real laid-out height in pushExclusions().
        snapPrefs = SnapPrefs(this)
        paper.snapMarginPx = resources.getDimensionPixelSize(R.dimen.toolbar_bar_thickness).toFloat()
        paper.snapToGuides = snapPrefs.enabled
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
            isSnapOn = { paper.snapToGuides },
            onToggleSnap = { toggleSnap() },
            onScratchPad = { sendSelectionToPad() },
            // Read at every show, not captured once: the extension can be disabled under us, and
            // `ScratchPadEntry` re-runs discovery on every resume and after a failed open.
            isScratchPadAvailable = { ::scratchPad.isInitialized && scratchPad.isAvailable },
            onCalendar = { sendSelectionToCalendar() },
            // Read at every show, for the pad's reason: `CalendarEntry` re-runs discovery on every
            // resume and after a failed open, and a button that lies is worse than one that is absent.
            isCalendarAvailable = { ::calendar.isInitialized && calendar.isAvailable },
            onTag = { tagSelection() },
            // Same rule as the pad's, and the same reason: `TagManagerEntry` re-runs discovery on
            // every resume, so what the bar reads is what was true at the last resume, not at
            // startup.
            isTagAvailable = { ::tagEntry.isInitialized && tagEntry.isAvailable },
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

        // Recents (arc 10): the ToC's mirror image — right-hand button, right-hand panel. No
        // availability gate; an empty list says so in the panel.
        recentsFlow = RecentsFlow(
            activity = this,
            paper = paper,
            repo = repo,
            notebookId = notebookId,
            alive = { opened && !closing },
            onShowingChanged = { pushExclusions() },
            switchTo = { id -> switchToNotebook(id) },
            button = binding.btnRecents,
        )

        // The Scratch Pad (arc 11). Must exist before RESUMED — it registers an ActivityResult
        // launcher. The one line that is the notebook's own: the EPD pipeline goes over immediately
        // before the launch, because the pad is a second paper surface in a second process. The
        // notebook is NOT sealed behind it — the pad opens no `.soil`, and this session, its undo
        // stack and its unsaved page are all still here when the result comes back.
        scratchPad = ScratchPadEntry(
            activity = this,
            button = binding.btnScratchPad,
            // The notebook is the one caller that can be sent to, so the pad shows its Send buttons.
            sendEnabled = true,
            beforeLaunch = { paper.releaseForHandoff() },
            onSent = { onPadSent() },
            onDrained = { drained -> pasteFromPad(drained) },
        )
        binding.btnScratchPad.setOnClickListener { if (opened && !closing) scratchPad.open() }
        TooltipCompat.setTooltipText(binding.btnScratchPad, binding.btnScratchPad.contentDescription)

        // The Calendar (arc 23 / Y3) — the seventh extension point, and the pad's twin in every way
        // that matters here: a second paper surface in a second process, built at this point in
        // onCreate because it registers an ActivityResult launcher, and handed the EPD pipeline the
        // instant before it launches. The notebook is not sealed behind it either — the calendar
        // opens no `.soil`.
        calendar = CalendarEntry(
            activity = this,
            button = binding.btnCalendar,
            // The notebook is the one caller that can be sent to, so the calendar shows its Send buttons.
            sendEnabled = true,
            beforeLaunch = { paper.releaseForHandoff() },
            onSent = { onCalendarSent() },
            onDrained = { drained -> pasteFromCalendar(drained) },
        )
        binding.btnCalendar.setOnClickListener { if (opened && !closing) calendar.open() }
        TooltipCompat.setTooltipText(binding.btnCalendar, binding.btnCalendar.contentDescription)

        // The Document editor (arc 19 / M3) — the fifth extension point. Like the pad it must exist
        // before RESUMED (it registers an ActivityResult launcher), and like the pad the notebook is
        // NOT sealed behind it: the editor opens no `.soil` at all — every read and write it makes
        // comes back here through the callback binder [DocumentHostHooks] serves.
        //
        // **No `releaseForHandoff`.** The editor is chrome, not a paper surface — nothing over there
        // draws ink, so the EPD pipeline stays here, exactly as it does for the arc-13 template
        // picker (see `templatePickLauncher`). But it is SN's first CROSS-PROCESS full-screen child
        // over a live notebook, and the two are not the same claim: the M3 on-device pen check is
        // what decides whether it holds. If the Ratta ink daemon draws beneath the editor, the fix
        // is the scratch pad's ordering (releaseForHandoff immediately before the launch, the
        // extension reclaiming in its own onResume) — never a repaint, and never a workaround here.
        //
        // The three pieces are wired to each other, so each reaches the next through a lambda
        // rather than a constructor argument: the seed flow stages onto the hooks and opens the
        // entry, the hooks call the seed flow for the editor's own recognitions, and the entry
        // holds the hooks. Every one of those reads a `lateinit` that is assigned by the time it
        // can fire (a tap, or a Binder call from a showing that does not exist yet).
        documentSeedFlow = DocumentSeedFlow(
            activity = this,
            session = { session },
            displayedPageId = { displayedPageId },
            alive = { opened && !closing && ::session.isInitialized },
            hooks = { documentHooks },
            openEditor = { documentEntry.open() },
        )
        documentHooks = DocumentHostHooks(
            notebook = { session },
            // displayedPageId, never session.currentIndex — the R6 torn-read rule: the document
            // belongs to the page whose strokes are on the paper. Since M6 this is the fallback
            // for the editor's own target, which a flip moves and the notebook does not follow.
            displayedPageId = { displayedPageId },
            notebookName = { notebookName },
            // NOT the seed flow's `opened && !closing` gate — see the field's doc: the reconnect
            // wait needs the still-opening window, the teardown flush needs the closing one.
            alive = { !documentWritesClosed },
            sessionOpen = { ::session.isInitialized && session.isOpen },
            recognizePageText = { pageId -> documentSeedFlow.recognize(pageId) },
            recognizeBatch = {
                documentSeedFlow.recognizerReady()?.let { client ->
                    { pageId: String -> documentSeedFlow.recognizeWith(client, pageId) }
                }
            },
            // M8: the flag both editor-only hooks are gated on, and the flag every state carries.
            isTextDocument = { isTextDocument() },
            rename = { name -> renameTextDocument(name) },
        )
        // Before the reconnect below, and before anything can ask for state: a host killed behind
        // the editor must come back pointing at the page — and, since M7, the scope — the editor
        // is still showing (og's STATE_DOCUMENT_NOTEBOOK, the mode-routing flag).
        documentHooks.restoreTarget(
            savedInstanceState?.getString(KEY_DOCUMENT_TARGET),
            savedInstanceState?.getBoolean(KEY_DOCUMENT_SCOPE) == true,
        )
        documentEntry = DocumentEditorEntry(
            activity = this,
            button = binding.btnDocument,
            hooks = documentHooks,
            // The showing is over — see [documentShowingEnded]. For an ordinary notebook that is the
            // catch-up (og's `navigateToPage(endedOn)`); since M8 a text document can also mean
            // "now show me the pages" or "seal and go".
            onClosed = { documentShowingEnded() },
        )
        // The tap goes to the seed flow, not straight to the entry: og's order is flush → stored
        // document? → recognize → stage → open, and the editor is opened by its last step.
        binding.btnDocument.setOnClickListener { if (opened && !closing) documentSeedFlow.start() }
        TooltipCompat.setTooltipText(binding.btnDocument, binding.btnDocument.contentDescription)

        // Tags (arc 21 / W2) — the sixth point's doors. Like the pad and the editor it must exist
        // before RESUMED (it registers an ActivityResult launcher), and like them there is no
        // `releaseForHandoff`: the tag screen carries no paper at all, so M3's measured answer —
        // stop-behind is enough for a non-drawing child screen, cross-process included — covers it.
        //
        // The button opens a bar, not a screen: a tag lands on the notebook or on the page, and
        // that is the one thing the button cannot decide for the user.
        tagEntry = TagManagerEntry(activity = this, button = binding.btnTags)
        tagsPopup = TagsPopup(
            root = binding.root,
            bar = binding.tagsPopup,
            anchor = binding.btnTags,
            bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onTagNotebook = { hideTagsPopup(); openTagsFor(TagShowing.TARGET_NOTEBOOK) },
            onTagPage = { hideTagsPopup(); openTagsFor(TagShowing.TARGET_PAGE) },
            onManage = { hideTagsPopup(); openTagManage() },
        )
        binding.btnTags.setOnClickListener {
            if (!opened || closing) return@setOnClickListener
            if (tagsPopup.isShowing) hideTagsPopup() else showTagsPopup()
        }
        TooltipCompat.setTooltipText(binding.btnTags, binding.btnTags.contentDescription)
        // We died with the editor still on screen (M4): the extension's process — and its unsaved
        // text — outlived us, holding a host binder that went with the old instance. Re-open the
        // client here, in onCreate, so the fresh `begin` reaches the editor as its flush signal.
        // It must happen now and not in onResume: a pending ActivityResult is delivered BEFORE
        // onResume, and the entry joins the reconnect from there rather than racing it.
        documentShowingRestored = savedInstanceState?.getBoolean(KEY_DOCUMENT_SHOWING) == true
        if (documentShowingRestored) documentEntry.reconnect()
        // M8: and whether the pages were already on the glass when we died — a text document that
        // has shown its canvas comes back an ordinary notebook (see [TextDocRouting]). Read here,
        // before openSession is launched at the end of this method, because it is the first thing
        // the route asks about.
        canvasShown = savedInstanceState?.getBoolean(KEY_CANVAS_SHOWN) == true

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
            // M8 — the route. A showing that ended while all of the above was on IO is re-decided
            // first and outranks everything (the S2 trap, [TextDocRouting.parkClose]); after that a
            // text document opens into its editor and leaves the paper alone.
            val parkedBox = pendingCloseAfterOpen
            pendingCloseAfterOpen = null
            val parked = parkedBox?.let {
                TextDocRouting.closeDecision(session.isTextDocument, canvasShown, it.mode)
            }
            when (
                val route = TextDocRouting.openDecision(
                    isTextDocument = session.isTextDocument,
                    canvasShown = canvasShown,
                    reconnectPending = documentShowingRestored,
                    parkedClose = parked,
                )
            ) {
                TextDocRouting.Open.CANVAS -> {
                    // A parked close replays onto the page the editor ended on — the catch-up (or
                    // the ✓-Done canvas) it would have been, had the open finished in time.
                    loadCanvas(parkedBox?.endedOn ?: session.currentPage.id)
                }
                TextDocRouting.Open.SEAL_AND_LEAVE -> {
                    // The editor left toward the library while we were still opening: seal what we
                    // opened and go, without ever putting a page on the paper.
                    Slog.d(TAG) { "text document: the showing ended before the open did — sealing" }
                    close()
                }
                TextDocRouting.Open.EDITOR_LAUNCH, TextDocRouting.Open.EDITOR_RECONNECT -> {
                    openIntoEditor(launch = route == TextDocRouting.Open.EDITOR_LAUNCH)
                }
            }
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
     * Put [pageId] on the paper: the loads, the renderers, `loadStrokes`, and the chrome that
     * describes them. The open's second half, extracted at M8 because a **text document defers it** —
     * the canvas is loaded when ✓ Done asks for the pages, on the page the editor ended on, and on a
     * close it is never loaded at all. Everything above this line is cheap; everything in it is not.
     *
     * Safe to run twice (the deferred path re-affirms `opened` and re-pushes the exclusions), and it
     * is the one place [canvasShown] is set — the latch that makes a text document ordinary.
     */
    private suspend fun loadCanvas(pageId: String) {
        // The editor may have ended on another page; the session is still on the one it opened at.
        val idx = session.pages.indexOfFirst { it.id == pageId }
        val page = if (idx >= 0 && idx != session.currentIndex) session.goTo(idx) else session.currentPage
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
        canvasShown = true
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
    }

    /**
     * The text-document open (M8): the **lightweight** setup and the editor, with no stroke
     * deserialization anywhere in it. All it establishes is the page the hooks fall back to, the
     * scope they answer in, and the `opened` flag their `alive` gate reads — the editor's `begin`
     * asks for state before the launch, so all three have to be true by then.
     *
     * The scope is set to the notebook document **only on a fresh open**: a restored target (or a
     * live showing being reconnected to) is the editor's own memory of where it is, and overwriting
     * it would answer a reconnecting editor's `current()` about the wrong document.
     *
     * [launch] false is the reconnect: the editor is already on screen and
     * [DocumentEditorEntry.reconnect] has re-minted its binder, so launching would bind twice over
     * one showing. The "Opening…" box stays up behind it — nothing else will take it down, and this
     * screen is not what the user is looking at.
     *
     * **No seed flow.** Notebook scope serves the STORED document and nothing else (the M7 lock), so
     * an empty text document opens instantly instead of paying a recognition it would throw away.
     */
    private suspend fun openIntoEditor(launch: Boolean) {
        displayedPageId = session.currentPage.id
        if (launch && !documentShowingRestored && documentHooks.targetPageId == null) {
            documentHooks.restoreTarget(session.currentPage.id, notebookScope = true)
        }
        opened = true
        pushExclusions()
        if (!launch) {
            Slog.d(TAG) { "text document: reconnected to the showing already on screen" }
            // The editor is *believed* to be on screen — but the belief is saved state, and the
            // system may have dropped the editor task-mate along with us. If this screen is still
            // the thing on the glass at the deadline, the belief was wrong (M11 review find).
            watchForAnEditorThatNeverOpens(reconnect = true)
            return
        }
        if (!documentEntry.isAvailable) {
            // Nothing to route into — no editor installed, or discovery has not answered yet. The
            // pages are the honest fallback: a text document is an ordinary notebook underneath,
            // and a screen of chrome over an unloaded surface is not a screen.
            Slog.d(TAG) { "text document: no editor extension — showing the pages" }
            loadCanvas(session.currentPage.id)
            return
        }
        // Hand the box over rather than stack a second one: the entry raises its own
        // [OpeningOverlay] and runs the launch strictly after that frame is on the glass (its
        // pre-draw + post — the trap that overlay exists for). Both are the same layout and both
        // moves happen in this one Main message, so the swap costs no frame and shows no gap.
        binding.openingOverlay.root.visibility = View.GONE
        documentEntry.open()
        watchForAnEditorThatNeverOpens()
    }

    /**
     * The one thing the text route may not leave to chance: an editor that never appears. A bind or
     * a `begin` can fail (a package replaced under us, a document over the cap) and the entry
     * answers that with its own problem dialog — which would leave this screen sitting on chrome
     * over a paper surface that was never loaded. So: one bounded look, and if no showing is up and
     * we are still the thing on the glass, the pages come up instead.
     *
     * A launch that DID happen leaves this screen stopped, and a showing that has already ended has
     * either loaded the canvas or started closing — three checks that each cost nothing.
     */
    private fun watchForAnEditorThatNeverOpens(reconnect: Boolean = false) {
        lifecycleScope.launch {
            delay(EDITOR_LAUNCH_WATCHDOG_MS)
            if (closing || isFinishing || isDestroyed || canvasShown) return@launch
            // On the reconnect route [DocumentEntry.isShowing] is true by construction (the binder
            // was re-minted), so it proves nothing there — RESUMED is the check that can: a live
            // editor on top means this screen is STOPPED, and a screen still RESUMED at the
            // deadline is a screen with no editor over it, whatever the saved state believed.
            if (!reconnect && documentEntry.isShowing) return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            Slog.d(TAG) { "text document: the editor never opened — showing the pages" }
            runPageOp { loadCanvas(displayedPageId) }
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

    /** Seal a session the screen will never use — on [appScope], because our own scope is dying.
     *  The document gate flips first: nothing may write into a seal already decided. */
    private fun sealAbandonedOpen() {
        documentWritesClosed = true
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

    // ── The document editor's showing ────────────────────────────────────────

    /** Whether the open notebook is a text document (M8) — false until the session has read the
     *  index bit, which is also the honest answer for every question asked before then. */
    private fun isTextDocument(): Boolean = ::session.isInitialized && session.isTextDocument

    /**
     * The showing ended (M6, grown at M8). Runs on Main from the entry's result callback, which is
     * **before** `onResume` and can therefore be before the open has even finished.
     *
     * The advisory is taken **first**, because `resetTarget()` clears it along with everything else
     * the showing owned. What it means is [TextDocRouting.closeDecision]'s table: an ordinary
     * notebook (and a text document whose pages are already up) catches up to the page the editor
     * ended on; a text document that has never shown its canvas either loads it now (✓ Done) or
     * seals to the library (the leave door, and every silence).
     */
    private fun documentShowingEnded() {
        val mode = documentHooks.takeCloseMode()
        val endedOn = documentHooks.targetPageId
        documentHooks.resetTarget()
        if (TextDocRouting.parkClose(opened)) {
            // Nothing to act on yet — see [TextDocRouting.parkClose]. openSession re-decides it.
            pendingCloseAfterOpen = ParkedClose(mode, endedOn)
            return
        }
        when (TextDocRouting.closeDecision(isTextDocument(), canvasShown, mode)) {
            TextDocRouting.Close.CATCH_UP ->
                if (endedOn != null && endedOn != displayedPageId) runPageOp { refreshToPage(endedOn) }
            TextDocRouting.Close.LOAD_CANVAS -> {
                // The box goes back up for a load the user asked for and cannot see the cost of:
                // the page comes off the `.soil` and the surface is set up from nothing. No
                // [OpeningOverlay.showThen] wait is owed here — nothing pauses this screen, so the
                // traversal that paints the box runs while the load is out on IO.
                binding.openingOverlay.root.visibility = View.VISIBLE
                runPageOp { loadCanvas(endedOn ?: session.currentPage.id) }
            }
            TextDocRouting.Close.SEAL_TO_LIBRARY -> close()
        }
    }

    /**
     * The editor's tap-the-title rename (M8), text documents only — [DocumentHostHooks] has already
     * gated on that. Runs on a **Binder thread**, where blocking is the contract (see that class's
     * threading note), and every refusal is an `IllegalArgumentException` whose message the editor
     * shows verbatim: the words are the library's own, so a name refused here reads exactly as one
     * refused in the rename dialog.
     *
     * The name is user content — never logged, length only.
     */
    private fun renameTextDocument(requested: String) {
        val name = requested.trim()
        NameRules.validate(name)?.let {
            throw IllegalArgumentException(NameDialog.problemMessage(this, it))
        }
        val versionCode = runCatching {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        }.getOrNull()
        runBlocking {
            withContext(Dispatchers.IO) {
                // Blob-free: the row's cover has no business being read to answer a rename.
                val row = repo.summary(notebookId) ?: throw IllegalStateException("notebook closed")
                if (name == row.name) return@withContext   // the library's rule: not a collision
                // Excluding the row itself: re-casing its own name is a rename, not a collision.
                if (repo.nameTaken(row.parentId, ObjectType.NOTEBOOK, name, notebookId)) {
                    throw IllegalArgumentException(getString(R.string.rename_duplicate_notebook, name))
                }
                repo.rename(notebookId, name)
                notebookName = name   // what the editor's own header asks for, from a Binder thread
                // Posted, never awaited: this thread is inside a Binder transaction and must not
                // wait on Main. No pen-idle gate either — the editor is on top of this screen, so
                // there is no frame to present and nothing to interrupt.
                runOnUiThread { if (!isFinishing && !isDestroyed) binding.notebookName.text = name }
                // og refreshes the meta at a rename — the `.soil` stays self-describing.
                if (versionCode != null && ::session.isInitialized && session.isOpen) {
                    runCatching { session.refreshMeta(versionCode) }
                        .onFailure { Log.w(TAG, "refreshMeta after rename failed", it) }
                }
            }
        }
        Slog.d(TAG) { "renamed: ${name.length} chars" }
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
         * vanishes by itself. One batched call per gesture. A link erases **whole**, wrapped
         * content and all (the locked K1 model) — the eraser can never reach inside one.
         *
         * A **scribble** reports through [onScribbleErased] instead, not here.
         */
        override fun onContentErased(contentIds: List<String>) {
            if (!opened) return
            val pageId = displayedPageId
            val (headingIds, links) = removeContent(contentIds) ?: return
            paper.notifyContentChanged()
            // One sweep is one entry. A link's restore needs its full snapshot (row + wrapped
            // children), so anything with a link in it is recorded as a Deleted covering both
            // kinds rather than two entries the user would have to undo twice.
            if (links.isNotEmpty()) undo.record(Action.Deleted(pageId, emptyList(), headingIds, links))
            else undo.record(Action.HeadingDeleted(pageId, headingIds))
            Slog.d(TAG) { "eraser removed ${headingIds.size} headings, ${links.size} links" }
        }
        /**
         * A scribble crossed something out (arc 14 / g-paper 0.1.23). One gesture, one callback,
         * **one undo entry** — which is the whole reason the engine reports strokes and content
         * together here rather than through [onStrokesErased] + [onContentErased]: a scribble
         * that took a line of ink and the heading above it must not cost the user two undos.
         *
         * Per kind the semantics are the eraser tool's — whole strokes, whole headings, whole
         * links (wrapped children and all). What differs is only *reach*: the engine decides
         * content by penetration (14 dp of scribble path inside the bounds), so ink scribbled
         * out beside a heading leaves the heading standing. Links used to be scribble-immune
         * outright; the user reversed that on 2026-08-26 (arc-14 wizard).
         *
         * Either list may be empty, never both.
         */
        override fun onScribbleErased(strokeIds: List<String>, contentIds: List<String>) {
            if (!opened) return
            val pageId = displayedPageId
            // The mirror is the only place the geometry still exists once the engine drops it.
            val strokes = strokeIds.mapNotNull { liveStrokes.remove(it) }
            if (strokeIds.isNotEmpty()) session.store.erase(strokeIds)
            val (headingIds, links) = removeContent(contentIds) ?: (emptyList<String>() to emptyList())
            if (strokes.isEmpty() && headingIds.isEmpty() && links.isEmpty()) return
            undo.record(Action.ScribbleErased(pageId, strokes, headingIds, links))
            Slog.d(TAG) {
                "scribble removed ${strokes.size} strokes, ${headingIds.size} headings, " +
                    "${links.size} links"
            }
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
            restoreToolAfterTransferPaste()
        }
        override fun onToolChanged(tool: Tool) { toolbar.sync(tool) }
    }

    /**
     * The content half of an erase, shared by the eraser tool ([PaperListener.onContentErased])
     * and a scribble ([PaperListener.onScribbleErased]): take the ids that are ours off the page
     * — rows, working copies, renderers — and hand back what actually went so the caller can
     * record the undo entry its own act deserves. Null when none of [contentIds] was ours.
     *
     * The component owns no content, so nothing disappears until this runs and something
     * re-records; until then the objects stay on the committed layer. **The repaint is the
     * caller's**, not this helper's: the eraser tool has to ask for one
     * (`paper.notifyContentChanged()`), while a scribble must not — g-paper re-records on its
     * own the moment `onScribbleErased` returns, and a second repaint is a second EPD refresh
     * whose first half would show the ink gone and the heading still standing.
     */
    private fun removeContent(contentIds: List<String>): Pair<List<String>, List<PageLink>>? {
        val headingIds = contentIds.filter { liveHeadings.containsKey(it) }
        val links = contentIds.mapNotNull { liveLinks[it] }
        if (headingIds.isEmpty() && links.isEmpty()) return null
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
        // Wrapped headings are out of the outline while wrapped (their parent is the link, not
        // the page), so erasing a link that holds one changes the Contents just as a loose one does.
        if (headingIds.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
        return headingIds to links
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
        override fun onTwoFingerSwipeDown() { recentsFlow.open() }       // arc 10 — the Recents
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

    /**
     * The Recents hop (arc 10). The panel is a snapshot, so the tapped notebook is re-checked against
     * the index first — a delete elsewhere is possible, and the honest answer is a problem dialog,
     * not an open that fails. Then the launch runs the link-follow's order exactly: raise the
     * "Opening…" box, and only once its frame is on the glass seal **this** notebook and start the
     * other — one live session per `.soil`, family-wide.
     *
     * Deliberately **not** a follow: nothing is pushed onto the link trail, and the target opens
     * without `viaLink`, so its Back exits to the library. It is a fresh open like the library's,
     * which means `onCreate` **clears** the trail on arrival — and that is the point rather than a
     * side effect: a trail left standing across a switch would let a link followed later in the
     * *new* notebook walk back into the one you switched away from. A switch starts a new story.
     */
    private fun switchToNotebook(targetId: String) {
        if (!opened || closing || targetId == notebookId) return
        lifecycleScope.launch {
            // Kept apart deliberately: "the row is gone" and "the read failed" are different
            // answers, and telling someone their notebook was deleted when the index merely
            // hiccupped is a lie they cannot check.
            val read = runCatching { repo.aliveNotebooks(listOf(targetId))[targetId] }
            if (isFinishing || isDestroyed || closing) return@launch
            read.onFailure { e ->
                Log.w(TAG, "recents switch: index read failed", e)
                Dialogs.problem(this@NotebookActivity, R.string.recents_gone_title, R.string.recents_read_failed_body)
                return@launch
            }
            val summary = read.getOrNull()
            if (summary == null) {
                Dialogs.problem(this@NotebookActivity, R.string.recents_gone_title, R.string.recents_gone_body)
                return@launch
            }
            OpeningOverlay.showThen(this@NotebookActivity) {
                close { startActivity(intent(this@NotebookActivity, targetId, summary.name)) }
            }
        }
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
        hideTagsPopup()           // and its Tag page door would now aim at a page nobody chose
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
            // Same replay as Deleted — a different act to the user, the same rows to put back.
            is Action.ScribbleErased -> {
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
            // No drain: a re-papering writes one page row and never touches the stroke writer.
            is Action.TemplateChanged -> { session.applyTemplate(a.pageId, a.from); refreshToPage(a.pageId) }
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
            is Action.ScribbleErased -> {
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
            is Action.TemplateChanged -> { session.applyTemplate(a.pageId, a.to); refreshToPage(a.pageId) }
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

    /**
     * Flip snap-to-guide (arc 9). The engine holds the live flag and `SnapPrefs` the durable one;
     * both are written here so they can never disagree, and the bar re-reads the engine.
     *
     * Nothing else happens: no toast (the border is the confirmation, and a toast for a setting the
     * user can see would be noise), and the current selection stays exactly where it is — snapping
     * governs the *next* drag, it never moves anything by itself.
     */
    private fun toggleSnap() {
        val next = !paper.snapToGuides
        paper.snapToGuides = next
        snapPrefs.enabled = next
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
        // A wrapped heading now *stays* in the outline (the gather hops link → page), so this
        // cannot flip availability any more — kept because parentage moved and the gate is cheap.
        contentsFlow.refresh()
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

    // ── Scratch Pad transfers (arc 11 / J5) ──────────────────────────────────

    /**
     * The selection toolbar's **Pad**: ask where the ink should land on the pad, then hand it over
     * and open the pad on it.
     *
     * **A copy, not a move** — the notebook keeps its ink and records nothing on its undo stack.
     * There is nothing to undo: nothing on this page changed.
     *
     * The order carries three rules:
     *  - **Ink only.** The bar's button is already gone on anything else, but the selection can
     *    change kind between the show and the tap, and `WireStroke` is the whole of what the
     *    contract carries — a heading or a link in the set has no honest wire form.
     *  - **The strokes come from [liveStrokes] filtered by the id set**, which preserves **writing
     *    order** (a LinkedHashMap filled by load then by commit) — never iterate the Set itself.
     *  - **The caps are checked before any bind.** A refusal must cost nothing: no store open, no
     *    bind, no screen.
     *
     * The placement sheet rises from a selection-toolbar tap — the O1 pattern, the same act as the
     * lasso popup's own sheet — so it needs no new frame-silence exception.
     */
    private fun sendSelectionToPad() {
        if (!opened || closing || !::scratchPad.isInitialized) return
        val sel = currentSelection ?: return
        if (sel.contentIds.isNotEmpty() || sel.strokeIds.isEmpty()) return
        val ids = sel.strokeIds
        val strokes = liveStrokes.values.filter { it.id in ids }
        if (strokes.isEmpty()) return
        if (!TransferCaps.withinLimits(strokes.size, TransferCaps.pointCount(strokes))) {
            Dialogs.problem(this, R.string.scratch_too_large_title, R.string.scratch_too_large_body)
            return
        }
        val page = session.currentPage
        ActionSheetDialog(this)
            .title(getString(R.string.scratch_placement_title))
            .addAction(R.drawable.ic_plus, getString(R.string.scratch_placement_new_page)) {
                openPadWith(strokes, page, ExtensionContract.PLACEMENT_NEW_PAGE)
            }
            .addAction(R.drawable.ic_pencil_down, getString(R.string.scratch_placement_current_page)) {
                openPadWith(strokes, page, ExtensionContract.PLACEMENT_CURRENT_PAGE)
            }
            .show()
    }

    /** Hand the ink to the entry, which opens the store, holds the bind, sends and launches — and
     *  which tells us [onPadSent] only once the ink is actually across. */
    private fun openPadWith(strokes: List<Stroke>, page: PageRef, placement: Int) {
        if (!opened || closing) return
        scratchPad.open(
            ScratchPadEntry.Send(strokes, page.width.toFloat(), page.height.toFloat(), placement)
        )
    }

    /** The ink is on the pad. The selection it came from goes (it has been acted on) and the toast
     *  confirms something that has already happened — the standing toast rule, kept honest by
     *  firing here rather than at the tap, where the send could still have failed. */
    private fun onPadSent() {
        if (isFinishing || isDestroyed) return
        paper.clearSelection()
        toast(getString(R.string.scratch_sent_toast))
    }

    // ── Calendar transfers (arc 23 / Y3) ─────────────────────────────────────

    /**
     * The selection toolbar's **Calendar**: ask which calendar page the ink should land on, then
     * hand it over and open the calendar on it.
     *
     * [sendSelectionToPad]'s order, rule for rule — **a copy, not a move** (nothing on this page
     * changed, so nothing goes on the undo stack), **ink only** (the selection can change kind
     * between the show and the tap, and `WireStroke` is the whole of what the contract carries),
     * the strokes taken from [liveStrokes] filtered by the id set so **writing order** survives,
     * and **the caps checked before any bind** — a refusal must cost nothing.
     *
     * The four choices come from [CalendarTargets], which routes every one through
     * `CalendarTarget.of`: the host knows today and nothing else about periods. The rows carry no
     * icons — four identical calendar glyphs would say nothing (`LinkPickerActivity`'s new-page
     * sheet is the precedent).
     *
     * The sheet rises from a selection-toolbar tap — the O1 pattern, the same act as the lasso
     * popup's own sheet — so it needs no new frame-silence exception.
     */
    private fun sendSelectionToCalendar() {
        if (!opened || closing || !::calendar.isInitialized) return
        val sel = currentSelection ?: return
        if (sel.contentIds.isNotEmpty() || sel.strokeIds.isEmpty()) return
        val ids = sel.strokeIds
        val strokes = liveStrokes.values.filter { it.id in ids }
        if (strokes.isEmpty()) return
        if (!TransferCaps.withinLimits(strokes.size, TransferCaps.pointCount(strokes))) {
            Dialogs.problem(this, R.string.calendar_too_large_title, R.string.calendar_too_large_body)
            return
        }
        val page = session.currentPage
        val sheet = ActionSheetDialog(this).title(getString(R.string.calendar_target_title))
        for (row in CalendarTargets.rows(LocalDate.now())) {
            val label = when (row.choice) {
                CalendarTargets.Choice.TODAY_AM -> R.string.calendar_target_today_am
                CalendarTargets.Choice.TODAY_PM -> R.string.calendar_target_today_pm
                CalendarTargets.Choice.THIS_WEEK -> R.string.calendar_target_week
                CalendarTargets.Choice.THIS_MONTH -> R.string.calendar_target_month
            }
            sheet.addAction(null, getString(label)) { openCalendarWith(strokes, page, row.target) }
        }
        sheet.show()
    }

    /** Hand the ink to the entry, which opens the store, holds the bind, sends and launches — and
     *  which tells us [onCalendarSent] only once the ink is actually across. */
    private fun openCalendarWith(strokes: List<Stroke>, page: PageRef, target: CalendarTarget) {
        if (!opened || closing) return
        calendar.open(
            CalendarEntry.Send(strokes, page.width.toFloat(), page.height.toFloat(), target)
        )
    }

    /** The ink is on the calendar. The selection it came from goes (it has been acted on) and the
     *  toast confirms something that has already happened — the standing toast rule, kept honest by
     *  firing here rather than at the tap, where the send could still have failed. */
    private fun onCalendarSent() {
        if (isFinishing || isDestroyed) return
        paper.clearSelection()
        toast(getString(R.string.calendar_sent_toast))
    }

    // ── The transfer paste, shared by both (arc 23 / Y3) ─────────────────────

    /**
     * Ink coming back from the pad ([ScratchPadEntry.onDrained]) — the transfer paste, in the pad's
     * words.
     */
    private fun pasteFromPad(drained: ScratchPadClient.Drained) =
        pasteTransferred(drained.strokes, drained.truncated, PAD_WORDING, "the scratch pad")

    /**
     * Ink coming back from the calendar ([CalendarEntry.onDrained]) — the same paste, the calendar's
     * words. Deliberately **not** a sibling copy of the pad's: the two transfers differ in nothing
     * but the three strings they say, and a copy is how the `RattaNotebookView` trap is recreated one
     * file at a time.
     */
    private fun pasteFromCalendar(drained: CalendarClient.Drained) =
        pasteTransferred(drained.strokes, drained.truncated, CALENDAR_WORDING, "the calendar")

    /**
     * Ink coming back from an extension's screen — the strokes are already sanitized and capped by
     * [TransferCaps.Drain], and their **ids are minted here**: nothing from the wire is trusted
     * beyond its geometry. [wording] is the whole of what the two callers differ by; [source] names
     * the sender in the log line and nowhere else.
     *
     * It lands on the page that is displayed **when the write runs**, appended after that page's
     * current max `"order"` with relative order preserved (writing order is load-bearing — the
     * arc-8 rebase rule), as **one** undoable step. Coordinates are kept **1:1**: the sender's page
     * and the notebook page are both this device's screen, and a cross-size page clips the ink like
     * any other.
     *
     * It lands **selected with the lasso armed**, so the pen can drag it into place at once — a
     * selection under the pen can neither be dragged nor dismissed, so the tool is switched
     * **before** `setSelection`, and the tool the user had comes back pen-idle when that selection
     * is dismissed. That frame is the selection toolbar's own recorded exception, at a boundary
     * (nothing is being written — the user has just come back from another screen).
     */
    private fun pasteTransferred(
        wire: List<WireStroke>,
        truncated: Boolean,
        wording: TransferWording,
        source: String,
    ) {
        if (!opened || closing) return
        runPageOp {
            val pageId = displayedPageId
            session.store.drain()
            val strokes = TransferCaps.toStrokes(wire)
            if (strokes.isEmpty()) return@runPageOp
            val written = runCatching { session.pasteStrokes(pageId, strokes) }
                .onFailure { Log.w(TAG, "paste from $source failed", it) }
            if (written.isFailure) {
                Dialogs.problem(this, R.string.clip_failed_title, wording.pasteFailedBodyRes)
                return@runPageOp
            }
            // The user may have flipped away while the write was in flight; the rows are correct
            // either way, and the next load will show them.
            if (pageId != displayedPageId) return@runPageOp

            strokes.forEach { liveStrokes[it.id] = it }
            paper.addStrokes(strokes)
            paper.notifyContentChanged()
            // A transfer paste IS a strokes-only object paste: same rows created, same direction,
            // same replay — so it takes arc-8's entry rather than a fifteenth kind (J5 Q1).
            undo.record(Action.ObjectsPasted(pageId, strokes.map { it.id }, emptyList(), emptyList()))

            var box = strokes.first().bounds
            for (i in 1 until strokes.size) box = box.union(strokes[i].bounds)
            // The write lands AFTER the tool change, never before it (the O2 lesson): arming the
            // lasso dismisses whatever selection was still up, and that dismissal runs
            // `restoreToolAfterTransferPaste` — which would consume this very field and put the pen
            // back under the selection we are about to make.
            val priorTool = paper.tool
            if (priorTool != Tool.LASSO) {
                armLasso()
                toolBeforeTransferPaste = priorTool
            }
            val strokeIds = strokes.mapTo(HashSet()) { it.id }
            val selection = Selection(strokeIds, emptySet(), box)
            paper.setSelection(strokeIds, emptySet(), box)
            selectionActive = true
            currentSelection = selection
            showSelectionToolbar(selection)

            // A cut drain is a problem the user has to know about — the rest of their ink is still
            // over there. Otherwise the ordinary paste toast, in arc-8's words (J5 Q2).
            if (truncated) {
                Dialogs.problem(
                    this, getString(wording.truncatedTitleRes),
                    getString(wording.truncatedBodyRes, strokes.size),
                )
            } else {
                toast(getString(R.string.objects_pasted_toast))
            }
            Slog.d(TAG) { "pasted ${strokes.size} strokes from $source onto $pageId" }
        }
    }

    /** The three strings a transfer paste says in its sender's name — the whole difference between
     *  the pad's paste and the calendar's. */
    private class TransferWording(
        val pasteFailedBodyRes: Int,
        val truncatedTitleRes: Int,
        val truncatedBodyRes: Int,
    )

    /** Put back the tool a transfer paste took away — only while the lasso is still armed (a tool
     *  the user picked meanwhile wins), and pen-idle, because it is a chrome frame like any other. */
    private fun restoreToolAfterTransferPaste() {
        val prior = toolBeforeTransferPaste ?: return
        toolBeforeTransferPaste = null
        if (paper.tool != Tool.LASSO) return
        whenPenIdle {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenPenIdle
            paper.tool = prior
            toolbar.sync(prior)
        }
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

    // ── The tag doors (arc 21 / W2) ──────────────────────────────────────────

    /**
     * Open the tag button's secondary bar. Availability is the button's own business — it is GONE
     * unless a trusted tag manager is installed — so the only gate here is [canvasShown]: two of
     * the three doors are about the page on the paper, and a text document that has never shown
     * its pages has none. The bar stays absent rather than opening with a door that would do
     * nothing (J4 — a control that cannot work is not shown greyed).
     */
    private fun showTagsPopup() {
        if (!opened || closing || !canvasShown) return
        if (tagsPopup.show()) pushExclusions()
    }

    private fun hideTagsPopup() {
        if (!::tagsPopup.isInitialized || !tagsPopup.isShowing) return
        tagsPopup.hide()
        pushExclusions()
    }

    /**
     * The two quick doors: the tag screen in **ADD** mode, on this notebook or on the page whose
     * ink is on the paper, with the field focused and the keyboard up.
     *
     * `displayedPageId`, never `session.currentIndex` — the R6 torn-read rule. A tag belongs to
     * the page the user can see, and during a page op those two are briefly different things.
     */
    private fun openTagsFor(targetKind: Int) {
        if (!opened || closing || !::session.isInitialized) return
        val showing = if (targetKind == TagShowing.TARGET_NOTEBOOK) {
            TagShowing(
                notebookId = notebookId,
                pageId = null,
                targetLabel = notebookName,
                mode = TagShowing.MODE_ADD,
            )
        } else {
            val pageId = displayedPageId ?: return
            TagShowing(
                notebookId = notebookId,
                pageId = pageId,
                targetLabel = pageLabel(pageId),
                mode = TagShowing.MODE_ADD,
            )
        }
        tagEntry.open(showing)
    }

    /**
     * Manage: the notebook **and** every one of its pages in one showing, so the whole notebook's
     * tagging can be read and fixed in one place.
     *
     * The page ids travel with the **labels the host resolved for them** — a page number is the
     * host's to name, and the extension has no idea what a page is. They go over the bind with
     * everything else; nothing rides the Intent.
     */
    private fun openTagManage() {
        if (!opened || closing || !::session.isInitialized) return
        val pageIds = session.pages.map { it.id }
        val listed = TagTargets.listedPages(pageIds)
        if (listed.size != pageIds.size) {
            Slog.d(TAG) { "tag manage: ${pageIds.size} pages, listing ${listed.size}" }
        }
        tagEntry.open(
            TagShowing(
                notebookId = notebookId,
                pageId = null,
                targetLabel = notebookName,
                mode = TagShowing.MODE_MANAGE,
                pageIds = listed,
                pageLabels = listed.indices.map { getString(R.string.tag_page_label, it + 1) },
            ),
        )
    }

    /** "Page N" for the tag screen's title — resolved from the live page list at the tap. A page
     *  that is not in the list has no number to give, so the notebook's own name stands in rather
     *  than a "Page 0" that names nothing. */
    private fun pageLabel(pageId: String): String {
        val n = TagTargets.pageNumber(session.pages.map { it.id }, pageId)
        return if (n == null) notebookName else getString(R.string.tag_page_label, n)
    }

    // ── Lasso → tag (arc 21 / W3) ────────────────────────────────────────────

    /**
     * The selection toolbar's **Tag**: whatever is lassoed becomes a tag on **the page it is on** —
     * always the page, never the notebook (the wizard's call), and always non-destructively. The ink,
     * the heading and the selection are all exactly as they were afterwards; a tag is a *snapshot* of
     * some text at this moment, not a second name for the thing it was taken from, so editing that
     * heading later never renames the tag.
     *
     * Which flow this is, is read off the selection **at the tap** rather than trusted from the bar
     * that offered it — the selection can be moved, changed or dismissed between the bar going up and
     * a button landing. [TagSelection] holds the rule; the `else` branch is the belt to the bar's
     * braces.
     */
    private fun tagSelection() {
        if (!opened || closing || !::session.isInitialized || !::tagEntry.isInitialized) return
        val sel = currentSelection ?: return
        val pageId = displayedPageId ?: return
        val lone = sel.strokeIds.isEmpty() && sel.contentIds.size == 1
        val loneHeading = if (lone) liveHeadings[sel.contentIds.first()] else null
        when {
            loneHeading != null -> tagFromHeading(pageId, loneHeading)
            sel.contentIds.isEmpty() && sel.strokeIds.isNotEmpty() -> tagFromInk(sel, pageId)
            else -> Slog.d(TAG) { "tag: nothing this selection can be tagged with" }
        }
    }

    /**
     * A heading is already words: one call, one toast, no screen — the wizard's "silent" flow. The
     * hash prefix is storage, not the title, so it never reaches the tag.
     *
     * The one exception is a title that is **not a tag** — over the 64-char cap, or blank. Rather
     * than refuse a tap the bar just offered, it lands in the same correction screen the ink flow
     * uses, prefilled with as much of the title as fits, so the act can still be finished in one
     * more gesture instead of none.
     */
    private fun tagFromHeading(pageId: String, heading: Heading) {
        val title = HeadingPrefix.stripHeadingPrefix(heading.text)
        if (!TagSelection.isTag(title)) {
            Slog.d(TAG) { "tag: heading of ${title.length} chars is not a tag — correcting instead" }
            openTagAdd(pageId, TagSelection.prefill(title))
            return
        }
        tagEntry.assign(title, notebookId, pageId) { display ->
            // The toast fires here and not at the tap: the standing rule is that a toast confirms
            // something that has already happened, and until the write lands it has not. The
            // selection stays up — nothing was consumed.
            toast(getString(R.string.tag_applied_toast, display))
        }
    }

    /**
     * Ink is words that have to be read first. Recognition is the heading convert's — the same
     * extension, the same single-writing-area call, the same **selection bounds** as the area (a
     * page-sized area under one line of writing collapses recognition to fragments), and the same
     * problem dialogs when there is no recognizer or it has nothing to say.
     *
     * The result is never attached silently: it goes into the tag screen's add field for the user to
     * correct, because a recognizer's best guess is not the user's word for something. Everything the
     * flow needs is captured now — recognition is async and the selection may be gone by the time it
     * answers.
     */
    private fun tagFromInk(sel: Selection, pageId: String) {
        val strokes = liveStrokes.values.filter { it.id in sel.strokeIds }
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        HeadingConvert.run(
            this, strokes, bounds.width, bounds.height,
            onRecognized = { text -> openTagAdd(pageId, TagSelection.prefill(text)) },
        )
    }

    /** The tag screen on this page, add field focused and prefilled with what was recognized. */
    private fun openTagAdd(pageId: String, prefill: String?) {
        if (!opened || closing) return
        tagEntry.open(
            TagShowing(
                notebookId = notebookId,
                pageId = pageId,
                targetLabel = pageLabel(pageId),
                mode = TagShowing.MODE_ADD,
                prefill = prefill,
            ),
        )
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
        sheet.addAction(R.drawable.ic_template, getString(R.string.page_template_action)) { openTemplatePicker() }
        sheet.addAction(R.drawable.ic_trash, getString(R.string.delete_page_action)) { confirmDeletePage() }
        sheet.show()
    }

    /**
     * The page's paper (arc 12; the whole template library since arc 13 / G3). Opened from a row of
     * the page sheet, so the pen is demonstrably idle — this rides the long-press sheet's
     * frame-silence exception, it is not a new one, and launching a screen adds no frame here at all.
     *
     * The page's current token is read first so the picker can tick the card in force, which makes
     * this the one page-sheet row that acts asynchronously: the read is blob-free (digests only)
     * and the sheet the user just tapped is already gone, so there is no window where two surfaces
     * are up.
     *
     * Scope is unchanged and stays **this page only** — the same scope Copy, Cut and Delete have.
     */
    private fun openTemplatePicker() {
        if (!opened || closing) return
        lifecycleScope.launch {
            val current = runCatching { session.currentTemplateToken() }
                .onFailure { Log.w(TAG, "template token read failed", it) }
                .getOrNull()
            if (isFinishing || isDestroyed || !opened || closing) return@launch
            // A read that FAILS still opens the picker, with nothing ticked — every card is still a
            // valid choice, and an unknown token already ticks nothing, so a failure costs the user
            // nothing they must act on.
            templatePickLauncher.launch(TemplatesActivity.pickIntent(this@NotebookActivity, current))
        }
    }

    /**
     * Re-paper the current page from a library pick, record it, and put the result on the glass —
     * the page swap is what decodes the new template, so this is a single EPD refresh like every
     * other flip.
     *
     * The pick names a card; the pixels are read here ([TemplatePicks.paper]) because the browser
     * never opens a `.soil` and the notebook never held a library id. A row that has gone since the
     * tap leaves the page exactly as it was and says so — a template that vanished must never
     * become blank paper by default.
     */
    private suspend fun doChangeTemplate(pick: TemplatePick) {
        val paper = withContext(Dispatchers.IO) { TemplatePicks.paper(repo, pick) }
        if (paper == null) {
            Dialogs.problem(this, R.string.template_gone_title, R.string.template_gone_body)
            return
        }
        // Paper that resolved but will not DRAW (bytes that no longer decode, an allocation the
        // device refused) leaves the page exactly as it was and says so — the same answer as a row
        // that vanished, because from the user's side it is the same event: the paper they asked
        // for is not available and the paper they had must not be wiped for it.
        val change = try {
            session.changeTemplate(paper, resources.displayMetrics.densityDpi.toFloat())
        } catch (e: NotebookSession.PaperRenderFailed) {
            Log.w(TAG, "template render failed", e)
            Dialogs.problem(this, R.string.template_render_failed_title, R.string.template_render_failed_body)
            return
        }
        // The paper resolved and the page took it — that is an apply, and an apply is the only
        // thing that makes paper recent (arc 13 / G5). Recorded before the null check: re-picking
        // the paper already in force writes nothing (a true no-op with no undo step), but the user
        // did choose it, and the shelf's job is to remember what they chose. A prefs write is not
        // a page change, so the no-op stays one. A *failed* render never reaches here: it is not
        // paper the user can go back to.
        TemplateRecents.record(this, pick)
        if (change == null) return
        undo.record(Action.TemplateChanged(change.pageId, change.from, change.to))
        refreshToPage(change.pageId)
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
        // Snap's margin is "one toolbar" (arc 9) — and the toolbar is the button row *plus* its
        // 1 dp border, so the dimen alone would leave a snapped object two pixels behind the black
        // rule. Take the bar's real laid-out height instead, here because this runs on every chrome
        // layout change and so can never drift from the thing it is measuring.
        binding.topBar.height.takeIf { it > 0 }?.let { paper.snapMarginPx = it.toFloat() }
        if (!opened || !canvasShown) {
            // The toolbar arms the pen from the first frame, but the page isn't on the paper yet —
            // a stroke committed now would hit the listener's `opened` guard, never reach the
            // store, and be silently wiped by loadStrokes. Block the whole surface until then.
            //
            // [canvasShown], not just `opened` (M8): a text document is *open* — its hooks answer,
            // its rename works — while its paper has never been loaded at all. Ink onto that
            // surface would be ink at no page's size, and the Ratta daemon draws it beneath every
            // window this route puts on top.
            paper.setExclusionRects(listOf(BLOCK_ALL))
            return
        }
        if ((::contentsFlow.isInitialized && contentsFlow.showing) ||
            (::recentsFlow.isInitialized && recentsFlow.showing)
        ) {
            // A full-height panel is up (Contents, or arc 10's Recents): the Ratta ink daemon draws
            // firmware ink beneath any Android window, so the whole paper is one exclusion rect
            // until it dismisses. (The small transient dialogs deliberately don't do this — a
            // persistent full-height panel is where a pen plausibly lands.)
            paper.setExclusionRects(listOf(BLOCK_ALL))
            return
        }
        val paperLoc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
        val rects = (
            listOfNotNull(rectOf(binding.topBar), rectOf(binding.bottomStrip)) +
                selectionToolbar.rects() + lassoPopup.rects() + tagsPopup.rects()
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
        if (::paper.isInitialized) {
            val action = ev.actionMasked
            // Every pointer going down, not just the first: with a hand resting on the glass the
            // pen arrives as ACTION_POINTER_DOWN, and a latch written only at ACTION_DOWN would
            // still be carrying the resting contact's answer (O2 review).
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                dismissLassoPopupOnContact(ev, ev.actionIndex)
                dismissTagsPopupOnContact(ev, ev.actionIndex)
            }
            if (action == MotionEvent.ACTION_DOWN) {
                val tool = ev.getToolType(0)
                val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                if (!stylus && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Any contact that starts outside the popup — and outside the lasso button that toggles it —
     * takes it down: an outside tap, the start of a finger gesture, a pen going to write.
     *
     * [tapDismissedPopup] latches whether *this* contact is the one that did it, and is rewritten at
     * every pointer-down — [index] is the one going down — so it can never go stale. `onPaperTapped`
     * reads it and declines to paste: a contact spent on a dismissal is spent, the same rule g-paper
     * applies to the tap that dismisses a selection. The lasso button is excluded or its own re-tap
     * would close the popup here and immediately reopen it in [NotebookToolbar].
     */
    private fun dismissLassoPopupOnContact(ev: MotionEvent, index: Int) {
        if (!::lassoPopup.isInitialized) { tapDismissedPopup = false; return }
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        val onToggle = rectOf(binding.btnLasso)?.contains(x, y) == true
        tapDismissedPopup = lassoPopup.isShowing && !onToggle && !lassoPopup.contains(x, y)
        if (tapDismissedPopup) hideLassoPopup()
    }

    /**
     * The tag bar's outside-tap dismissal (arc 21 / W2) — the lasso popup's rule, and the tag
     * button excluded for the same reason: without that, its own re-tap would close the bar here
     * and the click listener would immediately reopen it.
     *
     * It deliberately does **not** write [tapDismissedPopup]. That latch exists so a contact spent
     * dismissing the clipboard popup is not also spent pasting, and the tag bar has no such
     * second meaning — a pen going to write while it is up should take it down and then ink.
     */
    private fun dismissTagsPopupOnContact(ev: MotionEvent, index: Int) {
        if (!::tagsPopup.isInitialized || !tagsPopup.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (rectOf(binding.btnTags)?.contains(x, y) == true) return
        if (tagsPopup.contains(x, y)) return
        hideTagsPopup()
    }

    /** Both bars, the selection toolbar and the two floating popups — a floating bar is chrome
     *  like any other. */
    private fun overChrome(ev: MotionEvent): Boolean {
        val top = rectOf(binding.topBar)
        val bottom = rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) ||
            (bottom?.contains(x, y) == true) ||
            (::selectionToolbar.isInitialized && selectionToolbar.contains(x, y)) ||
            (::lassoPopup.isInitialized && lassoPopup.contains(x, y)) ||
            (::tagsPopup.isInitialized && tagsPopup.contains(x, y))
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
        // Re-discovered on every resume: a package can be disabled or replaced under us, and this
        // is also the resume that follows a return from the pad.
        if (::scratchPad.isInitialized) scratchPad.refresh()
        if (::calendar.isInitialized) calendar.refresh()
        if (::documentEntry.isInitialized) documentEntry.refresh()
        if (::tagEntry.isInitialized) tagEntry.refresh()
    }

    /**
     * The three things this screen carries across its own death (M4, grown at M6/M7): whether the
     * document editor was showing, which page it was on, and whether it was on the NOTEBOOK
     * document (og's `STATE_DOCUMENT_NOTEBOOK` — the mode-routing flag that keeps a recreated host
     * from serving a page document to an editor whose buffer holds the notebook one). Everything
     * else it needs is in the Intent or the `.soil` — but a live showing lives only in another
     * process, and without these the recreated instance would have no way to know a bind is owed
     * one, nor which document the editor's next `current()` is asking about. See
     * [DocumentEditorEntry.reconnect] and [DocumentHostHooks.restoreTarget].
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            KEY_DOCUMENT_SHOWING,
            ::documentEntry.isInitialized && documentEntry.isShowing,
        )
        outState.putString(
            KEY_DOCUMENT_TARGET,
            if (::documentHooks.isInitialized) documentHooks.targetPageId else null,
        )
        outState.putBoolean(
            KEY_DOCUMENT_SCOPE,
            ::documentHooks.isInitialized && documentHooks.scopeIsNotebook,
        )
        // M8: and whether this incarnation ever put the pages on the glass — a recreated text
        // document that had must come back as an ordinary notebook, not into the editor again.
        outState.putBoolean(KEY_CANVAS_SHOWN, canvasShown)
    }

    /**
     * The cover this notebook's card shows, at both durability points (M8). A **text document**
     * renders its own opening lines through `:markdown` ([TextCover]) instead of snapshotting the
     * paper: on that route the surface may never have been loaded at all, and a snapshot of an
     * unloaded surface is a blank card where a page used to be.
     *
     * What it renders is the **stored** document. The editor may still be holding unsaved text, and
     * the `.soil`'s copy is the only picture this side can honestly draw — og renders at
     * seal-after-flush, and `onStop`'s mid-session capture is a bonus on the same terms.
     */
    private suspend fun captureCover(p: PaperView, s: NotebookSession, id: String) {
        if (s.isTextDocument) TextCover.render(repo, id, s.documents.get(id)?.text.orEmpty())
        else if (opened) CoverSnapshot.capture(p, id, repo)
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
                    // The editor launching over us is one of the ways we get here — and it is
                    // exactly when a text document's cover should be re-rendered.
                    if (!closing) captureCover(p, s, id)
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
        // A Dialog outliving its finishing Activity is a window leak — take both panels down now.
        if (::contentsFlow.isInitialized) contentsFlow.dismissIfShowing()
        if (::recentsFlow.isInitialized) recentsFlow.dismissIfShowing()
        // Recents shows "when I last put it down" (arc 10). This and the onDestroy fallback are
        // mutually exclusive on `closing`, so the stamp is written exactly once per screen.
        RecentsPrefs(this).touch(notebookId)
        // The relay's source closes over the session about to be sealed — drop it with the screen.
        if (::linkPickFlow.isInitialized) linkPickFlow.close()
        BrowseState(this).lastOpenNotebookId = null
        if (!::session.isInitialized || !session.isOpen) { andThen?.invoke(); finish(); return }
        val p = paper; val s = session; val id = notebookId
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        appScope.launch {
            withContext(NonCancellable) {
                // The editor's teardown first (M11): a showing that just ended has an `end()` flush
                // in flight on the entry's detached scope, and the seal below must not start under
                // it — flush-before-seal, across the process boundary. A finished (or absent) job
                // joins instantly; `documentWritesClosed` is what refuses anything after this line.
                if (::documentEntry.isInitialized) documentEntry.finishJob?.join()
                documentWritesClosed = true
                // The page-op mutex first: an insert/delete that passed the `closing` check before
                // it flipped may still be inside its transaction — sealing under it would fail the
                // transaction silently (runPageOp swallows) or split the .soil from its index
                // mirror. New ops can't start (`closing` is set), so this only waits, never races.
                pageOps.withLock {
                    // Before the seal, always — and for a text document before the paper has
                    // necessarily ever been loaded (captureCover is what knows the difference).
                    try { captureCover(p, s, id) } catch (e: Exception) { Log.w(TAG, "cover failed", e) }
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
        // otherwise leak a panel dialog's window — the exact hazard close() documents.
        if (::contentsFlow.isInitialized) contentsFlow.dismissIfShowing()
        if (::recentsFlow.isInitialized) recentsFlow.dismissIfShowing()
        if (::linkPickFlow.isInitialized) linkPickFlow.close()
        // The pad's held bind must not outlive the screen that opened it, result or no result.
        if (::scratchPad.isInitialized) scratchPad.close()
        // The calendar's held bind, same rule and the same reason.
        if (::calendar.isInitialized) calendar.close()
        // The tag screen's held bind, same rule. It reaches back into nothing of ours — the index
        // is the extension's own store value — so it needs no ordering against the seal below.
        if (::tagEntry.isInitialized) tagEntry.close()
        // Same rule for the editor's held bind — and it matters more here, because its host binder
        // reaches back into this session: released before the seal below, never after. The close's
        // Job is what enforces "never after" (M11): the seal coroutine joins it, so the extension's
        // `end()` flush lands on a session that is still open.
        val documentClose = if (::documentEntry.isInitialized) documentEntry.close() else null
        if (::paper.isInitialized) paper.release()
        // A destroy that isn't a normal close (e.g. finish() out of failOpen) still seals.
        if (::session.isInitialized && session.isOpen && !closing) {
            closing = true
            undo.clear()
            RecentsPrefs(this).touch(notebookId)   // close()'s twin — see the note there
            val s = session
            appScope.launch {
                withContext(NonCancellable) {
                    documentClose?.join()
                    documentWritesClosed = true
                    pageOps.withLock { try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) } }
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotebookActivity"

        /** Saved state (M4): the document editor was showing when this instance went down. */
        private const val KEY_DOCUMENT_SHOWING = "notebook.documentShowing"

        /** Saved state (M6): the page that showing had flipped to — the host's target, which the
         *  notebook underneath does not follow until the showing ends. */
        private const val KEY_DOCUMENT_TARGET = "notebook.documentTarget"

        /** Saved state (M7): that showing was on the NOTEBOOK document (og's
         *  `STATE_DOCUMENT_NOTEBOOK`) — the mode-routing flag's host half. */
        private const val KEY_DOCUMENT_SCOPE = "notebook.documentScope"

        /** Saved state (M8): this incarnation has put the pages on the paper, so a text document
         *  comes back an ordinary notebook — [TextDocRouting]'s one-way latch. */
        private const val KEY_CANVAS_SHOWN = "notebook.canvasShown"

        /** How long a text document waits for the editor it launched before deciding it is not
         *  coming and showing the pages instead. Comfortably past a cold bind's KDF (≈3 s on the
         *  Nomad) — this is a backstop, not a timeout anyone should ever see. */
        private const val EDITOR_LAUNCH_WATCHDOG_MS = 10_000L

        /** What a paste back from the scratch pad says when it fails or is cut short. */
        private val PAD_WORDING = TransferWording(
            pasteFailedBodyRes = R.string.scratch_paste_failed_body,
            truncatedTitleRes = R.string.scratch_truncated_title,
            truncatedBodyRes = R.string.scratch_truncated_body,
        )

        /** The same three, in the calendar's name (arc 23 / Y3). */
        private val CALENDAR_WORDING = TransferWording(
            pasteFailedBodyRes = R.string.calendar_paste_failed_body,
            truncatedTitleRes = R.string.calendar_truncated_title,
            truncatedBodyRes = R.string.calendar_truncated_body,
        )

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
