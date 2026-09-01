package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFile
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.extstore.StoreExecutor
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownPaginator
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownParser
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * **The document, on paper** (arc 19 / M9): the notebook's authored Markdown laid out the way the
 * editor's Preview lays it out, sliced into pages and baked into an ordinary [PageBundle] — so
 * `:ext-pdf` receives it none the wiser and assembles a PDF of the *document* with no idea it was
 * not a notebook of ink.
 *
 * That is the point of the shape. The Source row on the Export screen (Notebook pages / Document)
 * is the **host's** question, answered entirely host-side: both answers arrive at the exporter as
 * the same bundle of images through the same fd, and no extension learns that a second kind of page
 * exists. A format that can draw one can draw the other, for free, for ever.
 *
 * The guards are [ExportOpen]'s (the family's one door, in the order that *is* the invariant), and
 * the one-page-at-a-time memory rule is [ExportRender]'s to the letter — the same things are at
 * stake and the same files answer for them. What differs is only what lands on the page:
 *
 *  - **The ground is plain white, always.** There is no template, and the exporter's page-template
 *    toggle is inert here (the Export screen hides its row while Source is Document — GONE, never
 *    disabled). A document is not a page of the notebook wearing its paper; ruled lines under
 *    typeset prose read as a mistake, and the user's phase-start call was that they never appear.
 *  - **The metrics are the Preview's**, mirrored in [DocumentPdfMetrics] (margins, line spacing,
 *    block gap) and topped up with the editor's own saved text size, read from the document
 *    editor's host-owned extension store. Read, never created: an export that minted a store for
 *    an extension would be writing on that extension's behalf, which nothing host-side may do.
 *  - **The page size is the notebook's own** — its first live page row's width and height. A
 *    document exported beside its notebook comes out at the same edge, and the screen's size never
 *    enters this file (the D1 rule). No sized page is [Problem.DAMAGED]: a data problem, refused as
 *    one rather than guessed at.
 *  - **Pagination is [MarkdownPaginator]'s**, on line boundaries, so no line of prose is ever cut
 *    through by a page edge. The layout is built once for the whole document and each page draws a
 *    slice of it — one layout, so page seven cannot disagree with page six about where a paragraph
 *    starts.
 *
 * Document text is never logged — page counts and lengths only.
 */
object DocumentPdfRender {

    private const val TAG = "DocumentPdfRender"

    /** Why a render could not produce a bundle. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never render under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** The file would not open, or would not read (wrong key, damaged). */
        UNREADABLE,

        /** Nothing has been written in this notebook — no notebook document and no page document
         *  (or Markdown that renders to nothing at all). A refusal, never an empty document. */
        NO_DOCUMENT,

        /** No page row carries a usable size, or the page is smaller than its own margins — a data
         *  problem, which must not wear [RENDER_FAILED]'s memory-or-space sentence: the user would
         *  free storage and retry forever against a file that never changes (the D3 review). */
        DAMAGED,

        /** More pages than [PageBundle.MAX_PAGES] — the container's own cap, refused with its own
         *  sentence for the same reason as [DAMAGED]. */
        TOO_LONG,

        /** A page would not allocate, draw, encode or write — out of memory, or out of space. */
        RENDER_FAILED,
    }

    sealed class Outcome {
        /** [file] lives in the cache dir; [bytes] is the bundle's length, for the log and nothing
         *  else — a page bundle's size is deliberately **not** what the destination ends up
         *  holding (see [ExportVerification]). */
        class Ready(val file: File, val bytes: Long) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    /**
     * Lay [notebookId]'s document out and render it into a bundle. IO throughout; never touches the
     * UI, never logs a name, a path or a line of the document. [progress] is called on IO **before**
     * each page with (page number, page count) so the screen can say which one is under the brush —
     * it may suspend to hop to Main, and a slow one only slows the render.
     */
    suspend fun render(
        context: Context,
        notebookId: String,
        progress: suspend (Int, Int) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        // The bake's own failures are caught inside the open, not around it: they mean the *render*
        // failed, which is a different sentence from the file not opening — and the seal still runs.
        val opened = ExportOpen.readOnly(context, notebookId, "render the document") { db ->
            try {
                bake(context, db, notebookId, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class and message only — a render failure's message can carry a path.
                Log.w(TAG, "document page render failed: ${e.javaClass.simpleName}")
                Outcome.Failed(Problem.RENDER_FAILED)
            } catch (e: OutOfMemoryError) {
                // A page allocation that the per-page recycle could not save. Not a crash: the
                // notebook is untouched and the screen has a sentence for it.
                Log.w(TAG, "document page render ran out of memory")
                Outcome.Failed(Problem.RENDER_FAILED)
            }
        }
        when (opened) {
            is ExportOpen.Opened.Read -> opened.value
            is ExportOpen.Opened.Blocked -> Outcome.Failed(problemOf(opened.guard))
        }
    }

    /** The family's guards in this render's own words — the document bake's reasons, one for one. */
    private fun problemOf(guard: ExportOpen.Guard): Problem = when (guard) {
        ExportOpen.Guard.MISSING -> Problem.MISSING
        ExportOpen.Guard.IN_USE -> Problem.IN_USE
        ExportOpen.Guard.NO_KEY -> Problem.NO_KEY
        ExportOpen.Guard.UNREADABLE -> Problem.UNREADABLE
    }

    /**
     * The bundle write. Throws on anything that means the bundle is not whole — a short write, an
     * allocation that failed: a truncated bundle must never reach the exporter, and
     * [PageBundle.Writer.close] enforces the same thing from its own side. The *data* refusals (no
     * document, an unusable page size, more pages than the container carries) return their own
     * [Problem]s instead of throwing, so they never wear the memory-or-space sentence.
     */
    private suspend fun bake(
        context: Context,
        db: SoilDatabase,
        notebookId: String,
        progress: suspend (Int, Int) -> Unit,
    ): Outcome {
        // Always the Markdown, never the plain-text strip: the strip exists to make a *text file*
        // readable without the syntax, and there is no syntax left on a typeset page to take out.
        val markdown = ExportText.markdownOf(db, notebookId) ?: return Outcome.Failed(Problem.NO_DOCUMENT)

        // The notebook's own edge — the first page row's authored size, exactly as the page render
        // takes each page's (the D1 rule read from the document side).
        val first = db.dao().childrenOfType(notebookId, SoilSchema.TYPE_PAGE).firstOrNull()
        val pageW = (first?.width ?: 0f).toInt()
        val pageH = (first?.height ?: 0f).toInt()
        val metrics = context.resources.displayMetrics
        val box = DocumentPdfMetrics.box(pageW, pageH, metrics.density)
            ?: return Outcome.Failed(Problem.DAMAGED)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = DocumentPdfMetrics.textSizePx(editorTextSizeSp(context), metrics.scaledDensity)
            color = Color.BLACK
        }
        val spanned = MarkdownRenderer.render(
            MarkdownParser.parse(markdown),
            availableWidthPx = box.width,
            paint = paint,
            density = metrics.density,
            blockGapPx = DocumentPdfMetrics.px(DocumentPdfMetrics.BLOCK_GAP_DP, metrics.density),
        )
        // Every block ends in '\n', so the last one leaves a trailing newline that StaticLayout
        // turns into a real empty line — and here, a whole trailing page of it (MarkdownDraw's
        // rule, which is why measurement and drawing agree everywhere in this family).
        var end = spanned.length
        while (end > 0 && spanned[end - 1] == '\n') end--
        if (end < spanned.length) spanned.delete(end, spanned.length)
        if (spanned.isEmpty()) return Outcome.Failed(Problem.NO_DOCUMENT)

        // includePad = true is the TextView default, and the parity point: the Preview is a
        // TextView, so anything else here would put the first line a few px off the one the writer
        // was reading.
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, box.width)
            .setLineSpacing(0f, DocumentPdfMetrics.LINE_SPACING_MULTIPLIER)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .build()
        val lines = (0 until layout.lineCount).map {
            MarkdownPaginator.Line(layout.getLineTop(it), layout.getLineTop(it + 1))
        }
        val pages = MarkdownPaginator.paginate(lines, box.height)
        if (pages.isEmpty()) return Outcome.Failed(Problem.NO_DOCUMENT)
        if (pages.size > PageBundle.MAX_PAGES) return Outcome.Failed(Problem.TOO_LONG)

        val bundle = File(ExportArtifact.freshDir(context), "$notebookId.pages")

        // The writer owns the stream from the moment it is constructed — but not before: a
        // constructor that refuses (too many pages) or a header write that fails would otherwise
        // leave the fd open with nothing left holding it.
        val out = FileOutputStream(bundle)
        val bundleWriter = try {
            PageBundle.Writer(out, pages.size)
        } catch (e: Throwable) {
            runCatching { out.close() }
            throw e
        }
        bundleWriter.use { writer ->
            pages.forEachIndexed { index, page ->
                progress(index + 1, pages.size)
                writer.writePage(pageW, pageH, bakePage(pageW, pageH, box, layout, page.top))
            }
        }
        val bytes = bundle.length()
        Slog.d(TAG) { "laid ${spanned.length} chars out over ${pages.size} page(s), $bytes bytes" }
        return Outcome.Ready(bundle, bytes)
    }

    /**
     * One page of the document at the notebook's own pixel size. Opaque by construction: erased to
     * white — the ground, and the whole ground — with the layout drawn on top, which is what makes
     * [Bitmap.Config.RGB_565] correct here rather than merely cheaper (the F5 rule).
     *
     * The slice is a clip and a translate over the one layout: clip to the content box so a line
     * taller than a whole page is cut at the margin rather than bleeding into it, then shift the
     * layout up by [top] — the page's first line's own top, which is what makes every page start
     * flush instead of opening with the gap that separated it from the page before.
     */
    private fun bakePage(
        widthPx: Int,
        heightPx: Int,
        box: DocumentPdfMetrics.Box,
        layout: StaticLayout,
        top: Int,
    ): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${widthPx}x$heightPx page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipRect(box.left, box.top, box.left + box.width, box.top + box.height)
            canvas.translate(box.left.toFloat(), (box.top - top).toFloat())
            layout.draw(canvas)
            canvas.restore()
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            // Before the next page starts — the memory rule, kept where it cannot be forgotten.
            bitmap.recycle()
        }
    }

    /**
     * The editor's saved text size, in sp — the one number this render takes from outside itself.
     *
     * It lives in the **document editor's** host-owned extension store, in the `prefs` table whose
     * shape `DocumentContract` pins (arc 22 / X1 — the ONE extension table the host reads, through
     * its own executor and no binder), so the path is: which package serves the editor point, does
     * that package already have a store, does the store have the table, what does the row say.
     * **Only if the store file and the table already exist** — [ExtensionStores.open] would
     * otherwise create one, and minting a store (or a table) on an extension's behalf from an
     * export is not a thing the host does.
     *
     * Every failure at all — no editor installed, no store, an unparseable value, a locked library
     * — lands on the default. A text size is comfort, and an export must never refuse over one.
     */
    private suspend fun editorTextSizeSp(context: Context): Float = try {
        val pkg = ExtensionRegistry.documentEditor(context)?.packageName
        if (pkg == null || !extensionStoreFile(context, pkg).exists()) {
            DocumentPdfMetrics.DEFAULT_TEXT_SIZE_SP
        } else {
            val db = ExtensionStores.open(context, pkg)
            if (!db.hasTable(DocumentContract.PREFS_TABLE)) {
                DocumentPdfMetrics.DEFAULT_TEXT_SIZE_SP
            } else {
                var raw: String? = null
                db.executor().query(
                    "SELECT ${DocumentContract.PREFS_VALUE_COLUMN} FROM ${DocumentContract.PREFS_TABLE} " +
                        "WHERE ${DocumentContract.PREFS_KEY_COLUMN} = ?",
                    listOf(Cell.Text(DocumentPdfMetrics.TEXT_SIZE_KEY)),
                    object : StoreExecutor.RowSink {
                        override fun columns(names: List<String>) = Unit
                        override fun row(cells: List<Cell>): Boolean {
                            raw = (cells[0] as? Cell.Text)?.value
                            return false
                        }
                    },
                )
                DocumentPdfMetrics.textSizeSp(raw)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Slog.d(TAG) { "no editor text size to read (${e.javaClass.simpleName}) — using the default" }
        DocumentPdfMetrics.DEFAULT_TEXT_SIZE_SP
    }
}
