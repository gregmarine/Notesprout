package com.symmetricalpalmtree.notesproutsn.ext.bible

import kotlin.math.roundToInt

/**
 * One recent chapter: where, and when it was picked (arc 37 / B7). Pure — no Android, no store.
 * Never logged: it names where the user has read.
 */
data class RecentRef(val usfm: String, val chapter: Int, val at: Long) {

    val ref: ChapterRef get() = ChapterRef(usfm, chapter)

    companion object {
        /** A row back into a reference, or `null` for a book code this build does not know or a
         *  chapter below 1 — the store row is dropped, never a dialog. */
        fun of(usfm: String, chapter: Int, at: Long): RecentRef? {
            val book = Canon.tryUsfm(usfm) ?: return null
            if (chapter < 1) return null
            return RecentRef(book.usfm, chapter, at)
        }
    }
}

/**
 * What the reader's Recents panel shows, as arithmetic (arc 37 / B7 — pure Kotlin, JVM-tested):
 * the notebook's `RecentRows` in the extension's own copy, subject for subject. **Stored order
 * wins** — recents is a history, and a sort would turn "what I was just reading" into the canon —
 * with the notebook's one clause: the chapter you are *in* is never offered as somewhere to go.
 *
 * A recent is a **pick** — a chapter chosen from the Contents or from this panel — never a page
 * turn or a chapter the swipe flowed into: the list answers "where did I deliberately go", and a
 * reader who swiped from Genesis 1 to Genesis 9 went to Genesis once.
 */
object RecentChapters {

    /** How many picks the store keeps and the panel reads — a history, not an archive. */
    const val KEEP = 30

    /**
     * The rows to render, newest first: [recents] in their stored order, never [current], each
     * chapter at most once (a duplicate can only be a corrupted store, so the first — newest —
     * wins).
     */
    fun select(recents: List<RecentRef>, current: ChapterRef?): List<RecentRef> {
        val seen = HashSet<ChapterRef>(recents.size)
        return recents.filter { r ->
            val ref = r.ref
            !ref.matches(current) && seen.add(ref)
        }
    }

    /** A row's name: the running head's own form — "Psalm 23", "Genesis 1". */
    fun label(ref: ChapterRef): String = "${Canon.chapterTitleName(ref.usfm)} ${ref.chapter}"

    /**
     * The panel's share of the window width — the notebook Recents' 50 %, **narrower than the
     * Contents' 60 %**: a row is a name and a time. The 480 dp full-screen breakpoint is
     * [ContentsLayout]'s (one rule for "a sidebar doesn't fit here"); only the width differs.
     */
    const val SIDEBAR_WIDTH_FRACTION = 0.50f

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /**
     * How many rows fit a body of [bodyHeightPx] when one row measures [rowHeightPx] — at least 1,
     * and 1 for a nonsense row height. The row is **measured**, not taken from a dp constant: two
     * lines at two text sizes is not a number worth hard-coding.
     */
    fun itemsPerPage(bodyHeightPx: Int, rowHeightPx: Int): Int {
        if (rowHeightPx <= 0) return 1
        return maxOf(1, bodyHeightPx / rowHeightPx)
    }

    private fun ChapterRef.matches(other: ChapterRef?): Boolean =
        other != null && chapter == other.chapter && usfm.equals(other.usfm, ignoreCase = true)
}
