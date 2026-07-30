package com.notesprout.android.core.markdown

/**
 * The three text decisions behind a page's document, kept as pure functions so they are covered by
 * plain JVM tests (no Android, no Robolectric) and read the same everywhere they are asked.
 *
 * See docs/documents.md.
 */
object DocumentDraft {

    /** The rule an appended draft is joined on — the Markdown horizontal rule the renderer draws. */
    private const val JOIN = "\n\n---\n\n"

    /**
     * True when a document holds nothing the user would miss, which is exactly the condition for
     * seeding it from the page. Whitespace counts as nothing: a document emptied by hand is
     * undrafted again, so the next visit offers the page's text afresh.
     */
    fun isUndrafted(text: String?): Boolean = text.isNullOrBlank()

    /**
     * [existing] with [draft] added at the end, under a horizontal rule. The join is only added when
     * there is something to join to, so appending into an empty document reads like a plain draft
     * rather than one preceded by a stray rule.
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
     * for the editor's "page has changed" line.
     *
     * A null [srcUpdatedAt] means the text was authored by hand and never drafted from the page, so
     * there is no earlier state to have moved on from and nothing to report.
     */
    fun isStale(srcUpdatedAt: Long?, layerMaxUpdatedAt: Long): Boolean =
        srcUpdatedAt != null && layerMaxUpdatedAt > srcUpdatedAt
}
