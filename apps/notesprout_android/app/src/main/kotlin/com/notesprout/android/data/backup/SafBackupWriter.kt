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
     * Replace a backup file without a window where the destination holds only a truncated copy.
     *
     * SAF has no atomic replace, so: stream into a `.part` sibling first (the existing good backup
     * is untouched if the stream dies — USB unplug, destination full), then swap `.part` into the
     * final name. On providers without rename support, falls back to delete-then-rewrite — the
     * bytes have already streamed once successfully at that point, so the risk window is minimal.
     * Restore ignores `.part`/`.old` names, so a leftover from a killed run is never mistaken for
     * a backup file.
     */
    fun replaceFile(
        context: Context,
        dir: DocumentFile,
        fileName: String,
        source: File,
        mime: String = "application/octet-stream",
    ): Boolean {
        return try {
            // 1. Stream into the temp sibling.
            dir.findFile("$fileName.part")?.delete()
            val part = dir.createFile(mime, "$fileName.part") ?: run {
                Log.e("SafBackupWriter", "createFile returned null for $fileName.part")
                return false
            }
            if (!stream(context, source, part)) {
                part.delete()
                return false
            }

            // 2. Swap: move the old file out of the way, then rename the temp in.
            dir.findFile("$fileName.old")?.delete()
            val old = dir.findFile(fileName)
            if (old != null && !old.renameTo("$fileName.old")) old.delete()
            if (part.renameTo(fileName)) {
                if (part.name != fileName) {
                    Log.w("SafBackupWriter", "SAF renamed $fileName → ${part.name}")
                }
            } else {
                // Provider without rename support: rewrite the final name directly.
                part.delete()
                val target = dir.createFile(mime, fileName) ?: run {
                    Log.e("SafBackupWriter", "createFile returned null for $fileName")
                    dir.findFile("$fileName.old")?.renameTo(fileName)
                    return false
                }
                if (!stream(context, source, target)) {
                    target.delete()
                    dir.findFile("$fileName.old")?.renameTo(fileName)
                    return false
                }
            }
            dir.findFile("$fileName.old")?.delete()
            true
        } catch (e: Exception) {
            Log.e("SafBackupWriter", "replaceFile failed: $fileName", e)
            false
        }
    }

    private fun stream(context: Context, source: File, target: DocumentFile): Boolean = try {
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            source.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
            true
        } ?: run {
            Log.e("SafBackupWriter", "openOutputStream null for ${target.name}")
            false
        }
    } catch (e: Exception) {
        Log.e("SafBackupWriter", "stream failed: ${target.name}", e)
        false
    }
}
