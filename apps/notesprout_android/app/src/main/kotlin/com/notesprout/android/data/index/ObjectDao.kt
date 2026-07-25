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

    // ── Phase C: bulk columnar backfill of the index `objects` table ─────────

    /** Structural rows still carrying legacy JSON in `data` (convert to columnar on the manual sweep). */
    @Query("SELECT * FROM objects WHERE data <> '' AND type IN ('notebook', 'template', 'folder', 'template_folder')")
    suspend fun legacyStructuralIndexRows(): List<ObjectEntity>

    /** Columnar image rows (notebook cover / template image in `blob`) — candidates for WEBP re-encode. */
    @Query("SELECT * FROM objects WHERE data = '' AND blob IS NOT NULL AND type IN ('notebook', 'template')")
    suspend fun columnarImageIndexRows(): List<ObjectEntity>

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
