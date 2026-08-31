package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownParser
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A **text document's** library cover (arc 19 / M8): its opening lines, rendered through the
 * shared `:markdown` engine onto a white page and stored on the notebook's index row — the same
 * slot [CoverSnapshot] fills for a handwritten notebook, filled by the only thing a text document
 * has to show.
 *
 * **The canvas is a fixed [WIDTH_PX] × [HEIGHT_PX] and the density is a fixed [DENSITY]** — not the
 * device's. A cover rendered against a real screen would come out at a different type size on every
 * device in the family, and the same document's card would look like a different document on the
 * Nomad and the Manta. Fixed size ⇒ constant density ⇒ covers that match. (og's shape, and the
 * reason the numbers below are unitless rather than dp.)
 *
 * It is a **thumbnail of prose**, so legibility beats fidelity: a generous margin, body type big
 * enough to read at card size, a real gap between blocks (the editor Preview's), and the text
 * simply clipped where it runs off the bottom edge — no ellipsis, no fade, nothing that says
 * "there is more" in a way the card itself does not.
 *
 * Caller-agnostic on purpose: the import path calls it the moment the document is created (a
 * notebook may not be opened for weeks, and a card with no cover reads as an empty notebook), and
 * the editor's close path calls it after a flush. Neither knows about the other.
 *
 * Never throws — a cover is a nicety, and a failed one leaves the card exactly as it was. Document
 * text is never logged; lengths only.
 */
object TextCover {

    private const val TAG = "TextCover"

    /** The fixed cover canvas — 3:4, the family's page proportion, and constant across devices. */
    const val WIDTH_PX = 600
    const val HEIGHT_PX = 800

    /** The density every text cover is laid out at, so `:markdown`'s dp-based indents and quote
     *  stripes come out the same size on every device. */
    private const val DENSITY = 2f

    /** Body type on the 600px canvas: ~3% of the width, which reads at card size. Headings scale
     *  off it inside the renderer. */
    private const val BODY_SIZE_PX = 19f

    /** White space around the text block. Generous — a thumbnail that fills its edges reads as
     *  noise rather than as a page. */
    private const val MARGIN_PX = 34

    /** The blank line between blocks — 8dp at [DENSITY], the editor Preview's gap. Prose packed
     *  tight is what an on-page object wants, not what a page of paragraphs wants. */
    private const val BLOCK_GAP_PX = 16

    /**
     * How much of the document is even considered. A cover shows the opening; laying out ten
     * megabytes to throw away all but the first page would be the expensive way to draw the same
     * picture. Both bounds are generous — whichever runs out first wins.
     */
    private const val MAX_LINES = 60
    private const val MAX_CHARS = 2000

    /**
     * Render [markdown]'s opening onto the cover canvas and store it on [notebookId]'s row.
     *
     * Blank text is **not** a skipped cover: it renders the empty page — a white card is what an
     * empty document honestly looks like, and leaving a previous cover standing would show content
     * the document no longer has.
     */
    suspend fun render(repo: IndexRepository, notebookId: String, markdown: String) =
        withContext(Dispatchers.IO) {
            val bitmap = try {
                draw(markdown)
            } catch (e: Exception) {
                // Includes the allocation the device refused. A card with no cover is a card.
                Log.w(TAG, "text cover render failed: ${e.javaClass.simpleName}")
                return@withContext
            }
            try {
                repo.setCover(notebookId, CoverSnapshot.encode(bitmap))
                Slog.d(TAG) { "text cover stored for $notebookId" }
            } catch (e: Exception) {
                Log.w(TAG, "text cover store failed for $notebookId: ${e.javaClass.simpleName}")
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * The page itself. `RGB_565` because the ground is opaque white and the ink is black — half the
     * memory of `ARGB_8888` for a picture with no alpha in it (the F5 bitmap-hygiene rule). The
     * caller owns the returned bitmap and must recycle it.
     */
    fun draw(markdown: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val head = opening(markdown)
        if (head.isBlank()) return bitmap
        val width = WIDTH_PX - 2 * MARGIN_PX
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_SIZE_PX
        }
        val spanned = MarkdownRenderer.render(
            blocks = MarkdownParser.parse(head),
            availableWidthPx = width,
            paint = paint,
            density = DENSITY,
            blockGapPx = BLOCK_GAP_PX,
        )
        val layout = StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, paint, width)
            .build()
        // The bitmap's own bounds are the clip: whatever runs past the bottom edge is simply not
        // drawn, which is the honest thumbnail. No `maxLines` — that would ellipsize.
        canvas.save()
        canvas.translate(MARGIN_PX.toFloat(), MARGIN_PX.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** The opening lines: at most [MAX_LINES] lines and [MAX_CHARS] characters of them. Pure. */
    fun opening(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val capped = if (markdown.length > MAX_CHARS) markdown.take(MAX_CHARS) else markdown
        var cut = 0
        var lines = 0
        while (lines < MAX_LINES) {
            val nl = capped.indexOf('\n', cut)
            if (nl < 0) return capped
            cut = nl + 1
            lines++
        }
        return capped.substring(0, cut)
    }
}
