package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import com.symmetricalpalmtree.notesproutsn.ext.bible.Footnote
import com.symmetricalpalmtree.notesproutsn.ext.bible.RenderBlock
import com.symmetricalpalmtree.notesproutsn.ext.bible.Xref

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
 * layer) and the `ReaderTypography` parameter became [BodyMeasurer]; the
 * cross-reference links on `\r` headings came back at arc 41.
 */
object ChapterPaginator {

    /**
     * Flattens the rich block layer into an atom stream carrying print structure:
     * each block contributes a leading [BreakAtom] (poetry/paragraph/stanza) or a
     * [HeadingAtom], then its verse numbers and words. Verse-number spans in a
     * block's content are lifted out as [NumberAtom]s; the rest tokenizes to words.
     * [xrefs] (arc 41): the chapter's cross-references — those sourced on a block ride its
     * [HeadingAtom] as [XrefLink]s (only `\r` lines carry any); note-sourced ones are the
     * footnote popup's, not the page's, and are ignored here.
     */
    fun atomsForBlocks(
        blocks: List<RenderBlock>,
        footnotes: List<Footnote>,
        xrefs: List<Xref> = emptyList(),
    ): List<Atom> {
        val notesByBlock = footnotes.groupBy { it.blockId }
        val linksByBlock = xrefs.filter { it.fromBlock }.groupBy { it.sourceId }
        val atoms = ArrayList<Atom>()
        for (block in blocks) {
            val heading = headingFor(block, linksByBlock[block.id].orEmpty())
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

    /** [block]'s content with every verse-marker span (and the space after it) cut out. */
    private fun withoutMarkers(block: RenderBlock): String {
        if (block.verses.isEmpty()) return block.content
        val sb = StringBuilder(block.content.length)
        var i = 0
        for (m in block.verses.sortedBy { it.start }) {
            if (m.start < i || m.end > block.content.length) continue
            sb.append(block.content, i, m.start)
            i = m.end
            if (i < block.content.length && block.content[i] == ' ') i++
        }
        sb.append(block.content, i, block.content.length)
        return sb.toString().trim()
    }

    private fun flowFor(kind: String): Flow = when (kind) {
        "q1" -> Flow.POETRY1
        "q2", "q3" -> Flow.POETRY2
        "qr" -> Flow.POETRY_REFRAIN
        "li1" -> Flow.LIST1
        "li2" -> Flow.LIST2
        else -> Flow.PARAGRAPH // p, pmo, pc, pm, mi, nb, …
    }

    private fun headingFor(block: RenderBlock, xrefs: List<Xref>): HeadingAtom? = when (block.kind) {
        "s1", "ms", "ms1" -> HeadingAtom(block.content, HeadingKind.MAJOR)
        "s2", "s3", "mr", "qa", "sr", "sp" -> HeadingAtom(block.content, HeadingKind.MINOR)
        // The heading's text is the block's content verbatim, so the builder's char spans map
        // onto it one to one.
        "r" -> HeadingAtom(
            block.content,
            HeadingKind.REFERENCE,
            xrefs.map { XrefLink(it.start, it.end, it.targetStartKey, it.targetEndKey) },
        )
        // A psalm's superscription carries its `\v 1` marker inline ("1 A Psalm of David…"): the
        // digit is the builder's, not the heading's, and a heading has no number.
        "d" -> HeadingAtom(withoutMarkers(block), HeadingKind.SUPERSCRIPTION)
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
     * The verse **in effect** at the top of each page — the reader's position anchor (arc 37 / B2).
     *
     * A page's anchor is the verse whose text its first line belongs to: the last verse number
     * standing before the page's first word (a number, or a heading then a number, may open the
     * page), and otherwise the verse carried in from the page before, because a page that opens
     * mid-verse is still *inside* that verse. A page with no verse anywhere before or on it — a
     * lone heading page at a chapter's start — anchors to verse 1, the chapter's own beginning.
     *
     * Anchors are non-decreasing by construction, which is what makes [pageContaining] a plain
     * scan, and what makes "write the anchor on every turn, reopen on the page containing it"
     * round-trip: the page a verse lands on is the page it was written from.
     */
    fun anchorVerses(pages: List<List<Atom>>): List<Int> {
        val anchors = ArrayList<Int>(pages.size)
        var carried = 0 // the last verse number seen on any earlier page; 0 = none yet
        for (page in pages) {
            var opening = 0 // the last number standing before this page's first content atom
            for (atom in page) {
                if (atom is WordAtom || atom is FootnoteAtom) break
                if (atom is NumberAtom) opening = atom.number
            }
            anchors.add(if (opening > 0) opening else carried.coerceAtLeast(1))
            for (atom in page) if (atom is NumberAtom) carried = atom.number
        }
        return anchors
    }

    /**
     * The page [verse] is read on: the **last** page whose anchor is at or before it, or page 0
     * when none is (a verse before the first anchor, or no pages measured at all). Total by
     * design — a stored position from a differently-sized page run must still open something.
     */
    fun pageContaining(anchors: List<Int>, verse: Int): Int {
        var page = 0
        for (index in anchors.indices) if (anchors[index] <= verse) page = index
        return page
    }
}
