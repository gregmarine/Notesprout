package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ObjectDao {

    // Two backing queries handle the IS NULL case correctly — Room cannot express
    // "WHERE parentId IS NULL" via a nullable parameter in a single @Query.
    @Query("SELECT * FROM objects WHERE parentId IS NULL AND deletedAt IS NULL")
    suspend fun getChildrenOfRoot(): List<ObjectEntity>

    @Query("SELECT * FROM objects WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun getChildrenOfParent(parentId: String): List<ObjectEntity>

    suspend fun getChildren(parentId: String?, type: String?): List<ObjectEntity> {
        val all = if (parentId == null) getChildrenOfRoot() else getChildrenOfParent(parentId)
        return if (type == null) all else all.filter { it.type == type }
    }

    @Query("SELECT * FROM objects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ObjectEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(obj: ObjectEntity)

    @Update
    suspend fun update(obj: ObjectEntity)

    @Query("UPDATE objects SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("SELECT * FROM objects WHERE deletedAt IS NULL")
    suspend fun getAllNotDeleted(): List<ObjectEntity>

    // ── Transitional PNG→WEBP compaction (see NotebookCompactor.compactIndex) ──

    /**
     * Cheap header projection for image-bearing index rows (template image / notebook cover
     * snapshot): id, type, and the first 80 base64 chars of `data`. [NotebookCompactor.compactIndex]
     * reads the head to decide whether a WEBP re-encode is needed, pulling [imageDataForId] only for
     * those. Self-limiting, like the `.soil` scan.
     */
    @Query("SELECT id, type, substr(data, 1, 4000) AS head FROM objects WHERE type IN ('template', 'notebook')")
    suspend fun imageRowHeads(): List<IndexImageHead>

    /** Full `data` for a single index row — pulled only for rows chosen for re-encode. */
    @Query("SELECT data FROM objects WHERE id = :id")
    suspend fun imageDataForId(id: String): String?

    /** Overwrite a row's [data] WITHOUT touching `updatedAt` (avoids needlessly re-flagging for backup). */
    @Query("UPDATE objects SET data = :data WHERE id = :id")
    suspend fun rewriteObjectData(id: String, data: String)

    // ── List membership as child rows (Phase B) ──────────────────────────────
    // A `list_item` row is one membership edge: parentId = list id, refId = member id,
    // sortOrder = position. Membership churn hard-deletes (no tombstones — not precious history).

    @Query("SELECT * FROM objects WHERE parentId = :listId AND type = 'list_item' AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun getListItems(listId: String): List<ObjectEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM objects WHERE parentId = :listId AND refId = :memberId AND type = 'list_item' AND deletedAt IS NULL)")
    suspend fun listContains(listId: String, memberId: String): Boolean

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM objects WHERE parentId = :listId AND type = 'list_item' AND deletedAt IS NULL")
    suspend fun maxListSortOrder(listId: String): Int

    @Query("DELETE FROM objects WHERE parentId = :listId AND refId = :memberId AND type = 'list_item'")
    suspend fun deleteListItem(listId: String, memberId: String)

    /** Scrub a member from every list (e.g. a deleted notebook). */
    @Query("DELETE FROM objects WHERE refId = :memberId AND type = 'list_item'")
    suspend fun deleteListItemsForMember(memberId: String)

    /** Clear a list's child rows (used when re-migrating a legacy inline list). */
    @Query("DELETE FROM objects WHERE parentId = :listId AND type = 'list_item'")
    suspend fun deleteAllListItems(listId: String)

    @Query("UPDATE objects SET sortOrder = :sortOrder WHERE parentId = :listId AND refId = :memberId AND type = 'list_item'")
    suspend fun updateListItemOrder(listId: String, memberId: String, sortOrder: Int)
}

/** id/type/base64-head projection for [ObjectDao.imageRowHeads] (type selects the image field). */
data class IndexImageHead(val id: String, val type: String, val head: String)
