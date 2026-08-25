package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pad's page-list arithmetic on top of the shared `PageMath`. */
class ScratchPagesTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun insertAfterTheCurrentPage() {
        assertEquals(listOf("a", "b", "n", "c"), ScratchPages.insertAfter(ids, "b", "n"))
        assertEquals(listOf("a", "b", "c", "n"), ScratchPages.insertAfter(ids, "c", "n"))
    }

    @Test
    fun insertAfterAnUnknownIdGoesToTheEnd() {
        assertEquals(listOf("a", "b", "c", "n"), ScratchPages.insertAfter(ids, "zz", "n"))
        assertEquals(listOf("a", "b", "c", "n"), ScratchPages.insertAfter(ids, null, "n"))
    }

    @Test
    fun insertBeforeTheCurrentPage() {
        assertEquals(listOf("a", "n", "b", "c"), ScratchPages.insertBefore(ids, "b", "n"))
        assertEquals(listOf("n", "a", "b", "c"), ScratchPages.insertBefore(ids, "a", "n"))
    }

    @Test
    fun insertBeforeAnUnknownIdGoesToTheStart() {
        assertEquals(listOf("n", "a", "b", "c"), ScratchPages.insertBefore(ids, "zz", "n"))
    }

    @Test
    fun deleteLandsOnThePreviousPage() {
        assertEquals(listOf("a", "c") to "a", ScratchPages.delete(ids, "b"))
        assertEquals(listOf("b", "c") to "b", ScratchPages.delete(ids, "a"))
        assertEquals(listOf("a", "b") to "b", ScratchPages.delete(ids, "c"))
    }

    @Test
    fun deleteNeverGoesBelowOnePage() {
        // The lone page keeps its id and is emptied by the caller — a pad always has a page.
        assertEquals(listOf("a") to "a", ScratchPages.delete(listOf("a"), "a"))
    }

    @Test
    fun deleteOfAnUnknownIdChangesNothing() {
        assertEquals(ids to "a", ScratchPages.delete(ids, "zz"))
    }

    @Test
    fun clampCurrentFallsBackToTheFirstPage() {
        assertEquals("b", ScratchPages.clampCurrent(ids, "b"))
        assertEquals("a", ScratchPages.clampCurrent(ids, "gone"))
        assertEquals("a", ScratchPages.clampCurrent(ids, null))
        assertThrows(IllegalArgumentException::class.java) { ScratchPages.clampCurrent(emptyList(), "a") }
    }
}
