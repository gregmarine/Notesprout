package com.symmetricalpalmtree.notesprout.data.soil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

/** The only key scope in v0. Kept as a string column/field so the file stays in the `.soil` family. */
const val KEY_SCOPE_GLOBAL = "GLOBAL"

/**
 * The self-describing single row of `notebook_meta` — the same field set as Notesprout's
 * `NotebookMeta` so a Paper file stays in the family. Refreshed on create / open / close / rename /
 * move. Ids and names only; never key material.
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
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String): NotebookMeta = codec.decodeFromString(serializer(), s)
    }
}

@Serializable
data class FolderRef(val id: String, val name: String, val parentId: String?)
