package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Drive REST v3's JSON, in and out (arc 25 / V2) — **kotlinx.serialization only** (the repo's one
 * JSON rule: zero reflection, code-generated; never `org.json`), with `ignoreUnknownKeys` so a field
 * Google adds tomorrow does not break a build from today.
 *
 * Two facts about the wire that everything else can then forget:
 *  - **`size` is a string.** It is an int64 and JSON has no such thing, so Drive quotes it. A
 *    missing or unparseable one is 0 — never a failure, because a size is corroboration and the
 *    host's rule for disagreement is *check the file*, never delete (the arc-15 rule).
 *  - **`modifiedTime` is RFC 3339.** Absent or unparseable is 0, which [CloudEntry] reads as "the
 *    provider did not say".
 *
 * A row Drive reports that this seam cannot describe — an id or a name that fails
 * [CloudContract]'s own checks (a name with a slash in it is possible in Drive and impossible
 * here) — is **skipped**, not failed: one strange neighbour must not make a folder unbrowsable.
 */
object DriveJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** What a reply that could not be read at all becomes. Not [DriveFailures.network]: the network
     *  worked, the provider answered something unexpected. */
    const val UNREADABLE: String = "unreadable reply"

    @Serializable
    private class FileDto(
        val id: String = "",
        val name: String? = null,
        val mimeType: String? = null,
        val size: String? = null,
        val modifiedTime: String? = null,
    )

    @Serializable
    private class FileListDto(
        val files: List<FileDto> = emptyList(),
        val nextPageToken: String? = null,
    )

    @Serializable
    private class UserDto(val emailAddress: String? = null)

    @Serializable
    private class AboutDto(val user: UserDto? = null)

    @Serializable
    private class CreateFolderDto(val name: String, val mimeType: String, val parents: List<String>)

    @Serializable
    private class UploadMetaDto(val name: String? = null, val parents: List<String>? = null)

    /** One page of a listing: the entries this seam can describe, and the token for the next page. */
    class Page(val entries: List<CloudEntry>, val nextPageToken: String?)

    /** Read a `files` reply. */
    fun parseFileList(body: String): Page {
        val dto = decode(FileListDto.serializer(), body)
        return Page(dto.files.mapNotNull { toEntry(it) }, dto.nextPageToken?.takeIf { it.isNotEmpty() })
    }

    /** Read a single-file reply, or null when it is not one this seam can describe. */
    fun parseFile(body: String): CloudEntry? = toEntry(decode(FileDto.serializer(), body))

    /** The id out of a single-file reply, or null. The fallback when a write's reply carried a
     *  shape [parseFile] could not use but did name the file it made. */
    fun parseId(body: String): String? =
        decode(FileDto.serializer(), body).id.takeIf { CloudContract.isEntryId(it) }

    /**
     * The signed-in account's email, or `""` when the reply did not carry one. Empty is legal on
     * the wire (`CloudStatus` allows a connected account with no label), so a provider that will not
     * say who it is never blocks a connection. **The value is user content — never log it.**
     */
    fun parseAboutEmail(body: String): String {
        val email = decode(AboutDto.serializer(), body).user?.emailAddress ?: return ""
        return label(email)
    }

    /** Trim an account label to something [CloudContract.isLabel] accepts; anything else is "". */
    fun label(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length > CloudContract.MAX_ACCOUNT_LABEL_CHARS) return ""
        return if (CloudContract.isLabel(trimmed, CloudContract.MAX_ACCOUNT_LABEL_CHARS)) trimmed else ""
    }

    /** The body that creates a folder. */
    fun folderBody(name: String, parentId: String): String =
        json.encodeToString(
            CreateFolderDto.serializer(),
            CreateFolderDto(name, DriveRest.FOLDER_MIME, listOf(parentId)),
        )

    /** The metadata part of an upload. [parentId] is null on a replace — the file already has its
     *  parent and Drive refuses `parents` on an update. */
    fun uploadMetaBody(name: String, parentId: String?): String =
        json.encodeToString(
            UploadMetaDto.serializer(),
            UploadMetaDto(name, parentId?.let { listOf(it) }),
        )

    /** RFC 3339 to epoch millis; absent, unparseable or before the epoch is 0. */
    fun epochMs(rfc3339: String?): Long {
        if (rfc3339.isNullOrEmpty()) return 0L
        return try {
            Instant.parse(rfc3339).toEpochMilli().coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
    }

    /** Folders first, then files; each group by name, case-insensitively. The order the host's
     *  browser draws and never re-sorts. */
    fun sorted(entries: List<CloudEntry>): List<CloudEntry> =
        entries.sortedWith(
            compareBy<CloudEntry> { if (it.isFolder) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.id },
        )

    /** The seam's ceiling: a longer listing is **truncated, never failed**. */
    fun truncated(entries: List<CloudEntry>): List<CloudEntry> =
        if (entries.size <= CloudContract.MAX_LIST_ENTRIES) entries
        else entries.subList(0, CloudContract.MAX_LIST_ENTRIES)

    private fun toEntry(dto: FileDto): CloudEntry? {
        val id = dto.id
        val name = dto.name ?: return null
        if (!CloudContract.isEntryId(id) || !CloudContract.isName(name)) return null
        val isFolder = dto.mimeType == DriveRest.FOLDER_MIME
        val size = if (isFolder) 0L else (dto.size?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        return CloudEntry(id, name, isFolder, size, epochMs(dto.modifiedTime))
    }

    private fun <T> decode(serializer: kotlinx.serialization.DeserializationStrategy<T>, body: String): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (e: Exception) {
            throw IllegalStateException(UNREADABLE)
        }
}
