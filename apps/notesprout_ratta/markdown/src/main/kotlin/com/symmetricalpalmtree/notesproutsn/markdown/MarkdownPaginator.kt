package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * Splits a laid-out document into page-height slices **on line boundaries** — the piece the PDF
 * preview export stands on: each slice becomes one rendered page, and no line of text is ever cut
 * through by a page edge.
 *
 * Pure Kotlin by design. A layout engine cannot run on the JVM, so the caller measures first and
 * hands over one [Line] per laid-out line (from `StaticLayout.getLineTop(i)` /
 * `getLineTop(i + 1)`); everything decided here — where each page starts, which lines it holds —
 * is plain math over those boxes, covered by plain JVM tests. The caller draws a page by
 * translating its canvas up by [Page.top] and clipping to the page height.
 *
 * The rules:
 *
 * - **A line belongs to exactly one page.** A line whose bottom would cross the page edge starts
 *   the next page instead of straddling both.
 * - **Every page starts flush at its first line's top.** Whatever gap the layout put above that
 *   line (a block gap, a blank line) stays on the page before, so page two never opens with a
 *   dent of dead space that only existed to separate it from page one.
 * - **A line taller than a whole page gets a page to itself** and is clipped at the bottom when
 *   drawn. Refusing it would make pagination fail on a document that renders fine, and splitting
 *   it would cut text — clipping one freak line is the honest degrade, and it guarantees the
 *   walk always advances.
 * - **No lines, no pages.** An empty layout paginates to nothing; whether that means "nothing to
 *   export" is the caller's call, made where it can say so to the user.
 */
object MarkdownPaginator {

    /**
     * One laid-out line: its top and bottom edges in layout coordinates, px. [bottom] must be
     * greater than [top], and consecutive lines must not overlap ([top] at or below the previous
     * line's bottom) — which is exactly what a text layout produces.
     */
    data class Line(val top: Int, val bottom: Int) {
        init {
            require(bottom > top) { "line has no height: top=$top bottom=$bottom" }
        }
    }

    /**
     * One page: the inclusive [firstLine]..[lastLine] index range it holds, and [top] — the layout
     * y its content starts at (its first line's own top, so drawing is `translate(0, -top)`).
     */
    data class Page(val firstLine: Int, val lastLine: Int, val top: Int)

    /**
     * [lines] sliced into pages of [pageHeightPx]. Indices in the result refer into [lines].
     *
     * [pageHeightPx] is the **content** height a page offers — the caller subtracts its margins
     * before asking, so margins are its own business and stay out of the math here.
     */
    fun paginate(lines: List<Line>, pageHeightPx: Int): List<Page> {
        require(pageHeightPx > 0) { "page height must be positive: $pageHeightPx" }
        if (lines.isEmpty()) return emptyList()

        val pages = mutableListOf<Page>()
        var first = 0
        while (first < lines.size) {
            val top = lines[first].top
            // Take every following line that still ends inside this page. The first line is taken
            // unconditionally — that is the taller-than-a-page rule and the progress guarantee.
            var last = first
            while (last + 1 < lines.size && lines[last + 1].bottom - top <= pageHeightPx) last++
            pages += Page(first, last, top)
            first = last + 1
        }
        return pages
    }
}
