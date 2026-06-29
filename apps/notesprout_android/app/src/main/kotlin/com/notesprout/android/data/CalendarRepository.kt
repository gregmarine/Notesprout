package com.notesprout.android.data

import androidx.room.withTransaction
import com.notesprout.android.NotesproutClipboard
import com.notesprout.android.data.index.CALENDAR_ROOT_ID
import com.notesprout.android.data.index.CalendarDao
import com.notesprout.android.data.index.CalendarEntity
import com.notesprout.android.data.index.NotesproutDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Persistence for calendar-view handwriting (Month / Week / Day-AM / Day-PM pages) in the
 * `calendar` table of `notesprout.db`. Mirrors [ScratchpadRepository] but is keyed by a
 * deterministic page id (the view + date key) and lazily creates each page + layer on first open.
 * Reuses [ScratchpadPageContent] for the load result. Content is always plaintext.
 */
class CalendarRepository(
    private val db: NotesproutDatabase,
    private val dao: CalendarDao,
) {

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /** Ensures the calendar root row exists. Safe to call repeatedly. */
    suspend fun ensureBootstrap() = withContext(Dispatchers.IO) {
        if (dao.getRootCount() > 0) return@withContext
        val now = System.currentTimeMillis()
        dao.insertOrIgnore(
            CalendarEntity(
                id          = CALENDAR_ROOT_ID,
                parentId    = "",
                boundingBox = BoundingBox(0f, 0f, 0f, 0f).toJson(),
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                type        = "calendar_root",
                data        = "{}",
            )
        )
    }

    // ── Page resolution ─────────────────────────────────────────────────────

    /**
     * Ensure the page row (id = [pageKey]) and its single content layer exist, creating them on
     * first open. Returns (pageId, layerId).
     */
    suspend fun getOrCreatePageLayer(pageKey: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val emptyBbox = BoundingBox(0f, 0f, 0f, 0f).toJson()

        val existingLayer = dao.getLayerForPage(pageKey)
        if (existingLayer != null && dao.getObjectById(pageKey) != null) {
            return@withContext pageKey to existingLayer.id
        }

        var layerId = existingLayer?.id ?: UUID.randomUUID().toString()
        db.withTransaction {
            if (dao.getObjectById(pageKey) == null) {
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = pageKey,
                        parentId    = CALENDAR_ROOT_ID,
                        boundingBox = emptyBbox,
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = "page",
                        data        = PageData(width = 0f, height = 0f, template = "").toJson(),
                    )
                )
            }
            if (dao.getLayerForPage(pageKey) == null) {
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = layerId,
                        parentId    = pageKey,
                        boundingBox = emptyBbox,
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = "layer",
                        data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
                    )
                )
            } else {
                layerId = dao.getLayerForPage(pageKey)!!.id
            }
        }
        pageKey to layerId
    }

    // ── Page size ─────────────────────────────────────────────────────────────

    suspend fun setPageSize(pageId: String, w: Float, h: Float) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val pageRow = dao.getObjectById(pageId) ?: return@withContext
        val updatedData = PageData.fromJson(pageRow.data).copy(width = w, height = h).toJson()
        val bboxJson = BoundingBox(0f, 0f, w, h).toJson()
        dao.updatePageSize(pageId, bboxJson, updatedData, now)
    }

    // ── Load page ─────────────────────────────────────────────────────────────

    /** Load all content for [pageId]. Mirrors [ScratchpadRepository.loadPage]. */
    suspend fun loadPage(pageId: String, density: Float): ScratchpadPageContent = withContext(Dispatchers.IO) {
        val layer = dao.getLayerForPage(pageId)
            ?: return@withContext ScratchpadPageContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val layerId = layer.id

        val strokes = dao.getStrokesForLayer(layerId).mapNotNull { row ->
            runCatching { LiveStroke.fromStrokeData(row.id, StrokeData.fromJson(row.data)) }.getOrNull()
        }

        val headings = dao.getHeadingsForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { HeadingObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            HeadingStroke(id = row.id, boundingBox = box, strokes = obj.strokes, recognizedText = obj.recognizedText, level = obj.level)
        }

        val textObjects = dao.getTextObjectsForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { TextObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            TextRender(id = row.id, boundingBox = box, text = obj.text, strokes = obj.strokes)
        }

        val lineObjects = dao.getLineObjectsForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { LineObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            val startX: Float; val startY: Float; val endX: Float; val endY: Float
            when (obj.orientation) {
                LineOrientation.HORIZONTAL -> {
                    startX = box.left; endX = box.right; startY = box.centerY(); endY = box.centerY()
                }
                LineOrientation.VERTICAL -> {
                    startX = box.centerX(); endX = box.centerX(); startY = box.top; endY = box.bottom
                }
            }
            LineRender(row.id, box, startX, startY, endX, endY, obj.style, obj.orientation, obj.strokeWidthDp, obj.dotSpacingDp * density)
        }

        val links = dao.getLinkObjectsForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { LinkObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            LinkRender(
                id          = row.id,
                boundingBox = box,
                target      = obj.target,
                chrome      = obj.chrome,
                strokes     = obj.strokes,
                headings    = obj.headings,
                textObjects = obj.textObjects,
                lines       = obj.lines.map { it.toLineRender(density) },
            )
        }

        val stickyNotes = dao.getStickyNotesForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { StickyNoteObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            StickyNoteRender(
                id            = row.id,
                boundingBox   = box,
                strokes       = obj.strokes,
                headings      = obj.headings,
                textObjects   = obj.textObjects,
                lines         = obj.lines.map { it.toLineRender(density) },
                shapes        = obj.shapes,
                contentWidth  = obj.contentWidth,
                contentHeight = obj.contentHeight,
            )
        }

        val shapeObjects = dao.getShapeObjectsForLayer(layerId).mapNotNull { row ->
            val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
            val obj = runCatching { ShapeObject.fromJson(row.data) }.getOrNull() ?: return@mapNotNull null
            ShapeRender.from(row.id, obj, density)
        }

        ScratchpadPageContent(strokes, headings, textObjects, lineObjects, links, stickyNotes, shapeObjects)
    }

    // ── Save strokes ──────────────────────────────────────────────────────────

    suspend fun saveStrokes(layerId: String, strokes: List<LiveStroke>) = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val entities = strokes.map { stroke ->
            val bbox = stroke.boundingBox
            CalendarEntity(
                id          = stroke.id,
                parentId    = layerId,
                boundingBox = BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(),
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                type        = "stroke",
                data        = stroke.toStrokeData(now).toJson(),
            )
        }
        db.withTransaction { dao.insertAll(entities) }
    }

    // ── Insert objects (from clipboard / transfer) ─────────────────────────────

    suspend fun insertObjects(
        layerId: String,
        content: NotesproutClipboard.ClipboardContent,
        density: Float,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            content.strokes.forEach { stroke ->
                val bbox = stroke.boundingBox
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = stroke.id,
                        parentId    = layerId,
                        boundingBox = BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = "stroke",
                        data        = stroke.toStrokeData(now).toJson(),
                    )
                )
            }
            content.headings.forEach { heading ->
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = heading.id,
                        parentId    = layerId,
                        boundingBox = heading.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_HEADING,
                        data        = HeadingObject(heading.strokes, heading.recognizedText, heading.level).toJson(),
                    )
                )
            }
            content.textObjects.forEach { textObj ->
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = textObj.id,
                        parentId    = layerId,
                        boundingBox = textObj.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_TEXT,
                        data        = TextObject(text = textObj.text, strokes = textObj.strokes).toJson(),
                    )
                )
            }
            content.lineObjects.forEach { lineObj ->
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = lineObj.id,
                        parentId    = layerId,
                        boundingBox = lineObj.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_LINE,
                        data        = LineObject(lineObj.style, lineObj.orientation, lineObj.strokeWidthDp, lineObj.dotSpacingPx / density).toJson(),
                    )
                )
            }
            content.links.forEach { link ->
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = link.id,
                        parentId    = layerId,
                        boundingBox = link.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_LINK,
                        data        = link.toLinkObject(density).toJson(),
                    )
                )
            }
            content.stickyNotes.forEach { note ->
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = note.id,
                        parentId    = layerId,
                        boundingBox = note.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_STICKY_NOTE,
                        data        = note.toStickyNoteObject(density).toJson(),
                    )
                )
            }
            content.shapeObjects.forEach { shape ->
                val shapeObj = shape.toShapeObject(density)
                dao.insertOrIgnore(
                    CalendarEntity(
                        id          = shape.id,
                        parentId    = layerId,
                        boundingBox = shape.boundingBox.toBoundingBoxJson(),
                        sortOrder   = 0,
                        createdAt   = now,
                        updatedAt   = now,
                        type        = TYPE_SHAPE,
                        data        = shapeObj.toJson(),
                    )
                )
            }
        }
    }

    // ── Serialize for calendar → notebook export ───────────────────────────────

    /**
     * Serialize already-positioned clipboard [content] into [CalendarExportChild] rows for a
     * foreign `.soil`. Mirrors [insertObjects]'s per-type serialization exactly, but produces
     * detached rows (no DB write) so the caller can insert them into another notebook. Geometry
     * is taken as-is — translate [content] (e.g. for the toolbar top-margin) before calling.
     */
    fun serializeForExport(
        content: NotesproutClipboard.ClipboardContent,
        density: Float,
    ): List<CalendarExportChild> {
        val now = System.currentTimeMillis()
        val out = ArrayList<CalendarExportChild>()
        content.strokes.forEach { stroke ->
            val bbox = stroke.boundingBox
            out += CalendarExportChild(
                type = "stroke",
                bbox = BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(),
                order = 0,
                data = stroke.toStrokeData(now).toJson(),
            )
        }
        content.headings.forEach { heading ->
            out += CalendarExportChild(
                TYPE_HEADING, heading.boundingBox.toBoundingBoxJson(), 0,
                HeadingObject(heading.strokes, heading.recognizedText, heading.level).toJson(),
            )
        }
        content.textObjects.forEach { textObj ->
            out += CalendarExportChild(
                TYPE_TEXT, textObj.boundingBox.toBoundingBoxJson(), 0,
                TextObject(text = textObj.text, strokes = textObj.strokes).toJson(),
            )
        }
        content.lineObjects.forEach { lineObj ->
            out += CalendarExportChild(
                TYPE_LINE, lineObj.boundingBox.toBoundingBoxJson(), 0,
                LineObject(lineObj.style, lineObj.orientation, lineObj.strokeWidthDp, lineObj.dotSpacingPx / density).toJson(),
            )
        }
        content.links.forEach { link ->
            out += CalendarExportChild(
                TYPE_LINK, link.boundingBox.toBoundingBoxJson(), 0,
                link.toLinkObject(density).toJson(),
            )
        }
        content.stickyNotes.forEach { note ->
            out += CalendarExportChild(
                TYPE_STICKY_NOTE, note.boundingBox.toBoundingBoxJson(), 0,
                note.toStickyNoteObject(density).toJson(),
            )
        }
        content.shapeObjects.forEach { shape ->
            out += CalendarExportChild(
                TYPE_SHAPE, shape.boundingBox.toBoundingBoxJson(), 0,
                shape.toShapeObject(density).toJson(),
            )
        }
        return out
    }

    // ── Soft delete ───────────────────────────────────────────────────────────

    suspend fun softDeleteObjects(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.withTransaction { ids.forEach { dao.softDelete(it, now) } }
    }

    // ── Undo/redo snapshot support ──────────────────────────────────────────────

    /** Snapshot of all live content objects on [layerId] (for the undo/redo history). */
    suspend fun snapshotLayer(layerId: String): List<CalendarEntity> = withContext(Dispatchers.IO) {
        dao.getAllChildrenForLayer(layerId)
    }

    /** Replace [layerId]'s children with [rows] (undo/redo restore). Page + layer rows untouched. */
    suspend fun restoreLayer(layerId: String, rows: List<CalendarEntity>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.deleteChildren(layerId)
            if (rows.isNotEmpty()) dao.insertAll(rows)
        }
    }
}
