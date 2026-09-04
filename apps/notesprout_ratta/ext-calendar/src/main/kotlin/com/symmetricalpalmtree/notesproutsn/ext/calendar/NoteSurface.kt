package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.notebook.InkSelectionBar
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack

/**
 * The event note's handwriting half (arc 24 / Z3): one page of ink on a **bounded** g-paper surface
 * under the editor's fields — the first second paper surface in one process, measured on the Nomad
 * before it was built (the plan's Z3 step 0).
 *
 * **What it is not.** Not an `InkScreenActivity`: the editor is a form with a paper *view* in it,
 * not a paper screen with chrome over it. So this class owns what the skeleton would have — the
 * paper, the [InkDocument] over [NoteSql], the in-memory undo, the finger gestures and the lasso's
 * one-button bar — and leaves out what the skeleton exists for: there is **no debounce and no
 * leave flush**. The note is kept in memory until the event's Save, where [write] hands its
 * statements to the store to ride the event's own transaction; Cancel discards ink exactly as it
 * discards a typed title.
 *
 * **The page size is minted, not measured twice.** A note that already has a size (`event.noteWidth
 * × noteHeight`, written with its first stroke) keeps it wherever it is shown, anchored top-left,
 * 1:1 — the pad's rule. A note without one takes the area's size at its **first** layout, and holds
 * it: the keyboard shrinks the view under `adjustResize`, never the page. [mintedSize] is what Save
 * writes — the page's size once there is ink, and the stored size (possibly none) while there is not.
 * The page carries one template (arc 24 / Z5a) — the "Notes" band label at the page's top-left,
 * baked at the page's own size and rebaked only when that size changes.
 *
 * **Blocked is how the surface goes away.** While the keyboard is up, or the Text half is showing,
 * the view is `INVISIBLE` **and** the whole of it is an exclusion rect: an attached Ratta paper view
 * keeps the firmware pen claimed whatever its visibility, and the firmware paints wherever it is
 * not told not to — a hidden view without the block would still ink under the keyboard. Before the
 * page is on the paper the same block stands, so nothing can be written that a load would then
 * throw away.
 *
 * **The EPD handoff is the probe's proven shape**: the calendar behind the events list touches
 * nothing (its view tears down on focus loss, the engine's process-local ownership guard does the
 * rest); this surface reclaims in the editor's `onResume` ([resume]), releases before **every**
 * `finish()` ([handoff]) and is torn down in `onDestroy` ([release]). A failure there goes to
 * g-paper, never a host workaround.
 *
 * Frame silence: the bar's show at lasso completion (and its re-anchor after a move) is the
 * ledgered exception, as on every paper screen.
 */
class NoteSurface(
    activity: AppCompatActivity,
    /** The note host — the parent the selection bar floats in; its rect is the bar's whole band. */
    private val host: FrameLayout,
    paperContainer: FrameLayout,
    selectionBarView: LinearLayout,
    deleteHint: String,
    /** Bakes the note page's one template (arc 24 / Z5a) — the "Notes" band label — at the given
     *  page size. Called only when the size actually changes; see [applyPageSize]. */
    private val bakeTemplate: (width: Int, height: Int) -> Bitmap,
) {

    val paper: PaperView = GPaper.create(activity)
    private val document = InkDocument(NoteSql, TAG)
    private val undo = UndoRedoStack<InkAction>()
    private val selectionBar: InkSelectionBar
    private val gestures: PageGestures

    private var selection: Selection? = null

    /** The ids the page was loaded with — what [write] must **not** list as minted. */
    private var loadedIds: Set<String> = emptySet()

    /** The size the event already holds for its note, 0 × 0 until a first stroke minted one. */
    private var storedWidth = 0f
    private var storedHeight = 0f

    /** The page size the current [template] was baked at — null before the first bake. */
    private var templateSize: Pair<Int, Int>? = null
    private var template: Bitmap? = null

    /** The area at its first layout — the size a note without one of its own is minted at. */
    private var areaWidth = 0
    private var areaHeight = 0

    private var shown = false

    /**
     * True while the surface must not be written on — the keyboard is up, or the Text half is
     * showing. The view goes `INVISIBLE` and the whole of it becomes an exclusion rect; see the
     * class note for why both.
     */
    var blocked: Boolean = false
        set(value) {
            field = value
            applyBlock()
        }

    // ── g-paper → the document ───────────────────────────────────────────────

    private val paperListener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!shown || blocked) return
            document.addStroke(stroke)
            undo.record(InkAction.Drew(document.pageId, stroke))
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!shown || blocked) return
            document.erase(strokeIds)?.let { undo.record(it) }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!shown || blocked) return
            document.move(move.strokeIds, move.dx, move.dy)?.let { undo.record(it) }
            selection = selection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            selection?.let { selectionBar.show(it.bounds) }
        }

        override fun onSelectionCreated(selection: Selection) {
            this@NoteSurface.selection = selection
            // Not pen-idle-gated: a lasso ends with the pen still hovering, and the engine has
            // already presented the box — this frame is part of that presentation.
            selectionBar.show(selection.bounds)
        }

        override fun onSelectionDragStarted() = selectionBar.hide()

        override fun onSelectionDismissed() {
            selection = null
            selectionBar.hide()
        }
    }

    init {
        paperContainer.addView(
            paper.asView(),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // The notebook's fixed values: a closed loop arms the lasso, a dense scribble erases. There
        // is no tool button on this surface, so these two gestures and the pen's eraser end are the
        // whole of "eraser + lasso" (the locked "Note — tools" decision).
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.setPaperListener(paperListener)
        selectionBar = InkSelectionBar(
            root = host,
            paperView = paper.asView(),
            bar = selectionBarView,
            band = { if (host.height == 0) null else 0..host.height },
            releaseRender = { paper.releaseRender() },
            deleteHint = deleteHint,
            onDelete = { deleteSelection() },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selection != null },
            overChrome = { selectionBar.contains(it.x.toInt(), it.y.toInt()) },
            listener = object : PageGestures.Listener {
                override fun onUndo() = doUndo()
                override fun onRedo() = doRedo()
                // No flips, no inserts, no taps: a note is one page and has nothing else to hear.
            },
        )
        paperContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width == 0 || v.height == 0) return@addOnLayoutChangeListener
            if (areaWidth == 0) {
                areaWidth = v.width
                areaHeight = v.height
                Slog.d(TAG) { "area ${v.width}x${v.height}" }
                applyPageSize()
            }
            // A resize (the keyboard) moves the whole-view block with it.
            applyBlock()
        }
        applyBlock()
    }

    // ── The page ─────────────────────────────────────────────────────────────

    /**
     * Put [eventId]'s note on the paper: [ink] as read from the store, and the size the event
     * holds for it ([width] × [height], 0 × 0 when it has none). In-memory history starts over.
     */
    fun show(eventId: String, ink: List<Pair<Long, Stroke>>, width: Float, height: Float) {
        document.reset(eventId, ink)
        loadedIds = ink.map { it.second.id }.toHashSet()
        storedWidth = width
        storedHeight = height
        undo.clear()
        selection = null
        selectionBar.hide()
        applyPageSize()
        paper.loadStrokes(document.strokes)
        shown = true
        applyBlock()
        Slog.d(TAG) { "note shown: ${ink.size} stroke(s), page ${pageSize().first.toInt()}x${pageSize().second.toInt()}" }
    }

    val hasStrokes: Boolean get() = shown && document.strokes.isNotEmpty()

    /** Whether anything on the page differs from what was loaded — Cancel's "did the note move". */
    val hasUnsavedChanges: Boolean get() = shown && document.hasUnsavedChanges

    /**
     * The page's size: the stored one when the event has minted it, else the area's first layout
     * — 0 × 0 before either exists (then g-paper uses the view's own size, which is the same thing
     * until the first layout lands and pins it).
     */
    fun pageSize(): Pair<Float, Float> =
        if (storedWidth > 0f && storedHeight > 0f) storedWidth to storedHeight
        else areaWidth.toFloat() to areaHeight.toFloat()

    /**
     * The size Save writes for the note: the page's, once there is ink on it ("minted with the
     * first stroke"), and whatever the event already held while there is not.
     */
    fun mintedSize(): Pair<Float, Float> = if (hasStrokes) pageSize() else storedWidth to storedHeight

    /**
     * The note's contribution to a save landing under [landedUnder] (the store's answer — see
     * [NoteWrite]): the pending op log when that is the id the note was loaded for, a whole copy
     * under fresh stroke ids when it is not. Nothing is cleared — the screen closes on success and
     * keeps everything on failure.
     */
    fun write(landedUnder: String): NoteWrite {
        if (!shown) return NoteWrite.NONE
        if (landedUnder == document.pageId) {
            val minted = document.strokes.map { it.id }.filter { it !in loadedIds }
            return NoteWrite(document.pendingStatements(), minted)
        }
        return NoteWrite.copy(document.entries(), landedUnder, CalendarStore::newId)
    }

    private fun deleteSelection() {
        val ids = selection?.strokeIds?.toList() ?: return
        if (ids.isEmpty()) { paper.clearSelection(); return }
        document.erase(ids)?.let { undo.record(it) }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    // ── Undo / redo (in memory, per showing; synchronous — nothing here writes) ──

    private fun doUndo() {
        if (!shown || blocked) return
        val a = undo.popUndo() ?: return
        document.revert(a)
        undo.pushRedo(a)
        reload()
    }

    private fun doRedo() {
        if (!shown || blocked) return
        val a = undo.popRedo() ?: return
        document.reapply(a)
        undo.pushUndo(a)
        reload()
    }

    /** The page-swap order: selection first, pixels hold, then one refresh with the new content. */
    private fun reload() {
        paper.clearSelection()
        selection = null
        selectionBar.hide()
        paper.clearForContentSwap()
        paper.loadStrokes(document.strokes)
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    private fun applyPageSize() {
        val (w, h) = pageSize()
        if (w <= 0f || h <= 0f) return
        paper.setPageSize(w.toInt(), h.toInt())
        val size = w.toInt() to h.toInt()
        if (size == templateSize) return
        // The replaced bitmap is recycled — g-paper holds only the one it was last given
        // (`CalendarActivity.applyTemplate`'s rule).
        val fresh = bakeTemplate(size.first, size.second)
        val old = template
        paper.setTemplate(fresh)
        old?.recycle()
        template = fresh
        templateSize = size
    }

    private fun applyBlock() {
        val v = paper.asView()
        val block = blocked || !shown
        v.visibility = if (blocked) View.INVISIBLE else View.VISIBLE
        if (block) {
            paper.clearSelection()
            paper.setExclusionRects(listOf(Rect(0, 0, maxOf(v.width, 1), maxOf(v.height, 1))))
        } else {
            paper.setExclusionRects(emptyList())
        }
    }

    // ── The editor's hooks ───────────────────────────────────────────────────

    /**
     * Observer only — the editor's `dispatchTouchEvent` feeds it every event. The finger gestures
     * (undo/redo) and the EPD chrome-release for a finger landing on the selection bar, which the
     * bar's buttons would otherwise consume before the overlay let go.
     */
    fun onTouch(ev: MotionEvent) {
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !paper.isPenActive && selectionBar.contains(ev.x.toInt(), ev.y.toInt())) paper.releaseRender()
        }
    }

    /** The editor's `onResume`: reclaim the pipeline (focus events are unreliable on e-ink). */
    fun resume() = paper.resumeDrawing()

    /** Immediately before **every** `finish()`: release while this view still owns the pipeline. */
    fun handoff() = paper.releaseForHandoff()

    /** The editor's `onDestroy`. */
    fun release() {
        paper.release()
        template?.recycle()
        template = null
    }

    private companion object {
        const val TAG = "NoteSurface"
    }
}
