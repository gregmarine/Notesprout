package com.symmetricalpalmtree.notesproutsn.data.index

import com.symmetricalpalmtree.notesproutsn.data.index.IndexCompactor.Edge
import com.symmetricalpalmtree.notesproutsn.templates.TemplateLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index purge's pure half (arc 17 / K1). The stakes are the arc-13 prune trap: a pinned-
 * templates edge may point at a built-in sentinel that is not a row, and a sweep that treats
 * "no row" as "orphan" silently unpins every built-in paper.
 */
class IndexCompactorTest {

    @Test
    fun anEdgeToAnExistingRowIsKept() {
        val edges = listOf(Edge("e1", "row1"))
        assertTrue(IndexCompactor.orphanEdgeIds(edges, setOf("row1")).isEmpty())
    }

    @Test
    fun anEdgeToAMissingRowIsSwept() {
        val edges = listOf(Edge("e1", "gone"), Edge("e2", "row1"))
        assertEquals(listOf("e1"), IndexCompactor.orphanEdgeIds(edges, setOf("row1")))
    }

    @Test
    fun everySentinelEdgeIsKept() {
        // The pinned-templates shelf's built-ins are ids with no row behind them — ever.
        val edges = IndexCompactor.PROTECTED_REF_IDS.mapIndexed { i, id -> Edge("e$i", id) }
        assertTrue(IndexCompactor.orphanEdgeIds(edges, emptySet()).isEmpty())
    }

    @Test
    fun aNullRefIdSurvives() {
        // Malformed in a way this pass does not understand — left exactly as it came.
        assertTrue(IndexCompactor.orphanEdgeIds(listOf(Edge("e1", null)), emptySet()).isEmpty())
    }

    @Test
    fun theProtectedSetIsTheTemplateLibrarySentinels() {
        // IndexCompactor spells the set from ListIds so the data layer does not reach into a
        // screen package; this is the pin that keeps the two from drifting.
        assertEquals(TemplateLibrary.SENTINEL_IDS, IndexCompactor.PROTECTED_REF_IDS)
    }
}
