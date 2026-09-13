package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index panel's layout rules (arc 37 / B6) — the Contents' numbers, in the extension's copy. */
class IndexLayoutTest {

    @Test
    fun `the sidebar needs 480 dp`() {
        assertTrue(IndexLayout.fullScreen(479))
        assertFalse(IndexLayout.fullScreen(480))
        assertFalse(IndexLayout.fullScreen(749))   // the Nomad
        assertFalse(IndexLayout.fullScreen(1024))  // the Manta
    }

    @Test
    fun `the sidebar is sixty percent, rounded`() {
        assertEquals(842, IndexLayout.sidebarWidthPx(1404))   // the Nomad's glass
        assertEquals(600, IndexLayout.sidebarWidthPx(1000))
    }

    @Test
    fun `a row's slot is height plus separator`() {
        assertEquals(69, IndexLayout.rowPx(1f))
        assertEquals(129, IndexLayout.rowPx(1.875f))
    }

    @Test
    fun `rows per page floors and never drops below one`() {
        assertEquals(10, IndexLayout.itemsPerPage(690, 1f))
        assertEquals(9, IndexLayout.itemsPerPage(689, 1f))
        assertEquals(1, IndexLayout.itemsPerPage(10, 1f))
        assertEquals(1, IndexLayout.itemsPerPage(0, 1f))
        assertEquals(1, IndexLayout.itemsPerPage(500, 0f))
    }
}
