package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.library.GridMath

/**
 * The Contents tree (arc 4 / C1 — pure Kotlin, JVM-tested): heading items → a nested tree of up to
 * [MAX_LEVEL] levels, the rows visible under an expansion set, the highlighted entry for the current
 * page, and the paging math. Paper's arc-5 shape (itself the original `TocRepository` widened to six
 * levels): **an orphan attaches to the nearest shallower heading before it** (or becomes a root)
 * instead of vanishing — nothing the user wrote is hidden. SN builds items straight from core
 * `heading` rows ([ContentsSource]); there is no provider layer here.
 */
object OutlineTree {

    /** The depth cap — the `heading` row contract's level range (`SoilSchema.TYPE_HEADING`, 1–6). */
    const val MAX_LEVEL: Int = 6

    /** One heading's outline item — its page (id for navigation, index for order/display) +
     *  position (for document order) and its description. */
    data class Item(val objectId: String, val pageId: String, val pageIndex: Int, val x: Float, val y: Float, val label: String, val level: Int)

    /** Document order — (pageIndex, y, x); the one comparator [build] and the gather's cap share. */
    val DOCUMENT_ORDER: Comparator<Item> = compareBy<Item> { it.pageIndex }.thenBy { it.y }.thenBy { it.x }

    /** A tree node; [children] in document order; [parent] null for a root. [pageId] is what a row
     *  tap navigates by — the index is snapshot-relative and only ever displayed. */
    class Node(
        val id: String,
        val pageId: String,
        val pageIndex: Int,
        val label: String,
        val level: Int,
        val parent: Node?,
    ) {
        val children: MutableList<Node> = ArrayList()
    }

    /**
     * Sort [items] into **document order** — the caller passes them unsorted; sorting lives here so
     * it is tested — then one pass with a per-level "last open node" stack: a node of level *L*
     * attaches to the deepest open node whose level < *L* (levels between are skipped — the orphan
     * rule), else it is a root; opening a node clears every deeper slot. Parents persist across
     * page boundaries.
     */
    fun build(items: List<Item>): List<Node> {
        val sorted = items.sortedWith(DOCUMENT_ORDER)
        val roots = ArrayList<Node>()
        val open = arrayOfNulls<Node>(MAX_LEVEL + 1)   // index = level (1..MAX_LEVEL); 0 unused
        for (item in sorted) {
            val level = item.level.coerceIn(1, MAX_LEVEL)
            var parent: Node? = null
            for (l in level - 1 downTo 1) { val n = open[l]; if (n != null) { parent = n; break } }
            val node = Node(item.objectId, item.pageId, item.pageIndex, item.label, level, parent)
            if (parent == null) roots += node else parent.children += node
            open[level] = node
            for (l in level + 1..MAX_LEVEL) open[l] = null
        }
        return roots
    }

    /** Pre-order walk of [roots], descending only into nodes whose id is in [expanded]. */
    fun visible(roots: List<Node>, expanded: Set<String>): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) {
            out += n
            if (n.id in expanded) n.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return out
    }

    /** Every node in document order (pre-order, everything expanded). */
    fun all(roots: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) { out += n; n.children.forEach(::walk) }
        roots.forEach(::walk)
        return out
    }

    /**
     * The entry to highlight for [currentPageIndex]: the **last** node (document order) with
     * `pageIndex ≤ currentPageIndex` — none → null; if it is not visible under [expanded], its
     * nearest **visible** ancestor. [all] = [OutlineTree.all] of the roots.
     */
    fun highlight(all: List<Node>, currentPageIndex: Int, expanded: Set<String>): String? {
        val target = all.lastOrNull { it.pageIndex <= currentPageIndex } ?: return null
        var n: Node = target
        while (!isVisible(n, expanded)) n = n.parent ?: return n.id   // a root is always visible
        return n.id
    }

    private fun isVisible(n: Node, expanded: Set<String>): Boolean {
        var p = n.parent
        while (p != null) { if (p.id !in expanded) return false; p = p.parent }
        return true
    }

    /** The ids of [node]'s ancestors, root first (what the dialog pre-expands). */
    fun ancestorsOf(node: Node): List<String> {
        val out = ArrayList<String>()
        var p = node.parent
        while (p != null) { out.add(0, p.id); p = p.parent }
        return out
    }

    /** Which list page holds the row at [indexInVisible]. */
    fun pageOf(indexInVisible: Int, itemsPerPage: Int): Int =
        if (itemsPerPage <= 0 || indexInVisible < 0) 0 else indexInVisible / itemsPerPage

    /** How many list pages [n] rows need — at least 1. One copy of the contract: the library's
     *  [GridMath.pageCount] (its clamp twin, [GridMath.clampPage], is used at the dialog's sites). */
    fun pageCount(n: Int, itemsPerPage: Int): Int = GridMath.pageCount(n, itemsPerPage)
}
