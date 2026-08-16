package com.symmetricalpalmtree.notesprout.data.soil

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row of the `notebook` table — the universal object row of a Paper `.soil` (see [SoilSchema]).
 *
 * Hierarchy: `notebook` row (`parentId = ""`) → `page` rows → `stroke` rows; one `template` row
 * under the notebook row. Columnar payload, wide + sparse, every payload column nullable:
 *  - notebook: [text] = title, [refId] = last-opened page id
 *  - template: [text] = kind name, [width]/[height] px, [blob] = WEBP q100
 *  - page: [refId] = template row id ("" = blank), [width]/[height] px
 *  - stroke: [color] `#RRGGBB`/`#AARRGGBB`, [strokeWidth] px, [style] g-paper StrokeStyle name,
 *    [blob] = format-B geometry ([com.symmetricalpalmtree.notesprout.core.StrokeCodec])
 */
@Entity(
    tableName = SoilSchema.TABLE,
    indices = [Index(value = ["parentId", "order", "deletedAt"], name = "idx_notebook_parent_order")],
)
data class SoilObjectEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val type: String,
    @ColumnInfo(name = "order", defaultValue = "0") val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val text: String? = null,
    val refId: String? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Int? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
)
