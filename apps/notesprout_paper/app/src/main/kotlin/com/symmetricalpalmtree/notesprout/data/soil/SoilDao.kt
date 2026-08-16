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

    /** Live stroke ids of a page — cheap (no blobs), used when deleting a page. */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type = 'stroke' AND deletedAt IS NULL")
    suspend fun liveStrokeIds(pageId: String): List<String>

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
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM notebook WHERE parentId = :parentId AND type = :type AND deletedAt IS NULL")
    suspend fun maxOrder(parentId: String, type: String): Int
}
