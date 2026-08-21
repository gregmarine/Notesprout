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
 *  - object (arc 4): [style] = provider identity `<pkg>:<typeId>`, [text] = the provider's opaque
 *    payload, [x]/[y]/[width]/[height] = bounds in page px, [order] = z-order among the page's objects
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
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Int? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
)

/** Projection of [SoilObjectEntity] for `SoilDao.liveObjectIdentities` — parent page + provider identity only. */
data class ObjectIdentityRow(val parentId: String, val style: String?)
