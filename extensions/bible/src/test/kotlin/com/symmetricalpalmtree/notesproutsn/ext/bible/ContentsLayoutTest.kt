package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index panel's layout rules (arc 37 / B6) — the Contents' numbers, in the extension's copy. */
class ContentsLayoutTest {

    @Test
    fun `the sidebar needs 480 dp`() {
        assertTrue(ContentsLayout.fullScreen(479))
        assertFalse(ContentsLayout.fullScreen(480))
        assertFalse(ContentsLayout.fullScreen(749))   // the Nomad
        assertFalse(ContentsLayout.fullScreen(1024))  // the Manta
    }

    @Test
    fun `the sidebar is sixty percent, rounded`() {
        assertEquals(842, ContentsLayout.sidebarWidthPx(1404))   // the Nomad's glass
        assertEquals(600, ContentsLayout.sidebarWidthPx(1000))
    }

    @Test
    fun `a row's slot is height plus separator`() {
        assertEquals(69, ContentsLayout.rowPx(1f))
        assertEquals(129, ContentsLayout.rowPx(1.875f))
    }

    @Test
    fun `rows per page floors and never drops below one`() {
        assertEquals(10, ContentsLayout.itemsPerPage(690, 1f))
        assertEquals(9, ContentsLayout.itemsPerPage(689, 1f))
        assertEquals(1, ContentsLayout.itemsPerPage(10, 1f))
        assertEquals(1, ContentsLayout.itemsPerPage(0, 1f))
        assertEquals(1, ContentsLayout.itemsPerPage(500, 0f))
    }
}
