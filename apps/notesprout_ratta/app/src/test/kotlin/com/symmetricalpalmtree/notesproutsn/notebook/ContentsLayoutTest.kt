package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contents width branch, rows-per-page and indent math (arc 4 / C1). Density 1.875 is both real
 * devices (Nomad 749 dp / 1404 px · Manta 1024 dp / 1920 px).
 */
class ContentsLayoutTest {

    @Test
    fun `below 480 dp the Contents fills the screen`() {
        assertTrue(ContentsLayout.fullScreen(479))
        assertTrue(ContentsLayout.fullScreen(320))
    }

    @Test
    fun `at 480 dp and above it is the sidebar`() {
        assertFalse(ContentsLayout.fullScreen(480))
        assertFalse(ContentsLayout.fullScreen(749))    // Nomad
        assertFalse(ContentsLayout.fullScreen(1024))   // Manta
    }

    @Test
    fun `the sidebar is sixty percent of the window, rounded`() {
        assertEquals(842, ContentsLayout.sidebarWidthPx(1404))   // 842.4 → 842 (Nomad)
        assertEquals(1152, ContentsLayout.sidebarWidthPx(1920))  // Manta
        assertEquals(0, ContentsLayout.sidebarWidthPx(0))
    }

    @Test
    fun `itemsPerPage floors a part row away`() {
        // 69 dp per row at 1.875 = 129.375 px; a body of 763 px holds 5.89 rows → 5.
        assertEquals(5, ContentsLayout.itemsPerPage(763, 1.875f))
        assertEquals(6, ContentsLayout.itemsPerPage(777, 1.875f))
        assertEquals(7, ContentsLayout.itemsPerPage(1000, 2f))
    }

    @Test
    fun `itemsPerPage is never below one`() {
        assertEquals(1, ContentsLayout.itemsPerPage(0, 1.875f))
        assertEquals(1, ContentsLayout.itemsPerPage(100, 1.875f))
        assertEquals(1, ContentsLayout.itemsPerPage(-50, 1.875f))
    }

    @Test
    fun `a zero density cannot divide by zero`() {
        assertEquals(1, ContentsLayout.itemsPerPage(1000, 0f))
    }

    @Test
    fun `indent is sixteen dp per level above the first`() {
        assertEquals(0, ContentsLayout.indentPx(1, 1.875f))
        assertEquals(30, ContentsLayout.indentPx(2, 1.875f))
        assertEquals(90, ContentsLayout.indentPx(4, 1.875f))   // 3 × 16 × 1.875
        assertEquals(150, ContentsLayout.indentPx(6, 1.875f))
    }

    @Test
    fun `a level below one indents by nothing`() {
        assertEquals(0, ContentsLayout.indentPx(0, 1.875f))
        assertEquals(0, ContentsLayout.indentPx(-3, 1.875f))
    }
}
