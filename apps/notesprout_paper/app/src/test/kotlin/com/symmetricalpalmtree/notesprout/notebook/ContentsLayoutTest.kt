package com.symmetricalpalmtree.notesprout.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Contents width rule + paging math (arc 5 / C1). */
class ContentsLayoutTest {

    @Test fun fullScreen_below480() {
        assertTrue(ContentsLayout.fullScreen(479))
        assertTrue(ContentsLayout.fullScreen(360))
        assertFalse(ContentsLayout.fullScreen(480))
        assertFalse(ContentsLayout.fullScreen(749))   // SNN / NA5C
        assertFalse(ContentsLayout.fullScreen(914))   // MIP11
    }

    @Test fun sidebar_isSixtyPercent() {
        assertEquals(842, ContentsLayout.sidebarWidthPx(1404))   // 842.4 → 842
        assertEquals(960, ContentsLayout.sidebarWidthPx(1600))
    }

    @Test fun itemsPerPage_floorsAndNeverBelowOne() {
        // 69 dp per row at density 2 = 138 px; 1000 px → 7 rows
        assertEquals(7, ContentsLayout.itemsPerPage(1000, 2f))
        assertEquals(1, ContentsLayout.itemsPerPage(0, 2f))
        assertEquals(1, ContentsLayout.itemsPerPage(100, 2f))
        assertEquals(1, ContentsLayout.itemsPerPage(500, 0f))
    }

    @Test fun indent_sixteenDpPerLevelAboveOne() {
        assertEquals(0, ContentsLayout.indentPx(1, 2f))
        assertEquals(32, ContentsLayout.indentPx(2, 2f))
        assertEquals(160, ContentsLayout.indentPx(6, 2f))
        assertEquals(0, ContentsLayout.indentPx(0, 2f))
    }
}
