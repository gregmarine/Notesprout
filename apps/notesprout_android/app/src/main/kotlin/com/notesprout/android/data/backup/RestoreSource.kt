package com.notesprout.android.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A restorable device backup: display name + how many notebooks it holds. */
data class RestoreDevice(val name: String, val notebookCount: Int)

/**
 * Reads a backup destination for restore. A source instance is reused between [listDevices] and
 * [fetchInto] — it caches the resolved handles internally and selects by list index, so the UI only
 * ever deals with display names.
 */
interface RestoreSource {
    /** Device folders available at this source (each contains a notesprout.db). Empty if none/unreachable. */
    suspend fun listDevices(): List<RestoreDevice>

    /**
     * Copy the [deviceIndex]-th device's index into [indexDest] and its .soil files into [gardenDir].
     * Returns the number of .soil files copied. Throws on a fatal error (index missing, IO failure).
     * [onProgress] reports (done, total) across the files copied.
     */
    suspend fun fetchInto(
        deviceIndex: Int,
        indexDest: File,
        gardenDir: File,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Int
}

/** Restore from a SAF tree the user picked (the backup root, or a single device folder). */
class SafRestoreSource(private val context: Context, private val treeUri: Uri) : RestoreSource {

    private var devices: List<DocumentFile> = emptyList()

    override suspend fun listDevices(): List<RestoreDevice> = withContext(Dispatchers.IO) {
        val root = SafBackupReader.treeDir(context, treeUri) ?: return@withContext emptyList()
        val dirs = mutableListOf<DocumentFile>()
        // The picked folder may itself be a device folder, or a parent holding device subfolders.
        if (SafBackupReader.hasIndex(root)) dirs.add(root)
        SafBackupReader.subDirs(root).forEach { if (SafBackupReader.hasIndex(it)) dirs.add(it) }
        devices = dirs
        dirs.map { RestoreDevice(it.name ?: "Backup", SafBackupReader.soilFiles(it).size) }
    }

    override suspend fun fetchInto(
        deviceIndex: Int, indexDest: File, gardenDir: File,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val dir = devices.getOrNull(deviceIndex) ?: error("Invalid backup selection.")
        val index = dir.findFile(SafBackupReader.INDEX_NAME) ?: error("Backup is missing its index.")
        val all = dir.listFiles().filter { it.isFile }
        val soils = all.filter { it.name?.endsWith(".soil") == true }
        // Sidecars exist only for notebooks whose WAL couldn't be absorbed at backup time; they
        // must travel with their .soil or the restored notebook silently loses those writes.
        val walsByName = all.filter { it.name?.endsWith(".soil-wal") == true }.associateBy { it.name }
        val total = soils.size + 1
        var done = 0
        if (!SafBackupReader.copyTo(context, index, indexDest)) error("Failed to read the backup index.")
        onProgress(++done, total)
        for (s in soils) {
            val name = s.name ?: continue
            // Any single failure aborts the whole fetch — a silently incomplete staging set would
            // otherwise be committed as the entire library.
            if (!SafBackupReader.copyTo(context, s, File(gardenDir, name))) {
                error("Failed to read \"$name\" from the backup — your current library is untouched.")
            }
            walsByName["$name-wal"]?.let { wal ->
                if (!SafBackupReader.copyTo(context, wal, File(gardenDir, "$name-wal"))) {
                    error("Failed to read \"$name-wal\" from the backup — your current library is untouched.")
                }
            }
            onProgress(++done, total)
        }
        soils.size
    }
}

/** Restore from Google Drive: My Drive / "Notesprout Backups" / <device> / {notesprout.db, *.soil}. */
class DriveRestoreSource(private val client: DriveApiClient) : RestoreSource {

    private var devices: List<DriveEntry> = emptyList()

    override suspend fun listDevices(): List<RestoreDevice> = withContext(Dispatchers.IO) {
        val backupsId = client.findChild(ROOT_BACKUP_FOLDER, "root", foldersOnly = true)
            ?: return@withContext emptyList()
        val deviceFolders = client.listChildren(backupsId, foldersOnly = true)
        val withIndex = deviceFolders.mapNotNull { folder ->
            val children = client.listChildren(folder.id, foldersOnly = false)
            if (children.none { it.name == SafBackupReader.INDEX_NAME }) return@mapNotNull null
            folder to children.count { it.name.endsWith(".soil") }
        }
        devices = withIndex.map { it.first }
        withIndex.map { RestoreDevice(it.first.name, it.second) }
    }

    override suspend fun fetchInto(
        deviceIndex: Int, indexDest: File, gardenDir: File,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val folder = devices.getOrNull(deviceIndex) ?: error("Invalid backup selection.")
        val children = client.listChildren(folder.id, foldersOnly = false)
        val index = children.firstOrNull { it.name == SafBackupReader.INDEX_NAME }
            ?: error("Backup is missing its index.")
        val soils = children.filter { it.name.endsWith(".soil") }
        // See SafRestoreSource: sidecars travel with their .soil or those writes are lost.
        val walsByName = children.filter { it.name.endsWith(".soil-wal") }.associateBy { it.name }
        val total = soils.size + 1
        var done = 0
        if (!client.downloadTo(index.id, indexDest)) error("Failed to download the backup index.")
        onProgress(++done, total)
        for (s in soils) {
            // Any single failure aborts the whole fetch — a silently incomplete staging set would
            // otherwise be committed as the entire library.
            if (!client.downloadTo(s.id, File(gardenDir, s.name))) {
                error("Failed to download \"${s.name}\" — your current library is untouched.")
            }
            walsByName["${s.name}-wal"]?.let { wal ->
                if (!client.downloadTo(wal.id, File(gardenDir, wal.name))) {
                    error("Failed to download \"${wal.name}\" — your current library is untouched.")
                }
            }
            onProgress(++done, total)
        }
        soils.size
    }
}
