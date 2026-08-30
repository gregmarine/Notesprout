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
 * is a stale `.part`/`.old` pair, both invisible to a restore (only `*.soil` / the index name are
 * ever read back). The next write of that name sweeps the `.part`; a `.old` standing **alone** is
 * the last good copy a crash stranded mid-swap and is renamed back, never swept (K3 review).
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
            // One listing serves the whole write (a listing is a whole-directory provider query —
            // K3 review): the leftover sweep, the crash recovery, and the existing-copy lookup.
            // A stray part is swept — createDocument over an existing name would otherwise land
            // as "name (1)" and the rename-in would go to the wrong file.
            val before = list(dirUri) ?: return false
            before.firstOrNull { it.name == partName }?.let { delete(it.uri) }
            var existing = before.firstOrNull { it.name == name }
            before.firstOrNull { it.name == oldName }?.let { staleOld ->
                if (existing == null) {
                    // A crash inside a previous swap: `.old` is the ONLY good copy. It goes back
                    // under its real name, never into the sweep — "a torn write never replaces a
                    // good backup" forbids deleting the last one too (K3 review).
                    val recovered = rename(staleOld.uri, name) ?: return false
                    existing = Entry(recovered, name, staleOld.size, isDir = false)
                } else {
                    delete(staleOld.uri) // a completed swap whose final delete failed — safe now
                }
            }

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
            val reported = sizeOf(part)
            if (landed != expected || (reported >= 0 && reported != expected)) {
                Log.w(TAG, "short write ($landed streamed, $reported landed, $expected expected)")
                delete(part)
                return false
            }

            // The swap. The previous copy steps aside rather than being deleted, so no window has
            // neither file complete under a name a restore would read.
            var oldUri: Uri? = null
            existing?.let {
                oldUri = rename(it.uri, oldName)
                if (oldUri == null) {
                    delete(part)
                    return false
                }
            }
            val finalUri = rename(part, name)
            if (finalUri == null) {
                // Roll the old copy back under its name; the part is swept next run.
                oldUri?.let { rename(it, name) }
                return false
            }
            oldUri?.let { delete(it) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $name", e)
            return false
        }
    }

    /** One document's `COLUMN_SIZE` — a single-document query, never a directory listing. -1 when
     *  the provider will not say. */
    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        Log.w(TAG, "size query failed", e)
        -1L
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
