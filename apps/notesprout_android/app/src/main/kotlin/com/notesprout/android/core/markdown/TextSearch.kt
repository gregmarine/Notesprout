package com.notesprout.android.core.markdown

/**
 * Pure text search / replace / counting behind the document editor's find bar and word count —
 * kept as plain functions, like the rest of this package, so they are covered by JVM tests and
 * behave identically wherever they are asked.
 *
 * Matching is case-insensitive and non-overlapping (each match starts after the previous match's
 * end), which is what a writer expects from a find bar. See docs/documents.md.
 */
object TextSearch {

    data class Match(val start: Int, val end: Int)

    /** Every match of [query] in [text] — case-insensitive, non-overlapping, in order. */
    fun matches(text: String, query: String): List<Match> {
        if (query.isEmpty()) return emptyList()
        val result = ArrayList<Match>()
        var from = 0
        while (from <= text.length - query.length) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            result += Match(at, at + query.length)
            from = at + query.length
        }
        return result
    }

    /**
     * Index into [matches] of the next match at or after [position], wrapping to the first —
     * a find bar always finds *something* when matches exist. -1 when there are none.
     */
    fun nextFrom(matches: List<Match>, position: Int): Int {
        if (matches.isEmpty()) return -1
        val idx = matches.indexOfFirst { it.start >= position }
        return if (idx >= 0) idx else 0
    }

    /**
     * Index into [matches] of the previous match strictly before [position], wrapping to the
     * last. -1 when there are none.
     */
    fun previousFrom(matches: List<Match>, position: Int): Int {
        if (matches.isEmpty()) return -1
        val idx = matches.indexOfLast { it.start < position }
        return if (idx >= 0) idx else matches.lastIndex
    }

    data class ReplaceAllResult(val text: String, val count: Int, val caret: Int)

    /**
     * Replace every match of [query] with [replacement], carrying [caret] by the length change of
     * everything replaced before it (a caret inside a match lands at that replacement's end).
     * Matches are computed against the input, so a replacement containing the query cannot loop.
     */
    fun replaceAll(text: String, query: String, replacement: String, caret: Int): ReplaceAllResult {
        val ms = matches(text, query)
        if (ms.isEmpty()) return ReplaceAllResult(text, 0, caret)
        val sb = StringBuilder(text.length + ms.size * (replacement.length - query.length).coerceAtLeast(0))
        var last = 0
        var newCaret = caret
        val delta = replacement.length - query.length
        for (m in ms) {
            sb.append(text, last, m.start).append(replacement)
            last = m.end
            if (m.end <= caret) newCaret += delta
            else if (m.start < caret) newCaret = m.start + replacement.length
        }
        sb.append(text, last, text.length)
        return ReplaceAllResult(sb.toString(), ms.size, newCaret.coerceIn(0, sb.length))
    }

    /** (words, characters) — a word is a maximal run of non-whitespace. */
    fun counts(text: String): Pair<Int, Int> {
        var words = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
        }
        return words to text.length
    }
}
