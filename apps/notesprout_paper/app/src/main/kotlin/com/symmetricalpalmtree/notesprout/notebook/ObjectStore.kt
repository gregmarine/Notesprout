package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.soil.SoilDao
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema

/**
 * `object` rows of the open notebook (arc 4 / H1). Every write goes through the same serial IO
 * [SoilWriter] as [StrokeStore] — objects and strokes never race, and the `updatedAt` touch
 * discipline is shared. Reads are plain suspend calls. The payload is opaque here: stored, moved,
 * soft-deleted, restored — never read for meaning, never logged.
 */
class ObjectStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {
    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live objects of [pageId] in z-order. A malformed row is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<PageObject> =
        dao.objectsOf(pageId).mapNotNull { ObjectRows.toObject(it) }

    // ── Writes (Main → serial IO) ─────────────────────────────────────────────

    /** Insert a new object; `"order"` = `MAX("order")+1` among the page's live objects (like strokes). */
    fun create(pageId: String, obj: PageObject) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_OBJECT) + 1
        dao.upsert(ObjectRows.toRow(obj.copy(order = order), pageId, now))
        Slog.d(TAG) { "create ${obj.id} (${obj.providerIdentity}) order=$order" }
    }

    /** Rewrite payload + bounds (an edit, or the render pass sizing the object to its image). */
    fun updatePayloadAndBounds(id: String, payload: String, x: Float, y: Float, w: Float, h: Float) = writer.enqueue {
        dao.updateObject(id, ObjectRows.cap(payload), x, y, w, h, System.currentTimeMillis())
        Slog.d(TAG) { "update $id bounds=($x,$y,$w,$h)" }
    }

    /** Translate live objects by (dx, dy) — a selection move. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            dao.moveObjects(ids, dx, dy, System.currentTimeMillis())
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
    }

    /** Soft-delete objects (a delete, undo of a create). */
    fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "remove ${ids.size}" }
        }
    }

    /** Bring soft-deleted objects back as live rows at their stored geometry + z-order (undo of a
     *  delete, redo of a create). Upserts, so a row that was never written lands too. */
    fun restore(pageId: String, objects: List<PageObject>) {
        if (objects.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            for (o in objects) dao.upsert(ObjectRows.toRow(o, pageId, now))
            Slog.d(TAG) { "restore ${objects.size} to $pageId" }
        }
    }

    private companion object {
        const val TAG = "ObjectStore"
    }
}
