package com.notesprout.android.data

import androidx.room.withTransaction
import com.notesprout.android.NotesproutClipboard
import com.notesprout.android.data.index.SCRATCHPAD_ROOT_ID
import com.notesprout.android.data.index.NotesproutDatabase
import com.notesprout.android.data.index.ScratchpadDao
import com.notesprout.android.data.index.ScratchpadEntity
import com.notesprout.android.data.index.toNotebookObject
import com.notesprout.android.data.index.toScratchpadEntity
import com.notesprout.android.data.index.updateColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import com.notesprout.android.data.ShapeObject
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.TYPE_SHAPE

data class ScratchpadPageContent(
    val strokes: List<LiveStroke>,
    val headings: List<HeadingStroke>,
    val textObjects: List<TextRender>,
    val lineObjects: List<LineRender>,
    val links: List<LinkRender>,
    val stickyNotes: List<StickyNoteRender> = emptyList(),
    val shapeObjects: List<ShapeRender> = emptyList(),
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
                    data        = "",
                    refId       = "",
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
                    data        = "",
                    text        = "Content",
                    flags       = LAYER_FLAGS_DEFAULT,
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
        dao.getObjectById(pageId) ?: return@withContext
        // Columnar: size → boundingBox, data cleared (scratchpad pages have no template).
        val bboxJson = BoundingBox(0f, 0f, w, h).toJson()
        dao.updatePageSizeColumnar(pageId, bboxJson, now)
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
        val entities = strokes.map { it.toStrokeRow(layerId, 0, now, now).toScratchpadEntity() }
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
            content.strokes.forEach { dao.insertOrIgnore(it.toStrokeRow(layerId, 0, now, now).toScratchpadEntity()) }
            content.headings.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now).toScratchpadEntity()) }
            content.textObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now).toScratchpadEntity()) }
            content.lineObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toScratchpadEntity()) }
            content.links.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toScratchpadEntity()) }
            content.stickyNotes.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toScratchpadEntity()) }
            content.shapeObjects.forEach { dao.insertOrIgnore(it.toRow(layerId, 0, now, now, density).toScratchpadEntity()) }
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

    // ── Undo/redo snapshot support ──────────────────────────────────────────────

    /** Snapshot of all live content objects on [layerId] (for the undo/redo history). */
    suspend fun snapshotLayer(layerId: String): List<ScratchpadEntity> = withContext(Dispatchers.IO) {
        dao.getAllChildrenForLayer(layerId)
    }

    /** Replace [layerId]'s children with [rows] (undo/redo restore). Page + layer rows untouched. */
    suspend fun restoreLayer(layerId: String, rows: List<ScratchpadEntity>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.deleteChildren(layerId)
            if (rows.isNotEmpty()) dao.insertAll(rows)
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
                    data        = "",
                    refId       = "",
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
                    data        = "",
                    text        = "Content",
                    flags       = LAYER_FLAGS_DEFAULT,
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
