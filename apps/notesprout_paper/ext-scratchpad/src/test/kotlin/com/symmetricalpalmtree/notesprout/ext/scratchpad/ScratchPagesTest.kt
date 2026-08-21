package com.symmetricalpalmtree.notesprout.ext.scratchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScratchPagesTest {

    @Test
    fun insertAfterAndBefore() {
        assertEquals(listOf("a", "n", "b"), ScratchPages.insertAfter(listOf("a", "b"), "a", "n"))
        assertEquals(listOf("a", "b", "n"), ScratchPages.insertAfter(listOf("a", "b"), "b", "n"))
        assertEquals(listOf("a", "b", "n"), ScratchPages.insertAfter(listOf("a", "b"), "zzz", "n"))
        assertEquals(listOf("n"), ScratchPages.insertAfter(emptyList(), null, "n"))
        assertEquals(listOf("n", "a", "b"), ScratchPages.insertBefore(listOf("a", "b"), "a", "n"))
        assertEquals(listOf("a", "n", "b"), ScratchPages.insertBefore(listOf("a", "b"), "b", "n"))
        assertEquals(listOf("n", "a"), ScratchPages.insertBefore(listOf("a"), "zzz", "n"))
    }

    @Test
    fun deleteLandsOnPreviousOrFirst_neverBelowOne() {
        assertEquals(listOf("a", "c") to "a", ScratchPages.delete(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("b", "c") to "b", ScratchPages.delete(listOf("a", "b", "c"), "a"))
        assertEquals(listOf("a", "b") to "b", ScratchPages.delete(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("a") to "a", ScratchPages.delete(listOf("a"), "a"))
        assertEquals(listOf("a", "b") to "a", ScratchPages.delete(listOf("a", "b"), "zzz"))
    }

    @Test
    fun clampCurrent() {
        assertEquals("b", ScratchPages.clampCurrent(listOf("a", "b"), "b"))
        assertEquals("a", ScratchPages.clampCurrent(listOf("a", "b"), "zzz"))
        assertEquals("a", ScratchPages.clampCurrent(listOf("a", "b"), null))
        assertThrows(IllegalArgumentException::class.java) { ScratchPages.clampCurrent(emptyList(), null) }
    }
}
