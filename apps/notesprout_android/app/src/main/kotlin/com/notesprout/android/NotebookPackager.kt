package com.notesprout.android

import android.content.Context
import com.notesprout.android.core.Slog
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.NotebookMetaStore
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object NotebookPackager {

    /**
     * Packages from an already-open [db] connection (NotebookActivity path).
     *
     * The caller must flush strokes to [db] before invoking this. This function refreshes
     * notebook_meta (with exportedAt) through the live connection, checkpoints so the main
     * file is complete, then copies the main file only. The live connection stays open —
     * its -wal/-shm are not deleted.
     */
    suspend fun packageOpenForExport(
        context: Context,
        db: SoilDatabase,
        repo: IndexRepository,
        notebookId: String,
    ): File = withContext(Dispatchers.IO) {
        val soilPath = soilFile(context, notebookId)

        runCatching {
            val meta = NotebookMetaStore.buildFromIndex(repo, notebookId)
                ?.copy(exportedAt = System.currentTimeMillis())
                ?: return@runCatching
            NotebookMetaStore.write(db, meta)
        }.onFailure { Slog.d("NotebookPackager") { "open-db meta refresh failed: ${it.message}" } }

        // Checkpoint so the main file holds all committed content.
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }

        val name = repo.getNotebook(notebookId)?.name ?: notebookId
        val safeName = sanitizeName(name, fallback = notebookId)
        val outDir = File(context.cacheDir, "exported_notebooks")
            .also { it.deleteRecursively(); it.mkdirs() }
        val outFile = File(outDir, "$safeName.soil")
        soilPath.copyTo(outFile, overwrite = true)
        outFile
    }

    /**
     * Copies the notebook .soil to cacheDir/exported_notebooks/<safeName>.soil.
     *
     * [openableKey]: empty string = plaintext (no SQLCipher key needed); non-empty string = GLOBAL
     * passphrase (open with key); null = skip meta refresh (encrypted-NOTEBOOK or key not cached —
     * copy the cold file as-is).
     *
     * Best-effort: a failed refresh is logged and skipped; the copy always proceeds.
     */
    suspend fun packageForExport(
        context: Context,
        repo: IndexRepository,
        notebookId: String,
        openableKey: String?,
    ): File = withContext(Dispatchers.IO) {
        val soilPath = soilFile(context, notebookId)

        // Refresh notebook_meta with exportedAt so the copy carries current provenance.
        if (openableKey != null) {
            runCatching {
                val builder = SoilDatabase.builder(context, soilPath.absolutePath)
                if (openableKey.isNotEmpty()) {
                    builder.openHelperFactory(SoilCrypto.roomFactory(openableKey))
                }
                val db = builder.build()
                try {
                    val meta = NotebookMetaStore.buildFromIndex(repo, notebookId)
                        ?.copy(exportedAt = System.currentTimeMillis())
                        ?: return@runCatching
                    NotebookMetaStore.write(db, meta)
                    db.openHelper.writableDatabase
                        .query("PRAGMA wal_checkpoint(TRUNCATE)")
                        .use { it.moveToFirst() }
                } finally {
                    db.close()
                }
            }.onFailure { Slog.d("NotebookPackager") { "meta refresh failed: ${it.message}" } }
        }

        val name = repo.getNotebook(notebookId)?.name ?: notebookId
        val safeName = sanitizeName(name, fallback = notebookId)
        val outDir = File(context.cacheDir, "exported_notebooks")
            .also { it.deleteRecursively(); it.mkdirs() }
        val outFile = File(outDir, "$safeName.soil")
        soilPath.copyTo(outFile, overwrite = true)
        outFile
    }

    private fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") fallback else cleaned
    }
}
