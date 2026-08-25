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
     * Ink that arrived from the notebook onto the **current** page (J5) — the one placement that
     * changes nothing structural. Undo removes exactly what came, redo puts exactly it back; the
     * strokes are carried whole because they were minted on arrival and no row holds them yet.
     *
     * Its own kind rather than an [Erased] with the arms swapped, for the host's `ObjectsPasted`
     * reason: an entry whose ids run the opposite direction cannot share a replay arm without one
     * of the two undoing itself.
     */
    class Pasted(val pageId: String, val strokes: List<Stroke>) : ScratchAction

    /**
     * A page insert or delete, as the two id lists it moved between plus the affected page's ink on
     * **each side** of the move.
     *
     * One shape covers both because the revert is the same sentence either way: put the id list
     * back, write [blob] if the page held ink in that state or drop its ink if it did not, and land
     * on the page that was current. Redo is the mirror, over [afterBlob].
     *
     * The two blobs are what let the shape stretch to J5's third case without a fifth kind:
     *
     * | act | [blob] (the `before` state) | [afterBlob] (the `after` state) |
     * |---|---|---|
     * | insert a blank page | null — no page | null — it lands blank |
     * | delete a page | its ink, so undo puts it back | null — nothing to keep |
     * | a **received new page** (J5) | null — no page | the ink that arrived with it |
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
        val afterBlob: ByteArray? = null,
    ) : ScratchAction
}
