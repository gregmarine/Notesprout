package com.symmetricalpalmtree.notesproutsn.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

private const val SUMMARY_COLS =
    "id, type, name, parentId, createdAt, updatedAt, pageCount, flags, templateKind"

@Dao
interface ObjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ObjectEntity)

    @Query("SELECT * FROM objects WHERE id = :id")
    suspend fun byId(id: String): ObjectEntity?

    @Query("SELECT $SUMMARY_COLS FROM objects WHERE id = :id")
    suspend fun summaryById(id: String): ObjectSummary?

    /** Alive children of [parentId] (NULL = root) of the given [type], blob-free. */
    @Query(
        "SELECT $SUMMARY_COLS FROM objects " +
        "WHERE type = :type AND deletedAt IS NULL AND " +
        "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun childrenOfType(parentId: String?, type: String): List<ObjectSummary>

    @Query(
        "SELECT count(*) FROM objects WHERE type = :type AND deletedAt IS NULL AND name = :name AND " +
        "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) AND id <> :excludeId"
    )
    suspend fun countSiblingsNamed(parentId: String?, type: String, name: String, excludeId: String): Int

    @Query("UPDATE objects SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: String, name: String, at: Long)

    @Query("UPDATE objects SET parentId = :parentId, updatedAt = :at WHERE id = :id")
    suspend fun move(id: String, parentId: String?, at: Long)

    @Query("UPDATE objects SET deletedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, at: Long)

    @Query("UPDATE objects SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE objects SET pageCount = :count WHERE id = :id")
    suspend fun setPageCount(id: String, count: Int)

    @Query("UPDATE objects SET blob = :cover WHERE id = :id")
    suspend fun setCover(id: String, cover: ByteArray?)

    @Query("SELECT blob FROM objects WHERE id = :id")
    suspend fun cover(id: String): ByteArray?

    // ── Lists (pinned) ───────────────────────────────────────────────────────

    @Query("SELECT * FROM objects WHERE type = 'list_item' AND parentId = :listId AND refId = :memberId LIMIT 1")
    suspend fun listItem(listId: String, memberId: String): ObjectEntity?

    @Query("SELECT refId FROM objects WHERE type = 'list_item' AND parentId = :listId ORDER BY sortOrder")
    suspend fun listMemberIds(listId: String): List<String>

    @Query("SELECT coalesce(max(sortOrder), -1) FROM objects WHERE type = 'list_item' AND parentId = :listId")
    suspend fun maxSortOrder(listId: String): Int

    /** Membership edges are the one routine hard delete. */
    @Query("DELETE FROM objects WHERE type = 'list_item' AND parentId = :listId AND refId = :memberId")
    suspend fun deleteListItem(listId: String, memberId: String)

    @Query("DELETE FROM objects WHERE type = 'list_item' AND refId = :memberId")
    suspend fun deleteEdgesTo(memberId: String)

    // ── Naming schemes ───────────────────────────────────────────────────────

    /**
     * The naming row for [parentId] (NULL = the library root) **including a soft-deleted one** —
     * clearing a scheme soft-deletes the row, and setting one again must revive that same row
     * rather than leave a second one behind it.
     */
    @Query(
        "SELECT * FROM objects WHERE type = 'naming' AND " +
        "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) LIMIT 1"
    )
    suspend fun namingRowAny(parentId: String?): ObjectEntity?
}
