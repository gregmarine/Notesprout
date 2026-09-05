package com.symmetricalpalmtree.notesproutsn.ext.cloud

import java.net.URLEncoder

/**
 * Drive REST v3's URLs and its query language, as pure builders (arc 25 / V2) — no network, no
 * token, so every shape here is JVM-tested. Read from og Notesprout's `DriveApiClient` and
 * re-derived; nothing is copied.
 *
 * **Escaping is the load-bearing part.** Drive's `q=` is a little language whose string literals are
 * single-quoted, and a file the user named `Don't Panic` would otherwise close the literal and turn
 * the rest of the query into syntax. Backslash first, then quote — the other order doubles its own
 * escapes.
 *
 * Every listing asks for the same [ENTRY_FIELDS] so one parser answers every call, and `size`
 * arrives as a **string** in Drive's JSON (it is an int64) — that is a fact about the wire, handled
 * in [DriveJson].
 */
object DriveRest {

    const val FOLDER_MIME: String = "application/vnd.google-apps.folder"

    const val FILES: String = "https://www.googleapis.com/drive/v3/files"
    const val UPLOAD: String = "https://www.googleapis.com/upload/drive/v3/files"
    const val ABOUT: String = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)"

    /** Everything a [com.symmetricalpalmtree.notesproutsn.extension.CloudEntry] is made of. */
    const val ENTRY_FIELDS: String = "id,name,mimeType,size,modifiedTime"

    /** Drive's own maximum page — one round trip for any folder the host itself wrote. */
    const val LIST_PAGE_SIZE: Int = 1_000

    /** A find only ever wants the first match; ten is enough to see there were siblings. */
    const val FIND_PAGE_SIZE: Int = 10

    /** Drive's alias for "My Drive" — the parent the provider's own root folder is created under. */
    const val MY_DRIVE: String = "root"

    /** Backslash first, then quote. */
    fun escape(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    /** One named child of [parentId], not trashed. */
    fun childQuery(parentId: String, name: String, foldersOnly: Boolean): String {
        var q = "name = '${escape(name)}' and '${escape(parentId)}' in parents and trashed = false"
        if (foldersOnly) q += " and mimeType = '$FOLDER_MIME'"
        return q
    }

    /** Every child of [parentId], not trashed. */
    fun childrenQuery(parentId: String): String =
        "'${escape(parentId)}' in parents and trashed = false"

    fun findUrl(parentId: String, name: String, foldersOnly: Boolean): String =
        "$FILES?q=${enc(childQuery(parentId, name, foldersOnly))}" +
            "&spaces=drive&fields=files($ENTRY_FIELDS)&pageSize=$FIND_PAGE_SIZE"

    fun listUrl(parentId: String, pageToken: String?): String {
        val base = "$FILES?q=${enc(childrenQuery(parentId))}" +
            "&spaces=drive&fields=nextPageToken,files($ENTRY_FIELDS)&pageSize=$LIST_PAGE_SIZE"
        return if (pageToken.isNullOrEmpty()) base else "$base&pageToken=${enc(pageToken)}"
    }

    fun createUrl(): String = "$FILES?fields=$ENTRY_FIELDS"

    fun metadataUrl(fileId: String): String = "$FILES/${enc(fileId)}?fields=$ENTRY_FIELDS"

    fun mediaUrl(fileId: String): String = "$FILES/${enc(fileId)}?alt=media"

    fun deleteUrl(fileId: String): String = "$FILES/${enc(fileId)}"

    fun multipartCreateUrl(): String = "$UPLOAD?uploadType=multipart&fields=$ENTRY_FIELDS"

    fun multipartUpdateUrl(fileId: String): String =
        "$UPLOAD/${enc(fileId)}?uploadType=multipart&fields=$ENTRY_FIELDS"

    fun resumableCreateUrl(): String = "$UPLOAD?uploadType=resumable&fields=$ENTRY_FIELDS"

    fun resumableUpdateUrl(fileId: String): String =
        "$UPLOAD/${enc(fileId)}?uploadType=resumable&fields=$ENTRY_FIELDS"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
