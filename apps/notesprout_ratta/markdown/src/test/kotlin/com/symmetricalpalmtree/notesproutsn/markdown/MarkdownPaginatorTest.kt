package com.symmetricalpalmtree.notesproutsn.markdown

import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownPaginator.Line
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownPaginator.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPaginatorTest {

    /** Uniform [count] lines of [height] px each, stacked from 0 — the plain-prose shape. */
    private fun stack(count: Int, height: Int): List<Line> =
        (0 until count).map { Line(it * height, (it + 1) * height) }

    @Test
    fun `no lines paginate to no pages`() {
        assertEquals(emptyList<Page>(), MarkdownPaginator.paginate(emptyList(), 100))
    }

    @Test
    fun `everything fitting yields one page starting at the first line's top`() {
        val pages = MarkdownPaginator.paginate(stack(5, 10), 100)
        assertEquals(listOf(Page(0, 4, 0)), pages)
    }

    @Test
    fun `a line ending exactly at the page edge still fits`() {
        // 10 lines of 10 px on a 100 px page: the tenth line's bottom lands exactly at 100.
        val pages = MarkdownPaginator.paginate(stack(10, 10), 100)
        assertEquals(listOf(Page(0, 9, 0)), pages)
    }

    @Test
    fun `one pixel over the edge starts the next page`() {
        // Same stack on a 99 px page: line 9 (bottom 100) no longer fits.
        val pages = MarkdownPaginator.paginate(stack(10, 10), 99)
        assertEquals(listOf(Page(0, 8, 0), Page(9, 9, 90)), pages)
    }

    @Test
    fun `pages start flush at their first line's own top`() {
        // A block gap in the layout: line 1 starts at 95, past what page one can hold in full.
        val lines = listOf(Line(0, 90), Line(95, 130))
        val pages = MarkdownPaginator.paginate(lines, 100)
        // Page two's top is 95 — the gap above line 1 stays behind on page one.
        assertEquals(listOf(Page(0, 0, 0), Page(1, 1, 95)), pages)
    }

    @Test
    fun `a line taller than the page gets a page to itself and the walk advances`() {
        val lines = listOf(Line(0, 10), Line(10, 300), Line(300, 310))
        val pages = MarkdownPaginator.paginate(lines, 100)
        assertEquals(listOf(Page(0, 0, 0), Page(1, 1, 10), Page(2, 2, 300)), pages)
    }

    @Test
    fun `a leading oversized line is still taken`() {
        val pages = MarkdownPaginator.paginate(listOf(Line(0, 500)), 100)
        assertEquals(listOf(Page(0, 0, 0)), pages)
    }

    @Test
    fun `every line lands on exactly one page in order`() {
        val lines = stack(37, 13)
        val pages = MarkdownPaginator.paginate(lines, 100)
        // Contiguous coverage: page n+1 picks up right after page n, first page at 0, last at the end.
        assertEquals(0, pages.first().firstLine)
        assertEquals(lines.lastIndex, pages.last().lastLine)
        for (i in 1 until pages.size) {
            assertEquals(pages[i - 1].lastLine + 1, pages[i].firstLine)
        }
        // And no page overfills except the single-oversized-line case, absent here.
        for (page in pages) {
            assertTrue(lines[page.lastLine].bottom - page.top <= 100)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive page height is a caller bug`() {
        MarkdownPaginator.paginate(stack(1, 10), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a heightless line is a caller bug`() {
        Line(10, 10)
    }
}
