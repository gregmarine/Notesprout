package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.notebook.OutlineTree.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPickerLabelsTest {

    private fun item(id: String, page: Int, level: Int, y: Float = 0f, x: Float = 0f) = Item(id, page, x, y, id, level)

    @Test
    fun earlierHeadingOnSamePageWins() {
        val roots = OutlineTree.build(listOf(item("second", 0, 1, y = 50f), item("first", 0, 1, y = 10f)))
        val pageIds = listOf("pg-0")
        assertEquals(mapOf("pg-0" to "first"), LinkPickerLabels.firstLabelPerPage(roots, pageIds))
    }

    @Test
    fun pagesWithoutHeadingsAreAbsent() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1)))
        val pageIds = listOf("pg-0", "pg-1", "pg-2")
        val out = LinkPickerLabels.firstLabelPerPage(roots, pageIds)
        assertEquals(mapOf("pg-0" to "h1"), out)
        assertTrue("pg-1" !in out)
        assertTrue("pg-2" !in out)
    }

    @Test
    fun nestedChildOnLaterPageWinsItsOwnPage() {
        // h1 is on page 0; its h2 child is on a later page — the walk is pre-order over the whole
        // tree, so the child still surfaces as the label for its own (later) page.
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 2, 2)))
        val pageIds = listOf("pg-0", "pg-1", "pg-2")
        val out = LinkPickerLabels.firstLabelPerPage(roots, pageIds)
        assertEquals(mapOf("pg-0" to "h1", "pg-2" to "h2"), out)
    }

    @Test
    fun outOfRangePageIndexSkippedWithoutError() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 5, 1)))
        val pageIds = listOf("pg-0")   // no entry for pageIndex 5
        assertEquals(mapOf("pg-0" to "h1"), LinkPickerLabels.firstLabelPerPage(roots, pageIds))
    }

    @Test
    fun emptyRootsProduceEmptyMap() {
        assertEquals(emptyMap<String, String>(), LinkPickerLabels.firstLabelPerPage(emptyList(), listOf("pg-0", "pg-1")))
    }

    @Test
    fun multiplePagesLandOnTheRightPageIds() {
        val roots = OutlineTree.build(listOf(
            item("h1", 0, 1, y = 0f),
            item("h2", 1, 2, y = 0f),
            item("h3", 1, 3, y = 10f),
            item("h4", 3, 1, y = 0f),
        ))
        val pageIds = listOf("pg-0", "pg-1", "pg-2", "pg-3")
        val out = LinkPickerLabels.firstLabelPerPage(roots, pageIds)
        assertEquals(
            mapOf("pg-0" to "h1", "pg-1" to "h2", "pg-3" to "h4"),
            out,
        )
        assertTrue("pg-2" !in out)
    }
}
