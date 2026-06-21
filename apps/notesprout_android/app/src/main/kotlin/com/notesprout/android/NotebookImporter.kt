package com.notesprout.android

import android.content.Context
import com.notesprout.android.core.Slog
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.data.NotebookMeta
import com.notesprout.android.data.NotebookMetaStore
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ImportException(message: String) : Exception(message)

data class ImportManifest(
    val meta: NotebookMeta?,
    val kind: SoilFileKind,
    val pageCount: Int,
    val fallbackName: String,
)

object NotebookImporter {

    /**
     * Probe the file and read its manifest. Throws [ImportException] on invalid or
     * unsupported (encrypted) files — caller shows the message as a toast.
     */
    suspend fun readManifest(
        file: File,
        fallbackName: String,
    ): ImportManifest = withContext(Dispatchers.IO) {
        val kind = SoilCrypto.probe(file)
        when (kind) {
            SoilFileKind.Invalid ->
                throw ImportException("Not a valid notebook file")
            SoilFileKind.Encrypted ->
                throw ImportException("Encrypted import coming soon")
            SoilFileKind.Plaintext -> {
                val rawDb = SoilCrypto.openRaw(file, null)
                try {
                    val hasNotebook = rawDb.rawQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='notebook'",
                        null
                    ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
                    if (!hasNotebook) throw ImportException("Not a valid notebook file")
                    val meta = NotebookMetaStore.readRaw(rawDb)
                    val pageCount = NotebookMetaStore.countPages(rawDb)
                    ImportManifest(meta, kind, pageCount, fallbackName)
                } finally {
                    rawDb.close()
                }
            }
        }
    }

    /**
     * Import a plaintext .soil from [file] (the import cache temp) into Garden and register it
     * in the global index. Returns the resolved notebook id.
     *
     * S1 behavior: placement always uses the notebook's own [folderPath] (default); id collisions
     * silently take a fresh UUID (Keep-both). The [file] is deleted after a successful import.
     */
    suspend fun importPlaintext(
        context: Context,
        repo: IndexRepository,
        file: File,
        manifest: ImportManifest,
        displayName: String,
    ): String = withContext(Dispatchers.IO) {
        val meta = manifest.meta

        // Resolve placement: walk folderPath root→parent, ensureFolderWithId at each level.
        var resolvedParentId: String? = null
        meta?.folderPath?.forEach { ref ->
            val entity = repo.ensureFolderWithId(ref.id, ref.name, resolvedParentId)
            resolvedParentId = entity.id
        }

        // Resolve id: use the embedded id if not already in the index, else fresh UUID.
        val resolvedId = if (meta != null && repo.getNotebook(meta.notebookId) == null) {
            meta.notebookId
        } else {
            UUID.randomUUID().toString()
        }

        // Clear any stale sidecars for the resolved id before copying into Garden.
        val gardenFile = soilFile(context, resolvedId)
        File(gardenFile.parent!!, "$resolvedId.soil-wal").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-shm").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-journal").delete()
        file.copyTo(gardenFile, overwrite = true)

        // Register the notebook in the global index.
        val now = System.currentTimeMillis()
        repo.importNotebookRow(
            id = resolvedId,
            name = displayName,
            parentId = resolvedParentId,
            obj = NotebookObject(
                snapshot = meta?.cover,
                pageCount = manifest.pageCount,
                encrypted = false,
                keyScope = null,
            ),
            createdAt = meta?.createdAt ?: now,
            updatedAt = now,
        )

        // Best-effort: refresh the embedded notebook_meta to reflect the imported identity
        // (new id, resolved folderPath, etc.) so a later re-export is self-consistent.
        runCatching {
            val freshMeta = NotebookMeta(
                notebookId = resolvedId,
                name = displayName,
                createdAt = meta?.createdAt ?: now,
                updatedAt = now,
                encrypted = false,
                keyScope = null,
                cover = meta?.cover,
                folderPath = repo.getFolderAncestry(resolvedParentId),
            )
            val db = SoilDatabase.builder(context, gardenFile.absolutePath).build()
            try {
                NotebookMetaStore.write(db, freshMeta)
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            } finally {
                db.close()
            }
        }.onFailure { Slog.d("NotebookImporter") { "meta refresh failed: ${it.message}" } }

        // Clean up the import temp file.
        runCatching { file.delete() }

        resolvedId
    }
}
