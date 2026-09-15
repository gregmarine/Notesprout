package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference

/**
 * A parsed reference (arc 38 / R1): an ordered set of [VerseRange]s that all belong to one
 * [book]. `John 3:14-16,18` becomes two ranges; `Genesis 1` one whole-chapter range. Ported from
 * Biblesprout (`data/Reference.kt`), minus `rawText` — the user's own words stay on the notebook
 * page (decision 2), so nothing here needs to carry them.
 *
 * Pure Kotlin — JVM-tested. Never logged: a reference names where the user has read.
 */
data class Passage(
    val book: CanonBook,
    val ranges: List<VerseRange>,
) {
    init { require(ranges.isNotEmpty()) { "a passage names at least one range" } }

    val startKey: Int get() = ranges.first().startKey
    val endKey: Int get() = ranges.last().endKey

    /**
     * A tidy canonical rendering, e.g. `John 3:14–16, 18`. Chapter prefixes are omitted once a
     * chapter is already established, matching how these are written by hand; a whole chapter
     * is the bare number (`Genesis 1`, `Genesis 1–2`). Biblesprout's, verbatim.
     */
    fun format(): String {
        val parts = ArrayList<String>()
        var shownChapter: Int? = null
        for (r in ranges) {
            val sc = VerseKey.chapterOf(r.startKey)
            val sv = VerseKey.verseOf(r.startKey)
            val ec = VerseKey.chapterOf(r.endKey)
            val ev = VerseKey.verseOf(r.endKey)

            if (sv == 0 && ev == VerseKey.MAX_VERSE) {
                parts.add(if (sc == ec) "$sc" else "$sc–$ec")
                shownChapter = null
                continue
            }
            if (sc == ec) {
                val prefix = if (shownChapter == sc) "" else "$sc:"
                parts.add(if (sv == ev) "$prefix$sv" else "$prefix$sv–$ev")
                shownChapter = sc
            } else {
                parts.add("$sc:$sv–$ec:$ev")
                shownChapter = ec
            }
        }
        return "${book.name} ${parts.joinToString(", ")}"
    }
}

/**
 * Turns the user's own words into [Passage]s. Tolerant of case, punctuation, spacing, en-dashes
 * and the common book abbreviations ([Canon.lookup]). Biblesprout's `ReferenceParser`, ported
 * verbatim: what it accepts is what the notebook's reference dialog accepts.
 *
 * Parsing says nothing about whether the verses **exist** — that is [ReferenceResolver]'s, against
 * the `.bible` itself.
 */
object ReferenceParser {
    // Book part must end in a letter/period; the spec starts at the first digit, so "1 Cor 13:4"
    // and spaceless "Ps23" both work.
    private val split = Regex("^\\s*(.*?[A-Za-z.])\\s*([0-9].*)$")
    private val crossChapterSpan = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")
    private val hasLetter = Regex("[A-Za-z]")

    /** Parses a single reference, or null if the book/spec is unknown or malformed. */
    fun parse(input: String): Passage? {
        val m = split.find(input.trim()) ?: return null
        val book = Canon.lookup(m.groupValues[1]) ?: return null
        val spec = m.groupValues[2].replace("–", "-").replace("—", "-").replace(" ", "")
        val ranges = parseSpec(book.ordinal, spec) ?: return null
        if (ranges.isEmpty()) return null
        return Passage(book, ranges)
    }

    /**
     * Parses one or more references separated by commas/semicolons, e.g. `John 3:14-17, Acts
     * 1:3` → two passages. A bare number continues the previous book (`John 3:16, 18`). Returns
     * an **empty list** when any part of the input is not a reference — the whole line stands
     * or falls together, so a dead half can never be linked.
     */
    fun parseAll(input: String): List<Passage> {
        val chunks = ArrayList<String>()
        val buffer = ArrayList<String>()
        fun flush() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.joinToString(", "))
                buffer.clear()
            }
        }

        for (part in input.split(Regex("[;,]"))) {
            val seg = part.trim()
            if (seg.isEmpty()) continue
            if (hasLetter.containsMatchIn(seg)) {
                flush() // a book name begins a new reference
                buffer.add(seg)
            } else {
                if (buffer.isEmpty()) return emptyList() // a number can't lead
                buffer.add(seg)
            }
        }
        flush()
        if (chunks.isEmpty()) return emptyList()

        val passages = ArrayList<Passage>()
        for (chunk in chunks) {
            val passage = parse(chunk) ?: return emptyList() // any bad chunk ⇒ nothing
            passages.add(passage)
        }
        return passages
    }

    private fun parseSpec(ordinal: Int, spec: String): List<VerseRange>? {
        // No colon → chapter-level ("3", "1-2", "1,3,5").
        if (!spec.contains(':')) {
            val ranges = ArrayList<VerseRange>()
            for (seg in spec.split(',')) {
                if (seg.isEmpty()) continue
                val dash = seg.indexOf('-')
                if (dash < 0) {
                    val c = seg.toIntOrNull() ?: return null
                    if (!chapterOk(c)) return null
                    ranges.add(VerseRange.chapters(ordinal, c, c))
                } else {
                    val a = seg.substring(0, dash).toIntOrNull()
                    val b = seg.substring(dash + 1).toIntOrNull()
                    if (a == null || b == null || !chapterOk(a) || !chapterOk(b) || b < a) return null
                    ranges.add(VerseRange.chapters(ordinal, a, b))
                }
            }
            return ranges.ifEmpty { null }
        }

        // Whole-spec cross-chapter verse span, e.g. "1:5-2:3".
        crossChapterSpan.find(spec)?.let { span ->
            val nums = span.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            val (c1, v1, c2, v2) = nums
            if (!chapterOk(c1) || !verseOk(v1) || !chapterOk(c2) || !verseOk(v2)) return null
            val start = VerseKey.encode(ordinal, c1, v1)
            val end = VerseKey.encode(ordinal, c2, v2)
            if (end < start) return null
            return listOf(VerseRange(start, end))
        }

        // Verse-level, comma-separated, carrying the chapter forward: "3:14-16,18".
        val ranges = ArrayList<VerseRange>()
        var chapter: Int? = null
        for (seg in spec.split(',')) {
            if (seg.isEmpty()) continue
            var vspec = seg
            val colon = seg.indexOf(':')
            if (colon >= 0) {
                chapter = seg.substring(0, colon).toIntOrNull()
                vspec = seg.substring(colon + 1)
            }
            val ch = chapter ?: return null // a bare verse with no chapter context
            if (!chapterOk(ch)) return null
            val dash = vspec.indexOf('-')
            if (dash < 0) {
                val v = vspec.toIntOrNull() ?: return null
                if (!verseOk(v)) return null
                ranges.add(VerseRange.verse(ordinal, ch, v))
            } else {
                val a = vspec.substring(0, dash).toIntOrNull()
                val b = vspec.substring(dash + 1).toIntOrNull()
                if (a == null || b == null || !verseOk(a) || !verseOk(b) || b < a) return null
                ranges.add(VerseRange.verses(ordinal, ch, a, b))
            }
        }
        return ranges.ifEmpty { null }
    }

    // A number [VerseKey] cannot pack is not a reference — "Psalm 1000" would wrap into chapter
    // 0, and a verse past `Int` would overflow the key and throw out of `VerseRange`. Typed text
    // reaches this parser straight from the reader's Search field, so it must never throw.
    private fun chapterOk(c: Int) = c in 1 until VerseKey.CHAPTER_LIMIT
    private fun verseOk(v: Int) = v in 1 until VerseKey.MAX_VERSE
}

/**
 * The wire form of a resolved reference (arc 38 / R1) — what crosses the seam in
 * `ResolvedReference.wire`, what the host stores in a Bible link's payload, and what `beginAt`
 * hands back: one `USFM:c:v-c:v` per range, ranges joined by `,`, a whole chapter as
 * `USFM:c:0-c:999` ([VerseKey.MAX_VERSE]). ASCII, no whitespace, no `|`. Example:
 * `JHN:3:14-3:18,PRO:3:5-3:6`.
 *
 * The **only** reader of the grammar is this object; the host treats the string as opaque and
 * checks nothing but the character set. [decode] is **total** — a wire from a store or a payload
 * this build did not write yields `null`, never a throw. Ranges keep their written order.
 */
object ReferenceCodec {

    /** The host's cap on a wire ([ResolvedReference.MAX_WIRE_CHARS]) — one number, not a mirror. */
    const val MAX_WIRE_CHARS = ResolvedReference.MAX_WIRE_CHARS

    fun encode(passages: List<Passage>): String =
        passages.joinToString(",") { p ->
            p.ranges.joinToString(",") { r ->
                "${p.book.usfm}:${VerseKey.chapterOf(r.startKey)}:${VerseKey.verseOf(r.startKey)}-" +
                    "${VerseKey.chapterOf(r.endKey)}:${VerseKey.verseOf(r.endKey)}"
            }
        }

    /**
     * The wire back into passages, adjacent ranges of one book folded into one [Passage] (so
     * `format()` can drop repeated chapter prefixes the way the writer did), or `null` for
     * anything this build cannot read.
     */
    fun decode(wire: String?): List<Passage>? {
        if (wire.isNullOrEmpty() || wire.length > MAX_WIRE_CHARS) return null
        val out = ArrayList<Passage>()
        var book: CanonBook? = null
        var ranges = ArrayList<VerseRange>()
        for (part in wire.split(',')) {
            val fields = part.split(':')
            if (fields.size != 4) return null
            val b = Canon.tryUsfm(fields[0]) ?: return null
            val c1 = fields[1].toIntOrNull() ?: return null
            val dash = fields[2].indexOf('-')
            if (dash < 0) return null
            val v1 = fields[2].substring(0, dash).toIntOrNull() ?: return null
            val c2 = fields[2].substring(dash + 1).toIntOrNull() ?: return null
            val v2 = fields[3].toIntOrNull() ?: return null
            if (c1 !in 1 until VerseKey.CHAPTER_LIMIT || c2 !in 1 until VerseKey.CHAPTER_LIMIT) return null
            if (v1 !in 0..VerseKey.MAX_VERSE || v2 !in 0..VerseKey.MAX_VERSE) return null
            val start = VerseKey.encode(b.ordinal, c1, v1)
            val end = VerseKey.encode(b.ordinal, c2, v2)
            if (end < start) return null
            if (book != null && book.usfm != b.usfm) {
                out.add(Passage(book, ranges))
                ranges = ArrayList()
            }
            book = b
            ranges.add(VerseRange(start, end))
        }
        val last = book ?: return null
        out.add(Passage(last, ranges))
        return out
    }

    /**
     * A whole chapter as one passage (B9 "Send") — what the reader's Send parks while a chapter
     * is on screen: the `USFM:c:0-c:999` range, so it decodes and labels exactly like a typed
     * "John 3" (`format()` names a whole chapter bare).
     */
    fun wholeChapter(ref: ChapterRef): Passage {
        val book = Canon.byUsfm(ref.usfm)
        val (start, end) = VerseKey.chapterBounds(book.ordinal, ref.chapter)
        return Passage(book, listOf(VerseRange(start, end)))
    }

    /** The canonical label of a decoded wire — the passages' `format()`s joined by `; `. */
    fun label(passages: List<Passage>): String = passages.joinToString("; ") { it.format() }
}

/**
 * The bounds check (arc 38 / R1): whether every chapter and every explicitly named verse of the
 * parsed passages exists in the source — the half of "is this a real reference" that only the
 * `.bible` can answer, and the reason resolving lives in the extension. Pure over two functions
 * so it is JVM-tested without a database: [chapterCount] answers a book's length (0 for a book
 * the source omits), [verseExists] whether one exact verse key is present.
 *
 * A whole-chapter range (`v == 0 .. MAX_VERSE`) needs only its chapters to exist; a verse range
 * needs its **endpoints** to exist — the verses between them are the source's to have or not
 * (a chapter has no holes in the BSB, and a hole would still render what is there).
 */
object ReferenceResolver {

    /**
     * The one-chapter books (Obadiah, Philemon, 2 John, 3 John, Jude) are cited by verse alone —
     * "Jude 24", "Philemon 6" — and the parser, which knows no chapter counts, reads every bare
     * number as a chapter. Rewrites such a whole-chapter range on a book [chapterCount] says has
     * exactly one chapter into the verses of chapter 1: `Jude 24` → `1:24`, `Jude 3-5` → `1:3-5`.
     * A bare `1` alone stays the whole book, as written. Every other passage passes untouched.
     * Pure; runs before [valid], which would otherwise refuse the chapter as out of range.
     */
    fun normalize(passages: List<Passage>, chapterCount: (usfm: String) -> Int): List<Passage> =
        passages.map { p ->
            if (chapterCount(p.book.usfm) != 1) return@map p
            val ranges = p.ranges.map { r ->
                val c1 = VerseKey.chapterOf(r.startKey)
                val c2 = VerseKey.chapterOf(r.endKey)
                val whole = VerseKey.verseOf(r.startKey) == 0 && VerseKey.verseOf(r.endKey) == VerseKey.MAX_VERSE
                if (!whole || (c1 == 1 && c2 == 1)) r
                else VerseRange.verses(p.book.ordinal, 1, c1, c2)
            }
            if (ranges == p.ranges) p else Passage(p.book, ranges)
        }

    fun valid(
        passages: List<Passage>,
        chapterCount: (usfm: String) -> Int,
        verseExists: (verseKey: Int) -> Boolean,
    ): Boolean {
        if (passages.isEmpty()) return false
        for (p in passages) {
            val chapters = chapterCount(p.book.usfm)
            if (chapters <= 0) return false
            for (r in p.ranges) {
                val c1 = VerseKey.chapterOf(r.startKey)
                val c2 = VerseKey.chapterOf(r.endKey)
                if (c1 < 1 || c2 > chapters) return false
                val v1 = VerseKey.verseOf(r.startKey)
                val v2 = VerseKey.verseOf(r.endKey)
                if (v1 == 0 && v2 == VerseKey.MAX_VERSE) continue   // whole chapter(s)
                if (v1 == 0 || v2 == VerseKey.MAX_VERSE) return false
                if (!verseExists(r.startKey) || !verseExists(r.endKey)) return false
            }
        }
        return true
    }
}

/**
 * A cross-reference's stored target — an inclusive verse-key range, one book by construction
 * (a `\ref` target names one book; `_check_xrefs` refuses a range that spans two) — as the wire
 * every other door already opens the passage view on (arc 41). A whole-chapter target carries
 * the builder's `0`/`999` sentinels, which are exactly the codec's own.
 */
object XrefWire {
    fun of(startKey: Int, endKey: Int): String {
        require(startKey <= endKey) { "reversed range" }
        val book = Canon.byOrdinal(VerseKey.ordinalOf(startKey))
        return ReferenceCodec.encode(listOf(Passage(book, listOf(VerseRange(startKey, endKey)))))
    }
}
