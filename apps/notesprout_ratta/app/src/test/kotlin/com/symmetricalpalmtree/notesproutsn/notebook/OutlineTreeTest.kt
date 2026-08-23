package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.notebook.OutlineTree.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contents tree (arc 4 / C1): document order, the orphan rule, visibility under an expansion
 * set, the highlight, and the paging math.
 */
class OutlineTreeTest {

    private fun item(id: String, page: Int, level: Int, y: Float = 0f, x: Float = 0f) =
        Item(objectId = id, pageId = "pg$page", pageIndex = page, x = x, y = y, label = id, level = level)

    private fun ids(nodes: List<OutlineTree.Node>) = nodes.map { it.id }

    private fun find(all: List<OutlineTree.Node>, id: String) = all.first { it.id == id }

    // ---- build: order ------------------------------------------------------------------------

    @Test
    fun `build sorts unsorted input into page then y then x`() {
        val roots = OutlineTree.build(
            listOf(
                item("d", page = 1, level = 1, y = 5f),
                item("b", page = 0, level = 1, y = 100f, x = 10f),
                item("c", page = 0, level = 1, y = 200f),
                item("a", page = 0, level = 1, y = 100f, x = 2f),
            ),
        )
        assertEquals(listOf("a", "b", "c", "d"), ids(roots))
    }

    @Test
    fun `build nests h1 h2 h3 and records the parent link`() {
        val roots = OutlineTree.build(
            listOf(
                item("h1", 0, 1, y = 0f), item("h2", 0, 2, y = 10f),
                item("h3", 0, 3, y = 20f), item("h2b", 0, 2, y = 30f),
            ),
        )
        assertEquals(listOf("h1"), ids(roots))
        assertEquals(listOf("h2", "h2b"), ids(roots[0].children))
        assertEquals(listOf("h3"), ids(roots[0].children[0].children))
        assertSame(roots[0], roots[0].children[0].parent)
        assertSame(roots[0].children[0], roots[0].children[0].children[0].parent)
    }

    // ---- build: the orphan rule --------------------------------------------------------------

    @Test
    fun `an orphan h3 after an h1 attaches to the h1`() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1, y = 0f), item("h3", 0, 3, y = 10f)))
        assertEquals(listOf("h1"), ids(roots))
        assertEquals(listOf("h3"), ids(roots[0].children))
        assertEquals(3, roots[0].children[0].level)   // the skipped level is kept, not rewritten
    }

    @Test
    fun `a leading orphan becomes a root instead of being dropped`() {
        val roots = OutlineTree.build(
            listOf(item("h2", 0, 2, y = 0f), item("h3", 0, 3, y = 10f), item("h1", 1, 1)),
        )
        assertEquals(listOf("h2", "h1"), ids(roots))
        assertEquals(listOf("h3"), ids(roots[0].children))
        assertNull(roots[0].parent)
    }

    @Test
    fun `a new shallower node clears the deeper open slots`() {
        // h1a > h2a, then a second h1 — the following h3 belongs to the NEW h1, not h2a's subtree.
        val roots = OutlineTree.build(
            listOf(
                item("h1a", 0, 1, y = 0f), item("h2a", 0, 2, y = 10f),
                item("h1b", 0, 1, y = 20f), item("h3", 0, 3, y = 30f),
            ),
        )
        assertEquals(listOf("h1a", "h1b"), ids(roots))
        assertEquals(listOf("h2a"), ids(roots[0].children))
        assertEquals(listOf("h3"), ids(roots[1].children))
    }

    @Test
    fun `an h2 after a later h1 attaches to that h1`() {
        val roots = OutlineTree.build(
            listOf(item("h1a", 0, 1), item("h1b", 1, 1), item("h2", 2, 2)),
        )
        assertTrue(roots[0].children.isEmpty())
        assertEquals(listOf("h2"), ids(roots[1].children))
    }

    @Test
    fun `parents persist across page boundaries`() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 3, 2)))
        assertEquals(listOf("h1"), ids(roots))
        assertEquals(listOf("h2"), ids(roots[0].children))
        assertEquals(3, roots[0].children[0].pageIndex)
    }

    @Test
    fun `build clamps the level into one through six`() {
        val roots = OutlineTree.build(listOf(item("zero", 0, 0, y = 0f), item("nine", 0, 9, y = 10f)))
        assertEquals(listOf("zero"), ids(roots))
        assertEquals(1, roots[0].level)                         // 0 → 1, so it is a root
        assertEquals(listOf("nine"), ids(roots[0].children))
        assertEquals(OutlineTree.MAX_LEVEL, roots[0].children[0].level)   // 9 → 6
    }

    // ---- visible / all -----------------------------------------------------------------------

    @Test
    fun `visible is a pre-order walk that stops at a collapsed node`() {
        val roots = OutlineTree.build(
            listOf(
                item("h1", 0, 1, y = 0f), item("h2", 0, 2, y = 10f),
                item("h3", 0, 3, y = 20f), item("h1b", 1, 1),
            ),
        )
        assertEquals(listOf("h1", "h1b"), ids(OutlineTree.visible(roots, emptySet())))
        assertEquals(listOf("h1", "h2", "h1b"), ids(OutlineTree.visible(roots, setOf("h1"))))
        assertEquals(listOf("h1", "h2", "h3", "h1b"), ids(OutlineTree.visible(roots, setOf("h1", "h2"))))
        // Expanded but its parent is not — the subtree stays out.
        assertEquals(listOf("h1", "h1b"), ids(OutlineTree.visible(roots, setOf("h2"))))
    }

    @Test
    fun `all is the full pre-order walk`() {
        val roots = OutlineTree.build(
            listOf(
                item("h1", 0, 1, y = 0f), item("h2", 0, 2, y = 10f),
                item("h3", 0, 3, y = 20f), item("h1b", 1, 1),
            ),
        )
        assertEquals(listOf("h1", "h2", "h3", "h1b"), ids(OutlineTree.all(roots)))
        assertTrue(OutlineTree.all(emptyList()).isEmpty())
    }

    // ---- highlight ---------------------------------------------------------------------------

    @Test
    fun `highlight takes the last entry on the current page`() {
        val roots = OutlineTree.build(
            listOf(
                item("h1", 0, 1), item("a", 1, 2, y = 10f),
                item("b", 1, 2, y = 10f, x = 50f), item("h1b", 3, 1),
            ),
        )
        val all = OutlineTree.all(roots)
        val open = setOf("h1")
        assertEquals("b", OutlineTree.highlight(all, 1, open))   // several on the page → the last by (y, x)
        assertEquals("b", OutlineTree.highlight(all, 2, open))   // a page with none → the last before it
        assertEquals("h1", OutlineTree.highlight(all, 0, open))
        assertEquals("h1b", OutlineTree.highlight(all, 9, open))
    }

    @Test
    fun `highlight is null when nothing sits at or before the page`() {
        val roots = OutlineTree.build(listOf(item("h1", 2, 1)))
        assertNull(OutlineTree.highlight(OutlineTree.all(roots), 1, emptySet()))
        assertNull(OutlineTree.highlight(emptyList(), 0, emptySet()))
    }

    @Test
    fun `a target collapsed away falls back to its nearest visible ancestor`() {
        val roots = OutlineTree.build(
            listOf(item("h1", 0, 1, y = 0f), item("h2", 0, 2, y = 10f), item("h3", 0, 3, y = 20f)),
        )
        val all = OutlineTree.all(roots)
        assertEquals("h3", OutlineTree.highlight(all, 0, setOf("h1", "h2")))
        assertEquals("h2", OutlineTree.highlight(all, 0, setOf("h1")))   // h3 hidden → its parent
        assertEquals("h1", OutlineTree.highlight(all, 0, emptySet()))    // everything closed → the root
    }

    @Test
    fun `a root target is always itself`() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 1, 2)))
        val all = OutlineTree.all(roots)
        // The target is the root itself — a root is visible under any expansion set.
        assertEquals("h1", OutlineTree.highlight(all, 0, emptySet()))
        assertEquals("h1", OutlineTree.highlight(all, 0, setOf("h1")))
    }

    // ---- ancestors ---------------------------------------------------------------------------

    @Test
    fun `ancestorsOf is root first and empty for a root`() {
        val roots = OutlineTree.build(
            listOf(item("h1", 0, 1, y = 0f), item("h2", 0, 2, y = 10f), item("h3", 0, 3, y = 20f)),
        )
        val all = OutlineTree.all(roots)
        assertEquals(listOf("h1", "h2"), OutlineTree.ancestorsOf(find(all, "h3")))
        assertEquals(listOf("h1"), OutlineTree.ancestorsOf(find(all, "h2")))
        assertTrue(OutlineTree.ancestorsOf(roots[0]).isEmpty())
    }

    @Test
    fun `a node carries its page id for navigation`() {
        val roots = OutlineTree.build(listOf(item("h1", 0, 1), item("h2", 3, 2)))
        assertEquals("pg0", roots[0].pageId)
        assertEquals("pg3", roots[0].children[0].pageId)
    }

    // ---- paging ------------------------------------------------------------------------------

    @Test
    fun `pageOf maps a row index onto its list page`() {
        assertEquals(0, OutlineTree.pageOf(0, 8))
        assertEquals(0, OutlineTree.pageOf(7, 8))
        assertEquals(1, OutlineTree.pageOf(8, 8))
        assertEquals(2, OutlineTree.pageOf(20, 8))
        assertEquals(0, OutlineTree.pageOf(-1, 8))    // no highlight → the first page
        assertEquals(0, OutlineTree.pageOf(5, 0))     // unmeasured body → the first page
    }

    @Test
    fun `pageCount is at least one and rounds up`() {
        assertEquals(1, OutlineTree.pageCount(0, 8))
        assertEquals(1, OutlineTree.pageCount(8, 8))     // an exact multiple is one page
        assertEquals(2, OutlineTree.pageCount(9, 8))
        assertEquals(3, OutlineTree.pageCount(17, 8))
        assertEquals(1, OutlineTree.pageCount(9, 0))
        assertEquals(1, OutlineTree.pageCount(9, -4))
    }
}
