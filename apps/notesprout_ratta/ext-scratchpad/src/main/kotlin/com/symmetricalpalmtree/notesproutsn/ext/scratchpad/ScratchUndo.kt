package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.PageInk

/**
 * What the pad can put back (arc 11 / J4, grown in J5; split in arc 23 / Y1). Two shapes: an
 * **ink** edit — one of `:ext-ink`'s four [InkAction]s (a stroke, a set of strokes, a translation,
 * ink that arrived from the notebook), wrapped so the stack stays one sealed type — and a change to
 * the **page list itself**, which is the pad's own and stays here: the calendar has no page list.
 *
 * The history itself is `:sn-screen`'s [com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack]
 * typed on [ScratchAction]; the replay lives in [ScratchDocument], which lands on the action's page,
 * mutates, writes the store and reloads — the same "the store is the source of truth" rule the
 * notebook replays under.
 */
sealed interface ScratchAction {

    /** A stroke-level edit on one page — the replay is [com.symmetricalpalmtree.notesproutsn.ink.InkDocument]'s. */
    class Ink(val action: InkAction) : ScratchAction

    /**
     * A page insert or delete, as the two id lists it moved between plus the affected page's ink on
     * **each side** of the move.
     *
     * One shape covers both because the revert is the same sentence either way: put the id list
     * back, re-create the page with [ink] and its strokes if it held any in that state or drop the
     * page if it did not exist, and land on the page that was current. Redo is the mirror, over
     * [afterInk].
     *
     * The two inks are what let the shape stretch to J5's third case without another kind:
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
