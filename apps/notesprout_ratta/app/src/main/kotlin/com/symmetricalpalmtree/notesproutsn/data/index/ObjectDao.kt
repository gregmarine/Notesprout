package com.symmetricalpalmtree.notesproutsn.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipHeader

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

    /**
     * The alive notebooks among [ids], blob-free and in no particular order (arc 10 — the notebook's
     * Recents panel resolves up to twenty ids at once). Deliberately **not** [byId]/`alive`, which
     * read the whole row: twenty cover blobs is a megabyte the panel never draws.
     */
    @Query(
        "SELECT $SUMMARY_COLS FROM objects " +
        "WHERE id IN (:ids) AND type = 'notebook' AND deletedAt IS NULL"
    )
    suspend fun aliveNotebooks(ids: List<String>): List<ObjectSummary>

    /**
     * Alive rows of [type] whose name matches [pattern] (`LIKE`, `\\` escaping —
     * `templates/TemplateSearch.likePattern` builds it), **anywhere in the tree**, blob-free.
     *
     * No `parentId` at all: a search that only looked in the folder you happen to be standing in
     * would answer "no" for paper that is two folders over, which is the one question a search
     * exists to answer. SQLite's `LIKE` is ASCII case-insensitive, which is deliberately the same
     * rule `TemplateSearch.matchesLabel` applies to the sentinels the screen composes.
     */
    @Query(
        "SELECT $SUMMARY_COLS FROM objects " +
        "WHERE type = :type AND deletedAt IS NULL AND name LIKE :pattern ESCAPE '\\'"
    )
    suspend fun searchOfType(type: String, pattern: String): List<ObjectSummary>

    /** The alive rows of [type] among [ids], blob-free (arc 13 / G5 — one read for a whole pinned
     *  or recents shelf). Empty [ids] never hits the database. */
    @Query(
        "SELECT $SUMMARY_COLS FROM objects " +
        "WHERE id IN (:ids) AND type = :type AND deletedAt IS NULL"
    )
    suspend fun aliveOfType(ids: List<String>, type: String): List<ObjectSummary>

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

    // ── Clipboard (arc 7) ────────────────────────────────────────────────────

    /**
     * The clipboard row's header — kind, provenance and clock, **blob-free**. This is what the
     * long-press sheet's availability question costs: the payload is megabytes and is read only
     * when a paste actually happens ([clipBlob]).
     */
    @Query(
        "SELECT name AS kind, refId AS sourceNotebookId, updatedAt AS copiedAt, flags AS version " +
        "FROM objects WHERE id = :id AND type = 'clipboard' AND deletedAt IS NULL"
    )
    suspend fun clipHeader(id: String): ClipHeader?

    @Query("SELECT blob FROM objects WHERE id = :id AND type = 'clipboard' AND deletedAt IS NULL")
    suspend fun clipBlob(id: String): ByteArray?

    /**
     * Retire an **unusable** clipboard row (B3 review): soft-delete it *and* drop its pixels, so a
     * payload that cannot be decoded stops advertising a Paste that can only fail — and stops
     * costing megabytes in the index for nothing. There is still no Clear in the UI; this is the
     * recovery path, and the next copy's upsert revives the row wholesale.
     */
    @Query("UPDATE objects SET deletedAt = :at, blob = NULL WHERE id = :id AND type = 'clipboard'")
    suspend fun clipClear(id: String, at: Long)
}
