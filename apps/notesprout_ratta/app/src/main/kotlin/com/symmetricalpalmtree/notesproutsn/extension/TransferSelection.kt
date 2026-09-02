package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The one selection rule every outbound ink transfer obeys (arc 23 / Y4 — pure, JVM-tested). Both
 * lasso sends — Send to Scratch Pad (J5) and Send to Calendar (Y3) — asked it in their own words
 * before; a rule written twice is a rule that drifts.
 *
 * **Ink only.** The selection toolbar's button is already gone on anything else, but the selection
 * can change kind between the show and the tap, and `WireStroke` is the whole of what either
 * contract carries — a heading or a link in the set has no honest wire form, so a mixed selection
 * sends **nothing** rather than silently sending the strokes out of it.
 *
 * **Writing order survives.** The strokes come from the caller's live map (a map filled by load then
 * by commit, so its values are in writing order) filtered by the id set — never by iterating the Set
 * itself, whose order is its own.
 */
object TransferSelection {

    /** The strokes this selection may send, in writing order — **empty** when it is not ink-only,
     *  when it holds no strokes, or when none of its ids is live. */
    fun sendable(selection: Selection, live: Collection<Stroke>): List<Stroke> {
        if (selection.contentIds.isNotEmpty() || selection.strokeIds.isEmpty()) return emptyList()
        val ids = selection.strokeIds
        return live.filter { it.id in ids }
    }
}
