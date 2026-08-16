package com.symmetricalpalmtree.notesprout.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Row types in the global index. */
object ObjectType {
    const val FOLDER = "folder"
    const val NOTEBOOK = "notebook"
    const val LIST = "list"
    const val LIST_ITEM = "list_item"
}

/** Notebook `flags` bits. */
object NotebookFlags {
    const val ENCRYPTED = 1
}

/**
 * A row of the global index `objects` table — the universal row shape, lean: no legacy JSON, no
 * app-content tables. Every listing/card read is answerable from this row alone.
 *
 *  - folder: [name], [parentId]
 *  - notebook: [name], [parentId], [pageCount], [flags], [keyScope], [templateKind], [blob] = cover (WEBP q100)
 *  - list: sentinel id ([ListIds]), [name]
 *  - list_item: [parentId] = list id, [refId] = member id, [sortOrder]
 *
 * [updatedAt] is bumped ONLY by real edits (name, ink, page insert/delete, move) — it is the
 * "Last modified" the library sorts by. Soft deletes only ([deletedAt]); membership edges are the one
 * routine hard delete.
 */
@Entity(
    tableName = "objects",
    indices = [Index(value = ["parentId", "type", "deletedAt"])],
)
data class ObjectEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val pageCount: Int? = null,
    val flags: Int? = null,
    val keyScope: String? = null,
    val templateKind: String? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
    val refId: String? = null,
    val sortOrder: Int? = null,
)

/**
 * Blob-free projection for lists and cards — everything but the cover bytes. Reading the full row
 * for a listing would drag every cover out of the encrypted index only to discard it.
 */
data class ObjectSummary(
    val id: String,
    val type: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int?,
    val flags: Int?,
    val templateKind: String?,
)
