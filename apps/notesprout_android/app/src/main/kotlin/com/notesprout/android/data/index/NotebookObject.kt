package com.notesprout.android.data.index

import kotlinx.serialization.Serializable

@Serializable
data class NotebookObject(
    val snapshot: String? = null,
    val pageCount: Int = 0
)
