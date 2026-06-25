package com.notesprout.android.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scratchpad",
    indices = [
        Index(
            name = "idx_scratchpad_parent_order",
            value = ["parentId", "order", "deletedAt"],
        )
    ],
)
data class ScratchpadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "parentId")
    val parentId: String,

    @ColumnInfo(name = "boundingBox")
    val boundingBox: String,

    @ColumnInfo(name = "order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "data")
    val data: String,
)
