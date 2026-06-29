package com.notesprout.android.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Object row for calendar-view handwriting, stored in `notesprout.db`.
 * Schema is identical to [ScratchpadEntity] so every existing object serializer / render model
 * works unchanged. See docs/scratchpad.md for the shared row-hierarchy model.
 */
@Entity(
    tableName = "calendar",
    indices = [
        Index(
            name = "idx_calendar_parent_order",
            value = ["parentId", "order", "deletedAt"],
        )
    ],
)
data class CalendarEntity(
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
