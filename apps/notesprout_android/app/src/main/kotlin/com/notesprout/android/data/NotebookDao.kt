package com.notesprout.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the `notebook` table.
 *
 * All methods are suspend functions — call them from Dispatchers.IO.
 * `order` is a reserved SQL word; queries that touch it use backtick or double-quote quoting.
 */
@Dao
interface NotebookDao {

    // ── Insert ───────────────────────────────────────────────────────────────

    /** Insert a single object (page, layer, stroke, …). Fails on conflict. */
    @Insert
    suspend fun insertObject(obj: NotebookObject)

    /**
     * Insert a single object, silently ignoring it if the same [NotebookObject.id]
     * already exists.  Used for incremental stroke saves — already-persisted strokes
     * are skipped without re-writing or deleting them.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(obj: NotebookObject)

    // ── Select ───────────────────────────────────────────────────────────────

    /**
     * All non-deleted rows of a given type.
     * Result order is undefined — callers sort as needed.
     */
    @Query("SELECT * FROM notebook WHERE type = :type AND deletedAt IS NULL")
    suspend fun getObjectsByType(type: String): List<NotebookObject>

    /**
     * All non-deleted children of [parentId], sorted by their `order` column ascending.
     * Returns every type under that parent — filter by [NotebookObject.type] in Kotlin
     * when only a specific subtype is needed.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getObjectsByParent(parentId: String): List<NotebookObject>

    /**
     * All non-deleted pages, sorted by `order` ascending.
     * Use this for multi-page navigation — it reflects the canonical page order.
     */
    @Query("SELECT * FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getPagesSorted(): List<NotebookObject>

    /**
     * The single non-deleted layer belonging to [pageId], or null if none exists.
     * Each page has exactly one content layer.
     */
    @Query("SELECT * FROM notebook WHERE type = 'layer' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getLayerForPage(pageId: String): NotebookObject?

    /**
     * All non-deleted strokes belonging to [layerId], sorted by `order` ascending.
     * Used when loading a page's strokes into the drawing view.
     */
    @Query("SELECT * FROM notebook WHERE type = 'stroke' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStrokesForLayer(layerId: String): List<NotebookObject>

    /**
     * First non-deleted row of [type], or null if none exist.
     * Useful for retrieving the single page or layer in a fresh notebook.
     */
    @Query("SELECT * FROM notebook WHERE type = :type AND deletedAt IS NULL LIMIT 1")
    suspend fun getFirstByType(type: String): NotebookObject?

    // ── Update ───────────────────────────────────────────────────────────────

    /**
     * Soft-delete a single object by [id].
     * Sets both [NotebookObject.deletedAt] and [NotebookObject.updatedAt] to [deletedAt].
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteById(id: String, deletedAt: Long)

    /**
     * Soft-delete all non-deleted children of [parentId].
     * Used to cascade soft-deletes: e.g. delete all strokes under a layer, or a
     * layer under a page, in a single query.
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun softDeleteByParentId(parentId: String, deletedAt: Long)

    /**
     * Legacy single-row soft-delete; kept for compatibility.
     * Prefer [softDeleteById] in new code.
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Update the sort [order] of a single object.
     * Used when re-sequencing pages after an insertion or deletion.
     */
    @Query("UPDATE notebook SET `order` = :order WHERE id = :id")
    suspend fun updateOrder(id: String, order: Int)
}
