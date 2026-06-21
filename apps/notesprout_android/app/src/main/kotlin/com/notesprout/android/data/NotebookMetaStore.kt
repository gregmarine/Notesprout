package com.notesprout.android.data

import com.notesprout.android.BuildConfig
import com.notesprout.android.crypto.SoilRawDb
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object NotebookMetaStore {

    suspend fun write(db: SoilDatabase, meta: NotebookMeta) = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO notebook_meta (id, json) VALUES (0, ?)",
            arrayOf(meta.toJson())
        )
    }

    suspend fun read(db: SoilDatabase): NotebookMeta? = withContext(Dispatchers.IO) {
        try {
            db.openHelper.writableDatabase
                .query("SELECT json FROM notebook_meta WHERE id = 0")
                .use { cursor ->
                    if (!cursor.moveToFirst()) return@withContext null
                    NotebookMeta.fromJson(cursor.getString(0))
                }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun buildFromIndex(repo: IndexRepository, notebookId: String): NotebookMeta? =
        withContext(Dispatchers.IO) {
            val entity = repo.getNotebook(notebookId) ?: return@withContext null
            val notebookObj = try {
                Json.decodeFromString<NotebookObject>(entity.data)
            } catch (_: Exception) {
                return@withContext null
            }
            val info = repo.getEncryptionInfo(notebookId)
            val folderPath = repo.getFolderAncestry(entity.parentId)
            val cover = if (!notebookObj.encrypted) notebookObj.snapshot else null
            NotebookMeta(
                notebookId     = notebookId,
                name           = entity.name,
                createdAt      = entity.createdAt,
                updatedAt      = entity.updatedAt,
                encrypted      = info.encrypted,
                keyScope       = info.keyScope,
                cover          = cover,
                folderPath     = folderPath,
                appVersionCode = BuildConfig.VERSION_CODE,
            )
        }

    suspend fun refresh(db: SoilDatabase, repo: IndexRepository, notebookId: String) {
        val meta = buildFromIndex(repo, notebookId) ?: return
        write(db, meta)
    }

    /** Read notebook_meta from a cold raw DB (no Room). Returns null if the table is absent. */
    fun readRaw(rawDb: SoilRawDb): NotebookMeta? {
        return try {
            rawDb.rawQuery("SELECT json FROM notebook_meta WHERE id = 0", null)
                .use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else NotebookMeta.fromJson(cursor.getString(0))
                }
        } catch (_: Exception) {
            null
        }
    }

    /** Count non-deleted page rows in a cold raw DB (no Room). */
    fun countPages(rawDb: SoilRawDb): Int {
        return try {
            rawDb.rawQuery(
                "SELECT count(*) FROM notebook WHERE type='page' AND deletedAt IS NULL",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }
}
