package com.notesprout.android.data.index

import kotlinx.serialization.Serializable

@Serializable
data class ListObject(
    val notebookIds: List<String> = emptyList()
)
