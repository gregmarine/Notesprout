package com.notesprout.android.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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
 * Opens the .soil file with a plain [SQLiteDatabase] and closes it before returning.
 * Must be called on [Dispatchers.IO] (internally enforced via [withContext]).
 *
 * The connection is opened `OPEN_READWRITE` (not `OPEN_READONLY`) on purpose: a read-only WAL
 * connection re-creates `-shm` on open and cannot unlink `-wal`/`-shm` on close (deletion needs
 * write permission), stranding those sidecars in the notebook folder. [checkpointTruncateAndClose]
 * checkpoint-truncates and closes so SQLite removes both, keeping the folder showing only `.soil`.
 */
suspend fun loadNotebookCoverBitmap(file: File): Bitmap? = withContext(Dispatchers.IO) {
    var db: SQLiteDatabase? = null
    try {
        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode cover object for ${file.name}; falling back to snapshot", e)
                }
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode page snapshot for ${file.name}", e)
                }
            }
        }

        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load cover bitmap for ${file.name}", e)
        null
    } finally {
        db?.checkpointTruncateAndClose(TAG, file)
    }
}

private const val TAG = "CoverLoader"

/**
 * Closes a connection opened on [file] for *reading* without stranding `-wal`/`-shm`/`-journal`
 * sidecars in the notebook folder (CLAUDE.md "folder shows only `.soil` files" rule).
 *
 * Read paths must open the file `OPEN_READWRITE` (not `OPEN_READONLY`): a read-only WAL
 * connection re-creates `-shm` on open and cannot unlink `-wal`/`-shm` on close, because
 * deleting them requires write permission. This checkpoint-truncates the WAL and closes so
 * SQLite removes both sidecars on the last-connection close, then deletes the empty `-journal`
 * shell SQLite leaves behind (mirroring the Room/[PageCopier] close paths).
 *
 * Best-effort: if another connection holds a read lock the TRUNCATE is skipped without error;
 * the close and journal cleanup still run.
 */
internal fun SQLiteDatabase.checkpointTruncateAndClose(tag: String, file: File) {
    try {
        rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    } catch (e: Exception) {
        Log.e(tag, "WAL checkpoint failed for ${file.name}", e)
    } finally {
        close()
        File("${file.absolutePath}-journal").takeIf { it.exists() }?.delete()
    }
}

/** Minimal view of a page's `data` JSON — only extracts the optional snapshot field. */
@Serializable
private data class PageSnapshot(val snapshot: String? = null)

private val coverCodec = Json { ignoreUnknownKeys = true }
private val pageCodec  = Json { ignoreUnknownKeys = true }
