package com.symmetricalpalmtree.notesprout.data.soil

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Row-level access to the `notebook` table. Higher-level logic lives in `notebook/` (session, stroke store). */
@Dao
interface SoilDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SoilObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SoilObjectEntity>)

    @Query("SELECT * FROM notebook WHERE id = :id")
    suspend fun byId(id: String): SoilObjectEntity?

    @Query("SELECT * FROM notebook WHERE type = :type AND parentId = :parentId AND deletedAt IS NULL ORDER BY `order`")
    suspend fun childrenOfType(parentId: String, type: String): List<SoilObjectEntity>

    @Query("SELECT * FROM notebook WHERE type = 'notebook' AND parentId = '' LIMIT 1")
    suspend fun notebookRow(): SoilObjectEntity?

    @Query("SELECT count(*) FROM notebook WHERE type = 'page' AND deletedAt IS NULL")
    suspend fun livePageCount(): Int

    @Query("UPDATE notebook SET deletedAt = :at, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDelete(ids: List<String>, at: Long)

    /** Un-soft-delete rows (undo of an erase / a page delete). No-op for ids that are already alive. */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun restore(ids: List<String>, at: Long)

    /** Live stroke ids of a page — cheap (no blobs). */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type = 'stroke' AND deletedAt IS NULL")
    suspend fun liveStrokeIds(pageId: String): List<String>

    /** Live content ids of a page — strokes **and** objects — for a page delete / undo (arc 4 / H1). */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type IN ('stroke', 'object') AND deletedAt IS NULL")
    suspend fun liveChildIds(pageId: String): List<String>

    /** Live object rows of a page in z-order (arc 4). */
    @Query("SELECT * FROM notebook WHERE parentId = :pageId AND type = 'object' AND deletedAt IS NULL ORDER BY `order`")
    suspend fun objectsOf(pageId: String): List<SoilObjectEntity>

    /** Every live object row of the notebook (arc 5 — the Contents gather; objects carry no blob). A page
     *  delete soft-deletes its children, so this is already the live set; the caller still guards `parentId`. */
    @Query("SELECT * FROM notebook WHERE type = 'object' AND deletedAt IS NULL")
    suspend fun liveObjectsAll(): List<SoilObjectEntity>

    /** Rewrite an object's payload + bounds (edit / re-render sizing). Live rows only. */
    @Query("UPDATE notebook SET text = :text, x = :x, y = :y, width = :w, height = :h, updatedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateObject(id: String, text: String, x: Float, y: Float, w: Float, h: Float, at: Long)

    /** Translate live objects by (dx, dy) — a selection move. */
    @Query("UPDATE notebook SET x = x + :dx, y = y + :dy, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun moveObjects(ids: List<String>, dx: Float, dy: Float, at: Long)

    @Query("UPDATE notebook SET refId = :refId, updatedAt = :at WHERE id = :id")
    suspend fun setRefId(id: String, refId: String?, at: Long)

    @Query("UPDATE notebook SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun setText(id: String, text: String?, at: Long)

    @Query("UPDATE notebook SET `order` = :order, updatedAt = :at WHERE id = :id")
    suspend fun setOrder(id: String, order: Int, at: Long)

    @Query("UPDATE notebook SET blob = :blob, updatedAt = :at WHERE id = :id")
    suspend fun setBlob(id: String, blob: ByteArray?, at: Long)

    @Query("SELECT * FROM notebook WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SoilObjectEntity>

    /** Highest live `"order"` among [parentId]'s children of [type], or -1 when there are none. */
    /** Over live **and** soft-deleted children, so `"order"` stays monotonic across erase → restore
     *  (a stroke un-deleted in place never ties with one committed after its erase — H5). */
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM notebook WHERE parentId = :parentId AND type = :type")
    suspend fun maxOrder(parentId: String, type: String): Int
}
