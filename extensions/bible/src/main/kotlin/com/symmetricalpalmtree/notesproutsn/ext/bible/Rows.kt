package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * The rows [BibleDatabase] reads out of a `.bible` source. Pure data — no
 * Android, no SQLite — so the paginator that consumes them is JVM-testable.
 *
 * Ported from Biblesprout (`data/BibleDatabase.kt`), trimmed to the reader's
 * shapes: the word layer is not in this build (the slim database carries none);
 * cross-references joined at arc 41.
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
 * renders the caller as a superscript `*`; since arc 41 a finger tap on it opens
 * [text] in a popup, with the note's own cross-references tappable inside it.
 */
data class Footnote(
    val id: Int,
    val blockId: Int,
    val offset: Int,
    val verseKey: Int?,
    val label: String?,
    val text: String,
)

/**
 * One cross-reference span (arc 41): `[start, end)` chars of its source's display text — a
 * `\r` parallel-passage block's `content` when [sourceKind] is `block`, a footnote's `text`
 * when it is `note` — pointing at the inclusive verse-key range [targetStartKey]..[targetEndKey].
 * The builder resolved the target from the USFM code or, failing that, the display text's own
 * book name, and refused anything outside the canon — so a row here always lands somewhere
 * (`tools/bible/build_bible_db.py`, `_check_xrefs`).
 */
data class Xref(
    val sourceKind: String,
    val sourceId: Int,
    val start: Int,
    val end: Int,
    val targetStartKey: Int,
    val targetEndKey: Int,
) {
    val fromBlock: Boolean get() = sourceKind == KIND_BLOCK
    val fromNote: Boolean get() = sourceKind == KIND_NOTE

    companion object {
        const val KIND_BLOCK = "block"
        const val KIND_NOTE = "note"
    }
}

/**
 * One verse of the source's `verse` table — the plain-text layer, clean of block structure
 * (arc 38 / R1, read by [BibleDatabase.versesForRange]). The **passage view** flows these as
 * prose (arc 38 / R2): the rich block layer belongs to a whole chapter, and a passage is a
 * handful of verses out of the middle of one.
 *
 * Pure data, like every other row here, so the atom builder over it is JVM-testable.
 */
data class VerseRow(
    val verseKey: Int,
    val usfm: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)
