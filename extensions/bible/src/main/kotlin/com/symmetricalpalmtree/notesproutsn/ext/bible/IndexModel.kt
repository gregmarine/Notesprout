package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * What the index panel shows, with no views in it (arc 37 / B3, reshaped by B6) — pure and
 * JVM-tested, so the list a finger taps is proved by a test rather than by squinting at a device.
 *
 * **The notebook's Contents, with a grid for the second level.** Every book is a root entry; an
 * *expanded* book is followed by its chapters as rows of [CHAPTER_COLUMNS] numbers — a number
 * needs a square, not a line of its own, and a grid is what the old two-grid dialog taught the
 * hand already (B3). The whole thing is **one flat list of uniform-height rows** ([items]) so
 * that the panel paginates it the way the Contents paginates headings: rows per page measured
 * once from the real body height, a page turned by the pager or a swipe. Psalms' 25 rows of
 * chapters simply run on across pages like any long outline would.
 *
 * Nothing here knows which page is showing or which book is open — that is the panel's
 * in-memory state, handed in as [expanded]; this object only answers what the list looks like
 * and where a given book or chapter falls in it.
 */
object IndexModel {

    /** The chapters grid: six numbers to a row — the width the B3 grid settled on. */
    const val CHAPTER_COLUMNS = 6

    /** One row of the panel's list. */
    sealed class Item {
        /** A book's own row: its name, its chapter count and a toggle. */
        data class Book(val book: BookRow, val expanded: Boolean) : Item()

        /**
         * One row of an expanded book's chapter grid — exactly [CHAPTER_COLUMNS] slots, a `null`
         * past the book's last chapter holding its column open (2 John: one chapter and five
         * spacers, the same row shape as Psalms').
         */
        data class Chapters(val book: BookRow, val cells: List<Int?>) : Item()
    }

    /** Chapters `1..count` as rows of [CHAPTER_COLUMNS], the last row padded with nulls. */
    fun chapterRows(count: Int): List<List<Int?>> =
        (1..count).toList().chunked(CHAPTER_COLUMNS).map { row ->
            val cells: List<Int?> = row
            cells + List(CHAPTER_COLUMNS - cells.size) { null }
        }

    /**
     * The flat list: [books] in the order given (the canon's), each followed by its chapter rows
     * when its USFM code is in [expanded] (case-insensitive, as book codes are everywhere here).
     */
    fun items(books: List<BookRow>, expanded: Set<String>): List<Item> {
        val open = expanded.mapTo(HashSet()) { it.uppercase() }
        val out = ArrayList<Item>()
        for (book in books) {
            val isOpen = book.usfm.uppercase() in open
            out += Item.Book(book, isOpen)
            if (isOpen) for (row in chapterRows(book.chapterCount)) out += Item.Chapters(book, row)
        }
        return out
    }

    /** Where [usfm]'s own row sits in [items], or -1. */
    fun indexOfBook(items: List<Item>, usfm: String): Int =
        items.indexOfFirst { it is Item.Book && it.book.usfm.equals(usfm, ignoreCase = true) }

    /**
     * Where the chapter row holding [chapter] of [usfm] sits in [items], or -1 when that book is
     * not expanded (or the chapter is not in it). The row the panel opens on.
     */
    fun indexOfChapter(items: List<Item>, usfm: String, chapter: Int): Int =
        items.indexOfFirst {
            it is Item.Chapters && it.book.usfm.equals(usfm, ignoreCase = true) && chapter in it.cells
        }

    /** The list page item [index] falls on at [perPage] rows a page; a missing index is page 0. */
    fun pageOf(index: Int, perPage: Int): Int =
        if (index < 0 || perPage <= 0) 0 else index / perPage

    /** At least one page, even of an empty list — the pager label always has something to say. */
    fun pageCount(size: Int, perPage: Int): Int =
        if (perPage <= 0) 1 else maxOf(1, (size + perPage - 1) / perPage)

    /**
     * [page] brought inside `0 until pageCount`. What makes the arrows **no-ops** at either end
     * rather than disabled buttons — a greyed control is invisible on e-ink, so the panel simply
     * stays where it is.
     */
    fun clampPage(page: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)
}
