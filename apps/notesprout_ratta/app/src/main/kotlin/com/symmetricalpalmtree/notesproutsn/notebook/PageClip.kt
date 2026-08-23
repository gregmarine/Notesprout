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
 *
 * The one row it reads *meaning* out of is a link's payload, and only across notebooks (B2): an
 * own-notebook link means a different thing once the page is in another file — see [rewriteLink].
 */
object PageClip {

    /** How the destination should reach a template — decided by the caller, which is the only side
     *  that can see what the destination `.soil` already holds. */
    sealed interface Template {
        /** The page had no template (a blank page): the pasted page's `refId` is `""`. */
        data object None : Template

        /** A template row in the destination is this page's paper — point at it, insert nothing.
         *  Always the answer for a same-notebook paste; across notebooks it is reached either by
         *  id (a previous paste brought the row in) or by content ([matchTemplate]). */
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

        val crossNotebook = env.sourceNotebookId.isNotBlank() && env.sourceNotebookId != notebookId
        val contentIds = ArrayList<String>(content.size)
        for (row in content) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            var out = row.toRow(id, parentId, row.order, now)
            if (crossNotebook && row.type == SoilSchema.TYPE_LINK) {
                out = out.copy(text = rewriteLink(row.text, env.sourceNotebookId, pageRow.id, newPageId))
            }
            rows += out
            contentIds += id
        }
        return Plan(newPageId, rows, contentIds)
    }

    /**
     * What a link's payload must say once its page lives in **another** notebook (B2).
     *
     * [LinkPayload.KIND_PAGE] carries no notebook id — it means "a page of my own notebook", which
     * is a *different* page once the row has moved. So it is re-pointed at the notebook it was
     * copied from, explicitly: `KIND_PAGE` → [LinkPayload.KIND_NOTEBOOK_PAGE] with
     * [sourceNotebookId]. The link keeps working, and it keeps meaning what it meant.
     *
     * One exception: a link whose target **is the page being pasted** re-points at the new copy and
     * stays own-notebook, so a page that links to itself still does after the trip.
     *
     * `KIND_NOTEBOOK` and `KIND_NOTEBOOK_PAGE` already name their notebook and travel unchanged —
     * including one that names the source page explicitly: it was written to mean *that* page in
     * *that* notebook, and the original is still there.
     *
     * A payload that does not decode (foreign, future, corrupt) travels **verbatim**: rewriting
     * what we cannot read would be inventing a target, and a follow already lands in the
     * dead-target dialog. A same-notebook paste never reaches here at all — it is verbatim by
     * definition.
     */
    private fun rewriteLink(
        text: String?,
        sourceNotebookId: String,
        sourcePageId: String,
        newPageId: String,
    ): String? {
        val decoded = LinkPayload.decode(text ?: return null) ?: return text
        if (decoded.kind != LinkPayload.KIND_PAGE) return text
        val target = decoded.pageId ?: return text
        return runCatching {
            if (target == sourcePageId) {
                LinkPayload.encode(decoded.chrome, LinkPayload.KIND_PAGE, null, newPageId)
            } else {
                LinkPayload.encode(decoded.chrome, LinkPayload.KIND_NOTEBOOK_PAGE, sourceNotebookId, target)
            }
        }.getOrDefault(text)
    }

    /**
     * The id of a destination template row that is **the same paper** as the payload's [payload]
     * template, or null when the destination has none (B2's dedupe rule).
     *
     * "The same paper" is the kind label, the page size it was rendered for, and byte-identical
     * pixels — a WEBP the same renderer produced from the same inputs. Anything less strict would
     * silently re-paper a pasted page; anything stricter than identity is guesswork. The caller
     * shortlists candidates blob-free and loads only those, so the byte compare is over a handful
     * of rows at most.
     *
     * Without this, a page pasted into a notebook that already has an identical template inserts a
     * second copy of the same WEBP under a different id — every notebook pair stacking its own.
     */
    fun matchTemplate(payload: ClipRow?, candidates: List<SoilObjectEntity>): String? {
        if (payload == null) return null
        val bytes = payload.blobBytes() ?: return null
        return candidates.firstOrNull {
            it.text == payload.text &&
                it.width == payload.width &&
                it.height == payload.height &&
                it.blob != null && it.blob.contentEquals(bytes)
        }?.id
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
