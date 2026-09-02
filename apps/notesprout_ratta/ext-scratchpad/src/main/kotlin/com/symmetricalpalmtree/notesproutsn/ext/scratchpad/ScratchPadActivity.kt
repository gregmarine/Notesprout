package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkPage
import com.symmetricalpalmtree.notesproutsn.ink.InkScreenActivity
import com.symmetricalpalmtree.notesproutsn.notebook.InkSelectionBar
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The extension-owned Scratch Pad screen (arc 11 / J4; UI-rule tier 2) — the notebook's shape, in
 * the extension's own process, built from `:sn-screen` and, since arc 23, on `:ext-ink`'s
 * [InkScreenActivity]: **the whole tier-2 skeleton is there** — full-bleed g-paper, the page-op
 * lock, the undo/redo replay, the debounced save against every leave flush, the chrome band and
 * exclusions, the EPD handoff and Send. This class is what is the **pad's own**: its page list and
 * pager, its inserts and its one page action, and the placement branch a new page needs. The pages
 * and their persistence are [ScratchDocument]'s; the store is the host's, lent for this showing —
 * **the extension writes nothing to disk itself, ever**.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be, the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * **The EPD handoff is the arc's headline risk and this screen is one half of it** — the ordering
 * rule and its reasons are [InkScreenActivity]'s class note, and a failure there goes to g-paper,
 * never a host workaround. So is **Back awaits the flush**.
 *
 * **The transfers (J5) never touch the paper from outside.** Inbound ink was placed in the store on
 * the Binder thread *before* this screen existed, so [openDocument] simply loads it and then
 * consumes [ScratchSession]'s record **once**: it switches to the **lasso before `setSelection`** (a
 * selection under the pen can neither be dragged nor dismissed), selects what arrived, and records
 * exactly one undo step — a new-page placement as a [ScratchAction.Page] carrying the arrived ink as
 * its `afterInk`, a current-page one as an [InkAction.Pasted]. The tool the user had comes back
 * pen-idle when that selection is dismissed, unless they picked another one meanwhile. Outbound ink
 * is parked in [ScratchSession] and the screen finishes with [ExtensionContract.RESULT_SCRATCH_SEND]
 * — the host drains it on the bind it is still holding.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The page indicator waits for the gate
 * ([ScratchToolbar]); the frames that do not are the notebook's own recorded exceptions, in their
 * scratch-pad form — the delete confirm at a long-press, the selection bar's show at lasso
 * completion (and its own re-anchor after a move, and its show over a received placement), the
 * "Opening…" box's hide when the page lands, and a problem dialog at a pen-up or at a chrome tap
 * (a refused stroke, an empty Send).
 */
class ScratchPadActivity : InkScreenActivity<ScratchAction>() {

    private lateinit var binding: ActivityScratchPadBinding
    private lateinit var toolbar: ScratchToolbar
    private var document: ScratchDocument? = null

    /** True when the pad was opened from a notebook — the two Send buttons exist only then. */
    private var sendEnabled = false

    /**
     * True when the host says this launch follows a `receiveInk` (arc 11 / J6 — the extra was
     * written and never read until then). It is what makes a placement *this showing's*: the record
     * itself is process-local state that a `begin` clears, so gating on the Intent is belt and
     * braces of the kind the rest of this seam is built from — and it is what keeps the contract
     * honest, since a documented extra nobody reads is one a later change will trust wrongly.
     */
    private var openReceived = false

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.scratch_store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.scratch_store_failed_body
    override val nothingToSendTitleRes: Int get() = R.string.scratch_nothing_to_send_title
    override val nothingToSendBodyRes: Int get() = R.string.scratch_nothing_to_send_body
    override val sendResultCode: Int get() = ExtensionContract.RESULT_SCRATCH_SEND

    override fun parkOutgoing(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float) =
        ScratchSession.park(chunks, pageWidth, pageHeight)

    /** The pad's stack is one sealed type over both an ink edit and a page-list one. */
    override fun record(action: InkAction) = undo.record(ScratchAction.Ink(action))

    override fun syncTool(tool: Tool) = toolbar.sync(tool)

    override fun showPage() = showPage(firstLoad = false)

    override suspend fun revert(action: ScratchAction) {
        document?.revert(action)
    }

    override suspend fun reapply(action: ScratchAction) {
        document?.reapply(action)
    }

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        sendEnabled = intent.getBooleanExtra(ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED, false)
        openReceived = intent.getBooleanExtra(ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED, false)
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
        paper.setPaperListener(paperListener)

        toolbar = ScratchToolbar(
            paper = paper,
            bottomBar = binding.bottomBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnSend = binding.btnSend,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            pageIndicator = binding.pageIndicator,
            onBack = { exit() },
            onSend = { sendPage() },
            // No-op at a bound, never disabled: a greyed control is invisible on e-ink.
            onPrevPage = { runPageOp { flipTo(pageIndex() - 1) } },
            onNextPage = { runPageOp { flipTo(pageIndex() + 1) } },
            sendEnabled = sendEnabled,
        )
        selectionBar = InkSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            deleteHint = getString(R.string.delete_selection_action),
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            sendHint = if (sendEnabled) getString(R.string.cd_scratch_send_selection) else null,
            onSend = { sendSelection() },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { selectionBar.rects() },
            extraContains = { x, y -> selectionBar.contains(x, y) },
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
        val doc = document ?: return
        try {
            doc.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scratch store unavailable", e)
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        doc.adoptSurfaceSize()
        showPage(firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to writing,
        // which would hold the box up over the page the user asked for. A boundary frame, not a
        // frame during writing — nothing has been drawn yet.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${doc.pageId} loaded: ${doc.strokes.size} strokes, ${doc.pageCount} pages" }
        consumeReceived()
    }

    /**
     * The one-shot handover of a `receiveInk` placement (J5) — the ink is already in the store and
     * already on the paper (it came in with [ScratchDocument.load]); what is left is to say so.
     *
     * **Consumed once**: the record is cleared before anything can fail, so a placement whose page
     * has since gone (only reachable through a host restart mid-showing) is dropped rather than
     * re-applied at the next open — and it is only applied at all when the launch Intent's
     * [ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED] says the host sent one.
     *
     * One undo step, and which one depends on what the placement did to the page list: a **new
     * page** is a [ScratchAction.Page] whose `afterInk` is the ink that came with it — undo takes
     * the page away with its cargo, redo brings both back — and a **current page** placement is a
     * [InkAction.Pasted], which removes and restores exactly what arrived, at the orders it
     * arrived at. The selection it lands as is the skeleton's
     * ([InkScreenActivity.showArrivedSelection]).
     */
    private fun consumeReceived() {
        val doc = document ?: return
        val received = ScratchSession.received ?: return
        ScratchSession.received = null
        if (!openReceived) {
            // The host did not launch us for a placement, so this record is not ours to apply.
            // Not reachable while `begin` clears the session — which is the point of checking.
            Slog.d(TAG) { "received placement dropped: this launch did not ask for one" }
            return
        }
        if (received.pageId != doc.pageId) {
            Slog.d(TAG) { "received placement dropped: page ${received.pageId} is not current" }
            return
        }
        val ids = received.strokeIds.toHashSet()
        val arrived = doc.strokes.filter { it.id in ids }
        if (arrived.isEmpty()) return

        undo.record(
            if (received.newPage) {
                ScratchAction.Page(
                    before = received.pagesBefore,
                    beforeCurrent = received.currentBefore,
                    after = doc.pageIds,
                    afterCurrent = received.pageId,
                    pageId = received.pageId,
                    ink = null,                           // the page did not exist before
                    afterInk = doc.currentInk(),
                )
            } else {
                ScratchAction.Ink(InkAction.Pasted(received.pageId, arrived, arrived.map { doc.orderOf(it.id) ?: 0L }))
            }
        )
        showArrivedSelection(ids, arrived)
        Slog.d(TAG) { "received ${arrived.size} strokes (newPage=${received.newPage})" }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the pad grows where you write.
            val doc = document ?: return@runPageOp
            if (doc.pageIndex < doc.pageCount - 1) flipTo(doc.pageIndex + 1)
            else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp { flipTo(pageIndex() - 1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onPageSheetRequested() = confirmDeletePage()
        // The pad implements only what it has: SN's other callbacks (Contents, Recents, the trail
        // walk-back, link follow) stay the no-op defaults `PageGestures.Listener` already gives.
    }

    private fun pageIndex(): Int = document?.pageIndex ?: 0

    private suspend fun flipTo(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.pageCount) return   // no-op at a bound
        doc.goToIndex(index)
        showPage()
    }

    private suspend fun doInsert(after: Boolean) {
        val doc = document ?: return
        undo.record(doc.insert(after))
        showPage()
    }

    private suspend fun doDelete() {
        val doc = document ?: return
        undo.record(doc.deleteCurrent())
        showPage()
    }

    /**
     * Put the document's current page on the paper. The order is the host-responsibilities page-swap
     * law: `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` /
     * `setTemplate` → `loadStrokes`, which is a single EPD refresh. Any selection goes first,
     * because a data-in call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()   // idempotent — clearSelection fires onSelectionDismissed too
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(doc.pageWidth.toInt(), doc.pageHeight.toInt())
        paper.setTemplate(null)   // the pad is plain paper: no templates, ever
        paper.loadStrokes(doc.strokes)
        toolbar.setPage(doc.pageNumber, doc.pageCount)
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

    private companion object {
        const val TAG = "ScratchPadActivity"
    }
}
