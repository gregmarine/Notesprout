package com.notesprout.notesprout.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.util.UUID

class SoilDatabase(private val filePath: String) {

    companion object {
        private const val TAG = "SoilDatabase"
    }

    private var db: SQLiteDatabase? = null

    fun open(): SoilDatabase {
        val d = SQLiteDatabase.openOrCreateDatabase(filePath, null)
        // PRAGMA returns a result row — must use rawQuery, not execSQL, on BOOX Android 15
        try {
            d.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "WAL pragma failed, continuing with default journal mode", e)
        }
        createTables(d)
        db = d
        return this
    }

    private fun createTables(d: SQLiteDatabase) {
        d.execSQL("""
            CREATE TABLE IF NOT EXISTS notebook_meta (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                syncVersion INTEGER NOT NULL DEFAULT 0,
                pageWidth REAL NOT NULL,
                pageHeight REAL NOT NULL
            )
        """)
        d.execSQL("""
            CREATE TABLE IF NOT EXISTS pages (
                id TEXT PRIMARY KEY,
                parentId TEXT,
                type TEXT NOT NULL,
                subtype TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                pageNumber INTEGER NOT NULL,
                width REAL NOT NULL,
                height REAL NOT NULL
            )
        """)
        d.execSQL("""
            CREATE TABLE IF NOT EXISTS layers (
                id TEXT PRIMARY KEY,
                parentId TEXT,
                type TEXT NOT NULL,
                subtype TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                isLocked INTEGER NOT NULL DEFAULT 0,
                isVisible INTEGER NOT NULL DEFAULT 1,
                opacity REAL NOT NULL DEFAULT 1.0
            )
        """)
        d.execSQL("""
            CREATE TABLE IF NOT EXISTS strokes (
                id TEXT PRIMARY KEY,
                parentId TEXT,
                type TEXT NOT NULL,
                subtype TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                points TEXT NOT NULL,
                color INTEGER NOT NULL,
                width REAL NOT NULL
            )
        """)
    }

    fun initializeNotebook(name: String, pageWidth: Double, pageHeight: Double) {
        val d = db ?: return
        val now = System.currentTimeMillis()

        val meta = NotebookMeta(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
        d.insert("notebook_meta", null, meta.toContentValues())

        val page = PageModel(
            id = UUID.randomUUID().toString(),
            parentId = null,
            createdAt = now,
            updatedAt = now,
            pageNumber = 1,
            width = pageWidth,
            height = pageHeight
        )
        d.insert("pages", null, page.toContentValues())

        val layer = LayerModel(
            id = UUID.randomUUID().toString(),
            parentId = page.id,
            createdAt = now,
            updatedAt = now
        )
        d.insert("layers", null, layer.toContentValues())
    }

    fun getNotebookMeta(): NotebookMeta? {
        val d = db ?: return null
        return d.query("notebook_meta", null, null, null, null, null, null, "1").use { c ->
            if (c.moveToFirst()) NotebookMeta.fromCursor(c) else null
        }
    }

    fun updateNotebookName(name: String) {
        val d = db ?: return
        val cv = ContentValues().apply {
            put("name", name)
            put("updatedAt", System.currentTimeMillis())
        }
        d.update("notebook_meta", cv, null, null)
    }

    fun getFirstPage(): PageModel? {
        val d = db ?: return null
        return d.query("pages", null, "deletedAt IS NULL", null, null, null, "pageNumber ASC", "1").use { c ->
            if (c.moveToFirst()) PageModel.fromCursor(c) else null
        }
    }

    fun getAllPages(): List<PageModel> {
        val d = db ?: return emptyList()
        return d.query("pages", null, "deletedAt IS NULL", null, null, null, "pageNumber ASC").use { c ->
            val result = mutableListOf<PageModel>()
            while (c.moveToNext()) result.add(PageModel.fromCursor(c))
            result
        }
    }

    fun addPage(pageWidth: Double, pageHeight: Double): PageModel {
        val d = db ?: throw IllegalStateException("Database not open")
        val now = System.currentTimeMillis()
        val maxPageNum = d.rawQuery("SELECT MAX(pageNumber) FROM pages WHERE deletedAt IS NULL", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val page = PageModel(
            id = UUID.randomUUID().toString(),
            parentId = null,
            createdAt = now,
            updatedAt = now,
            pageNumber = maxPageNum + 1,
            width = pageWidth,
            height = pageHeight
        )
        d.insert("pages", null, page.toContentValues())
        val layer = LayerModel(
            id = UUID.randomUUID().toString(),
            parentId = page.id,
            createdAt = now,
            updatedAt = now
        )
        d.insert("layers", null, layer.toContentValues())
        return page
    }

    fun deletePage(pageId: String) {
        val d = db ?: return
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("deletedAt", now)
            put("updatedAt", now)
        }
        d.update("pages", cv, "id = ?", arrayOf(pageId))
    }

    fun getLayersForPage(pageId: String): List<LayerModel> {
        val d = db ?: return emptyList()
        return d.query("layers", null, "parentId = ? AND deletedAt IS NULL", arrayOf(pageId), null, null, null).use { c ->
            val result = mutableListOf<LayerModel>()
            while (c.moveToNext()) result.add(LayerModel.fromCursor(c))
            result
        }
    }

    fun softDeleteStrokesForLayer(layerId: String) {
        val d = db ?: return
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("deletedAt", now)
            put("updatedAt", now)
        }
        d.update("strokes", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(layerId))
    }

    fun softDeleteLayersForPage(pageId: String) {
        val d = db ?: return
        val layers = getLayersForPage(pageId)
        layers.forEach { softDeleteStrokesForLayer(it.id) }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("deletedAt", now)
            put("updatedAt", now)
        }
        d.update("layers", cv, "parentId = ? AND deletedAt IS NULL", arrayOf(pageId))
    }

    fun getFirstLayer(pageId: String): LayerModel? {
        val d = db ?: return null
        return d.query("layers", null, "parentId = ? AND deletedAt IS NULL", arrayOf(pageId), null, null, null, "1").use { c ->
            if (c.moveToFirst()) LayerModel.fromCursor(c) else null
        }
    }

    fun getStrokes(layerId: String): List<StrokeModel> {
        val d = db ?: return emptyList()
        return d.query("strokes", null, "parentId = ? AND deletedAt IS NULL", arrayOf(layerId), null, null, "createdAt ASC").use { c ->
            val result = mutableListOf<StrokeModel>()
            while (c.moveToNext()) result.add(StrokeModel.fromCursor(c))
            result
        }
    }

    fun addStroke(stroke: StrokeModel) {
        val d = db ?: run {
            Log.w(TAG, "addStroke called before open()")
            return
        }
        d.insert("strokes", null, stroke.toContentValues())
    }

    fun deleteStroke(strokeId: String) {
        val d = db ?: return
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("deletedAt", now)
            put("updatedAt", now)
        }
        d.update("strokes", cv, "id = ?", arrayOf(strokeId))
    }

    fun deleteLayer(layerId: String) {
        val d = db ?: return
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("deletedAt", now)
            put("updatedAt", now)
        }
        d.update("layers", cv, "id = ?", arrayOf(layerId))
    }

    fun restoreStroke(strokeId: String) {
        val d = db ?: return
        val cv = ContentValues().apply {
            putNull("deletedAt")
            put("updatedAt", System.currentTimeMillis())
        }
        d.update("strokes", cv, "id = ?", arrayOf(strokeId))
    }

    fun restorePage(pageId: String) {
        val d = db ?: return
        val cv = ContentValues().apply {
            putNull("deletedAt")
            put("updatedAt", System.currentTimeMillis())
        }
        d.update("pages", cv, "id = ?", arrayOf(pageId))
    }

    fun restoreLayer(layerId: String) {
        val d = db ?: return
        val cv = ContentValues().apply {
            putNull("deletedAt")
            put("updatedAt", System.currentTimeMillis())
        }
        d.update("layers", cv, "id = ?", arrayOf(layerId))
    }

    fun getNonDeletedStrokesForLayer(layerId: String): List<StrokeModel> = getStrokes(layerId)

    fun close() {
        db?.close()
        db = null
    }
}
