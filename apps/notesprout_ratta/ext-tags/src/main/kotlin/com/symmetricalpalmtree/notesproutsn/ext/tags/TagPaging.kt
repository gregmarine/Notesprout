package com.symmetricalpalmtree.notesproutsn.ext.tags

/**
 * The tag list's prev/next pager, as arithmetic (arc 21 / W1) — pure and JVM-tested, because it is
 * the part that gets an off-by-one and the part a screenshot cannot check.
 *
 * The idiom is the Today dashboard's, and the reasons carry over: **rows are measured against the
 * real band**, never a guessed count, so the list never half-draws a row and never leaves a gap; the
 * arrows **never disable** (a disabled control is invisible on e-ink), they simply have nothing to
 * do at the ends; and when there is only one page the pager is `INVISIBLE`, not `GONE`, so the band
 * keeps the same height and the rows above it do not shift when the count crosses the boundary.
 */
object TagPaging {

    /** How many whole rows of [rowPx] fit in [bandPx]. At least one — a band too short for a row
     *  still shows the one you are looking at rather than nothing at all. */
    fun rowsPerPage(bandPx: Int, rowPx: Int): Int {
        if (rowPx <= 0) return 1
        return (bandPx / rowPx).coerceAtLeast(1)
    }

    /** Pages needed for [total] items. Always at least one — an empty list is one empty page. */
    fun pageCount(total: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return ((total + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /** [page] brought inside `0 until pageCount(total, perPage)`. */
    fun clampPage(page: Int, total: Int, perPage: Int): Int =
        page.coerceIn(0, pageCount(total, perPage) - 1)

    /** The slice of [items] on [page]; empty when the page is past the end. */
    fun <T> slice(items: List<T>, page: Int, perPage: Int): List<T> {
        if (perPage <= 0) return emptyList()
        val from = page * perPage
        if (from >= items.size || from < 0) return emptyList()
        return items.subList(from, minOf(from + perPage, items.size))
    }

    // No `pageOf` here. The screen never follows a moved row onto its new page: an edit re-renders
    // the page you are standing on, and both lists are clamped by [clampPage] instead. A helper for
    // a navigation the screen does not do would read as a promise that it does.
}
