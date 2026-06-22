package com.notesprout.android.data.backup

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "DriveApiClient"

@Serializable private data class DriveFile(val id: String, val name: String? = null)
@Serializable private data class DriveFileList(val files: List<DriveFile> = emptyList())
@Serializable private data class CreateFolderBody(
    val name: String, val mimeType: String, val parents: List<String>,
)
@Serializable private data class UploadMeta(
    val name: String? = null, val parents: List<String>? = null,
)
@Serializable private data class DriveUser(val emailAddress: String? = null, val displayName: String? = null)
@Serializable private data class DriveAbout(val user: DriveUser? = null)

private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val FILES = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
private const val ABOUT = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)"
const val ROOT_BACKUP_FOLDER = "Notesprout Backups"

private val codec = Json { ignoreUnknownKeys = true }

class DriveApiClient(private val accessToken: String) {

    /** Returns the connected account email, or null on any failure. */
    fun accountEmail(): String? = try {
        val conn = open("GET", ABOUT)
        val body = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveAbout.serializer(), body).user?.emailAddress
        } else null
    } catch (e: Exception) {
        Log.e(TAG, "accountEmail failed: ${e.message}")
        null
    }

    /**
     * Searches for a child of [parentId] with the given [name].
     * Returns the first matching file ID, or null if none found.
     */
    fun findChild(name: String, parentId: String, foldersOnly: Boolean): String? = try {
        val q = buildChildQuery(name, parentId, foldersOnly)
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=drive&fields=files(id,name)&pageSize=10"
        val conn = open("GET", url)
        val body = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveFileList.serializer(), body).files.firstOrNull()?.id
        } else {
            Log.e(TAG, "findChild HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "findChild failed: ${e.message}")
        null
    }

    /** Finds or creates a folder named [name] inside [parentId]. Returns the folder ID or null. */
    fun ensureFolder(name: String, parentId: String): String? =
        findChild(name, parentId, foldersOnly = true) ?: createFolder(name, parentId)

    private fun createFolder(name: String, parentId: String): String? = try {
        val body = codec.encodeToString(CreateFolderBody.serializer(),
            CreateFolderBody(name, FOLDER_MIME, listOf(parentId)))
        val conn = open("POST", "$FILES?fields=id")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val resp = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveFile.serializer(), resp).id
        } else {
            Log.e(TAG, "createFolder HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "createFolder failed: ${e.message}")
        null
    }

    /**
     * Uploads [source] to Drive as [name] inside [parentId], replacing any existing file (D16).
     * Uses resumable upload: initiate → receive session URI → stream bytes.
     * Returns true on success.
     */
    fun uploadOrReplace(name: String, parentId: String, source: File): Boolean {
        return try {
        val existing = findChild(name, parentId, foldersOnly = false)

        // 1. Initiate resumable upload session.
        val initUrl = if (existing == null)
            "$UPLOAD?uploadType=resumable&fields=id"
        else
            "$UPLOAD/$existing?uploadType=resumable&fields=id"
        val initMethod = if (existing == null) "POST" else "PATCH"

        val metaJson = if (existing == null) {
            codec.encodeToString(UploadMeta.serializer(), UploadMeta(name = name, parents = listOf(parentId)))
        } else {
            codec.encodeToString(UploadMeta.serializer(), UploadMeta())
        }

        val initConn = open(initMethod, initUrl)
        initConn.doOutput = true
        initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        initConn.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
        initConn.setRequestProperty("X-Upload-Content-Length", source.length().toString())
        initConn.outputStream.use { it.write(metaJson.toByteArray(Charsets.UTF_8)) }

        if (initConn.responseCode != 200) {
            Log.e(TAG, "uploadOrReplace initiate HTTP ${initConn.responseCode}")
            return false
        }
        val sessionUri = initConn.getHeaderField("Location")
        if (sessionUri.isNullOrBlank()) {
            Log.e(TAG, "uploadOrReplace: no Location header")
            return false
        }

        // 2. Stream file bytes via PUT to the session URI.
        val uploadConn = URL(sessionUri).openConnection() as HttpURLConnection
        uploadConn.requestMethod = "PUT"
        uploadConn.setFixedLengthStreamingMode(source.length())
        uploadConn.setRequestProperty("Content-Type", "application/octet-stream")
        uploadConn.connectTimeout = 30_000
        uploadConn.readTimeout = 30_000
        uploadConn.doOutput = true
        uploadConn.outputStream.use { out ->
            source.inputStream().use { inp ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
        }
        val code = uploadConn.responseCode
        if (code == 200 || code == 201) true
        else {
            Log.e(TAG, "uploadOrReplace upload HTTP $code")
            false
        }
        } catch (e: Exception) {
            Log.e(TAG, "uploadOrReplace failed: ${e.message}")
            false
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun buildChildQuery(name: String, parentId: String, foldersOnly: Boolean): String {
        val escaped = escapeDriveString(name)
        var q = "name = '$escaped' and '$parentId' in parents and trashed = false"
        if (foldersOnly) q += " and mimeType = '$FOLDER_MIME'"
        return q
    }

    internal fun escapeDriveString(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")

    private fun open(method: String, urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.doInput = true
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String =
        try { conn.inputStream.bufferedReader().readText() }
        catch (_: Exception) { conn.errorStream?.bufferedReader()?.readText() ?: "" }
}
