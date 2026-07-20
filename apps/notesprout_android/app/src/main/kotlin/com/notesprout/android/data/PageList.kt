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
 * Uses a raw ([SoilRawDb]) connection rather than Room on purpose: this is a read-only peek that
 * must not trigger a migration or contend with a live Room connection held by NotebookActivity.
 */
fun loadPageRefs(path: String, passphrase: String?): List<PageRef> {
    var db: SoilRawDb? = null
    return try {
        db = SoilCrypto.openRaw(File(path), passphrase)
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
            result
        }
    } catch (_: Exception) {
        emptyList()
    } finally {
        db?.close()
    }
}
