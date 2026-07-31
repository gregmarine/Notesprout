package com.notesprout.android.recognition

import com.notesprout.android.core.Slog
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.PageText
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.ShapeType
import com.notesprout.android.data.TYPE_PAGE_TEXT
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.loadHeadingsSubtree
import com.notesprout.android.data.loadLinksSubtree
import com.notesprout.android.data.loadTextsSubtree
import com.notesprout.android.data.toShapeRender
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

    /**
     * Load everything on [pageId]'s content layer that recognition can consume.
     *
     * The page-level queries are all scoped to `parentId = layerId`, which is exactly what makes a
     * composite's content invisible to them — so this walks into composites too:
     *
     * - **Links** wrap ordinary page content (that is what a link *is*), and their children carry
     *   page-absolute coordinates, so they merge straight into the page's own reading order. A text
     *   object made into a link is still a text object the reader can see.
     * - A **heading or text object whose recognition failed** keeps its ink as child rows. That ink is
     *   what is actually on the page, so it goes into the stroke pool and gets another pass here —
     *   this pipeline segments line by line and can succeed where the single-shot attempt did not.
     *
     * Deliberately left out, all of them things the reader is not meant to read as prose:
     * **sticky notes** (collapsed to an icon, and their children are in the note's *local* coordinate
     * space, so merging them would scatter text across the page), **shapes** other than horizontal
     * rules, and **line objects** — the Lines tool draws page guides, not content, which is why they
     * render in `inkLight` (see docs/content-objects.md).
     */
    suspend fun loadPageContent(dao: NotebookDao, pageId: String): PageContent {
        val layer = dao.getLayerForPage(pageId)
            ?: return PageContent(emptyList(), emptyList(), emptyList(), emptyList())

        val strokes = mutableListOf<LiveStroke>()
        val headings = mutableListOf<PageContent.HeadingBlock>()
        val textBlocks = mutableListOf<PageContent.TextBlock>()
        val ruleTops = mutableListOf<Float>()

        strokes += dao.getStrokesForLayer(layer.id).mapNotNull { row ->
            runCatching { LiveStroke.fromRow(row) }.getOrNull()
        }

        // Subtree loads rather than the bare rows: a recognized heading/text is a single row and reads
        // the same either way, while a failed one brings its ink along.
        dao.loadHeadingsSubtree(layer.id).forEach { collectHeading(it, headings, strokes) }
        dao.loadTextsSubtree(layer.id).forEach { collectText(it, textBlocks, strokes) }

        // Density is irrelevant to everything read here (stroke geometry is px; a shape's type,
        // rotation and centre are density-independent), so decode with density = 1f.
        for (link in dao.loadLinksSubtree(layer.id, 1f)) {
            strokes += link.strokes
            link.headings.forEach { collectHeading(it, headings, strokes) }
            link.textObjects.forEach { collectText(it, textBlocks, strokes) }
            ruleTops += link.shapes.mapNotNull { ruleTopOf(it) }
        }

        ruleTops += dao.getShapeObjectsForLayer(layer.id).mapNotNull { row ->
            ruleTopOf(row.toShapeRender(1f) ?: return@mapNotNull null)
        }

        return PageContent(strokes, headings, textBlocks, ruleTops)
    }

    /** A recognized heading is a block; one whose recognition failed contributes its ink instead. */
    private fun collectHeading(
        h: HeadingStroke,
        headings: MutableList<PageContent.HeadingBlock>,
        strokes: MutableList<LiveStroke>,
    ) {
        val text = h.recognizedText?.takeIf { it.isNotBlank() }
        if (text != null) {
            headings += PageContent.HeadingBlock(
                top = h.boundingBox.top, left = h.boundingBox.left, level = h.level, text = text,
            )
        } else {
            strokes += h.strokes
        }
    }

    /**
     * The same for text objects — but only ever one or the other. A text object converted from ink
     * keeps the original strokes alongside its text, and feeding both would say everything twice.
     */
    private fun collectText(
        t: TextRender,
        textBlocks: MutableList<PageContent.TextBlock>,
        strokes: MutableList<LiveStroke>,
    ) {
        val md = t.text.takeIf { it.isNotBlank() }
        if (md != null) {
            textBlocks += PageContent.TextBlock(
                top = t.boundingBox.top, left = t.boundingBox.left, markdown = md,
            )
        } else {
            strokes += t.strokes.orEmpty()
        }
    }

    /** The y of a shape that reads as a horizontal rule, or null when it is any other shape. */
    private fun ruleTopOf(sr: ShapeRender): Float? {
        if (sr.type != ShapeType.LINE) return null
        // sin(rotation) ≈ 0 for horizontal (rotation near 0° or 180°).
        val tilt = abs(sin(Math.toRadians(sr.rotationDeg.toDouble())))
        if (tilt > sin(Math.toRadians(RULE_MAX_TILT_DEG.toDouble()))) return null
        return sr.centerY
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

    /**
     * A cache entry is fresh when its watermark is at least the layer's current max, it was written by
     * the pipeline this build has, AND (when [expectedEngine] is given) by that engine.
     *
     * The engine check is why switching the Handwriting Engine toggle does not leave the viewer and
     * export serving the other engine's results forever. The schema check is the same idea one level
     * up: the watermark only notices the *page* changing, so a page that has sat still since a
     * recognizer improvement would otherwise keep its incomplete text indefinitely.
     */
    fun isFresh(cached: PageText?, currentMax: Long, expectedEngine: String? = null): Boolean =
        cached != null && cached.sourceMaxUpdatedAt >= currentMax &&
            cached.schema >= PageText.CURRENT_SCHEMA &&
            (expectedEngine == null || cached.engine == expectedEngine)

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
        // Watermark BEFORE content (matching freshOrRecognizeReadOnly): read the other way round,
        // an edit committed between the two queries is covered by the stored watermark but absent
        // from the recognized text — isFresh then reports stale text as current, and the export
        // path (no RTR re-trigger) serves it until the next edit.
        val max = layerMaxUpdatedAt(dao, pageId)
        val content = loadPageContent(dao, pageId)
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
        if (isFresh(cached, max, recognizer.engineName)) return cached!!
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
        if (isFresh(cached, max, recognizer.engineName)) return cached!!
        val content = loadPageContent(dao, pageId)
        return recognizer.recognizePage(content, sourceMaxUpdatedAt = max)
    }
}
