package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * A lasso selection ⇄ clipboard payload (arc 8) — [PageClip]'s sibling, on the same envelope and
 * the same single index row, discriminated by [ClipEnvelope.KIND_OBJECTS]. Pure Kotlin — no
 * Android, no DB — so the risky half of object copy/paste (which id becomes which, what re-parents
 * onto what, where the ink actually lands) is provable off-device.
 *
 * Three deliberate differences from [PageClip], each one because a page paste owns a whole
 * self-contained row set while an object paste lands **among** rows that are already there:
 *
 *  1. **`"order"` is rebased, not verbatim.** Pasted rows go after the destination page's current
 *     `MAX("order")` **for their own type** (the family numbers per parent+type — see
 *     `SoilDao.maxOrder`), keeping their relative sequence. Writing order is load-bearing (a later
 *     lasso-convert reads the strokes as a sequence — the M-arc / N3 lesson), so the *sequence*
 *     survives even though the numbers do not. A link's wrapped children keep their orders verbatim:
 *     their parent is a brand-new link row with nothing to collide with.
 *  2. **Geometry is object-level, not row-level.** A `stroke` row carries no `x/y/width/height` —
 *     its geometry is entirely inside the format-B blob — so translating one means
 *     decode → [Stroke.translated] → re-encode through [StrokeRows]. A `heading` / `link` row is
 *     the opposite: bounds *are* columns, and a link's wrapped children are page-absolute and
 *     translate with it ([PageLink.translated]'s rule).
 *  3. **Where it lands is decided at paste time**, by the caller's [ObjectPlacement] lambda — the
 *     payload's own box is not known until the stroke blobs are decoded, which is exactly the work
 *     [plan] is already doing.
 *
 * Two rules it shares with [PageClip], and rests on just as hard:
 *  - **Every pasted row gets a fresh id**, wired through one old→new map, so a link's wrapped
 *    children re-parent onto the *copied* link and not the original.
 *  - **A row whose parent did not travel is dropped**, never re-parented onto the page: the payload
 *    is untrusted input like any file, and a link's orphaned child re-appearing loose on the page
 *    would be a silent corruption rather than a visible absence. Telling the two apart takes one
 *    inference here that [PageClip] gets for free from the page row it carries — see [sourcePageOf].
 */
object ObjectClip {

    /**
     * The rows to write and what they become. [rows] is in insert order (top-level first, then each
     * link's children); the decoded halves are what the screen puts into its working copies and onto
     * the paper, already translated. [contentIds] — the new ids of the **top-level** rows plus the
     * links' children — is what an undo of the paste soft-deletes and a redo restores.
     */
    data class Plan(
        val rows: List<SoilObjectEntity>,
        val strokes: List<Stroke>,
        val headings: List<Heading>,
        val links: List<PageLink>,
        val contentIds: List<String>,
    ) {
        /** Union of everything pasted, for the selection the paste lands in. Null when empty. */
        val bounds: Bounds?
            get() {
                var b: Bounds? = null
                for (s in strokes) b = b?.union(s.bounds) ?: s.bounds
                for (h in headings) b = b?.union(h.bounds) ?: h.bounds
                for (l in links) b = b?.union(l.bounds) ?: l.bounds
                return b
            }

        val isEmpty: Boolean get() = strokes.isEmpty() && headings.isEmpty() && links.isEmpty()
    }

    /**
     * Snapshot a selection into an envelope. [top] is the selected rows themselves — strokes,
     * headings and links, exactly as they sit on the page — and [children] the live children of the
     * selected **links** (a link copies whole; nothing ever reaches inside one — the K1 model).
     *
     * The caller must have drained the writer first: a stroke commit still queued would land after
     * this read and be silently missing from the copy. Null when nothing usable was selected.
     */
    fun capture(
        top: List<SoilObjectEntity>,
        children: List<SoilObjectEntity>,
        sourceNotebookId: String,
        now: Long,
    ): ClipEnvelope? {
        if (top.isEmpty()) return null
        return ClipEnvelope(
            version = ClipEnvelope.VERSION,
            kind = ClipEnvelope.KIND_OBJECTS,
            sourceNotebookId = sourceNotebookId,
            copiedAt = now,
            rows = (top + children).map { it.toClipRow() },
        )
    }

    /**
     * Turn [env] into the rows a paste onto [pageId] must write, and the objects the screen must
     * then hold. Null when the payload carries nothing this build can place.
     *
     * [baseOrder] is the destination page's current `MAX("order")` for a row type (the caller reads
     * it per type); the first pasted row of that type takes `base + 1`. [place] receives the
     * payload's own bounding box — the ink extent, so half a stroke width is never left hanging off
     * the page (a g-paper `Stroke.bounds` is point-tight — the K2 trap) — and answers with the shift
     * every pasted object takes.
     *
     * [newId] is injected so the id remap is testable; production passes `UUID.randomUUID`.
     */
    fun plan(
        env: ClipEnvelope,
        pageId: String,
        baseOrder: (type: String) -> Int,
        now: Long,
        newId: () -> String,
        place: (Bounds) -> ObjectPlacement.Offset,
    ): Plan? {
        val rowIds = env.rows.mapTo(HashSet()) { it.id }
        val linkIds = env.rows.filter { it.type == SoilSchema.TYPE_LINK }.mapTo(HashSet()) { it.id }
        val placeable = env.rows.filter { it.type != SoilSchema.TYPE_PAGE && it.type != SoilSchema.TYPE_TEMPLATE }

        // Top level = a row parented to the **source page** — the one parent every selected object
        // shares and the one row that never travels. A link is top-level by definition: no-nesting
        // is the locked K1 rule, so a link inside a link is a payload this build refuses to
        // reproduce rather than one it flattens.
        val sourceParent = sourcePageOf(placeable, rowIds) ?: return null
        val top = placeable.filter { it.parentId == sourceParent }
        if (top.isEmpty()) return null
        val children = placeable.filter { it.parentId in linkIds && it.type != SoilSchema.TYPE_LINK }

        // Decode first: the box the caller places by is the ink's true extent, and the decode is
        // also the only way a stroke's geometry can be translated at all.
        val decoded = HashMap<String, Stroke>(top.size + children.size)
        for (row in top + children) {
            if (row.type != SoilSchema.TYPE_STROKE) continue
            StrokeRows.toStroke(row.toRow(row.id, row.parentId, row.order, now))?.let { decoded[row.id] = it }
        }
        val box = payloadBounds(top, decoded) ?: return null
        val offset = place(box)
        val dx = offset.dx
        val dy = offset.dy

        val idMap = HashMap<String, String>((top.size + children.size) * 2)
        for (row in top) idMap[row.id] = newId()
        for (row in children) idMap[row.id] = newId()

        // Rebased per type, in the payload's own sequence — the numbers change, the order does not.
        val nextOrder = HashMap<String, Int>()
        fun rebased(type: String): Int {
            val next = nextOrder[type] ?: (baseOrder(type) + 1)
            nextOrder[type] = next + 1
            return next
        }

        val rows = ArrayList<SoilObjectEntity>(top.size + children.size)
        val strokes = ArrayList<Stroke>()
        val headings = ArrayList<Heading>()
        val childStrokes = HashMap<String, MutableList<Stroke>>()
        val childHeadings = HashMap<String, MutableList<Heading>>()
        val contentIds = ArrayList<String>(top.size + children.size)
        val linkRows = ArrayList<SoilObjectEntity>()

        for (row in top.sortedBy { it.order }) {
            val id = idMap.getValue(row.id)
            val out = translated(row, id, pageId, rebased(row.type), now, dx, dy, decoded[row.id]) ?: continue
            rows += out
            contentIds += id
            when (out.type) {
                SoilSchema.TYPE_STROKE -> StrokeRows.toStroke(out)?.let { strokes += it }
                SoilSchema.TYPE_HEADING -> HeadingRows.toHeading(out)?.let { headings += it }
                SoilSchema.TYPE_LINK -> linkRows += out
            }
        }
        // Children keep their own `"order"`: their parent is a row that did not exist a moment ago.
        for (row in children.sortedBy { it.order }) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            val out = translated(row, id, parentId, row.order, now, dx, dy, decoded[row.id]) ?: continue
            rows += out
            contentIds += id
            when (out.type) {
                SoilSchema.TYPE_STROKE ->
                    StrokeRows.toStroke(out)?.let { childStrokes.getOrPut(parentId) { ArrayList() } += it }
                SoilSchema.TYPE_HEADING ->
                    HeadingRows.toHeading(out)?.let { childHeadings.getOrPut(parentId) { ArrayList() } += it }
            }
        }
        val links = linkRows.mapNotNull { row ->
            LinkRows.toLink(row, childStrokes[row.id].orEmpty(), childHeadings[row.id].orEmpty())
        }
        if (strokes.isEmpty() && headings.isEmpty() && links.isEmpty()) return null
        return Plan(rows, strokes, headings, links, contentIds)
    }

    /**
     * The id of the page the selection was copied from, inferred rather than carried — arc 7's
     * `kind` discriminator promised objects would need **no format change**, and this is what pays
     * for that promise.
     *
     * The inference is sound because one selection lives on one page: every top-level row shares a
     * single parent that is not itself in the payload, so the **most common** such parent is the
     * source page (first appearance breaks a tie, so the answer never depends on row order beyond
     * what capture already fixes). What it buys is the untrusted-payload rule: a row whose parent is
     * some *other* absent id is a link's orphan, and it is dropped rather than re-parented onto the
     * page, where it would re-appear loose as a silent corruption.
     *
     * Null when no row is parented outside the payload at all — nothing could be top-level, so
     * there is nothing to paste.
     */
    private fun sourcePageOf(rows: List<ClipRow>, rowIds: Set<String>): String? {
        var best: String? = null
        var bestCount = 0
        val counts = LinkedHashMap<String, Int>()
        for (row in rows) {
            if (row.parentId in rowIds) continue
            val n = (counts[row.parentId] ?: 0) + 1
            counts[row.parentId] = n
            if (n > bestCount) { best = row.parentId; bestCount = n }
        }
        return best
    }

    /**
     * The payload's extent in **source** coordinates, over the top-level rows only — a link's
     * children live inside its bounds, so counting them would change nothing and counting a
     * foreign link's mis-sized children would change it wrongly.
     *
     * A stroke contributes its *ink* extent (`bounds` grown by half the stroke width), not its
     * point-tight bounds: the standing K2 trap, applied to placement so a clamped paste never
     * shears half a nib off the page edge. A row that decodes to nothing contributes nothing.
     */
    private fun payloadBounds(top: List<ClipRow>, decoded: Map<String, Stroke>): Bounds? {
        var b: Bounds? = null
        for (row in top) {
            val r = when (row.type) {
                SoilSchema.TYPE_STROKE -> decoded[row.id]?.let { it.bounds.inflated(it.width / 2f) }
                else -> {
                    val x = row.x; val y = row.y; val w = row.width; val h = row.height
                    if (x == null || y == null || w == null || h == null) null
                    else if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite())) null
                    else Bounds(x, y, x + w, y + h)
                }
            } ?: continue
            b = b?.union(r) ?: r
        }
        return b
    }

    /**
     * One pasted row: fresh id, new parent, rebased order, geometry shifted by ([dx], [dy]).
     *
     * A `stroke` row's geometry is inside the blob, so it is re-encoded from its decoded [stroke]
     * (dropped when the blob was unusable — one stroke lost, never the whole paste). Everything
     * else moves by its `x`/`y` columns; a row that has none simply travels un-shifted rather than
     * being invented a position.
     */
    private fun translated(
        row: ClipRow,
        id: String,
        parentId: String,
        order: Int,
        now: Long,
        dx: Float,
        dy: Float,
        stroke: Stroke?,
    ): SoilObjectEntity? {
        if (row.type == SoilSchema.TYPE_STROKE) {
            val s = stroke ?: return null
            return StrokeRows.toRow(s.translated(dx, dy).copy(id = id), parentId, order, now)
        }
        val out = row.toRow(id, parentId, order, now)
        val x = out.x
        val y = out.y
        return out.copy(
            x = if (x != null && x.isFinite()) x + dx else x,
            y = if (y != null && y.isFinite()) y + dy else y,
        )
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
