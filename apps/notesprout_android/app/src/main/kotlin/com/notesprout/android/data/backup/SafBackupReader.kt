package com.notesprout.android.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Read side of the SAF backup destination — used by restore to enumerate and copy backup files. */
object SafBackupReader {

    const val INDEX_NAME = "notesprout.db"

    /** Resolve a picked tree Uri to a readable DocumentFile, or null. */
    fun treeDir(context: Context, treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canRead() }

    /** Child directories of [dir]. */
    fun subDirs(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.isDirectory }

    /** True if [dir] directly contains a backup index (i.e. it is itself a device folder). */
    fun hasIndex(dir: DocumentFile): Boolean =
        dir.findFile(INDEX_NAME)?.isFile == true

    /** The `<uuid>.soil` files directly inside [dir]. */
    fun soilFiles(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".soil") == true }

    /** Copy a DocumentFile's content to [dest]. Returns true on success. */
    fun copyTo(context: Context, doc: DocumentFile, dest: File): Boolean = try {
        context.contentResolver.openInputStream(doc.uri)?.use { inp ->
            dest.outputStream().use { out -> inp.copyTo(out) }
        } != null
    } catch (e: Exception) {
        Log.e("SafBackupReader", "copyTo failed for ${doc.name}: ${e.message}")
        false
    }
}
