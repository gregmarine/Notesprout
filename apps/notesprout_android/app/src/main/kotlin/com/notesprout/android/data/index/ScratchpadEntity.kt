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

    // ── Columnar payload (v6, all nullable — mirrors NotebookObject; see [toNotebookObject]) ──
    @ColumnInfo(name = "x") val x: Float? = null,
    @ColumnInfo(name = "y") val y: Float? = null,
    @ColumnInfo(name = "width") val width: Float? = null,
    @ColumnInfo(name = "height") val height: Float? = null,
    @ColumnInfo(name = "text") val text: String? = null,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "strokeWidth") val strokeWidth: Float? = null,
    @ColumnInfo(name = "refId") val refId: String? = null,
    @ColumnInfo(name = "level") val level: Int? = null,
    @ColumnInfo(name = "lineStyle") val lineStyle: String? = null,
    @ColumnInfo(name = "orientation") val orientation: String? = null,
    @ColumnInfo(name = "dotSpacing") val dotSpacing: Float? = null,
    @ColumnInfo(name = "shapeType") val shapeType: String? = null,
    @ColumnInfo(name = "centerX") val centerX: Float? = null,
    @ColumnInfo(name = "centerY") val centerY: Float? = null,
    @ColumnInfo(name = "rotationDeg") val rotationDeg: Float? = null,
    @ColumnInfo(name = "pointCount") val pointCount: Int? = null,
    @ColumnInfo(name = "contentW") val contentW: Float? = null,
    @ColumnInfo(name = "contentH") val contentH: Float? = null,
    @ColumnInfo(name = "linkTarget") val linkTarget: String? = null,
    @ColumnInfo(name = "chrome") val chrome: String? = null,
    @ColumnInfo(name = "flags") val flags: Int? = null,
    @ColumnInfo(name = "blob", typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
)
