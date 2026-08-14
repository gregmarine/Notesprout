package com.notesprout.android.data.index

import com.notesprout.android.crypto.KeyScope
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NotebookObject(
    val snapshot: String? = null,
    val pageCount: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val encrypted: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keyScope: KeyScope? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val excludeFromBackup: Boolean = false,
    val lastBackedUpLocal: Long? = null,
    val lastBackedUpDrive: Long? = null,
    /**
     * True for a **text document** — a notebook whose primary surface is its notebook document
     * (opens straight into the document editor). Stored as a `flags` bit on columnar rows;
     * defaults false for legacy JSON rows. See docs/documents.md § Text documents.
     */
    val textDocument: Boolean = false,
)
