package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * What the index dialog shows, with no views in it (arc 37 / B3) — pure and JVM-tested, so the
 * grid a finger taps is proved by a test rather than by squinting at a device. The calendar's
 * `DayPickerModel` shape, one arc over: two grids behind one pair of arrows, and the arithmetic
 * that says which page a thing is on.
 *
 * **Two grids.** The books grid is 3 × 6 — a book's *name* has to fit, and "1 Thessalonians" at
 * three columns is already tight. The chapters grid is 6 × 6 — a number needs a square, not a
 * column. Both flow **in canon order**: the Old/New Testament boundary is *not* a page boundary
 * and no testament label is shown, because a page break there would leave Malachi's page
 * two-thirds empty for the sake of a word this reader never says.
 *
 * Every page's last row is padded with nulls to a full row, so the caller can add a spacer and
 * keep the columns where they are — a short final row that re-centres itself reads as a different
 * grid.
 */
object IndexModel {

    /** The books grid: three columns of names, six rows — eighteen books to a page. */
    const val BOOK_COLUMNS = 3
    const val BOOK_ROWS = 6
    const val BOOKS_PER_PAGE = BOOK_COLUMNS * BOOK_ROWS

    /** The chapters grid: six columns of numbers, six rows — thirty-six chapters to a page. */
    const val CHAPTER_COLUMNS = 6
    const val CHAPTER_ROWS = 6
    const val CHAPTERS_PER_PAGE = CHAPTER_COLUMNS * CHAPTER_ROWS

    /**
     * [books] as pages → rows → cells, in the order given (the canon's). A row is always
     * [BOOK_COLUMNS] slots wide; only the last row of the last page can carry nulls, and a page
     * is only as tall as it needs to be.
     */
    fun bookPages(books: List<BookRow>): List<List<List<BookRow?>>> =
        grid(books, BOOKS_PER_PAGE, BOOK_COLUMNS)

    /** The page [usfm] sits on, or 0 when the source does not carry that book. */
    fun bookPageOf(books: List<BookRow>, usfm: String): Int {
        val index = books.indexOfFirst { it.usfm.equals(usfm, ignoreCase = true) }
        return if (index < 0) 0 else index / BOOKS_PER_PAGE
    }

    /**
     * Chapters `1..count` as pages → rows → cells, [CHAPTER_COLUMNS] wide. A book of one chapter
     * is one row with five spacers in it, not one lonely cell stretched across the dialog.
     */
    fun chapterPages(count: Int): List<List<List<Int?>>> =
        grid((1..count).toList(), CHAPTERS_PER_PAGE, CHAPTER_COLUMNS)

    /** The page chapter [chapter] sits on. Below 1 is page 0 — there is no page before the first. */
    fun chapterPageOf(chapter: Int): Int =
        if (chapter < 1) 0 else (chapter - 1) / CHAPTERS_PER_PAGE

    /**
     * [page] brought inside `0 until pageCount`. What makes the arrows **no-ops** at either end
     * rather than disabled buttons — a greyed control is invisible on e-ink, so the index simply
     * stays where it is.
     */
    fun clampPage(page: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

    /** Pages of [perPage] items, each split into rows of [columns] with the last row padded. */
    private fun <T> grid(items: List<T>, perPage: Int, columns: Int): List<List<List<T?>>> =
        items.chunked(perPage).map { page ->
            page.chunked(columns).map { row ->
                val cells: List<T?> = row
                cells + List(columns - cells.size) { null }
            }
        }
}
