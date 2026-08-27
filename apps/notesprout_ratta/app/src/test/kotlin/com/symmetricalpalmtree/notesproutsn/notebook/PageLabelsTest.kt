package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The heading-as-page-name rule (K2): topmost by `(y, x)`, prefix stripped, blank names nothing —
 *  across the page's loose headings **and** the ones its links wrap. */
class PageLabelsTest {

    private fun heading(id: String, text: String, x: Float, y: Float) = Heading(
        id = id, text = text, level = 2, x = x, y = y, width = 100f, height = 40f, order = 0,
    )

    @Test
    fun `no headings names nothing`() {
        assertNull(PageLabels.titleOf(emptyList()))
    }

    @Test
    fun `topmost by y wins`() {
        val title = PageLabels.titleOf(
            listOf(
                heading("a", "## Lower", 0f, 500f),
                heading("b", "# Upper", 300f, 100f),
            )
        )
        assertEquals("Upper", title)
    }

    @Test
    fun `same y falls to x`() {
        val title = PageLabels.titleOf(
            listOf(
                heading("a", "## Right", 400f, 100f),
                heading("b", "## Left", 50f, 100f),
            )
        )
        assertEquals("Left", title)
    }

    @Test
    fun `prefix is stripped and whitespace trimmed`() {
        assertEquals("Meeting notes", PageLabels.titleOf(listOf(heading("a", "###  Meeting notes ", 0f, 0f))))
    }

    @Test
    fun `a title that strips to nothing names nothing`() {
        assertNull(PageLabels.titleOf(listOf(heading("a", "## ", 0f, 0f))))
    }

    // --- Wrapped headings count too ---

    @Test
    fun `a heading wrapped in a link names the page`() {
        val link = link(heading("w", "# Wrapped", 0f, 100f))
        val title = PageLabels.titleOf(PageContent(emptyList(), emptyList(), listOf(link)))
        assertEquals("Wrapped", title)
    }

    @Test
    fun `topmost wins whether it is loose or wrapped`() {
        val loose = heading("l", "## Loose", 0f, 400f)
        val link = link(heading("w", "## Wrapped", 0f, 100f))
        assertEquals(
            "Wrapped",
            PageLabels.titleOf(PageContent(emptyList(), listOf(loose), listOf(link))),
        )
        val lower = link(heading("w2", "## Lower wrapped", 0f, 900f))
        assertEquals(
            "Loose",
            PageLabels.titleOf(PageContent(emptyList(), listOf(loose), listOf(lower))),
        )
    }

    @Test
    fun `a page with only links that wrap no heading names nothing`() {
        assertNull(PageLabels.titleOf(PageContent(emptyList(), emptyList(), listOf(link()))))
    }

    private fun link(vararg wrapped: Heading) = PageLink(
        id = "lnk${wrapped.size}", payload = "L1|1|1|nb|", chrome = LinkPayload.CHROME_UNDERLINE,
        x = 0f, y = 0f, width = 200f, height = 80f, order = 0,
        strokes = emptyList(), headings = wrapped.toList(),
    )
}
