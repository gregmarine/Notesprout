package com.notesprout.android.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads the best available cover bitmap for a notebook .soil file.
 *
 * Resolution order:
 * 1. Explicit cover object (`type = 'cover'`) referenced by the notebook metadata's
 *    `cover` field — decoded from [CoverObject.image] (base64).
 * 2. Snapshot of the last-opened page — extracted from the page row's `data` JSON
 *    `snapshot` field (base64 transparent-background PNG).
 * 3. Returns null → caller shows the `ic_notebook` fallback icon.
 *
 * Opens the .soil file read-only with a plain [SQLiteDatabase]; closes it before
 * returning. Must be called on [Dispatchers.IO] (internally enforced via [withContext]).
 */
suspend fun loadNotebookCoverBitmap(file: File): Bitmap? = withContext(Dispatchers.IO) {
    var db: SQLiteDatabase? = null
    try {
        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        // ── Step 1: read notebook metadata ───────────────────────────────────
        val (notebookId, metadataJson) = db.rawQuery(
            "SELECT id, data FROM notebook WHERE type = 'notebook' LIMIT 1", null
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            Pair(c.getString(0), c.getString(1))
        }

        val metadata = NotebookMetadata.fromJson(notebookId, metadataJson)

        // ── Step 2: try explicit cover object ─────────────────────────────────
        if (metadata.cover.isNotEmpty()) {
            val coverJson = db.rawQuery(
                "SELECT data FROM notebook WHERE parentId = ? AND type = 'cover' AND deletedAt IS NULL LIMIT 1",
                arrayOf(notebookId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

            if (coverJson != null) {
                try {
                    val coverObj = coverCodec.decodeFromString<CoverObject>(coverJson)
                    if (coverObj.image.isNotEmpty()) {
                        val bytes = Base64.decode(coverObj.image, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?.let { return@withContext it }
                    }
                } catch (_: Exception) { /* fall through to snapshot */ }
            }
        }

        // ── Step 3: try snapshot of last-opened page ──────────────────────────
        val pageId = metadata.lastOpenedPage
        if (!pageId.isNullOrEmpty()) {
            val pageJson = db.rawQuery(
                "SELECT data FROM notebook WHERE id = ? AND deletedAt IS NULL LIMIT 1",
                arrayOf(pageId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

            if (pageJson != null) {
                try {
                    val page = pageCodec.decodeFromString<PageSnapshot>(pageJson)
                    if (!page.snapshot.isNullOrEmpty()) {
                        val bytes = Base64.decode(page.snapshot, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?.let { return@withContext it }
                    }
                } catch (_: Exception) { /* fall through to null */ }
            }
        }

        null
    } catch (_: Exception) {
        null
    } finally {
        db?.close()
    }
}

/** Minimal view of a page's `data` JSON — only extracts the optional snapshot field. */
@Serializable
private data class PageSnapshot(val snapshot: String? = null)

private val coverCodec = Json { ignoreUnknownKeys = true }
private val pageCodec  = Json { ignoreUnknownKeys = true }
