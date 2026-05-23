package com.notesprout.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for the `notebook` table.
 *
 * All methods are suspend functions — call them from Dispatchers.IO.
 * `order` is a reserved SQL word; queries that sort by it use backtick quoting.
 */
@Dao
interface NotebookDao {

    /** Insert a single object (page, layer, stroke, …). */
    @Insert
    suspend fun insertObject(obj: NotebookObject)

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
     * Soft-delete a single object by id.
     * Sets both [deletedAt] and [updatedAt] to [deletedAt] timestamp.
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * First non-deleted row of [type], or null if none exist.
     * Useful for retrieving the single page or layer in a fresh notebook.
     */
    @Query("SELECT * FROM notebook WHERE type = :type AND deletedAt IS NULL LIMIT 1")
    suspend fun getFirstByType(type: String): NotebookObject?
}
