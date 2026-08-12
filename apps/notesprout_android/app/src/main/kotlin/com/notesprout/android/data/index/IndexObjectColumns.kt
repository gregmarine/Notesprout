package com.notesprout.android.data.index

import android.util.Base64
import com.notesprout.android.crypto.KeyScope
import kotlinx.serialization.json.Json

/**
 * Object ⇄ columnar-row mapping for the global index (`notesprout.db` `objects` table) — the index-side
 * analogue of [com.notesprout.android.data.ObjectColumns] for the `.soil` file.
 *
 * Every reader here is **format-agnostic**: a columnar row (`data == ""`) reads from the typed columns
 * + binary [ObjectEntity.blob]; a legacy row falls back to the `data` JSON, so pre-migration rows keep
 * working and convert lazily (see [com.notesprout.android.data.NotebookCompactor]). Writers always emit
 * columnar rows (`data = ""`). This is the single boundary the repository / activities use — no other
 * code decodes the notebook/template `data` JSON directly.
 *
 * Scope: notebook + template + folder (Phase A). List membership is Phase B; clipboard/backup-config
 * rows keep their JSON by design.
 */

private val lenientJson = Json { ignoreUnknownKeys = true }

// Notebook flag bits packed into ObjectEntity.flags.
private const val FLAG_ENCRYPTED = 1
private const val FLAG_EXCLUDE_BACKUP = 2

private fun flagsOf(encrypted: Boolean, excludeFromBackup: Boolean): Int =
    (if (encrypted) FLAG_ENCRYPTED else 0) or (if (excludeFromBackup) FLAG_EXCLUDE_BACKUP else 0)

// ── Notebook ─────────────────────────────────────────────────────────────────
// The cover snapshot (base64 in the legacy JSON) becomes the binary blob; everything else is a scalar
// column. Leak hygiene is unchanged — callers still gate the snapshot on `encrypted` before storing it.

/**
 * The lock state from a **columnar** summary's scalar columns alone: encrypted with anything but
 * GLOBAL scope (an unknown scope string stays locked, matching [notebookMeta]'s null-on-invalid).
 * Meaningless on a [ObjectSummary.legacy] row — the truth is in its JSON; callers fall back to a
 * full read there.
 */
fun ObjectSummary.columnarLocked(): Boolean =
    ((flags ?: 0) and FLAG_ENCRYPTED) != 0 && keyScope != KeyScope.GLOBAL.name

/** Read a notebook row's metadata: typed columns when columnar (`data == ""`), else legacy JSON. */
fun ObjectEntity.notebookMeta(): NotebookObject =
    if (data.isEmpty()) NotebookObject(
        snapshot = blob?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        pageCount = pageCount ?: 0,
        encrypted = ((flags ?: 0) and FLAG_ENCRYPTED) != 0,
        keyScope = keyScope?.let { runCatching { KeyScope.valueOf(it) }.getOrNull() },
        excludeFromBackup = ((flags ?: 0) and FLAG_EXCLUDE_BACKUP) != 0,
        lastBackedUpLocal = lastBackedUpLocal,
        lastBackedUpDrive = lastBackedUpDrive,
    ) else lenientJson.decodeFromString(NotebookObject.serializer(), data)

/**
 * Apply notebook [meta] onto this row as typed columns, clearing any legacy JSON (`data = ""`).
 * `updatedAt` is preserved — callers that mean to bump it do so with a following `.copy(updatedAt = …)`.
 */
fun ObjectEntity.withNotebookMeta(meta: NotebookObject): ObjectEntity = copy(
    data = "",
    pageCount = meta.pageCount,
    flags = flagsOf(meta.encrypted, meta.excludeFromBackup),
    keyScope = meta.keyScope?.name,
    lastBackedUpLocal = meta.lastBackedUpLocal,
    lastBackedUpDrive = meta.lastBackedUpDrive,
    blob = meta.snapshot?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
    width = null, height = null,
)

// ── Template ───────────────────────────────────────────────────────────────────
// The full-resolution image (base64 in the legacy JSON) becomes the binary blob; size goes to the
// width/height columns. Readers keep receiving [TemplateObject.image] as base64 (re-encoded on read).

/** Read a template row's payload: typed columns + blob when columnar, else legacy JSON. Null if malformed. */
fun ObjectEntity.templateObject(): TemplateObject? =
    if (data.isEmpty()) TemplateObject(
        width = width ?: 0,
        height = height ?: 0,
        image = blob?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
    ) else TemplateObject.fromJson(data)

/** Apply template [t] onto this row as typed columns + blob, clearing any legacy JSON (`data = ""`). */
fun ObjectEntity.withTemplate(t: TemplateObject): ObjectEntity = copy(
    data = "",
    width = t.width,
    height = t.height,
    blob = t.image.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
    pageCount = null, flags = null, keyScope = null, lastBackedUpLocal = null, lastBackedUpDrive = null,
)
