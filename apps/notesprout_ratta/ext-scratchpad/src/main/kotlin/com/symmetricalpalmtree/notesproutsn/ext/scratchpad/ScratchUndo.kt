package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * What the pad can put back (arc 11 / J4, grown in J5). Five kinds, against the notebook's
 * fourteen: the pad has no headings, no links and no clipboard, so an edit is a stroke, a set of
 * strokes, a translation, ink that arrived from the notebook, or a change to the page list itself.
 *
 * The history itself is `:sn-screen`'s [com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack]
 * typed on [ScratchAction]; the replay lives in [ScratchDocument], which mutates the store and then
 * reloads the page — the same "the store is the source of truth" rule the notebook replays under.
 *
 * `Pasted` (arc 11 / J5) is the fifth: ink that arrived from the notebook onto the **current**
 * page. A received **new page** is not one of these — it is a [ScratchAction.Page], because undoing
 * it has to take the page away with its cargo.
 */
sealed interface ScratchAction {

    /** One committed stroke. */
    class Drew(val pageId: String, val stroke: Stroke) : ScratchAction

    /**
     * Strokes taken off a page by the eraser or by the selection's Delete, each with **the `"order"`
     * it held** (arc 22 / X2 — the row's own column, not a position in a list). Orders are unique
     * per page and monotone, so nothing else can have taken one back: putting a stroke back at its
     * order lands it exactly where it was, which is what keeps a page's ink stable across an
     * undo/redo cycle.
     */
    class Erased(val pageId: String, val entries: List<Entry>) : ScratchAction {
        class Entry(val order: Long, val stroke: Stroke)
    }

    /** A selection drag. Reverted by translating back; each moved stroke's row is rewritten at the
     *  order it already held. */
    class Moved(val pageId: String, val ids: List<String>, val dx: Float, val dy: Float) : ScratchAction

    /**
     * Ink that arrived from the notebook onto the **current** page (J5) — the one placement that
     * changes nothing structural. Undo removes exactly what came, redo puts exactly it back; the
     * strokes are carried whole because they were minted on arrival and no row holds them yet.
     *
     * Its own kind rather than an [Erased] with the arms swapped, for the host's `ObjectsPasted`
     * reason: an entry whose ids run the opposite direction cannot share a replay arm without one
     * of the two undoing itself.
     */
    class Pasted(val pageId: String, val strokes: List<Stroke>, val orders: List<Long>) : ScratchAction {
        init {
            require(strokes.size == orders.size) { "${strokes.size} strokes for ${orders.size} orders" }
        }
    }

    /**
     * A page insert or delete, as the two id lists it moved between plus the affected page's ink on
     * **each side** of the move.
     *
     * One shape covers both because the revert is the same sentence either way: put the id list
     * back, re-create the page with [ink] and its strokes if it held any in that state or drop the
     * page if it did not exist, and land on the page that was current. Redo is the mirror, over
     * [afterInk].
     *
     * The two inks are what let the shape stretch to J5's third case without a fifth kind:
     *
     * | act | [ink] (the `before` state) | [afterInk] (the `after` state) |
     * |---|---|---|
     * | insert a blank page | null — no page | null — it lands blank |
     * | delete a page | its ink, so undo puts it back | null — nothing to keep |
     * | a **received new page** (J5) | null — no page | the ink that arrived with it |
     *
     * Deleting the **last** page empties it rather than removing it, so `before == after` there and
     * [ink] carries the ink that was emptied.
     */
    class Page(
        val before: List<String>,
        val beforeCurrent: String,
        val after: List<String>,
        val afterCurrent: String,
        val pageId: String,
        val ink: PageInk?,
        val afterInk: PageInk? = null,
    ) : ScratchAction
}
