package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import com.symmetricalpalmtree.notesproutsn.ext.bible.Footnote
import com.symmetricalpalmtree.notesproutsn.ext.bible.RenderBlock

/**
 * How tall a run of atoms renders. [ReaderTypography] is the real one — it
 * measures with the very [android.text.StaticLayout] the view then draws — but
 * the paginator only ever asks this one question, so the split keeps
 * [ChapterPaginator] pure and JVM-testable (a fake measurer needs no Android).
 */
fun interface BodyMeasurer {
    /** Rendered height of `atoms[start, start+count)` laid out at [width] px. */
    fun measure(atoms: List<Atom>, start: Int, count: Int, width: Int): Int
}

/**
 * Splits a chapter's text into screen-sized pages by binary-searching, via a
 * [BodyMeasurer], the most atoms that fit each page's height.
 *
 * Ported from Biblesprout (`reader/ChapterPaginator.kt`). The plain-verse
 * `atomsFor(chapter)` path came out (this reader only renders the rich block
 * layer), cross-references came out with the tappable headings, and the
 * `ReaderTypography` parameter became [BodyMeasurer].
 */
object ChapterPaginator {

    /**
     * Flattens the rich block layer into an atom stream carrying print structure:
     * each block contributes a leading [BreakAtom] (poetry/paragraph/stanza) or a
     * [HeadingAtom], then its verse numbers and words. Verse-number spans in a
     * block's content are lifted out as [NumberAtom]s; the rest tokenizes to words.
     */
    fun atomsForBlocks(blocks: List<RenderBlock>, footnotes: List<Footnote>): List<Atom> {
        val notesByBlock = footnotes.groupBy { it.blockId }
        val atoms = ArrayList<Atom>()
        for (block in blocks) {
            val heading = headingFor(block)
            if (heading != null) {
                atoms.add(heading)
                continue
            }
            if (block.kind == "b") {
                atoms.add(BreakAtom(Flow.STANZA))
                continue
            }
            atoms.add(BreakAtom(flowFor(block.kind)))
            tokenize(block, notesByBlock[block.id].orEmpty(), atoms)
        }
        return atoms
    }

    /**
     * Emits the block's content as NumberAtoms (at verse-marker spans) + WordAtoms,
     * splicing a [FootnoteAtom] wherever a footnote caller is anchored ([Footnote.offset]).
     */
    private fun tokenize(block: RenderBlock, notes: List<Footnote>, out: ArrayList<Atom>) {
        val content = block.content
        val marks = block.verses // sorted by start
        val callers = notes.sortedBy { it.offset }
        var i = 0
        var mi = 0
        var ni = 0
        while (i < content.length) {
            // A footnote caller can sit between any two characters (usually right
            // after a word/punctuation), so check it before words and numbers.
            if (ni < callers.size && i == callers[ni].offset) {
                out.add(FootnoteAtom(callers[ni].id))
                ni++
                continue
            }
            if (mi < marks.size && i == marks[mi].start) {
                val m = marks[mi]
                out.add(NumberAtom(m.number, m.verseKey))
                i = m.end
                mi++
                continue
            }
            if (content[i] == ' ') { i++; continue }
            val bound = minOf(
                if (mi < marks.size) marks[mi].start else content.length,
                if (ni < callers.size) callers[ni].offset else content.length,
            )
            var j = i
            while (j < bound && content[j] != ' ') j++
            if (j > i) {
                out.add(WordAtom(content.substring(i, j)))
                i = j
            } else {
                i++
            }
        }
        // A caller anchored at the very end of the block's content.
        while (ni < callers.size && callers[ni].offset >= content.length) {
            out.add(FootnoteAtom(callers[ni].id)); ni++
        }
    }

    private fun flowFor(kind: String): Flow = when (kind) {
        "q1" -> Flow.POETRY1
        "q2", "q3" -> Flow.POETRY2
        "qr" -> Flow.POETRY_REFRAIN
        "li1" -> Flow.LIST1
        "li2" -> Flow.LIST2
        else -> Flow.PARAGRAPH // p, pmo, pc, pm, mi, nb, …
    }

    private fun headingFor(block: RenderBlock): HeadingAtom? = when (block.kind) {
        "s1", "ms", "ms1" -> HeadingAtom(block.content, HeadingKind.MAJOR)
        "s2", "s3", "mr", "qa", "sr", "sp" -> HeadingAtom(block.content, HeadingKind.MINOR)
        "r" -> HeadingAtom(block.content, HeadingKind.REFERENCE)
        "d" -> HeadingAtom(block.content, HeadingKind.SUPERSCRIPTION)
        else -> null
    }

    /**
     * Splits [atoms] into pages that each fit the given heights. [firstPageHeight]
     * is usually smaller than [otherPageHeight] because the first page also carries
     * the book/chapter heading. Always places at least one atom per page.
     */
    fun paginate(
        atoms: List<Atom>,
        measurer: BodyMeasurer,
        width: Int,
        firstPageHeight: Int,
        otherPageHeight: Int,
    ): List<List<Atom>> {
        val pages = ArrayList<List<Atom>>()
        var start = 0
        while (start < atoms.size) {
            val maxHeight = if (pages.isEmpty()) firstPageHeight else otherPageHeight
            // Always place at least one atom to guarantee progress.
            val fitted = fitCount(atoms, start, maxHeight, measurer, width).coerceAtLeast(1)
            val count = trimDanglingOpeners(atoms, start, fitted)
            pages.add(ArrayList(atoms.subList(start, start + count)))
            start += count
        }
        return pages
    }

    /**
     * Never end a page on the atoms that *open* the next verse — a verse [NumberAtom],
     * or the [BreakAtom]/[HeadingAtom]s that precede it — when their text spilled onto
     * the following page. Backs the page's cut off to the last real content atom (a
     * word, or a footnote caller attached to one) so a verse number always stays on
     * the page with its text. If the fitted page has no content atom at all (e.g. a
     * lone oversized heading), it is left as-is so pagination still makes progress.
     */
    private fun trimDanglingOpeners(atoms: List<Atom>, start: Int, count: Int): Int {
        var lastContent = -1
        for (i in start until start + count) {
            when (atoms[i]) {
                is WordAtom, is FootnoteAtom -> lastContent = i
                else -> {}
            }
        }
        if (lastContent < 0) return count
        val trimmed = lastContent - start + 1
        return if (trimmed < count) trimmed else count
    }

    /**
     * The most atoms from [start] whose rendered height fits [maxHeight], or 0 if
     * not even one fits. Binary search over [BodyMeasurer.measure].
     */
    fun fitCount(
        atoms: List<Atom>,
        start: Int,
        maxHeight: Int,
        measurer: BodyMeasurer,
        width: Int,
    ): Int {
        val remaining = atoms.size - start
        if (remaining <= 0) return 0
        fun heightOf(count: Int) = measurer.measure(atoms, start, count, width)
        if (heightOf(1) > maxHeight) return 0

        var lo = 1
        var hi = 1
        while (hi < remaining && heightOf(hi) <= maxHeight) {
            lo = hi
            hi *= 2
        }
        if (hi > remaining) hi = remaining

        var best = lo
        var low = lo
        var high = hi
        while (low <= high) {
            val mid = (low + high) / 2
            if (heightOf(mid) <= maxHeight) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * The verse key of the first verse number on a page, or null when the page
     * opens mid-verse (its text carried over from the page before). This is the
     * reader's position anchor — B2 stores it, and a reopen picks the page whose
     * range contains it.
     */
    fun firstVerseKey(page: List<Atom>): Int? =
        page.firstNotNullOfOrNull { (it as? NumberAtom)?.verseKey }
}
