package com.notesprout.android.data

import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilRawDb
import java.io.File

/**
 * One page of a notebook, in display order.
 *
 * [number] is the 1-based display index; [headingName] is the page's name per the authoritative
 * page-name rule ([topHeadingNamesByPageId]), or null when the page has no heading with recognized
 * text — callers fall back to "Page [number]".
 */
data class PageRef(val id: String, val number: Int, val headingName: String? = null)

/**
 * Read the ordered page list out of the `.soil` at [path] via a short-lived raw connection.
 *
 * [passphrase] is the SQLCipher key, or null for a plaintext notebook. Returns an empty list if the
 * file cannot be opened or read — callers treat that as "nothing to show" rather than an error,
 * which is what every existing call site already does.
 *
 * [rawKey] is the notebook's already-derived raw key when the caller has one cached
 * ([com.notesprout.android.crypto.KeyOpener.cachedRawKey]) — the open then skips SQLCipher's KDF
 * (~300–700 ms → ~35 ms). A stale raw key (file swapped by restore/re-import) falls back to the
 * passphrase open, so passing one is never less correct than not.
 *
 * Uses a raw ([SoilRawDb]) connection rather than Room on purpose: this is a read-only peek that
 * must not trigger a migration or contend with a live Room connection held by NotebookActivity.
 */
fun loadPageRefs(path: String, passphrase: String?, rawKey: ByteArray? = null): List<PageRef> {
    if (passphrase != null && rawKey != null) {
        try {
            return readPageRefs(SoilRawDb.Encrypted(SoilCrypto.openRawEncryptedRawKey(File(path), rawKey)))
        } catch (_: Exception) {
            // Stale cached key — fall through to the passphrase open below.
        }
    }
    return try {
        readPageRefs(SoilCrypto.openRaw(File(path), passphrase))
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * True when the `.soil` at [path] holds a non-blank **notebook document** — a `document` row
 * parented to the notebook root (see docs/documents.md). The export screen uses this to decide
 * whether to offer the Source choice. Same short-lived raw-connection contract as [loadPageRefs];
 * false when the file cannot be opened or the schema predates documents.
 */
fun hasNotebookDocument(path: String, passphrase: String?, rawKey: ByteArray? = null): Boolean {
    if (passphrase != null && rawKey != null) {
        try {
            return readHasNotebookDocument(
                SoilRawDb.Encrypted(SoilCrypto.openRawEncryptedRawKey(File(path), rawKey))
            )
        } catch (_: Exception) {
            // Stale cached key — fall through to the passphrase open below.
        }
    }
    return try {
        readHasNotebookDocument(SoilCrypto.openRaw(File(path), passphrase))
    } catch (_: Exception) {
        false
    }
}

private fun readHasNotebookDocument(db: SoilRawDb): Boolean {
    try {
        db.rawQuery(
            // The notebook-root fallback mirrors DocumentRepository.notebookDocParentId: legacy
            // files with no root row use the NIL UUID.
            "SELECT COUNT(*) FROM notebook WHERE type = 'document' AND deletedAt IS NULL " +
                "AND TRIM(COALESCE(text, '')) <> '' AND parentId = COALESCE(" +
                "(SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1), ?)",
            arrayOf(com.notesprout.android.MainActivity.NIL_UUID),
        ).use { c ->
            return c.moveToNext() && c.getInt(0) > 0
        }
    } finally {
        db.close()
    }
}

private fun readPageRefs(db: SoilRawDb): List<PageRef> {
    try {
        val headingNames = topHeadingNamesByPageId(db)
        db.rawQuery(
            "SELECT id FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC",
            null,
        ).use { c ->
            val result = mutableListOf<PageRef>()
            var number = 1
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                result.add(PageRef(id, number++, headingNames[id]))
            }
            return result
        }
    } finally {
        db.close()
    }
}
