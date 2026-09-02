package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule both lasso sends obey before any bind: ink only, in writing order. */
class TransferSelectionTest {

    private fun stroke(id: String) =
        Stroke(id = id, points = listOf(StrokePoint(1f, 2f, 0.5f, 0f, 0L)))

    /** A live map's values: load order, then commit order — what the notebook actually holds. */
    private val live = linkedMapOf(
        "a" to stroke("a"), "b" to stroke("b"), "c" to stroke("c"), "d" to stroke("d"),
    ).values

    private fun selection(strokeIds: Set<String>, contentIds: Set<String> = emptySet()) =
        Selection(strokeIds, contentIds, Bounds(0f, 0f, 10f, 10f))

    @Test
    fun sendsTheSelectedStrokesInWritingOrder() {
        // The id set's own iteration order is deliberately the reverse of the live order.
        val out = TransferSelection.sendable(selection(linkedSetOf("d", "b")), live)
        assertEquals(listOf("b", "d"), out.map { it.id })
    }

    @Test
    fun aMixedSelectionSendsNothing() {
        // A heading or a link in the set has no wire form — the whole selection is refused rather
        // than silently reduced to its strokes.
        assertTrue(TransferSelection.sendable(selection(setOf("a"), setOf("h1")), live).isEmpty())
    }

    @Test
    fun aContentOnlySelectionSendsNothing() {
        assertTrue(TransferSelection.sendable(selection(emptySet(), setOf("h1")), live).isEmpty())
    }

    @Test
    fun anEmptySelectionSendsNothing() {
        assertTrue(TransferSelection.sendable(selection(emptySet()), live).isEmpty())
    }

    @Test
    fun idsThatAreNoLongerLiveSendNothing() {
        // The selection can outlive the strokes it names (an undo between the show and the tap).
        assertTrue(TransferSelection.sendable(selection(setOf("gone")), live).isEmpty())
    }
}
