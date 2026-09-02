package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The stroke-level edits a paper-owning extension can put back (arc 11 / J4–J5 as the pad's
 * `ScratchAction`; the four stroke kinds shared as `:ext-ink` since arc 23 / Y1). An edit is a
 * stroke, a set of strokes, a translation, or ink that arrived from the notebook — every one names
 * the page it happened on, so a history that spans pages (the pad's page list, the calendar's
 * periods) can navigate there before replaying.
 *
 * The history itself is `:sn-screen`'s `UndoRedoStack`, typed by each consumer on its own action
 * type (the pad wraps these under a `ScratchAction` alongside its page-level `Page` action, which
 * stays in the pad — the calendar has no page list to edit). The replay of these four is
 * [InkDocument.revert] / [InkDocument.reapply]: in memory, on the page the document is showing, with
 * the consumer flushing afterwards — the same "the store is the source of truth" rule the notebook
 * replays under.
 */
sealed interface InkAction {

    /** The page the edit happened on — a replay lands there first. */
    val pageId: String

    /** One committed stroke. */
    class Drew(override val pageId: String, val stroke: Stroke) : InkAction

    /**
     * Strokes taken off a page by the eraser or by the selection's Delete, each with **the `"order"`
     * it held** (arc 22 / X2 — the row's own column, not a position in a list). Orders are unique
     * per page and monotone, so nothing else can have taken one back: putting a stroke back at its
     * order lands it exactly where it was, which is what keeps a page's ink stable across an
     * undo/redo cycle.
     */
    class Erased(override val pageId: String, val entries: List<Entry>) : InkAction {
        class Entry(val order: Long, val stroke: Stroke)
    }

    /** A selection drag. Reverted by translating back; each moved stroke's row is rewritten at the
     *  order it already held. */
    class Moved(override val pageId: String, val ids: List<String>, val dx: Float, val dy: Float) : InkAction

    /**
     * Ink that arrived from the notebook onto an existing page (J5) — the one placement that changes
     * nothing structural. Undo removes exactly what came, redo puts exactly it back; the strokes are
     * carried whole because they were minted on arrival and no row holds them yet.
     *
     * Its own kind rather than an [Erased] with the arms swapped, for the host's `ObjectsPasted`
     * reason: an entry whose ids run the opposite direction cannot share a replay arm without one
     * of the two undoing itself.
     */
    class Pasted(override val pageId: String, val strokes: List<Stroke>, val orders: List<Long>) : InkAction {
        init {
            require(strokes.size == orders.size) { "${strokes.size} strokes for ${orders.size} orders" }
        }
    }
}
