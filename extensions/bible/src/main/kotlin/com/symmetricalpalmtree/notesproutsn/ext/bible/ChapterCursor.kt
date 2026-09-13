package com.symmetricalpalmtree.notesproutsn.ext.bible

/** One chapter's address: the book's USFM code and its 1-based chapter number. */
data class ChapterRef(val usfm: String, val chapter: Int)

/**
 * The canon as a single chain of chapters (arc 37 / B2) — what turns a swipe past the last page of
 * Genesis 50 into Exodus 1, and a swipe back off Exodus 1 into Genesis 50's **last** page
 * (decision 10). Pure: no Android, no database, no view — it is handed the source's own chapter
 * counts (`BibleDatabase.books()`, read once) and orders them by [Canon]'s ordinal, so the chain is
 * the canon's and never the map's iteration order.
 *
 * A book the map does not mention is **skipped**, not guessed at: a source that ships part of the
 * canon still reads end to end. [next] of the last chapter of the last present book and [prev] of
 * the first chapter of the first are `null` — the reader's silent no-op at Revelation 22 and
 * Genesis 1.
 */
class ChapterCursor(chapterCounts: Map<String, Int>) {

    /** Canonical codes only, positive counts only — anything else is not a book we can walk into. */
    private val counts: Map<String, Int> = buildMap {
        for ((usfm, count) in chapterCounts) {
            val book = Canon.tryUsfm(usfm) ?: continue
            if (count >= 1) put(book.usfm, count)
        }
    }

    /** The present books in canonical order — the chain [next]/[prev] step along. */
    private val present: List<CanonBook> = Canon.books.filter { counts.containsKey(it.usfm) }

    /** The chapter after [ref]: the next one in its book, else the first of the next present book. */
    fun next(ref: ChapterRef): ChapterRef? {
        val book = Canon.tryUsfm(ref.usfm) ?: return null
        val count = counts[book.usfm]
        if (count != null && ref.chapter in 1 until count) {
            return ChapterRef(book.usfm, ref.chapter + 1)
        }
        val following = present.firstOrNull { it.ordinal > book.ordinal } ?: return null
        return ChapterRef(following.usfm, 1)
    }

    /** The chapter before [ref]: the previous one in its book, else the **last** of the book before. */
    fun prev(ref: ChapterRef): ChapterRef? {
        val book = Canon.tryUsfm(ref.usfm) ?: return null
        if (ref.chapter > 1) return ChapterRef(book.usfm, ref.chapter - 1)
        val preceding = present.lastOrNull { it.ordinal < book.ordinal } ?: return null
        return ChapterRef(preceding.usfm, counts.getValue(preceding.usfm))
    }
}
