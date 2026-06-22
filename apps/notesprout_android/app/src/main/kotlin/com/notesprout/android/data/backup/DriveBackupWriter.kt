package com.notesprout.android.data.backup

import java.io.File

object DriveBackupWriter {
    /**
     * Resolves (find-or-create, every run — D16) the path:
     * My Drive / "Notesprout Backups" / <deviceFolderName>
     * Returns the device folder's Drive file ID, or null on failure.
     */
    fun resolveDeviceFolderId(client: DriveApiClient, deviceFolderName: String): String? {
        val root = client.ensureFolder(ROOT_BACKUP_FOLDER, "root") ?: return null
        return client.ensureFolder(deviceFolderName, root)
    }

    /** Replace-in-place one file into the device folder. */
    fun replaceFile(
        client: DriveApiClient,
        deviceFolderId: String,
        fileName: String,
        source: File,
    ): Boolean = client.uploadOrReplace(fileName, deviceFolderId, source)
}
