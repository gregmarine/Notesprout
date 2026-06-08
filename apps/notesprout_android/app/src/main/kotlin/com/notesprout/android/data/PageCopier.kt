package com.notesprout.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "PageCopier"

/**
 * Mirrors the Room close path ([com.notesprout.android.NotebookActivity.closeNotebook]) for the
 * raw [SQLiteDatabase] connections used by the `*Raw` helpers: flushes the WAL back into the
 * `.soil` file and reclaims free pages so the file stays clean (CLAUDE.md "folder shows only
 * `.soil` files" rule).
 *
 * PRAGMAs that return a result set must consume the cursor — never `execSQL`.
 *
 * Call this immediately before [SQLiteDatabase.close]. Only the leftover rollback `-journal`
 * is deleted afterwards (see [cleanStrayJournal]); the `-wal`/`-shm` sidecars are NOT touched
 * here because [com.notesprout.android.NotebookActivity] keeps its own Room connection open to
 * the same file while these helpers run — SQLite removes those when that last connection closes.
 */
private fun SQLiteDatabase.checkpointAndVacuum() {
    rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() }
    rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
}

/** Deletes the empty `-journal` artifact SQLite may leave next to [notebookPath]. */
private fun cleanStrayJournal(notebookPath: String) {
    File("$notebookPath-journal").takeIf { it.exists() }?.delete()
}

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
 * Same operation as [copyPageAfter] but uses a raw [SQLiteDatabase] writable connection
 * instead of Room.
 *
 * Used by [com.notesprout.android.PageIndexActivity], which operates without a Room
 * instance so NotebookActivity's open connection is not disturbed.  SQLite WAL mode
 * safely permits this single-writer access while NotebookActivity is paused.
 *
 * Opens [notebookPath] as a writable database, performs the copy in one transaction,
 * then closes the connection.  Returns the new page's UUID, or null on any failure.
 *
 * Must be called on [Dispatchers.IO].
 */
suspend fun copyPageAfterRaw(
    sourcePageId: String,
    targetPageId: String,
    notebookPath: String,
): String? = withContext(Dispatchers.IO) {
    var db: SQLiteDatabase? = null
    try {
        db = SQLiteDatabase.openDatabase(notebookPath, null, SQLiteDatabase.OPEN_READWRITE)

        // ── Read source objects ───────────────────────────────────────────────

        data class Row(val id: String, val parentId: String, val bbox: String,
                       val order: Int, val data: String)

        fun readRow(cursor: android.database.Cursor): Row? {
            if (!cursor.moveToFirst()) return null
            return Row(
                id       = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                parentId = cursor.getString(cursor.getColumnIndexOrThrow("parentId")),
                bbox     = cursor.getString(cursor.getColumnIndexOrThrow("boundingBox")),
                order    = cursor.getInt(cursor.getColumnIndexOrThrow("order")),
                data     = cursor.getString(cursor.getColumnIndexOrThrow("data")),
            )
        }

        val sourcePage = db.rawQuery(
            "SELECT * FROM notebook WHERE id = ? LIMIT 1", arrayOf(sourcePageId)
        ).use { readRow(it) } ?: return@withContext null

        val sourceLayer = db.rawQuery(
            "SELECT * FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
            arrayOf(sourcePageId)
        ).use { readRow(it) } ?: return@withContext null

        data class ChildRow(val type: String, val bbox: String, val order: Int, val data: String)
        val sourceObjects: List<ChildRow> = db.rawQuery(
            "SELECT type, boundingBox, `order`, data FROM notebook WHERE parentId = ? AND deletedAt IS NULL ORDER BY `order` ASC",
            arrayOf(sourceLayer.id)
        ).use { c ->
            val list = mutableListOf<ChildRow>()
            while (c.moveToNext()) list.add(ChildRow(c.getString(0), c.getString(1), c.getInt(2), c.getString(3)))
            list
        }

        // ── Find insertion position ───────────────────────────────────────────

        data class PageOrder(val id: String, val order: Int)
        val allPages: List<PageOrder> = db.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        val targetIdx      = allPages.indexOfFirst { it.id == targetPageId }
        val insertionIndex = if (targetIdx >= 0) targetIdx + 1 else allPages.size

        val now        = System.currentTimeMillis()
        val newPageId  = UUID.randomUUID().toString()
        val newLayerId = UUID.randomUUID().toString()

        // ── Write in a transaction ────────────────────────────────────────────

        db.beginTransaction()
        try {
            for (i in insertionIndex until allPages.size) {
                val cv = ContentValues().apply { put("`order`", i + 1) }
                db.update("notebook", cv, "id = ?", arrayOf(allPages[i].id))
            }

            db.insert("notebook", null, ContentValues().apply {
                put("id",          newPageId)
                put("parentId",    sourcePage.parentId)
                put("type",        "page")
                put("boundingBox", sourcePage.bbox)
                put("`order`",     insertionIndex)
                put("createdAt",   now)
                put("updatedAt",   now)
                putNull("deletedAt")
                put("data",        sourcePage.data)
            })

            db.insert("notebook", null, ContentValues().apply {
                put("id",          newLayerId)
                put("parentId",    newPageId)
                put("type",        "layer")
                put("boundingBox", sourceLayer.bbox)
                put("`order`",     0)
                put("createdAt",   now)
                put("updatedAt",   now)
                putNull("deletedAt")
                put("data",        sourceLayer.data)
            })

            for (obj in sourceObjects) {
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

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        newPageId
    } catch (e: Exception) {
        Log.e(TAG, "copyPageAfterRaw failed for source=$sourcePageId target=$targetPageId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
    }
}

/**
 * Moves [pageId] to immediately after [targetPageId] by reassigning page `order` values.
 *
 * No rows are created or deleted — only `order` is updated.  Returns the ID of the page
 * that was immediately before [pageId] prior to the move (null = [pageId] was first).
 * Returns an empty string on success when [pageId] was the first page.
 * Returns null on any failure.
 *
 * Used by [com.notesprout.android.PageIndexActivity] which operates without a Room instance.
 * Must be called on [Dispatchers.IO].
 */
suspend fun movePageAfterRaw(
    pageId: String,
    targetPageId: String,
    notebookPath: String,
): String? = withContext(Dispatchers.IO) {
    var db: SQLiteDatabase? = null
    try {
        db = SQLiteDatabase.openDatabase(notebookPath, null, SQLiteDatabase.OPEN_READWRITE)

        data class PageOrder(val id: String, val order: Int)
        val allPages: MutableList<PageOrder> = db.rawQuery(
            "SELECT id, `order` FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null
        ).use { c ->
            val list = mutableListOf<PageOrder>()
            while (c.moveToNext()) list.add(PageOrder(c.getString(0), c.getInt(1)))
            list
        }

        val movingIdx = allPages.indexOfFirst { it.id == pageId }
        if (movingIdx < 0) return@withContext null

        val previousAfterPageId: String? = if (movingIdx > 0) allPages[movingIdx - 1].id else null

        val movingPage = allPages.removeAt(movingIdx)
        val targetIdx = allPages.indexOfFirst { it.id == targetPageId }
        val insertAt = if (targetIdx >= 0) targetIdx + 1 else allPages.size
        allPages.add(insertAt, movingPage)

        db.beginTransaction()
        try {
            allPages.forEachIndexed { i, page ->
                val cv = ContentValues().apply { put("`order`", i) }
                db.update("notebook", cv, "id = ?", arrayOf(page.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.checkpointAndVacuum()
        previousAfterPageId ?: ""
    } catch (e: Exception) {
        Log.e(TAG, "movePageAfterRaw failed for page=$pageId target=$targetPageId", e)
        null
    } finally {
        db?.close()
        cleanStrayJournal(notebookPath)
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
): Long? = withContext(Dispatchers.IO) {
    var db: SQLiteDatabase? = null
    try {
        db = SQLiteDatabase.openDatabase(notebookPath, null, SQLiteDatabase.OPEN_READWRITE)
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
