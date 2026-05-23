package com.notesprout.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity mapping to the single `notebook` table inside every `.soil` file.
 *
 * Every object in a notebook — pages, layers, strokes, images, metadata — is a row
 * in this table. Type behaviour lives in Kotlin; the [type] column is a plain string
 * discriminator (e.g. "page", "layer", "stroke").
 *
 * Column names mirror the schema defined in CLAUDE.md exactly. `order` is an SQL
 * reserved word, so the Kotlin property is named `sortOrder` and mapped via @ColumnInfo.
 *
 * The index declaration must mirror the one created by MainActivity.createNotebook() so
 * Room's schema validation passes when opening an existing .soil file.
 */
@Entity(
    tableName = "notebook",
    indices = [
        Index(
            name = "idx_notebook_parent_order",
            value = ["parentId", "order", "deletedAt"],
        )
    ],
)
data class NotebookObject(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "parentId")
    val parentId: String,

    /** JSON: {"x":0.0,"y":0.0,"width":0.0,"height":0.0} */
    @ColumnInfo(name = "boundingBox")
    val boundingBox: String,

    /**
     * Sort order among siblings — mapped from the SQL `order` column.
     * defaultValue = "0" must match `DEFAULT 0` in the CREATE TABLE statement
     * so Room's pre-open schema validation agrees on this column.
     */
    @ColumnInfo(name = "order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    /** Null means the object is alive; non-null is a soft delete timestamp (Unix epoch ms). */
    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,

    /**
     * Object type discriminator — "page", "layer", "stroke", etc.
     * No DEFAULT in SQL, so no defaultValue here either.
     */
    @ColumnInfo(name = "type")
    val type: String,

    /** Type-owned JSON blob — stroke arrays, image base64, text content, etc. */
    @ColumnInfo(name = "data")
    val data: String,
)
