package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** CRUD for the `calendar` table — mirrors [ScratchpadDao] against the calendar table. */
@Dao
interface CalendarDao {

    // ── Insert ───────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertObject(obj: CalendarEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(obj: CalendarEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(objects: List<CalendarEntity>)

    // ── Select ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM calendar WHERE type = 'layer' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getLayerForPage(pageId: String): CalendarEntity?

    @Query("SELECT * FROM calendar WHERE type = 'stroke' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStrokesForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'heading' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getHeadingsForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'text' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getTextObjectsForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'line' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getLineObjectsForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'link' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getLinkObjectsForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'sticky_note' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStickyNotesForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE type = 'shape' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getShapeObjectsForLayer(layerId: String): List<CalendarEntity>

    @Query("SELECT COUNT(*) FROM calendar WHERE type = 'calendar_root' AND deletedAt IS NULL")
    suspend fun getRootCount(): Int

    /** All non-deleted template rows in the calendar table, oldest first (day-page template picker). */
    @Query("SELECT * FROM calendar WHERE type = 'template' AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getTemplatesSorted(): List<CalendarEntity>

    /** A single non-deleted template row by [id], or null — used to render a day page's template. */
    @Query("SELECT * FROM calendar WHERE type = 'template' AND id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getTemplateById(id: String): CalendarEntity?

    @Query("SELECT * FROM calendar WHERE id = :id LIMIT 1")
    suspend fun getObjectById(id: String): CalendarEntity?

    /**
     * Day-note page ids (`cal-daynote-YYYY-MM-DD`) that actually hold content, matching [idPattern]
     * (e.g. `cal-daynote-____-06-30` — four `_` wildcards for the year). Only pages whose content
     * layer has at least one live non-layer child are returned, so blank auto-created day pages are
     * excluded. Powers the Day-Detail History year picker + read-only note lookup.
     */
    @Query(
        "SELECT DISTINCT p.id FROM calendar p " +
            "JOIN calendar l ON l.parentId = p.id AND l.type = 'layer' AND l.deletedAt IS NULL " +
            "JOIN calendar c ON c.parentId = l.id AND c.type != 'layer' AND c.deletedAt IS NULL " +
            "WHERE p.type = 'page' AND p.deletedAt IS NULL AND p.id LIKE :idPattern"
    )
    suspend fun dayNotePagesWithContent(idPattern: String): List<String>

    /** All live content objects on a layer (any type) — used to snapshot for undo/redo. */
    @Query("SELECT * FROM calendar WHERE parentId = :layerId AND type != 'layer' AND deletedAt IS NULL")
    suspend fun getAllChildrenForLayer(layerId: String): List<CalendarEntity>

    /** Hard-delete every child row of a layer (used by undo/redo restore — calendar is local). */
    @Query("DELETE FROM calendar WHERE parentId = :layerId")
    suspend fun deleteChildren(layerId: String)

    // ── Update ───────────────────────────────────────────────────────────────

    @Query("UPDATE calendar SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE calendar SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun softDeleteByParentId(parentId: String, deletedAt: Long)

    @Query("UPDATE calendar SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateData(id: String, data: String, updatedAt: Long)

    @Query("UPDATE calendar SET boundingBox = :boundingBox, data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageSize(id: String, boundingBox: String, data: String, updatedAt: Long)

    @Query("UPDATE calendar SET boundingBox = :boundingBox, data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateObjectData(id: String, boundingBox: String, data: String, updatedAt: Long)
}
