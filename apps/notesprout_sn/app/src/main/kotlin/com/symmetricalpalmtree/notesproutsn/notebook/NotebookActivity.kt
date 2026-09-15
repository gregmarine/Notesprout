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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
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
import com.symmetricalpalmtree.notesproutsn.crypto.KeyFailure
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookRecovery
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import com.symmetricalpalmtree.notesproutsn.data.template.PaperSource
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateFit
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceStack
import com.symmetricalpalmtree.notesproutsn.data.prefs.LinkTrail
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.ChromePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.SnapPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix
import com.symmetricalpalmtree.notesproutsn.extension.BibleEntry
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteIndex
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.LookupHandoff
import com.symmetricalpalmtree.notesproutsn.extension.CalendarEntry
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.DocumentEditorEntry
import com.symmetricalpalmtree.notesproutsn.extension.DrainedInk
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.export.ExportActivity
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.InkSend
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.extension.PassageText
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference
import com.symmetricalpalmtree.notesproutsn.extension.ScratchPadEntry
import com.symmetricalpalmtree.notesproutsn.extension.TagManagerEntry
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import com.symmetricalpalmtree.notesproutsn.extension.TransferCaps
import com.symmetricalpalmtree.notesproutsn.extension.TransferSelection
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.ReplayPlan
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import com.symmetricalpalmtree.notesproutsn.notebook.ChromeBand
import com.symmetricalpalmtree.notesproutsn.notebook.ChromeToggle
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar
import com.symmetricalpalmtree.notesproutsn.notebook.asBar
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
import java.util.UUID

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
    /** Arc 33: the one global "chrome hidden" flag and the flip that honours it on this screen. */
    private lateinit var chromePrefs: ChromePrefs
    private lateinit var chromeToggle: ChromeToggle
    private val doubleTapRule = DoubleTapToggleRule()
    /** Arc 36: the corner tool button + mini toolbar that stand in for the hidden bars. */
    private lateinit var collapsed: CollapsedChrome
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
    /** The Bible reader's entry (arc 37 / B0) — the ninth point's door, and the owner of the bottom
     *  strip's `btnBible`. No paper behind it: no handoff, no chrome flag. */
    private lateinit var bible: BibleEntry
    /** The three tag doors that button opens (arc 21 / W2). */
    private lateinit var tagsPopup: TagsPopup
    /** The Insert button's sub-bar (arc 28 / H1) — Sticky, Text and the six shapes. */
    private lateinit var insertBar: InsertBar

    /** The eraser button's sub-bar (arc 29 / LE2) — Point · Lasso, opened by a re-tap on the armed
     *  eraser. `:sn-screen`'s, because all four paper surfaces share the one bar. */
    private lateinit var eraserBar: EraserBar

    /** The shape transform mode's floating bar (arc 28 / H4) — up only while
     *  `paper.transformingContentId != null`, torn down by the one `onTransformEnded`. */
    private lateinit var transformBar: ShapeTransformBar

    /** Insert, convert and edit for text objects (arc 28 / H2) — out of this file, on the
     *  [LinkPickFlow] pattern, because H1's ledger asked the per-kind flows to stop landing here.
     *  The selection state and the working copies stay private: the flow reaches them only through
     *  the few verbs [TextFlow.Host] names. `strokesIn` filters the *mirror*, never the selection's
     *  Set — a LinkedHashMap filled by load then by commit preserves **writing order**, which the
     *  recognizer reads as the writing itself. */
    private val textFlow = TextFlow(this, object : TextFlow.Host {
        override val alive: Boolean get() = opened && !closing
        override val session: NotebookSession get() = this@NotebookActivity.session
        override val pageId: String get() = displayedPageId
        override val objects: PageObjects get() = pageObjects
        override val paper: PaperView get() = this@NotebookActivity.paper
        override val density: Float get() = resources.displayMetrics.density
        override fun strokesIn(ids: Set<String>) = liveStrokes.values.filter { it.id in ids }
        override fun dropLiveStrokes(ids: List<String>) { ids.forEach { liveStrokes.remove(it) } }
        override fun record(action: Action) = undo.record(action)
        override fun selectAsText(text: PageText) = this@NotebookActivity.selectAsText(text)
        override fun armLassoForLanding() = this@NotebookActivity.armLassoForLanding()
        override fun occupied(): List<Bounds> = occupiedBounds()
        override fun armPendingSelection(select: () -> Unit) { pendingSelection = select }
        override fun drainPendingSelection() { pendingSelection?.let { pendingSelection = null; it() } }
    })

    /** Convert, insert and edit for **Bible references** (arc 38 / R3) — [TextFlow]'s neighbour and
     *  its shape exactly, out of this file for the same reason. What it makes is an ordinary text
     *  object wrapped in an ordinary link, so everything it needs of the screen is either a verb
     *  [TextFlow.Host] already names or the wrap machinery `createLinkFromSelection` uses, reached
     *  through [BibleRefFlow.Host.wrapTextAsLink]. It never touches the extension itself: the one
     *  call out is `resolve`, which is [BibleEntry]'s (and answers null when there is no reader —
     *  the doors are gated, but a package can be pulled between the tap and the Save). */
    private val bibleRefFlow = BibleRefFlow(this, object : BibleRefFlow.Host {
        override val alive: Boolean get() = opened && !closing
        override val session: NotebookSession get() = this@NotebookActivity.session
        override val pageId: String get() = displayedPageId
        override val objects: PageObjects get() = pageObjects
        override val paper: PaperView get() = this@NotebookActivity.paper
        override val density: Float get() = resources.displayMetrics.density
        override fun strokesIn(ids: Set<String>) = liveStrokes.values.filter { it.id in ids }
        override fun record(action: Action) = undo.record(action)
        override suspend fun resolve(text: String): ResolvedReference? =
            if (::bible.isInitialized) bible.resolve(text) else null
        // Read at the call, never captured — discovery re-runs on resume.
        override val supportsReferences: Boolean get() = ::bible.isInitialized && bible.supportsReferences
        // Arc 40 "Verses": both read at the call, never captured — discovery re-runs on resume.
        override val supportsVerses: Boolean get() = ::bible.isInitialized && bible.supportsText
        override suspend fun passageText(wire: String): PassageText? =
            if (::bible.isInitialized) bible.passageText(wire) else null
        override fun wrapTextAsLink(
            pageId: String,
            text: PageText,
            payload: String,
            consumedStrokeIds: List<String>,
        ): PageLink? = this@NotebookActivity.wrapTextAsLink(pageId, text, payload, consumedStrokeIds)
        override fun liveLink(id: String): PageLink? = liveLinks[id]
        override fun relandEditedLink(link: PageLink) = this@NotebookActivity.relandEditedLink(link)
        override fun armLassoForLanding() = this@NotebookActivity.armLassoForLanding()
        override fun occupied(): List<Bounds> = occupiedBounds()
        override fun toast(text: String) = this@NotebookActivity.toast(text)
    })

    /** Insert and transform for shapes (arc 28 / H4) — [TextFlow]'s neighbour, out of this file for
     *  the same reason and reaching the screen through the same kind of narrow [ShapeFlow.Host].
     *  Two of its verbs exist only because g-paper's transform mode dismisses the selection without
     *  a callback ([ShapeFlow.Host.dismissSelectionChrome]) and leaves nothing selected at the exit
     *  ([ShapeFlow.Host.restoreToolAfterLanding]) — both are chrome this screen alone owns. */
    private val shapeFlow = ShapeFlow(object : ShapeFlow.Host {
        override val alive: Boolean get() = opened && !closing
        override val session: NotebookSession get() = this@NotebookActivity.session
        override val pageId: String get() = displayedPageId
        override val objects: PageObjects get() = pageObjects
        override val paper: PaperView get() = this@NotebookActivity.paper
        override val density: Float get() = resources.displayMetrics.density
        override fun record(action: Action) = undo.record(action)
        override fun selectAsShape(shape: PageShape) = this@NotebookActivity.selectAsShape(shape)
        override fun armLassoForLanding() = this@NotebookActivity.armLassoForLanding()
        override fun occupied(): List<Bounds> = occupiedBounds()
        override fun restoreToolAfterLanding() = restoreToolAfterTransferPaste()
        override fun dismissSelectionChrome() {
            selectionActive = false
            currentSelection = null
            selectionToolbar.hide()
        }
        override fun showTransformBar(shape: PageShape) {
            transformBar.show(shape)
            pushExclusions()
        }
        override fun relabelTransformBar(shape: PageShape) = transformBar.relabel(shape)
        override fun transformBarCovers(shape: PageShape) = transformBar.coveredBy(shape)
        override fun hideTransformBar() {
            if (!::transformBar.isInitialized || !transformBar.isShowing) return
            transformBar.hide()
            pushExclusions()
        }
    })
    /** The sticky note flow (arc 28 / H5): insert, the editor's launch and return, the finger-tap
     *  reopen. Its [StickyFlow.Host] is the same shape as the shape flow's plus the handoff verbs —
     *  the launcher below is the one door to [StickyEditorActivity], and the result callback's
     *  first act is the pipeline reclaim (result callbacks run before `onResume`). */
    private val stickyFlow: StickyFlow = StickyFlow(object : StickyFlow.Host {
        override val alive: Boolean get() = opened && !closing
        override val session: NotebookSession get() = this@NotebookActivity.session
        override val pageId: String get() = displayedPageId
        override val objects: PageObjects get() = pageObjects
        override val paper: PaperView get() = this@NotebookActivity.paper
        override val density: Float get() = resources.displayMetrics.density
        override fun contentSize(): Pair<Int, Int> =
            StickyDefaults.contentSize(binding.root.width, binding.root.height)
        override fun record(action: Action) = undo.record(action)
        override fun runPageOp(block: suspend () -> Unit) = this@NotebookActivity.runPageOp(block)
        override fun selectAsSticky(sticky: PageSticky) = this@NotebookActivity.selectAsSticky(sticky)
        override fun armLassoForLanding() = this@NotebookActivity.armLassoForLanding()
        override fun occupied(): List<Bounds> = occupiedBounds()
        override fun endTransformIfRunning() = this@NotebookActivity.endTransformIfRunning()
        override fun reclaimPipeline() { if (this@NotebookActivity::paper.isInitialized) this@NotebookActivity.paper.resumeDrawing() }
        override fun dismissFloatingChrome() {
            hideLassoPopup()
            hideTagsPopup()
            hideInsertBar()
            hideEraserBar()
        }
        override fun launchEditor(intent: Intent) = stickyEditorLauncher.launch(intent)
        override fun syncClipboardMark() {
            if (this@NotebookActivity::toolbar.isInitialized) markClipboard(SnClipboard.hasObjects)
        }
    })

    /** The sticky editor's launcher (arc 28 / H5). Registered at field-init like the template
     *  picker's, before RESUMED. The callback ignores the result code: whatever the editor said,
     *  the pipeline is reclaimed and the rows are re-read. */
    private val stickyEditorLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { stickyFlow.onEditorClosed() }

    private val repo by lazy { IndexRepository() }

    /**
     * **Save as template** (arc 31 / HV2) — the page sheet's last row, whole, out of this file on
     * the [StickyFlow] pattern. It registers its own folder-picker launcher at construction (this
     * field is initialised before STARTED, like [templatePickLauncher]), holds the page's pixels
     * across that picker itself, and writes only to the template library: no `.soil` write, no undo
     * entry, nothing about this notebook changes. `repo` is passed as a lambda because it is
     * `by lazy` for `IndexGuard`'s sake and this field is built before `onCreate` runs.
     */
    private val saveAsTemplateFlow = SaveAsTemplateFlow(
        activity = this,
        repo = { repo },
        session = { session },
        alive = { opened && !closing },
        runPageOp = { block -> runPageOp(block) },
    )

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
     * Which key this notebook opened under (arc 26 / U4) — read from the index row once, in
     * [keyFor], and never again: the scope is changed only by the Encryption screen, which cannot
     * run while this one is on the glass. `GLOBAL` before the open, which is the honest answer for
     * a screen that has not read the row yet and the safe one for the cover question below.
     *
     * The seal-time cover capture asks it: a `NOTEBOOK`-scope notebook's card is a lock and the
     * index holds no cover for it (decision 11), so nothing here may write one back.
     */
    @Volatile
    private var keyScope: KeyScope = KeyScope.GLOBAL

    /** Recovery offered once per notebook launch (arc 26 / U6, og's `openFixAttempted`) — on the
     *  Intent so a recreate cannot re-offer it; a boolean, never a secret. */
    private var recoveryAttempted: Boolean
        get() = intent.getBooleanExtra(EXTRA_RECOVERY_ATTEMPTED, false)
        set(value) { intent.putExtra(EXTRA_RECOVERY_ATTEMPTED, value) }

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

    /** The surface stack (arc 32 / RS1) and this instance's token on it — see [SurfaceStack]. */
    private lateinit var stack: SurfaceStack
    private lateinit var stackToken: String

    /**
     * The follow's target page, overriding the notebook's remembered `refId` for this open only —
     * read from the Intent **only on a fresh create** (locked K4): Android redelivers the original
     * Intent on a recreate, and re-applying the override would land a rebuilt via-link notebook on
     * the link's target instead of where the user actually was (the Paper quirk, fixed here).
     * Consumed by [openSession]; a dead target falls back to `refId` silently — the honest dialog
     * was the tapping side's job.
     */
    private var initialPageId: String? = null

    /**
     * The chain of extension screens that stood above this notebook when the process died (arc 32 /
     * RS2), read from `EXTRA_RESUME_ABOVE` on a **fresh create only** — [initialPageId]'s rule and
     * its reason: Android redelivers the original Intent on a task rebuild, and a screen the user
     * has since backed out of must not come back. **Consumed once**, at whichever of [loadCanvas]
     * or [openIntoEditor] the open ends in; emptied as it is read, so the second run of a
     * re-entrant load is a no-op.
     */
    private var resumeAbove: List<Surface> = emptyList()

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

    /**
     * Arc 42 "Notes": the coalescer that keeps the Bible reader's index of where this notebook's
     * references sit. Every act that touched the page's links ends in [markNotes], every act that
     * moved the page list in `markStructural`, and the push itself runs debounced on [appScope] —
     * so a Back taken right after the last mark still lands. Both reads it needs go through the
     * open session, never a second connection, and answer empty before (or after) it is there.
     */
    private val noteSync = BibleNoteSync(
        context = this,
        scope = appScope,
        notebookId = { notebookId },
        notebookName = { notebookName },
        linkRows = {
            if (::session.isInitialized && session.isOpen) session.db.dao().liveLinkRows() else emptyList()
        },
        livePageIds = {
            if (::session.isInitialized && session.isOpen) session.pages.map { it.id } else emptyList()
        },
    )

    /** Arc 42: [pageId]'s 1-based number in the notebook, 0 when it is no longer one of its pages
     *  (which is what tells the sync there is nothing to push). */
    private fun ordinalOf(pageId: String): Int =
        if (!::session.isInitialized) 0 else session.pages.indexOfFirst { it.id == pageId } + 1

    /** Arc 42: the page's links **as the working copy now has them** — so every call site sits
     *  after [liveLinks] has been brought in line with the rows, never before. */
    private fun markNotes(pageId: String = displayedPageId) {
        if (pageId.isEmpty()) return
        noteSync.markPage(pageId, ordinalOf(pageId), liveLinks.values)
    }

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
     * The visible page's texts, shapes and sticky icons, and the three renderers that paint them
     * (arc 28 / H1). One field instead of six working copies and three renderers, for the reason
     * this file's header gives: everything separable lives in a collaborator.
     */
    private lateinit var pageObjects: PageObjects

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
        if (savedInstanceState == null) {
            resumeAbove = ReplayPlan.decodeAbove(intent.getStringArrayListExtra(EXTRA_RESUME_ABOVE))
        }
        // The surface-stack token (arc 32 / RS1): saved across a same-process recreate so the
        // attach below refreshes this screen's entry in place instead of stacking a second one.
        stackToken = savedInstanceState?.getString(KEY_STACK_TOKEN) ?: UUID.randomUUID().toString()
        stack = SurfaceStack(this)

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
        // Arc 28 (D8): **registration order is z-order**, and this is the one place it is written
        // out — headings · texts · shapes · links · sticky icons, then the engine's ink over all of
        // it. Texts and shapes go under the links because a link's composite already holds the ones
        // it wrapped and its own chrome must sit on top of them; the sticky icons go last, so a
        // note dropped over anything stays reachable.
        pageObjects = PageObjects(dm.density, dm.scaledDensity, stickyIcon())
        paper.addContentRenderer(pageObjects.textRenderer)
        paper.addContentRenderer(pageObjects.shapeRenderer)
        // Its own mutate()d icon: a Drawable carries mutable bounds, and the composite raster it
        // draws a wrapped sticky into may not be built on Main.
        linkRenderer = LinkRenderer(dm.density, dm.scaledDensity, stickyIcon())
        paper.addContentRenderer(linkRenderer)
        paper.addContentRenderer(pageObjects.stickyRenderer)

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
            // A second tap on the armed eraser opens its own sub-bar (arc 29 / LE2) — Point ·
            // Lasso — and a third closes it again. The lasso popup's toggle exactly.
            onEraserReTap = { if (eraserBar.isShowing) hideEraserBar() else showEraserBar() },
            // Arming a different tool takes the popup with it: it belongs to the lasso. The Insert
            // bar goes too — it belongs to no tool, but a tool tap is a new intention. And so does
            // the eraser sub-bar, which belongs to the eraser being left.
            onToolTapped = {
                if (lassoPopup.isShowing) hideLassoPopup()
                hideInsertBar()
                hideEraserBar()
            },
            // Arc 36: the collapsed chrome's corner button repaints with the bar — the one funnel
            // every tool change passes through (a tap, `arm`, `armLasso`, every by-hand sync,
            // `onToolChanged`), so no path can leave it wearing a tool that is no longer armed.
            onSynced = { if (::collapsed.isInitialized) collapsed.sync() },
        )
        // The bottom strip's pager (the calendar's and the pad's [‹] [n / N] [›]). It flips only
        // WITHIN the notebook: the swipe past the last page still grows the notebook, but a button
        // that made a page on a stray tap would write to the file for a mis-tap. At either bound it
        // is a silent no-op — the arrows never disable (a disabled control is invisible on e-ink).
        // The render is released first, pen-gated, exactly as every other chrome tap does it.
        binding.btnPrevPage.setOnClickListener {
            releaseRenderIfIdle()
            runPageOp { if (session.currentIndex > 0) navigateTo(session.currentIndex - 1) }
        }
        binding.btnNextPage.setOnClickListener {
            releaseRenderIfIdle()
            runPageOp { if (session.currentIndex < session.pages.lastIndex) navigateTo(session.currentIndex + 1) }
        }
        listOf(binding.btnPrevPage, binding.btnNextPage).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
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
            // Resolved at tap time, never captured: the selection can move, die or change kind
            // between the bar going up and a button landing.
            onTextConvert = { currentSelection?.let { textFlow.convert(it) } },
            // Resolved at tap time for the same reason, and by id: the flow re-reads the working
            // copy, which is the only place the shape's current geometry lives.
            onTransform = { loneSelectedShapeId()?.let { shapeFlow.beginTransform(it) } },
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
            // Arc 38 / R3. Resolved at tap time like every other selection verb here.
            onBible = { currentSelection?.let { bibleRefFlow.convert(it) } },
            // The pad's rule again, and one step narrower: `BibleEntry` re-runs discovery on every
            // resume, and what is read here is whether the reader it found understands *references*
            // — an older one still serves the bottom bar's plain door and must not offer this.
            isBibleAvailable = { ::bible.isInitialized && bible.supportsReferences },
            // Arc 40 "Verses": a lone placed Bible *reference* (never one already holding the
            // verses), against a reader declaring the text floor.
            onVerses = { loneSelectedLink()?.let { bibleRefFlow.expand(it) } },
            isVersesAvailable = {
                val link = loneSelectedLink()
                link != null && LinkPayload.referenceOf(link.payload) != null &&
                    !LinkPayload.isBibleText(link.payload) && ::bible.isInitialized && bible.supportsText
            },
        )
        // The transform mode's own floating bar (arc 28 / H4). It is not part of the selection
        // toolbar: the mode is not a selection, and the two are never up at the same time.
        transformBar = ShapeTransformBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.transformBar,
            band = { chromeBand() },
            // Ungated, like every other bar handler here: the pen that tapped it is still hovering.
            releaseRender = { paper.releaseRender() },
            onToggleLock = { shapeFlow.toggleAspectLock() },
            onDone = { shapeFlow.done() },
        )
        binding.notebookName.text = name
        binding.pageIndicator.text = ""

        pageGestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            // H3's one finding, met on the Nomad in g-paper's own demo: a host finger handler that
            // goes on consuming while the transform mode is up swallows the handle drags and the
            // tap that ends the mode. The gate is the selection's, widened to cover it.
            standDown = { selectionActive || paper.transformingContentId != null },
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
            // The transform mode first: `releaseForHandoff` is a silent release, and a mode still
            // running when the pipeline goes over would take its geometry with it (H4).
            beforeLaunch = { endTransformIfRunning(); paper.releaseForHandoff() },
            // A launch the system refused after the hand-over (arc 34 / M8): this screen never
            // paused, so its onResume will not re-arm the pipeline — this does.
            afterLaunchFailed = { paper.resumeDrawing() },
            onSent = { onPadSent() },
            onDrained = { drained -> pasteFromPad(drained) },
            onClosed = { onPadClosed(it) },
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
            beforeLaunch = { endTransformIfRunning(); paper.releaseForHandoff() },
            afterLaunchFailed = { paper.resumeDrawing() },
            onSent = { onCalendarSent() },
            onDrained = { drained -> pasteFromCalendar(drained) },
            onExport = { onCalendarExport(it) },
            onClosed = { onCalendarClosed(it) },
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
            // Arc 39 "Lookup": the editor's selection → the passage view over the editor.
            lookupReference = { text -> lookupBibleReference(text) },
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
            // Arc 39: the editor offers its Bible item only when the reader here understands
            // references — the host's discovery answer, read at the open (the entry below is
            // built a few lines down; nothing opens the editor before onResume).
            bibleAvailable = { ::bible.isInitialized && bible.supportsReferences },
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
            // Arc 36: the popup may hang off the collapsed overflow row — a door taken closes it too.
            onTagNotebook = { hideTagsPopup(); dismissCollapsed(); openTagsFor(TagShowing.TARGET_NOTEBOOK) },
            onTagPage = { hideTagsPopup(); dismissCollapsed(); openTagsFor(TagShowing.TARGET_PAGE) },
            onManage = { hideTagsPopup(); dismissCollapsed(); openTagManage() },
        )
        binding.btnTags.setOnClickListener {
            if (!opened || closing) return@setOnClickListener
            if (tagsPopup.isShowing) hideTagsPopup() else showTagsPopup()
        }
        TooltipCompat.setTooltipText(binding.btnTags, binding.btnTags.contentDescription)

        // The Bible (arc 37 / B0) — the tag manager's shape: a launcher, so built here; a
        // non-drawing screen, so no handoff. Its button is on the bottom strip (the user's call).
        bible = BibleEntry(
            activity = this,
            button = binding.btnBible,
            // Arc 38 / R3: the Insert bar's Bible button tracks the *reference* floor, not merely
            // the reader's presence, and discovery re-runs on every resume — so the offer is made
            // from the answer rather than once at startup. The bar is built a few lines below this
            // one; nothing calls back before onResume.
            onAvailabilityChanged = { supported ->
                if (::insertBar.isInitialized) insertBar.offer(InsertBar.Kind.BIBLE, supported)
            },
            // B9: this door has a notebook behind it, so the reader gets its Send to notebook
            // button; what it parks lands here as a Bible reference object, selected.
            sendEnabled = true,
            onSent = { reference -> bibleRefFlow.insertResolved(reference) },
            // Arc 40 "Verses": the Send that chose the words — the reference and its Markdown.
            onSentText = { reference, text -> bibleRefFlow.insertVersesSent(reference, text) },
            // Arc 42 "Notes": this door has a host screen behind it that can open a notebook page,
            // so the reader gets its Notes panel and its Rebuild.
            notesEnabled = true,
            // TODO arc 42 N3/N4 — the follow and the rebuild.
            onOpenNote = { Slog.d(TAG) { "open note: $it" } },
            onRebuildNotes = { Slog.d(TAG) { "rebuild notes requested" } },
        )
        binding.btnBible.setOnClickListener {
            if (!opened || closing) return@setOnClickListener
            bible.open()
        }
        TooltipCompat.setTooltipText(binding.btnBible, binding.btnBible.contentDescription)

        // Insert (arc 28 / H1, D4) — the sub-bar and the button that opens it. Every one of the
        // eight buttons is GONE until its own phase offers it (J4): a control that does nothing
        // does not exist. H2 gave one of them — Text — something to do in every build, which is
        // what took the debug gate off the button itself.
        insertBar = InsertBar(
            root = binding.root,
            bar = binding.insertBar,
            anchor = binding.btnInsert,
            bandBottom = { chromeBand()?.last },
            // Ungated, like the tags popup's and the selection bar's: a pick here opens a dialog
            // (Text) or lands a selection, and the pen that tapped it is still hovering — an
            // idle-gated release would hold the frame until the pen left the glass (the R3 panel
            // lesson). H1 gated it because nothing on the bar could fire.
            releaseRender = { paper.releaseRender() },
            // Insert is a command, not a tool: the armed tool is untouched and what lands, lands
            // selected (D4).
            onInsert = { kind ->
                hideInsertBar()
                dismissCollapsed()   // arc 36: the bar may hang off the mini toolbar
                val shape = InsertBar.shapeType(kind)
                when {
                    shape != null -> shapeFlow.insertAtCentre(shape)
                    kind == InsertBar.Kind.TEXT -> textFlow.insertAtCentre()
                    kind == InsertBar.Kind.STICKY -> stickyFlow.insertAtCentre()
                    kind == InsertBar.Kind.BIBLE -> bibleRefFlow.insertAtCentre()
                }
            },
        )
        // The arc-28 eight in every build since H5 — Text (H2), the six shapes (H4), Sticky (H5).
        // Bible (arc 38 / R3) is the one that comes and goes: it is offered from `BibleEntry`'s own
        // discovery (the `onAvailabilityChanged` above), which runs in onCreate and on every resume.
        InsertBar.Kind.entries.forEach { insertBar.offer(it, it != InsertBar.Kind.BIBLE) }
        // The eraser's sub-bar (arc 29 / LE2, D3) — the Insert bar's recipe, hung under the eraser
        // button instead, and opened only by that button's own re-tap (there is no third top-bar
        // slot: a twelfth 62 dp button falls off the Nomad's edge). A pick arms the tool inside
        // [EraserBar] and lands here, where the bar comes down and the toolbar is told — a
        // host-set tool is never echoed back as `onToolChanged`.
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { tool -> hideEraserBar(); toolbar.arm(tool) },
        )
        binding.btnInsert.setOnClickListener {
            if (!opened || closing) return@setOnClickListener
            if (insertBar.isShowing) hideInsertBar() else showInsertBar()
        }
        TooltipCompat.setTooltipText(binding.btnInsert, binding.btnInsert.contentDescription)
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

        // Arc 33: both bars hide and show together on a finger double-tap. The button-anchored
        // popups go down first — their button is about to go — while the floating bars a lasso
        // raises (selection toolbar, transform bar) keep working over bare paper. Applied from the
        // persisted flag before the first layout, so a screen opened hidden never shows its bars.
        chromePrefs = ChromePrefs(this)
        // Arc 36: while hidden, the bars collapse to a corner tool button with a mini toolbar under
        // it — the tools, Insert, and an overflow row of Back and every door. Entries mirror the
        // bar buttons they stand for (visibility, glyph, click), so a door absent from the bar is
        // absent here and the bar's own handler runs. Insert and Tags hang their sub-bars under
        // the mini toolbar's own buttons — the bar's are inside a GONE bar with stale edges.
        collapsed = CollapsedChrome(
            root = binding.root,
            knob = binding.collapsedKnob,
            miniBar = binding.collapsedBar,
            overflowBar = binding.collapsedOverflow,
            paper = paper,
            bandBottom = { chromeBand()?.last },
            canOpen = { opened && !closing && canvasShown },
            commands = listOf(
                // Insert hangs its bar under the mini toolbar's own button; the overflow row
                // shares that edge, so it goes first (two rows under one bar would overlap).
                CollapsedChrome.Entry.mirroring(R.drawable.ic_plus, binding.btnInsert) { anchor ->
                    if (insertBar.isShowing) { hideInsertBar(); return@mirroring }
                    collapsed.hideOverflow()
                    showInsertBar(anchor)
                },
            ),
            overflow = listOf(
                CollapsedChrome.Entry.mirroring(R.drawable.ic_arrow_left, binding.btnBack),
                CollapsedChrome.Entry.mirroring(R.drawable.ic_list, binding.btnContents),
                CollapsedChrome.Entry.mirroring(R.drawable.ic_file_text, binding.btnDocument),
                CollapsedChrome.Entry.mirroring(R.drawable.ic_tag, binding.btnTags) { anchor ->
                    if (tagsPopup.isShowing) hideTagsPopup() else showTagsPopup(anchor)
                },
                CollapsedChrome.Entry.mirroring(R.drawable.ic_clock, binding.btnRecents),
                // The Bible's bar button is on the bottom strip, but a door is a door: mirrored
                // here before Calendar so the pad stays last (arc 37 / B0).
                CollapsedChrome.Entry.mirroring(R.drawable.ic_bible, binding.btnBible),
                CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar, binding.btnCalendar),
                CollapsedChrome.Entry.mirroring(R.drawable.ic_sketching, binding.btnScratchPad),
            ),
            onOpen = { endTransformIfRunning(); hideLassoPopup(); hideTagsPopup(); hideInsertBar(); hideEraserBar() },
            // The two sub-bars hung off the rows go with them, by every path a row can close —
            // raw hides: the one exclusion push follows in `onChanged`.
            onClose = { insertBar.hide(); tagsPopup.hide() },
            onArmed = { toolbar.arm(it) },
            onChanged = ::pushExclusions,
        )
        chromeToggle = ChromeToggle(
            paper = paper,
            root = binding.root,
            bars = listOf(binding.topBar, binding.bottomStrip),
            // The corner button is about to appear: it is made honest here, where the tool it must
            // wear is whatever the bar last armed — a host-set tool (a bar tap, an eraser sub-bar
            // pick) is never echoed back as `onToolChanged` (arc 36 / C2).
            beforeHide = { hideLassoPopup(); hideTagsPopup(); hideInsertBar(); hideEraserBar() },
            afterLayout = ::pushExclusions,
            onChanged = { chromePrefs.hidden = it },
            whileHidden = listOf(binding.collapsedKnob),
            beforeShow = { dismissCollapsed() },
        )
        chromeToggle.apply(chromePrefs.hidden, releaseRender = false)

        followFlow = LinkFollowFlow(
            activity = this,
            session = { session },
            displayedPageId = { displayedPageId },
            liveLinks = { liveLinks.values },
            alive = { opened && !closing },
            navigateToPage = { pageId -> runPageOp { refreshToPage(pageId) } },
            closeAndLaunch = { target -> close { startActivity(target) } },
            // The dead-target dialog's "Edit link" goes wherever this link's own Edit goes: a Bible
            // link is retargeted in the reference dialog, never in the page picker.
            editLink = { link -> editLinkTarget(link) },
            // Arc 38 / R3: true when the showing was asked for, false when no reader that
            // understands references is installed — which is the dead-target dialog's other body.
            openBible = { reference ->
                if (::bible.isInitialized && bible.supportsReferences) {
                    bible.open(reference)
                    true
                } else {
                    false
                }
            },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backPressed() }
        })

        // On the surface stack from here (arc 32 / RS1): a cold restore must reopen this notebook
        // the same way it was open — losing the via-link flag would clear the persisted trail and
        // take the walk-back with it. Popped at every close, never from onDestroy.
        stack.attach(SurfaceEntry(stackToken, Surface.NOTEBOOK, notebookId, viaLink))
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
            val resolved = keyFor(alive) ?: return   // cancelled prompt: leave quietly
            when (val r = session.open(resolved)) {
                is NotebookSession.OpenResult.Failed -> {
                    // The key's fault, once per launch (arc 26 / U6): the recovery dialog — a
                    // passphrase the person holds must always be enough. A RETRY re-runs this whole
                    // open (the file may have been re-keyed, or a passphrase parked); the latch
                    // makes the second pass the last, so a still-failing file never loops.
                    if (NotebookRecovery.Plan.shouldOffer(r.keyed, KeyFailure.isKeyFailure(r.cause), recoveryAttempted)) {
                        recoveryAttempted = true
                        Log.w(TAG, "open failed on the key for $notebookId — offering recovery")
                        binding.openingOverlay.root.visibility = View.GONE
                        val outcome = NotebookRecovery.offer(this, notebookId, alive.name, keyScope)
                        if (isFinishing || closing) return
                        if (outcome == NotebookRecovery.Outcome.RETRY) {
                            binding.openingOverlay.root.visibility = View.VISIBLE
                            openSession()
                            return
                        }
                        // Declined: the dialog already explained, so leave quietly — and never
                        // restore into a notebook that would not open.
                        Slog.d(TAG) { "recovery declined — leaving" }
                        stack.pop(stackToken)
                        finish()
                        return
                    }
                    // A NOTEBOOK-scope open that failed *after* the prompt verified the passphrase
                    // is a real failure, not a locked file — but the reason is a crypto message, so
                    // the words the person reads are ours. Structural reasons keep their own.
                    failOpen(
                        if (r.keyed && keyScope == KeyScope.NOTEBOOK) getString(R.string.notebook_open_locked_body)
                        else r.reason
                    )
                    return
                }
                NotebookSession.OpenResult.Ok -> Unit
            }
            if (isFinishing || closing) { sealAbandonedOpen(); return }
            // Arc 42 "Notes": the open's one blob-free read — whether this notebook holds a Bible
            // link at all, which is what keeps an ordinary notebook from ever binding the reader.
            // Fire-and-forget, off Main, a failure swallowed (the Rebuild door is the answer).
            lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) { noteSync.prime() } } }
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
            markClipboard(SnClipboard.hasObjects)
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
     * The key this notebook opens under (arc 26 / U4), decided once per open from the index row's
     * scope — and the one place this screen may prompt for a passphrase.
     *
     * `GLOBAL`: [SoilDatabase.resolve], which also carries a mid-rotation second candidate.
     * `NOTEBOOK`: [NotebookPassphrasePrompt], on **every** open (decision 12) — the box comes down
     * while the dialog is up (it shields every touch under it and a dialog behind a grey pane reads
     * as a hang) and goes back up for the open that follows. A cancelled prompt is not an error:
     * null answers, the screen leaves quietly with no dialog, and the library's last-open pointer
     * is cleared exactly as [failOpen] clears it — a cancelled open must not be restored into on
     * the next cold launch.
     *
     * The typed passphrase is returned inside the [KeyResolver.Resolved] and goes straight into the
     * open; it is never held in a field, logged, or put in an Intent.
     */
    private suspend fun keyFor(alive: ObjectSummary): KeyResolver.Resolved? {
        val scope = KeyScope.of(alive.keyScope)
        keyScope = scope
        if (scope == KeyScope.GLOBAL) return SoilDatabase.resolve(this, notebookId)
        // The one door that takes a parked hand-off (create / import / scope change / link follow):
        // a silent first open right after the passphrase was typed elsewhere, then every open asks.
        NotebookPassphrasePrompt.takeParked(this, notebookId)?.let { return KeyResolver.Resolved.Passphrases(it) }
        binding.openingOverlay.root.visibility = View.GONE
        val typed = NotebookPassphrasePrompt.ask(this, notebookId, alive.name)
        if (typed == null) {
            Slog.d(TAG) { "open cancelled at the passphrase prompt" }
            stack.pop(stackToken)
            finish()
            return null
        }
        if (!isFinishing && !isDestroyed) binding.openingOverlay.root.visibility = View.VISIBLE
        return KeyResolver.Resolved.Passphrases(typed)
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
        val links = correctedForDevice(session.links.loadPage(page.id), page.width)
        // Arc 28: the page's texts, shapes and sticky icons, read here with everything else — the
        // texts already re-measured for this device (the heading remeasure's rule, PageObjects).
        val objects = pageObjects.load(session, page.id, page.width)
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
        pageObjects.set(objects)
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
        // Last, and only here: the chain above this notebook goes back up **after** `opened` is
        // true, after the "Opening…" box is down, and therefore after the own-key prompt has
        // succeeded — the same gate every extension button reads. Consume-once makes the deferred
        // path's second run of this tail a no-op.
        replayAbove()
    }

    /**
     * Put the extension screen that stood above this notebook back on top of it (arc 32 / RS2) —
     * the chain the library handed down in `EXTRA_RESUME_ABOVE`, raised through the **entries**,
     * never an Intent of our own: an extension screen refuses any caller that is not a
     * `startActivityForResult` from the host, and each one needs a store lease, a held bind and a
     * `begin()` before its Intent means anything. So the reopen is exactly the tap the user would
     * have made, `beforeLaunch`'s `releaseForHandoff` and the entry's own box included.
     *
     * **Consumed once**, on the way in: [loadCanvas] can run twice (the text-document route defers
     * it), and the second run must not raise a second screen. Nothing is retried and nothing is
     * queued — a screen this launch could not put back is simply gone, and the button for it is
     * one tap away.
     *
     * Each reopen **awaits its entry's own discovery** rather than reading `isAvailable`: the
     * `onResume` refresh and this replay are two coroutines whose finishing order is a race. An
     * extension that is not installed, not trusted, or below its floor is one log line naming the
     * surface — never an id — and the chain ends there.
     */
    private fun replayAbove() {
        val above = resumeAbove
        resumeAbove = emptyList()
        if (above.isEmpty() || !opened || closing) return
        Slog.d(TAG) { "restore: reopening $above above the notebook" }
        lifecycleScope.launch {
            if (above == PAD_OVER_CALENDAR) {
                // The calendar's pad door: the pad on top, the calendar latched behind it.
                if (!calendar.discovered()) {
                    Slog.d(TAG) { "restore: the calendar is not installed — chain above dropped" }
                    return@launch
                }
                if (!standingForReplay()) return@launch
                if (!scratchPad.discovered()) {
                    Slog.d(TAG) { "restore: the scratch pad is not installed — the calendar comes back alone" }
                    if (standingForReplay()) calendar.open()
                    return@launch
                }
                if (!standingForReplay()) return@launch
                openPadOverCalendar()
                return@launch
            }
            when (above.firstOrNull()) {
                Surface.CALENDAR -> {
                    if (!calendar.discovered()) {
                        Slog.d(TAG) { "restore: the calendar is not installed — dropped" }
                        return@launch
                    }
                    if (standingForReplay()) calendar.open()
                }
                Surface.SCRATCH_PAD -> {
                    if (!scratchPad.discovered()) {
                        Slog.d(TAG) { "restore: the scratch pad is not installed — dropped" }
                        return@launch
                    }
                    if (standingForReplay()) scratchPad.open()
                }
                Surface.BIBLE -> {
                    if (!bible.discovered()) {
                        Slog.d(TAG) { "restore: the bible reader is not installed — dropped" }
                        return@launch
                    }
                    if (standingForReplay()) bible.open()
                }
                Surface.DOCUMENT_EDITOR -> {
                    bible.discovered() // the editor's Bible item is decided at the launch (arc 39)
                    if (!documentEntry.discovered()) {
                        Slog.d(TAG) { "restore: the document editor is not installed — dropped" }
                        return@launch
                    }
                    if (!standingForReplay()) return@launch
                    // Decision 4: the editor is reopened **directly** — no seed flow, no
                    // recognition, nothing staged. So it is only reopened where there is something
                    // to open: the page the notebook landed on has to have a document row.
                    val pageId = displayedPageId
                    if (pageId.isEmpty()) return@launch
                    val documented = withContext(Dispatchers.IO) {
                        try {
                            session.documents.get(pageId) != null
                        } catch (e: Exception) {
                            Slog.d(TAG) { "restore: document read failed: ${e.javaClass.simpleName}" }
                            false
                        }
                    }
                    if (!standingForReplay()) return@launch
                    if (!documented) {
                        Slog.d(TAG) { "restore: the page has no document — the editor is not reopened" }
                        return@launch
                    }
                    documentEntry.open()
                }
                else -> Unit
            }
        }
    }

    /** Still worth raising a screen over: the same `opened && !closing` gate every extension button
     *  reads, plus the two Activity flags, re-asked after every suspension in [replayAbove]. */
    private fun standingForReplay(): Boolean = opened && !closing && !isFinishing && !isDestroyed

    /**
     * Arc 39 "Lookup" — the editor's selection, arriving on a **Binder thread** through
     * [DocumentHostHooks]: ask the reader to resolve it (bind-per-call, off any screen) and park
     * the answer for the host's lookup screen ([LookupHandoff] → `BibleLookupActivity`), which the
     * editor starts next. **This screen launches nothing**: it is stopped behind the editor, and a
     * child's result would not reach it until the editor closed — the stale-latch trap the lookup
     * screen exists for. `runBlocking` here is the allowed case (a Binder thread, never the UI
     * thread); the editor holds a wait dialog up for the call's life. Answers a [DocumentContract]
     * `REFERENCE_*` code; the text is never logged.
     *
     * `resolve`'s null covers "not a reference" and "could not ask" alike (arc 38's one answer),
     * so a reader that failed to answer reads as unknown here — the honest alternative would be a
     * second call, and the failure is logged by the client either way.
     */
    private fun lookupBibleReference(text: String): Int = runBlocking {
        if (!opened || closing || !::bible.isInitialized || !bible.supportsReferences) {
            return@runBlocking DocumentContract.REFERENCE_UNAVAILABLE
        }
        val reader = bible.provider ?: return@runBlocking DocumentContract.REFERENCE_UNAVAILABLE
        val editor = documentEntry.providerPackage ?: return@runBlocking DocumentContract.REFERENCE_UNAVAILABLE
        val resolved = bible.resolve(text) ?: return@runBlocking DocumentContract.REFERENCE_UNKNOWN
        LookupHandoff.park(reader, resolved.wire, editor)
        // Arc 42 "Notes" (decision 3): a Lookup is the one thing that puts a *document* in the
        // index — nothing scans document text. The editor's own target says which document it
        // is; the notebook document and a text document have no page, and go in with no ordinal.
        // Fire-and-forget on the application scope: never block this Binder thread on a push.
        val onPage =
            if (documentHooks.scopeIsNotebook || session.isTextDocument) ""
            else documentHooks.targetPageId ?: displayedPageId
        val ordinal = if (onPage.isEmpty()) 0 else ordinalOf(onPage)
        val wire = resolved.wire
        appScope.launch {
            BibleNoteIndex.noteDocument(applicationContext, notebookId, notebookName, onPage, ordinal, wire)
        }
        DocumentContract.REFERENCE_OPENED
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
        if (launch) {
            // A text document opens *into* the editor by itself, so a restored DOCUMENT_EDITOR
            // entry is consumed silently here — reopening it would be a second launch over the one
            // this route is already making. Anything else above a text document has no page to
            // stand on and is dropped (arc 32 / RS2). The reconnect arm leaves the list alone: a
            // recreate never carried one.
            val above = resumeAbove
            resumeAbove = emptyList()
            if (above.isNotEmpty() && above != listOf(Surface.DOCUMENT_EDITOR)) {
                Slog.d(TAG) { "restore: $above above a text document dropped — it reopens into its editor" }
            }
        }
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
        // The editor's Bible item (arc 39) is decided at this launch from the reader's discovery,
        // which onResume only fires and forgets — a cold launch straight into a text document
        // would otherwise race it and open the editor without the item. A package query, cheap.
        bible.discovered()
        if (!opened || closing) return
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
        appScope.launch {
            withContext(NonCancellable) {
                // Arc 42: whatever was marked goes out before the connection does — a structural
                // flush reads the rows through it.
                runCatching { noteSync.flushBeforeSeal() }
                runCatching { s.seal() }
            }
        }
    }

    /** A tap that opened nothing must be explained, not toasted (e-ink rule) — dialog, then leave. */
    private fun failOpen(reason: String) {
        Log.w(TAG, "open failed: $reason")
        // The box must come down before the dialog goes up — it shields every touch under it, and
        // an OK button that cannot be tapped is a dead screen.
        binding.openingOverlay.root.visibility = View.GONE
        stack.pop(stackToken)
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
                // Arc 42: the index carries the name it lists this notebook under. Fire-and-forget
                // on the application scope — this is a Binder thread and must not wait on it.
                appScope.launch { BibleNoteIndex.rename(applicationContext, notebookId, name) }
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
                if (move.dx != 0f) remeasureMovedLinks(linkIds)
                linkRenderer.update(liveLinks.values.toList())
            }
            // Arc 28: texts, shapes and stickies that rode the same drag. Each store's move is a
            // delta on the same two columns; a sticky's content is local to the note and does not
            // move at all, which is why its icon's row is the whole of the write.
            val objs = pageObjects.split(move.contentIds)
            if (!objs.isEmpty) {
                session.texts.move(objs.textIds, move.dx, move.dy)
                session.shapes.move(objs.shapeIds, move.dx, move.dy)
                session.stickies.move(objs.stickyIds, move.dx, move.dy)
                pageObjects.translate(objs, move.dx, move.dy)
            }
            // One drag is one re-record, whatever kinds rode along.
            if (headingIds.isNotEmpty() || linkIds.isNotEmpty() || !objs.isEmpty) paper.notifyContentChanged()
            undo.record(
                Action.Moved(
                    pageId, ids, move.dx, move.dy, headingIds, linkIds,
                    objs.textIds, objs.shapeIds, objs.stickyIds,
                )
            )
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
         * A sub-threshold tap inside the selection box: on a selected heading — or, since arc 28 /
         * H2, on a lone selected text object — it opens that object's edit dialog (the one
         * tap-to-edit path). The engine already palm-gated and escrowed the tap, and the callback
         * is pen-only. A heading is looked for anywhere in the set (a mixed selection can hold
         * one); a text opens only when it is the *whole* selection ([SelectionMode.TEXT]), because
         * a tap inside a mixed box is not a request to edit one of the things in it.
         */
        override fun onSelectionTapped(x: Float, y: Float) {
            if (!opened) return
            val sel = currentSelection ?: return
            val h = sel.contentIds.asSequence().mapNotNull { liveHeadings[it] }
                .firstOrNull { it.bounds.contains(x, y) }
            // Ungated for the SelectionToolbar-button reason: the tap has to show its result and
            // the dialog repaints over the page; there is no live stroke at a tap's pen-up.
            if (h != null) {
                paper.releaseRender()
                HeadingEditDialog.show(this@NotebookActivity, h) { raw -> applyHeadingEdit(h.id, raw) }
                return
            }
            if (sel.strokeIds.isNotEmpty() || sel.contentIds.size != 1) return
            val t = pageObjects.texts[sel.contentIds.first()] ?: return
            if (!t.bounds.contains(x, y)) return
            paper.releaseRender()
            textFlow.edit(t)
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
            val removed = removeContent(contentIds) ?: return
            paper.notifyContentChanged()
            // One sweep is one entry. A link's restore needs its full snapshot (row + wrapped
            // children), so anything with a link in it is recorded as a Deleted covering both
            // kinds rather than two entries the user would have to undo twice — and since arc 28
            // the same is true of a text, a shape or a sticky.
            recordWithStickies(removed.stickies, removed.links) { stickies, links ->
                eraseEntry(pageId, emptyList(), removed, stickies, links, EraseKind.ERASER)
            }
            Slog.d(TAG) { "eraser removed ${removed.summary()}" }
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
            val removed = removeContent(contentIds) ?: Removed.NONE
            if (strokes.isEmpty() && removed.isEmpty) return
            recordWithStickies(removed.stickies, removed.links) { stickies, links ->
                eraseEntry(pageId, strokes, removed, stickies, links, EraseKind.SCRIBBLE)
            }
            Slog.d(TAG) { "scribble removed ${strokes.size} strokes, ${removed.summary()}" }
        }
        /**
         * A lasso-erase gesture (arc 29 / LE2, g-paper 0.1.28): one closed outline took everything
         * it holds. The body is [onScribbleErased]'s exactly — one gesture, one callback, **one
         * undo entry** — and for the same reason: a loop that swallowed a line of ink and the
         * heading above it must not cost the user two undos.
         *
         * What differs from a scribble is only the *reach*: the hit rule is the **lasso's own**
         * (D4) — a stroke goes if any point lies inside the loop, a heading / link / text / shape /
         * sticky goes whole if the loop touches its box — so lasso-erase and select-then-Delete
         * always agree about what a loop holds. Per kind the semantics are the eraser tool's:
         * whole strokes, whole objects, a link with its wrapped children.
         *
         * **No repaint from here** (the arc-14 rule): the engine drops the strokes from its own
         * model and re-records the moment this returns, and [removeContent] already asked for the
         * one frame the content needs. A second would be a second EPD refresh whose first half
         * would show the ink gone and the heading still standing.
         *
         * Either list may be empty, never both — an outline that took nothing reports nothing.
         */
        override fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) {
            if (!opened) return
            val pageId = displayedPageId
            // The mirror is the only place the geometry still exists once the engine drops it.
            val strokes = strokeIds.mapNotNull { liveStrokes.remove(it) }
            if (strokeIds.isNotEmpty()) session.store.erase(strokeIds)
            val removed = removeContent(contentIds) ?: Removed.NONE
            if (strokes.isEmpty() && removed.isEmpty) return
            recordWithStickies(removed.stickies, removed.links) { stickies, links ->
                eraseEntry(pageId, strokes, removed, stickies, links, EraseKind.LASSO)
            }
            Slog.d(TAG) { "lasso erased ${strokes.size} strokes, ${removed.summary()}" }
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
            val successor = pendingSelection
            pendingSelection = null
            if (successor != null) {
                // Arc 40 walk finding: an insert-landed selection acted on by its own bar (Verses
                // on a just-sent reference) is dismissed with a successor, and the transfer-paste
                // latch would have put PEN back under it — the successor sat unselectable under a
                // pen. The latch is *kept*, not cleared: the tool goes back when the successor is
                // itself dismissed, which is what the insert promised in the first place.
                successor()
                return
            }
            restoreToolAfterTransferPaste()
        }
        /**
         * The transform mode's live box (arc 28 / H4, g-paper 0.1.27): the working copy follows it
         * and **nothing else happens** — no store write, and above all no `notifyContentChanged()`,
         * because the engine repaints the transform layer through `ShapeRenderer.drawObject` the
         * moment this returns. A frame per sample would be an EPD refresh per sample.
         */
        override fun onTransformChanged(contentId: String, box: OrientedBox) {
            if (!opened) return
            shapeFlow.onTransformChanged(contentId, box)
        }
        /**
         * The mode's **one** teardown — it fires on every exit, the bar's own Done included, so
         * the persist, the undo entry and the chrome all live on the other side of this one call
         * ([ShapeFlow.onTransformEnded]). Ungated on `opened`: a close or a page swap ends the
         * mode by hand precisely so this runs and the geometry is not dropped.
         */
        override fun onTransformEnded(contentId: String, before: OrientedBox, after: OrientedBox) {
            shapeFlow.onTransformEnded(contentId, before, after)
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
    private fun removeContent(contentIds: List<String>): Removed? {
        val headingIds = contentIds.filter { liveHeadings.containsKey(it) }
        val links = contentIds.mapNotNull { liveLinks[it] }
        val objs = pageObjects.split(contentIds)
        if (headingIds.isEmpty() && links.isEmpty() && objs.isEmpty) return null
        if (headingIds.isNotEmpty()) {
            session.headings.erase(headingIds)
            headingIds.forEach { liveHeadings.remove(it) }
            headingRenderer.headings = liveHeadings.values.toList()
        }
        if (links.isNotEmpty()) {
            // A link wrapping a sticky goes down in [recordWithStickies] instead — queued there on
            // the spot too, by a job that reads the note's content ahead of its own delete.
            session.links.remove(links.filter { it.stickies.isEmpty() })
            links.forEach { liveLinks.remove(it.id) }
            linkRenderer.update(liveLinks.values.toList())
            markNotes()   // arc 42: the page's links, as the erase left them
        }
        val stickies = pageObjects.stickiesIn(objs.stickyIds)
        if (!objs.isEmpty) {
            // Texts and shapes go down here, with everything else. **The sticky rows do not** —
            // see [recordWithStickies]: their content has to be read before it is deleted (the
            // undo snapshot), which the store does inside the delete's own job, queued there on
            // the spot. What happens here is the whole of what the eye sees.
            session.texts.erase(objs.textIds)
            session.shapes.erase(objs.shapeIds)
            pageObjects.drop(objs)
        }
        // Wrapped headings are out of the outline while wrapped (their parent is the link, not
        // the page), so erasing a link that holds one changes the Contents just as a loose one does.
        if (headingIds.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
        return Removed(headingIds, links, objs.textIds, objs.shapeIds, stickies)
    }

    /**
     * What one erase, scribble or Delete took off the page besides ink — the shape every one of
     * those undo entries is built from. [stickies] are **icons**: their content is read, and their
     * rows deleted, by [recordWithStickies].
     */
    private class Removed(
        val headingIds: List<String>,
        val links: List<PageLink>,
        val textIds: List<String>,
        val shapeIds: List<String>,
        val stickies: List<PageSticky>,
    ) {
        val isEmpty: Boolean get() =
            headingIds.isEmpty() && links.isEmpty() && textIds.isEmpty() &&
                shapeIds.isEmpty() && stickies.isEmpty()

        fun summary(): String =
            "${headingIds.size} headings, ${links.size} links, ${textIds.size} texts, " +
                "${shapeIds.size} shapes, ${stickies.size} stickies"

        companion object { val NONE = Removed(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    /**
     * Delete the sticky half of an erase and record the one entry the whole act deserves.
     *
     * A sticky's undo snapshot has to carry its **content** (`StickyStore.restore` revives the
     * snapshot's `childIds`, and an icon alone would come back as an empty note), and that read
     * suspends — which a g-paper callback does not. So the sticky rows (and a link wrapping one —
     * `LinkStore.remove` takes the note's content down with the link, and `LinkStore.restore` can
     * only revive what the snapshot names) go through `removeWithContent`: **the delete is queued
     * on the spot, in writer order**, and the job reads the content ahead of its own soft-delete
     * and hands the snapshot back. Only the *record* waits for it. It is still **one gesture, one
     * entry** — the entry is simply recorded a beat later, and nothing repaints from here either
     * way (the page already lost the icon in the caller's own frame).
     *
     * Before arc 34 / M6 the delete itself sat inside a page op with a drain and the reads ahead
     * of it — and a page op is skipped under `closing`, so an erase while another op held the
     * mutex, followed by Back, never deleted the rows and the sticky came back on the next open.
     * Nothing here goes through [runPageOp] any more; the record needs no lock.
     *
     * With no sticky in the act nothing is deferred at all: [entry] is recorded on the spot, which
     * is every erase and every delete in the app until H5 puts a sticky on a page.
     */
    private fun recordWithStickies(
        icons: List<PageSticky>,
        links: List<PageLink>,
        entry: (stickies: List<PageSticky>, links: List<PageLink>) -> Action,
    ) {
        val wrapping = links.filter { it.stickies.isNotEmpty() }
        if (icons.isEmpty() && wrapping.isEmpty()) { undo.record(entry(emptyList(), links)); return }
        // Both queued now, before any suspension — a Back tap after this line cannot un-queue them.
        val stickySnapshot = session.stickies.removeWithContent(icons)
        val linkSnapshot = session.links.removeWithContent(wrapping)
        lifecycleScope.launch {
            // A cancelled deferred (writer closed) ends this quietly: no delete ran, so no entry.
            val full = stickySnapshot.await()
            val fullWrapping = linkSnapshot.await().associateBy { it.id }
            val fullLinks = links.map { l -> fullWrapping[l.id] ?: l }
            undo.record(entry(full, fullLinks))
        }
    }

    /** Which of the three erases is asking for an entry (arc 29 / LE2 — the boolean the scribble
     *  introduced could not name a third). The kinds replay identically; they stay apart so a
     *  future undo *label* can say which act it is reversing. */
    private enum class EraseKind { ERASER, SCRIBBLE, LASSO }

    /**
     * The kind an erase gets: a scribble and a lasso erase are each always their own kind, and an
     * eraser sweep that took nothing but headings keeps the narrower [Action.HeadingDeleted] it has
     * always had (its rows revive in place from ids alone). Everything else is a [Action.Deleted]
     * covering every kind at once — one sweep the user undoes with one tap.
     *
     * The headings-only narrowing belongs to the **eraser** alone: it is the sweep whose rows are
     * ids and nothing else, and neither of the two gesture kinds gives up its own name for it.
     */
    private fun eraseEntry(
        pageId: String,
        strokes: List<Stroke>,
        r: Removed,
        stickies: List<PageSticky>,
        /** [Removed.links], with any wrapped sticky's content read in ([recordWithStickies]). */
        links: List<PageLink>,
        kind: EraseKind,
    ): Action = when {
        kind == EraseKind.SCRIBBLE -> Action.ScribbleErased(
            pageId, strokes, r.headingIds, links, r.textIds, r.shapeIds, stickies,
        )
        kind == EraseKind.LASSO -> Action.LassoErased(
            pageId, strokes, r.headingIds, links, r.textIds, r.shapeIds, stickies,
        )
        strokes.isEmpty() && links.isEmpty() && r.textIds.isEmpty() &&
            r.shapeIds.isEmpty() && stickies.isEmpty() ->
            Action.HeadingDeleted(pageId, r.headingIds)
        else -> Action.Deleted(
            pageId, strokes, r.headingIds, links, r.textIds, r.shapeIds, stickies,
        )
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
            val px = x - loc[0]; val py = y - loc[1]
            // Stickies before links (D2): the icons sit above the links in the draw order, so a
            // note dropped over a link is what the finger is on.
            val hit = stickyFlow.openAt(px, py) || followFlow.followAt(px, py)
            // Arc 33: remembered for the double-tap that may follow — both taps of a pair reach
            // here before the double fires, so the rule below always sees what they landed on.
            doubleTapRule.tapped(hit)
        }
        override fun onFingerDoubleTap(x: Float, y: Float) {
            if (!doubleTapRule.shouldToggle()) return
            toggleChrome()
        }
    }

    /**
     * The chrome toggle (arc 33): every bar goes / comes back on a finger double-tap that hit no
     * sticky and no link, and the flag is persisted for every other paper screen. Only a screen
     * that is open and not closing flips — the gesture cannot even arm before the page lands, but
     * the escrow can deliver a pair across a close.
     */
    private fun toggleChrome() {
        if (!opened || closing) return
        chromeToggle.toggle()
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
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (t: Throwable) {
                    // The `:ext-ink` screens' shape, over the pure PageOpFailure table (arc 34 / M7):
                    // a cancellation is rethrown, a store failure is a dialog, the rest is a log.
                    when (PageOpFailure.classify(t)) {
                        PageOpFailure.Outcome.RETHROW -> throw t
                        PageOpFailure.Outcome.DIALOG -> {
                            Log.w(TAG, "page op failed: store", t)
                            if (!isFinishing && !isDestroyed) {
                                Dialogs.problem(this@NotebookActivity, R.string.page_op_failed_title, R.string.page_op_failed_body)
                            }
                        }
                        PageOpFailure.Outcome.LOG -> Log.w(TAG, "page op failed", t)
                    }
                }
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
        // An in-flight transform is ended by hand FIRST — before the row reads below. `loadStrokes`
        // would end it too, but only after those reads, so a refresh onto the very page the mode
        // began on would paint the geometry it started from. The drain is what makes the read
        // downstream of the write the exit just enqueued; `release` being silent is why the mode is
        // ended at all rather than left to die with the swap.
        if (paper.transformingContentId != null) {
            endTransformIfRunning()
            session.store.drain()
        }
        val targetId = session.pages[index.coerceIn(0, session.pages.lastIndex)].id
        val lateCommits = mutableListOf<Stroke>()
        loadingCommits = targetId to lateCommits
        val page: PageRef
        val strokes: List<Stroke>
        val headings: List<Heading>
        val links: List<PageLink>
        val objects: PageObjects.Loaded
        val linkBitmaps: Map<String, android.graphics.Bitmap>
        try {
            page = session.goTo(index)
            strokes = session.store.loadPage(page.id)
            headings = remeasureForDevice(session.headings.loadPage(page.id))
            links = correctedForDevice(session.links.loadPage(page.id), page.width)
            objects = pageObjects.load(session, page.id, page.width)
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
        hideInsertBar()           // whatever it would place belongs to the page being left
        hideEraserBar()           // and the eraser's own sub-bar goes with every other floating bar
        dismissCollapsed()        // arc 36: the mini toolbar's rows too
        paper.clearForContentSwap()
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        // Renderers before loadStrokes: the swap's single re-record paints the new page's headings
        // and links — the composites have to be built by then (the hover-repaint trap).
        liveHeadings = headings.associateByTo(linkedMapOf()) { it.id }
        headingRenderer.headings = headings
        liveLinks = links.associateByTo(linkedMapOf()) { it.id }
        linkRenderer.update(links, linkBitmaps)
        pageObjects.set(objects)
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
        noteSync.markStructural()   // arc 42: every page's ordinal may have moved
        navigateTo(session.currentIndex)   // put the freshly-inserted blank page on the paper
    }

    /**
     * Erase page (arc 30 / PE1) — the delete's chain with the page row left standing: drain (the
     * queued stroke commits must land before the id snapshot, or a stroke drawn a moment ago is
     * missed and orphaned live under an erased page), erase, record, repaint **once** through
     * [refreshToPage]. An empty page's erase is silent: no transaction, no entry, no repaint.
     */
    private suspend fun doErase() {
        session.store.drain()
        val pageId = session.currentPage.id
        val ids = session.eraseCurrent()
        if (ids.isEmpty()) return
        undo.record(Action.PageErased(pageId, ids))
        refreshToPage(pageId)
        markNotes(pageId)   // arc 42: after the reload — liveLinks is the erased page's now
    }

    private suspend fun doDelete() {
        // Drain first: a stroke commit still queued on the writer would otherwise land AFTER the
        // delete's liveContentIds snapshot and transaction — a permanently live orphan row under a
        // soft-deleted page, invisible to the recorded snapshot and to redo's reconcile.
        session.store.drain()
        val snap = session.deleteCurrent()
        undo.record(Action.Page(snap))
        noteSync.markStructural()   // arc 42
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
        markReplayed(a)
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
        markReplayed(a)
        undo.pushUndo(a)
    }

    /**
     * Arc 42 "Notes": what one replayed [a] owes the index — the page kinds push the whole
     * notebook (every ordinal may have moved), the link-touching kinds push their own page, and
     * everything else pushes nothing. Called **after** the replay, which ends in
     * `refreshToPage` → `navigateTo` and therefore with [liveLinks] already reloaded from the
     * rows; a replay onto a page that is no longer displayed is left to that page's next load.
     */
    private fun markReplayed(a: Action) {
        if (BibleNoteIndex.isStructural(a)) noteSync.markStructural()
        else if (BibleNoteIndex.mayTouchLinks(a) && a.pageId == displayedPageId) markNotes(a.pageId)
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
                // Arc 28: texts and shapes revive in place from ids; a sticky needs its snapshot,
                // whose `childIds` are the content rows the restore has to bring back with it.
                session.texts.restore(a.textIds)
                session.shapes.restore(a.shapeIds)
                session.stickies.restore(a.pageId, a.stickies)
                session.store.drain(); refreshToPage(a.pageId)
            }
            // Same replay as Deleted — a different act to the user, the same rows to put back.
            is Action.ScribbleErased -> {
                session.store.revive(a.strokes.map { it.id })
                session.headings.restore(a.headingIds)
                session.links.restore(a.pageId, a.links)
                session.texts.restore(a.textIds)
                session.shapes.restore(a.shapeIds)
                session.stickies.restore(a.pageId, a.stickies)
                session.store.drain(); refreshToPage(a.pageId)
            }
            // And again for the lasso eraser (arc 29 / LE2) — deliberately its own arm rather than
            // folded into the one above with a comma, so the three erase kinds stay as legible here
            // as they are in the action set.
            is Action.LassoErased -> {
                session.store.revive(a.strokes.map { it.id })
                session.headings.restore(a.headingIds)
                session.links.restore(a.pageId, a.links)
                session.texts.restore(a.textIds)
                session.shapes.restore(a.shapeIds)
                session.stickies.restore(a.pageId, a.stickies)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.Moved -> {
                session.store.move(a.ids, -a.dx, -a.dy)
                session.headings.move(a.headingIds, -a.dx, -a.dy)
                session.links.move(a.linkIds, -a.dx, -a.dy)
                session.texts.move(a.textIds, -a.dx, -a.dy)
                session.shapes.move(a.shapeIds, -a.dx, -a.dy)
                // The icon row only: a note's content is local and never moved with it.
                session.stickies.move(a.stickyIds, -a.dx, -a.dy)
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
                session.texts.erase(a.textIds)
                session.shapes.erase(a.shapeIds)
                // remove(), not erase(): a pasted note's content rows go down with its icon.
                session.stickies.remove(a.stickies.map { it.id })
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingTextEdited -> { session.headings.updateContent(a.before); session.store.drain(); refreshToPage(a.pageId) }
            is Action.HeadingLevelChanged -> { session.headings.updateContent(a.before); session.store.drain(); refreshToPage(a.pageId) }
            // Arc 28 — the three new kinds. A text conversion is the heading conversion's shape:
            // the row goes, the ink it consumed comes back IN PLACE (writing order is load-bearing).
            // An insert's strokeIds is empty, so the same arm covers both.
            is Action.TextCreated -> {
                session.texts.erase(listOf(a.text.id))
                session.store.revive(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.TextEdited -> { session.texts.updateContent(a.before); session.store.drain(); refreshToPage(a.pageId) }
            // Arc 38 / R3 — a Bible reference, reversed whole: unwrap (the text goes back to being
            // page content), take the text away, and put the ink it replaced back IN PLACE (writing
            // order is load-bearing). In that order: the unlink's re-parent must find the text row
            // where it left it. An insert's strokeIds is empty, so the same arm covers both.
            is Action.BibleRefCreated -> {
                session.links.unlink(a.pageId, a.link)
                session.texts.erase(listOf(a.text.id))
                session.store.revive(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.BibleRefEdited -> replayBibleRef(a.pageId, a.before)
            is Action.ShapeInserted -> { session.shapes.erase(listOf(a.shape.id)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.ShapeTransformed -> { session.shapes.transform(a.before); session.store.drain(); refreshToPage(a.pageId) }
            // remove(), for the paste's reason: an insert's undo takes the note's children too —
            // there are none yet at an insert, and there may be after a redo/edit/undo round trip.
            is Action.StickyInserted -> { session.stickies.remove(listOf(a.sticky.id)); session.store.drain(); refreshToPage(a.pageId) }
            // setContent makes the list the note's WHOLE content, so either direction is the same
            // call with the other side's strokes.
            is Action.StickyContentEdited -> { session.stickies.setContent(a.stickyId, a.before); session.store.drain(); refreshToPage(a.pageId) }
            // Undo of a wrap IS an unlink; undo of an unlink is a re-wrap in place (K1).
            is Action.LinkCreated -> { session.links.unlink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkUnlinked -> { session.links.relink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkEdited -> { session.links.updatePayload(a.linkId, a.before); session.store.drain(); refreshToPage(a.pageId) }
            is Action.PageErased -> { session.restoreIds(a.objectIds); session.store.drain(); refreshToPage(a.pageId) }
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
            // A received page is a paste the calendar made: the same two lines, its own kind (HV5).
            is Action.PageReceived -> {
                session.reconcile(a.snapshot.before, emptyList(), a.snapshot.objectIds, a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
            }
            // The pair lands and leaves together (arc 35 / HA1): back to the list the FIRST insert
            // saw, every row either insert created soft-deleted.
            is Action.PagesReceived -> {
                session.reconcile(a.first.before, emptyList(), a.objectIds, a.first.beforeCurrentId)
                refreshToPage(session.currentPage.id)
            }
            // No drain: a re-papering writes one page row and never touches the stroke writer.
            is Action.TemplateChanged -> { session.applyTemplate(a.pageId, a.from); refreshToPage(a.pageId) }
        }
    }

    /**
     * One side of an [Action.BibleRefEdited] (arc 38 / R3), written the DB-is-truth way like every
     * other replay: the three rows the edit touched — the wrapped text's content, the link's
     * payload, the link's box — then a reload of the page.
     *
     * The composite cache is dropped first: a reload alone would not shift it, because the link's
     * padded size is usually the same on both sides of a reference edit and that is exactly what
     * [LinkRenderer.update] reuses on.
     */
    private suspend fun replayBibleRef(pageId: String, side: PageLink) {
        side.texts.firstOrNull()?.let { session.texts.updateContent(it) }
        session.links.updatePayload(side.id, side.payload)
        session.links.updateBounds(side.id, side.x, side.y, side.width, side.height)
        linkRenderer.invalidate(side.id)
        session.store.drain()
        refreshToPage(pageId)
    }

    private suspend fun reapply(a: Action) {
        when (a) {
            is Action.Drew -> { session.store.revive(listOf(a.stroke.id)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Erased -> { session.store.remove(a.strokes.map { it.id }); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Deleted -> {
                session.store.remove(a.strokes.map { it.id })
                session.headings.erase(a.headingIds)
                session.links.remove(a.links)
                session.texts.erase(a.textIds)
                session.shapes.erase(a.shapeIds)
                session.stickies.remove(a.stickies.map { it.id })
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.ScribbleErased -> {
                session.store.remove(a.strokes.map { it.id })
                session.headings.erase(a.headingIds)
                session.links.remove(a.links)
                session.texts.erase(a.textIds)
                session.shapes.erase(a.shapeIds)
                session.stickies.remove(a.stickies.map { it.id })
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.LassoErased -> {
                session.store.remove(a.strokes.map { it.id })
                session.headings.erase(a.headingIds)
                session.links.remove(a.links)
                session.texts.erase(a.textIds)
                session.shapes.erase(a.shapeIds)
                session.stickies.remove(a.stickies.map { it.id })
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.Moved -> {
                session.store.move(a.ids, a.dx, a.dy)
                session.headings.move(a.headingIds, a.dx, a.dy)
                session.links.move(a.linkIds, a.dx, a.dy)
                session.texts.move(a.textIds, a.dx, a.dy)
                session.shapes.move(a.shapeIds, a.dx, a.dy)
                session.stickies.move(a.stickyIds, a.dx, a.dy)
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
                session.texts.restore(a.textIds)
                session.shapes.restore(a.shapeIds)
                session.stickies.restore(a.pageId, a.stickies)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.HeadingTextEdited -> { session.headings.updateContent(a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.HeadingLevelChanged -> { session.headings.updateContent(a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.TextCreated -> {
                session.texts.restore(listOf(a.text.id))
                session.store.remove(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.TextEdited -> { session.texts.updateContent(a.after); session.store.drain(); refreshToPage(a.pageId) }
            // The undo run backwards: the text row first (relink re-parents it, so it has to be
            // alive before the wrap goes back on), then the wrap, then the ink out again.
            is Action.BibleRefCreated -> {
                session.texts.restore(listOf(a.text.id))
                session.links.relink(a.pageId, a.link)
                session.store.remove(a.strokeIds)
                session.store.drain(); refreshToPage(a.pageId)
            }
            is Action.BibleRefEdited -> replayBibleRef(a.pageId, a.after)
            is Action.ShapeInserted -> { session.shapes.restore(listOf(a.shape.id)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.ShapeTransformed -> { session.shapes.transform(a.after); session.store.drain(); refreshToPage(a.pageId) }
            // restore(), not a create: the row is soft-deleted, not gone — and if the undo's delete
            // never reached the file, StickyStore.restore re-inserts the snapshot rather than fail.
            is Action.StickyInserted -> { session.stickies.restore(a.pageId, listOf(a.sticky)); session.store.drain(); refreshToPage(a.pageId) }
            is Action.StickyContentEdited -> { session.stickies.setContent(a.stickyId, a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkCreated -> { session.links.relink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkUnlinked -> { session.links.unlink(a.pageId, a.link); session.store.drain(); refreshToPage(a.pageId) }
            is Action.LinkEdited -> { session.links.updatePayload(a.linkId, a.after); session.store.drain(); refreshToPage(a.pageId) }
            is Action.PageErased -> { session.eraseIds(a.objectIds); session.store.drain(); refreshToPage(a.pageId) }
            is Action.Page -> {
                session.reconcile(a.snapshot.after, emptyList(), a.snapshot.objectIds, a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
            is Action.PagePasted -> {
                session.reconcile(a.snapshot.after, a.snapshot.objectIds, emptyList(), a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
            is Action.PageReceived -> {
                session.reconcile(a.snapshot.after, a.snapshot.objectIds, emptyList(), a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
            }
            is Action.PagesReceived -> {
                session.reconcile(a.last.after, a.objectIds, emptyList(), a.last.afterCurrentId)
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
        val objs = pageObjects.split(sel.contentIds)
        if (ids.isEmpty() && headingIds.isEmpty() && links.isEmpty() && objs.isEmpty) return
        val strokes = ids.mapNotNull { liveStrokes[it] }
        val stickyIcons = pageObjects.stickiesIn(objs.stickyIds)
        if (headingIds.isNotEmpty()) {
            session.headings.erase(headingIds)
            headingIds.forEach { liveHeadings.remove(it) }
            headingRenderer.headings = liveHeadings.values.toList()
        }
        if (links.isNotEmpty()) {
            // A link wrapping a sticky goes down with the stickies — see [recordWithStickies].
            session.links.remove(links.filter { it.stickies.isEmpty() })
            links.forEach { liveLinks.remove(it.id) }
            linkRenderer.update(liveLinks.values.toList())
            markNotes(pageId)   // arc 42
        }
        if (!objs.isEmpty) {
            // The sticky rows are deliberately not deleted here — [recordWithStickies] does that,
            // on the spot, by a job that reads the content the undo snapshot needs ahead of it.
            session.texts.erase(objs.textIds)
            session.shapes.erase(objs.shapeIds)
            pageObjects.drop(objs)
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
        if (headingIds.isNotEmpty() || links.isNotEmpty() || !objs.isEmpty) paper.notifyContentChanged()
        // Nothing captured means nothing to put back — record no history rather than a lying entry.
        if (strokes.isNotEmpty() || headingIds.isNotEmpty() || links.isNotEmpty() || !objs.isEmpty) {
            recordWithStickies(stickyIcons, links) { stickies, fullLinks ->
                Action.Deleted(pageId, strokes, headingIds, fullLinks, objs.textIds, objs.shapeIds, stickies)
            }
        } else Log.w(TAG, "selection delete: no geometry for ${ids.size} ids — not undoable")
        if (headingIds.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
        Slog.d(TAG) {
            "selection delete: ${strokes.size} strokes, ${headingIds.size} headings, " +
                "${links.size} links, ${objs.textIds.size} texts, ${objs.shapeIds.size} shapes, " +
                "${stickyIcons.size} stickies"
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
     * Every in-memory correction a loaded link gets: its wrapped texts re-measured for THIS
     * device at `pageWidth − x` ([PageLink.withTextsRemeasured] — the loose texts' rule, which a
     * text under a link never had), then the underline band. Rows are corrected when next written.
     */
    private fun correctedForDevice(links: List<PageLink>, pageWidth: Int): List<PageLink> {
        if (links.isEmpty()) return links
        val density = resources.displayMetrics.density
        return links.map { link ->
            link.withTextsRemeasured({ t -> pageObjects.measure(t.text, t.x, pageWidth) }, density)
                .withUnderlineBand(density)
        }
    }

    /**
     * A moved link whose wrapped texts now sit at another `x`: their wrap width changed with it
     * (`pageWidth − x`), so re-measure, and when that moved the box, write the rows the store's
     * delta move could not know about and drop the composite so it rasters at the new size.
     * Nothing to do for a link wrapping no text — the common case, one map lookup.
     */
    private fun remeasureMovedLinks(ids: List<String>) {
        val pageWidth = session.currentPage.width
        val density = resources.displayMetrics.density
        for (id in ids) {
            val moved = liveLinks[id] ?: continue
            if (moved.texts.isEmpty()) continue
            val sized = moved.withTextsRemeasured({ t -> pageObjects.measure(t.text, t.x, pageWidth) }, density)
            if (sized === moved) continue
            liveLinks[id] = sized
            for (t in sized.texts) if (t !in moved.texts) session.texts.updateContent(t)
            session.links.updateBounds(id, sized.x, sized.y, sized.width, sized.height)
            linkRenderer.invalidate(id)
        }
    }

    /**
     * The bar with the right mode: pure strokes → CONVERT's H + Link, one heading alone → CHANGE's H
     * with its level lit + Link, one link alone → Edit + Unlink, anything mixed → Delete plus Link
     * while no link is in it. A link anywhere in a mixed selection takes Link away — the no-nesting
     * rule (K1), read off the working copy rather than trusted from the engine's id set.
     *
     * **Arc 28:** the rule itself moved to [SelectionModes] at H2, so the D5 table is a table a
     * test can read; what stays here is the three predicates, which are answered off *this
     * screen's working copies* rather than trusted from the engine's id set. H5 gave a lone
     * **sticky** its own mode ([SelectionMode.STICKY]).
     */
    private fun showSelectionToolbar(sel: Selection) {
        val lone = sel.strokeIds.isEmpty() && sel.contentIds.size == 1
        val loneHeading = if (lone) liveHeadings[sel.contentIds.first()] else null
        val mode = SelectionModes.classify(
            sel.strokeIds.size, sel.contentIds,
            isHeading = { liveHeadings.containsKey(it) },
            isLink = { liveLinks.containsKey(it) },
            isText = { pageObjects.texts.containsKey(it) },
            isShape = { pageObjects.shapes.containsKey(it) },
            isSticky = { pageObjects.stickies.containsKey(it) },
        )
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

    /** [selectAsHeading]'s twin for a text object (arc 28 / H2) — [TextFlow] asks for it after a
     *  create, a conversion or an edit, all three of which leave the old selection frame stale. */
    private fun selectAsText(t: PageText) {
        paper.setSelection(emptySet(), setOf(t.id), t.bounds)
        selectionActive = true
        currentSelection = Selection(emptySet(), setOf(t.id), t.bounds)
        selectionToolbar.show(t.bounds, SelectionMode.TEXT, null)
    }

    /**
     * The same twin for a shape (arc 28 / H4) — [ShapeFlow] asks for it after an insert and after a
     * transform's **Done**, which by contract leaves nothing selected. The box is the padded AABB
     * [ShapeGeometry] reports as the hit target, so what the lasso frames is exactly what a lasso
     * would have caught (a rotated shape is hit by its box — D3's accepted caveat).
     */
    private fun selectAsShape(s: PageShape) {
        val bounds = ShapeGeometry.aabb(s, resources.displayMetrics.density)
        paper.setSelection(emptySet(), setOf(s.id), bounds)
        selectionActive = true
        currentSelection = Selection(emptySet(), setOf(s.id), bounds)
        selectionToolbar.show(bounds, SelectionMode.SHAPE, null)
    }

    /**
     * The same twin for a sticky (arc 28 / H5) — [StickyFlow] asks for it when the editor closes
     * after an initial create, so the icon can be dragged into place at once (decision 2).
     */
    private fun selectAsSticky(s: PageSticky) {
        paper.setSelection(emptySet(), setOf(s.id), s.bounds)
        selectionActive = true
        currentSelection = Selection(emptySet(), setOf(s.id), s.bounds)
        selectionToolbar.show(s.bounds, SelectionMode.STICKY, null)
    }

    /** The single selected shape's id, or null — [loneSelectedLink]'s rule and its reason: the
     *  selection can move, die or change kind between the bar going up and a button landing. */
    private fun loneSelectedShapeId(): String? {
        val sel = currentSelection ?: return null
        if (sel.strokeIds.isNotEmpty() || sel.contentIds.size != 1) return null
        return sel.contentIds.first().takeIf { pageObjects.shapes.containsKey(it) }
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

    /** Edit on a lone selected link: the flow captures the link and prefills the picker — or, for
     *  a Bible link, the reference dialog ([editLinkTarget] decides, by reading the payload). */
    private fun beginLinkEdit() {
        if (!opened || closing) return
        val link = loneSelectedLink() ?: return
        editLinkTarget(link)
    }

    /**
     * Retarget one link — **the payload decides which question that is** (arc 38 / R3). A
     * [LinkPayload.KIND_BIBLE] link's target is a passage of scripture, and the page picker has
     * nothing to say about one, so it opens the reference dialog instead; every other kind opens
     * the picker as it always has. Both doors into an Edit — the lasso bar's button and the
     * dead-target dialog's — come through here, so the two can never disagree.
     */
    private fun editLinkTarget(link: PageLink) {
        if (!opened || closing) return
        when {
            // Arc 40: the verses on the page — the words are scripture, so Edit is the text dialog.
            LinkPayload.isBibleText(link.payload) -> bibleRefFlow.editVerses(link)
            LinkPayload.referenceOf(link.payload) != null -> bibleRefFlow.edit(link)
            else -> linkPickFlow.beginEdit(link)
        }
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
        markNotes()   // arc 42: the payload is what the index holds
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
        // Arc 28: a link wraps the new kinds too — `LinkStore` re-parents whatever ids it is
        // handed, and the children lists are what make a wrapped set move, capture and restore
        // whole. A wrapped sticky's own content stays under the sticky, not under the link.
        val objs = pageObjects.split(sel.contentIds)
        val texts = pageObjects.textsIn(objs.textIds)
        val shapes = pageObjects.shapesIn(objs.shapeIds)
        val stickies = pageObjects.stickiesIn(objs.stickyIds)
        val bounds = PageLink.unionBounds(
            strokes, headings, resources.displayMetrics.density, texts, shapes, stickies,
        ) ?: return   // nothing of the captured selection is still on the page
        val link = PageLink(
            id = java.util.UUID.randomUUID().toString(),
            payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
            order = 0,   // the store lands it at MAX(order)+1 among the page's links
            strokes = strokes, headings = headings,
            texts = texts, shapes = shapes, stickies = stickies,
        )
        landLink(pageId, link, strokes.map { it.id })
        undo.record(Action.LinkCreated(pageId, link))
        Slog.d(TAG) {
            "wrapped ${strokes.size} strokes + ${headings.size} headings + ${texts.size} texts + " +
                "${shapes.size} shapes + ${stickies.size} stickies → link"
        }
    }

    /**
     * Wrap one freshly created text object in a link (arc 38 / R3 — [BibleRefFlow]'s door into the
     * wrap machinery). The row for the text has already been written by the flow; what is left is
     * exactly what a lasso wrap does, with a child set of one and no working copy to take the text
     * out of (it was never put on the page — it belongs to the link from the moment it exists).
     *
     * [consumedStrokeIds] is the ink a conversion replaced; it is the list the paper is told to
     * drop, standing where a wrap's own wrapped strokes stand — in both cases they are the strokes
     * leaving the page's live set in this frame.
     *
     * Null when nothing can be wrapped: the bounds of one text object are never degenerate, so this
     * only happens if the page turned under the dialog, which the flow has already checked.
     */
    private fun wrapTextAsLink(
        pageId: String,
        text: PageText,
        payload: String,
        consumedStrokeIds: List<String>,
    ): PageLink? {
        if (!opened || closing) return null
        val bounds = PageLink.unionBounds(
            emptyList(), emptyList(), resources.displayMetrics.density, listOf(text),
        ) ?: return null
        val link = PageLink(
            id = java.util.UUID.randomUUID().toString(),
            payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
            order = 0,   // the store lands it at MAX(order)+1 among the page's links
            strokes = emptyList(), headings = emptyList(), texts = listOf(text),
        )
        landLink(pageId, link, consumedStrokeIds)
        return link
    }

    /**
     * The tail every wrap shares: the row, the working copies, the renderers, the successor
     * selection and **one frame**. [droppedStrokeIds] are the strokes leaving the page's live set
     * with this act — a wrap's own wrapped ink, or the ink a conversion consumed.
     *
     * The undo entry is deliberately **not** recorded here: a plain wrap is one
     * [Action.LinkCreated], a Bible reference is one [Action.BibleRefCreated] covering three rows,
     * and which of them this act is belongs to the caller.
     */
    private fun landLink(pageId: String, link: PageLink, droppedStrokeIds: List<String>) {
        session.links.create(pageId, link)
        // A wrapped heading now *stays* in the outline (the gather hops link → page), so this
        // cannot flip availability any more — kept because parentage moved and the gate is cheap.
        contentsFlow.refresh()
        droppedStrokeIds.forEach { liveStrokes.remove(it) }
        link.headings.forEach { liveHeadings.remove(it.id) }
        // The wrapped objects leave the page's own lists: they belong to the link now, and the
        // link's composite is what draws them. (A text the caller never put on the page is simply
        // not there to remove — the drop is by id and a miss costs nothing.)
        pageObjects.drop(
            pageObjects.split(
                link.texts.map { it.id } + link.shapes.map { it.id } + link.stickies.map { it.id },
            )
        )
        liveLinks[link.id] = link
        headingRenderer.headings = liveHeadings.values.toList()
        linkRenderer.update(liveLinks.values.toList())
        // The successor selection rides the dismissal `removeStrokes` is about to perform — see
        // `onSelectionDismissed`. Injecting it there keeps the smart-lasso session alive across the
        // wrap, so the engine restores PEN when the *link's* selection is dismissed, not mid-wrap.
        pendingSelection = { selectAsLink(link) }
        if (droppedStrokeIds.isNotEmpty()) paper.removeStrokes(droppedStrokeIds) else paper.clearSelection()
        // No dismissal fired — select directly.
        pendingSelection?.let { pendingSelection = null; it() }
        // Unconditional, for the conversion's reason: removeStrokes only re-records when it dropped
        // something, and a heading-only wrap still has to paint. One Main block → one frame.
        paper.notifyContentChanged()
        markNotes(pageId)   // arc 42: every wrap ends here — the plain link's and the reference's
    }

    /**
     * A Bible reference's Edit landed (arc 38 / R3): the link's payload, box and one wrapped text
     * have all changed together, so the composite must be **rebuilt** rather than reused — its
     * padded size very often does not change when the words do ([LinkRenderer.invalidate]).
     * Re-selecting re-anchors the bar under the resized box (the post-edit re-anchor, a recorded
     * frame-silence exception).
     */
    private fun relandEditedLink(link: PageLink) {
        if (!opened || closing) return
        liveLinks[link.id] = link
        linkRenderer.invalidate(link.id)
        syncLinkRenderer()
        markNotes()   // arc 42: a reference's Edit moved the passage the index names
        selectAsLink(link)
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
            markNotes(pageId)   // arc 42: after the reload, which is this act's sync
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
            sel.contentIds.filter {
                liveHeadings.containsKey(it) || liveLinks.containsKey(it) || pageObjects.holds(it)
            }
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
            markClipboard(true)
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
            val links = correctedForDevice(plan.links, page.width)
            // Arc 28: a pasted text is re-measured for THIS device too — same correction, same
            // reason (the row is corrected whenever the text is next written).
            val texts = pageObjects.remeasured(plan.texts, page.width)
            // Composites off Main, before the frame that paints them (the hover-repaint trap).
            val linkBitmaps = linkRenderer.prebuild(links)

            headings.forEach { liveHeadings[it.id] = it }
            links.forEach { liveLinks[it.id] = it }
            plan.strokes.forEach { liveStrokes[it.id] = it }
            headingRenderer.headings = liveHeadings.values.toList()
            linkRenderer.update(liveLinks.values.toList(), linkBitmaps)
            pageObjects.put(texts, plan.shapes, plan.stickies)
            if (plan.strokes.isNotEmpty()) paper.addStrokes(plan.strokes)
            // Unconditional: addStrokes only re-records when it actually added ink, and a
            // heading-or-link-only paste still has to paint. One Main block → one frame.
            paper.notifyContentChanged()
            undo.record(
                Action.ObjectsPasted(
                    pageId, plan.strokes.map { it.id }, headings.map { it.id }, links,
                    texts.map { it.id }, plan.shapes.map { it.id },
                    // Whole snapshots: the paste wrote a note's content, and only the snapshot
                    // names the child rows a redo has to bring back.
                    plan.stickies,
                )
            )
            if (headings.isNotEmpty() || links.any { it.headings.isNotEmpty() }) contentsFlow.refresh()
            markNotes(pageId)   // arc 42: a pasted link is a reference the index has never seen

            // Land it selected, bar up — the pen drags it into place from here.
            var box: Bounds? = null
            for (s in plan.strokes) box = box?.union(s.bounds) ?: s.bounds
            for (h in headings) box = box?.union(h.bounds) ?: h.bounds
            for (l in links) box = box?.union(l.bounds) ?: l.bounds
            for (t in texts) box = box?.union(t.bounds) ?: t.bounds
            // A shape's box is its rotated, point-tight outline — the same rect the lasso shows.
            for (sh in plan.shapes) { val r = ShapeGeometry.tightBounds(sh); box = box?.union(r) ?: r }
            for (st in plan.stickies) box = box?.union(st.bounds) ?: st.bounds
            box?.let { bounds ->
                val contentIds = (
                    headings.map { it.id } + links.map { it.id } + texts.map { it.id } +
                        plan.shapes.map { it.id } + plan.stickies.map { it.id }
                    ).toSet()
                val strokeIds = plan.strokes.mapTo(HashSet()) { it.id }
                val selection = Selection(strokeIds, contentIds, bounds)
                paper.setSelection(strokeIds, contentIds, bounds)
                selectionActive = true
                currentSelection = selection
                showSelectionToolbar(selection)
            }
            toast(getString(R.string.objects_pasted_toast))
            Slog.d(TAG) {
                "pasted ${plan.strokes.size} strokes, ${headings.size} headings, ${links.size} links, " +
                    "${texts.size} texts, ${plan.shapes.size} shapes, ${plan.stickies.size} stickies"
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
        markClipboard(false)
        runCatching { withContext(Dispatchers.IO) { clipStore.clear(System.currentTimeMillis()) } }
            .onFailure { Log.w(TAG, "clipboard clear failed", it) }
    }

    // ── Ink transfers to an extension screen (arc 11 / J5, arc 23 / Y3) ──────

    /**
     * The gate **both** lasso sends pass — Send to Scratch Pad and Send to Calendar — written once
     * (arc 23 / Y4): it was two copies of the same four rules, and a rule written twice is a rule
     * that drifts.
     *
     *  - **The screen is alive and the entry is built** ([ready] is the caller's `isInitialized`).
     *  - **A copy, not a move** — the notebook keeps its ink and records nothing on its undo stack.
     *    There is nothing to undo: nothing on this page changed.
     *  - **Ink only, in writing order** — [TransferSelection.sendable], the pure rule: the bar's
     *    button is already gone on anything else, but the selection can change kind between the show
     *    and the tap, and `WireStroke` is the whole of what either contract carries.
     *  - **The caps are checked before any bind.** A refusal must cost nothing: no store open, no
     *    bind, no screen — only the "too much to send" dialog in the extension's own words.
     *
     * What is **not** shared is [sheet]: the pad asks where on the pad, the calendar asks which
     * calendar page, and that sheet *is* the difference between the two sends. Either rises from a
     * selection-toolbar tap — the O1 pattern, the same act as the lasso popup's own sheet — so it
     * needs no new frame-silence exception.
     */
    private fun sendSelectionToExtension(
        ready: Boolean,
        tooLargeTitleRes: Int,
        tooLargeBodyRes: Int,
        sheet: (List<Stroke>, PageRef) -> Unit,
    ) {
        if (!opened || closing || !ready) return
        val sel = currentSelection ?: return
        val strokes = TransferSelection.sendable(sel, liveStrokes.values)
        if (strokes.isEmpty()) return
        if (!TransferCaps.withinLimits(strokes.size, TransferCaps.pointCount(strokes))) {
            Dialogs.problem(this, tooLargeTitleRes, tooLargeBodyRes)
            return
        }
        sheet(strokes, session.currentPage)
    }

    /** The selection toolbar's **Pad**: ask where the ink should land on the pad, then hand it over
     *  and open the pad on it. */
    private fun sendSelectionToPad() = sendSelectionToExtension(
        ready = ::scratchPad.isInitialized,
        tooLargeTitleRes = R.string.scratch_too_large_title,
        tooLargeBodyRes = R.string.scratch_too_large_body,
    ) { strokes, page ->
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
        scratchPad.open(InkSend(strokes, page.width.toFloat(), page.height.toFloat(), placement))
    }

    /** The ink is on the pad. The selection it came from goes (it has been acted on) and the toast
     *  confirms something that has already happened — the standing toast rule, kept honest by
     *  firing here rather than at the tap, where the send could still have failed. */
    private fun onPadSent() {
        if (isFinishing || isDestroyed) return
        paper.clearSelection()
        toast(getString(R.string.scratch_sent_toast))
    }

    /**
     * The selection toolbar's **Calendar** (arc 23 / Y3): the same gate, then ask which calendar
     * page the ink should land on, hand it over and open the calendar on it.
     *
     * The four choices come from [CalendarTargets], which routes every one through
     * `CalendarTarget.of`: the host knows today and nothing else about periods. The rows carry no
     * icons — four identical calendar glyphs would say nothing (`LinkPickerActivity`'s new-page
     * sheet is the precedent).
     */
    private fun sendSelectionToCalendar() = sendSelectionToExtension(
        ready = ::calendar.isInitialized,
        tooLargeTitleRes = R.string.calendar_too_large_title,
        tooLargeBodyRes = R.string.calendar_too_large_body,
    ) { strokes, page ->
        val sheet = ActionSheetDialog(this).title(getString(R.string.calendar_target_title))
        for (choice in CalendarTargets.Choice.entries) {
            val label = when (choice) {
                CalendarTargets.Choice.TODAY_AM -> R.string.calendar_target_today_am
                CalendarTargets.Choice.TODAY_PM -> R.string.calendar_target_today_pm
                CalendarTargets.Choice.THIS_WEEK -> R.string.calendar_target_week
                CalendarTargets.Choice.THIS_MONTH -> R.string.calendar_target_month
            }
            // The target is resolved AT THE TAP — a sheet left up across midnight sends to the day
            // the person is tapping on, not the day the lasso was drawn on.
            sheet.addAction(null, getString(label)) {
                openCalendarWith(strokes, page, CalendarTargets.target(choice, LocalDate.now()))
            }
        }
        sheet.show()
    }

    /** Hand the ink to the entry, which opens the store, holds the bind, sends and launches — and
     *  which tells us [onCalendarSent] only once the ink is actually across. */
    private fun openCalendarWith(strokes: List<Stroke>, page: PageRef, target: CalendarTarget) {
        if (!opened || closing) return
        calendar.open(InkSend(strokes, page.width.toFloat(), page.height.toFloat(), target))
    }

    /** The calendar's Scratch Pad door (arc 23 / Y4): the calendar closed asking for the pad, so the
     *  pad opens (with this notebook behind it, as its own door would), and a plain close of the pad
     *  brings the calendar back at its bookmark. A pad that sent ink here instead stays closed — the
     *  paste is what the person is looking at. */
    private var reopenCalendarAfterPad = false

    private fun onCalendarClosed(resultCode: Int) {
        if (resultCode != ExtensionContract.RESULT_CALENDAR_OPEN_SCRATCH_PAD) return
        if (!opened || closing) return
        openPadOverCalendar()
    }

    /**
     * The pad raised with the calendar behind it — the calendar's own door (arc 23 / Y4) and the
     * shape a `CALENDAR, SCRATCH_PAD` chain is put back in (arc 32 / RS2), so both roads run the
     * same three lines.
     *
     * The latch is persisted **structurally**: a CALENDAR entry beneath the pad's, so a cold launch
     * puts the chain back. The calendar's own entry popped itself at its result, and
     * [onCalendarClosed] runs in a POSTED coroutine — after this screen's `onResume`, whose
     * `markTop` has already dropped everything above it — so the re-attach lands on top and the
     * pad's push goes above it.
     */
    private fun openPadOverCalendar() {
        stack.attach(calendar.stackEntry)
        reopenCalendarAfterPad = true
        scratchPad.open()
    }

    private fun onPadClosed(resultCode: Int) {
        if (!reopenCalendarAfterPad) return
        reopenCalendarAfterPad = false
        if (resultCode == RESULT_CANCELED && opened && !closing) calendar.open()
    }

    /**
     * The calendar's Export door (arc 31 / HV4): the calendar closed asking for the page it was
     * showing to be exported, so the Export screen opens at that target and the calendar comes back
     * at its bookmark when it is over, whatever the outcome.
     *
     * **This notebook is not closed for it**, unlike the page sheet's own Export row (arc 30 /
     * PE2): a calendar export opens no `.soil` at all, so `ExportOpen`'s held-file guard is not in
     * the way and there is nothing to seal. The notebook stands behind both screens exactly as it
     * stands behind the calendar itself.
     *
     * The latch dies with the process. Killed behind the Export screen, the calendar simply stays
     * closed and the notebook comes back as it was — the honest fallback, one tap from its button.
     */
    private var reopenCalendarAfterExport = false

    private fun onCalendarExport(target: CalendarTarget) {
        if (!opened || closing) return
        reopenCalendarAfterExport = true
        startActivity(ExportActivity.intent(this, target))
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
    private fun pasteFromPad(drained: List<DrainedInk>) =
        pasteTransferred(drained.flatMap { it.strokes }, drained.any { it.truncated }, PAD_WORDING, "the scratch pad")

    /**
     * Ink coming back from the calendar ([CalendarEntry.onDrained]) — the same paste, the calendar's
     * words. Deliberately **not** a sibling copy of the pad's: the two transfers differ in nothing
     * but the three strings they say, and a copy is how the `RattaNotebookView` trap is recreated one
     * file at a time.
     */
    private suspend fun pasteFromCalendar(drained: List<DrainedInk>) {
        // Ink only: a selection send, or a whole-page send whose paper never arrived (the entry
        // logged why). The arc-23 road, unchanged — it lands on the page that is displayed. A
        // multi-page send (arc 35 / HA1) always carries paper; one without it is the entry telling
        // us a render failed, and every drain's ink lands on the displayed page rather than vanish.
        if (drained.all { it.paper == null }) {
            pasteTransferred(drained.flatMap { it.strokes }, drained.any { it.truncated }, CALENDAR_WORDING, "the calendar")
            return
        }
        receiveCalendarPages(drained)
    }

    /**
     * **A whole page from the calendar** (arc 31 / HV5): a new page after the one on screen,
     * papered with the view that was sent, the ink on top, one undo entry, the ink selected with
     * the lasso armed. An empty send is a page of paper and nothing else, which is the whole point
     * of it — the calendar refuses an empty *selection*, never an empty page.
     *
     * [paperBytes] came out of another process and is checked before anything is written: the size
     * against [com.symmetricalpalmtree.notesproutsn.data.template.TemplateImport.MAX_BLOB_BYTES]
     * and the **decoded** picture against the page it claims to be ([CalendarPaper.accept]) — a
     * bounded decode, off Main, whose bitmap is thrown away at once (the row stores the bytes, and
     * the page swap is what decodes them for the glass). Paper that fails that falls back to the
     * ink-only road: ink on the displayed page is a smaller wrong than a send that vanished. Paper
     * that fails it with **no** ink behind it landed nothing at all, and says so.
     */
    private suspend fun receiveCalendarPages(drained: List<DrainedInk>) {
        if (!opened || closing) return
        // Every page's paper is checked before anything is written (the HV5 rule, per page): a page
        // whose paper fails lands ink-only on the displayed page — never in the middle of the pair.
        val pages = ArrayList<Pair<DrainedInk, ByteArray>>(drained.size)
        val inkOnly = ArrayList<DrainedInk>(0)
        for (d in drained) {
            val paperBytes = d.paper
            val width = d.pageWidth.toInt()
            val height = d.pageHeight.toInt()
            val usable = paperBytes != null && withContext(Dispatchers.IO) {
                val bitmap = Bitmaps.decodeBounded(paperBytes, NotebookSession.MAX_TEMPLATE_EDGE)
                if (bitmap == null) false else try {
                    CalendarPaper.accept(paperBytes.size, bitmap.width, bitmap.height, width, height)
                } finally {
                    bitmap.recycle()
                }
            }
            if (usable) pages += d to paperBytes!! else {
                Slog.d(TAG) { "the calendar's paper was refused (${paperBytes?.size ?: 0} B against ${width}x$height)" }
                inkOnly += d
            }
        }
        if (pages.isEmpty()) {
            val strokes = inkOnly.flatMap { it.strokes }
            if (strokes.isNotEmpty()) {
                pasteTransferred(strokes, inkOnly.any { it.truncated }, CALENDAR_WORDING, "the calendar")
            } else {
                Dialogs.problem(this, R.string.calendar_receive_failed_title, R.string.calendar_receive_failed_body)
            }
            return
        }
        runPageOp {
            // The shared writer first, as every page op does: a stroke commit still queued would
            // land after the page list has already been swapped out from under it.
            session.store.drain()
            val dpi = resources.displayMetrics.densityDpi.toFloat()
            val snaps = ArrayList<NotebookSession.Structural>(pages.size)
            var lastStrokes: List<Stroke> = emptyList()
            // In order: each receive inserts after the page the previous one landed on (the
            // session's current index moves with it), so AM comes before PM (arc 35 / HA1).
            for ((d, paperBytes) in pages) {
                val strokes = TransferCaps.toStrokes(d.strokes)
                val snap = runCatching {
                    session.receivePage(d.pageWidth.toInt(), d.pageHeight.toInt(), PaperSource.Image(paperBytes, TemplateFit.FIT), strokes, dpi)
                }.onFailure { Log.w(TAG, "the calendar's page could not be added", it) }.getOrNull() ?: break
                snaps += snap
                lastStrokes = strokes
            }
            if (snaps.isEmpty()) {
                // Nothing was written — the render threw, or the transaction did. One sentence.
                Dialogs.problem(this, R.string.calendar_receive_failed_title, R.string.calendar_receive_failed_body)
                return@runPageOp
            }
            // One undo step for what was one gesture: a lone page keeps HV5's kind, a pair takes
            // the arc-35 kind that replays both snapshots together.
            undo.record(if (snaps.size == 1) Action.PageReceived(snaps[0]) else Action.PagesReceived(snaps))
            noteSync.markStructural()   // arc 42: pages landed, so every ordinal below them moved
            // The paste's road home: the page, its template and its strokes all come off the rows,
            // and it is synchronous within this page op — so the selection below lands on ink that
            // is already on the glass.
            navigateTo(session.currentIndex)
            landTransferred(lastStrokes, pages.last().first.truncated, CALENDAR_WORDING, R.string.calendar_page_received_toast)
            Slog.d(TAG) { "received ${snaps.size} page(s) from the calendar, last ${snaps.last().afterCurrentId}" }
            if (inkOnly.isNotEmpty()) Slog.d(TAG) { "${inkOnly.size} page(s) of the send arrived without paper and were dropped" }
        }
    }

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
     * It lands **selected with the lasso armed** and says so — [landTransferred], which the
     * received-page road (arc 31 / HV5) shares rather than copying.
     */

    /**
     * Every box on the displayed page, for [FreePlacement] (the user's decision 2026-09-13: a
     * centre drop lands at the nearest clear spot, never on what is there): the working copies of
     * texts, shapes (their padded AABB), stickies, headings and links, plus the **live ink** — a
     * text dropped over handwriting is the same stacking. Read on Main at the drop, so what the
     * user sees is what is avoided.
     */
    private fun occupiedBounds(): List<Bounds> {
        val density = resources.displayMetrics.density
        val out = ArrayList<Bounds>()
        pageObjects.texts.values.mapTo(out) { Bounds(it.x, it.y, it.x + it.width, it.y + it.height) }
        pageObjects.shapes.values.mapTo(out) { ShapeGeometry.aabb(it, density) }
        pageObjects.stickies.values.mapTo(out) { Bounds(it.x, it.y, it.x + it.width, it.y + it.height) }
        liveHeadings.values.mapTo(out) { it.bounds }
        liveLinks.values.mapTo(out) { it.bounds }
        liveStrokes.values.mapTo(out) { it.bounds }
        return out
    }

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
            markNotes(pageId)   // arc 42: the object paste's kind, so the object paste's mark

            landTransferred(strokes, truncated, wording, R.string.objects_pasted_toast)
            Slog.d(TAG) { "pasted ${strokes.size} strokes from $source onto $pageId" }
        }
    }

    /**
     * The tail both transfer roads share (arc 31 / HV5 — the ink-only paste's and the received
     * page's): [strokes] selected with the lasso armed, then the word that follows.
     *
     * It lands **selected with the lasso armed**, so the pen can drag it into place at once — a
     * selection under the pen can neither be dragged nor dismissed, so the tool is switched
     * **before** `setSelection` (the O2 lesson): arming the lasso dismisses whatever selection was
     * still up, and that dismissal runs `restoreToolAfterTransferPaste` — which would otherwise
     * consume the parked tool and put the pen back under the selection being made here. The tool
     * the user had comes back pen-idle when this selection is dismissed. That frame is the
     * selection toolbar's own recorded exception, at a boundary (nothing is being written — the
     * user has just come back from another screen).
     *
     * A **cut** drain is a problem the user has to know about — the rest of their ink is still over
     * there — so it is a dialog and not [toastRes]. An empty [strokes] selects nothing: a page that
     * arrived as paper alone has nothing to point at.
     */
    private fun landTransferred(
        strokes: List<Stroke>,
        truncated: Boolean,
        wording: TransferWording,
        toastRes: Int,
    ) {
        if (strokes.isNotEmpty()) {
            var box = strokes.first().bounds
            for (i in 1 until strokes.size) box = box.union(strokes[i].bounds)
            armLassoForLanding()
            val strokeIds = strokes.mapTo(HashSet()) { it.id }
            val selection = Selection(strokeIds, emptySet(), box)
            paper.setSelection(strokeIds, emptySet(), box)
            selectionActive = true
            currentSelection = selection
            showSelectionToolbar(selection)
        }
        if (truncated) {
            Dialogs.problem(
                this, getString(wording.truncatedTitleRes),
                getString(wording.truncatedBodyRes, strokes.size),
            )
        } else {
            toast(getString(toastRes))
        }
    }

    /** The three strings a transfer paste says in its sender's name — the whole difference between
     *  the pad's paste and the calendar's. */
    private class TransferWording(
        val pasteFailedBodyRes: Int,
        val truncatedTitleRes: Int,
        val truncatedBodyRes: Int,
    )

    /**
     * Arm the lasso for a selection the host is about to land while some other tool is armed — a
     * transfer paste, or (arc 28 / H2) an Insert. A selection drawn under a PEN tool looks selected
     * and is not: the pen inks through it and can neither drag nor tap it (the eye-check #5 round-2
     * finding, met again on the Nomad at H2's first walk). The prior tool is remembered and comes
     * back at this selection's dismissal via [restoreToolAfterTransferPaste], which is what "the
     * armed tool is unchanged by an insert" (D4) means in practice. Must run BEFORE `setSelection`
     * (the O2 lesson): arming dismisses whatever selection was still up, and that dismissal is the
     * restore path.
     */
    private fun armLassoForLanding() {
        val priorTool = paper.tool
        if (priorTool == Tool.LASSO) return
        armLasso()
        toolBeforeTransferPaste = priorTool
    }

    /** Put back the tool a transfer paste or an insert took away — only while the lasso is still
     *  armed (a tool the user picked meanwhile wins), and pen-idle, because it is a chrome frame
     *  like any other. */
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

    /**
     * End a running transform mode by hand (arc 28 / H4). Every caller wants the same thing: the
     * geometry **persisted and recorded** through `onTransformEnded` rather than dropped, which is
     * what would happen on the silent `release` path. Idempotent and safe before `onCreate` has
     * built the surface.
     */
    private fun endTransformIfRunning() {
        if (!::paper.isInitialized) return
        if (paper.transformingContentId != null) paper.endTransform()
    }

    /** Open the clipboard popup, or keep P1's silent no-op when there is nothing of ours to offer. */
    private fun showLassoPopup() {
        if (!opened || closing) return
        if (!SnClipboard.hasObjects) return
        // Another bar taking this one's place ends the mode — the same rule the floating bars
        // already apply to each other, and the transform bar is one of them.
        endTransformIfRunning()
        hideEraserBar()   // the newest tap wins, as it does between the other floating bars
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
    private fun showTagsPopup(anchor: View? = null) {
        if (!opened || closing || !canvasShown) return
        endTransformIfRunning()
        hideEraserBar()
        hideInsertBar()   // arc 36: from the overflow row the contact that opens this is inside the collapsed chrome, which the Insert bar's own dismissal leaves alone
        if (tagsPopup.show(anchor)) pushExclusions()
    }

    private fun hideTagsPopup() {
        if (!::tagsPopup.isInitialized || !tagsPopup.isShowing) return
        tagsPopup.hide()
        pushExclusions()
    }

    // ── The Insert bar (arc 28 / H1) ─────────────────────────────────────────

    /**
     * Open the Insert sub-bar. [canvasShown] gates it for the tag bar's reason — everything it can
     * place lands on the page whose ink is on the paper, and a text document that has never shown
     * its pages has none. The two other floating bars come down first: they are three answers to
     * three different buttons, and the newest tap wins.
     */
    private fun showInsertBar(anchor: View? = null) {
        if (!opened || closing || !canvasShown) return
        endTransformIfRunning()
        hideLassoPopup()
        hideTagsPopup()
        hideEraserBar()
        if (insertBar.show(anchor)) pushExclusions()
    }

    // ── The collapsed chrome (arc 36 / C1) ───────────────────────────────────

    /** Both of the corner button's rows down. Idempotent, safe before the surface is built. */
    private fun dismissCollapsed() {
        if (::collapsed.isInitialized) collapsed.dismiss()
    }

    /** The lasso's clipboard mark, on the bar and on the collapsed chrome alike (arc 8 / arc 36). */
    private fun markClipboard(loaded: Boolean) {
        toolbar.showClipboardLoaded(loaded)
        if (::collapsed.isInitialized) collapsed.showClipboardLoaded(loaded)
    }

    private fun hideInsertBar() {
        if (!::insertBar.isInitialized || !insertBar.isShowing) return
        insertBar.hide()
        pushExclusions()
    }

    // ── The eraser sub-bar (arc 29 / LE2) ────────────────────────────────────

    /**
     * Open the eraser's sub-bar — Point · Lasso. The Insert bar's gates exactly, [canvasShown]
     * included: the tools arm against the paper, and a text document that has never shown its
     * pages has none. The three other floating bars come down first — they are four answers to
     * four different buttons, and the newest tap wins.
     *
     * Deliberately **not** pen-idle gated: this is one chrome frame at a deliberate tap, and the
     * pen that tapped it is still hovering (the floating-bar rule the lasso popup ledgered).
     */
    private fun showEraserBar() {
        if (!opened || closing || !canvasShown) return
        endTransformIfRunning()
        hideLassoPopup()
        hideTagsPopup()
        hideInsertBar()
        if (eraserBar.show()) pushExclusions()
    }

    private fun hideEraserBar() {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        eraserBar.hide()
        pushExclusions()
    }

    /** A fresh `ic_sticker_2` for one renderer: a [android.graphics.drawable.Drawable] carries
     *  mutable bounds, so every renderer that draws one owns its own `mutate()`d copy (D2). */
    private fun stickyIcon() = AppCompatResources.getDrawable(this, R.drawable.ic_sticker_2)!!.mutate()

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
        sheet.addAction(R.drawable.ic_erase_page, getString(R.string.erase_page_action)) { confirmErasePage() }
        sheet.addAction(R.drawable.ic_trash, getString(R.string.delete_page_action)) { confirmDeletePage() }
        // Arc 30 / PE2: absent, never disabled, while no exporter is installed (the library's
        // canExport rule) — answered at every resume rather than in an IO beat under the sheet.
        if (exportAvailable) {
            sheet.addAction(R.drawable.ic_download, getString(R.string.export_page_action)) { exportPage() }
        }
        // Arc 31 / HV2, last: absent — never disabled — when the page carries no usable size, the
        // one state in which there is nothing to draw.
        if (saveAsTemplateFlow.canRaster(displayedPageId)) {
            sheet.addAction(R.drawable.ic_photo_plus, getString(R.string.save_as_template_action)) {
                saveAsTemplateFlow.start(displayedPageId)
            }
        }
        // Export notebook, last (2026-09-10): the library's whole-notebook Export reachable without
        // leaving for the library — the same close-export-reopen door as Export page with no page
        // id, so the Export screen opens at whole scope with Soil listed and no Scope row. Same
        // exporter gate as Export page.
        if (exportAvailable) {
            sheet.addAction(R.drawable.ic_download, getString(R.string.export_notebook_action)) { exportNotebook() }
        }
        sheet.show()
    }

    /** Whether a trusted exporter is installed — the page sheet's Export row exists only then.
     *  Re-asked on every resume ([refreshExportAvailable]), never in the sheet's own beat: the
     *  sheet is built synchronously on the long-press, and a package rarely changes under an open
     *  notebook. A stale true costs one dialog on the Export screen, never a crash. */
    @Volatile private var exportAvailable = false

    private fun refreshExportAvailable() {
        lifecycleScope.launch {
            exportAvailable = runCatching { ExtensionRegistry.exporters(this@NotebookActivity).isNotEmpty() }
                .getOrDefault(false)
        }
    }

    /**
     * **Export page** (arc 30 / PE2, decisions 2 and 5): close, export, reopen. The Export screen
     * reads a *cold* `.soil` (`ExportOpen` guard 2 refuses a held file), so the notebook closes
     * exactly as it does for a Recents switch — drain, cover, bookmark, seal — and the launch runs
     * after the seal by `close(andThen)`'s ordering. The Export screen relaunches this notebook when
     * it finishes, whatever the outcome, and the reopen lands on the bookmark the close just wrote —
     * this page. Undo history dies with the close, as on every close.
     *
     * Not through `runPageOp`: `close()` takes the page-op lock itself, so a page op in flight
     * finishes first anyway, and `closing` refuses everything after. The page is `displayedPageId`
     * — what is on the glass (the R6 rule), never `session.currentIndex` mid-flip.
     */
    private fun exportPage() {
        val pageId = displayedPageId
        if (pageId.isEmpty()) return
        exportVia(pageId)
    }

    /**
     * **Export notebook** (2026-09-10): the library's whole-notebook Export from the page sheet —
     * the same door as [exportPage] with no page id, so the Export screen opens at whole scope
     * (Soil listed, no Scope row) exactly as from the library, and reopens this notebook after.
     */
    private fun exportNotebook() = exportVia(pageId = null)

    /** The close-export-reopen door shared by [exportPage] (a page id) and [exportNotebook] (null). */
    private fun exportVia(pageId: String?) {
        if (!opened || closing) return
        val name = notebookName
        OpeningOverlay.showThen(this) {
            close {
                startActivity(
                    ExportActivity.intent(
                        this@NotebookActivity, notebookId, name, pageId = pageId, returnToNotebook = true,
                    )
                )
            }
        }
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
        markClipboard(false)
        hideLassoPopup()
        if (cut) {
            val snap = session.deleteCurrent()
            undo.record(Action.Page(snap))
            noteSync.markStructural()   // arc 42: the delete's mark, under the cut's name
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
        noteSync.markStructural()   // arc 42: a whole page of links may have arrived
        navigateTo(session.currentIndex)
        toast(getString(if (before) R.string.pasted_before_toast else R.string.pasted_after_toast, anchor))
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** "Erase this page?" — the delete confirm's shape (arc 30 / PE1); Erase runs [doErase]. */
    private fun confirmErasePage() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.erase_page_title)
                .setPositiveButton(R.string.erase_confirm) { _, _ -> runPageOp { doErase() } }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
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
        //
        // Deliberately NOT visibility-aware (arc 33 / F1): this reads like the GONE-keeps-its-size
        // trap, but here the stale height is exactly the wanted value — the margin stays "one
        // toolbar" while the chrome is hidden, so an object snapped then still clears the bar when
        // it comes back. The margin is page-space, not chrome state.
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
                selectionToolbar.rects() + lassoPopup.rects() + tagsPopup.rects() +
                insertBar.rects() + eraserBar.rects() + transformBar.rects() +
                (if (::collapsed.isInitialized) collapsed.rects() else emptyList())
            )
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    /**
     * The free band between the two chrome bars, in the root's coordinates — where a floating bar
     * may be placed. Null until a shown bar has been laid out; a hidden bar (arc 33) yields its
     * edge of the screen instead, so the floating bars keep showing over bare paper.
     */
    private fun chromeBand(): IntRange? = ChromeBand.of(
        binding.root.height,
        binding.topBar.asBar(edge = binding.topBar.bottom),
        binding.bottomStrip.asBar(edge = binding.bottomStrip.top),
    )

    /**
     * Frame-silence rule: never present an app frame while the pen is active (Ratta overlay ink
     * lags for every frame it must mask). Chrome text changes wait for the gate to open.
     */
    private fun setPageIndicator(n: Int, total: Int) {
        val text = getString(R.string.page_indicator, n, total)
        whenPenIdle { binding.pageIndicator.text = text }
    }

    /** [PenIdle.releaseRenderIfIdle] — the same rule [NotebookToolbar] writes against, plus the
     *  `lateinit` guard this screen needs (chrome can be tapped before the surface is built). */
    private fun releaseRenderIfIdle() {
        if (::paper.isInitialized) PenIdle.releaseRenderIfIdle(paper)
    }

    /** [PenIdle.whenIdle] — the frame-silence gate, posted on this screen's root. */
    private fun whenPenIdle(action: () -> Unit) = PenIdle.whenIdle(paper, binding.root, action)

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
                dismissInsertBarOnContact(ev, ev.actionIndex)
                dismissEraserBarOnContact(ev, ev.actionIndex)
                dismissCollapsedOnContact(ev, ev.actionIndex)
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
        // Arc 36: the popup may hang off the overflow row, whose Tags button toggles it — the same
        // close-then-reopen trap as the bar's button, so a contact on the rows is theirs to answer.
        if (::collapsed.isInitialized && collapsed.contains(x, y)) return
        hideTagsPopup()
    }

    /** The Insert bar's outside-tap dismissal (arc 28 / H1) — the tag bar's rule exactly, its own
     *  button excluded for the same reason, and [tapDismissedPopup] deliberately untouched: that
     *  latch belongs to the clipboard popup, whose contact has a second meaning. */
    private fun dismissInsertBarOnContact(ev: MotionEvent, index: Int) {
        if (!::insertBar.isInitialized || !insertBar.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (rectOf(binding.btnInsert)?.contains(x, y) == true) return
        if (insertBar.contains(x, y)) return
        if (::collapsed.isInitialized && collapsed.contains(x, y)) return   // arc 36, the tag bar's reason
        hideInsertBar()
    }

    /** The eraser sub-bar's outside-tap dismissal (arc 29 / LE2) — the Insert bar's rule exactly,
     *  the eraser button excluded because its own re-tap would otherwise close the bar here and
     *  the toolbar would immediately reopen it, and [tapDismissedPopup] deliberately untouched:
     *  that latch belongs to the clipboard popup, whose contact has a second meaning. */
    private fun dismissEraserBarOnContact(ev: MotionEvent, index: Int) {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (rectOf(binding.btnEraser)?.contains(x, y) == true) return
        if (eraserBar.contains(x, y)) return
        hideEraserBar()
    }

    /**
     * The collapsed chrome's outside-tap dismissal (arc 36) — the rule lives in [CollapsedChrome];
     * the sub-bars hung off its rows (Insert, Tags) keep the rows up under a contact of their own.
     *
     * It **does** write [tapDismissedPopup], the lasso popup's latch: the mini toolbar sits open
     * under an armed lasso wearing the clipboard mark, so a pen tap spent closing it must not also
     * paste — the same second meaning that latch was minted for. Runs after the lasso popup's
     * dismissal, which rewrites the latch at every pointer-down, so this only ever adds to it.
     */
    private fun dismissCollapsedOnContact(ev: MotionEvent, index: Int) {
        if (!::collapsed.isInitialized) return
        val dismissed = collapsed.dismissOnContact(ev.getX(index).toInt(), ev.getY(index).toInt()) { x, y ->
            (::insertBar.isInitialized && insertBar.contains(x, y)) ||
                (::tagsPopup.isInitialized && tagsPopup.contains(x, y))
        }
        if (dismissed) tapDismissedPopup = true
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
            (::tagsPopup.isInitialized && tagsPopup.contains(x, y)) ||
            (::insertBar.isInitialized && insertBar.contains(x, y)) ||
            (::eraserBar.isInitialized && eraserBar.contains(x, y)) ||
            (::transformBar.isInitialized && transformBar.contains(x, y)) ||
            (::collapsed.isInitialized && collapsed.contains(x, y))
    }

    /** The shared rule — visibility-aware since arc 33, so a hidden bar has no rect. */
    private fun rectOf(v: View): Rect? = PaperToolbar.rectOf(v)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Resumed = the top of the surface stack: whatever was above this screen has closed (arc
        // 32 / RS1). Runs AFTER the result callbacks, so an entry's pop always precedes this drop.
        // Guarded because an IndexGuard bounce returns from onCreate but still gets this callback.
        if (::stack.isInitialized) stack.markTop(stackToken)
        // Arc 33: another paper screen (the pad, the calendar, the sticky editor) may have flipped
        // the one global flag while this one was away — re-sync before the paper comes back.
        if (::chromeToggle.isInitialized) chromeToggle.sync(chromePrefs.hidden)
        if (::paper.isInitialized) paper.resumeDrawing()
        // Re-discovered on every resume: a package can be disabled or replaced under us, and this
        // is also the resume that follows a return from the pad.
        if (::scratchPad.isInitialized) scratchPad.refresh()
        if (::calendar.isInitialized) calendar.refresh()
        // The calendar comes home after its export (arc 31 / HV4) — the pad-door guard, for the
        // same reason. Never on the resume that starts it: the entry's result runs in a posted
        // coroutine, so the latch is set on the way *into* the Export screen, not before this.
        if (reopenCalendarAfterExport && ::calendar.isInitialized) {
            reopenCalendarAfterExport = false
            if (opened && !closing) calendar.open()
        }
        if (::documentEntry.isInitialized) documentEntry.refresh()
        if (::tagEntry.isInitialized) tagEntry.refresh()
        if (::bible.isInitialized) bible.refresh()
        refreshExportAvailable()
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
        if (::stackToken.isInitialized) outState.putString(KEY_STACK_TOKEN, stackToken)
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
        // Arc 26 / U4, decision 11: a NOTEBOOK-scope notebook shows a lock, and the index must hold
        // no picture of its contents — not a page snapshot, not the opening lines of its document.
        // The scope was read once at open (`keyScope`), so this costs nothing per stop.
        if (keyScope == KeyScope.NOTEBOOK) return
        if (s.isTextDocument) TextCover.render(repo, id, s.documents.get(id)?.text.orEmpty())
        else if (opened) CoverSnapshot.capture(p, id, repo)
    }

    override fun onStop() {
        super.onStop()
        // Same rule as close()'s, one step earlier: a screen going into the background with the
        // transform mode still up must persist that geometry through `onTransformEnded` — and
        // before the cover is captured below, so the snapshot shows the shape as it now is.
        endTransformIfRunning()
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
        // Before `closing` and before the history goes: a transform still running is persisted and
        // recorded through `onTransformEnded`, never dropped (`release` is silent). The undo entry
        // it writes dies with the stack a line below, which is correct — the row does not.
        endTransformIfRunning()
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
        stack.pop(stackToken)
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
                    // Arc 42: only what is already pending, and only before the seal — a
                    // structural flush reads the rows through this very connection. Nothing is
                    // pushed *because* of a close: a cold store lease would pay the KDF at every
                    // Back.
                    try { noteSync.flushBeforeSeal() } catch (e: Exception) { Log.w(TAG, "notes flush failed", e) }
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
        // The Bible's held bind, same rule and the same reason as the tag screen's.
        if (::bible.isInitialized) bible.close()
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

        /** Host-internal (arc 32 / RS2): the extension screens that stood above this notebook when
         *  the process died, bottom-first, as an `ArrayList<String>` of
         *  [com.symmetricalpalmtree.notesproutsn.data.prefs.Surface] names. Read once on a cold
         *  create and ignored on a task rebuild — [EXTRA_INITIAL_PAGE_ID]'s rule. Never crosses to
         *  any other component: the screens themselves are raised through their entries. */
        const val EXTRA_RESUME_ABOVE = "resumeAbove"

        /** The one two-deep chain SN has: the calendar's own pad door (arc 23 / Y4). Every other
         *  legal chain is a single screen — see `ReplayPlan.legalAbove`. */
        private val PAD_OVER_CALENDAR = listOf(Surface.CALENDAR, Surface.SCRATCH_PAD)
        private const val KEY_STACK_TOKEN = "stackToken"
        /** Set on the Intent by the screen itself once recovery has been offered (U6). */
        private const val EXTRA_RECOVERY_ATTEMPTED = "recoveryAttempted"

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(
            context: Context,
            notebookId: String,
            notebookName: String,
            viaLink: Boolean = false,
            initialPageId: String? = null,
            resumeAbove: List<Surface> = emptyList(),
        ): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
                .putExtra(EXTRA_VIA_LINK, viaLink)
                .putExtra(EXTRA_INITIAL_PAGE_ID, initialPageId)
                .putStringArrayListExtra(EXTRA_RESUME_ABOVE, ArrayList(resumeAbove.map { it.name }))
    }
}
