package com.notesprout.android.data

import android.content.ContentValues
import android.util.Log
import androidx.room.withTransaction
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilRawDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "PageCopier"

/** Deletes the empty `-journal` artifact SQLite may leave next to [notebookPath]. */
private fun cleanStrayJournal(notebookPath: String) {
    File("$notebookPath-journal").takeIf { it.exists() }?.delete()
}

/**
 * Result of [movePagesAcrossNotebooks]: the destination insertions (same shape as
 * [copyPagesAcrossNotebooks]) plus the data needed to build a source-side restore undo action.
 */
data class MoveAcrossResult(
    val destInsertions: List<Pair<String, Int>>,
    val sourceDeletedAt: Long,
    val sourceDeletedPageIds: List<String>,
)

/**
 * Deep-copies [sourcePageId] and inserts the duplicate immediately after [targetPageId].
 *
 * Copies: the page row, its non-deleted layer, and all non-deleted content objects for that
 * layer (strokes, headings, and any future types).  The `data` JSON (including any snapshot)
 * is preserved verbatim — the snapshot is still valid since the content is identical.
 *
 * Soft-deleted objects are not copied.
 *
 * Returns the new page's UUID, or null if the source page or its layer cannot be found.
 * Must be called on [Dispatchers.IO] (enforced internally by [withTransaction]).
 *
 * Used by [com.notesprout.android.NotebookActivity] which holds the Room connection.
 */
suspend fun copyPageAfter(
    sourcePageId: String,
    targetPageId: String,
    db: SoilDatabase,
): String? {
    val dao = db.notebookDao()

    val sourcePage    = dao.getObjectById(sourcePageId) ?: return null
    val sourceLayer   = dao.getLayerForPage(sourcePageId) ?: return null
    val sourceObjects = dao.getObjectsByParent(sourceLayer.id)

    val allPages       = dao.getPagesSorted()
    val targetIdx      = allPages.indexOfFirst { it.id == targetPageId }
    val insertionIndex = if (targetIdx >= 0) targetIdx + 1 else allPages.size

    val now        = System.currentTimeMillis()
    val newPageId  = UUID.randomUUID().toString()
    val newLayerId = UUID.randomUUID().toString()

    db.withTransaction {
        for (i in insertionIndex until allPages.size) {
            dao.updateOrder(allPages[i].id, i + 1)
        }
        dao.insertObject(sourcePage.copy(
            id        = newPageId,
            sortOrder = insertionIndex,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ))
        dao.insertObject(sourceLayer.copy(
            id        = newLayerId,
            parentId  = newPageId,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ))
        for (obj in sourceObjects) {
            dao.insertObject(obj.copy(
                id        = UUID.randomUUID().toString(),
                parentId  = newLayerId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ))
        }
    }

    return newPageId
}

/**
 * Moves the pages listed in [pageIds] so they form a contiguous block immediately before or after
 * [targetPageId]. The block is ordered by the pages' original document order (NOT selection order),
 * so undo/redo predecessor chaining is consistent regardless of how the user built the selection.
 *
 * Algorithm:
 *  1. Read the full current page order.
 *  2. Remove all [pageIds] entries from that list.
 *  3. Find [targetPageId] in the remaining list; splice the block in before or after it.
 *  4. Rewrite `order` for every page in a single transaction.
 *
 * Returns one [Triple] per moved page, ordered by original document order:
 * (pageId, previousAfterPageId, newAfterPageId), compatible with
 * [com.notesprout.android.history.UndoRedoAction.MovedPageRef] and the movedActions extras used by
 * PageIndexActivity / NotebookActivity.
 * - [previousAfterPageId] = the page immediately before this page in the ORIGINAL order
 *   (null / "" when it was the first page), used by undo to restore the original position.
 * - newAfterPageId = the page immediately before this page in the FINAL (post-move) order
 *   (null / "" when it became the first page), used by redo to re-apply the move.
 *
 * Undo/redo scheme: NotebookActivity pushes ONE PagesMoved per move operation. Because the result
 * is in original order and the moved block is contiguous in the final order, both undo and redo
 * process the list FORWARD: undo moves each page after its original predecessor, redo moves each
 * page after its new predecessor. Forward processing guarantees a page's predecessor (if it is also
 * in the block) is already in place before the page is moved relative to it. Cross-operation
 * ordering is handled by the undo stack (one PagesMoved per op).
 *
 * Returns null on any failure. Must be called on [Dispatchers.IO].
 */
suspend fun movePagesRelativeRaw(
    pageIds: List<String>,
    targetPageId: String,
    before: Boolean,
    notebookPath: String,
    passphrase: String? = null,
): List<Triple<String, String?, String?>>? = withContext(Dispatchers.IO) {
    if (pageIds.isEmpty()) return@withContext emptyList()
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)

        data class PageOrder(val id: String, val order: Int)
        val allPages: MutableList<PageOrder> = db.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        // Build a map of id → page that was immediately before it in the original order
        // (null if it was the first page). We need this to construct undo data.
        val originalPredecessor = mutableMapOf<String, String?>()
        allPages.forEachIndexed { i, p ->
            originalPredecessor[p.id] = if (i > 0) allPages[i - 1].id else null
        }

        // Remove the moved pages from the list; keep a stable set for quick lookup.
        val pageIdSet = pageIds.toSet()
        val remaining = allPages.filter { it.id !in pageIdSet }.toMutableList()

        // Find target in the remaining list.
        val targetIdx = remaining.indexOfFirst { it.id == targetPageId }
        val insertAt  = if (targetIdx >= 0) {
            if (before) targetIdx else targetIdx + 1
        } else remaining.size  // target not found (edge case): append

        // Splice the block in ORIGINAL document order (not selection order) so undo/redo
        // predecessor chaining is deterministic.
        val block = allPages.filter { it.id in pageIdSet }   // already in original order
        block.forEachIndexed { i, row ->
            remaining.add(insertAt + i, row)
        }

        // Rewrite order values. `remaining` is now the final order.
        db.beginTransaction()
        try {
            remaining.forEachIndexed { i, page ->
                val cv = ContentValues().apply { put("`order`", i) }
                db.update("notebook", cv, "id = ?", arrayOf(page.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()

        // Compute each moved page's predecessor in the FINAL order (for redo).
        val newPredecessor = mutableMapOf<String, String?>()
        remaining.forEachIndexed { i, p ->
            newPredecessor[p.id] = if (i > 0) remaining[i - 1].id else null
        }

        // Build undo/redo triples in ORIGINAL document order:
        //   (pageId, originalPredecessor [undo], newPredecessor [redo]).
        val result = block.map { row ->
            Triple(row.id, originalPredecessor[row.id], newPredecessor[row.id])
        }
        result
    } catch (e: Exception) {
        Log.e(TAG, "movePagesRelativeRaw failed pages=$pageIds target=$targetPageId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Deep-copies each page in [sourcePageIds] and inserts the copies as a contiguous block
 * immediately before or after [targetPageId], in [sourcePageIds] order.
 *
 * Each copy includes: the page row, its non-deleted layer, and all non-deleted content objects
 * for that layer. The `data` JSON is preserved verbatim (including any snapshot).
 * Soft-deleted objects are not copied.
 *
 * Returns one [Pair] per new page: (newPageId, newPageIndex after insertion), compatible with
 * [com.notesprout.android.history.UndoRedoAction.PagePasted] and the pastedActions extras used
 * by PageIndexActivity / NotebookActivity. The indices are 0-based positions in the notebook
 * after all copies have been inserted.
 *
 * Returns null on any failure. Must be called on [Dispatchers.IO].
 */
suspend fun copyPagesRelativeRaw(
    sourcePageIds: List<String>,
    targetPageId: String,
    before: Boolean,
    notebookPath: String,
    passphrase: String? = null,
): List<Pair<String, Int>>? = withContext(Dispatchers.IO) {
    if (sourcePageIds.isEmpty()) return@withContext emptyList()
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)

        // ── Shared row types ─────────────────────────────────────────────────
        data class Row(val id: String, val parentId: String, val bbox: String,
                       val order: Int, val data: String, val type: String)
        data class ChildRow(val type: String, val bbox: String, val order: Int, val data: String)

        fun readRow(c: android.database.Cursor): Row? {
            if (!c.moveToFirst()) return null
            return Row(
                id       = c.getString(c.getColumnIndexOrThrow("id")),
                parentId = c.getString(c.getColumnIndexOrThrow("parentId")),
                bbox     = c.getString(c.getColumnIndexOrThrow("boundingBox")),
                order    = c.getInt(c.getColumnIndexOrThrow("order")),
                data     = c.getString(c.getColumnIndexOrThrow("data")),
                type     = c.getString(c.getColumnIndexOrThrow("type")),
            )
        }

        // ── Read source pages ────────────────────────────────────────────────
        data class SourcePage(val page: Row, val layer: Row, val children: List<ChildRow>)
        val sources = sourcePageIds.mapNotNull { srcId ->
            val page = db.rawQuery(
                "SELECT * FROM notebook WHERE id = ? LIMIT 1", arrayOf(srcId)
            ).use { readRow(it) } ?: return@mapNotNull null

            val layer = db.rawQuery(
                "SELECT * FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
                arrayOf(srcId)
            ).use { readRow(it) } ?: return@mapNotNull null

            val children: List<ChildRow> = db.rawQuery(
                "SELECT type, boundingBox, `order`, data FROM notebook WHERE parentId = ? AND deletedAt IS NULL ORDER BY `order` ASC",
                arrayOf(layer.id)
            ).use { c ->
                val list = mutableListOf<ChildRow>()
                while (c.moveToNext()) list.add(ChildRow(c.getString(0), c.getString(1), c.getInt(2), c.getString(3)))
                list
            }
            SourcePage(page, layer, children)
        }
        if (sources.isEmpty()) return@withContext null

        // ── Find current page order ──────────────────────────────────────────
        data class PageOrder(val id: String, val order: Int)
        val allPages: MutableList<PageOrder> = db.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        val targetIdx = allPages.indexOfFirst { it.id == targetPageId }
        // insertionIndex: the 0-based slot where the first new page goes
        val insertionIndex = if (targetIdx >= 0) {
            if (before) targetIdx else targetIdx + 1
        } else allPages.size

        val now         = System.currentTimeMillis()
        val newPageIds  = mutableListOf<String>()

        // ── Write in a single transaction ─────────────────────────────────────
        db.beginTransaction()
        try {
            // Shift existing pages at or after insertionIndex up by sourcePageIds.size slots.
            val shift = sources.size
            for (i in (insertionIndex until allPages.size).reversed()) {
                val cv = ContentValues().apply { put("`order`", i + shift) }
                db.update("notebook", cv, "id = ?", arrayOf(allPages[i].id))
            }

            // Insert each copy in order.
            sources.forEachIndexed { offset, src ->
                val newPageId  = UUID.randomUUID().toString()
                val newLayerId = UUID.randomUUID().toString()
                newPageIds.add(newPageId)

                db.insert("notebook", null, ContentValues().apply {
                    put("id",          newPageId)
                    put("parentId",    src.page.parentId)
                    put("type",        "page")
                    put("boundingBox", src.page.bbox)
                    put("`order`",     insertionIndex + offset)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        src.page.data)
                })

                db.insert("notebook", null, ContentValues().apply {
                    put("id",          newLayerId)
                    put("parentId",    newPageId)
                    put("type",        "layer")
                    put("boundingBox", src.layer.bbox)
                    put("`order`",     0)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        src.layer.data)
                })

                for (obj in src.children) {
                    db.insert("notebook", null, ContentValues().apply {
                        put("id",          UUID.randomUUID().toString())
                        put("parentId",    newLayerId)
                        put("type",        obj.type)
                        put("boundingBox", obj.bbox)
                        put("`order`",     obj.order)
                        put("createdAt",   now)
                        put("updatedAt",   now)
                        putNull("deletedAt")
                        put("data",        obj.data)
                    })
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()

        // The new pages occupy indices [insertionIndex .. insertionIndex + sources.size - 1]
        // in the post-insert notebook.
        val result = newPageIds.mapIndexed { offset, id ->
            id to (insertionIndex + offset)
        }
        result
    } catch (e: Exception) {
        Log.e(TAG, "copyPagesRelativeRaw failed sources=$sourcePageIds target=$targetPageId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Inserts a `type="template"` row into the `.soil` at [notebookPath], mirroring
 * [com.notesprout.android.NotebookActivity.insertLibraryTemplateIntoSoil] but via a raw
 * [SQLiteDatabase] connection (PageIndexActivity has no Room instance).
 *
 * A page's `template` property stores a `.soil` template-row id, NOT a global-index library id, so
 * applying a library template from the index requires copying the library image into the `.soil`
 * first. One row is inserted per Set-Template operation and shared by every selected page.
 *
 * [parentId] should be the notebook metadata row id (`type="notebook"`); template resolution
 * ([com.notesprout.android.data.NotebookDao.getTemplateById]) is by id only, so the parent is
 * cosmetic — callers pass the metadata id when available.
 *
 * Returns the new template row's UUID, or null on failure. Must be called on [Dispatchers.IO].
 */
suspend fun insertSoilTemplateRaw(
    notebookPath: String,
    parentId: String,
    width: Int,
    height: Int,
    name: String,
    imageBase64: String,
    passphrase: String? = null,
): String? = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)
        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()
        val bbox = BoundingBox(0f, 0f, width.toFloat(), height.toFloat()).toJson()
        val data = TemplateData(width, height, name, imageBase64).toJson()

        db.beginTransaction()
        try {
            db.insert("notebook", null, ContentValues().apply {
                put("id",          newId)
                put("parentId",    parentId)
                put("type",        "template")
                put("boundingBox", bbox)
                put("`order`",     0)
                put("createdAt",   now)
                put("updatedAt",   now)
                putNull("deletedAt")
                put("data",        data)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        newId
    } catch (e: Exception) {
        Log.e(TAG, "insertSoilTemplateRaw failed", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Inserts a single blank page (and its content layer) into the .soil at [notebookPath].
 *
 * Placement:
 *  - [anchorPageId] != null  → insert immediately before/after that page per [before].
 *  - [anchorPageId] == null  → append to the end.
 *
 * The new page inherits the template id of its anchor page (or, for end-insert, the last page;
 * "" when the notebook has no pages). parentId is the notebook metadata row id (type="notebook"),
 * falling back to any existing page's parentId.
 *
 * Returns Pair(newPageId, newPageIndex) — 0-based index in the post-insert page order — or null
 * on failure. Must be called on [Dispatchers.IO].
 */
suspend fun insertBlankPageRaw(
    notebookPath: String,
    anchorPageId: String?,
    before: Boolean,
    pageWidth: Float,
    pageHeight: Float,
    passphrase: String? = null,
): Pair<String, Int>? = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)

        data class PageRow(val id: String, val parentId: String, val order: Int, val data: String)
        val allPages: List<PageRow> = db.rawQuery(
            "SELECT id, parentId, `order`, data FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageRow>()
            while (c.moveToNext()) {
                list.add(PageRow(c.getString(0), c.getString(1), c.getInt(2), c.getString(3)))
            }
            list
        }

        val anchorIdx = if (anchorPageId != null) allPages.indexOfFirst { it.id == anchorPageId } else -1
        val insertionIndex = when {
            anchorIdx >= 0 -> if (before) anchorIdx else anchorIdx + 1
            else -> allPages.size
        }

        val inheritedTemplate: String = when {
            anchorIdx >= 0 -> PageData.fromJson(allPages[anchorIdx].data).template
            allPages.isNotEmpty() -> PageData.fromJson(allPages.last().data).template
            else -> ""
        }

        val notebookParentId = db.rawQuery(
            "SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: allPages.firstOrNull()?.parentId
            ?: UUID.randomUUID().toString()

        val bbox = BoundingBox(0f, 0f, pageWidth, pageHeight).toJson()
        val pageData = PageData(width = pageWidth, height = pageHeight, template = inheritedTemplate).toJson()
        val now = System.currentTimeMillis()
        val newPageId  = UUID.randomUUID().toString()
        val newLayerId = UUID.randomUUID().toString()

        db.beginTransaction()
        try {
            for (i in (insertionIndex until allPages.size).reversed()) {
                val cv = ContentValues().apply { put("`order`", i + 1) }
                db.update("notebook", cv, "id = ?", arrayOf(allPages[i].id))
            }
            db.insert("notebook", null, ContentValues().apply {
                put("id",          newPageId)
                put("parentId",    notebookParentId)
                put("type",        "page")
                put("boundingBox", bbox)
                put("`order`",     insertionIndex)
                put("createdAt",   now)
                put("updatedAt",   now)
                putNull("deletedAt")
                put("data",        pageData)
            })
            db.insert("notebook", null, ContentValues().apply {
                put("id",          newLayerId)
                put("parentId",    newPageId)
                put("type",        "layer")
                put("boundingBox", bbox)
                put("`order`",     0)
                put("createdAt",   now)
                put("updatedAt",   now)
                putNull("deletedAt")
                put("data",        """{"label":"Content","isLocked":false,"isVisible":true}""")
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        newPageId to insertionIndex
    } catch (e: Exception) {
        Log.e(TAG, "insertBlankPageRaw failed anchor=$anchorPageId before=$before", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Reads the `type="notebook"` metadata row id from the `.soil` at [notebookPath], used as the
 * parentId for template rows inserted by [insertSoilTemplateRaw]. Returns null if absent.
 * Must be called on [Dispatchers.IO].
 */
suspend fun readNotebookRowId(notebookPath: String, passphrase: String? = null): String? = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)
        db.rawQuery("SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "readNotebookRowId failed", e)
        null
    } finally {
        db?.close()
    }
}

/**
 * Sets the `template` property of every page in [pageIds] to [newTemplateId] (a `.soil` template
 * row id, or "" to clear / make blank), rewriting each page row's `data` JSON in one transaction.
 *
 * Returns one [Pair] per successfully-updated page: (pageId, previousTemplateId), where
 * previousTemplateId is the page's prior template row id or null if it had none. This is compatible
 * with [com.notesprout.android.history.UndoRedoAction.TemplateChanged] and the templateChanges
 * extras used by PageIndexActivity / NotebookActivity. Pages not found are skipped.
 *
 * Returns null on any failure. Must be called on [Dispatchers.IO].
 */
suspend fun setPagesTemplateRaw(
    pageIds: List<String>,
    newTemplateId: String,
    notebookPath: String,
    passphrase: String? = null,
): List<Pair<String, String?>>? = withContext(Dispatchers.IO) {
    if (pageIds.isEmpty()) return@withContext emptyList()
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)
        val now = System.currentTimeMillis()
        val result = mutableListOf<Pair<String, String?>>()

        db.beginTransaction()
        try {
            for (pageId in pageIds) {
                val oldData = db.rawQuery(
                    "SELECT data FROM notebook WHERE id = ? AND type = 'page' LIMIT 1",
                    arrayOf(pageId)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: continue

                val parsed = PageData.fromJson(oldData)
                val previousTemplateId = parsed.template.takeIf { it.isNotEmpty() }
                val newData = parsed.copy(template = newTemplateId).toJson()

                db.update("notebook", ContentValues().apply {
                    put("data",      newData)
                    put("updatedAt", now)
                }, "id = ?", arrayOf(pageId))

                result.add(pageId to previousTemplateId)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        result
    } catch (e: Exception) {
        Log.e(TAG, "setPagesTemplateRaw failed pages=$pageIds template=$newTemplateId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Soft-deletes all pages in [pageIds] (and their layers/children) in the source .soil using a
 * single shared [deletedAt]. Opens, writes, and closes [sourcePath] internally.
 * Returns true on success. Must be called on [Dispatchers.IO].
 */
private suspend fun softDeleteSourcePages(
    pageIds: List<String>,
    deletedAt: Long,
    sourcePath: String,
    sourcePass: String?,
): Boolean = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(sourcePath), sourcePass)
        val cv = ContentValues().apply {
            put("deletedAt", deletedAt)
            put("updatedAt", deletedAt)
        }
        db.beginTransaction()
        try {
            for (pageId in pageIds) {
                val layerId = db.rawQuery(
                    "SELECT id FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
                    arrayOf(pageId)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

                db.update("notebook", cv, "id = ?", arrayOf(pageId))
                db.update("notebook", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(pageId))
                if (layerId != null) {
                    db.update("notebook", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(layerId))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.checkpointAndVacuum()
        true
    } catch (e: Exception) {
        Log.e(TAG, "softDeleteSourcePages failed pages=$pageIds", e)
        false
    } finally {
        db?.close()
        cleanStrayJournal(sourcePath)
    }
}

/**
 * Soft-deletes [pageId] and all its descendants (layer + strokes) using a single shared
 * timestamp, matching the pattern used by NotebookActivity's deletePage().
 *
 * All three soft-delete calls use the same [deletedAt] so [restoreChildrenDeletedSince]
 * can recover exactly those rows on undo.
 *
 * Returns the [deletedAt] timestamp on success, or null on any failure.
 * Must be called on [Dispatchers.IO].
 */
suspend fun deletePageRaw(
    pageId: String,
    notebookPath: String,
    passphrase: String? = null,
): Long? = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)
        val deletedAt = System.currentTimeMillis()

        val layerId = db.rawQuery(
            "SELECT id FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
            arrayOf(pageId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        val cv = ContentValues().apply {
            put("deletedAt", deletedAt)
            put("updatedAt", deletedAt)
        }
        // All three soft-deletes must be atomic — a failure between them would leave a
        // half-deleted page (page row gone, children orphaned, or vice-versa).
        db.beginTransaction()
        try {
            db.update("notebook", cv, "id = ?", arrayOf(pageId))
            db.update("notebook", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(pageId))
            if (layerId != null) {
                db.update("notebook", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(layerId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        deletedAt
    } catch (e: Exception) {
        Log.e(TAG, "deletePageRaw failed for page=$pageId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Deep-copies each page in [sourcePageIds] from the `.soil` at [sourcePath] into the `.soil` at
 * [destPath], placing the copies as a contiguous block before or after [targetPageId] in the
 * destination (append to end when [targetPageId] is null or not found).
 *
 * Each copy includes: the page row, its non-deleted layer, and all non-deleted content objects.
 * Template rows referenced by any copied page are also copied into [destPath] with fresh UUIDs,
 * and each page's `data.template` is rewritten to point to the new destination template id.
 * Page content (`boundingBox`, `data` other than `template`) is preserved verbatim.
 * Soft-deleted objects are not copied.
 *
 * [sourcePath] and [destPath] must be distinct files — use [copyPagesRelativeRaw] for same-file
 * operations.
 *
 * Returns one [Pair] per new page: (newPageId, 0-based index in the destination after insertion),
 * or null on any failure. On failure the destination transaction is rolled back; no partial writes
 * remain. Must be called on [Dispatchers.IO].
 */
suspend fun copyPagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,
    destPath: String, destPass: String?,
): List<Pair<String, Int>>? = withContext(Dispatchers.IO) {
    if (sourcePageIds.isEmpty()) return@withContext emptyList()
    if (sourcePath == destPath) {
        Log.e(TAG, "copyPagesAcrossNotebooks: source == dest; use copyPagesRelativeRaw")
        return@withContext null
    }

    // ── Shared local types ────────────────────────────────────────────────
    data class Row(val id: String, val parentId: String, val bbox: String,
                   val order: Int, val data: String, val type: String)
    data class ChildRow(val type: String, val bbox: String, val order: Int, val data: String)

    fun readRow(c: android.database.Cursor): Row? {
        if (!c.moveToFirst()) return null
        return Row(
            id       = c.getString(c.getColumnIndexOrThrow("id")),
            parentId = c.getString(c.getColumnIndexOrThrow("parentId")),
            bbox     = c.getString(c.getColumnIndexOrThrow("boundingBox")),
            order    = c.getInt(c.getColumnIndexOrThrow("order")),
            data     = c.getString(c.getColumnIndexOrThrow("data")),
            type     = c.getString(c.getColumnIndexOrThrow("type")),
        )
    }

    data class SourcePage(val page: Row, val layer: Row, val children: List<ChildRow>)
    data class TemplateRow(val bbox: String, val data: String)

    var sourceDb: SoilRawDb? = null
    var destDb: SoilRawDb? = null
    try {
        // ── Read from source ──────────────────────────────────────────────
        sourceDb = SoilCrypto.openRaw(File(sourcePath), sourcePass)

        val sources = sourcePageIds.mapNotNull { srcId ->
            val page = sourceDb.rawQuery(
                "SELECT * FROM notebook WHERE id = ? LIMIT 1", arrayOf(srcId)
            ).use { readRow(it) } ?: return@mapNotNull null

            val layer = sourceDb.rawQuery(
                "SELECT * FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
                arrayOf(srcId)
            ).use { readRow(it) } ?: return@mapNotNull null

            val children: List<ChildRow> = sourceDb.rawQuery(
                "SELECT type, boundingBox, `order`, data FROM notebook WHERE parentId = ? AND deletedAt IS NULL ORDER BY `order` ASC",
                arrayOf(layer.id)
            ).use { c ->
                val list = mutableListOf<ChildRow>()
                while (c.moveToNext()) list.add(ChildRow(c.getString(0), c.getString(1), c.getInt(2), c.getString(3)))
                list
            }
            SourcePage(page, layer, children)
        }
        if (sources.isEmpty()) return@withContext null

        // Collect distinct template ids referenced by the pages being copied.
        val referencedTemplateIds = sources.mapNotNull { src ->
            PageData.fromJson(src.page.data).template.takeIf { it.isNotEmpty() }
        }.toSet()

        val sourceTemplates = mutableMapOf<String, TemplateRow>()
        for (templateId in referencedTemplateIds) {
            sourceDb.rawQuery(
                "SELECT boundingBox, data FROM notebook WHERE id = ? AND type = 'template' LIMIT 1",
                arrayOf(templateId)
            ).use { c ->
                if (c.moveToFirst()) sourceTemplates[templateId] = TemplateRow(c.getString(0), c.getString(1))
            }
        }

        sourceDb.close()
        sourceDb = null
        cleanStrayJournal(sourcePath)

        // ── Write to destination ──────────────────────────────────────────
        destDb = SoilCrypto.openRaw(File(destPath), destPass)

        // Use dest notebook metadata row as parentId for pages and templates.
        val destParentId = destDb.rawQuery(
            "SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else sources.first().page.parentId }

        data class PageOrder(val id: String, val order: Int)
        val allDestPages: MutableList<PageOrder> = destDb.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        val targetIdx = if (targetPageId != null) allDestPages.indexOfFirst { it.id == targetPageId } else -1
        val insertionIndex = if (targetIdx >= 0) {
            if (before) targetIdx else targetIdx + 1
        } else allDestPages.size

        val now = System.currentTimeMillis()
        val newPageIds = mutableListOf<String>()
        val templateRemap = mutableMapOf<String, String>()

        destDb.beginTransaction()
        try {
            // Copy template rows into dest and build the remap.
            for ((srcTemplateId, templateRow) in sourceTemplates) {
                val newTemplateId = UUID.randomUUID().toString()
                destDb.insert("notebook", null, ContentValues().apply {
                    put("id",          newTemplateId)
                    put("parentId",    destParentId)
                    put("type",        "template")
                    put("boundingBox", templateRow.bbox)
                    put("`order`",     0)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        templateRow.data)
                })
                templateRemap[srcTemplateId] = newTemplateId
            }

            // Shift existing dest pages at or after insertionIndex to make room.
            val shift = sources.size
            for (i in (insertionIndex until allDestPages.size).reversed()) {
                destDb.update("notebook", ContentValues().apply { put("`order`", i + shift) },
                    "id = ?", arrayOf(allDestPages[i].id))
            }

            // Insert each copied page, layer, and children.
            sources.forEachIndexed { offset, src ->
                val newPageId  = UUID.randomUUID().toString()
                val newLayerId = UUID.randomUUID().toString()
                newPageIds.add(newPageId)

                // Rewrite template id if this page referenced a template.
                val pageData = PageData.fromJson(src.page.data)
                val remappedData = templateRemap[pageData.template]?.let { newTplId ->
                    pageData.copy(template = newTplId).toJson()
                } ?: src.page.data

                destDb.insert("notebook", null, ContentValues().apply {
                    put("id",          newPageId)
                    put("parentId",    destParentId)
                    put("type",        "page")
                    put("boundingBox", src.page.bbox)
                    put("`order`",     insertionIndex + offset)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        remappedData)
                })

                destDb.insert("notebook", null, ContentValues().apply {
                    put("id",          newLayerId)
                    put("parentId",    newPageId)
                    put("type",        "layer")
                    put("boundingBox", src.layer.bbox)
                    put("`order`",     0)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        src.layer.data)
                })

                for (obj in src.children) {
                    destDb.insert("notebook", null, ContentValues().apply {
                        put("id",          UUID.randomUUID().toString())
                        put("parentId",    newLayerId)
                        put("type",        obj.type)
                        put("boundingBox", obj.bbox)
                        put("`order`",     obj.order)
                        put("createdAt",   now)
                        put("updatedAt",   now)
                        putNull("deletedAt")
                        put("data",        obj.data)
                    })
                }
            }

            destDb.setTransactionSuccessful()
        } finally {
            destDb.endTransaction()
        }

        destDb.checkpointAndVacuum()

        newPageIds.mapIndexed { offset, id -> id to (insertionIndex + offset) }
    } catch (e: Exception) {
        Log.e(TAG, "copyPagesAcrossNotebooks failed sources=$sourcePageIds", e)
        null
    } finally {
        sourceDb?.close()
        cleanStrayJournal(sourcePath)
        destDb?.close()
        cleanStrayJournal(destPath)
    }
}

/**
 * Moves each page in [sourcePageIds] from the `.soil` at [sourcePath] into the `.soil` at
 * [destPath], placing the block before or after [targetPageId] in the destination (append to end
 * when [targetPageId] is null or not found).
 *
 * Copies the pages and their templates first (via [copyPagesAcrossNotebooks]); only soft-deletes
 * the source rows after a successful copy. If the copy fails, the source is untouched. If the
 * soft-delete fails after a successful copy, returns null — the caller should treat this as an
 * error (pages will exist in both notebooks; the user can manually delete the duplicate).
 *
 * [sourcePath] and [destPath] must be distinct files — use [movePagesRelativeRaw] for same-file
 * operations.
 *
 * Returns [MoveAcrossResult] on success (dest insertions + source soft-delete metadata for
 * building a source-side undo action), or null on any failure. Must be called on [Dispatchers.IO].
 */
suspend fun movePagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,
    destPath: String, destPass: String?,
): MoveAcrossResult? = withContext(Dispatchers.IO) {
    if (sourcePageIds.isEmpty()) return@withContext null
    if (sourcePath == destPath) {
        Log.e(TAG, "movePagesAcrossNotebooks: source == dest; use movePagesRelativeRaw")
        return@withContext null
    }

    val destInsertions = copyPagesAcrossNotebooks(
        sourcePageIds, sourcePath, sourcePass,
        targetPageId, before, destPath, destPass,
    ) ?: return@withContext null

    val deletedAt = System.currentTimeMillis()
    val deleted = softDeleteSourcePages(sourcePageIds, deletedAt, sourcePath, sourcePass)
    if (!deleted) return@withContext null

    MoveAcrossResult(
        destInsertions     = destInsertions,
        sourceDeletedAt    = deletedAt,
        sourceDeletedPageIds = sourcePageIds,
    )
}

// ── Calendar → notebook page export ──────────────────────────────────────────

/** One content object to insert onto an exported calendar page's layer. */
data class CalendarExportChild(val type: String, val bbox: String, val order: Int, val data: String)

/**
 * One notebook page produced from a calendar view: the grid/timeline becomes the page's template
 * (a fresh `type="template"` row in the dest `.soil`, NOT a library template), at [width]×[height]
 * (the calendar canvas size). [children] are the calendar view's content objects copied verbatim
 * (empty for a template-only export).
 */
data class CalendarExportPage(
    val templateName: String,
    val templateImageBase64: String,
    val width: Int,
    val height: Int,
    val children: List<CalendarExportChild>,
)

/**
 * Reads the ordered live page ids of the notebook at [notebookPath] (used to drive the calendar
 * export's insert-position picker). Returns null on failure. Must be called on [Dispatchers.IO].
 */
suspend fun loadNotebookPageIds(
    notebookPath: String,
    passphrase: String? = null,
): List<String>? = withContext(Dispatchers.IO) {
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)
        db.rawQuery(
            "SELECT id FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null,
        ).use { c ->
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadNotebookPageIds failed for $notebookPath", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Inserts the calendar-derived [exportPages] into the `.soil` at [notebookPath] as a contiguous
 * block before/after [anchorPageId] (append to end when [anchorPageId] is null or not found).
 *
 * Each page gets a fresh `type="template"` row holding the baked grid image, a `type="page"` row
 * whose `data.template` points at it (sized to the calendar canvas), a content layer, and any
 * [CalendarExportPage.children] copied verbatim with fresh UUIDs. No library template is created.
 *
 * Returns one [Pair] per new page (newPageId, 0-based index after insertion) or null on failure.
 * On failure the transaction rolls back; no partial writes remain. Must be called on
 * [Dispatchers.IO].
 */
suspend fun insertCalendarPagesIntoNotebook(
    notebookPath: String,
    passphrase: String?,
    anchorPageId: String?,
    before: Boolean,
    exportPages: List<CalendarExportPage>,
): List<Pair<String, Int>>? = withContext(Dispatchers.IO) {
    if (exportPages.isEmpty()) return@withContext emptyList()
    var db: SoilRawDb? = null
    try {
        db = SoilCrypto.openRaw(File(notebookPath), passphrase)

        val notebookParentId = db.rawQuery(
            "SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: UUID.randomUUID().toString()

        data class PageOrder(val id: String, val order: Int)
        val allPages: List<PageOrder> = db.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null,
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        val targetIdx = if (anchorPageId != null) allPages.indexOfFirst { it.id == anchorPageId } else -1
        val insertionIndex = if (targetIdx >= 0) {
            if (before) targetIdx else targetIdx + 1
        } else allPages.size

        val now = System.currentTimeMillis()
        val newPageIds = mutableListOf<String>()

        db.beginTransaction()
        try {
            // Shift existing pages at or after the insertion slot to make room.
            val shift = exportPages.size
            for (i in (insertionIndex until allPages.size).reversed()) {
                db.update("notebook", ContentValues().apply { put("`order`", i + shift) },
                    "id = ?", arrayOf(allPages[i].id))
            }

            exportPages.forEachIndexed { offset, ep ->
                val newTemplateId = UUID.randomUUID().toString()
                val newPageId     = UUID.randomUUID().toString()
                val newLayerId    = UUID.randomUUID().toString()
                newPageIds.add(newPageId)

                val bbox = BoundingBox(0f, 0f, ep.width.toFloat(), ep.height.toFloat()).toJson()

                db.insert("notebook", null, ContentValues().apply {
                    put("id",          newTemplateId)
                    put("parentId",    notebookParentId)
                    put("type",        "template")
                    put("boundingBox", bbox)
                    put("`order`",     0)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        TemplateData(ep.width, ep.height, ep.templateName, ep.templateImageBase64).toJson())
                })

                db.insert("notebook", null, ContentValues().apply {
                    put("id",          newPageId)
                    put("parentId",    notebookParentId)
                    put("type",        "page")
                    put("boundingBox", bbox)
                    put("`order`",     insertionIndex + offset)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        PageData(width = ep.width.toFloat(), height = ep.height.toFloat(), template = newTemplateId).toJson())
                })

                db.insert("notebook", null, ContentValues().apply {
                    put("id",          newLayerId)
                    put("parentId",    newPageId)
                    put("type",        "layer")
                    put("boundingBox", bbox)
                    put("`order`",     0)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        """{"label":"Content","isLocked":false,"isVisible":true}""")
                })

                for (child in ep.children) {
                    db.insert("notebook", null, ContentValues().apply {
                        put("id",          UUID.randomUUID().toString())
                        put("parentId",    newLayerId)
                        put("type",        child.type)
                        put("boundingBox", child.bbox)
                        put("`order`",     child.order)
                        put("createdAt",   now)
                        put("updatedAt",   now)
                        putNull("deletedAt")
                        put("data",        child.data)
                    })
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        newPageIds.mapIndexed { offset, id -> id to (insertionIndex + offset) }
    } catch (e: Exception) {
        Log.e(TAG, "insertCalendarPagesIntoNotebook failed for $notebookPath", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}
