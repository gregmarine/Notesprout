package com.notesprout.android.recognition

import com.notesprout.android.core.Slog
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.PageText
import com.notesprout.android.data.ShapeType
import com.notesprout.android.data.TYPE_PAGE_TEXT
import com.notesprout.android.data.toHeadingStroke
import com.notesprout.android.data.toShapeRender
import com.notesprout.android.data.toTextRender
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/**
 * DB glue for the recognized-text cache. Loads a page's recognizable content, reads/writes the
 * `page_text` row, and exposes the "fresh-or-recognize" path that RTR, export, and the viewer share.
 *
 * All methods are `suspend` and expect to run on `Dispatchers.IO`. No UI. See
 * docs/handwriting-recognition.md § "Storage" / "Threading, e-ink & correctness rules".
 */
object PageTextRepository {

    private const val TAG = "PageTextRepo"

    /** A SHAPE line counts as a horizontal rule when its rotation is within this of horizontal. */
    private const val RULE_MAX_TILT_DEG = 15f

    /** Load everything on [pageId]'s content layer that recognition can consume. */
    suspend fun loadPageContent(dao: NotebookDao, pageId: String): PageContent {
        val layer = dao.getLayerForPage(pageId)
            ?: return PageContent(emptyList(), emptyList(), emptyList(), emptyList())

        val strokes: List<LiveStroke> = dao.getStrokesForLayer(layer.id).mapNotNull { row ->
            runCatching { LiveStroke.fromRow(row) }.getOrNull()
        }

        val headings = dao.getHeadingsForLayer(layer.id).mapNotNull { row ->
            val h = row.toHeadingStroke() ?: return@mapNotNull null
            val text = h.recognizedText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PageContent.HeadingBlock(top = h.boundingBox.top, left = h.boundingBox.left, level = h.level, text = text)
        }

        val textBlocks = dao.getTextObjectsForLayer(layer.id).mapNotNull { row ->
            val t = row.toTextRender() ?: return@mapNotNull null
            val md = t.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PageContent.TextBlock(top = t.boundingBox.top, left = t.boundingBox.left, markdown = md)
        }

        // Horizontal SHAPE lines → horizontal rules. The Lines tool (type = "line") is template
        // ruling / guides and is intentionally NOT fed here. Density is irrelevant here (we only read
        // type / rotation / centerY, all density-independent), so decode with density = 1f.
        val ruleTops = dao.getShapeObjectsForLayer(layer.id).mapNotNull { row ->
            val sr = row.toShapeRender(1f) ?: return@mapNotNull null
            if (sr.type != ShapeType.LINE) return@mapNotNull null
            // sin(rotation) ≈ 0 for horizontal (rotation near 0° or 180°).
            val tilt = abs(sin(Math.toRadians(sr.rotationDeg.toDouble())))
            if (tilt > sin(Math.toRadians(RULE_MAX_TILT_DEG.toDouble()))) return@mapNotNull null
            sr.centerY
        }

        return PageContent(strokes, headings, textBlocks, ruleTops)
    }

    /** The layer's content watermark for staleness comparison (0 if the page has no content). */
    suspend fun layerMaxUpdatedAt(dao: NotebookDao, pageId: String): Long {
        val layer = dao.getLayerForPage(pageId) ?: return 0L
        return dao.getMaxContentUpdatedAt(layer.id) ?: 0L
    }

    /** The cached [PageText] for [pageId], or null if never recognized. */
    suspend fun getCached(dao: NotebookDao, pageId: String): PageText? {
        val row = dao.getPageTextRow(pageId) ?: return null
        return runCatching { PageText.fromJson(row.data) }.getOrNull()
    }

    /** A cache entry is fresh when its watermark is at least the layer's current max. */
    fun isFresh(cached: PageText?, currentMax: Long): Boolean =
        cached != null && cached.sourceMaxUpdatedAt >= currentMax

    /** Insert-or-replace the single page_text row for [pageId]. */
    suspend fun upsert(dao: NotebookDao, pageId: String, pageText: PageText) {
        val now = System.currentTimeMillis()
        val existing = dao.getPageTextRow(pageId)
        if (existing != null) {
            dao.updateData(existing.id, pageText.toJson(), now)
        } else {
            dao.insertObject(
                NotebookObject(
                    id = UUID.randomUUID().toString(),
                    parentId = pageId,
                    boundingBox = "{}",
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    type = TYPE_PAGE_TEXT,
                    data = pageText.toJson(),
                )
            )
        }
    }

    /**
     * Recognize [pageId] now and write the result to the cache. Runs [recognizer] over freshly
     * loaded content and stamps the current layer watermark. Returns the produced [PageText].
     */
    suspend fun recognizeAndCache(
        dao: NotebookDao,
        pageId: String,
        recognizer: PageTextRecognizer,
    ): PageText {
        val content = loadPageContent(dao, pageId)
        val max = layerMaxUpdatedAt(dao, pageId)
        val result = recognizer.recognizePage(content, sourceMaxUpdatedAt = max)
        upsert(dao, pageId, result)
        Slog.d(TAG) { "Recognized page $pageId (${result.text.length} chars, max=$max)" }
        return result
    }

    /**
     * Return fresh recognized text for [pageId]: reuse the cache when it is up to date, otherwise
     * recognize and cache. The single entry point export/viewer use so a partially-RTR notebook
     * exports fast for done pages and only computes the missing / stale ones.
     */
    suspend fun freshOrRecognize(
        dao: NotebookDao,
        pageId: String,
        recognizer: PageTextRecognizer,
    ): PageText {
        val max = layerMaxUpdatedAt(dao, pageId)
        val cached = getCached(dao, pageId)
        if (isFresh(cached, max)) return cached!!
        return recognizeAndCache(dao, pageId, recognizer)
    }

    /**
     * Like [freshOrRecognize] but never writes: fresh cache is returned as-is, otherwise the page
     * is recognized in memory without upserting. The viewer uses this so it can open its own DB
     * connection (while the notebook is still open elsewhere) without any write contention.
     */
    suspend fun freshOrRecognizeReadOnly(
        dao: NotebookDao,
        pageId: String,
        recognizer: PageTextRecognizer,
    ): PageText {
        val max = layerMaxUpdatedAt(dao, pageId)
        val cached = getCached(dao, pageId)
        if (isFresh(cached, max)) return cached!!
        val content = loadPageContent(dao, pageId)
        return recognizer.recognizePage(content, sourceMaxUpdatedAt = max)
    }
}
