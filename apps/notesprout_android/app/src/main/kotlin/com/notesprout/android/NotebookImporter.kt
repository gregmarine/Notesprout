package com.notesprout.android

import android.content.Context
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
     * Import a plaintext .soil from [file] into Garden and register it in the global index.
     *
     * [parentId] and [resolvedId] are pre-resolved by the caller (placement + collision
     * dialogs in MainActivity). [file] is deleted after a successful import.
     */
    suspend fun importPlaintext(
        context: Context,
        repo: IndexRepository,
        file: File,
        manifest: ImportManifest,
        displayName: String,
        parentId: String?,
        resolvedId: String,
    ): String = withContext(Dispatchers.IO) {
        val meta = manifest.meta

        // Clear any stale sidecars before copying into Garden.
        val gardenFile = soilFile(context, resolvedId)
        File(gardenFile.parent!!, "$resolvedId.soil-wal").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-shm").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-journal").delete()
        file.copyTo(gardenFile, overwrite = true)

        val now = System.currentTimeMillis()
        repo.importNotebookRow(
            id = resolvedId,
            name = displayName,
            parentId = parentId,
            obj = NotebookObject(
                snapshot = meta?.cover,
                pageCount = manifest.pageCount,
                encrypted = false,
                keyScope = null,
            ),
            createdAt = meta?.createdAt ?: now,
            updatedAt = now,
        )

        refreshPlaintextMeta(context, repo, gardenFile, resolvedId, displayName, parentId, meta, now)

        runCatching { file.delete() }
        resolvedId
    }

    /**
     * Replace an existing notebook in-place (same index row / folder / pin) with the contents
     * of [file]. Updates name, page count, and snapshot from [manifest]; keeps placement.
     * [file] is deleted after a successful replace.
     */
    suspend fun replacePlaintext(
        context: Context,
        repo: IndexRepository,
        file: File,
        manifest: ImportManifest,
        displayName: String,
        existingId: String,
    ): String = withContext(Dispatchers.IO) {
        val meta = manifest.meta
        val parentId = repo.getNotebook(existingId)?.parentId

        val gardenFile = soilFile(context, existingId)
        File(gardenFile.parent!!, "$existingId.soil-wal").delete()
        File(gardenFile.parent!!, "$existingId.soil-shm").delete()
        File(gardenFile.parent!!, "$existingId.soil-journal").delete()
        file.copyTo(gardenFile, overwrite = true)

        repo.renameNotebook(existingId, displayName)
        repo.updateNotebookPageCount(existingId, manifest.pageCount)
        repo.updateNotebookSnapshot(existingId, meta?.cover)

        val now = System.currentTimeMillis()
        refreshPlaintextMeta(context, repo, gardenFile, existingId, displayName, parentId, meta, now)

        runCatching { file.delete() }
        existingId
    }

    /**
     * Import an encrypted .soil into Garden and register it in the global index.
     *
     * If [finalPass] differs from [enteredPass], the temp [file] is re-keyed in place before
     * copying into Garden. Re-keying on the temp keeps Garden clean on any failure.
     * [file] is deleted after a successful import.
     *
     * NEVER pass a cover snapshot for encrypted notebooks — leak hygiene.
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
        File(gardenFile.parent!!, "$resolvedId.soil-wal").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-shm").delete()
        File(gardenFile.parent!!, "$resolvedId.soil-journal").delete()
        file.copyTo(gardenFile, overwrite = true)

        val now = System.currentTimeMillis()
        repo.importNotebookRow(
            id = resolvedId,
            name = displayName,
            parentId = parentId,
            obj = NotebookObject(
                snapshot = null,
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
        File(gardenFile.parent!!, "$existingId.soil-wal").delete()
        File(gardenFile.parent!!, "$existingId.soil-shm").delete()
        File(gardenFile.parent!!, "$existingId.soil-journal").delete()
        file.copyTo(gardenFile, overwrite = true)

        repo.renameNotebook(existingId, displayName)
        repo.updateNotebookPageCount(existingId, manifest.pageCount)
        repo.updateNotebookSnapshot(existingId, null)
        repo.setEncryptionState(existingId, true, scope)

        val now = System.currentTimeMillis()
        refreshEncryptedMeta(context, repo, gardenFile, existingId, displayName, parentId, manifest, scope, finalPass, now)

        runCatching { file.delete() }
        existingId
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

    private suspend fun refreshPlaintextMeta(
        context: Context,
        repo: IndexRepository,
        gardenFile: File,
        notebookId: String,
        displayName: String,
        parentId: String?,
        meta: NotebookMeta?,
        now: Long,
    ) = runCatching {
        val freshMeta = NotebookMeta(
            notebookId = notebookId,
            name = displayName,
            createdAt = meta?.createdAt ?: now,
            updatedAt = now,
            encrypted = false,
            keyScope = null,
            cover = meta?.cover,
            folderPath = repo.getFolderAncestry(parentId),
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
}
