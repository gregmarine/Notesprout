package com.symmetricalpalmtree.notesproutsn.data.clip

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One `.soil` row, neutral: the universal row shape with its identity and lineage carried as plain
 * strings, and the blob as **Base64**. `createdAt`/`updatedAt`/`deletedAt` deliberately do not
 * travel — a paste is a new row and stamps its own clock, and only live rows are ever captured.
 *
 * **`java.util.Base64`, never `android.util.Base64`**: the android class is a stub under
 * `unitTests.isReturnDefaultValues`, which would make every JVM codec test in this file's suite
 * lie (the N1 `StaticLayout` lesson, applied before it could cost anything).
 */
@Serializable
data class ClipRow(
    val id: String,
    val parentId: String,
    val type: String,
    val order: Int = 0,
    val text: String? = null,
    val refId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Int? = null,
    val blob: String? = null,
) {
    /** The decoded blob, or null when there is none **or it is unusable** — a malformed stroke
     *  blob costs that one stroke (`StrokeRows.toStroke` drops it), never the whole paste. */
    fun blobBytes(): ByteArray? =
        blob?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    companion object {
        fun encodeBlob(bytes: ByteArray?): String? = bytes?.let { Base64.getEncoder().encodeToString(it) }
    }
}

/**
 * What the clipboard holds: a set of `.soil` rows plus the provenance a paste needs, serialized as
 * kotlinx JSON and stored in the global index's single clipboard row ([ClipStore]).
 *
 * The shape is deliberately **kind-discriminated and neutral** — [KIND_PAGE] is arc 7's whole
 * story, but `rows` is already a set, so a later arc can put strokes / headings / links on the same
 * clipboard as `kind = "objects"` with no format change and no migration.
 *
 * [decode] never throws (the `LinkPayload` discipline): an unusable payload simply reads as no
 * clipboard at all. The byte cap is enforced on write **and** read, so a payload that grew past it
 * — or one a future build wrote — is refused whole rather than half-applied.
 */
@Serializable
data class ClipEnvelope(
    val version: Int,
    val kind: String,
    val sourceNotebookId: String,
    val copiedAt: Long,
    val rows: List<ClipRow>,
) {
    companion object {
        /** Envelope grammar version. Written into the row's `flags` too, so the header knows it. */
        const val VERSION = 1

        /** A whole page and everything on it. */
        const val KIND_PAGE = "page"

        /**
         * What a lasso selection caught — strokes, headings and links, with the links' wrapped
         * children (arc 8). The promise arc 7 wrote into this discriminator, kept: no format
         * change, no migration, and **one slot** — a copy of either kind replaces the other.
         *
         * The two kinds differ in what a paste *means*, not in what the envelope holds. A page
         * payload owns a whole self-contained row set (its rows' `"order"` travels verbatim); an
         * objects payload lands *among* the destination page's rows, so its `"order"` is rebased —
         * see [com.symmetricalpalmtree.notesproutsn.notebook.ObjectClip].
         */
        const val KIND_OBJECTS = "objects"

        /**
         * Cap on the encoded payload. Generous — a dense page of ink Base64s to a few MB — but a
         * hard stop: the blob is read whole into memory at paste time, and the index is the wrong
         * place for something unbounded. Over-cap is a refused copy with a problem dialog, never a
         * truncated payload.
         *
         * **The ceiling is not ours, it is the cursor's** (B3 review): SQLCipher reads a row back
         * through an `android.database.CursorWindow` sized `SQLiteCursor.DEFAULT_CURSOR_WINDOW_SIZE`
         * = 8 MiB, so a blob above that can be *written* and then never *read* —
         * `SQLiteBlobTooBigException` at every paste, on a clipboard the sheet is still advertising.
         * A copy that cannot be pasted is worse than a refused copy, so the cap sits well under the
         * window with room for the row around it. Pinned by [MAX_BYTES] guard test.
         */
        const val MAX_BYTES = 6 * 1024 * 1024

        /** The read-back window the cap must stay under — see [MAX_BYTES]. */
        const val CURSOR_WINDOW_BYTES = 8 * 1024 * 1024

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Encoded UTF-8 JSON, or null when it does not fit [MAX_BYTES]. */
        fun encode(env: ClipEnvelope): ByteArray? {
            val bytes = try {
                json.encodeToString(serializer(), env).toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            return bytes.takeIf { it.size <= MAX_BYTES }
        }

        /** The envelope in [bytes], or null for anything unusable — absent, over-cap, malformed,
         *  empty, or written by a **newer** build than this one understands. */
        fun decode(bytes: ByteArray?): ClipEnvelope? {
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val env = try {
                json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
            if (env.version !in 1..VERSION) return null
            if (env.kind.isEmpty() || env.rows.isEmpty()) return null
            return env
        }
    }
}

/**
 * Everything the long-press sheet needs to decide synchronously whether Paste exists — and
 * **not** the payload. Read as a projection ([com.symmetricalpalmtree.notesproutsn.data.index.ObjectDao.clipHeader]),
 * so asking "is there a page on the clipboard?" never drags megabytes out of the encrypted index.
 */
data class ClipHeader(
    val kind: String,
    val sourceNotebookId: String?,
    val copiedAt: Long,
    val version: Int?,
)
