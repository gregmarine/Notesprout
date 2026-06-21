package com.notesprout.android.data

import com.notesprout.android.BuildConfig
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
}
