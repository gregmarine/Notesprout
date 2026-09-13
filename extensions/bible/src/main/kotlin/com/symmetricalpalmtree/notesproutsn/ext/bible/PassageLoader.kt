package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Atom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.BreakAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ChapterPaginator
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Flow
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.HeadingAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.HeadingKind
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.NumberAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.ReaderPage
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.WordAtom

/**
 * One paginated **passage**, ready to page through (arc 38 / R2) — [ChapterPages]' sibling for
 * the reference the notebook followed here. It carries no anchors and no [ChapterRef] of its
 * own: a passage is not a place the reader can be left, so **no position is written from it**
 * (`REFERENCE_PLAN.md`), and there is nothing to flow into off either end.
 *
 * [openAt] / [openVerse] are the **first** range's start — where the Full chapter door goes, and
 * the one thing a passage says about the wider book.
 */
class PassagePages(
    /** The wire the host handed us, kept verbatim: it is this showing's identity in the recents. */
    val wire: String,
    /** The canonical label — the screen's title ("John 3:14–18; Proverbs 3:5–6"). */
    val label: String,
    val pages: List<List<Atom>>,
    val rendered: List<ReaderPage>,
    val openAt: ChapterRef,
    val openVerse: Int,
) {
    val size: Int get() = pages.size
}

/**
 * The passage view's atom stream, as arithmetic (arc 38 / R2) — pure Kotlin over
 * [VerseRow]s, so it is JVM-tested without a database or a font.
 *
 * A passage is **not** a chapter: the rich block layer (paragraphs, poetry lines, section
 * headings, footnote callers) describes a whole chapter's typesetting, and half a chapter of it
 * read out of the middle would open on a hanging poetry indent or a heading belonging to verses
 * that are not here. So the passage view flows the `verse` table's clean plain text instead — the
 * reader's own **paragraph** — and marks structure only where the reference itself changes
 * subject: a MAJOR heading naming the book and chapter at every crossing, the shape Biblesprout's
 * passage view had.
 *
 * **Never logged**: what these atoms carry is scripture, and where they come from is a reference.
 */
object PassageAtoms {

    /**
     * [verses] (already in reading order, the ranges concatenated as written) as atoms: at every
     * (book, chapter) change a heading and a fresh paragraph, then each verse's superscript
     * number and its words. Words split on spaces — the paginator cuts on atom boundaries, so a
     * word is the smallest thing a page can end on.
     */
    fun atomsFor(verses: List<VerseRow>): List<Atom> {
        val atoms = ArrayList<Atom>()
        var usfm: String? = null
        var chapter = 0
        for (row in verses) {
            if (row.usfm != usfm || row.chapter != chapter) {
                usfm = row.usfm
                chapter = row.chapter
                atoms.add(
                    HeadingAtom("${Canon.chapterTitleName(row.usfm)} ${row.chapter}", HeadingKind.MAJOR),
                )
                // One paragraph per chapter run: the verses of one chapter read as one block of
                // prose, and the next crossing opens its own.
                atoms.add(BreakAtom(Flow.PARAGRAPH))
            }
            atoms.add(NumberAtom(row.verse, row.verseKey))
            for (word in row.text.split(' ')) {
                if (word.isNotEmpty()) atoms.add(WordAtom(word))
            }
        }
        return atoms
    }
}

/**
 * Builds a [PassagePages] out of a wire (arc 38 / R2) — the passage view's whole IO half.
 *
 * It owns nothing: the source, the typography and the build monitor are [ChapterLoader]'s
 * ([ChapterLoader.withSource]), so one screen has one open database, one `TextPaint`, and one
 * thread measuring it at a time. Closing is the chapter loader's too.
 *
 * **Every method here blocks — call it on `Dispatchers.IO`, never Main.**
 */
class PassageLoader(private val chapters: ChapterLoader) {

    /**
     * [wire] read, flowed and paginated for a page of [width] × [height] px. A range the source
     * has nothing for is **skipped** — a reference can outlive a source that omits a book — but a
     * wire that yields no verses at all throws: there is no passage to show, and the screen says
     * so with its problem dialog.
     *
     * Both page heights are the same: a passage has no big chapter number over its first page,
     * because it usually does not start at a chapter's beginning.
     */
    fun passage(wire: String, width: Int, height: Int): PassagePages {
        val passages = ReferenceCodec.decode(wire) ?: error("unreadable reference")
        val first = passages.first().ranges.first()
        val openAt = ChapterRef(
            Canon.byOrdinal(VerseKey.ordinalOf(first.startKey)).usfm,
            VerseKey.chapterOf(first.startKey),
        )
        // A whole-chapter range starts at the sentinel verse 0; the chapter itself starts at 1.
        val openVerse = VerseKey.verseOf(first.startKey).coerceAtLeast(1)
        return chapters.withSource { db, typo ->
            val verses = ArrayList<VerseRow>()
            for (passage in passages) {
                for (range in passage.ranges) {
                    verses.addAll(db.versesForRange(range.startKey, range.endKey))
                }
            }
            check(verses.isNotEmpty()) { "no verses for the reference" }
            val atoms = PassageAtoms.atomsFor(verses)
            val safety = typo.dp(ChapterLoader.SAFETY_PAD_DP)
            val pages = ChapterPaginator.paginate(
                atoms,
                typo,
                width,
                firstPageHeight = height - safety,
                otherPageHeight = height - safety,
            )
            check(pages.isNotEmpty()) { "no pages for the reference" }
            // Laid out here, on IO: a page turn then costs one invalidate on Main.
            val rendered = pages.map { ReaderPage(typo.bodyLayout(it, width)) }
            PassagePages(
                wire = wire,
                label = ReferenceCodec.label(passages),
                pages = pages,
                rendered = rendered,
                openAt = openAt,
                openVerse = openVerse,
            )
        }
    }
}
