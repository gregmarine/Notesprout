package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

/**
 * A tappable span in a drawn page body (arc 41): chars `[start, end)` of the body's text. Two
 * kinds — a cross-reference in a `\r` parallel-passage line, and a footnote caller `*`.
 */
sealed interface PageMark {
    val start: Int
    val end: Int

    /** A cross-reference: a tap opens the inclusive verse-key range as a passage. */
    data class Reference(
        override val start: Int,
        override val end: Int,
        val targetStartKey: Int,
        val targetEndKey: Int,
    ) : PageMark

    /** A footnote caller: a tap opens footnote [footnoteId] in a popup. */
    data class Caller(override val start: Int, override val end: Int, val footnoteId: Int) : PageMark
}

/**
 * The hit rules — pure, JVM-tested. A reference span hits on `offset in start until end`, the
 * ordinary "which char is under the finger"; a caller is one superscript glyph, a target no wider
 * than a hyphen, so it also hits on the offset right after it (`start..end`) — the boundary the
 * layout answers for a finger landing on its right half.
 */
object PageMarks {
    fun at(marks: List<PageMark>, offset: Int): PageMark? {
        for (mark in marks) {
            when (mark) {
                is PageMark.Reference -> if (offset >= mark.start && offset < mark.end) return mark
                is PageMark.Caller -> if (offset >= mark.start && offset <= mark.end) return mark
            }
        }
        return null
    }
}
