package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index's arithmetic (arc 37 / B3). The grid a finger taps is proved here rather than on a
 * device: how many pages the canon makes, which page a book or a chapter lands on, and that every
 * page is a FULL grid of slots — a shorter last page would shrink the dialog and re-centre it under
 * a finger that has already aimed (the B4 walk's 2 John mis-tap).
 */
class IndexModelTest {

    /** The real 66 books, with the canon's own chapter counts nowhere in sight — only the shape
     *  of the list matters here, so [Canon]'s rows stand in for the source's `book` table. */
    private val books: List<BookRow> = Canon.books.map {
        BookRow(it.usfm, it.ordinal, it.name, it.testament, chapterCount = 1)
    }

    @Test
    fun `the canon makes four book pages`() {
        val pages = IndexModel.bookPages(books)
        assertEquals(4, pages.size)
        assertEquals(listOf(18, 18, 18, 12), pages.map { page -> page.sumOf { row -> row.count { it != null } } })
    }

    @Test
    fun `every book row is three slots wide`() {
        for (page in IndexModel.bookPages(books)) {
            for (row in page) assertEquals(IndexModel.BOOK_COLUMNS, row.size)
        }
    }

    @Test
    fun `a short last page is padded to the full grid`() {
        // 64 books = 3 full pages + a page of 10: three full rows, a row of one + two spacers,
        // and two whole spacer rows so the page is still BOOK_ROWS tall.
        val page = IndexModel.bookPages(books.take(64)).last()
        assertEquals(IndexModel.BOOK_ROWS, page.size)
        assertEquals(listOf("3 John", null, null), page[3].map { it?.name })
        assertEquals(List(IndexModel.BOOK_COLUMNS) { null }, page[4])
        assertEquals(List(IndexModel.BOOK_COLUMNS) { null }, page[5])
        // The canon's own last page: 12 books in four rows, then two spacer rows.
        assertEquals(IndexModel.BOOK_ROWS, IndexModel.bookPages(books).last().size)
    }

    @Test
    fun `book pages run in canon order across the testaments`() {
        val pages = IndexModel.bookPages(books)
        // Malachi (39) and Matthew (40) are neighbours: the OT/NT split is not a page boundary.
        assertEquals("Malachi", pages[2][0][2]?.name)
        assertEquals("Matthew", pages[2][1][0]?.name)
    }

    @Test
    fun `bookPageOf finds the page a book sits on`() {
        assertEquals(0, IndexModel.bookPageOf(books, "GEN"))
        assertEquals(1, IndexModel.bookPageOf(books, "PSA"))   // ordinal 19, the first of page 1
        assertEquals(3, IndexModel.bookPageOf(books, "REV"))   // ordinal 66, the last of page 3
    }

    @Test
    fun `an unknown book opens the first page`() {
        assertEquals(0, IndexModel.bookPageOf(books, "XYZ"))
        assertEquals(0, IndexModel.bookPageOf(emptyList(), "GEN"))
    }

    @Test
    fun `Psalms makes five chapter pages`() {
        val pages = IndexModel.chapterPages(150)
        assertEquals(5, pages.size)
        assertEquals(6, pages.last().sumOf { row -> row.count { it != null } })
        assertEquals(IndexModel.CHAPTER_ROWS, pages.last().size)   // padded to the full grid
        assertEquals(1, pages.first().first().first())
        assertEquals(150, pages.last().flatten().filterNotNull().last())
    }

    @Test
    fun `a one-chapter book is one cell and thirty-five spacers`() {
        val pages = IndexModel.chapterPages(1)
        assertEquals(1, pages.size)
        assertEquals(IndexModel.CHAPTER_ROWS, pages.first().size)   // the same dialog as Psalms'
        val row = pages.first().first()
        assertEquals(IndexModel.CHAPTER_COLUMNS, row.size)
        assertEquals(1, row.first())
        assertEquals(5, row.count { it == null })
        assertNull(row.last())
        assertEquals(35, pages.first().sumOf { r -> r.count { it == null } })
    }

    @Test
    fun `every chapter row is six slots wide`() {
        for (page in IndexModel.chapterPages(150)) {
            for (row in page) assertEquals(IndexModel.CHAPTER_COLUMNS, row.size)
        }
    }

    @Test
    fun `chapterPageOf finds the page a chapter sits on`() {
        assertEquals(0, IndexModel.chapterPageOf(1))
        assertEquals(0, IndexModel.chapterPageOf(36))    // the last of the first page
        assertEquals(1, IndexModel.chapterPageOf(37))    // the first of the second
        assertEquals(4, IndexModel.chapterPageOf(150))
        assertEquals(0, IndexModel.chapterPageOf(0))     // nonsense opens the first page
    }

    @Test
    fun `clampPage keeps a page inside the grid`() {
        assertEquals(0, IndexModel.clampPage(-1, 4))
        assertEquals(0, IndexModel.clampPage(0, 4))
        assertEquals(3, IndexModel.clampPage(3, 4))
        assertEquals(3, IndexModel.clampPage(4, 4))      // the no-op at the end
        assertEquals(0, IndexModel.clampPage(2, 0))      // nothing to page through
    }

    @Test
    fun `an empty book table makes no pages`() {
        assertTrue(IndexModel.bookPages(emptyList()).isEmpty())
        assertTrue(IndexModel.chapterPages(0).isEmpty())
    }
}
