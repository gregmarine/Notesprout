package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScratchpadDao {

    // ── Insert ───────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertObject(obj: ScratchpadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(obj: ScratchpadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(objects: List<ScratchpadEntity>)

    // ── Select ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM scratchpad WHERE type = 'page' AND parentId = :rootId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getPagesSorted(rootId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'layer' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getLayerForPage(pageId: String): ScratchpadEntity?

    @Query("SELECT * FROM scratchpad WHERE parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getObjectsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'stroke' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStrokesForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'heading' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getHeadingsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'text' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getTextObjectsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'line' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getLineObjectsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'link' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getLinkObjectsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'sticky_note' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStickyNotesForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT * FROM scratchpad WHERE type = 'shape' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getShapeObjectsForLayer(layerId: String): List<ScratchpadEntity>

    @Query("SELECT COUNT(*) FROM scratchpad WHERE parentId = :rootId AND type = 'page' AND deletedAt IS NULL")
    suspend fun getPageCount(rootId: String): Int

    @Query("SELECT COUNT(*) FROM scratchpad WHERE type = 'scratchpad_root' AND deletedAt IS NULL")
    suspend fun getRootCount(): Int

    @Query("SELECT * FROM scratchpad WHERE id = :id LIMIT 1")
    suspend fun getObjectById(id: String): ScratchpadEntity?

    /** All live content objects on a layer (any type) — used to snapshot for undo/redo. */
    @Query("SELECT * FROM scratchpad WHERE parentId = :layerId AND type != 'layer' AND deletedAt IS NULL")
    suspend fun getAllChildrenForLayer(layerId: String): List<ScratchpadEntity>

    /** Hard-delete every child row of a layer (used by undo/redo restore — scratch pad is local). */
    @Query("DELETE FROM scratchpad WHERE parentId = :layerId")
    suspend fun deleteChildren(layerId: String)

    // ── Update ───────────────────────────────────────────────────────────────

    @Query("UPDATE scratchpad SET `order` = :order WHERE id = :id")
    suspend fun updateOrder(id: String, order: Int)

    @Query("UPDATE scratchpad SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE scratchpad SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun softDeleteByParentId(parentId: String, deletedAt: Long)

    @Query("UPDATE scratchpad SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateData(id: String, data: String, updatedAt: Long)

    @Query("UPDATE scratchpad SET boundingBox = :boundingBox, data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageSize(id: String, boundingBox: String, data: String, updatedAt: Long)

    @Query("UPDATE scratchpad SET boundingBox = :boundingBox, data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateObjectData(id: String, boundingBox: String, data: String, updatedAt: Long)
}
