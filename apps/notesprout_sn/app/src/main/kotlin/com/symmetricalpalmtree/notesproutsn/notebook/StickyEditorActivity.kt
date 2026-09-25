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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.InkTones
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.SnClipboard
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import com.symmetricalpalmtree.notesproutsn.data.prefs.ChromePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.PenShadePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.SnapPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityStickyEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The sticky note editor (arc 28 / H5, decisions 1–3, D2): a **core** screen with its own g-paper
 * surface — the second second-paper-surface in one process, after the calendar's event note (Z3)
 * — sharing the notebook's open `.soil` through [StickyEditorTransfer]. It opens no database, holds
 * no session, and is meaningless launched from anywhere but [NotebookActivity]'s result launcher.
 *
 * **What is on the glass.** Full-bleed paper with one **floating** top bar (`[←] [pen] [eraser]
 * [lasso]` + a centred "Sticky Note" title — Back saves-and-closes; there is no cancel because
 * every stroke is already a row, and no ✓ because Back already does the one thing it would) laid
 * over it, the notebook's own shape since arc 33 / F2 — and a **single-finger double-tap hides the
 * bar and brings it back**, one flag shared with every other paper screen ([ChromePrefs]). The
 * paper is the size of the note's content ([StickyEditorTransfer.Showing.contentW] × `contentH`,
 * laid top-left 1:1 — a foreign size is the notebook's foreign-page rule), which since F2 is the
 * whole window for a note authored here; an older, smaller note has the paper beyond it excluded
 * ([StickyPageRects]) so the pen cannot write outside the note. The tools are the notebook's, fixed
 * (3 px pen, 15 px eraser, black) — including the eraser's **two kinds** since arc 29 / LE2, Point
 * and Lasso, picked from the [EraserBar] its own re-tap opens; 2/3-finger undo/redo replay an
 * in-memory [StickyInk]; the lasso's bar is Snap · Copy · Cut · Delete; a pen tap on bare paper
 * pastes the clipboard's ink.
 *
 * **Writes are whole sets, debounced.** Every act updates [ink] and schedules
 * [StickyEditorTransfer.Sink.setContent] with the whole list [DEBOUNCE_MS] later; a leave
 * ([onStop], every exit) flushes at once. The sink is an `enqueue` on the notebook's serial
 * writer, so a flush never suspends and the order of writes is the order of acts. The host records
 * **one** undo entry per showing from `initial → output` in its result callback; this screen's
 * own stack lives inside the showing only.
 *
 * **The EPD handoff chain** (Z3's rule): the notebook released the pipeline immediately before the
 * launch; this surface reclaims in [onResume], releases before **every** `finish()` ([exit]), and
 * the notebook reclaims at the top of its result callback (result callbacks run before its
 * `onResume`). A failure there is fixed in g-paper, never worked around here.
 *
 * **Process death** leaves nothing staged: [onCreate] finds no showing and finishes at once — the
 * notebook beneath is recreated behind `IndexGuard` and loses at most the debounce window.
 *
 * Frame silence: the selection bar's show at lasso completion / re-anchor after a move / over a
 * paste landing are the notebook's ledgered exceptions, applied here unchanged.
 */
class StickyEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickyEditorBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: PaperToolbar
    private lateinit var selectionBar: FloatingSelectionBar
    /** The eraser button's sub-bar (arc 29 / LE2) — Point · Lasso, opened by a re-tap on the armed
     *  eraser. `:sn-screen`'s [EraserBar], the same one the notebook hangs under its own button. */
    private lateinit var eraserBar: EraserBar

    /** The pen's shade panel (arc 49 / P4) — `:sn-screen`'s bar under the armed pen button on its
     *  re-tap, the eraser sub-bar's arrangement; the level is the device's ([penShadePrefs]). */
    private lateinit var paletteBar: PaletteBar
    private lateinit var penShadePrefs: PenShadePrefs

    /** The pen button wearing its shade (arc 49 / P4) — the toolbar is `:sn-screen`'s bare
     *  [PaperToolbar], so this screen owns the glyph itself. */
    private lateinit var penGlyph: PenShadeGlyph
    private lateinit var gestures: PageGestures
    private lateinit var snapPrefs: SnapPrefs
    /** The one global chrome flag (arc 33) — read at open, written at every toggle. */
    private lateinit var chromePrefs: ChromePrefs
    private lateinit var chromeToggle: ChromeToggle

    /** The collapsed chrome (arc 36 / C2) — the corner tool button and its two rows, what this
     *  screen shows while its one bar is hidden. */
    private lateinit var collapsed: CollapsedChrome
    private lateinit var showing: StickyEditorTransfer.Showing
    private val clipStore by lazy { ClipStore() }

    private val ink = StickyInk()
    private val undo = UndoRedoStack<StickyInk.Action>()

    /** True once the note is on the paper; before it, the whole surface is blocked. */
    private var shown = false
    /** The page size the note was shown at — what [StickyPageRects] fences the paper against. */
    private var pageW = 0
    private var pageH = 0
    private var closing = false
    private var selection: Selection? = null

    /** The tool a paste's landing took away — put back pen-idle at that selection's dismissal. */
    private var toolBeforeLanding: Tool? = null

    private var saveJob: Job? = null
    private var dirty = false

    // ── g-paper → the note ───────────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!shown || closing) return
            undo.record(ink.add(stroke))
            scheduleSave()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!shown || closing) return
            ink.erase(strokeIds)?.let { undo.record(it); scheduleSave() }
        }

        /**
         * A lasso-erase gesture (arc 29 / LE2): the point eraser's body, because on this surface
         * the two take exactly the same thing — ink. [contentIds] is always empty here (a note
         * carries no content renderers at all: no headings, no links, no objects) and is therefore
         * ignored; a `StickyInk.Action` kind of its own would have nothing to label.
         */
        override fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) {
            if (!shown || closing) return
            ink.erase(strokeIds)?.let { undo.record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!shown || closing) return
            ink.move(move.strokeIds, move.dx, move.dy)?.let { undo.record(it); scheduleSave() }
            selection = selection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            selection?.let { selectionBar.show(it.bounds) }
            pushExclusions()
        }

        override fun onSelectionCreated(selection: Selection) {
            this@StickyEditorActivity.selection = selection
            // Not pen-idle-gated: the lasso ends with the pen still hovering and the engine has
            // already presented the box — this frame is part of that presentation.
            selectionBar.show(selection.bounds)
            pushExclusions()
        }

        override fun onSelectionDragStarted() {
            selectionBar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selection = null
            selectionBar.hide()
            pushExclusions()
            restoreToolAfterLanding()
        }

        /** A pen tap on bare paper with objects on the clipboard: paste their ink here. */
        override fun onPaperTapped(x: Float, y: Float) {
            if (!shown || closing) return
            if (!SnClipboard.hasObjects) return
            doPaste(x, y)
        }

        override fun onToolChanged(tool: Tool) {
            if (::toolbar.isInitialized) toolbar.sync(tool)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        // Nothing staged means the process died under us: the notebook beneath is being recreated
        // and there is nothing here to edit with. Leave at once, empty-handed.
        val staged = StickyEditorTransfer.take()
        if (staged == null) {
            Log.w(TAG, "no showing staged — finishing")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        showing = staged
        binding = ActivityStickyEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // The notebook's fixed tools (P1), armed before the listener is attached — the engine reads
        // the two recognisers as it wires itself up.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        // Arc 49 / P3: the note goes direct to the Supernote panel like the notebook (g-paper
        // Phase 42) — unconditional, the daemon fallback is the engine's. Every panel post is cut
        // around the exclusion rects, so the off-page fence bands pushExclusions() lists for an
        // older, smaller note fence the panel exactly as they fenced the daemon: the committed
        // image is view-sized and white beyond the page, and nothing of it is ever posted there.
        paper.directInk = true
        paper.tool = Tool.PEN
        // Black until the device's shade is applied below (arc 49 / P4) — the same first answer.
        paper.penColor = Stroke.BLACK
        paper.penWidth = NotebookToolbar.PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = NotebookToolbar.ERASER_RADIUS_PX
        snapPrefs = SnapPrefs(this)
        paper.snapMarginPx = resources.getDimensionPixelSize(R.dimen.toolbar_bar_thickness).toFloat()
        paper.snapToGuides = snapPrefs.enabled
        paper.setPaperListener(listener)

        toolbar = PaperToolbar(
            bar = binding.topBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            paper = paper,
            onBack = { exit() },
            // Arc 36: the corner button repaints with the bar — the one funnel every tool change
            // passes through, so no by-hand arm can leave it out.
            onSynced = { if (::collapsed.isInitialized) collapsed.sync() },
            // A second tap on the armed eraser opens its sub-bar — Point · Lasso — and a third
            // closes it again (arc 29 / LE2, the notebook's toggle exactly).
            onEraserReTap = { if (eraserBar.isShowing) hideEraserBar() else showEraserBar() },
            // Arming a different tool takes the sub-bars with it: they belong to the tool being
            // left. A tool tap never reaches this screen otherwise — [PaperToolbar] consumes it.
            onToolTapped = { hideEraserBar(); hidePaletteBar() },
            // Arc 49 / P4: a second tap on the armed pen opens its shade panel, a third closes it.
            onPenReTap = { if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar() },
        )
        penGlyph = PenShadeGlyph(binding.btnPen, Stroke.BLACK)
        // Constructed after the toolbar because a pick lands on `toolbar.arm` (a host-set tool is
        // never echoed back as `onToolChanged`, so the buttons are synced by hand). The band is the
        // root below the top bar — this screen has no bottom strip — and the whole root once the
        // bar is hidden ([chromeBand]).
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { hideEraserBar(); toolbar.arm(it) },
        )
        // The pen's shade panel (arc 49 / P4) — the notebook's arrangement: one device-wide level
        // in the host's prefs, read at open and at every resume, written at every pick.
        penShadePrefs = PenShadePrefs(this)
        paletteBar = PaletteBar(
            root = binding.root,
            bar = binding.paletteBar,
            anchor = binding.btnPen,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            armedLevel = { penShadePrefs.level },
            onPicked = { level -> penShadePrefs.level = level; applyPenShade() },
        )
        applyPenShade()
        // Arc 36 / C2: while the bar is hidden it collapses to a corner tool button with a mini
        // toolbar under it — the four tools and an overflow row that is Back alone here (a note
        // has one door). Built after the toolbar and the sub-bar, because a pick lands on
        // `toolbar.arm` and opening a row takes the sub-bar down.
        collapsed = CollapsedChrome(
            root = binding.root,
            knob = binding.collapsedKnob,
            miniBar = binding.collapsedBar,
            overflowBar = binding.collapsedOverflow,
            paper = paper,
            bandBottom = { chromeBand()?.last },
            canOpen = { shown && !closing },
            overflow = listOf(CollapsedChrome.Entry.mirroring(R.drawable.ic_arrow_left, binding.btnBack)),
            onOpen = { hideEraserBar(); hidePaletteBar() },
            // The shade panel hung off the rows goes with them — a raw hide, the one exclusion
            // push follows in `onChanged` (arc 49 / P4).
            onClose = { paletteBar.hide() },
            onArmed = { toolbar.arm(it) },
            onChanged = ::pushExclusions,
            // Arc 49 / P4: the mini toolbar's pen wears the shade as the bar's does, and its
            // re-tap hangs the shade panel under the row's own button.
            penIcon = {
                val ink = penGlyph.ink
                CollapsedChrome.PenIcon(ink) { ShadeIcon.pen(this, ink) }
            },
            onPenReTap = { anchor -> if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar(anchor) },
        )
        // The lasso wears the clipboard mark exactly as the notebook's does (arc 8): the one
        // standing hint that a pen tap on bare paper will paste. Re-read after every copy/cut.
        syncClipboardMark()

        selectionBar = FloatingSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionBar,
            band = { chromeBand() },
            buttons = listOf(
                FloatingSelectionBar.Button(R.drawable.ic_snap, getString(R.string.snap_action_off)) {
                    paper.releaseRender(); toggleSnap()
                },
                FloatingSelectionBar.Button(R.drawable.ic_copy, getString(R.string.copy_objects_action)) {
                    paper.releaseRender(); doCopy(cut = false)
                },
                FloatingSelectionBar.Button(R.drawable.ic_cut, getString(R.string.cut_objects_action)) {
                    paper.releaseRender(); doCopy(cut = true)
                },
                FloatingSelectionBar.Button(R.drawable.ic_trash, getString(R.string.delete_selection_action)) {
                    paper.releaseRender(); deleteSelection()
                },
            ),
        )
        syncSnapButton()

        // Arc 33: the one bar hides and shows on a finger double-tap, and the flag is the same one
        // every other paper screen reads. The eraser's sub-bar goes down first — its button is
        // about to go — while the lasso's floating bar keeps working over bare paper.
        chromePrefs = ChromePrefs(this)
        chromeToggle = ChromeToggle(
            paper = paper,
            root = binding.root,
            bars = listOf(binding.topBar),
            beforeHide = { hideEraserBar() },
            afterLayout = ::pushExclusions,
            onChanged = { chromePrefs.hidden = it },
            whileHidden = listOf(binding.collapsedKnob),
            beforeShow = { collapsed.dismiss() },
        )

        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selection != null },
            overChrome = { overChrome(it) },
            listener = object : PageGestures.Listener {
                override fun onUndo() = doUndo()
                override fun onRedo() = doRedo()
                // No flips, no inserts, no sheet: a note is one page and has nothing else to hear.
                // The double-tap is the chrome toggle, plainly — a note carries no stickies and no
                // links, so there is nothing here for the notebook's collision rule to arbitrate.
                override fun onFingerDoubleTap(x: Float, y: Float) { toggleChrome() }
            },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit()
        })

        // The note goes on the paper at the container's first layout — the page size needs the
        // real area when the row carries none, and a stroke written before the load would be
        // thrown away by it, so the surface is blocked until then.
        blockAll()
        // The root, not paperContainer (arc 33 / F2): since the paper went full-bleed the container
        // never changes size on a flip, and the root's listener also re-fires after the toggle's
        // own requestLayout.
        binding.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width == 0 || v.height == 0) return@addOnLayoutChangeListener
            if (!shown) showNote(v.width, v.height) else pushExclusions()
        }
        // Applied from the persisted flag before the first layout, so an editor opened hidden never
        // shows its bar. `initial`: nothing is on the glass yet, so no render release.
        chromeToggle.apply(chromePrefs.hidden, releaseRender = false)
        Slog.d(TAG) {
            "open: sticky ${showing.stickyId} ${showing.initial.size} stroke(s) " +
                "${showing.contentW}x${showing.contentH} engine=${paper.engineId}"
        }
    }

    private fun showNote(areaW: Int, areaH: Int) {
        val (w, h) = pageSize(areaW, areaH)
        pageW = w
        pageH = h
        paper.setPageSize(w, h)
        ink.reset(showing.initial)
        undo.clear()
        paper.loadStrokes(showing.initial)
        shown = true
        pushExclusions()
        Slog.d(TAG) { "note shown ${w}x$h (area ${areaW}x$areaH)" }
    }

    /** The note's own size when the row carries one; this device's paper area otherwise. */
    private fun pageSize(areaW: Int, areaH: Int): Pair<Int, Int> =
        if (showing.contentW > 0 && showing.contentH > 0) showing.contentW to showing.contentH
        else areaW to areaH

    override fun onResume() {
        super.onResume()
        // Arc 33: another paper screen (the notebook, the pad, the calendar) may have flipped the
        // one global flag while this one was away — re-sync before the paper comes back.
        if (::chromeToggle.isInitialized) chromeToggle.sync(chromePrefs.hidden)
        // Arc 49 / P4: the same for the pen's shade, one level for every paper screen.
        if (::paletteBar.isInitialized) applyPenShade()
        // Reclaim the pipeline (focus events are unreliable on e-ink) — the notebook released it
        // immediately before launching us.
        if (::paper.isInitialized) paper.resumeDrawing()
    }

    override fun onStop() {
        super.onStop()
        // A leave flush: unbounded, because there may be no next debounce.
        flushNow()
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        saveJob?.cancel()
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::paper.isInitialized) {
            gestures.onTouchEvent(ev)
            val action = ev.actionMasked
            // Every pointer going down, not just the first: with a hand resting on the glass the
            // pen arrives as ACTION_POINTER_DOWN (the notebook's O2 finding).
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                dismissEraserBarOnContact(ev, ev.actionIndex)
                dismissPaletteBarOnContact(ev, ev.actionIndex)
                dismissCollapsedOnContact(ev, ev.actionIndex)
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val tool = ev.getToolType(0)
                val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                // A finger landing on chrome: the bar's buttons would consume the tap before the
                // overlay let go, so release first — pen-gated, as every chrome release is.
                if (!stylus && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Every way out — Back (both): flush, hand the final set to the host, release the
     * pipeline while this view still owns it, and only then finish. Idempotent.
     */
    private fun exit() {
        if (closing) return
        closing = true
        // The floating bars belong to a screen that is leaving.
        hideEraserBar()
        hidePaletteBar()
        dismissCollapsed()
        flushNow()
        StickyEditorTransfer.leave(ink.strokes)
        setResult(Activity.RESULT_OK)
        paper.releaseForHandoff()
        finish()
    }

    // ── The debounced write ──────────────────────────────────────────────────

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            flushNow()
        }
    }

    /** Enqueue the whole set now, if anything changed since the last write. Never suspends. */
    private fun flushNow() {
        saveJob?.cancel()
        saveJob = null
        if (!dirty) return
        dirty = false
        val strokes = ink.strokes
        showing.sink.setContent(strokes)
        Slog.d(TAG) { "flushed ${strokes.size} stroke(s)" }
    }

    // ── Undo / redo (in memory, per showing) ─────────────────────────────────

    private fun doUndo() {
        if (!shown || closing) return
        val a = undo.popUndo() ?: return
        ink.revert(a)
        undo.pushRedo(a)
        reload()
    }

    private fun doRedo() {
        if (!shown || closing) return
        val a = undo.popRedo() ?: return
        ink.reapply(a)
        undo.pushUndo(a)
        reload()
    }

    /** The page-swap order: selection first, pixels hold, then one refresh with the new content. */
    private fun reload() {
        paper.clearSelection()
        selection = null
        selectionBar.hide()
        hideEraserBar()   // a floating bar never survives a content swap
        hidePaletteBar()  // nor the shade panel (arc 49 / P4)
        dismissCollapsed()   // and neither do the corner button's rows (arc 36 / C2)
        paper.clearForContentSwap()
        paper.loadStrokes(ink.strokes)
        pushExclusions()
        scheduleSave()
    }

    // ── The lasso bar ────────────────────────────────────────────────────────

    private fun toggleSnap() {
        val next = !paper.snapToGuides
        paper.snapToGuides = next
        snapPrefs.enabled = next
        syncSnapButton()
    }

    private fun syncSnapButton() {
        val b = selectionBar.buttonAt(0)
        val on = paper.snapToGuides
        b.isSelected = on
        val hint = getString(if (on) R.string.snap_action_on else R.string.snap_action_off)
        b.contentDescription = hint
        TooltipCompat.setTooltipText(b, hint)
    }

    private fun deleteSelection() {
        val ids = selection?.strokeIds?.toList() ?: return
        if (ids.isEmpty()) { paper.clearSelection(); return }
        ink.erase(ids)?.let { undo.record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    /**
     * Copy — or cut, a copy and then the bar's own Delete. The notebook's three orderings, with the
     * drain made unnecessary: the selection is read from [ink], which every act has already
     * updated. **Write, then delete**; then re-arm the lasso so the placement tap that follows
     * places instead of inking (dismissing a selection restores PEN).
     */
    private fun doCopy(cut: Boolean) {
        if (!shown || closing) return
        val sel = selection ?: return
        val strokes = ink.strokes.filter { it.id in sel.strokeIds }
        if (strokes.isEmpty()) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val env = ObjectClip.capture(
                top = StickyClip.rowsFor(strokes, showing.stickyId, now),
                children = emptyList(),
                sourceNotebookId = showing.notebookId,
                now = now,
            )
            if (env == null) {
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_capture_failed)
                return@launch
            }
            val write = runCatching { withContext(Dispatchers.IO) { clipStore.write(env) } }
                .onFailure { Log.w(TAG, "clipboard write failed", it) }
            val header = write.getOrNull()
            if (header == null) {
                val message =
                    if (write.isSuccess) R.string.clip_objects_too_large else R.string.clip_objects_write_failed
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, message)
                return@launch
            }
            if (closing) return@launch
            SnClipboard.set(header)
            syncClipboardMark()
            if (cut) deleteSelection() else paper.clearSelection()
            paper.tool = Tool.LASSO
            toolbar.sync(Tool.LASSO)
            toast(getString(if (cut) R.string.objects_cut_toast else R.string.objects_copied_toast))
            Slog.d(TAG) { "${if (cut) "cut" else "copied"} ${strokes.size} stroke(s)" }
        }
    }

    /**
     * Paste the clipboard's **ink** centred on the tap ([StickyClip]), landing selected under the
     * lasso so the pen drags it into place — the notebook's landing, with its arm-the-lasso rule:
     * a selection under a pen tool is a picture of one.
     */
    private fun doPaste(x: Float, y: Float) {
        lifecycleScope.launch {
            val env = withContext(Dispatchers.IO) { runCatching { clipStore.readEnvelope() }.getOrNull() }
            if (!shown || closing) return@launch
            if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS || env.rows.isEmpty()) {
                // Gone or unreadable. The notebook retires such a clipboard; here the honest answer
                // is the same dialog, and the notebook does the retiring at its next paste.
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@launch
            }
            val extracted = StickyClip.extract(env) { UUID.randomUUID().toString() }
            if (extracted == null || extracted.strokes.isEmpty()) {
                if (extracted != null && extracted.leftOut) {
                    Dialogs.problem(this@StickyEditorActivity, R.string.sticky_paste_no_ink_title, R.string.sticky_paste_no_ink_body)
                } else {
                    Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                }
                return@launch
            }
            val v = paper.asView()
            val (pageW, pageH) = pageSize(v.width, v.height)
            val placed = StickyClip.placed(extracted, x, y, pageW.toFloat(), pageH.toFloat())
            val action = ink.paste(placed) ?: return@launch
            undo.record(action)
            paper.addStrokes(placed)
            scheduleSave()
            // Land it selected, bar up — the lasso armed first (a selection drawn under PEN can be
            // neither dragged nor tapped); the prior tool returns at this selection's dismissal.
            var box = placed.first().bounds
            for (s in placed) box = box.union(s.bounds)
            val ids = placed.mapTo(HashSet()) { it.id }
            armLassoForLanding()
            paper.setSelection(ids, emptySet(), box)
            selection = Selection(ids, emptySet(), box)
            selectionBar.show(box)
            pushExclusions()
            toast(getString(if (extracted.leftOut) R.string.sticky_paste_ink_only_toast else R.string.objects_pasted_toast))
            Slog.d(TAG) { "pasted ${placed.size} stroke(s)${if (extracted.leftOut) " (ink only)" else ""}" }
        }
    }

    /** The notebook's `showClipboardLoaded`, for this screen's lasso button — and, since arc 36,
     *  for the corner button and the mini toolbar's lasso, which wear the same mark. */
    private fun syncClipboardMark() {
        binding.btnLasso.setImageResource(
            if (SnClipboard.hasObjects) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso,
        )
        if (::collapsed.isInitialized) collapsed.showClipboardLoaded(SnClipboard.hasObjects)
    }

    private fun armLassoForLanding() {
        val prior = paper.tool
        if (prior == Tool.LASSO) return
        paper.tool = Tool.LASSO
        toolbar.sync(Tool.LASSO)
        toolBeforeLanding = prior
    }

    /** Put back the tool a paste's landing took away — only while the lasso is still armed (a tool
     *  the user picked meanwhile wins), and pen-idle, because it is a chrome frame. */
    private fun restoreToolAfterLanding() {
        val prior = toolBeforeLanding ?: return
        toolBeforeLanding = null
        if (paper.tool != Tool.LASSO) return
        PenIdle.whenIdle(paper, binding.root) {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenIdle
            paper.tool = prior
            toolbar.sync(prior)
        }
    }

    // ── Chrome geometry ──────────────────────────────────────────────────────

    /** Before the note is on the paper: the whole surface is chrome (nothing may be written). */
    private fun blockAll() {
        val v = paper.asView()
        paper.setExclusionRects(listOf(Rect(0, 0, maxOf(v.width, 1), maxOf(v.height, 1))))
    }

    /**
     * The exclusion rects, in paper px. Since arc 33 / F2 the top bar **floats over** the paper, so
     * it is excluded like every floating bar — and drops out of the list the moment it is hidden
     * ([PaperToolbar.rectOf] refuses a non-VISIBLE view, F1's trap-1 rule). The off-page bands are
     * the other half: an older note's page is smaller than the view, and the paper beyond it is
     * white and writable until it is fenced ([StickyPageRects]).
     */
    private fun pushExclusions() {
        if (!shown) { blockAll(); return }
        val v = paper.asView()
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        val chrome = (
            listOfNotNull(PaperToolbar.rectOf(binding.topBar)) + selectionBar.rects() +
                (if (::eraserBar.isInitialized) eraserBar.rects() else emptyList()) +
                (if (::paletteBar.isInitialized) paletteBar.rects() else emptyList()) +
                (if (::collapsed.isInitialized) collapsed.rects() else emptyList())
            )
            .map { Rect(it.left - loc[0], it.top - loc[1], it.right - loc[0], it.bottom - loc[1]) }
        // Already paper px — the page's own geometry, not a view in the root's coordinates.
        val offPage = StickyPageRects.offPage(pageW, pageH, v.width, v.height).map { it.toRect() }
        paper.setExclusionRects(chrome + offPage)
    }

    /**
     * The free band below the top bar, in the root's coordinates — where a floating bar may be
     * placed. This screen has no bottom bar, so the band runs to the root's own bottom edge; a
     * hidden bar (arc 33) yields the top edge instead. Before [ChromeBand] this read the bar's
     * height and answered null at 0 — which is what a GONE bar reports, so every floating bar
     * would have silently refused to show while the chrome was hidden (F1's trap 2).
     */
    private fun chromeBand(): IntRange? =
        ChromeBand.of(binding.root.height, binding.topBar.asBar(edge = binding.topBar.bottom), null)

    /**
     * The chrome toggle (arc 33): the bar goes and comes back on a finger double-tap, and the flag
     * is persisted for every other paper screen. Only a screen whose note is on the paper and not
     * closing flips — the gesture cannot arm before that, but the escrow can deliver a pair across
     * a close.
     */
    private fun toggleChrome() {
        if (!shown || closing) return
        chromeToggle.toggle()
    }

    private fun overChrome(ev: MotionEvent): Boolean {
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return PaperToolbar.rectOf(binding.topBar)?.contains(x, y) == true ||
            selectionBar.contains(x, y) ||
            (::eraserBar.isInitialized && eraserBar.contains(x, y)) ||
            (::paletteBar.isInitialized && paletteBar.contains(x, y)) ||
            (::collapsed.isInitialized && collapsed.contains(x, y))
    }

    // ── The eraser sub-bar (arc 29 / LE2) ────────────────────────────────────

    /**
     * Open the eraser's sub-bar — Point · Lasso. Gated on the note actually being on the paper,
     * for the surface-block's reason, and **not** pen-idle gated: one chrome frame at a deliberate
     * tap, with the pen that tapped it still hovering (the notebook's floating-bar rule).
     */
    private fun showEraserBar() {
        if (!shown || closing) return
        if (eraserBar.show()) pushExclusions()
    }

    /** Idempotent — every dismiss path calls it without checking. */
    private fun hideEraserBar() {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        eraserBar.hide()
        pushExclusions()
    }

    /** The bar's outside-tap dismissal — the notebook's rule, the eraser button excluded because
     *  its own re-tap would otherwise close the bar here and the toolbar would reopen it. */
    private fun dismissEraserBarOnContact(ev: MotionEvent, index: Int) {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (PaperToolbar.rectOf(binding.btnEraser)?.contains(x, y) == true) return
        if (eraserBar.contains(x, y)) return
        hideEraserBar()
    }

    // ── The pen's shade panel (arc 49 / P4) ──────────────────────────────────

    /** Open the shade panel under the pen button, or under [anchor] (the mini toolbar's own pen
     *  button while the chrome is collapsed). [showEraserBar]'s gate and its frame rule. */
    private fun showPaletteBar(anchor: View? = null) {
        if (!shown || closing) return
        hideEraserBar()
        val opened = if (anchor == null) paletteBar.show() else paletteBar.show(anchor)
        if (opened) pushExclusions()
    }

    /** Idempotent — every dismiss path calls it without checking. */
    private fun hidePaletteBar() {
        if (!::paletteBar.isInitialized || !paletteBar.isShowing) return
        paletteBar.hide()
        pushExclusions()
    }

    /** Arm the pen with the device's shade — at open, at every resume, and after a pick. */
    private fun applyPenShade() {
        val ink = InkTones.tone(penShadePrefs.level)
        paper.penColor = ink
        penGlyph.report(ink)
        if (::collapsed.isInitialized) collapsed.sync()
    }

    /** The panel's outside-tap dismissal — the eraser sub-bar's rule with the pen button excluded,
     *  and the collapsed rows it may be hanging under kept. */
    private fun dismissPaletteBarOnContact(ev: MotionEvent, index: Int) {
        if (!::paletteBar.isInitialized || !paletteBar.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (PaperToolbar.rectOf(binding.btnPen)?.contains(x, y) == true) return
        if (paletteBar.contains(x, y)) return
        if (::collapsed.isInitialized && collapsed.contains(x, y)) return
        hidePaletteBar()
    }

    // ── The collapsed chrome (arc 36 / C2) ───────────────────────────────────

    /** Both of the corner button's rows down. Idempotent, safe before the chrome is built. */
    private fun dismissCollapsed() {
        if (::collapsed.isInitialized) collapsed.dismiss()
    }

    /** The outside-contact dismissal — the rule lives in [CollapsedChrome], and this screen hangs
     *  no sub-bar off the rows, so there is nothing to keep alive under a contact. */
    private fun dismissCollapsedOnContact(ev: MotionEvent, index: Int) {
        if (!::collapsed.isInitialized) return
        // The shade panel hung off the rows keeps them up under a contact of its own (arc 49 / P4).
        collapsed.dismissOnContact(ev.getX(index).toInt(), ev.getY(index).toInt()) { x, y ->
            ::paletteBar.isInitialized && paletteBar.contains(x, y)
        }
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "StickyEditor"

        /** The trailing debounce between an act and its whole-set write (D2). */
        const val DEBOUNCE_MS = 600L

        private const val EXTRA_NOTEBOOK_ID = "notebookId"
        private const val EXTRA_PAGE_ID = "pageId"
        private const val EXTRA_STICKY_ID = "stickyId"

        /** Ids only, for the log — everything the editor works with rides [StickyEditorTransfer]. */
        fun intent(context: Context, notebookId: String, pageId: String, stickyId: String): Intent =
            Intent(context, StickyEditorActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_STICKY_ID, stickyId)
    }
}
