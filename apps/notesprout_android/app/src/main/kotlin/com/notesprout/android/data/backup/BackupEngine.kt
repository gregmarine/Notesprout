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
        toCompact.forEachIndexed { i, (id, name) ->
            onProgress(i + 1, toCompact.size, "Compacting $name")
            compactInPlace(context, repo, id)
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
            when (item.kind) {
                BackupKind.LOCAL -> {
                    localAttempted++
                    if (SafBackupWriter.replaceFile(context, localDir!!, "${item.id}.soil", soil)) {
                        localSucceeded++
                        repo.markNotebookBackedUp(item.id, BackupKind.LOCAL, runStart)
                    } else {
                        localFailed++
                        localErrors.add("Failed to back up '${item.name}' to local storage.")
                    }
                }
                BackupKind.DRIVE -> {
                    driveAttempted++
                    if (DriveBackupWriter.replaceFile(driveClient!!, driveFolderId!!, "${item.id}.soil", soil)) {
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

        var localIndexCopied = false
        if (localDir != null) {
            localIndexCopied = SafBackupWriter.replaceFile(context, localDir, "notesprout.db", indexFile)
            if (!localIndexCopied) localErrors.add("Failed to copy notesprout.db to local storage.")
        }

        var driveIndexCopied = false
        if (driveClient != null && driveFolderId != null) {
            driveIndexCopied = DriveBackupWriter.replaceFile(driveClient, driveFolderId, "notesprout.db", indexFile)
            if (!driveIndexCopied) driveErrors.add("Failed to copy notesprout.db to Google Drive.")
        }

        // ── Finalise ──────────────────────────────────────────────────────────
        if (localDir != null) {
            results[BackupKind.LOCAL] = DestResult(localAttempted, localSucceeded, localFailed, localSkipped, localIndexCopied, localErrors)
        }
        if (driveClient != null) {
            results[BackupKind.DRIVE] = DestResult(driveAttempted, driveSucceeded, driveFailed, driveSkipped, driveIndexCopied, driveErrors)
        }

        repo.saveBackupConfig(config.copy(lastRunAt = runStart))

        BackupResult(results)
    }

    /**
     * Open one `.soil` and run the same seal-time [NotebookCompactor.compact] pass used on close, so
     * the file about to be backed up is in its leanest form. Mirrors the key resolution of the manual
     * "Compact Notebooks" sweep: plaintext opens with no key; a GLOBAL-scope notebook opens only if
     * its passphrase is cached; a NOTEBOOK-scope notebook (no unattended key) is skipped. Any failure
     * is swallowed — this is best-effort optimisation, never a reason to fail the backup copy that
     * follows. Caller must be on [Dispatchers.IO].
     */
    private suspend fun compactInPlace(context: Context, repo: IndexRepository, notebookId: String) {
        try {
            val info = repo.getEncryptionInfo(notebookId)
            val key: String? = when {
                !info.encrypted -> null
                info.keyScope == KeyScope.GLOBAL ->
                    PassphraseStore.getGlobalPassphrase(context) ?: return
                else -> return
            }
            val file = soilFile(context, notebookId)
            if (!file.exists()) return
            val builder = SoilDatabase.builder(context, file.absolutePath)
            if (key != null) builder.openHelperFactory(SoilCrypto.roomFactory(key))
            val db = builder.build()
            try {
                NotebookCompactor.compact(db, context.resources.displayMetrics.density)
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w("BackupEngine", "Pre-backup compaction failed for $notebookId — backing up as-is: ${e.message}")
        }
    }
}
