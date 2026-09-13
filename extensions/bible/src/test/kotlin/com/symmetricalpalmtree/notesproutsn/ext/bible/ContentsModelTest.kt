package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index panel's arithmetic (arc 37 / B3, reshaped by B6). The list a finger taps is proved
 * here rather than on a device: every book a root row, an expanded book followed by rows of six
 * chapter numbers, the last row padded so a column never collapses, and the paging that finds
 * the row the current chapter sits in.
 */
class ContentsModelTest {

    /** The real 66 books with the canon's shape; chapter counts are set per test where they matter. */
    private fun books(chapters: (CanonBook) -> Int = { 1 }): List<BookRow> = Canon.books.map {
        BookRow(it.usfm, it.ordinal, it.name, it.testament, chapterCount = chapters(it))
    }

    private fun realish(): List<BookRow> = books {
        when (it.usfm) {
            "GEN" -> 50
            "PSA" -> 150
            "2JN" -> 1
            else -> 3
        }
    }

    @Test
    fun `chapter rows are six wide and the last is padded`() {
        val rows = ContentsModel.chapterRows(50)
        assertEquals(9, rows.size)
        for (row in rows) assertEquals(ContentsModel.CHAPTER_COLUMNS, row.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.first())
        assertEquals(listOf(49, 50, null, null, null, null), rows.last())
    }

    @Test
    fun `a one-chapter book is one cell and five spacers`() {
        assertEquals(listOf(listOf(1, null, null, null, null, null)), ContentsModel.chapterRows(1))
    }

    @Test
    fun `no chapters is no rows`() {
        assertTrue(ContentsModel.chapterRows(0).isEmpty())
    }

    @Test
    fun `collapsed, the list is one row per book in canon order`() {
        val items = ContentsModel.items(realish(), emptySet())
        assertEquals(66, items.size)
        assertTrue(items.all { it is ContentsModel.Item.Book && !it.expanded })
        assertEquals("GEN", (items.first() as ContentsModel.Item.Book).book.usfm)
        assertEquals("REV", (items.last() as ContentsModel.Item.Book).book.usfm)
    }

    @Test
    fun `an expanded book is followed by its chapter rows, then the next book`() {
        val items = ContentsModel.items(realish(), setOf("GEN"))
        assertEquals(66 + 9, items.size)
        val first = items[0] as ContentsModel.Item.Book
        assertTrue(first.expanded)
        for (i in 1..9) {
            val row = items[i] as ContentsModel.Item.Chapters
            assertEquals("GEN", row.book.usfm)
        }
        assertEquals("EXO", (items[10] as ContentsModel.Item.Book).book.usfm)
    }

    @Test
    fun `expansion keys are case-insensitive`() {
        assertEquals(66 + 9, ContentsModel.items(realish(), setOf("gen")).size)
    }

    @Test
    fun `several books can be open at once`() {
        val items = ContentsModel.items(realish(), setOf("GEN", "PSA"))
        assertEquals(66 + 9 + 25, items.size)
    }

    @Test
    fun `indexOfBook finds the row and misses politely`() {
        val items = ContentsModel.items(realish(), setOf("GEN"))
        assertEquals(0, ContentsModel.indexOfBook(items, "GEN"))
        assertEquals(10, ContentsModel.indexOfBook(items, "exo"))
        assertEquals(-1, ContentsModel.indexOfBook(items, "XYZ"))
    }

    @Test
    fun `indexOfChapter is the row holding it, only while the book is open`() {
        val open = ContentsModel.items(realish(), setOf("GEN"))
        assertEquals(1, ContentsModel.indexOfChapter(open, "GEN", 1))
        assertEquals(1, ContentsModel.indexOfChapter(open, "GEN", 6))
        assertEquals(2, ContentsModel.indexOfChapter(open, "gen", 7))
        assertEquals(9, ContentsModel.indexOfChapter(open, "GEN", 50))
        assertEquals(-1, ContentsModel.indexOfChapter(open, "GEN", 51))
        val closed = ContentsModel.items(realish(), emptySet())
        assertEquals(-1, ContentsModel.indexOfChapter(closed, "GEN", 1))
    }

    @Test
    fun `Psalm 119 lands on the twentieth chapter row`() {
        val items = ContentsModel.items(realish(), setOf("PSA"))
        val psalms = ContentsModel.indexOfBook(items, "PSA")
        assertEquals(psalms + 20, ContentsModel.indexOfChapter(items, "PSA", 119))
    }

    @Test
    fun `paging math`() {
        assertEquals(0, ContentsModel.pageOf(0, 12))
        assertEquals(0, ContentsModel.pageOf(11, 12))
        assertEquals(1, ContentsModel.pageOf(12, 12))
        assertEquals(0, ContentsModel.pageOf(-1, 12))
        assertEquals(0, ContentsModel.pageOf(5, 0))
        assertEquals(1, ContentsModel.pageCount(0, 12))
        assertEquals(1, ContentsModel.pageCount(12, 12))
        assertEquals(2, ContentsModel.pageCount(13, 12))
        assertEquals(6, ContentsModel.pageCount(66, 12))
        assertEquals(1, ContentsModel.pageCount(66, 0))
    }

    @Test
    fun `clampPage is a no-op at either end and never negative`() {
        assertEquals(0, ContentsModel.clampPage(-1, 4))
        assertEquals(3, ContentsModel.clampPage(9, 4))
        assertEquals(2, ContentsModel.clampPage(2, 4))
        assertEquals(0, ContentsModel.clampPage(5, 0))
    }

    @Test
    fun `an empty source is an empty list`() {
        assertTrue(ContentsModel.items(emptyList(), setOf("GEN")).isEmpty())
        assertEquals(-1, ContentsModel.indexOfBook(emptyList(), "GEN"))
        assertNull(ContentsModel.items(emptyList(), emptySet()).firstOrNull())
        assertFalse(ContentsModel.items(books(), emptySet()).any { it is ContentsModel.Item.Chapters })
    }
}
