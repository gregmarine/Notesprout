package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * `link` rows of the open notebook (arc 6 / K1) and the re-parenting that wraps a selection in
 * one. Every write goes through the session's single serial [SoilWriter] — shared with
 * [StrokeStore] / [HeadingStore], so a wrap can never interleave with a stroke commit — and the
 * multi-row ops additionally run inside one Room transaction ([transact], injected so the store
 * stays JVM-testable), so a link row and its children's `parentId` are never separately visible.
 *
 * A wrap keeps ids and page-absolute coordinates — only `parentId` flips page → link, and back on
 * an unlink. Soft deletes everywhere; restores are in place, like every store in the family.
 */
class LinkStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
    /** One Room transaction around [block] — `db.withTransaction` in production, direct call in tests. */
    private val transact: suspend (block: suspend () -> Unit) -> Unit,
) {

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live links of [pageId] in z-order, each with its wrapped children (strokes in writing
     *  order, headings in z-order). A malformed row is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<PageLink> =
        dao.linksOf(pageId).mapNotNull { row ->
            val strokes = dao.childrenOfType(row.id, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }
            val headings = dao.childrenOfType(row.id, SoilSchema.TYPE_HEADING).mapNotNull { HeadingRows.toHeading(it) }
            LinkRows.toLink(row, strokes, headings)
        }

    /** Live descendant ids of [pageId] — its own content **and** the links' children
     *  (page delete / undo carries wrapped selections with their page). */
    suspend fun deepChildIds(pageId: String): List<String> = dao.liveDescendantIds(pageId)

    // ── Writes (Main → serial IO) ────────────────────────────────────────────

    /** Wrap a selection: insert the link row at `MAX("order")+1` among the page's links and
     *  re-parent its children page → link, in one transaction. */
    fun create(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            val order = dao.maxOrder(pageId, SoilSchema.TYPE_LINK) + 1
            dao.upsert(LinkRows.toRow(link.copy(order = order), pageId, now))
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, link.id, now) }
        }
        Slog.d(TAG) { "create ${link.id} wrapping ${link.childIds.size}" }
    }

    /** Unwrap: the children become page children again and the link row is soft-deleted. */
    fun unlink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, pageId, now) }
            dao.softDelete(listOf(link.id), now)
        }
        Slog.d(TAG) { "unlink ${link.id} releasing ${link.childIds.size}" }
    }

    /** Redo of [create] / undo of [unlink] — revives the row at its stored geometry + z-order and
     *  re-parents the same children back under it. */
    fun relink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            dao.upsert(LinkRows.toRow(link, pageId, now))
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, link.id, now) }
        }
        Slog.d(TAG) { "relink ${link.id} wrapping ${link.childIds.size}" }
    }

    /** Soft-delete links **and everything they wrap** (a selection delete / an eraser hit). */
    fun remove(links: List<PageLink>) {
        if (links.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            val ids = links.map { it.id } + links.flatMap { it.childIds }
            transact {
                ids.chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
            }
            Slog.d(TAG) { "remove ${links.size} (${ids.size} rows)" }
        }
    }

    /** Undo of [remove]. The children rows still carry `parentId` = link id, so restoring by id
     *  is enough; the link row is upserted so one that was never written lands too. */
    fun restore(pageId: String, links: List<PageLink>) {
        if (links.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            transact {
                for (l in links) dao.upsert(LinkRows.toRow(l, pageId, now))
                links.flatMap { it.childIds }.chunked(ID_CHUNK).forEach { dao.restore(it, now) }
            }
            Slog.d(TAG) { "restore ${links.size} to $pageId" }
        }
    }

    /**
     * Translate links by (dx, dy) — row and wrapped children alike, from ids only (an undo replay
     * has no [PageLink]). Heading children shift via their stored top-left ([SoilDao.moveBy]);
     * stroke geometry lives in the blob, so stroke children re-encode like [StrokeStore.move].
     */
    fun move(linkIds: List<String>, dx: Float, dy: Float) {
        if (linkIds.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            for (linkId in linkIds) {
                dao.moveBy(listOf(linkId), dx, dy, now)
                for (row in dao.childrenOfType(linkId, SoilSchema.TYPE_STROKE)) {
                    if (row.deletedAt != null) continue
                    val stroke = StrokeRows.toStroke(row) ?: continue
                    val moved = StrokeRows.toRow(stroke.translated(dx, dy), row.parentId, row.order, now)
                    dao.upsert(moved.copy(createdAt = row.createdAt))
                }
                val headingIds = dao.childrenOfType(linkId, SoilSchema.TYPE_HEADING).map { it.id }
                if (headingIds.isNotEmpty()) dao.moveBy(headingIds, dx, dy, now)
            }
            Slog.d(TAG) { "move ${linkIds.size} by ($dx,$dy)" }
        }
    }

    /** Rewrite a link's payload (the K2 Edit path — contract-only in K1). Bounds are unchanged —
     *  the wrapped ink is. */
    fun updatePayload(id: String, payload: String) = writer.enqueue {
        dao.setText(id, LinkRows.cap(payload), System.currentTimeMillis())
        Slog.d(TAG) { "updatePayload $id" }
    }

    private companion object {
        const val TAG = "LinkStore"

        /** SQLite caps bound variables at 999 — a big wrap's id list is chunked *inside* the
         *  transaction (chunking loses no atomicity). */
        const val ID_CHUNK = 500
    }
}
