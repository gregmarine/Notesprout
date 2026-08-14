package com.notesprout.android.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.text.TextPaint
import com.notesprout.android.core.ImageCodec
import com.notesprout.android.core.markdown.TextObjectRenderer

/**
 * Cover renderer for **text documents** — the library card shows the document's opening lines,
 * the way a notebook's card shows its first page. Content-as-cover, via the same Markdown→Canvas
 * path the on-page text objects use ([TextObjectRenderer]).
 *
 * The bitmap is a fixed portrait canvas in its own coordinate space (density is a constant, not
 * the screen's), so the same document renders the same cover on every device. Returned as the
 * base64-WEBP string `IndexRepository.updateNotebookSnapshot` stores — callers still route it
 * through the seal path's `cacheSnapshotIfAllowed`, which owns the encryption leak gate
 * (NOTEBOOK-scope notebooks never cache page content into the index).
 *
 * Thread-safe and view-free; call on Dispatchers.IO. See docs/documents.md § Text documents.
 */
object TextCover {

    private const val WIDTH = 600
    private const val HEIGHT = 800
    private const val PADDING = 44f
    private const val TEXT_SIZE = 24f
    private const val DENSITY = 1.5f
    private const val MAX_LINES = 26

    /** Render [markdown]'s opening lines as a cover, or null when there is nothing to show. */
    fun render(markdown: String): String? {
        val text = markdown.trim()
        if (text.isEmpty()) return null
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = TEXT_SIZE
            }
            TextObjectRenderer.draw(
                canvas,
                TextRender(
                    id = "",
                    boundingBox = RectF(PADDING, PADDING, WIDTH - PADDING, HEIGHT - PADDING),
                    text = text,
                ),
                widthPx = (WIDTH - 2 * PADDING).toInt(),
                paint = paint,
                density = DENSITY,
                maxLines = MAX_LINES,
            )
            return ImageCodec.encodeBase64(bmp)
        } finally {
            bmp.recycle()
        }
    }
}
