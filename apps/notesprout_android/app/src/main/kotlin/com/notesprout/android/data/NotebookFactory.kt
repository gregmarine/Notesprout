package com.notesprout.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.notesprout.android.BuildConfig
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.ObjectEntity
import java.util.UUID

/**
 * Creates a new blank, unencrypted notebook: the global-index row plus a fully initialized
 * .soil file (schema, undo_redo_state + notebook_meta tables, one blank page, one content layer,
 * notebook_meta with folder ancestry). Returns the new index ObjectEntity.
 *
 * Note: the SQL bootstrap here is intentionally duplicated from MainActivity.createNotebook —
 * that function handles the encrypted/template path which does not apply here.
 *
 * Must be called off the main thread.
 */
suspend fun createBlankNotebook(
    context: Context,
    name: String,
    parentId: String?,
    repository: IndexRepository,
    pageWidthPx: Int,
    pageHeightPx: Int,
): ObjectEntity {
    val entity = repository.createNotebook(name, parentId)
    val soilPath = soilFile(context, entity.id)

    val db = SQLiteDatabase.openOrCreateDatabase(soilPath, null)
    try {
        fun pragma(sql: String) = db.rawQuery(sql, null).use { it.moveToFirst() }
        fun exec(sql: String, args: Array<Any?>? = null) =
            if (args != null) db.execSQL(sql, args) else db.execSQL(sql)

        pragma("PRAGMA journal_mode = WAL")
        pragma("PRAGMA wal_autocheckpoint = 100")
        pragma("PRAGMA auto_vacuum = INCREMENTAL")

        exec(
            """
            CREATE TABLE IF NOT EXISTS notebook (
                id          TEXT    NOT NULL PRIMARY KEY,
                parentId    TEXT    NOT NULL,
                boundingBox TEXT    NOT NULL,
                "order"     INTEGER NOT NULL DEFAULT 0,
                createdAt   INTEGER NOT NULL,
                updatedAt   INTEGER NOT NULL,
                deletedAt   INTEGER,
                type        TEXT    NOT NULL,
                data        TEXT    NOT NULL
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
                ON notebook(parentId, "order", deletedAt)
            """.trimIndent()
        )
        exec("CREATE TABLE IF NOT EXISTS undo_redo_state (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)")

        val screenW = pageWidthPx.toFloat()
        val screenH = pageHeightPx.toFloat()
        val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()
        val now = System.currentTimeMillis()

        val insertSql = """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data)
                           VALUES (?, ?, ?, 0, ?, ?, NULL, ?, ?)"""

        val notebookRowId = UUID.randomUUID().toString()
        val pageId        = UUID.randomUUID().toString()

        val notebookDataJson = NotebookMetadata(
            id             = notebookRowId,
            title          = name,
            cover          = "",
            lastOpenedPage = pageId,
        ).toJson()
        exec(insertSql, arrayOf(notebookRowId, "", "{}", now, now, "notebook", notebookDataJson))

        exec(insertSql, arrayOf(
            pageId, notebookRowId, bboxJson, now, now, "page",
            PageData(width = screenW, height = screenH, template = "").toJson()
        ))

        val layerId = UUID.randomUUID().toString()
        exec(insertSql, arrayOf(
            layerId, pageId, bboxJson, now, now, "layer",
            """{"label":"Content","isLocked":false,"isVisible":true}"""
        ))

        val folderPath = repository.getFolderAncestry(parentId)
        val initialMeta = NotebookMeta(
            notebookId     = entity.id,
            name           = name,
            createdAt      = entity.createdAt,
            updatedAt      = entity.updatedAt,
            encrypted      = false,
            keyScope       = null,
            cover          = null,
            folderPath     = folderPath,
            appVersionCode = BuildConfig.VERSION_CODE,
        )
        exec("INSERT OR REPLACE INTO notebook_meta (id, json) VALUES (0, ?)", arrayOf(initialMeta.toJson()))

        pragma("PRAGMA incremental_vacuum")
        pragma("PRAGMA wal_checkpoint(TRUNCATE)")
    } finally {
        db.close()
    }

    java.io.File("${soilPath.absolutePath}-journal").takeIf { it.exists() }?.delete()
    return entity
}
