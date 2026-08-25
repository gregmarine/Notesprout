package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * What the pad can put back (arc 11 / J4). Four kinds, against the notebook's fourteen: the pad has
 * no headings, no links and no clipboard, so an edit is a stroke, a set of strokes, a translation,
 * or a change to the page list itself.
 *
 * The history itself is `:sn-screen`'s [com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack]
 * typed on [ScratchAction]; the replay lives in [ScratchDocument], which mutates the store and then
 * reloads the page — the same "the store is the source of truth" rule the notebook replays under.
 *
 * `Pasted` (a received placement onto the current page) arrives in J5.
 */
sealed interface ScratchAction {

    /** One committed stroke. */
    class Drew(val pageId: String, val stroke: Stroke) : ScratchAction

    /**
     * Strokes taken off a page by the eraser or by the selection's Delete, each with **the index it
     * held**: putting them back where they were keeps a page's stroke order stable across an
     * undo/redo cycle, which is what makes the encoded blob stable too.
     */
    class Erased(val pageId: String, val entries: List<Entry>) : ScratchAction {
        class Entry(val index: Int, val stroke: Stroke)
    }

    /** A selection drag. Reverted by translating back — and **re-measured**, because stroke
     *  geometry is zlib-compressed per stroke and the same floats do not re-encode to the same size. */
    class Moved(val pageId: String, val ids: List<String>, val dx: Float, val dy: Float) : ScratchAction

    /**
     * A page insert or delete, as the two id lists it moved between plus the affected page's blob.
     *
     * One shape covers both because the revert is the same sentence either way: put the id list
     * back, restore [blob] if there was one (a delete) or drop the page's ink if there was not (an
     * insert lands blank), and land on the page that was current. Redo is its mirror, and always
     * drops the blob — a re-inserted page is blank and a re-deleted one has no ink to keep.
     *
     * Deleting the **last** page empties it rather than removing it, so `before == after` there and
     * [blob] carries the ink that was emptied.
     */
    class Page(
        val before: List<String>,
        val beforeCurrent: String,
        val after: List<String>,
        val afterCurrent: String,
        val pageId: String,
        val blob: ByteArray?,
    ) : ScratchAction
}
