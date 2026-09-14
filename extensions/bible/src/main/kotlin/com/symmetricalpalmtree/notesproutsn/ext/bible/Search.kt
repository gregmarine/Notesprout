package com.symmetricalpalmtree.notesproutsn.ext.bible

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * Search (arc 37 / B8, the user's decision 2026-09-13) — Biblesprout's `FindActivity` in the
 * reader's shape: **one field, two answers.** What is typed is first read as a scripture
 * reference; if it is one, the reader simply goes there. Otherwise it is words, and the words are
 * looked for in the text — a list of possible hits, best matches first, a tap opening the chapter
 * on that verse's page.
 *
 * Everything in this file is pure Kotlin, JVM-tested: the query grammar, the ranking, the
 * routing and the snippet. The one thing that needs a database is [BibleDatabase.search].
 *
 * **Nothing typed and nothing found is ever logged** — a query names what the user is looking
 * for, which is as private as where they have read.
 */

/** One verse the words were found in — the `verse` table's row, plus its rank. */
data class SearchHit(
    val verseKey: Int,
    val usfm: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
) {
    val ref: ChapterRef get() = ChapterRef(usfm, chapter)

    /** "Psalm 23:1", "John 3:16" — the running head's book name and the verse's own address. */
    val label: String get() = "${Canon.chapterTitleName(usfm)} $chapter:$verse"
}

/**
 * What a search of [query] found: at most [SearchQuery.MAX_HITS] rows, best first, and the
 * **true** [total] — the list is capped, the count is honest (Biblesprout's list is silently
 * capped at 100; here the header says "First 100 of 412").
 */
data class SearchResults(val query: String, val tokens: List<String>, val hits: List<SearchHit>, val total: Int) {
    val capped: Boolean get() = total > hits.size
}

/**
 * The words → the FTS4 `MATCH` expression. Biblesprout's `Fts.matchExpression`, in the FTS4
 * grammar the platform SQLite speaks: every letter/digit run is a token, lowercased, each with a
 * trailing `*` (a prefix — "love" also finds "loved" and "lovely"), all of them space-joined,
 * which FTS reads as **AND**. Lowercasing is also what keeps the words safe: FTS4's operators
 * (`OR`, `NOT`, `NEAR`) are upper-case only, and the token regex admits no punctuation, so no
 * query is ever a syntax error.
 */
object SearchQuery {

    /** The list is capped here; the count is not ([SearchResults.total]). */
    const val MAX_HITS = 100

    private val token = Regex("[\\p{L}\\p{N}]+")

    /** The lowercased word tokens of [query], in order — empty for punctuation alone. */
    fun tokens(query: String): List<String> = token.findAll(query.lowercase()).map { it.value }.toList()

    /** The FTS4 expression, or `null` when there is nothing searchable in [query]. */
    fun matchExpression(query: String): String? {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}

/**
 * BM25 over FTS4's `matchinfo(verse_fts, 'pcnalx')` blob — the ranking FTS5 gives away as
 * `ORDER BY rank`, done by hand because the platform engine's FTS4 has no `rank`. The blob is
 * little-endian 32-bit ints: `p` phrases, `c` columns, `n` rows in the table, `a[c]` average
 * tokens per column, `l[c]` this row's tokens per column, then for every (phrase, column) the
 * triple (hits in this row, hits in all rows, rows with a hit). One column here, so the sums are
 * over phrases only. Higher is better; a ubiquitous prefix ("the*") scores every row alike, so
 * the tie-break — canonical order — is what the reader then sees.
 */
object SearchRank {

    private const val K1 = 1.2
    private const val B = 0.75

    fun bm25(matchinfo: ByteArray): Double = bm25(toInts(matchinfo))

    fun bm25(m: IntArray): Double {
        if (m.size < 3) return 0.0
        val phrases = m[0]
        val columns = m[1]
        val rows = m[2].toDouble()
        if (phrases <= 0 || columns <= 0 || rows <= 0.0) return 0.0
        val avgAt = 3
        val lenAt = avgAt + columns
        val hitsAt = lenAt + columns
        if (m.size < hitsAt + 3 * phrases * columns) return 0.0
        var score = 0.0
        for (p in 0 until phrases) {
            for (c in 0 until columns) {
                val base = hitsAt + 3 * (p * columns + c)
                val tf = m[base].toDouble()
                if (tf <= 0.0) continue
                val docsWithHit = m[base + 2].toDouble()
                val idf = ln((rows - docsWithHit + 0.5) / (docsWithHit + 0.5)).coerceAtLeast(0.0)
                val avg = m[avgAt + c].toDouble().coerceAtLeast(1.0)
                val len = m[lenAt + c].toDouble()
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * len / avg))
            }
        }
        return score
    }

    /**
     * The best [limit] of [scored] (`verseKey to score`), best first, **ties in canonical
     * order** — the same verse key ordering the book has.
     */
    fun top(scored: List<Pair<Int, Double>>, limit: Int): List<Int> =
        Scored().also { s -> for ((key, score) in scored) s.add(key, score) }.top(limit)

    /**
     * Scored rows held as two primitive arrays — no object per row — with [top] as a bounded
     * selection: a min-heap of at most `limit` indices, so the ubiquitous prefix costs
     * O(n log k), not a sort of everything to keep a hundred.
     */
    class Scored {
        private var keys = IntArray(256)
        private var scores = DoubleArray(256)
        var size = 0
            private set

        fun add(key: Int, score: Double) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                scores = scores.copyOf(size * 2)
            }
            keys[size] = key
            scores[size] = score
            size++
        }

        /** Best first; equal scores in canonical (ascending key) order. */
        fun top(limit: Int): List<Int> {
            if (limit <= 0 || size == 0) return emptyList()
            // "Worse" = lower score, or the same score and a later key: the heap's head is the
            // worst of the kept, and a newcomer replaces it only when it beats it.
            val worse = Comparator<Int> { a, b ->
                val byScore = scores[a].compareTo(scores[b])
                if (byScore != 0) byScore else keys[b].compareTo(keys[a])
            }
            val heap = java.util.PriorityQueue<Int>(limit.coerceAtMost(size) + 1, worse)
            for (i in 0 until size) {
                if (heap.size < limit) {
                    heap.add(i)
                } else if (worse.compare(i, heap.peek()!!) > 0) {
                    heap.poll()
                    heap.add(i)
                }
            }
            val out = IntArray(heap.size)
            var n = out.size
            while (n > 0) out[--n] = keys[heap.poll()!!]
            return out.toList()
        }
    }

    private fun toInts(blob: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(blob.size / 4)
        for (i in out.indices) out[i] = buf.getInt(i * 4)
        return out
    }
}

/**
 * Which door a query goes through — the syntax half of Biblesprout's classification (the
 * other half, whether the verses **exist**, is [ReferenceResolver]'s against the source, and a
 * reference that fails it is searched as words, exactly as there).
 *
 * - **A whole chapter** on its own ("Psalm 23", "Ps 23") is a chapter — the reading model, with
 *   its flow and its bookmark, not a citation.
 * - **Anything else that parses** ("John 3:16", "Gen 1-2", "John 3:14-17, Acts 1:3") is a
 *   passage, opened in the passage view.
 * - **Anything else** is words.
 */
sealed interface SearchRoute {
    data class Chapter(val ref: ChapterRef) : SearchRoute
    data class Passage(val wire: String, val passages: List<com.symmetricalpalmtree.notesproutsn.ext.bible.Passage>) : SearchRoute
    data class Words(val query: String) : SearchRoute

    companion object {
        /**
         * [chapterCount] lets a one-chapter book's verse-only citation ("Jude 5") route as the
         * passage it names ([ReferenceResolver.normalize]); without one every bare number is a
         * chapter, as the parser reads it.
         */
        fun classify(query: String, chapterCount: (usfm: String) -> Int = { 0 }): SearchRoute {
            val trimmed = query.trim()
            val passages = ReferenceResolver.normalize(ReferenceParser.parseAll(trimmed), chapterCount)
            if (passages.isEmpty()) return Words(trimmed)
            if (passages.size == 1 && passages[0].ranges.size == 1) {
                val r = passages[0].ranges[0]
                val c1 = VerseKey.chapterOf(r.startKey)
                val c2 = VerseKey.chapterOf(r.endKey)
                if (c1 == c2 && VerseKey.verseOf(r.startKey) == 0 && VerseKey.verseOf(r.endKey) == VerseKey.MAX_VERSE) {
                    return Chapter(ChapterRef(passages[0].book.usfm, c1))
                }
            }
            return Passage(ReferenceCodec.encode(passages), passages)
        }
    }
}

/**
 * A hit's two-line snippet (Biblesprout binds the whole verse and lets two lines clip it, so a
 * match past the second line is invisible). Here the text is **windowed** to start a few words
 * before the first match when that match sits deep in the verse, and every matched word is
 * returned as a bold range — the one typographic emphasis e-ink renders.
 */
object SearchSnippet {

    /** How many characters of run-up a deep match keeps in front of it. */
    const val LEAD_CHARS = 36

    data class Rendered(val text: String, val bold: List<IntRange>)

    /**
     * The word-start positions in [text] that begin with one of [tokens] (case-insensitive), as
     * `start until end` ranges of the token's length, in text order.
     */
    fun matches(text: String, tokens: List<String>): List<IntRange> {
        if (tokens.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < lower.length) {
            if (!lower[i].isLetterOrDigit()) { i++; continue }
            val start = i
            while (i < lower.length && lower[i].isLetterOrDigit()) i++
            val word = lower.substring(start, i)
            val hit = tokens.firstOrNull { word.startsWith(it) } ?: continue
            out.add(start until start + hit.length)
        }
        return out
    }

    fun render(text: String, tokens: List<String>): Rendered {
        val found = matches(text, tokens)
        val first = found.firstOrNull()?.first ?: 0
        if (first <= LEAD_CHARS) return Rendered(text, found)
        // Cut at a word boundary at most LEAD_CHARS before the first match, and say so.
        var cut = first - LEAD_CHARS
        while (cut > 0 && !text[cut - 1].isWhitespace()) cut++
        if (cut >= first) cut = first
        val prefix = "…"
        val shift = prefix.length - cut
        return Rendered(
            prefix + text.substring(cut),
            found.map { (it.first + shift)..(it.last + shift) },
        )
    }
}
