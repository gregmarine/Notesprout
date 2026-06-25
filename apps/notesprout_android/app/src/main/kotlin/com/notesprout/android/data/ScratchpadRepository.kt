package com.notesprout.android.data

import androidx.room.withTransaction
import com.notesprout.android.NotesproutClipboard
import com.notesprout.android.data.index.SCRATCHPAD_ROOT_ID
import com.notesprout.android.data.index.NotesproutDatabase
import com.notesprout.android.data.index.ScratchpadDao
import com.notesprout.android.data.index.ScratchpadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ScratchpadPageContent(
    val strokes: List<LiveStroke>,
    val headings: List<HeadingStroke>,
    val textObjects: List<TextRender>,
    val lineObjects: List<LineRender>,
    val links: List<LinkRender>,
)

class ScratchpadRepository(
    private val db: NotesproutDatabase,
    private val dao: ScratchpadDao,
) {

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /** Ensures the root, one page, and one layer exist. Safe to call repeatedly. */
    suspend fun ensureBootstrap() = withContext(Dispatchers.IO) {
        if (dao.getRootCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val emptyBbox = BoundingBox(0f, 0f, 0f, 0f).toJson()

        db.withTransaction {
            dao.insertObject(
                ScratchpadEntity(
                    id          = SCRATCHPAD_ROOT_ID,
                    parentId    = "",
                    boundingBox = emptyBbox,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "scratchpad_root",
                    data        = "{}",
                )
            )

            val pageId = UUID.randomUUID().toString()
            dao.insertObject(
                ScratchpadEntity(
                    id          = pageId,
                    parentId    = SCRATCHPAD_ROOT_ID,
                    boundingBox = emptyBbox,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "page",
                    data        = PageData(width = 0f, height = 0f, template = "").toJson(),
                )
            )

            dao.insertObject(
                ScratchpadEntity(
                    id          = UUID.randomUUID().toString(),
                    parentId    = pageId,
                    boundingBox = emptyBbox,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "layer",
                    data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
                )
            )
        }
    }

    // ── Page queries ──────────────────────────────────────────────────────────

    suspend fun getPages(): List<ScratchpadEntity> = withContext(Dispatchers.IO) {
        dao.getPagesSorted(SCRATCHPAD_ROOT_ID)
    }

    suspend fun getLayerForPage(pageId: String): ScratchpadEntity? = withContext(Dispatchers.IO) {
        dao.getLayerForPage(pageId)
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

    /**
     * Load all content for [pageId] and deserialize into render models.
     * [density] inflates link-embedded lines from dp → px.
     * // keep in sync with NotebookActivity.loadHeadingsFromDb / loadTextObjectsFromDb etc.
     */
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

        ScratchpadPageContent(strokes, headings, textObjects, lineObjects, links)
    }

    // ── Save strokes ──────────────────────────────────────────────────────────

    suspend fun saveStrokes(layerId: String, strokes: List<LiveStroke>) = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val entities = strokes.map { stroke ->
            val bbox = stroke.boundingBox
            ScratchpadEntity(
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
                    ScratchpadEntity(
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
                    ScratchpadEntity(
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
                    ScratchpadEntity(
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
                    ScratchpadEntity(
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
                    ScratchpadEntity(
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
        }
    }

    // ── Soft delete ───────────────────────────────────────────────────────────

    suspend fun softDeleteObjects(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.withTransaction { ids.forEach { dao.softDelete(it, now) } }
    }

    // ── Add / delete page ─────────────────────────────────────────────────────

    /** Insert a new blank page after [afterIndex]. Returns the new page's id. */
    suspend fun addPage(afterIndex: Int): String = withContext(Dispatchers.IO) {
        val pages = dao.getPagesSorted(SCRATCHPAD_ROOT_ID)
        val now = System.currentTimeMillis()
        val emptyBbox = BoundingBox(0f, 0f, 0f, 0f).toJson()
        val insertAt = (afterIndex + 1).coerceIn(0, pages.size)
        val newPageId = UUID.randomUUID().toString()

        db.withTransaction {
            for (i in insertAt until pages.size) {
                dao.updateOrder(pages[i].id, i + 1)
            }
            dao.insertObject(
                ScratchpadEntity(
                    id          = newPageId,
                    parentId    = SCRATCHPAD_ROOT_ID,
                    boundingBox = emptyBbox,
                    sortOrder   = insertAt,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "page",
                    data        = PageData(width = 0f, height = 0f, template = "").toJson(),
                )
            )
            dao.insertObject(
                ScratchpadEntity(
                    id          = UUID.randomUUID().toString(),
                    parentId    = newPageId,
                    boundingBox = emptyBbox,
                    sortOrder   = 0,
                    createdAt   = now,
                    updatedAt   = now,
                    type        = "layer",
                    data        = """{"label":"Content","isLocked":false,"isVisible":true}""",
                )
            )
        }

        newPageId
    }

    /**
     * Soft-delete [pageId] and its children.
     * If it is the last page, clears content only (keeps the page row) so there is always ≥1 page.
     */
    suspend fun deletePage(pageId: String) = withContext(Dispatchers.IO) {
        val pages = dao.getPagesSorted(SCRATCHPAD_ROOT_ID)
        val now = System.currentTimeMillis()

        if (pages.size <= 1) {
            val layer = dao.getLayerForPage(pageId) ?: return@withContext
            db.withTransaction { dao.softDeleteByParentId(layer.id, now) }
            return@withContext
        }

        val layer = dao.getLayerForPage(pageId)
        db.withTransaction {
            if (layer != null) {
                dao.softDeleteByParentId(layer.id, now)
                dao.softDelete(layer.id, now)
            }
            dao.softDelete(pageId, now)
            val remaining = dao.getPagesSorted(SCRATCHPAD_ROOT_ID)
            remaining.forEachIndexed { index, page -> dao.updateOrder(page.id, index) }
        }
    }
}
