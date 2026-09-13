package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recents panel's arithmetic (arc 37 / B7) — the notebook `RecentRowsTest` in the
 * extension's subject: stored order kept against a canon-order trap and a chronological trap,
 * the chapter being read dropped, duplicates collapsed, nothing invented, the row label in the
 * running head's form, and the paging maths.
 */
class RecentChaptersTest {

    private fun r(usfm: String, chapter: Int, at: Long) = RecentRef(usfm, chapter, at)

    @Test
    fun `stored order wins over the canon and over the stamps`() {
        val stored = listOf(r("REV", 22, 5L), r("GEN", 1, 9L), r("PSA", 23, 1L))
        val rows = RecentChapters.select(stored, current = null)
        assertEquals(listOf("REV:22", "GEN:1", "PSA:23"), rows.map { "${it.usfm}:${it.chapter}" })
    }

    @Test
    fun `the chapter being read is never offered`() {
        val stored = listOf(r("PSA", 23, 3L), r("GEN", 1, 2L), r("psa", 23, 1L))
        val rows = RecentChapters.select(stored, ChapterRef("PSA", 23))
        assertEquals(listOf("GEN:1"), rows.map { "${it.usfm}:${it.chapter}" })
    }

    @Test
    fun `a different chapter of the same book stays`() {
        val stored = listOf(r("PSA", 23, 3L), r("PSA", 24, 2L))
        val rows = RecentChapters.select(stored, ChapterRef("PSA", 23))
        assertEquals(listOf(24), rows.map { it.chapter })
    }

    @Test
    fun `duplicates collapse to the first, newest`() {
        val stored = listOf(r("GEN", 1, 9L), r("EXO", 2, 8L), r("GEN", 1, 1L))
        val rows = RecentChapters.select(stored, null)
        assertEquals(listOf(9L, 8L), rows.map { it.at })
    }

    @Test
    fun `nothing is invented`() {
        assertTrue(RecentChapters.select(emptyList(), ChapterRef("GEN", 1)).isEmpty())
    }

    @Test
    fun `a row names its chapter as the running head does`() {
        assertEquals("Psalm 23", RecentChapters.label(ChapterRef("PSA", 23)))
        assertEquals("Genesis 1", RecentChapters.label(ChapterRef("GEN", 1)))
        assertEquals("Song of Solomon 2", RecentChapters.label(ChapterRef("SNG", 2)))
    }

    @Test
    fun `a stored row this build cannot read is dropped, not thrown`() {
        assertNull(RecentRef.of("XYZ", 1, 0L))
        assertNull(RecentRef.of("GEN", 0, 0L))
        assertEquals(RecentRef("GEN", 3, 7L), RecentRef.of("gen", 3, 7L))
    }

    @Test
    fun `the panel is half the window, the Contents' breakpoint`() {
        assertEquals(702, RecentChapters.sidebarWidthPx(1404))
        assertTrue(RecentChapters.SIDEBAR_WIDTH_FRACTION < ContentsLayout.SIDEBAR_WIDTH_FRACTION)
    }

    @Test
    fun `rows per page are whole rows, at least one, and safe on an unmeasured row`() {
        assertEquals(3, RecentChapters.itemsPerPage(bodyHeightPx = 350, rowHeightPx = 100))
        assertEquals(1, RecentChapters.itemsPerPage(bodyHeightPx = 50, rowHeightPx = 100))
        assertEquals(1, RecentChapters.itemsPerPage(bodyHeightPx = 500, rowHeightPx = 0))
    }

    @Test
    fun `the history is bounded`() {
        assertEquals(30, RecentChapters.KEEP)
    }
}
