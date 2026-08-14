package com.notesprout.android.data

import com.notesprout.android.crypto.KeyScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

@Serializable
data class NotebookMeta(
    val formatVersion: Int = 1,
    val notebookId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val encrypted: Boolean,
    val keyScope: KeyScope? = null,
    val cover: String? = null,          // base64 PNG; plaintext notebooks only
    val folderPath: List<FolderRef> = emptyList(),
    val exportedAt: Long? = null,
    val appVersionCode: Int? = null,
    /**
     * True for a **text document** — a notebook whose primary surface is its notebook document.
     * Travels with the file so an imported text document opens into the editor on any device;
     * the importer copies it onto the index row's flag. Additive + defaulted, so older readers
     * ignore it and older files read as false.
     */
    val textDocument: Boolean = false,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String): NotebookMeta = codec.decodeFromString(serializer(), s)
    }
}

@Serializable
data class FolderRef(val id: String, val name: String, val parentId: String?)
