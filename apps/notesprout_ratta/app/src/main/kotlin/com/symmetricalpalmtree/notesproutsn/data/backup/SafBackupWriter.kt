package com.symmetricalpalmtree.notesproutsn.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * The destination side of a backup run (arc 17 / K2): a chosen SAF tree, written to atomically.
 * Hand-rolled over [DocumentsContract] — `androidx.documentfile` is not on the classpath and the
 * no-new-dependencies rule stands; the four calls this needs (query children, create, rename,
 * delete) are all platform API.
 *
 * **[writeAtomic] is the one write path**, og's `.part` discipline made whole: stream to
 * `<name>.part`, verify the landed size, move the previous good copy to `<name>.old`, rename the
 * part in, drop the `.old`. A torn write never replaces a good backup — the worst a crash leaves
 * is a stale `.part`/`.old` pair, both swept before the next write of that name and both invisible
 * to a restore (only `*.soil` / the index name are ever read back).
 *
 * Nothing here throws: every failure logs and answers false/null/empty, and the engine counts it.
 * Content URIs are never logged (a tree URI can carry the folder's display name); file *names*
 * are UUIDs and safe.
 */
class SafBackupWriter(private val resolver: ContentResolver, private val treeUri: Uri) {

    /** One child of a destination directory, as the run needs to see it. */
    data class Entry(val uri: Uri, val name: String, val size: Long, val isDir: Boolean)

    /**
     * The tree's root as a document URI, or null when the grant no longer resolves (folder
     * deleted, SD card ejected, permission revoked) — the engine's fail-fast.
     */
    fun root(): Uri? = try {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            rootUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { if (it.moveToFirst()) rootUri else null }
    } catch (e: Exception) {
        Log.w(TAG, "destination root did not resolve", e)
        null
    }

    /** The children of [dirUri], or null when the listing itself failed (never "empty" for a
     *  failure — the engine must not mistake an unreadable destination for a fresh one). */
    fun list(dirUri: Uri): List<Entry>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(dirUri)
        )
        val out = ArrayList<Entry>()
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                        name = c.getString(1) ?: continue,
                        size = if (c.isNull(2)) -1L else c.getLong(2),
                        isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                )
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "destination listing failed", e)
        null
    }

    fun find(dirUri: Uri, name: String): Entry? = list(dirUri)?.firstOrNull { it.name == name }

    /** Find-or-create the [name] subdirectory of [dirUri] (the debug `dev/` root). */
    fun ensureDir(dirUri: Uri, name: String): Uri? {
        find(dirUri, name)?.let { return if (it.isDir) it.uri else null }
        return try {
            DocumentsContract.createDocument(
                resolver, dirUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not create subdirectory", e)
            null
        }
    }

    /** Best-effort delete; false is only ever bookkeeping (a stale file, retried next run). */
    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        false
    }

    /**
     * Write [source] into [dirUri] as [name], atomically (see class doc). False leaves the
     * previous good copy in place under its own name whenever one existed — the failure modes that
     * cannot are a crash inside the swap itself, which the next run's sweep repairs.
     */
    fun writeAtomic(dirUri: Uri, name: String, source: File): Boolean {
        val partName = name + BackupPredicates.PART_SUFFIX
        val oldName = name + BackupPredicates.OLD_SUFFIX
        try {
            // Sweep leftovers from a killed run first — createDocument over an existing name would
            // otherwise land as "name (1)" and the rename-in would go to the wrong file.
            val before = list(dirUri) ?: return false
            before.firstOrNull { it.name == partName }?.let { delete(it.uri) }
            before.firstOrNull { it.name == oldName }?.let { delete(it.uri) }

            val part = DocumentsContract.createDocument(resolver, dirUri, OCTET_STREAM, partName)
                ?: return false
            var landed = -1L
            resolver.openOutputStream(part, "w").use { outStream ->
                if (outStream == null) return false
                source.inputStream().use { inStream ->
                    landed = inStream.copyTo(outStream)
                }
                outStream.flush()
            }
            // The torn-write check: what the stream counted, and — when the provider will say —
            // what the destination now holds. A mismatch deletes the part and keeps the old copy.
            val expected = source.length()
            val reported = find(dirUri, partName)?.size ?: -1L
            if (landed != expected || (reported >= 0 && reported != expected)) {
                Log.w(TAG, "short write ($landed streamed, $reported landed, $expected expected)")
                find(dirUri, partName)?.let { delete(it.uri) }
                return false
            }

            // The swap. The previous copy steps aside rather than being deleted, so no window has
            // neither file complete under a name a restore would read.
            val existing = find(dirUri, name)
            if (existing != null) {
                if (rename(existing.uri, oldName) == null) {
                    delete(part)
                    return false
                }
            }
            val finalUri = rename(part, name)
            if (finalUri == null) {
                // Roll the old copy back under its name; the part is swept next run.
                find(dirUri, oldName)?.let { rename(it.uri, name) }
                return false
            }
            find(dirUri, oldName)?.let { delete(it.uri) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $name", e)
            return false
        }
    }

    private fun rename(uri: Uri, newName: String): Uri? = try {
        DocumentsContract.renameDocument(resolver, uri, newName)
    } catch (e: Exception) {
        Log.w(TAG, "rename failed", e)
        null
    }

    private companion object {
        const val TAG = "SafBackupWriter"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
