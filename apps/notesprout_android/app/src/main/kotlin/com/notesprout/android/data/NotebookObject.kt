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

    /**
     * Legacy type-owned JSON payload. Kept for **lazy coexistence** while the columnar migration
     * (data-model-optimization Phase 1) rolls out: old rows still carry their JSON here; new columnar
     * rows write `""` and put their data in the typed columns + [blob] below. Format-agnostic readers
     * prefer the columns/[blob] and fall back to this when they are empty. A later phase drops it.
     */
    @ColumnInfo(name = "data")
    val data: String,

    // ── Columnar payload (v4, all nullable — wide sparse table) ────────────────
    // Geometry (was the boundingBox JSON). NULL for strokes (derived from points on load),
    // layers, and the notebook-meta row.
    @ColumnInfo(name = "x") val x: Float? = null,
    @ColumnInfo(name = "y") val y: Float? = null,
    @ColumnInfo(name = "width") val width: Float? = null,
    @ColumnInfo(name = "height") val height: Float? = null,

    // Shared content columns.
    /** heading.recognizedText · text.text · page_text · template.name · layer.label · notebook.title */
    @ColumnInfo(name = "text") val text: String? = null,
    /** stroke colour (`#RRGGBB`/`#AARRGGBB`). */
    @ColumnInfo(name = "color") val color: String? = null,
    /** stroke / line / shape width. */
    @ColumnInfo(name = "strokeWidth") val strokeWidth: Float? = null,
    /** page→template id · notebook→lastOpenedPage · link→target page/notebook id. */
    @ColumnInfo(name = "refId") val refId: String? = null,

    // Small type-specific fields.
    @ColumnInfo(name = "level") val level: Int? = null,                 // heading
    @ColumnInfo(name = "lineStyle") val lineStyle: String? = null,      // line
    @ColumnInfo(name = "orientation") val orientation: String? = null,  // line
    @ColumnInfo(name = "dotSpacing") val dotSpacing: Float? = null,     // line
    @ColumnInfo(name = "shapeType") val shapeType: String? = null,      // shape
    @ColumnInfo(name = "centerX") val centerX: Float? = null,           // shape
    @ColumnInfo(name = "centerY") val centerY: Float? = null,           // shape
    @ColumnInfo(name = "rotationDeg") val rotationDeg: Float? = null,   // shape
    @ColumnInfo(name = "pointCount") val pointCount: Int? = null,       // shape (STAR)
    @ColumnInfo(name = "contentW") val contentW: Float? = null,         // sticky content space
    @ColumnInfo(name = "contentH") val contentH: Float? = null,         // sticky content space
    @ColumnInfo(name = "linkTarget") val linkTarget: String? = null,    // link target (JSON discriminator)
    @ColumnInfo(name = "chrome") val chrome: String? = null,            // link chrome
    /** bitfield: layer isLocked (bit0) / isVisible (bit1); shape aspectLocked (bit0). */
    @ColumnInfo(name = "flags") val flags: Int? = null,

    /**
     * Binary payload. Stroke geometry (packed points via [com.notesprout.android.core.StrokeCodec]),
     * decoded template image bytes, or the atomic nested content of a composite (link / sticky note /
     * ML-fail heading·text fallback) held as zlib(JSON) until it is normalized in a later phase.
     */
    @ColumnInfo(name = "blob", typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
)

const val TYPE_STROKE       = "stroke"
const val TYPE_HEADING      = "heading"
const val TYPE_TEXT         = "text"
const val TYPE_LINE         = "line"
const val TYPE_LINK         = "link"
const val TYPE_STICKY_NOTE  = "sticky_note"
const val TYPE_SHAPE        = "shape"
/** Cached, reading-order recognized text for one page. One row per page; parentId = pageId. */
const val TYPE_PAGE_TEXT    = "page_text"
