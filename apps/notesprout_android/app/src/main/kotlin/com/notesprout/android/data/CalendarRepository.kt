package com.notesprout.android.data

import androidx.room.withTransaction
import com.notesprout.android.NotesproutClipboard
import com.notesprout.android.data.index.CALENDAR_ROOT_ID
import com.notesprout.android.data.index.CalendarDao
import com.notesprout.android.data.index.CalendarEntity
import com.notesprout.android.data.index.NotesproutDatabase
import com.notesprout.android.data.index.toCalendarEntity
import com.notesprout.android.data.index.toNotebookObject
import com.notesprout.android.data.index.updateColumns
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
                        data        = "",
                        refId       = "",
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
                        data        = "",
                        text        = "Content",
                        flags       = LAYER_FLAGS_DEFAULT,
                    )
                )
            } else {
                layerId = dao.getLayerForPage(pageKey)!!.id
            }
        }
        pageKey to layerId
    }

    // ── Templates (day-detail pages) ────────────────────────────────────────────

    /**
     * Copy a template image into the calendar table as a `type="template"` row (parent =
     * calendar root) and return its id. Mirrors the notebook's "copy library template into the
     * .soil" model so a day page keeps its ruling even if the source library template is deleted.
     */
    suspend fun insertTemplateRow(name: String, width: Int, height: Int, imageBase64: String): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.insertObject(
                CalendarEntity(
                    id          = id,
                    parentId    = CALENDAR_ROOT_ID,
                    boundingBox = BoundingBox(0f, 0f, width.toFloat(), height.toFloat()).toJson(),
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "template",
                    data        = "",
                    text        = name,
                    blob        = templateImageBlob(imageBase64),
                )
            )
            id
        }

    /** A single non-deleted calendar template row, or null. */
    suspend fun getTemplateById(id: String): CalendarEntity? = withContext(Dispatchers.IO) {
        dao.getTemplateById(id)
    }

    /** Set (or clear, with "") the [templateId] on a page row, preserving its size + snapshot. */
    suspend fun setPageTemplate(pageId: String, templateId: String) = withContext(Dispatchers.IO) {
        // Columnar: template → refId, data cleared. Size stays in boundingBox.
        dao.updatePageTemplate(pageId, templateId, System.currentTimeMillis())
    }

    // ── Page size ─────────────────────────────────────────────────────────────

    suspend fun setPageSize(pageId: String, w: Float, h: Float) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val pageRow = dao.getObjectById(pageId) ?: return@withContext
        // Preserve any template (refId) while updating the size (boundingBox) columnar.
        val template = pageRow.toNotebookObject().pageData().template
        val bboxJson = BoundingBox(0f, 0f, w, h).toJson()
        dao.updatePageSizeColumnar(pageId, bboxJson, template, now)
    }

    // ── Load page ─────────────────────────────────────────────────────────────

    /** Load all content for [pageId]. Mirrors [ScratchpadRepository.loadPage]. */
    suspend fun loadPage(pageId: String, density: Float): ScratchpadPageContent = withContext(Dispatchers.IO) {
        val layer = dao.getLayerForPage(pageId)
            ?: return@withContext ScratchpadPageContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val layerId = layer.id

        // Format-agnostic reads via the shared Phase-1 columnar mappings (binary strokes + columnar
        // composites when present, legacy JSON otherwise). Rows carry through a NotebookObject copy.
        val strokes = dao.getStrokesForLayer(layerId).mapNotNull { LiveStroke.fromRow(it.toNotebookObject()) }
        val headings = dao.getHeadingsForLayer(layerId).mapNotNull { it.toNotebookObject().toHeadingStroke() }
        val textObjects = dao.getTextObjectsForLayer(layerId).mapNotNull { it.toNotebookObject().toTextRender() }
        val lineObjects = dao.getLineObjectsForLayer(layerId).mapNotNull { it.toNotebookObject().toLineRender(density) }
        val links = dao.getLinkObjectsForLayer(layerId).mapNotNull { it.toNotebookObject().toLinkRender(density) }
        val stickyNotes = dao.getStickyNotesForLayer(layerId).mapNotNull { it.toNotebookObject().toStickyNoteRender(density) }
        val shapeObjects = dao.getShapeObjectsForLayer(layerId).mapNotNull { it.toNotebookObject().toShapeRender(density) }

        ScratchpadPageContent(strokes, headings, textObjects, lineObjects, links, stickyNotes, shapeObjects)
    }

    // ── Save strokes ──────────────────────────────────────────────────────────

    suspend fun saveStrokes(layerId: String, strokes: List<LiveStroke>) = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val entities = strokes.map { it.toStrokeRow(layerId, 0, now, now).toCalendarEntity() }
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
            content.strokes.forEach { dao.insertOrIgnore(it.toStrokeRow(layerId, 0, now, now).toCalendarEntity()) }
            content.headings.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now).toCalendarEntity()) }
            content.textObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now).toCalendarEntity()) }
            content.lineObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toCalendarEntity()) }
            content.links.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toCalendarEntity()) }
            content.stickyNotes.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toCalendarEntity()) }
            content.shapeObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toCalendarEntity()) }
        }
    }

    // ── Persist a lasso move (columnar in-place update) ─────────────────────────

    /**
     * Persist the new positions of objects moved by a lasso drag. Uses the columnar in-place update
     * (strokes via their blob, everything else via [updateColumns]) so the move survives a page flip
     * or reopen — the JSON-only `updateObjectData` path silently no-ops for columnar rows.
     */
    suspend fun persistMovedObjects(
        layerId: String,
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        lineObjects: List<LineRender>,
        links: List<LinkRender>,
        stickyNotes: List<StickyNoteRender>,
        shapeObjects: List<ShapeRender>,
        density: Float,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            strokes.forEach { dao.convertStrokeToBlobKeepingTimestamp(it.id, it.strokeBlob(), it.color, it.strokeWidth) }
            headings.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now)) }
            textObjects.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now)) }
            lineObjects.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now, density)) }
            links.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now, density)) }
            stickyNotes.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now, density)) }
            shapeObjects.forEach { dao.updateColumns(it.toRow(layerId, 0, now, now, density)) }
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
        val out = ArrayList<CalendarExportChild>()
        content.strokes.forEach { stroke ->
            val bbox = stroke.boundingBox
            out += CalendarExportChild(
                type = "stroke",
                bbox = BoundingBox(bbox.left, bbox.top, bbox.width(), bbox.height()).toJson(),
                order = 0,
                data = stroke.toStrokeData().toJson(),
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
