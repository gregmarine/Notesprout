package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The one paging loop both sides of the tag seam run (arc 22 / X3). */
class TagPagesTest {

    /** [rows] served in pages of [pageSize], recording the offsets that were asked for. */
    private class Table(val rows: Int, val pageSize: Int) {
        val offsets = ArrayList<Int>()
        fun page(offset: Int): List<Int> {
            offsets += offset
            if (offset >= rows) return emptyList()
            return (offset until minOf(offset + pageSize, rows)).toList()
        }
    }

    @Test
    fun aShortPageEndsTheLoop() {
        val table = Table(rows = 7, pageSize = 5)
        val all = TagPages.collect(5, 10) { table.page(it) }
        assertEquals((0 until 7).toList(), all)
        assertEquals(listOf(0, 5), table.offsets)
    }

    @Test
    fun aSinglePartialPageIsOneCall() {
        val table = Table(rows = 3, pageSize = 5)
        assertEquals((0 until 3).toList(), TagPages.collect(5, 10) { table.page(it) })
        assertEquals(listOf(0), table.offsets)
    }

    /** An empty listing still costs one call — there is no separate count to ask instead. */
    @Test
    fun anEmptyTableIsOneEmptyPage() {
        val table = Table(rows = 0, pageSize = 5)
        assertEquals(emptyList<Int>(), TagPages.collect(5, 10) { table.page(it) })
        assertEquals(listOf(0), table.offsets)
    }

    /** An exact multiple pays one extra empty call: a full page cannot say it was the last. */
    @Test
    fun anExactMultipleEndsAfterOneExtraEmptyPage() {
        val table = Table(rows = 10, pageSize = 5)
        assertEquals((0 until 10).toList(), TagPages.collect(5, 10) { table.page(it) })
        assertEquals(listOf(0, 5, 10), table.offsets)
    }

    /** A peer answering a full page forever must be stopped and reported, never answered with a
     *  truncated list that reads as the whole table. */
    @Test
    fun theRunawayGuardTrips() {
        try {
            TagPages.collect(2, 3) { offset -> listOf(offset, offset + 1) }
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun theGuardsAreSizedFromTheCaps() {
        // The two the host and the extension actually use.
        assertEquals(11, ExtensionContract.MAX_TAGS / ExtensionContract.TAGS_PAGE + 1)
        assertEquals(51, ExtensionContract.MAX_TAG_ASSIGNMENTS / ExtensionContract.ASSIGNMENTS_PAGE + 1)
    }

    @Test
    fun aNonPositivePageSizeIsARefusal() {
        try {
            TagPages.collect(0, 3) { emptyList<Int>() }
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            TagPages.collect(3, 0) { emptyList<Int>() }
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
