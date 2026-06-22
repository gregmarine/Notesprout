package com.notesprout.android.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

object SafBackupWriter {

    fun rootDir(context: Context, treeUri: Uri): DocumentFile? {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return if (doc.canWrite()) doc else null
    }

    fun ensureChildDir(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    /**
     * Replace-in-place: delete existing file with the same name, then create and stream source.
     * SAF delete+create is the standard approach (no atomic replace in SAF).
     * Logs a warning if SAF renames the file (de-dupe / extension append).
     */
    fun replaceFile(
        context: Context,
        dir: DocumentFile,
        fileName: String,
        source: File,
        mime: String = "application/octet-stream",
    ): Boolean {
        return try {
            dir.findFile(fileName)?.delete()
            val target = dir.createFile(mime, fileName) ?: run {
                Log.e("SafBackupWriter", "createFile returned null for $fileName")
                return false
            }
            if (target.name != fileName) {
                Log.w("SafBackupWriter", "SAF renamed $fileName → ${target.name}")
            }
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                source.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            } ?: run {
                Log.e("SafBackupWriter", "openOutputStream null for $fileName")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e("SafBackupWriter", "replaceFile failed: $fileName", e)
            false
        }
    }
}
