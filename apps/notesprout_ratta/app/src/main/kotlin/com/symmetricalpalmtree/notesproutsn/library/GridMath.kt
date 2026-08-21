package com.symmetricalpalmtree.notesproutsn.library

/**
 * The library grid is **paginated, never scrolling** — a page holds exactly the cards that fit the
 * real container, and the rest live on the next page. All of that is arithmetic, so it lives here,
 * pure and JVM-tested; the views only measure the band and hand the numbers over.
 *
 * Card shape: at least [minCardWidthPx] wide (a tier dimen — `library_card_min_width`), height
 * = width × [CARD_ASPECT]. Columns are how many minimum-width cards fit across; rows are how many
 * of the resulting card height fit down. Both floor to at least 1, so a container smaller than one
 * card still shows one card rather than an empty screen.
 */
object GridMath {

    /** A card is 1 : 1.4 — a page standing up, which is what a notebook cover is. */
    const val CARD_ASPECT = 1.4f

    fun columns(containerWidthPx: Int, minCardWidthPx: Int): Int {
        if (containerWidthPx <= 0 || minCardWidthPx <= 0) return 1
        return (containerWidthPx / minCardWidthPx).coerceAtLeast(1)
    }

    fun rows(containerWidthPx: Int, containerHeightPx: Int, minCardWidthPx: Int, aspect: Float = CARD_ASPECT): Int {
        if (containerHeightPx <= 0) return 1
        val cardHeight = (cardWidthPx(containerWidthPx, minCardWidthPx) * aspect).toInt()
        if (cardHeight <= 0) return 1
        return (containerHeightPx / cardHeight).coerceAtLeast(1)
    }

    /** The width a card actually gets: the container split evenly between [columns]. */
    fun cardWidthPx(containerWidthPx: Int, minCardWidthPx: Int): Int =
        (containerWidthPx / columns(containerWidthPx, minCardWidthPx)).coerceAtLeast(1)

    fun cardsPerPage(
        containerWidthPx: Int,
        containerHeightPx: Int,
        minCardWidthPx: Int,
        aspect: Float = CARD_ASPECT,
    ): Int = columns(containerWidthPx, minCardWidthPx) *
        rows(containerWidthPx, containerHeightPx, minCardWidthPx, aspect)

    /** Never zero: an empty listing is one (empty) page, so the pager always reads "1 / 1". */
    fun pageCount(totalItems: Int, cardsPerPage: Int): Int {
        if (totalItems <= 0 || cardsPerPage <= 0) return 1
        return (totalItems - 1) / cardsPerPage + 1
    }

    /** Keep the current page inside the listing after a delete shortens it. */
    fun clampPage(pageIndex: Int, pageCount: Int): Int =
        pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /** Half-open index range of the [pageIndex] slice, empty when the page is past the end. */
    fun pageRange(pageIndex: Int, cardsPerPage: Int, totalItems: Int): IntRange {
        val start = pageIndex * cardsPerPage
        if (start >= totalItems || cardsPerPage <= 0) return IntRange.EMPTY
        return start until minOf(start + cardsPerPage, totalItems)
    }
}
