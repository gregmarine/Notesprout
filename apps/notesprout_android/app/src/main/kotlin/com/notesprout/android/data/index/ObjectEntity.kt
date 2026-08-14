package com.notesprout.android.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row in the global index (`notesprout.db`). The structural fields (id/type/name/parent/timestamps)
 * are typed columns; the per-type payload historically lived in the opaque [data] JSON.
 *
 * data-model-optimization Phase A (index columnar): notebook + template + folder rows moved off the
 * `data` JSON onto the typed [pageCount]/[flags]/[keyScope]/[lastBackedUp*]/[width]/[height] columns and
 * the binary [blob] (notebook cover snapshot / template image bytes). A columnar row writes `data = ""`
 * and reads via the typed columns; legacy rows keep their JSON and convert lazily (see
 * [com.notesprout.android.data.NotebookCompactor]). The read/write boundary is [IndexObjectColumns].
 * (List membership is Phase B; clipboard/backup-config rows stay JSON by design.)
 */
@Entity(
    tableName = "objects",
    indices = [Index(value = ["parentId", "type", "deletedAt"])]
)
data class ObjectEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val data: String = "{}",
    // ── v7 columnar payload (present when data == "") ────────────────────────
    val pageCount: Int? = null,
    /** Bit flags: bit0 = encrypted, bit1 = excludeFromBackup (notebook rows). */
    val flags: Int? = null,
    val keyScope: String? = null,
    val lastBackedUpLocal: Long? = null,
    val lastBackedUpDrive: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Notebook cover snapshot / template image bytes (was base64-in-JSON). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
    // ── v8 relational membership (list_item rows: Phase B) ───────────────────
    /** The referenced member id (a `list_item` points at its notebook/template via this). */
    val refId: String? = null,
    /** Position within the parent list (a `list_item`'s ordinal). */
    val sortOrder: Int? = null,
)

/**
 * A blob-free projection of an [ObjectEntity] — the structural columns plus the scalar notebook
 * payload, **without** [ObjectEntity.blob] or the legacy `data` JSON. This is what list screens
 * should read when they need names, folder placement, or the lock state but not the cover bytes:
 * a full-row read drags the snapshot blob out of the encrypted index only to discard it.
 *
 * [legacy] is `data <> ''` — true for a pre-columnar row, whose encrypted/keyScope truth still
 * lives in the JSON. Callers that derive the lock state must fall back to a full read for those
 * (see `DayHistoryRepository.locksFor`); the structural columns are always populated either way.
 */
data class ObjectSummary(
    val id: String,
    val name: String,
    val parentId: String?,
    val type: String,
    val deletedAt: Long?,
    val flags: Int?,
    val keyScope: String?,
    val legacy: Boolean,
)
