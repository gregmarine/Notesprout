package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

/**
 * The smallest unit the paginator moves around: a raised verse number or a
 * single word of body text. Pages are always cut on atom boundaries, so words
 * are never split and no fragile character-offset math is needed.
 *
 * Ported from Biblesprout (`reader/Atom.kt`). The link and word-layer fields came
 * out with the features that used them — this reader has no cross-reference
 * taps, no word popup and no highlights — and the heading's `minor` boolean grew
 * into [HeadingKind], because the four heading kinds now render differently
 * (decision 8: psalm superscriptions italic, parallel-passage lines small italic).
 */
sealed interface Atom

/**
 * A verse number. [verseKey] is the canonical key it heads — what the reader
 * writes as its last-read position.
 */
data class NumberAtom(val number: Int, val verseKey: Int) : Atom

/** A single word of body text. */
data class WordAtom(val word: String) : Atom

/**
 * How a [BreakAtom] starts a new line — the print structure carried over from the
 * USFM block `kind`. PARAGRAPH is prose (first-line indent); POETRY levels are
 * indented, wrapped-line-hanging poetry; STANZA is a blank separator line.
 */
enum class Flow { PARAGRAPH, POETRY1, POETRY2, POETRY_REFRAIN, LIST1, LIST2, STANZA }

/** Forces a new line in the flow, styled by [flow] (poetry indent, paragraph…). */
data class BreakAtom(val flow: Flow) : Atom

/**
 * What a centered heading line is, and therefore how it renders: a section
 * heading (`s1`/`ms`), a minor one (`s2`, `mr`, `qa`…), a `\r` parallel-passage
 * reference line, or a psalm superscription (`\d`).
 */
enum class HeadingKind { MAJOR, MINOR, REFERENCE, SUPERSCRIPTION }

/** A centered heading rendered inline in the flow (e.g. "The Creation"). */
data class HeadingAtom(val text: String, val kind: HeadingKind) : Atom

/**
 * A footnote caller anchored between words. Rendered as a superscript `*` and
 * **not** tappable in this build (decision 8); [id] is kept so a future popup has
 * the footnote it belongs to.
 */
data class FootnoteAtom(val id: Int) : Atom
