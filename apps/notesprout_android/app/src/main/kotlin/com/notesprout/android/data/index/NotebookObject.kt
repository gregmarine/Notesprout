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
)
