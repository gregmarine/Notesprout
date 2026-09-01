package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 21 / W2 — MANAGE's overview: what it lists, in what order, and what a bare row says. */
class TagManageTest {

    /** Real UUIDs, because that is the only shape a target takes since W4. */
    private val NB = "11111111-1111-4111-8111-111111111111"
    private val P1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val P2 = "bbbbbbbb-2222-4222-8222-222222222222"
    private val P3 = "cccccccc-3333-4333-8333-333333333333"

    @Test
    fun `the notebook comes first, then the pages in the host's order`() {
        val rows = TagManage.targets(
            notebookId = NB,
            notebookLabel = "Notebook",
            pageIds = listOf(P1, P2, P3),
            pageLabels = listOf("Page 1", "Page 2", "Page 3"),
        )
        assertEquals(4, rows.size)
        assertEquals(TagShowing.TARGET_NOTEBOOK, rows[0].kind)
        assertEquals(NB, rows[0].notebookId)
        assertNull(rows[0].pageId)
        assertEquals("Notebook", rows[0].label)
        assertEquals(listOf(P1, P2, P3), rows.drop(1).map { it.pageId })
        assertTrue("every row names the notebook", rows.all { it.notebookId == NB })
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), rows.drop(1).map { it.label })
        assertEquals(listOf(TagShowing.TARGET_PAGE), rows.drop(1).map { it.kind }.distinct())
    }

    /** A page's label is the host's word for it — the extension has no idea what a page is, so it
     *  may never re-derive or re-sort one. */
    @Test
    fun `labels are carried through verbatim, whatever they say`() {
        val rows = TagManage.targets(NB, "Notebook", listOf(P1), listOf("Seite 9"))
        assertEquals("Seite 9", rows[1].label)
    }

    @Test
    fun `a notebook with no pages is one row`() {
        val rows = TagManage.targets(NB, "Notebook", emptyList(), emptyList())
        assertEquals(1, rows.size)
        assertEquals(NB, rows[0].notebookId)
        assertNull(rows[0].pageId)
    }

    /** TagShowing refuses to unmarshal mismatched arrays, so this cannot arrive — but an overview
     *  missing a row beats a screen that throws while drawing itself. */
    @Test
    fun `mismatched arrays list only what both halves have`() {
        val rows = TagManage.targets(NB, "Notebook", listOf(P1, P2), listOf("Page 1"))
        assertEquals(2, rows.size)
        assertEquals(P1, rows[1].pageId)
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
