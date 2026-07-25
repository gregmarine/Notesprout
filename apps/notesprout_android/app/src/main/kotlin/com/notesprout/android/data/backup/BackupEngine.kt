package com.notesprout.android.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notesprout.android.BuildConfig
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.NotebookCompactor
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BackupEngine {

    suspend fun run(
        context: Context,
        repo: IndexRepository,
        config: BackupConfig,
        onProgress: (current: Int, total: Int, label: String) -> Unit,
    ): BackupResult = withContext(Dispatchers.IO) {
        val runStart = System.currentTimeMillis()
        val results = mutableMapOf<BackupKind, DestResult>()

        // ── Resolve LOCAL destination ─────────────────────────────────────────
        var localDir: DocumentFile? = null
        if (config.localEnabled && config.localTreeUri != null) {
            localDir = try {
                SafBackupWriter.rootDir(context, Uri.parse(config.localTreeUri))
            } catch (_: Exception) { null }
            if (localDir == null) {
                results[BackupKind.LOCAL] = DestResult(
                    0, 0, 0, 0, false,
                    listOf("Local folder is no longer accessible. Re-choose the folder in Backup Settings.")
                )
            } else if (BuildConfig.DEBUG) {
                localDir = SafBackupWriter.ensureChildDir(localDir, "dev") ?: run {
                    results[BackupKind.LOCAL] = DestResult(
                        0, 0, 0, 0, false, listOf("Could not create dev/ subfolder in local backup destination.")
                    )
                    null
                }
            }
        }

        // ── Resolve DRIVE destination ─────────────────────────────────────────
        var driveClient: DriveApiClient? = null
        var driveFolderId: String? = null
        if (config.driveEnabled && config.driveAccountEmail != null) {
            when (val tr = DriveAuth.getAccessTokenSilent(context)) {
                is DriveAuth.TokenResult.Token -> {
                    val client = DriveApiClient(tr.accessToken)
                    val folderId = DriveBackupWriter.resolveDeviceFolderId(client, config.deviceFolderName)
                    if (folderId != null) {
                        driveClient = client
                        driveFolderId = if (BuildConfig.DEBUG) {
                            DriveBackupWriter.resolveChildFolderId(client, folderId, "dev") ?: run {
                                results[BackupKind.DRIVE] = DestResult(
                                    0, 0, 0, 0, false, listOf("Could not create dev/ subfolder in Google Drive backup folder.")
                                )
                                null
                            }
                        } else {
                            folderId
                        }
                        if (driveFolderId == null) driveClient = null
                    } else {
                        results[BackupKind.DRIVE] = DestResult(
                            0, 0, 0, 0, false,
                            listOf("Failed to resolve Google Drive backup folder. Check your connection and try again.")
                        )
                    }
                }
                is DriveAuth.TokenResult.Error -> {
                    results[BackupKind.DRIVE] = DestResult(
                        0, 0, 0, 0, false,
                        listOf("Reconnect Google Drive in Backup Settings: ${tr.message}")
                    )
                }
            }
        }

        // ── Build notebook work list ──────────────────────────────────────────
        data class Work(val id: String, val name: String, val kind: BackupKind)

        val work = mutableListOf<Work>()
        if (localDir != null) {
            repo.notebooksNeedingBackup(BackupKind.LOCAL).forEach { work.add(Work(it.id, it.name, BackupKind.LOCAL)) }
        }
        if (driveClient != null) {
            repo.notebooksNeedingBackup(BackupKind.DRIVE).forEach { work.add(Work(it.id, it.name, BackupKind.DRIVE)) }
        }

        // ── Compact the leanest form of each backed-up notebook, in place ─────
        // Only the notebooks moving in this run (unique across destinations) are compacted, right
        // before their bytes are copied. Compaction preserves `updatedAt`, so a notebook already in
        // the work list stays flagged and is copied in its now-smaller form below. Encrypted
        // notebooks we cannot open unattended (NOTEBOOK-scope, or GLOBAL without a cached passphrase)
        // are silently left as-is — still valid, just not extra-compacted; they self-compact on their
        // next open. VACUUM inside compact() only fires when a pass actually changed something, so an
        // already-lean notebook costs only cheap scans here.
        val toCompact = work.map { it.id to it.name }.distinctBy { it.first }
        // Ids whose compaction actually opened the file — the open/close replays + truncates any
        // WAL, so the main file alone is a complete copy. Notebooks we can't open unattended keep
        // whatever -wal an abnormal exit left; their sidecar is copied alongside below so the
        // backup never silently misses committed writes.
        val walAbsorbed = mutableSetOf<String>()
        toCompact.forEachIndexed { i, (id, name) ->
            onProgress(i + 1, toCompact.size, "Compacting $name")
            if (compactInPlace(context, repo, id)) walAbsorbed.add(id)
        }

        // ── Per-notebook copies ───────────────────────────────────────────────
        var localAttempted = 0; var localSucceeded = 0; var localFailed = 0; var localSkipped = 0
        val localErrors = mutableListOf<String>()
        var driveAttempted = 0; var driveSucceeded = 0; var driveFailed = 0; var driveSkipped = 0
        val driveErrors = mutableListOf<String>()

        work.forEachIndexed { i, item ->
            onProgress(i + 1, work.size, item.name)
            val soil = soilFile(context, item.id)
            if (!soil.exists()) {
                Log.w("BackupEngine", "Soil file missing for notebook ${item.id} — skipping")
                if (item.kind == BackupKind.LOCAL) localSkipped++ else driveSkipped++
                return@forEachIndexed
            }
            // A notebook that couldn't be checkpointed (keyless encrypted) may carry committed
            // writes in its -wal: copy the sidecar with the main file, and require BOTH to land
            // before stamping the notebook backed-up. When the WAL was absorbed (or none exists),
            // delete any stale sidecar at the destination — pairing a fresh .soil with an old
            // -wal on restore would corrupt the notebook.
            val walFile = File("${soil.absolutePath}-wal")
            val needsWal = item.id !in walAbsorbed && walFile.exists() && walFile.length() > 0L
            when (item.kind) {
                BackupKind.LOCAL -> {
                    localAttempted++
                    val ok = SafBackupWriter.replaceFile(context, localDir!!, "${item.id}.soil", soil) &&
                        if (needsWal) SafBackupWriter.replaceFile(context, localDir, "${item.id}.soil-wal", walFile)
                        else SafBackupWriter.deleteFile(localDir, "${item.id}.soil-wal")
                    if (ok) {
                        localSucceeded++
                        repo.markNotebookBackedUp(item.id, BackupKind.LOCAL, runStart)
                    } else {
                        localFailed++
                        localErrors.add("Failed to back up '${item.name}' to local storage.")
                    }
                }
                BackupKind.DRIVE -> {
                    driveAttempted++
                    val ok = DriveBackupWriter.replaceFile(driveClient!!, driveFolderId!!, "${item.id}.soil", soil) &&
                        if (needsWal) DriveBackupWriter.replaceFile(driveClient, driveFolderId, "${item.id}.soil-wal", walFile)
                        else DriveBackupWriter.deleteFile(driveClient, driveFolderId, "${item.id}.soil-wal")
                    if (ok) {
                        driveSucceeded++
                        repo.markNotebookBackedUp(item.id, BackupKind.DRIVE, runStart)
                    } else {
                        driveFailed++
                        driveErrors.add("Failed to back up '${item.name}' to Google Drive.")
                    }
                }
            }
        }

        // ── Index copy — last, after all per-notebook timestamps written (D9) ─
        NotesproutIndex.checkpointAndVacuum()
        val indexFile = File(context.getExternalFilesDir(null), "notesprout.db")

        // Snapshot the live index locally first: the DB stays open during the (slow, especially
        // Drive) destination writes, and a concurrent write + auto-checkpoint could tear a copy
        // streamed straight from the live file. The local copy's window is milliseconds, and the
        // probe rejects a torn snapshot before it can replace a good backup.
        val indexSnapshot = File(context.cacheDir, "backup_index_snapshot.db")
        val indexSource = runCatching {
            indexFile.copyTo(indexSnapshot, overwrite = true)
            check(SoilCrypto.probe(indexSnapshot) != com.notesprout.android.crypto.SoilFileKind.Invalid)
            indexSnapshot
        }.getOrElse {
            Log.w("BackupEngine", "index snapshot failed — streaming live file: ${it.message}")
            indexFile
        }

        var localIndexCopied = false
        if (localDir != null) {
            localIndexCopied = SafBackupWriter.replaceFile(context, localDir, "notesprout.db", indexSource)
            if (!localIndexCopied) localErrors.add("Failed to copy notesprout.db to local storage.")
        }

        var driveIndexCopied = false
        if (driveClient != null && driveFolderId != null) {
            driveIndexCopied = DriveBackupWriter.replaceFile(driveClient, driveFolderId, "notesprout.db", indexSource)
            if (!driveIndexCopied) driveErrors.add("Failed to copy notesprout.db to Google Drive.")
        }
        indexSnapshot.delete()

        // ── Finalise ──────────────────────────────────────────────────────────
        if (localDir != null) {
            results[BackupKind.LOCAL] = DestResult(localAttempted, localSucceeded, localFailed, localSkipped, localIndexCopied, localErrors)
        }
        if (driveClient != null) {
            results[BackupKind.DRIVE] = DestResult(driveAttempted, driveSucceeded, driveFailed, driveSkipped, driveIndexCopied, driveErrors)
        }

        // Stamp "last backup" only when something actually landed — a run where every destination
        // failed used to show "backed up just now" and lull the user into a false sense of safety.
        val anySuccess = results.values.any { it.indexCopied || it.succeeded > 0 }
        if (anySuccess) repo.saveBackupConfig(config.copy(lastRunAt = runStart))

        BackupResult(results)
    }

    /**
     * Open one `.soil` and run the same seal-time [NotebookCompactor.compact] pass used on close, so
     * the file about to be backed up is in its leanest form. Mirrors the key resolution of the manual
     * "Compact Notebooks" sweep: plaintext opens with no key; a GLOBAL-scope notebook opens only if
     * its passphrase is cached; a NOTEBOOK-scope notebook (no unattended key) is skipped. Any failure
     * is swallowed — this is best-effort optimisation, never a reason to fail the backup copy that
     * follows. Caller must be on [Dispatchers.IO].
     *
     * Returns true when the notebook was actually opened (the open/close also absorbs its WAL, so
     * the main file alone is a complete backup copy); false when it was skipped or failed.
     */
    private suspend fun compactInPlace(context: Context, repo: IndexRepository, notebookId: String): Boolean {
        try {
            val info = repo.getEncryptionInfo(notebookId)
            val key: String? = when {
                !info.encrypted -> null
                info.keyScope == KeyScope.GLOBAL ->
                    PassphraseStore.getGlobalPassphrase(context) ?: return false
                else -> return false
            }
            val file = soilFile(context, notebookId)
            if (!file.exists()) return false
            val builder = SoilDatabase.builder(context, file.absolutePath)
            if (key != null) builder.openHelperFactory(SoilCrypto.roomFactory(key))
            val db = builder.build()
            try {
                NotebookCompactor.compact(db, context.resources.displayMetrics.density)
            } finally {
                db.close()
            }
            return true
        } catch (e: Exception) {
            Log.w("BackupEngine", "Pre-backup compaction failed for $notebookId — backing up as-is: ${e.message}")
            return false
        }
    }
}
