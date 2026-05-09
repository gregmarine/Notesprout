package com.notesprout.android.data

data class BaseObject(
    val id: String,
    val parentId: String,
    val pluginId: String,
    val boundingBox: BoundingBox,
    val order: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncVersion: Long,
    val data: String
)
