package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * The rows [BibleDatabase] reads out of a `.bible` source. Pure data — no
 * Android, no SQLite — so the paginator that consumes them is JVM-testable.
 *
 * Ported from Biblesprout (`data/BibleDatabase.kt`), trimmed to the reader's
 * four shapes: the word layer, cross-references, search hits and verse slices
 * are not in this build (the slim database carries no word layer at all).
 */

/** One book of the source's `book` table, in canonical order. */
data class BookRow(
    val usfm: String,
    val ordinal: Int,
    val name: String,
    val testament: Testament,
    val chapterCount: Int,
)

/** A superscript verse-number span within a [RenderBlock]'s [RenderBlock.content]. */
data class VerseMark(val start: Int, val end: Int, val verseKey: Int, val number: Int)

/**
 * One display block from the rich layer: a paragraph, a poetry line, a section
 * heading or a stanza break, in document order. [content] is the display text
 * (verse-number digits inlined; [verses] locates them); [kind] is the USFM marker
 * (`p`, `pmo`, `q1`, `q2`, `b`, `s1`, `s2`, `d`, `r`, `li1`, …).
 */
data class RenderBlock(
    val id: Int,
    val kind: String,
    val startKey: Int?,
    val content: String,
    val verses: List<VerseMark>,
)

/**
 * A footnote: its caller sits at [offset] chars into block [blockId]'s content;
 * [text] is the body, [label] the origin reference (e.g. "1:6"). The reader
 * renders the caller as a plain superscript `*` — it is not tappable in this
 * build, so nothing yet reads [text] (decision 8).
 */
data class Footnote(
    val id: Int,
    val blockId: Int,
    val offset: Int,
    val verseKey: Int?,
    val label: String?,
    val text: String,
)
