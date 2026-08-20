package com.symmetricalpalmtree.notesprout.notebook

import androidx.room.withTransaction
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema

/**
 * `link` rows of the open notebook (arc 7 / L1) and the re-parenting that wraps a selection in one.
 * Every write goes through the same serial IO [SoilWriter] as [StrokeStore] / [ObjectStore], so a
 * wrap can never interleave with a stroke commit; the multi-statement ops ([create], [unlink],
 * [relink], [restore]) additionally run inside one Room transaction, so a link row and its children's
 * `parentId` are never separately visible. Reads are plain suspend calls.
 *
 * A wrap keeps ids and page-absolute coordinates — only `parentId` flips page → link, and back on an
 * unlink. The payload is opaque here: stored, capped, moved, soft-deleted, restored — never read for
 * meaning, never logged (counts only).
 */
class LinkStore(
    private val db: SoilDatabase,
    private val writer: SoilWriter,
) {
    private val dao = db.dao()

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live links of [pageId] in z-order, each with its wrapped children. A malformed row is dropped. */
    suspend fun loadPage(pageId: String): List<PageLink> =
        dao.linksOf(pageId).mapNotNull { row ->
            val strokes = dao.childrenOfType(row.id, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }
            val objects = dao.childrenOfType(row.id, SoilSchema.TYPE_OBJECT).mapNotNull { ObjectRows.toObject(it) }
            LinkRows.toLink(row, strokes, objects)
        }

    /** Live descendant ids of [pageId] — its own content **and** the links' children (page delete / undo). */
    suspend fun deepChildIds(pageId: String): List<String> = dao.liveDescendantIds(pageId)

    /** (link id, capped payload) of the page's live links in z-order — the `chromeOf` batch input. */
    suspend fun payloadsOf(pageId: String): List<Pair<String, String>> =
        dao.linksOf(pageId).map { it.id to LinkRows.cap(it.text ?: "") }

    // ── Writes (Main → serial IO) ─────────────────────────────────────────────

    /** Wrap a selection: insert the link row at `MAX("order")+1` among the page's links and re-parent
     *  its children page → link, in one transaction. */
    fun create(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val order = dao.maxOrder(pageId, SoilSchema.TYPE_LINK) + 1
            dao.upsert(LinkRows.toRow(link.copy(order = order), pageId, now))
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, link.id, now) }
        }
        Slog.d(TAG) { "create ${link.id} (${link.providerIdentity}) wrapping ${link.childIds.size}" }
    }

    /** Unwrap: the children become page children again and the link row is soft-deleted. */
    fun unlink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        db.withTransaction {
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, pageId, now) }
            dao.softDelete(listOf(link.id), now)
        }
        Slog.d(TAG) { "unlink ${link.id} releasing ${link.childIds.size}" }
    }

    /** Redo of [create] / undo of [unlink] — revives the row at its stored geometry + z-order and
     *  re-parents the same children back under it. */
    fun relink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        db.withTransaction {
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
            db.withTransaction {
                ids.chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
            }
            Slog.d(TAG) { "remove ${links.size} (${ids.size} rows)" }
        }
    }

    /** Undo of [remove]. The children rows still carry `parentId` = link id, so restoring by id is
     *  enough; the link row is upserted so one that was never written lands too. */
    fun restore(pageId: String, links: List<PageLink>) {
        if (links.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            db.withTransaction {
                for (l in links) dao.upsert(LinkRows.toRow(l, pageId, now))
                links.flatMap { it.childIds }.chunked(ID_CHUNK).forEach { dao.restore(it, now) }
            }
            Slog.d(TAG) { "restore ${links.size} to $pageId" }
        }
    }

    /** Translate links by (dx, dy) — row and wrapped children alike, from ids only (an undo replay has
     *  no [PageLink]). Stroke geometry is re-encoded, like [StrokeStore.move]. */
    fun move(linkIds: List<String>, dx: Float, dy: Float) {
        if (linkIds.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            for (linkId in linkIds) {
                dao.moveObjects(listOf(linkId), dx, dy, now)
                for (row in dao.childrenOfType(linkId, SoilSchema.TYPE_STROKE)) {
                    if (row.deletedAt != null) continue
                    val stroke = StrokeRows.toStroke(row) ?: continue
                    val moved = StrokeRows.toRow(stroke.translated(dx, dy), row.parentId, row.order, now)
                    dao.upsert(moved.copy(createdAt = row.createdAt))
                }
                val objectIds = dao.childrenOfType(linkId, SoilSchema.TYPE_OBJECT).map { it.id }
                if (objectIds.isNotEmpty()) dao.moveObjects(objectIds, dx, dy, now)
            }
            Slog.d(TAG) { "move ${linkIds.size} by ($dx,$dy)" }
        }
    }

    /** Rewrite a link's payload (the L2 Edit path). Bounds are unchanged — the wrapped ink is. */
    fun updatePayload(id: String, payload: String) = writer.enqueue {
        dao.setText(id, LinkRows.cap(payload), System.currentTimeMillis())
        Slog.d(TAG) { "updatePayload $id" }
    }

    private companion object {
        const val TAG = "LinkStore"
        /** SQLite caps bound variables at 999 — a big wrap's id list is chunked *inside* the
         *  transaction (chunking loses no atomicity; `StrokeStore.restore` chunks the same way). */
        const val ID_CHUNK = 500
    }
}
