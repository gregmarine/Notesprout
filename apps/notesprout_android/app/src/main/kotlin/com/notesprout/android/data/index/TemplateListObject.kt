package com.notesprout.android.data.index

import kotlinx.serialization.Serializable

@Serializable
data class TemplateListObject(
    val templateIds: List<String> = emptyList()
)
