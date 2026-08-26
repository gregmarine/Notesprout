package com.symmetricalpalmtree.notesproutsn.data.template

/**
 * Search's rules — **pure Kotlin, no Android, JVM-tested** (arc 13 / G5).
 *
 * Two jobs, and they are separate on purpose because the two halves of a search shelf come from two
 * different places. The rows come from SQLite ([likePattern] builds the `LIKE` argument), and the
 * sentinels — Blank and the three built-in papers — are composed by the screen and have no row to
 * match against, so they are matched here ([matchesLabel]) with the same rule the database uses.
 *
 * "The same rule" is the whole point of this file. SQLite's `LIKE` is case-insensitive for ASCII
 * and substring-anywhere; a Kotlin-side `contains` that was case-*sensitive* would make "Grid"
 * findable and "grid" not, for the built-in only — precisely the kind of split a user reads as the
 * search being broken rather than as two code paths.
 */
object TemplateSearch {

    /**
     * The escape character the `LIKE` query declares. Backslash: it cannot appear in a UUID, and
     * the family's name charset (`library/NameRules`) has no opinion about it, so escaping it is a
     * formality rather than a hot path.
     */
    const val ESCAPE = '\\'

    /**
     * A query as a `LIKE` pattern: substring-anywhere, with the three characters SQLite reads as
     * syntax neutralised. Without this a user searching for `_` matches **every** name (`_` is
     * LIKE's any-single-character) and one searching for `%` matches everything — a silent wrong
     * answer rather than an error, which is the worse of the two failures.
     */
    fun likePattern(query: String): String {
        val sb = StringBuilder(query.length + 8)
        sb.append('%')
        for (c in query.trim()) {
            if (c == ESCAPE || c == '%' || c == '_') sb.append(ESCAPE)
            sb.append(c)
        }
        sb.append('%')
        return sb.toString()
    }

    /**
     * True when [label] matches [query] the way [likePattern] does: substring, ASCII-insensitive.
     * An empty query matches nothing — a shelf showing the whole library is not a search result.
     */
    fun matchesLabel(label: String, query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && label.contains(q, ignoreCase = true)
    }

    /**
     * A query the screen should not run at all. Blank or whitespace-only: the dialog refuses it
     * rather than opening a shelf holding every sentinel and every row in the library, which does
     * not look like an empty search — it looks like a result.
     */
    fun isRunnable(query: String): Boolean = query.trim().isNotEmpty()
}
