package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The heading-as-page-name rule (K2): topmost by `(y, x)`, prefix stripped, blank names nothing. */
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
}
