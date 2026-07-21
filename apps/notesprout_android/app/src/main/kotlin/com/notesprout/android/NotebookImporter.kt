package com.notesprout.android

import android.content.Context
import androidx.room.withTransaction
import com.notesprout.android.core.Slog
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.crypto.SoilMigrator
import com.notesprout.android.data.NotebookMeta
import com.notesprout.android.data.NotebookMetaStore
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
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
     * Probe the file and read its manifest.
     *
     * For encrypted files, [passphrase] must be supplied (already verified by the caller via
     * [com.notesprout.android.crypto.KeyResolver.resolveForImportRead]). If the file is
     * encrypted and no passphrase is given, or the file is invalid, an [ImportException] is thrown.
     */
    suspend fun readManifest(
        file: File,
        fallbackName: String,
        passphrase: String? = null,
    ): ImportManifest = withContext(Dispatchers.IO) {
        val kind = SoilCrypto.probe(file)
        when (kind) {
            SoilFileKind.Invalid ->
                throw ImportException("Not a valid notebook file")
            SoilFileKind.Encrypted -> {
                if (passphrase == null) throw ImportException("Not a valid notebook file")
                val rawDb = SoilCrypto.openRaw(file, passphrase)
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
     * Import an encrypted .soil into Garden and register it in the global index.
     *
     * If [finalPass] differs from [enteredPass], the temp [file] is re-keyed in place before
     * copying into Garden. Re-keying on the temp keeps Garden clean on any failure.
     * [file] is deleted after a successful import.
     *
     * The **index** snapshot is kept only for GLOBAL scope (the index is encrypted at rest and the
     * key is available, so its card renders a cover like any other global notebook); NOTEBOOK scope
     * stays cover-less. The **portable** in-`.soil` meta always stays cover-less (see
     * [refreshEncryptedMeta]) so a keyless import on another device can't leak a preview.
     */
    suspend fun importEncrypted(
        context: Context,
        repo: IndexRepository,
        file: File,
        manifest: ImportManifest,
        displayName: String,
        parentId: String?,
        resolvedId: String,
        enteredPass: String,
        finalPass: String,
        scope: KeyScope,
    ): String = withContext(Dispatchers.IO) {
        if (finalPass != enteredPass) {
            SoilMigrator.rekeyInPlace(file, enteredPass, finalPass)
        }

        val gardenFile = soilFile(context, resolvedId)
        installIntoGarden(file, gardenFile)

        val now = System.currentTimeMillis()
        repo.importNotebookRow(
            id = resolvedId,
            name = displayName,
            parentId = parentId,
            obj = NotebookObject(
                snapshot = if (scope == KeyScope.GLOBAL) manifest.meta?.cover else null,
                pageCount = manifest.pageCount,
                encrypted = true,
                keyScope = scope,
            ),
            createdAt = manifest.meta?.createdAt ?: now,
            updatedAt = now,
        )

        refreshEncryptedMeta(context, repo, gardenFile, resolvedId, displayName, parentId, manifest, scope, finalPass, now)

        runCatching { file.delete() }
        resolvedId
    }

    /**
     * Replace an existing encrypted notebook in-place.
     *
     * Keeps the existing index row's placement. Updates name, page count, and encryption scope
     * from [scope]. Re-keys the temp [file] if [finalPass] != [enteredPass]. [file] is deleted
     * after a successful replace.
     */
    suspend fun replaceEncrypted(
        context: Context,
        repo: IndexRepository,
        file: File,
        manifest: ImportManifest,
        displayName: String,
        existingId: String,
        enteredPass: String,
        finalPass: String,
        scope: KeyScope,
    ): String = withContext(Dispatchers.IO) {
        if (finalPass != enteredPass) {
            SoilMigrator.rekeyInPlace(file, enteredPass, finalPass)
        }

        val parentId = repo.getNotebook(existingId)?.parentId
        val gardenFile = soilFile(context, existingId)
        installIntoGarden(file, gardenFile)

        // The file (and its salt) was just replaced — drop the stale cached raw key immediately,
        // before anything can try to open the new file with it.
        com.notesprout.android.crypto.KeyMaterial.invalidate(context, existingId)
        // One transaction: a partial set of these writes (e.g. state written without the rename)
        // leaves the index describing the new file with the old keying — stranding the notebook
        // in a needless unlock loop until something repairs it.
        NotesproutIndex.db().withTransaction {
            repo.renameNotebook(existingId, displayName)
            repo.updateNotebookPageCount(existingId, manifest.pageCount)
            repo.updateNotebookSnapshot(existingId, if (scope == KeyScope.GLOBAL) manifest.meta?.cover else null)
            repo.setEncryptionState(existingId, true, scope)
        }

        val now = System.currentTimeMillis()
        refreshEncryptedMeta(context, repo, gardenFile, existingId, displayName, parentId, manifest, scope, finalPass, now)

        runCatching { file.delete() }
        existingId
    }

    /**
     * Install the verified temp [file] at [gardenFile] atomically: copy to a `.new` sibling first,
     * then rename into place. A plain `copyTo(overwrite = true)` truncates the destination up
     * front, so a mid-copy process death would leave a torn `.soil` where a notebook (on the
     * replace path, the user's existing notebook) used to be. Stale sidecars of the destination
     * are dropped so the fresh file isn't paired with another database's WAL.
     */
    private fun installIntoGarden(file: File, gardenFile: File) {
        val incoming = File("${gardenFile.absolutePath}.new")
        incoming.delete()
        file.copyTo(incoming, overwrite = true)
        listOf("-wal", "-shm", "-journal").forEach { File("${gardenFile.absolutePath}$it").delete() }
        if (!incoming.renameTo(gardenFile)) {
            incoming.delete()
            throw ImportException("Could not install the imported notebook file.")
        }
    }

    private suspend fun refreshEncryptedMeta(
        context: Context,
        repo: IndexRepository,
        gardenFile: File,
        notebookId: String,
        displayName: String,
        parentId: String?,
        manifest: ImportManifest,
        scope: KeyScope,
        passphrase: String,
        now: Long,
    ) = runCatching {
        val freshMeta = NotebookMeta(
            notebookId = notebookId,
            name = displayName,
            createdAt = manifest.meta?.createdAt ?: now,
            updatedAt = now,
            encrypted = true,
            keyScope = scope,
            cover = null,
            folderPath = repo.getFolderAncestry(parentId),
        )
        val db = SoilDatabase.builder(context, gardenFile.absolutePath)
            .openHelperFactory(SoilCrypto.roomFactory(passphrase))
            .build()
        try {
            NotebookMetaStore.write(db, freshMeta)
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }.onFailure { Slog.d("NotebookImporter") { "encrypted meta refresh failed: ${it.message}" } }
}
