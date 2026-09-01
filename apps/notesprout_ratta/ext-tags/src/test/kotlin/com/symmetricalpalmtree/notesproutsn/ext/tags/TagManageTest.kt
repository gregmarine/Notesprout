package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import org.junit.Assert.assertEquals
import org.junit.Test

/** Arc 21 / W2 — MANAGE's overview: what it lists, in what order, and what a bare row says. */
class TagManageTest {

    @Test
    fun `the notebook comes first, then the pages in the host's order`() {
        val rows = TagManage.targets(
            notebookId = "nb",
            notebookLabel = "Notebook",
            pageIds = listOf("p1", "p2", "p3"),
            pageLabels = listOf("Page 1", "Page 2", "Page 3"),
        )
        assertEquals(4, rows.size)
        assertEquals(TagShowing.TARGET_NOTEBOOK, rows[0].kind)
        assertEquals("nb", rows[0].id)
        assertEquals("Notebook", rows[0].label)
        assertEquals(listOf("p1", "p2", "p3"), rows.drop(1).map { it.id })
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), rows.drop(1).map { it.label })
        assertEquals(listOf(TagShowing.TARGET_PAGE), rows.drop(1).map { it.kind }.distinct())
    }

    /** A page's label is the host's word for it — the extension has no idea what a page is, so it
     *  may never re-derive or re-sort one. */
    @Test
    fun `labels are carried through verbatim, whatever they say`() {
        val rows = TagManage.targets("nb", "Notebook", listOf("p"), listOf("Seite 9"))
        assertEquals("Seite 9", rows[1].label)
    }

    @Test
    fun `a notebook with no pages is one row`() {
        val rows = TagManage.targets("nb", "Notebook", emptyList(), emptyList())
        assertEquals(1, rows.size)
        assertEquals("nb", rows[0].id)
    }

    /** TagShowing refuses to unmarshal mismatched arrays, so this cannot arrive — but an overview
     *  missing a row beats a screen that throws while drawing itself. */
    @Test
    fun `mismatched arrays list only what both halves have`() {
        val rows = TagManage.targets("nb", "Notebook", listOf("p1", "p2"), listOf("Page 1"))
        assertEquals(2, rows.size)
        assertEquals("p1", rows[1].id)
    }

    @Test
    fun `a row's tags read in the order they were handed in`() {
        assertEquals(
            "2026, reading list",
            TagManage.summary(listOf("2026", "reading list"), none = "No tags", separator = ", "),
        )
    }

    @Test
    fun `a target with no tags says so in words`() {
        assertEquals("No tags", TagManage.summary(emptyList(), none = "No tags", separator = ", "))
    }

    @Test
    fun `one tag stands alone with no separator`() {
        assertEquals("recipes", TagManage.summary(listOf("recipes"), none = "No tags", separator = ", "))
    }
}
