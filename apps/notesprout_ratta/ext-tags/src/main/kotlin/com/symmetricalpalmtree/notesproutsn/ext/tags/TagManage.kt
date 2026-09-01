package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.TagShowing

/**
 * MANAGE's overview as data (arc 21 / W2) — pure, so the two rules worth pinning are pinned:
 * **what the list holds and in what order**, and **what a row says about a target with no tags**.
 *
 * The order is the host's: the notebook, then its pages exactly as they were handed over. That is
 * page order, and re-sorting it here would be the extension inventing a page order out of labels it
 * was given precisely because it cannot work one out.
 */
object TagManage {

    /**
     * One row of the overview: a thing tags hang on, in the words the host gave for it.
     *
     * The notebook is on **every** row (arc 21 / W4) — the overview is one notebook's, and a page
     * row that named only its page would be the same half-fact that made tagged pages unfindable
     * from the library. [pageId] null is the notebook's own row.
     */
    class Row(val notebookId: String, val pageId: String?, val label: String) {
        val kind: Int
            get() = if (pageId == null) TagShowing.TARGET_NOTEBOOK else TagShowing.TARGET_PAGE
    }

    /**
     * The notebook first, then one row per page. A showing whose arrays disagree in length is not
     * possible — `TagShowing` refuses to unmarshal one — but the shorter of the two governs here
     * rather than throwing: an overview missing a row is still an overview.
     */
    fun targets(
        notebookId: String,
        notebookLabel: String,
        pageIds: List<String>,
        pageLabels: List<String>,
    ): List<Row> {
        val rows = ArrayList<Row>(pageIds.size + 1)
        rows += Row(notebookId, null, notebookLabel)
        for (i in 0 until minOf(pageIds.size, pageLabels.size)) {
            rows += Row(notebookId, pageIds[i], pageLabels[i])
        }
        return rows
    }

    /**
     * A row's second line: the tags it carries, in the order they were handed in (the index's
     * sorted order), or [none] when it carries nothing.
     *
     * "No tags" rather than a dash: words read better than glyphs on e-ink, and this line is the
     * answer the row exists to give.
     */
    fun summary(tags: List<String>, none: String, separator: String): String =
        if (tags.isEmpty()) none else tags.joinToString(separator)
}
