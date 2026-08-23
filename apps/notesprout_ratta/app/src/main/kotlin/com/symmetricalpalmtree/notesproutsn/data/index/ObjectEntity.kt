package com.symmetricalpalmtree.notesproutsn.data.index

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

    /**
     * A folder's default-notebook-name scheme (arc 5). An **additive row type**, not a schema
     * change: it reuses the universal row shape, so the Room identity hash — the format contract
     * with Paper — is untouched, and a Paper index simply never lists these rows.
     */
    const val NAMING = "naming"
}

/** Notebook `flags` bits. */
object NotebookFlags {
    const val ENCRYPTED = 1
}

/**
 * A row of the global index `objects` table — the universal row shape, lean: no legacy JSON, no
 * app-content tables. **Field order, names and types are the format contract** — they must
 * generate exactly Paper's schema (Room identity hash) so an SN index stays family-compatible.
 *
 *  - folder: [name], [parentId]
 *  - notebook: [name], [parentId], [pageCount], [flags] bit0 = encrypted, [keyScope] `GLOBAL`,
 *    [templateKind], [blob] = cover (WEBP q100)
 *  - list: sentinel id ([ListIds]), [name]
 *  - list_item: [parentId] = list id, [refId] = member id, [sortOrder]
 *  - naming: [parentId] = folder id, or null = the library root; [name] = the scheme text
 *
 * [updatedAt] is bumped ONLY by real edits (name, ink, page insert/delete, move) — it is the
 * "Last modified" the library sorts by. Soft deletes only ([deletedAt]); membership edges are the
 * one routine hard delete.
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
