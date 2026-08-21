package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.notebook.OutlineTree.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTreeTest {

    private fun item(id: String, page: Int, level: Int, y: Float = 0f, x: Float = 0f) = Item(id, page, x, y, id, level)

    private fun ids(nodes: List<OutlineTree.Node>) = nodes.map { it.id }

    @Test
    fun sortsByPageThenYThenX() {
        val roots = OutlineTree.build(listOf(
            item("c", 1, 1, y = 10f), item("a", 0, 1, y = 50f), item("b", 0, 1, y = 50f, x = -1f), item("d", 0, 1, y = 60f),
        ))
        assertEquals(listOf("b", "a", "d", "c"), ids(roots))
    }

    @Test
    fun nestsH1H2H3() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, 0f), item("h2", 0, 2, 10f), item("h3", 0, 3, 20f), item("h2b", 1, 2, 0f)))
        assertEquals(listOf("h1"), ids(roots))
        assertEquals(listOf("h2", "h2b"), ids(roots[0].children))
        assertEquals(listOf("h3"), ids(roots[0].children[0].children))
        assertEquals("h1", roots[0].children[0].parent?.id)
    }

    @Test
    fun orphanH3AttachesToH1() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, 0f), item("h3", 0, 3, 10f)))
        assertEquals(listOf("h3"), ids(roots[0].children))
        assertEquals(3, roots[0].children[0].level)
    }

    @Test
    fun orphanH2WithNoH1IsRoot() {
        val roots = OutlineTree.build(listOf(item("h2", 0, 2, 0f), item("h3", 0, 3, 10f), item("h1", 1, 1, 0f)))
        assertEquals(listOf("h2", "h1"), ids(roots))
        assertEquals(listOf("h3"), ids(roots[0].children))
    }

    @Test
    fun parentPersistsAcrossPages() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 3, 2)))
        assertEquals(listOf("h2"), ids(roots[0].children))
    }

    @Test
    fun newH1ClearsDeeperSlots() {
        // h1a > h2a ; h1b ; then an h3 — must attach to h1b, not h2a
        val roots = OutlineTree.build(listOf(item("h1a", 0, 1, 0f), item("h2a", 0, 2, 10f), item("h1b", 0, 1, 20f), item("h3", 0, 3, 30f)))
        assertEquals(listOf("h1a", "h1b"), ids(roots))
        assertEquals(listOf("h3"), ids(roots[1].children))
        assertEquals(listOf("h2a"), ids(roots[0].children))
    }

    @Test
    fun visibleCollapsedIsRootsAndExpandsInPlace() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, 0f), item("h2", 0, 2, 10f), item("h3", 0, 3, 20f), item("h1b", 1, 1)))
        assertEquals(listOf("h1", "h1b"), ids(OutlineTree.visible(roots, emptySet())))
        assertEquals(listOf("h1", "h2", "h1b"), ids(OutlineTree.visible(roots, setOf("h1"))))
        assertEquals(listOf("h1", "h2", "h3", "h1b"), ids(OutlineTree.visible(roots, setOf("h1", "h2"))))
        assertEquals(listOf("h1", "h1b"), ids(OutlineTree.visible(roots, setOf("h2"))))   // expanded but hidden parent
        assertEquals(listOf("h1", "h2", "h3", "h1b"), ids(OutlineTree.all(roots)))
    }

    @Test
    fun highlightLastAtOrBeforePage() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, 0f), item("h2", 1, 2, 0f), item("h2b", 1, 2, 10f), item("h1b", 3, 1)))
        val all = OutlineTree.all(roots)
        val open = setOf("h1")
        assertEquals("h2b", OutlineTree.highlight(all, 1, open))     // several on the page → the last
        assertEquals("h2b", OutlineTree.highlight(all, 2, open))     // page after → the last before it
        assertEquals("h1", OutlineTree.highlight(all, 0, open))
        assertEquals("h1b", OutlineTree.highlight(all, 9, open))
        assertEquals("h1", OutlineTree.highlight(all, 2, emptySet()))   // collapsed away → nearest visible ancestor
    }

    @Test
    fun highlightNullBeforeFirstEntry() {
        val roots = OutlineTree.build(listOf(item("h1", 2, 1)))
        assertNull(OutlineTree.highlight(OutlineTree.all(roots), 1, emptySet()))
        assertNull(OutlineTree.highlight(emptyList(), 0, emptySet()))
    }

    @Test
    fun ancestors() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, 0f), item("h2", 0, 2, 10f), item("h3", 0, 3, 20f)))
        val all = OutlineTree.all(roots)
        assertEquals(listOf("h1", "h2"), OutlineTree.ancestorsOf(OutlineTree.find(all, "h3")!!))
        assertTrue(OutlineTree.ancestorsOf(roots[0]).isEmpty())
    }

    @Test
    fun paging() {
        assertEquals(0, OutlineTree.pageOf(0, 8))
        assertEquals(0, OutlineTree.pageOf(7, 8))
        assertEquals(1, OutlineTree.pageOf(8, 8))
        assertEquals(0, OutlineTree.pageOf(-1, 8))
        assertEquals(1, OutlineTree.pageCount(0, 8))
        assertEquals(1, OutlineTree.pageCount(8, 8))
        assertEquals(2, OutlineTree.pageCount(9, 8))
        assertEquals(1, OutlineTree.pageCount(9, 0))
    }
}
