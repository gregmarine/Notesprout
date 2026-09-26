package com.symmetricalpalmtree.sketchcompanion.grid

/**
 * Where a guide grid's lines fall on a page (copied verbatim from NSE · Sketch, arc 51) — pure
 * arithmetic, no `android.graphics`, so every position is pinned on the JVM.
 *
 * **Square cells, one count across the page's width.** A cell is `pageWidth / count` on both axes,
 * so a portrait page carries more rows than columns and the last row at either end is a partial
 * cell. **Symmetric about the page's centre**: the partial cells at opposite edges are equal, so a
 * grid laid over a centred photo is centred on it too — the urban sketcher's layout grid. An even
 * count puts a line through the centre; an odd count puts the centre in the middle of a cell. The
 * rows take the columns' parity, so the page's centre is a crossing (even) or a cell's centre (odd)
 * on both axes at once.
 *
 * **Every position is computed from the centre outward, never accumulated.** `x += cell` over a
 * dozen cells drifts by the float error of every step — a recorded Notesprout trap — while
 * `centre ± (offset + k·cell)` is one multiply and one add from exact, whatever `k` is. Worked in
 * `Double` and handed back as `Float`.
 *
 * **Only positions strictly inside the page** are answered: a line on the page's own edge is the
 * edge, not a guide, and would be half off the bitmap anyway. Dots sit at every `(x, y)` pair of
 * the two lists — the same cell corners the lines cross at.
 */
object GridLayout {

    /** One grid, ready to draw: its [kind], the column [xs] and row [ys] inside the page in
     *  increasing order, and the [cell] side both were spaced by. */
    data class Plan(val kind: Int, val xs: List<Float>, val ys: List<Float>, val cell: Float) {
        /** How many dots a [Grid.DOTS] grid draws — every crossing of the two lists. */
        val dotCount: Int get() = xs.size * ys.size
    }

    /** A position within this of either edge counts as the edge — well under a pixel, well over
     *  the double error of a page-sized sum. */
    private const val EDGE_EPSILON = 1e-6

    /**
     * The grid of [kind] with [count] cells across [pageWidth], or **null** when there is nothing to
     * draw: the grid off, a count below one, or a degenerate page.
     */
    fun plan(kind: Int, count: Int, pageWidth: Int, pageHeight: Int): Plan? {
        if (kind == Grid.OFF || count < 1 || pageWidth <= 0 || pageHeight <= 0) return null
        val cell = pageWidth.toDouble() / count
        // Even: a line on the centre (offset 0). Odd: the centre mid-cell (offset half a cell).
        val offset = if (count % 2 == 0) 0.0 else cell / 2.0
        return Plan(
            kind = kind,
            xs = positions(pageWidth, cell, offset),
            ys = positions(pageHeight, cell, offset),
            cell = cell.toFloat(),
        )
    }

    /** The positions along one axis of [extent] px: `centre ± (offset + k·cell)`, each computed
     *  afresh from `k`, kept only strictly inside the page, in increasing order. */
    private fun positions(extent: Int, cell: Double, offset: Double): List<Float> {
        val centre = extent / 2.0
        val below = ArrayList<Float>()
        val above = ArrayList<Float>()
        var k = 0
        while (true) {
            val d = offset + k * cell
            val lo = centre - d
            val hi = centre + d
            val loIn = lo > EDGE_EPSILON
            val hiIn = hi < extent - EDGE_EPSILON
            if (!loIn && !hiIn) break
            if (d == 0.0) {
                above += centre.toFloat()   // the centre line itself, counted once
            } else {
                if (loIn) below += lo.toFloat()
                if (hiIn) above += hi.toFloat()
            }
            k++
        }
        below.reverse()
        return below + above
    }
}
