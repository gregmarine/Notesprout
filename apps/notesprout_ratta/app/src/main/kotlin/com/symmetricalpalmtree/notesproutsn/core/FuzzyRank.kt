package com.symmetricalpalmtree.notesproutsn.core

/**
 * The family's one **fuzzy name match** — pure Kotlin, no Android, JVM-tested (arc 20 / Q1).
 *
 * Two screens ask the same question of a name ("could the user have meant this?") and they must
 * never answer it differently: the library's search shelf, and the template browser's (arc 13,
 * which searched with SQL `LIKE` until this file replaced it). A `LIKE` cannot be fuzzy, so
 * matching moved out of the database entirely and into here — every candidate's name is read
 * blob-free and ranked in memory, which for a library of names is nothing.
 *
 * **Fuzzy means subsequence, not typo tolerance** (the user's explicit arc-20 call): the query's
 * letters must appear in the name **in order**, gaps allowed. So `mtg` finds "Meeting Notes" and
 * `blg` finds "Blog 20251008" — while `bolg` finds nothing, because a swapped letter is not a
 * subsequence. Edit distance was offered and declined; adding it needs a fresh decision, not an
 * afternoon's judgement. (Where that line actually falls is worth knowing: a **dropped** letter
 * still finds its name, since "these letters, in order, some missing" is what a subsequence is.
 * A **wrong or swapped** one does not.)
 *
 * Ranking is a [Match]: the **tier** first (how literally the name contains the query), then how
 * many of the matched letters landed on a **word start**, then how **tight** the run is. Ties
 * below that are broken by the caller-visible rules in [rank] — shorter name, then the name
 * itself — so the same library always produces the same page of results.
 */
object FuzzyRank {

    /** The name *is* the query (ignoring case and surrounding space). */
    const val TIER_EXACT = 4
    /** The name begins with the query. */
    const val TIER_PREFIX = 3
    /** The query appears whole, starting at a word boundary ("notes" in "Meeting Notes"). */
    const val TIER_WORD_START = 2
    /** The query appears whole, mid-word ("eeting" in "Meeting"). */
    const val TIER_SUBSTRING = 1
    /** The query's letters appear in order but not together ("mtg" in "Meeting"). */
    const val TIER_SUBSEQUENCE = 0

    /**
     * How well one name answered one query. Ordered **best first**, so a plain `sorted()` puts the
     * strongest match at the top.
     *
     * @param tier one of the `TIER_` constants above — the coarse shape of the match.
     * @param wordStarts how many of the matched characters sit at the start of a word. This is what
     *   separates "Meeting Notes" from "Amount Given" for `mtg`: both match, one meant it.
     * @param span how far apart the first and last matched characters are. Tighter is better, so a
     *   query answered by one word beats the same query answered by scraps of three.
     */
    data class Match(val tier: Int, val wordStarts: Int, val span: Int) : Comparable<Match> {
        override fun compareTo(other: Match): Int {
            if (tier != other.tier) return other.tier - tier
            if (wordStarts != other.wordStarts) return other.wordStarts - wordStarts
            return span - other.span
        }
    }

    /**
     * A query worth running at all. Blank or whitespace-only is not a search: a shelf that answered
     * it with the whole library would not read as "you searched for nothing", it would read as a
     * result. Callers show their own "type to search" state instead.
     */
    fun isRunnable(query: String): Boolean = query.trim().isNotEmpty()

    /** True when [name] answers [query] at all — [match] without the ranking. */
    fun matches(name: String, query: String): Boolean = match(name, query) != null

    /**
     * How [name] answers [query], or null when it does not.
     *
     * Case is folded **character by character** rather than with `String.lowercase()`: a couple of
     * characters lowercase to two (Turkish dotted capital I is the usual one), which would shift
     * every index after them and make the positions this returns describe a different string than
     * the caller handed in.
     */
    fun match(name: String, query: String): Match? {
        val q = fold(query.trim())
        if (q.isEmpty()) return null
        val n = fold(name)
        if (q.length > n.length) return null
        if (n == q) return Match(TIER_EXACT, wordStartsIn(name, 0, q.length), q.length)

        val first = n.indexOf(q)
        if (first == 0) return Match(TIER_PREFIX, wordStartsIn(name, 0, q.length), q.length)
        if (first > 0) {
            // A whole-query hit that begins a word beats one buried mid-word, wherever each sits:
            // "notes" is a better answer from "Meeting Notes" than from "Denotes".
            var i = first
            while (i >= 0) {
                if (isWordStart(name, i)) return Match(TIER_WORD_START, wordStartsIn(name, i, q.length), q.length)
                i = n.indexOf(q, i + 1)
            }
            return Match(TIER_SUBSTRING, wordStartsIn(name, first, q.length), q.length)
        }

        // Subsequence, in two passes — and the second pass is the difference between a ranking and
        // a coin toss. A plain left-to-right greedy takes the *earliest* home for every character,
        // which scores "Meeting Team Group" for `mtg` no better than "Amount Given": both land one
        // letter on a word start by luck. So:
        //
        //  1. walk backwards for each character's **latest** possible position. That both decides
        //     whether a subsequence exists at all and gives every forward step a hard ceiling.
        //  2. walk forwards taking, for each character, a **word start** if one is available before
        //     that ceiling, and the earliest position otherwise. Bounded by the ceiling, this can
        //     never paint the remaining characters into a corner.
        val latest = IntArray(q.length)
        var back = n.length - 1
        for (k in q.indices.reversed()) {
            val at = n.lastIndexOf(q[k], back)
            if (at < 0) return null
            latest[k] = at
            back = at - 1
        }

        var cursor = 0
        var firstHit = -1
        var lastHit = -1
        var starts = 0
        for (k in q.indices) {
            val ceiling = latest[k]
            var chosen = -1
            var at = n.indexOf(q[k], cursor)
            while (at in 0..ceiling) {
                if (chosen < 0) chosen = at
                if (isWordStart(name, at)) { chosen = at; break }
                at = n.indexOf(q[k], at + 1)
            }
            // Unreachable: `ceiling` is itself a position of this character at or after `cursor`.
            if (chosen < 0) return null
            if (firstHit < 0) firstHit = chosen
            lastHit = chosen
            if (isWordStart(name, chosen)) starts++
            cursor = chosen + 1
        }
        return Match(TIER_SUBSEQUENCE, starts, lastHit - firstHit + 1)
    }

    /**
     * [items] that match [query], **best first**. Ties fall back to the shorter name and then to the
     * name itself (case-insensitively), so the order is total and stable: two runs of the same
     * search over the same library produce the same page, which is the difference between a result
     * and a shuffle. A query that is not [isRunnable] returns nothing.
     */
    fun <T> rank(items: List<T>, query: String, name: (T) -> String): List<T> {
        if (!isRunnable(query)) return emptyList()
        val hits = ArrayList<Triple<T, Match, String>>(items.size)
        for (item in items) {
            val label = name(item)
            val m = match(label, query) ?: continue
            hits.add(Triple(item, m, label))
        }
        hits.sortWith(
            compareBy({ it.second }, { it.third.length }, { fold(it.third) })
        )
        return hits.map { it.first }
    }

    /**
     * Where a word begins: the start of the name, anything after a separator, a capital after a
     * lower-case letter or a digit (`BlogPost`), and the first digit after a letter (`Blog2025`).
     * The separators are the family's own name charset (`library/NameRules`) plus `/`, which
     * imported names can carry.
     */
    private fun isWordStart(name: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = name[index - 1]
        if (previous == ' ' || previous == '_' || previous == '-' || previous == '.' || previous == '/') return true
        val current = name[index]
        if (current.isUpperCase() && (previous.isLowerCase() || previous.isDigit())) return true
        return current.isDigit() && previous.isLetter()
    }

    private fun wordStartsIn(name: String, from: Int, length: Int): Int {
        var count = 0
        for (i in from until from + length) if (isWordStart(name, i)) count++
        return count
    }

    /** Case-folded character by character, so index `i` of the result is index `i` of the input. */
    private fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = s[i].lowercaseChar()
        return String(out)
    }
}
