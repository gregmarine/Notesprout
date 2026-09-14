package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import java.util.UUID

/**
 * The two ways a shape comes into being or changes (arc 28 / H4) — **insert** and **transform** —
 * kept out of [NotebookActivity] the way [TextFlow] is, and for the reason H1's ledger gave: the
 * screen file is over its documented size cap, and a per-kind flow is exactly the kind of thing
 * that can leave it.
 *
 * **Insert** is [TextFlow]'s twin with the dialog taken out: a shape needs nothing said about it,
 * so the row, the working copy, the undo entry and the landing selection all happen in one Main
 * block, at the page centre, with [ShapeDefaults]' numbers.
 *
 * **Transform** is the arc's one piece of borrowed machinery: g-paper 0.1.27 owns the overlay, the
 * handles, the knob, the aspect lock and the 5° rotation snap (D9), and this class owns only what
 * the engine deliberately does not know — that the thing under the overlay is a `shape` row. Four
 * rules bind it, all learned on the Nomad at H3 and all load-bearing:
 *
 *  1. **Arm the lasso before entering** ([Host.armLassoForLanding]): the mode is a no-op under a
 *     pen tool, so an Insert that left PEN armed would make Transform silently do nothing.
 *  2. **`onTransformChanged` updates the working copy and nothing else** — no store write, and
 *     above all **no `notifyContentChanged()`**: the engine repaints the transform layer through
 *     `ContentRenderer.drawObject` the instant this returns, so a second frame per sample would be
 *     an EPD refresh per sample.
 *  3. **`onTransformEnded` is the one teardown**, and it fires on *every* exit — the bar's own
 *     Done, a tap outside, a tool change, an erase, any data-in call. Persisting anywhere else
 *     would write once per sample and leave chrome standing on the exits nobody asked for.
 *  4. **Nothing is selected after an exit.** Done puts the shape back under the lasso
 *     ([Host.selectAsShape]); every other exit gives the prior tool back
 *     ([Host.restoreToolAfterLanding]), which is what a selection dismissal would have done.
 *
 * Geometry is summarised in the log, never dumped: a size and an angle, no point lists.
 */
class ShapeFlow(private val host: Host) {

    /** What [ShapeFlow] needs of [NotebookActivity], and nothing more. */
    interface Host {

        /** `opened && !closing` — a page is on the glass and the screen is not on its way out. */
        val alive: Boolean

        val session: NotebookSession

        /** The page whose strokes are on the paper — never `session.currentPage` (the R6 torn-read
         *  rule): what the user is transforming is on the page they are looking at. */
        val pageId: String

        val objects: PageObjects

        val paper: PaperView

        val density: Float

        fun record(action: Action)

        /** Land the selection on [shape] — `setSelection` is host-initiated (no `onSelectionCreated`
         *  echo), so the screen's flags and the bar are set by hand over there. */
        fun selectAsShape(shape: PageShape)

        /** Arm the lasso before a selection lands, or a transform begins, under a tool that is not
         *  it. The prior tool comes back at the selection's dismissal, or at a non-Done exit.
         *  Call BEFORE `setSelection` and BEFORE `beginTransform` (the O2 ordering). */
        fun armLassoForLanding()

        /** Every box already on the displayed page — texts, shapes, stickies, headings, links and
         *  the live ink — for [FreePlacement]: a drop lands at the nearest clear spot to the
         *  centre, never on top of what is there. */
        fun occupied(): List<Bounds>

        /** Put back the tool the landing took away — [NotebookActivity]'s
         *  `restoreToolAfterTransferPaste`. */
        fun restoreToolAfterLanding()

        /** Clear the screen's selection flags and hide the lasso bar by hand: `beginTransform`
         *  dismisses the engine's selection **without** `onSelectionDismissed`, so nothing else
         *  would ever take that chrome down. */
        fun dismissSelectionChrome()

        /** Put the floating transform bar up (or re-place it) for [shape]. */
        fun showTransformBar(shape: PageShape)

        /** Re-word the bar's aspect-lock latch without moving it. */
        fun relabelTransformBar(shape: PageShape)

        /** Whether the live overlay for [shape] now reaches the bar where it stands. */
        fun transformBarCovers(shape: PageShape): Boolean

        fun hideTransformBar()
    }

    /** The shape as it was when the mode began — the `before` side of the undo entry. */
    private var began: PageShape? = null

    /** The same shape with the live box written over it; what the renderer draws mid-drag. */
    private var working: PageShape? = null

    /** The page the mode began on — a transform that outlives a flip still persists to it. */
    private var transformPageId: String = ""

    /** Whether the exit about to be reported is the bar's own Done (the one that re-selects). */
    private var doneRequested = false

    // ── Insert (the Insert bar's six shape buttons) ──────────────────────────

    /**
     * Insert → one of the six: place [type] at the page centre with [ShapeDefaults]' numbers and
     * land it selected, so the very next drag moves it where it is wanted.
     *
     * Unlike a text object there is nothing to ask first — a shape is complete the moment it has a
     * type and a box — so the row, the working copy, the undo entry and the selection all land in
     * **one Main block**, which is one EPD frame.
     */
    fun insertAtCentre(type: ShapeType) {
        if (!host.alive) return
        val pageId = host.pageId
        val page = host.session.pages.firstOrNull { it.id == pageId } ?: return
        val shape = ShapeDefaults.at(
            id = UUID.randomUUID().toString(),
            type = type,
            pageWidth = page.width.toFloat(),
            pageHeight = page.height.toFloat(),
            density = host.density,
        ).let { built ->
            // Sized by the defaults, placed by FreePlacement: the centre when clear, else the
            // nearest clear spot. A fresh shape is unrotated, so its box is width × height.
            val (x, y) = FreePlacement.nearCentre(
                page.width.toFloat(), page.height.toFloat(), built.width, built.height,
                host.occupied(), host.density,
            )
            built.copy(cx = x + built.width / 2f, cy = y + built.height / 2f)
        }
        host.session.shapes.create(pageId, shape)
        host.objects.put(shapes = listOf(shape))
        host.record(Action.ShapeInserted(pageId, shape))
        // Insert leaves the armed tool alone (D4); a selection under PEN can be neither dragged
        // nor tapped, so the lasso is armed for this selection's life and the pen comes back at
        // its dismissal.
        host.armLassoForLanding()
        host.selectAsShape(shape)
        host.paper.notifyContentChanged()
        Slog.d(TAG) { "inserted a $type: ${shape.width.toInt()}x${shape.height.toInt()} px" }
    }

    // ── Transform (the lasso bar's Transform button) ─────────────────────────

    /**
     * Enter the engine's transform mode on the lone selected shape. [shapeId] is resolved against
     * the working copy **at tap time**, never captured when the bar went up: the selection can
     * move, die or change kind between those two moments.
     */
    fun beginTransform(shapeId: String) {
        if (!host.alive) return
        val shape = host.objects.shapes[shapeId] ?: return
        // The mode requires Tool.LASSO and is a silent no-op in any other tool — and an inserted
        // shape is routinely selected with PEN still armed underneath.
        host.armLassoForLanding()
        transformPageId = host.pageId
        began = shape
        working = shape
        doneRequested = false
        // `beginTransform` dismisses the selection WITHOUT `onSelectionDismissed`, so the flags
        // and the lasso bar have to be cleared here or they would stand over the overlay.
        host.dismissSelectionChrome()
        host.paper.beginTransform(
            shape.id,
            ShapeBox.toBox(shape),
            shape.aspectLocked,
            minSizePx = ShapeDefaults.MIN_SIZE_DP * host.density,
        )
        if (host.paper.transformingContentId != shape.id) {
            // The engine declined (a released surface). No `onTransformEnded` will ever come, so
            // the one teardown cannot be relied on: undo what this method did and put the shape
            // back under the lasso it was under a moment ago.
            began = null
            working = null
            host.selectAsShape(shape)
            return
        }
        host.showTransformBar(shape)
        Slog.d(TAG) { "transform began on a ${shape.type}" }
    }

    /** The bar's aspect latch. The engine takes the flip on the next resize; the row takes it at
     *  the exit — a lock change alone is a change worth an undo entry. */
    fun toggleAspectLock() {
        val current = working ?: return
        val next = current.copy(aspectLocked = !current.aspectLocked)
        working = next
        host.paper.setTransformAspectLocked(next.aspectLocked)
        host.relabelTransformBar(next)
    }

    /** The bar's Done. `endTransform` fires [onTransformEnded] synchronously — everything else
     *  (persist, undo, teardown, re-select) happens there, on this exit like any other. */
    fun done() {
        if (working == null) return
        doneRequested = true
        host.paper.endTransform()
    }

    /**
     * The live box (`PaperListener.onTransformChanged`). The working copy is the whole of the
     * update: the engine repaints the transform layer through `ShapeRenderer.drawObject` the
     * moment this returns, so asking for a frame here would be one EPD refresh per sample.
     *
     * The bar is re-placed only when the growing overlay has actually reached it. A bar that
     * chased every sample would be a second thing moving under the hand.
     */
    fun onTransformChanged(contentId: String, box: OrientedBox) {
        val current = working ?: return
        if (contentId != current.id) return
        val next = ShapeBox.applied(current, box)
        working = next
        host.objects.put(shapes = listOf(next))
        if (host.transformBarCovers(next)) host.showTransformBar(next)
    }

    /**
     * The one teardown (`PaperListener.onTransformEnded`) — it fires on **every** exit, the bar's
     * own Done included, so nothing here may assume which one it was beyond [doneRequested].
     *
     * [PageShape]s are compared, not [OrientedBox]es: an aspect-lock flip with no drag changes no
     * geometry at all and is still a change the user made and can undo.
     */
    fun onTransformEnded(contentId: String, before: OrientedBox, after: OrientedBox) {
        val beforeShape = began
        val live = working
        val pageId = transformPageId
        val done = doneRequested
        began = null
        working = null
        doneRequested = false
        host.hideTransformBar()
        if (beforeShape == null || contentId != beforeShape.id) return
        val afterShape = ShapeBox.applied(
            beforeShape.copy(aspectLocked = live?.aspectLocked ?: beforeShape.aspectLocked),
            after,
        )
        val changed = afterShape != beforeShape
        if (changed) {
            host.session.shapes.transform(afterShape)
            host.record(Action.ShapeTransformed(pageId, beforeShape, afterShape))
        }
        // Nothing is selected after an exit. Done keeps the shape under the lasso; every other
        // exit — a tap outside, a tool change, an erase, a data-in call — gives back the tool the
        // landing took away, exactly as a selection dismissal would have.
        if (!(done && host.alive && pageId == host.pageId)) host.restoreToolAfterLanding()
        // Beyond here everything touches the *visible* page. The rows above are already right; a
        // screen that has flipped away (or is closing) must not have another page's shape written
        // into its working copy (TextFlow's rule after an async create).
        if (!host.alive || pageId != host.pageId) return
        // Either the new geometry, or — when nothing changed overall — what the row says, since
        // the working copy may hold a live intermediate from a gesture the engine then cancelled.
        host.objects.put(shapes = listOf(if (changed) afterShape else beforeShape))
        if (done) host.selectAsShape(afterShape)
        // The engine has already restored the object to the committed record; this is what makes
        // that record hold the *final* geometry rather than the one the mode began on.
        host.paper.notifyContentChanged()
        Slog.d(TAG) {
            "transform ended (${if (done) "done" else "exit"}): " +
                "${afterShape.width.toInt()}x${afterShape.height.toInt()} @${afterShape.rotationDeg}"
        }
    }

    private companion object {
        const val TAG = "ShapeFlow"
    }
}
