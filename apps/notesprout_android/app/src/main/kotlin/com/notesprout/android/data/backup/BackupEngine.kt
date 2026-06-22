package com.notesprout.android.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.notesprout.android.BuildConfig
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
}
