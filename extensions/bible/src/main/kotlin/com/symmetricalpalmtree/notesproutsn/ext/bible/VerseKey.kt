package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * Packs/unpacks the canonical verse-key integer:
 * `ordinal * 1_000_000 + chapter * 1_000 + verse` (canon ordinal 1..66).
 *
 * Keys sort in canonical reading order, and — because a chapter's verses share
 * the same `ordinal*1e6 + chapter*1e3` base — any chapter or verse range is a
 * contiguous `BETWEEN` on this one column. The largest key (~66,150,999) fits
 * comfortably in a 32-bit [Int].
 *
 * Ported from Biblesprout (`data/VerseKey.kt`), verbatim: the same integers are
 * written by the `.bible` builder, so this file and `build_bible_db.py` must
 * agree forever.
 */
object VerseKey {
    const val BOOK_FACTOR = 1_000_000
    const val CHAPTER_FACTOR = 1_000

    /** Chapters pack into three digits: a chapter number must be below this. */
    const val CHAPTER_LIMIT = BOOK_FACTOR / CHAPTER_FACTOR

    /** "Rest of this chapter" sentinel; real verse numbers never approach it. */
    const val MAX_VERSE = 999

    fun encode(ordinal: Int, chapter: Int, verse: Int): Int =
        ordinal * BOOK_FACTOR + chapter * CHAPTER_FACTOR + verse

    fun ordinalOf(key: Int): Int = key / BOOK_FACTOR
    fun chapterOf(key: Int): Int = (key % BOOK_FACTOR) / CHAPTER_FACTOR
    fun verseOf(key: Int): Int = key % CHAPTER_FACTOR

    /** Inclusive key bounds covering an entire chapter, verse number aside. */
    fun chapterBounds(ordinal: Int, chapter: Int): Pair<Int, Int> =
        encode(ordinal, chapter, 0) to encode(ordinal, chapter, MAX_VERSE)
}

/**
 * A single, contiguous span of verses as inclusive key bounds. A one-verse
 * reference has `startKey == endKey`; a whole chapter spans the full
 * [VerseKey.MAX_VERSE] band.
 */
data class VerseRange(val startKey: Int, val endKey: Int) {
    init { require(startKey <= endKey) { "startKey ($startKey) > endKey ($endKey)" } }

    fun contains(key: Int): Boolean = key in startKey..endKey

    companion object {
        fun verse(ordinal: Int, chapter: Int, verse: Int): VerseRange {
            val k = VerseKey.encode(ordinal, chapter, verse)
            return VerseRange(k, k)
        }

        fun verses(ordinal: Int, chapter: Int, firstVerse: Int, lastVerse: Int): VerseRange =
            VerseRange(
                VerseKey.encode(ordinal, chapter, firstVerse),
                VerseKey.encode(ordinal, chapter, lastVerse),
            )

        /** A whole chapter, or a span of whole chapters `first..last`. */
        fun chapters(ordinal: Int, firstChapter: Int, lastChapter: Int): VerseRange =
            VerseRange(
                VerseKey.chapterBounds(ordinal, firstChapter).first,
                VerseKey.chapterBounds(ordinal, lastChapter).second,
            )
    }
}
