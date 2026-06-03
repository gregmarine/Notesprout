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

    // ── Notebook metadata ─────────────────────────────────────────────────────

    /**
     * The single notebook metadata row (type = 'notebook', parentId = ''), or null if
     * the notebook pre-dates the metadata row introduction.
     */
    @Query("SELECT * FROM notebook WHERE type = 'notebook' LIMIT 1")
    suspend fun getNotebookObject(): NotebookObject?

    /**
     * Insert or replace the notebook metadata row.
     * Used by [saveLastOpenedPage] to persist the last-viewed page UUID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotebookObject(obj: NotebookObject)

    // ── Generic single-row lookup ─────────────────────────────────────────────

    /**
     * Fetch any single row by its [id], regardless of type or soft-delete status.
     * Used by template loading to look up the page row and read its data JSON.
     */
    @Query("SELECT * FROM notebook WHERE id = :id LIMIT 1")
    suspend fun getObjectById(id: String): NotebookObject?

    // ── Template rows ─────────────────────────────────────────────────────────

    /**
     * All non-deleted template rows in this notebook, ordered by creation time ascending.
     * Used by the template dialog's "Notebook" tab.
     */
    @Query("SELECT * FROM notebook WHERE type = 'template' AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getTemplatesSorted(): List<NotebookObject>

    /**
     * A single non-deleted template row by [id], or null if not found.
     * Used when loading a page's stored template bitmap for rendering.
     */
    @Query("SELECT * FROM notebook WHERE type = 'template' AND id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getTemplateById(id: String): NotebookObject?

    // ── Data column update ────────────────────────────────────────────────────

    /**
     * Overwrite the [data] column for the row with [id] and update [updatedAt].
     * Used to persist the page's `template` property after the user picks a template,
     * and to persist page snapshots after non-writing transitions.
     */
    @Query("UPDATE notebook SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateData(id: String, data: String, updatedAt: Long)

    /**
     * Overwrite the serialized point array for a single stroke row.
     * Used by the lasso-move commit path to persist translated stroke coordinates
     * without re-inserting the row or changing any other columns.
     */
    @Query("UPDATE notebook SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStrokeData(id: String, data: String, updatedAt: Long)

    // ── Undo/redo restore operations ──────────────────────────────────────────

    /**
     * Restore a soft-deleted row by clearing its [NotebookObject.deletedAt].
     * Updates [NotebookObject.updatedAt] so snapshot-staleness checks detect the change.
     * Used by undo/redo to un-erase strokes and un-delete pages.
     */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreById(id: String, updatedAt: Long)

    /**
     * Restore all child rows of [parentId] whose [NotebookObject.deletedAt] is >= [since].
     *
     * This is the cascade-restore counterpart to [softDeleteByParentId]: when a page was
     * deleted at timestamp T, all its children (layers, strokes) also have deletedAt = T.
     * Passing [since] = T restores exactly those rows without touching children that were
     * independently soft-deleted before the page deletion.
     *
     * Updates [NotebookObject.updatedAt] so snapshot-staleness checks detect the restore.
     */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :updatedAt WHERE parentId = :parentId AND deletedAt >= :since")
    suspend fun restoreChildrenDeletedSince(parentId: String, since: Long, updatedAt: Long)

    /**
     * The layer row belonging to [pageId], regardless of soft-delete status.
     * Used by undo/redo when restoring or cascade-deleting layers whose page has been
     * soft-deleted and is not returned by [getLayerForPage].
     */
    @Query("SELECT * FROM notebook WHERE type = 'layer' AND parentId = :pageId LIMIT 1")
    suspend fun getLayerForPageAny(pageId: String): NotebookObject?

    // ── Cover loading ─────────────────────────────────────────────────────────

    /**
     * The non-deleted cover object for [notebookId], or null if none exists.
     * Used by the cover loading path to retrieve the cover image for display in the grid.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :notebookId AND type = 'cover' AND deletedAt IS NULL LIMIT 1")
    suspend fun getCoverForNotebook(notebookId: String): NotebookObject?

    /**
     * The non-deleted page row for [pageId], or null if not found / soft-deleted.
     * Used by the cover loading path to extract the page's `snapshot` field when no
     * explicit cover object is present.
     */
    @Query("SELECT * FROM notebook WHERE id = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getLastOpenedPageSnapshot(pageId: String): NotebookObject?

    // ── Snapshot staleness check ──────────────────────────────────────────────

    /**
     * The maximum [updatedAt] across ALL stroke rows (including soft-deleted) under
     * [layerId].  Soft-deleted rows have [NotebookObject.updatedAt] set to their
     * deletion timestamp, so this query detects both new strokes and erased strokes
     * that occurred after the last snapshot.
     *
     * Returns null if no stroke rows exist for the layer (blank page).
     *
     * Used in stale-snapshot detection: if the result exceeds the page row's
     * [NotebookObject.updatedAt], the stored snapshot pre-dates a stroke change and
     * must be discarded in favour of a full re-render.
     */
    @Query("SELECT MAX(updatedAt) FROM notebook WHERE type = 'stroke' AND parentId = :layerId")
    suspend fun getMaxStrokeUpdatedAt(layerId: String): Long?
}
