package com.notesprout.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Deep-copies [sourcePageId] and inserts the duplicate immediately after [targetPageId].
 *
 * Copies: the page row, its non-deleted layer, and all non-deleted strokes for that layer.
 * The `data` JSON (including any snapshot) is preserved verbatim — the snapshot is still
 * valid since the strokes are identical.
 *
 * Soft-deleted objects are not copied.
 *
 * Returns the new page's UUID, or null if the source page or its layer cannot be found.
 * Must be called on [Dispatchers.IO] (enforced internally by [withTransaction]).
 *
 * Used by [com.notesprout.android.DrawingActivity] which holds the Room connection.
 */
suspend fun copyPageAfter(
    sourcePageId: String,
    targetPageId: String,
    db: SoilDatabase,
): String? {
    val dao = db.notebookDao()

    val sourcePage   = dao.getObjectById(sourcePageId) ?: return null
    val sourceLayer  = dao.getLayerForPage(sourcePageId) ?: return null
    val sourceStrokes = dao.getStrokesForLayer(sourceLayer.id)

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
        for (stroke in sourceStrokes) {
            dao.insertObject(stroke.copy(
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
 * instance so DrawingActivity's open connection is not disturbed.  SQLite WAL mode
 * safely permits this single-writer access while DrawingActivity is paused.
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

        data class StrokeRow(val bbox: String, val order: Int, val data: String)
        val sourceStrokes: List<StrokeRow> = db.rawQuery(
            "SELECT boundingBox, `order`, data FROM notebook WHERE type = 'stroke' AND parentId = ? AND deletedAt IS NULL ORDER BY `order` ASC",
            arrayOf(sourceLayer.id)
        ).use { c ->
            val list = mutableListOf<StrokeRow>()
            while (c.moveToNext()) list.add(StrokeRow(c.getString(0), c.getInt(1), c.getString(2)))
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

            for (stroke in sourceStrokes) {
                db.insert("notebook", null, ContentValues().apply {
                    put("id",          UUID.randomUUID().toString())
                    put("parentId",    newLayerId)
                    put("type",        "stroke")
                    put("boundingBox", stroke.bbox)
                    put("`order`",     stroke.order)
                    put("createdAt",   now)
                    put("updatedAt",   now)
                    putNull("deletedAt")
                    put("data",        stroke.data)
                })
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        newPageId
    } catch (_: Exception) {
        null
    } finally {
        db?.close()
    }
}
