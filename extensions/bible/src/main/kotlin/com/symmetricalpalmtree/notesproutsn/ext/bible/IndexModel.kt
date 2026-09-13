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
 * **Every page is padded with nulls to the full grid** — rows × columns — not just its last row
 * to a full row. The dialog is a bordered window centred on the glass, and a last page with fewer
 * rows would shrink it and re-centre it: on e-ink that is a second frame of chrome jumping under a
 * finger that has already aimed (the B4 walk caught it on 2 John's one-row chapter grid). A spacer
 * row costs nothing; a dialog that moves costs a mis-tap.
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
     * [books] as pages → rows → cells, in the order given (the canon's). Every page is
     * [BOOK_ROWS] rows of [BOOK_COLUMNS] slots; the last page carries nulls where the canon ran
     * out.
     */
    fun bookPages(books: List<BookRow>): List<List<List<BookRow?>>> =
        grid(books, BOOK_ROWS, BOOK_COLUMNS)

    /** The page [usfm] sits on, or 0 when the source does not carry that book. */
    fun bookPageOf(books: List<BookRow>, usfm: String): Int {
        val index = books.indexOfFirst { it.usfm.equals(usfm, ignoreCase = true) }
        return if (index < 0) 0 else index / BOOKS_PER_PAGE
    }

    /**
     * Chapters `1..count` as pages → rows → cells, every page [CHAPTER_ROWS] × [CHAPTER_COLUMNS].
     * A book of one chapter is one cell and thirty-five spacers — the same dialog as Psalms'.
     */
    fun chapterPages(count: Int): List<List<List<Int?>>> =
        grid((1..count).toList(), CHAPTER_ROWS, CHAPTER_COLUMNS)

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

    /** Pages of `rows × columns` items; every page padded with nulls to exactly [rows] full rows. */
    private fun <T> grid(items: List<T>, rows: Int, columns: Int): List<List<List<T?>>> =
        items.chunked(rows * columns).map { page ->
            val filled = page.chunked(columns).map { row ->
                val cells: List<T?> = row
                cells + List(columns - cells.size) { null }
            }
            filled + List(rows - filled.size) { List<T?>(columns) { null } }
        }
}
