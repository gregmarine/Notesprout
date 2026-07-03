package com.notesprout.android.recognition

import com.notesprout.android.core.Slog
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.PageText

/**
 * All recognizable content of one page, already loaded from the `.soil` and normalized to the
 * fields the assembler needs. Coordinates are page pixels (top-left origin). See [PageTextRepository]
 * for the loader that builds this from a [com.notesprout.android.data.NotebookDao].
 */
data class PageContent(
    /** Raw handwriting strokes on the page's content layer — the input to [StrokeSegmenter]. */
    val strokes: List<LiveStroke>,
    /** Heading objects that already carry recognized text (unrecognized stroke headings are dropped). */
    val headings: List<HeadingBlock>,
    /** Text objects carrying Markdown source (blank/unrecognized ones are dropped). */
    val textBlocks: List<TextBlock>,
    /** Vertical centers of roughly-horizontal SHAPE lines → Markdown horizontal rules (`---`). */
    val ruleTops: List<Float>,
) {
    data class HeadingBlock(val top: Float, val left: Float, val level: Int, val text: String)
    data class TextBlock(val top: Float, val left: Float, val markdown: String)
}

/**
 * Orchestrates full-page recognition: segment strokes into reading-order lines/paragraphs,
 * recognize each line (context-chained), then interleave the recognized handwriting with the
 * page's heading objects, text objects, and horizontal rules by vertical position into one
 * Markdown document.
 *
 * Engine-agnostic — it only depends on [HandwritingRecognizer]. See docs/handwriting-recognition.md.
 */
class PageTextRecognizer(private val hwr: HandwritingRecognizer) {

    private companion object { const val TAG = "PageTextRecognizer" }

    /** One positioned Markdown block; blocks are sorted by [top] then joined with a blank line. */
    private data class Block(val top: Float, val left: Float, val markdown: String)

    /**
     * Recognize [content] into a [PageText]. Call on a background dispatcher (IO) — it performs
     * one ML Kit round-trip per handwriting line. [sourceMaxUpdatedAt] is the freshness watermark
     * to stamp on the result (the layer's `getMaxContentUpdatedAt` at load time).
     */
    suspend fun recognizePage(content: PageContent, sourceMaxUpdatedAt: Long): PageText {
        val blocks = mutableListOf<Block>()

        // 1. Handwriting strokes → segmented, per-line-recognized paragraphs (context-chained).
        val layout = StrokeSegmenter.segment(content.strokes)
        var pre = ""
        for (para in layout.paragraphs) {
            val lines = mutableListOf<String>()
            for (line in para.lines) {
                val t = hwr.recognizeSegment(line.strokes, line.bounds, pre)
                // Never log the recognized text itself (privacy rule) — only structure/length.
                Slog.d(TAG) { "line ${line.strokes.size} strokes @${line.bounds.top.toInt()} → ${t.length} chars" }
                if (t.isNotBlank() && t != HandwritingRecognizer.FALLBACK_TEXT) {
                    lines += t
                    pre = t   // feed line N into line N+1
                }
            }
            if (lines.isNotEmpty()) {
                val b = para.bounds
                blocks += Block(b.top, b.left, lines.joinToString("\n"))
            }
        }

        // 2. Heading objects — reuse their stored recognized text, re-prefixed from level.
        for (h in content.headings) {
            val md = HeadingObject.applyLevel(HeadingObject.stripHeadingPrefix(h.text), h.level)
                ?.takeIf { it.isNotBlank() } ?: continue
            blocks += Block(h.top, h.left, md)
        }

        // 3. Text objects — already Markdown.
        for (tb in content.textBlocks) {
            if (tb.markdown.isNotBlank()) blocks += Block(tb.top, tb.left, tb.markdown.trim())
        }

        // 4. Horizontal rules from SHAPE lines.
        for (y in content.ruleTops) blocks += Block(y, 0f, "---")

        if (blocks.isEmpty()) {
            return PageText(
                text = "",
                engine = PageText.ENGINE_MLKIT,
                recognizedAt = System.currentTimeMillis(),
                sourceMaxUpdatedAt = sourceMaxUpdatedAt,
            )
        }

        // Reading order: top → bottom, ties broken left → right.
        blocks.sortWith(compareBy({ it.top }, { it.left }))
        val markdown = blocks.joinToString("\n\n") { it.markdown }.trim()

        return PageText(
            text = markdown,
            engine = PageText.ENGINE_MLKIT,
            recognizedAt = System.currentTimeMillis(),
            sourceMaxUpdatedAt = sourceMaxUpdatedAt,
        )
    }
}
