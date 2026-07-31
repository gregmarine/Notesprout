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
    /** Recognizer that produced [text] — "mlkit" / "trocr" ("onyx" later). Lets us upgrade per-engine. */
    val engine: String,
    /** When [text] was produced (Unix epoch ms). */
    val recognizedAt: Long,
    /** `getMaxContentUpdatedAt(layerId)` at recognition time — the freshness watermark. */
    val sourceMaxUpdatedAt: Long,
    /**
     * What the pipeline knew how to read when this text was produced. The watermark only catches the
     * *page* changing; this catches the *recognizer* changing, which a page that has sat still since
     * would otherwise never notice. Bump [CURRENT_SCHEMA] whenever a pass starts covering content it
     * used to miss, and every cache written before it re-recognizes on next use.
     */
    val schema: Int = CURRENT_SCHEMA,
    /**
     * Schema 2: per-line provenance for **handwriting-derived** lines only (headings /
     * text objects / rules excluded) in reading order — powers the viewer's tap-to-correct
     * flow, which needs each line's source strokes to store a training pair. Old schema-1
     * rows decode with `lines = null` (ignoreUnknownKeys) and simply offer no correction
     * until re-recognized.
     */
    val lines: List<RecognizedLine>? = null,
) {
    @Serializable
    data class RecognizedLine(
        val text: String,
        /** Ids of the strokes this line was recognized from. */
        val strokeIds: List<String>,
        /** Line bounds (page px) — vertical position for display ordering. */
        val top: Float,
        val height: Float,
    )

    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        const val ENGINE_MLKIT = "mlkit"
        const val ENGINE_TROCR = "trocr"

        /**
         * 1 — original. 2 — per-line provenance ([lines]). 3 — reads inside composites: content
         * wrapped in a **link**, and the ink of a heading/text object whose recognition failed. Caches
         * below this are incomplete for any page that has one, so they are treated as stale.
         */
        const val CURRENT_SCHEMA = 3

        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): PageText = codec.decodeFromString(serializer(), json)
    }
}
