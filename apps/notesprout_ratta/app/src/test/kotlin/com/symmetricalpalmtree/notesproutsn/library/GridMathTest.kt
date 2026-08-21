package com.symmetricalpalmtree.notesproutsn.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridMathTest {

    /** Nomad portrait, sw720dp tier: 1404 px wide, 375 px minimum card (200dp @ 1.875). */
    private val nomadWidth = 1404
    private val nomadGrid = 1609 // screen height minus the two 70dp bars
    private val nomadMinCard = 375

    @Test
    fun columnsFitWholeCardsOnly() {
        assertEquals(3, GridMath.columns(nomadWidth, nomadMinCard))
        assertEquals(2, GridMath.columns(800, 375))
        assertEquals(1, GridMath.columns(400, 375))
    }

    @Test
    fun columnsNeverZero() {
        assertEquals(1, GridMath.columns(100, 375))
        assertEquals(1, GridMath.columns(0, 375))
        assertEquals(1, GridMath.columns(1404, 0))
    }

    @Test
    fun cardWidthSplitsTheContainerEvenly() {
        assertEquals(468, GridMath.cardWidthPx(nomadWidth, nomadMinCard))
    }

    @Test
    fun rowsUseTheAspectDerivedCardHeight() {
        // card 468 wide → 655 tall; 1609 / 655 = 2
        assertEquals(2, GridMath.rows(nomadWidth, nomadGrid, nomadMinCard))
    }

    @Test
    fun rowsNeverZeroInAShortBand() {
        assertEquals(1, GridMath.rows(nomadWidth, 100, nomadMinCard))
    }

    @Test
    fun cardsPerPageIsColumnsTimesRows() {
        assertEquals(6, GridMath.cardsPerPage(nomadWidth, nomadGrid, nomadMinCard))
        assertEquals(
            GridMath.columns(nomadWidth, nomadMinCard) * GridMath.rows(nomadWidth, nomadGrid, nomadMinCard),
            GridMath.cardsPerPage(nomadWidth, nomadGrid, nomadMinCard),
        )
    }

    @Test
    fun narrowerMinimumGivesMoreColumns() {
        // The 140dp base tier on the same panel: proof the dimen is what drives density.
        assertTrue(GridMath.columns(nomadWidth, 262) > GridMath.columns(nomadWidth, nomadMinCard))
    }

    @Test
    fun pageCountRoundsUp() {
        assertEquals(1, GridMath.pageCount(1, 6))
        assertEquals(1, GridMath.pageCount(6, 6))
        assertEquals(2, GridMath.pageCount(7, 6))
        assertEquals(3, GridMath.pageCount(13, 6))
    }

    @Test
    fun emptyListingIsStillOnePage() {
        assertEquals(1, GridMath.pageCount(0, 6))
        assertEquals(1, GridMath.pageCount(5, 0))
    }

    @Test
    fun clampPageAfterADeleteShortensTheListing() {
        // Was on page 3 of 3; two deletes leave two pages.
        assertEquals(1, GridMath.clampPage(2, 2))
        assertEquals(0, GridMath.clampPage(2, 1))
        assertEquals(0, GridMath.clampPage(-1, 3))
        assertEquals(2, GridMath.clampPage(2, 3))
    }

    @Test
    fun clampPageSurvivesAZeroPageCount() {
        assertEquals(0, GridMath.clampPage(4, 0))
    }

    @Test
    fun pageRangeIsTheVisibleSlice() {
        assertEquals(0 until 6, GridMath.pageRange(0, 6, 13))
        assertEquals(6 until 12, GridMath.pageRange(1, 6, 13))
        assertEquals(12 until 13, GridMath.pageRange(2, 6, 13))
    }

    @Test
    fun pageRangePastTheEndIsEmpty() {
        assertTrue(GridMath.pageRange(3, 6, 13).isEmpty())
        assertTrue(GridMath.pageRange(0, 6, 0).isEmpty())
    }
}
