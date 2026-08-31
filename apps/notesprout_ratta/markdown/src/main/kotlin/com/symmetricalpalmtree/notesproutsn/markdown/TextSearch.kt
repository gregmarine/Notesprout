package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * Find, replace, and counting for the document editor's find bar and its word count.
 *
 * Plain functions over plain strings, like everything else in this module: no view, no state, no
 * android — so the offset arithmetic is JVM-tested and behaves the same wherever it is called.
 *
 * Matching is case-insensitive and non-overlapping — each match begins after the previous one
 * ends. Overlapping hits would make "next" walk one character at a time through a run of repeats,
 * which is not what a find bar is for.
 */
object TextSearch {

    /** A hit at `[start, end)` in the text it was found in. */
    data class Match(val start: Int, val end: Int)

    /** Every hit of [query] in [text], in order. An empty query matches nothing, not everything. */
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
     * Index into [matches] of the first hit starting at or after [position], wrapping round to the
     * first when there is none past it — a find bar that has hits always lands on one. -1 when the
     * list is empty.
     */
    fun nextFrom(matches: List<Match>, position: Int): Int {
        if (matches.isEmpty()) return -1
        val idx = matches.indexOfFirst { it.start >= position }
        return if (idx >= 0) idx else 0
    }

    /**
     * Index into [matches] of the last hit starting strictly before [position], wrapping round to
     * the last. -1 when the list is empty.
     */
    fun previousFrom(matches: List<Match>, position: Int): Int {
        if (matches.isEmpty()) return -1
        val idx = matches.indexOfLast { it.start < position }
        return if (idx >= 0) idx else matches.lastIndex
    }

    /** The rewritten [text], how many hits were replaced, and where the caret ended up. */
    data class ReplaceAllResult(val text: String, val count: Int, val caret: Int)

    /**
     * Replace every hit of [query] with [replacement], carrying [caret] along by the length change
     * of everything replaced ahead of it. A caret standing inside a hit lands at the end of that
     * hit's replacement — there is no "middle" of the new text to aim for.
     *
     * The hits are worked out against the input before anything is written, so a replacement that
     * contains the query (`a` → `aa`) can never be re-matched and cannot loop.
     */
    fun replaceAll(text: String, query: String, replacement: String, caret: Int): ReplaceAllResult {
        val ms = matches(text, query)
        if (ms.isEmpty()) return ReplaceAllResult(text, 0, caret)
        val sb = StringBuilder(text.length + ms.size * (replacement.length - query.length).coerceAtLeast(0))
        var last = 0
        val step = replacement.length - query.length
        // Every hit that ends before the caret shifts it; the running total is what turns the
        // containing hit's old start into a new-text offset. Without it, a caret inside anything
        // but the first hit lands wherever the *unshifted* text used to be.
        var carried = 0
        var inside = -1
        for (m in ms) {
            sb.append(text, last, m.start).append(replacement)
            last = m.end
            if (m.end <= caret) carried += step
            else if (m.start < caret && inside < 0) inside = m.start + carried + replacement.length
        }
        sb.append(text, last, text.length)
        val newCaret = if (inside >= 0) inside else caret + carried
        return ReplaceAllResult(sb.toString(), ms.size, newCaret.coerceIn(0, sb.length))
    }

    /**
     * (words, characters). A word is a maximal run of non-whitespace, so the Markdown source is
     * measured as it stands — `**bold**` is one word, punctuation and markers and all.
     */
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
