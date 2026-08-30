package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * The three text judgements a page's document rests on, kept as pure functions so they read the
 * same wherever they are asked and are covered by plain JVM tests.
 */
object DocumentDraft {

    /** The separator an appended draft arrives under — Markdown's rule, which the renderer draws. */
    private const val JOIN = "\n\n---\n\n"

    /**
     * True when the document holds nothing anyone would miss — exactly the condition for seeding it
     * from the page's recognized text.
     *
     * Whitespace counts as nothing. A document emptied by hand is undrafted again, so the next
     * visit offers the page's text afresh rather than leaving the writer with a blank screen and no
     * way back to it.
     */
    fun isUndrafted(text: String?): Boolean = text.isNullOrBlank()

    /**
     * [existing] with [draft] added at the end, beneath a rule.
     *
     * The rule is only written when there is something on both sides of it. Appending into an empty
     * document reads as a plain first draft, not as one that someone put a stray divider above; and
     * appending nothing leaves the document exactly as it was.
     */
    fun append(existing: String, draft: String): String {
        val head = existing.trimEnd()
        val tail = draft.trim()
        if (tail.isEmpty()) return head
        if (head.isEmpty()) return tail
        return head + JOIN + tail
    }

    /**
     * True when the page has been written on since the document was drafted from it — the condition
     * behind the editor's "the page has changed" line.
     *
     * A null [srcUpdatedAt] means this text was authored by hand and never drafted from the page at
     * all. There is no earlier state for the page to have moved on from, so there is nothing to
     * report and the line stays away.
     */
    fun isStale(srcUpdatedAt: Long?, layerMaxUpdatedAt: Long): Boolean =
        srcUpdatedAt != null && layerMaxUpdatedAt > srcUpdatedAt
}
