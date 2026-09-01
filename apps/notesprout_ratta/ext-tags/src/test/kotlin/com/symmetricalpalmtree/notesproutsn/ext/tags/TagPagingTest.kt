package com.symmetricalpalmtree.notesproutsn.ext.tags

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pager's arithmetic (arc 21 / W1) — the part that gets an off-by-one and the part a
 *  screenshot cannot check. */
class TagPagingTest {

    @Test
    fun rowsPerPageIsWholeRowsAgainstTheRealBand() {
        assertEquals(5, TagPaging.rowsPerPage(bandPx = 500, rowPx = 100))
        // A partial row is not a row: it would draw half-clipped at the bottom of the band.
        assertEquals(5, TagPaging.rowsPerPage(bandPx = 599, rowPx = 100))
        // A band too short for one row still shows the one you are looking at, never nothing.
        assertEquals(1, TagPaging.rowsPerPage(bandPx = 40, rowPx = 100))
        assertEquals(1, TagPaging.rowsPerPage(bandPx = 0, rowPx = 100))
        assertEquals(1, TagPaging.rowsPerPage(bandPx = 500, rowPx = 0))
    }

    @Test
    fun pageCountIsAtLeastOne() {
        assertEquals(1, TagPaging.pageCount(total = 0, perPage = 5))
        assertEquals(1, TagPaging.pageCount(total = 5, perPage = 5))
        assertEquals(2, TagPaging.pageCount(total = 6, perPage = 5))
        assertEquals(3, TagPaging.pageCount(total = 11, perPage = 5))
    }

    @Test
    fun clampKeepsThePageInsideTheList() {
        assertEquals(0, TagPaging.clampPage(-3, total = 12, perPage = 5))
        assertEquals(2, TagPaging.clampPage(9, total = 12, perPage = 5))
        // The list shrinking under a standing page is the real case: deleting the last tag on
        // page 3 must land on page 2, not on an empty one.
        assertEquals(1, TagPaging.clampPage(2, total = 7, perPage = 5))
    }

    @Test
    fun sliceIsThePageAndNeverThrows() {
        val items = (1..12).toList()
        assertEquals(listOf(1, 2, 3, 4, 5), TagPaging.slice(items, page = 0, perPage = 5))
        assertEquals(listOf(11, 12), TagPaging.slice(items, page = 2, perPage = 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, page = 9, perPage = 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, page = -1, perPage = 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, page = 0, perPage = 0))
        assertEquals(emptyList<Int>(), TagPaging.slice(emptyList<Int>(), page = 0, perPage = 5))
    }

}
