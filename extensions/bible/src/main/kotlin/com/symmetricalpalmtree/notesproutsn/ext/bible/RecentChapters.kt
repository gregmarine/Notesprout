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
 * A row of the Recents panel (arc 38 / R2): a chapter picked by name, or a **reference** the
 * notebook followed here. Two tables, one list — see [RecentChapters.select].
 *
 * Pure — no Android, no store. Never logged: either kind names where the user has read.
 */
sealed interface RecentEntry {

    /** When it was picked. The merge's only sort key, and the only thing both kinds share. */
    val at: Long

    /** A chapter picked from the Contents or from this panel — [RecentRef]'s row, wrapped. */
    data class Chapter(val ref: ChapterRef, override val at: Long) : RecentEntry

    /**
     * A passage. [wire] is its identity in the store and what re-opening it hands back to
     * `beginAt`'s screen; [passages] is that wire already decoded, so a row can name itself
     * without re-parsing on Main.
     */
    data class Reference(
        val wire: String,
        val passages: List<Passage>,
        override val at: Long,
    ) : RecentEntry
}

/**
 * What the reader's Recents panel shows, as arithmetic (arc 37 / B7, grown by arc 38 / R2 — pure
 * Kotlin, JVM-tested): the notebook's `RecentRows` in the extension's own copy, subject for
 * subject. Recents is a **history**, never a canon — with the notebook's one clause: where you
 * *are* is never offered as somewhere to go.
 *
 * A recent is a **pick** — a chapter chosen from the Contents or from this panel, or a passage
 * followed from the notebook — never a page turn, a chapter the swipe flowed into, or a Full
 * chapter opened out of a passage: the list answers "where did I deliberately go", and a reader
 * who swiped from Genesis 1 to Genesis 9 went to Genesis once.
 *
 * **Two tables, one list** (arc 38 / R2). Each is stored newest-first already, so the merge sorts
 * the union by `at` and nothing else: interleaving two histories by anything but when they
 * happened would put one subject permanently above the other, which is exactly the canon B7
 * refused. The sort is stable, so rows stamped in the same millisecond keep their stored order.
 */
object RecentChapters {

    /** How many picks the store keeps and the panel reads — a history, not an archive. */
    const val KEEP = 30

    /**
     * The rows to render, newest first: [chapters] and [references] merged by their stamps,
     * never the chapter ([currentChapter]) or the passage ([currentReference]) in front of the
     * user, each chapter and each wire at most once (a duplicate can only be a corrupted store,
     * so the first — newest — wins), and at most [KEEP] of the union.
     */
    fun select(
        chapters: List<RecentRef>,
        references: List<RecentEntry.Reference>,
        currentChapter: ChapterRef?,
        currentReference: String?,
    ): List<RecentEntry> {
        val merged = ArrayList<RecentEntry>(chapters.size + references.size)
        chapters.mapTo(merged) { RecentEntry.Chapter(it.ref, it.at) }
        merged.addAll(references)
        merged.sortByDescending { it.at }   // stable: a tie keeps each table's stored order
        val seenChapters = HashSet<ChapterRef>(chapters.size)
        val seenWires = HashSet<String>(references.size)
        val out = ArrayList<RecentEntry>(KEEP)
        for (entry in merged) {
            val keep = when (entry) {
                is RecentEntry.Chapter -> !entry.ref.matches(currentChapter) && seenChapters.add(entry.ref)
                is RecentEntry.Reference -> entry.wire != currentReference && seenWires.add(entry.wire)
            }
            if (!keep) continue
            out.add(entry)
            if (out.size == KEEP) break
        }
        return out
    }

    /** A row's name: a chapter in the running head's form ("Psalm 23"), a passage in the
     *  canonical form the notebook's link wears ("John 3:14–18; Proverbs 3:5–6"). */
    fun label(entry: RecentEntry): String = when (entry) {
        is RecentEntry.Chapter -> label(entry.ref)
        is RecentEntry.Reference -> ReferenceCodec.label(entry.passages)
    }

    /** A chapter row's name: the running head's own form — "Psalm 23", "Genesis 1". */
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
