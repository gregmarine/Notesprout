package com.notesprout.android.data

import android.content.Context
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

    val db = try {
        com.notesprout.android.crypto.SoilCrypto.createRawPlaintext(soilPath)
    } catch (e: Exception) {
        // The index row went in first — take it back out rather than stranding a phantom card
        // that points at a file that never got built (disk full, IO error).
        runCatching { repository.softDeleteNotebook(entity.id) }
        throw e
    }
    try {
        fun pragma(sql: String) = db.rawQuery(sql, null).use { it.moveToFirst() }
        fun exec(sql: String, args: Array<Any?>? = null) =
            if (args != null) db.execSQL(sql, args) else db.execSQL(sql)

        pragma("PRAGMA journal_mode = WAL")
        pragma("PRAGMA wal_autocheckpoint = 100")
        pragma("PRAGMA auto_vacuum = INCREMENTAL")

        exec(SoilSchema.CREATE_NOTEBOOK_TABLE)
        exec(SoilSchema.CREATE_NOTEBOOK_INDEX)
        exec("CREATE TABLE IF NOT EXISTS undo_redo_state (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)")

        val screenW = pageWidthPx.toFloat()
        val screenH = pageHeightPx.toFloat()
        val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()
        val now = System.currentTimeMillis()

        // Columnar (Phase 2b): notebook meta, page, and layer all write typed columns, data = "".
        // Columns beyond `data`: text (notebook.title / layer.label), refId (notebook.lastOpenedPage /
        // page.template), flags (layer isLocked/isVisible bits).
        val insertSql = """INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data, text, refId, flags)
                           VALUES (?, ?, ?, 0, ?, ?, NULL, ?, '', ?, ?, ?)"""

        val notebookRowId = UUID.randomUUID().toString()
        val pageId        = UUID.randomUUID().toString()

        // notebook meta: title→text, lastOpenedPage→refId.
        exec(insertSql, arrayOf(notebookRowId, "", "{}", now, now, "notebook", name, pageId, null))
        // page: template→refId (none); size stays in boundingBox.
        exec(insertSql, arrayOf(pageId, notebookRowId, bboxJson, now, now, "page", null, "", null))
        // layer: label→text, visible+unlocked → flags.
        val layerId = UUID.randomUUID().toString()
        exec(insertSql, arrayOf(layerId, pageId, bboxJson, now, now, "layer", "Content", null, LAYER_FLAGS_DEFAULT))

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
    } catch (e: Exception) {
        // Mid-bootstrap failure: retract the index row and the partial file together.
        runCatching { repository.softDeleteNotebook(entity.id) }
        runCatching { db.close() }
        runCatching { soilPath.delete() }
        throw e
    } finally {
        runCatching { db.close() }
    }

    java.io.File("${soilPath.absolutePath}-journal").takeIf { it.exists() }?.delete()
    return entity
}
