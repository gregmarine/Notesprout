package com.symmetricalpalmtree.notesproutsn.data.soil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

/** The only key scope in SN. Kept as a string column/field so the file stays in the `.soil` family. */
const val KEY_SCOPE_GLOBAL = "GLOBAL"

/** The family's own-passphrase scope. SN never *opens* under it — it appears only in the meta of a
 *  re-keyed export (arc 15 / E2), where the file's honest self-description is "keyed to a
 *  passphrase of my own", exactly as og stamps it. */
const val KEY_SCOPE_NOTEBOOK = "NOTEBOOK"

/**
 * The self-describing single row of `notebook_meta` — the same field set (and declaration order)
 * as the family's `NotebookMeta`, so an SN file is indistinguishable from a Paper one. Refreshed
 * on create / open / close / rename / move. Ids and names only; never key material.
 */
@Serializable
data class NotebookMeta(
    val formatVersion: Int = 1,
    val notebookId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val encrypted: Boolean = true,
    val keyScope: String? = KEY_SCOPE_GLOBAL,
    val cover: String? = null,
    val folderPath: List<FolderRef> = emptyList(),
    val exportedAt: Long? = null,
    val appVersionCode: Int? = null,
    val textDocument: Boolean = false,
    /** Arc 43 / K3 — the mirror of `NotebookFlags.SKETCH`, so a sketch notebook stays a sketch
     *  notebook when its `.soil` is exported and imported somewhere else. **Last**, and additive
     *  like `textDocument` before it: a file written by an older build simply has no key, which
     *  decodes to false. The index bit is the authority; every meta writer sources this from the
     *  bit and never from the previous meta row (the og meta-refresh-wipe trap). Exclusive with
     *  [textDocument] by construction — see
     *  [com.symmetricalpalmtree.notesproutsn.data.index.NotebookKind]. */
    val sketch: Boolean = false,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String): NotebookMeta = codec.decodeFromString(serializer(), s)
    }
}

@Serializable
data class FolderRef(val id: String, val name: String, val parentId: String?)
