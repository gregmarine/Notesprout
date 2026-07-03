package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cached, reading-order recognized text for a single page — the payload of a
 * `type = "page_text"` row (parentId = pageId, one per page). Added as a new object type
 * with **zero schema migration** (the `type` column is a plain string discriminator), and
 * because it lives inside the `.soil` it is SQLCipher-encrypted at rest and travels on
 * export/import for free.
 *
 * [text] is stored as **Markdown** (headings as `#`/`##`/`###`, horizontal rules as `---`,
 * paragraphs separated by blank lines). The plain-text ("text-only") export strips the
 * Markdown syntax at export time — see [com.notesprout.android.recognition.MarkdownText].
 *
 * Staleness reuses the existing snapshot mechanism: if `NotebookDao.getMaxContentUpdatedAt(layerId)`
 * exceeds [sourceMaxUpdatedAt], the cache is stale and must be re-recognized (RTR) or badged
 * "updating…" (viewer).
 *
 * See docs/handwriting-recognition.md § "Storage".
 */
@Serializable
data class PageText(
    /** Assembled, reading-order Markdown. */
    val text: String,
    /** Recognizer that produced [text] — "mlkit" today, "onyx" later. Lets us upgrade per-engine. */
    val engine: String,
    /** When [text] was produced (Unix epoch ms). */
    val recognizedAt: Long,
    /** `getMaxContentUpdatedAt(layerId)` at recognition time — the freshness watermark. */
    val sourceMaxUpdatedAt: Long,
    val schema: Int = 1,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        const val ENGINE_MLKIT = "mlkit"

        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): PageText = codec.decodeFromString(serializer(), json)
    }
}
