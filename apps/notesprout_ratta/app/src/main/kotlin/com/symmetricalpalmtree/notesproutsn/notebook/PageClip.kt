package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * A page ⇄ clipboard payload, and nothing else (arc 7). Pure Kotlin — no Android, no DB — so the
 * risky half of copy/paste (which id becomes which, what re-parents onto what, what keeps its
 * `"order"`) is provable off-device, exactly like [PageMath] for the page list.
 *
 * The payload is deliberately **row-level, not object-level**: strokes, headings and links go in as
 * the rows they already are, so a page copies with everything on it — including anything a later
 * arc adds to the family table — without this file learning a single content type. The one row it
 * does understand is the page itself, because that is where the template reference lives.
 *
 * Two rules the whole thing rests on:
 *  - **Every pasted row gets a fresh id**, wired through one old→new map, so a link's wrapped
 *    children re-parent onto the *copied* link and not the original.
 *  - **`"order"` is preserved verbatim.** Writing order is load-bearing (recognition reads it as a
 *    sequence, the composite raster paints in it) — the M-arc / N3 lesson. Only the page row's own
 *    order is rewritten, to the slot it is being inserted at.
 */
object PageClip {

    /** How the destination should reach a template — decided by the caller, which is the only side
     *  that can see what the destination `.soil` already holds. */
    sealed interface Template {
        /** The page had no template (a blank page): the pasted page's `refId` is `""`. */
        data object None : Template

        /** A template row with this id already lives in the destination — point at it, insert
         *  nothing. Always the answer for a same-notebook paste, and the first dedupe rule. */
        data class Reuse(val id: String) : Template

        /** Bring the payload's template row in under [id]. */
        data class Insert(val id: String) : Template
    }

    /**
     * The rows to write and what they mean. [rows] is in insert order (template, page, then
     * content); [contentIds] is the page's new descendants — what an undo of the paste
     * soft-deletes and a redo restores. The template row is **not** in [contentIds]: a paste leaves
     * its template in place (harmless, and the next paste's dedupe reuses it).
     */
    data class Plan(
        val pageId: String,
        val rows: List<SoilObjectEntity>,
        val contentIds: List<String>,
    )

    /**
     * Snapshot [page] and everything on it into an envelope. [content] is the page's live
     * descendants — its strokes, headings and links **and** the links' wrapped children (two levels
     * since arc 6); [template] is its template row, or null for a blank page.
     *
     * The caller must have drained the writer first: a stroke commit still queued would land after
     * this read and be silently missing from the copy.
     */
    fun capture(
        page: SoilObjectEntity,
        template: SoilObjectEntity?,
        content: List<SoilObjectEntity>,
        sourceNotebookId: String,
        now: Long,
    ): ClipEnvelope = ClipEnvelope(
        version = ClipEnvelope.VERSION,
        kind = ClipEnvelope.KIND_PAGE,
        sourceNotebookId = sourceNotebookId,
        copiedAt = now,
        rows = (listOfNotNull(template) + page + content).map { it.toClipRow() },
    )

    /**
     * Turn [env] into the rows a paste into [notebookId] must write, with the pasted page taking
     * slot [pageOrder]. Null when the payload holds no page row at all — an unusable envelope, the
     * caller explains and writes nothing.
     *
     * [newId] is injected so the id remap is testable; production passes `UUID.randomUUID`.
     *
     * A content row whose parent did not travel is **dropped**, not re-parented onto the page: the
     * payload is untrusted input like any file, and a link's orphaned child re-appearing loose on
     * the page would be a silent corruption rather than a visible absence.
     */
    fun plan(
        env: ClipEnvelope,
        notebookId: String,
        pageOrder: Int,
        template: Template,
        now: Long,
        newId: () -> String,
    ): Plan? {
        val pageRow = env.rows.firstOrNull { it.type == SoilSchema.TYPE_PAGE } ?: return null
        val content = env.rows.filter { it.type != SoilSchema.TYPE_PAGE && it.type != SoilSchema.TYPE_TEMPLATE }

        val newPageId = newId()
        val idMap = HashMap<String, String>(content.size * 2 + 2)
        idMap[pageRow.id] = newPageId
        for (row in content) idMap[row.id] = newId()

        val rows = ArrayList<SoilObjectEntity>(content.size + 2)
        val templateRow = env.rows.firstOrNull { it.type == SoilSchema.TYPE_TEMPLATE }
        val refId = when (template) {
            Template.None -> ""
            is Template.Reuse -> template.id
            // A payload that names a template but doesn't carry the row degrades to blank rather
            // than leaving the page pointing at nothing.
            is Template.Insert -> if (templateRow == null) "" else {
                rows += templateRow.toRow(template.id, notebookId, templateRow.order, now)
                template.id
            }
        }
        rows += pageRow.toRow(newPageId, notebookId, pageOrder, now).copy(refId = refId)

        val contentIds = ArrayList<String>(content.size)
        for (row in content) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            rows += row.toRow(id, parentId, row.order, now)
            contentIds += id
        }
        return Plan(newPageId, rows, contentIds)
    }

    private fun SoilObjectEntity.toClipRow() = ClipRow(
        id = id, parentId = parentId, type = type, order = order,
        text = text, refId = refId, x = x, y = y, width = width, height = height,
        color = color, strokeWidth = strokeWidth, style = style, flags = flags,
        blob = ClipRow.encodeBlob(blob),
    )

    private fun ClipRow.toRow(id: String, parentId: String, order: Int, now: Long) = SoilObjectEntity(
        id = id, parentId = parentId, type = type, order = order,
        createdAt = now, updatedAt = now, deletedAt = null,
        text = text, refId = refId, x = x, y = y, width = width, height = height,
        color = color, strokeWidth = strokeWidth, style = style, flags = flags,
        blob = blobBytes(),
    )
}
