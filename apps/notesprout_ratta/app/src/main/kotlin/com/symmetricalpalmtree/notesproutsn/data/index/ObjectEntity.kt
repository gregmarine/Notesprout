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

    /**
     * The global clipboard (arc 7) — one row at [ListIds.CLIPBOARD_ID], replaced by every copy or
     * cut. The same additive-row-type pattern as [NAMING] and for the same reason: `notesprout.db`
     * is Room-validated and format-compatible with Paper, so a new `@Entity` would change the
     * identity hash and a Paper index would fail validation (and vice versa).
     *
     * `name` = the payload kind (`"page"`) · `refId` = the source notebook id · `flags` = the
     * envelope version · `createdAt`/`updatedAt` = when it was copied · `blob` = the
     * [com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope] JSON, UTF-8. Never
     * soft-deleted, and invisible to the library because every listing query is type-filtered.
     */
    const val CLIPBOARD = "clipboard"

    /**
     * A **static template** in the template library (arc 13) — imported pixels. The same
     * additive-row-type pattern as [NAMING] and [CLIPBOARD], and
     * for the same reason: `notesprout.db` is Room-validated and format-compatible with Paper, so a
     * new `@Entity` would change the identity hash and a Paper index would fail validation.
     *
     * `name` = the template's name · `parentId` = its [TEMPLATE_FOLDER] (null = the templates root)
     * · `templateKind` = the base [com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind]
     * or `IMAGE` · `flags` = the fit mode (G4) · `blob` = the **original** image bytes, rendered to
     * the page's size only on use. `refId` / `sortOrder` / `pageCount` / `keyScope` unused.
     *
     * Nothing about templates touches the filesystem, and deleting one never touches a notebook
     * that used it — the pixels were copied into the `.soil` at apply time.
     */
    const val TEMPLATE = "template"

    /**
     * A folder in the template library (arc 13). `parentId` exactly like a notebook [FOLDER], and
     * a separate type so the two hierarchies can never see each other: a templates listing asks for
     * this type, and the library's own listing asks for [FOLDER].
     */
    const val TEMPLATE_FOLDER = "template_folder"

    /**
     * The backup configuration (arc 17 / K2) — one row at [ListIds.BACKUP_ID], the same additive
     * row-type pattern as [CLIPBOARD] and for the same reason (the Room identity hash is the format
     * contract with Paper).
     *
     * `name` = `"backup"` · `flags` = the config grammar version · `blob` = the
     * [com.symmetricalpalmtree.notesproutsn.data.backup.BackupConfig] JSON, UTF-8 (the SAF tree
     * URI, `lastRunAt`, and the per-notebook stamp map). Never soft-deleted; a corrupt blob reads
     * as a fresh config, whose worst case is re-copying everything — the safe direction.
     */
    const val BACKUP = "backup"
}

/** Notebook `flags` bits. */
object NotebookFlags {
    const val ENCRYPTED = 1

    /** Arc 17 / K2 — "Exclude from backup" on the library long-press sheet. Format-safe: Paper
     *  ignores unknown bits. Setting or clearing it never bumps `updatedAt` (policy, not content —
     *  og's rule: a bump would re-flag the notebook the moment it was toggled). */
    const val EXCLUDE_FROM_BACKUP = 2
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
