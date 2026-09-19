package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SketchDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SketchRows
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.notebook.PageLabels
import com.symmetricalpalmtree.notesproutsn.notebook.PagePreview
import com.symmetricalpalmtree.notesproutsn.notebook.PageRaster
import com.symmetricalpalmtree.notesproutsn.notebook.PageReads
import com.symmetricalpalmtree.notesproutsn.notebook.SketchRaster
import com.symmetricalpalmtree.notesproutsn.notebook.StickyRows
import com.symmetricalpalmtree.notesproutsn.notebook.StrokeRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * **The other thing that gets exported** (arc 18 / D1): every page of the notebook, baked
 * full-fidelity into one encoded image each and streamed as a [PageBundle] — what a
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.SOURCE_PAGES] exporter receives
 * through its read fd in place of the `.soil`.
 *
 * It exists because of the seam, not in spite of it: **the host renders, the extension assembles.**
 * An exporter that turns a notebook into a document (a PDF) could never receive the file itself —
 * no key crosses — so the one process that *can* read the notebook does the reading, and hands over
 * pixels. The "what a notebook is" question stays host-side for good: when a kind of page arrives
 * that draws differently, it draws differently here and no extension changes.
 *
 * The guards are [ExportOpen]'s — the family's one door, in the order that *is* the invariant (the
 * file is there, the file is not held, there is a key, the open is sealed in a `finally`), because
 * the same things are at stake here as in [ExportArtifact.prepare]: a live writer, a missing key, a
 * file that will not open. What is this file's own:
 *
 *  1. **Nothing is written** — not even `notebook_meta`'s `exportedAt`, which the soil path stamps
 *     because *that file* is the thing travelling. A PDF is not the notebook, and a render must not
 *     mutate what it renders.
 *  2. **Bake page by page** into [ExportArtifact.freshDir] — the same directory the soil copy uses
 *     and [ExportArtifact.clean] wipes, so one `finally` in the screen takes both artifacts away.
 *
 * **One page in memory at a time** is a rule, not an optimisation: a whole notebook of full-size
 * bitmaps is an OOM on a 3 GB device. Each page is allocated, drawn, encoded, appended and recycled
 * before the next one starts, and [PageBundle]'s API is shaped to make anything else awkward.
 *
 * The bake itself is the page as it stands on the glass, minus the chrome: white ground, the
 * template under everything (unless the exporter's page-template toggle says otherwise — arc 18 /
 * D2, the one option this render executes), then the [PagePreview] layering — the one place that
 * order is written down ([PagePreview.drawContent]), which since arc 28 also carries text objects,
 * shapes and sticky icons (never a note's content). No link chrome, no selection chrome; those are
 * the screen's furniture, not the page's content. Pixels are [Bitmap.Config.RGB_565] over an opaque ground and
 * WEBP lossy q100 ([BuiltInTemplates.toWebp] — the app's one measured encoder, the F5 finding),
 * at the **page's own** size and scale 1: a page authored on another panel keeps its own edge, and
 * the screen's size never enters this file. Those last two paragraphs' worth of drawing is
 * [PageRaster]'s since arc 31 / HV2 — page-to-template wants the same picture, and one recipe with
 * two readers is the only way it stays the same picture. What stays here is the *bake*: the scope,
 * the plan, the endnotes, the one-page-at-a-time loop and the template held across pages.
 *
 * **Sketch pages (arc 43 / K7, decision 5).** A page that carries a raster sketch exports as
 * **two** pages: the ink page as above, then the sketch on plain white paper immediately after it
 * ([SketchRaster]). It is a second page rather than a layer because that is what the sketch is —
 * a drawing *beside* the writing, on its own sheet, which the person can read, print or hand on
 * without the notes over it — and because there is no honest way to flatten two pictures a person
 * deliberately kept apart. Everything downstream counts **bundle** pages from there: the endnotes
 * land after the last sketch, the progress line counts them, [PageBundle.MAX_PAGES] is measured
 * against them, and the per-page delivery names them ([ExportNaming.PageName]).
 *
 * **Endnotes (arc 28 / D7).** For an exporter that reads the version-2 bundle, every sticky note
 * with content becomes one more page after the notebook's: its strokes on white at the note's
 * content size, a caption strip under them, and two links in the bundle's trailer (icon → note,
 * caption → source page). [Endnotes] decides the numbering, sizes and links; this file draws
 * them, one page at a time like every other. Facing a version-1 exporter the pages carry their
 * icons and nothing more — the bundle written is exactly the arc-18 one.
 */
object ExportRender {

    private const val TAG = "ExportRender"

    /** Why a render could not produce a bundle. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never render under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** This notebook has its own passphrase and nothing in this process has typed it
         *  (arc 26 / U4) — not a missing key, one notebook that is still shut. */
        LOCKED,

        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** The file would not open, or would not read (wrong key, damaged). */
        UNREADABLE,

        /** No live page at all. [PageBundle] carries one page at least, and a document of nothing
         *  is not a document — so this is an honest refusal, never an empty file. */
        EMPTY,

        /** A page row carries no usable size (a damaged or foreign-written file) — a data problem,
         *  which must not wear [RENDER_FAILED]'s memory-or-space sentence: the user would free
         *  storage and retry forever against a file that never changes (the D3 review). */
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
         *  holding (see [ExportVerification]).
         *
         *  [pageNames] is one entry per baked **bundle** page, in bundle order: the page's number
         *  in the notebook, its topmost heading by the Contents rule ([PageLabels.titleOf]) or null
         *  when it has none (arc 31 / HV1), and whether it is that page's sketch (arc 43 / K7). It
         *  exists for the per-page delivery, which names every file after its own page — read here
         *  because the bake already holds each page's content, and reading it a second time would
         *  be a second full open. It carries the page's *number* rather than leaning on its index
         *  because a sketch page makes the two different things. The endnote pages get **no
         *  entry**: a note is not a page anyone named, and a per-page exporter never sees one (it
         *  declares bundle version 1, so no endnote is planned at all). */
        class Ready(
            val file: File,
            val bytes: Long,
            val pageNames: List<ExportNaming.PageName> = emptyList(),
        ) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    /**
     * Render [notebookId]'s pages into a bundle. IO throughout; never touches the UI, never logs a
     * name or a path. [progress] is called on IO **before** each page with (page number, page
     * count) so the screen can say which one is under the brush — it may suspend to hop to Main,
     * and a slow one only slows the render.
     *
     * [includeTemplate] false bakes the ink on white ground (the arc-18 / D2 toggle). It is the
     * host's answer to give because the bundle carries finished pixels: once a page is baked there
     * is no paper left in it for an extension to take out.
     *
     * [bundleVersion] is the exporter's own ceiling (`ExporterInfo.bundleVersion`): at
     * [PageBundle.VERSION] and above the sticky notes go out as endnotes; below it they stay
     * icons and the bundle is version 1.
     *
     * [pageIds] is the scope (arc 30 / PE2, [ExportScope.pageIds]): null bakes every page, a set
     * bakes those pages only — filtered **before** [plan], so the endnotes (collected from the
     * baked pages) and every count follow the page for free.
     */
    suspend fun render(
        context: Context,
        notebookId: String,
        includeTemplate: Boolean,
        progress: suspend (Int, Int) -> Unit,
        resolved: KeyResolver.Resolved? = null,
        bundleVersion: Int = PageBundle.VERSION_1,
        pageIds: Set<String>? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        // The bake's own failures are caught inside the open, not around it: they mean the *render*
        // failed, which is a different sentence from the file not opening — and the seal still runs.
        val opened = ExportOpen.readOnly(context, notebookId, "render", resolved) { db ->
            try {
                bake(context, db, notebookId, includeTemplate, bundleVersion, pageIds, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class and message only — a render failure's message can carry a path.
                Log.w(TAG, "page render failed: ${e.javaClass.simpleName}")
                Outcome.Failed(Problem.RENDER_FAILED)
            } catch (e: OutOfMemoryError) {
                // A page allocation that the per-page recycle could not save. Not a crash: the
                // notebook is untouched and the screen has a sentence for it.
                Log.w(TAG, "page render ran out of memory")
                Outcome.Failed(Problem.RENDER_FAILED)
            }
        }
        when (opened) {
            is ExportOpen.Opened.Read -> opened.value
            is ExportOpen.Opened.Blocked -> Outcome.Failed(problemOf(opened.guard))
        }
    }

    /** The family's guards in this render's own words — the bake's reasons, one for one. */
    private fun problemOf(guard: ExportOpen.Guard): Problem = when (guard) {
        ExportOpen.Guard.MISSING -> Problem.MISSING
        ExportOpen.Guard.IN_USE -> Problem.IN_USE
        ExportOpen.Guard.NO_KEY -> Problem.NO_KEY
        ExportOpen.Guard.LOCKED -> Problem.LOCKED
        ExportOpen.Guard.UNREADABLE -> Problem.UNREADABLE
    }

    /** One page as the bake takes it: identity, its **own** pixel size, the paper under it,
     *  [number] — what the **notebook** calls this page (arc 34 / L15), which is its place in the
     *  bundle only when the whole notebook is in scope and nothing is interleaved — and
     *  [hasSketch], which says this page exports as two ([bundlePages], decision 5).
     *
     *  [hasSketch] is **planned, not discovered**: it comes from one blob-free query of the pages
     *  that carry a sketch ([com.symmetricalpalmtree.notesproutsn.data.soil.SketchDao.pagesWithSketch]),
     *  asked once per notebook before the first page is drawn, because the bundle has to declare
     *  its page count in its header and a count that found out as it went would already have
     *  written the wrong one. */
    class PageBake(
        val id: String,
        val widthPx: Int,
        val heightPx: Int,
        val templateId: String,
        val number: Int,
        val hasSketch: Boolean = false,
    )

    /**
     * The page rows as the bake reads them — pure, so the three decisions that shape every exported
     * page are pinned by test rather than by eye: **display order** is the row order the DAO
     * already sorted by `"order"`; the size is the **page's own** authored size (the screen's never
     * enters this file, and a notebook written on another panel exports at the edge it was written
     * at); and blank paper is `""`, which is what an absent `refId` *means* in the format, not a
     * missing answer.
     *
     * Null when any row carries no usable size: a page that cannot be drawn at its own size would
     * have to be guessed at or dropped, and a document silently missing a page is worse than one
     * that refuses out loud.
     *
     * [sketched] is the set of page ids carrying a live sketch (arc 43 / K7) — empty for every
     * caller that has none to hand, which is why the whole interleaving can be reasoned about here
     * without a DAO in sight.
     */
    fun plan(
        scoped: List<ExportScope.ScopedPage>,
        sketched: Set<String> = emptySet(),
    ): List<PageBake>? = scoped.map { page ->
        val row = page.row
        val width = (row.width ?: 0f).toInt()
        val height = (row.height ?: 0f).toInt()
        if (width < 1 || height < 1) return null
        PageBake(row.id, width, height, row.refId.orEmpty(), page.number, row.id in sketched)
    }

    /** One page of the **bundle**: which of [plan]'s pages it comes from, and whether it is that
     *  page's sketch rather than its ink. */
    class BundlePage(val index: Int, val sketch: Boolean)

    /**
     * The bundle's pages for [pages] (arc 43 / K7, decision 5): each page's ink, then its sketch
     * when it has one — page 1 ink, page 1 sketch, page 2 ink, … — and the endnotes after all of
     * them. Pure, because every count downstream is derived from it: the header's declared page
     * count, the cap check, the progress line's denominator and the endnotes' first page.
     */
    fun bundlePages(pages: List<PageBake>): List<BundlePage> {
        val bundle = ArrayList<BundlePage>(pages.size)
        pages.forEachIndexed { index, page ->
            bundle += BundlePage(index, sketch = false)
            if (page.hasSketch) bundle += BundlePage(index, sketch = true)
        }
        return bundle
    }

    /**
     * Where each of [pages] lands in the bundle: the 1-based position of its **ink** page, which is
     * its own number shifted by every sketch page before it. This is what a link addresses — the
     * container numbers its own pages, and a sticky note's icon sits on the ink page, never on the
     * sketch beside it.
     */
    fun bundlePositions(pages: List<PageBake>): List<Int> {
        var at = 0
        return pages.map { page ->
            at += 1
            val position = at
            if (page.hasSketch) at += 1
            position
        }
    }

    /**
     * The bundle write. Throws on anything that means the bundle is not whole — a short write, an
     * allocation that failed: a truncated bundle must never reach the exporter, and
     * [PageBundle.Writer.close] enforces the same thing from its own side. The two *data* refusals
     * (an unsized page row, more pages than the container carries) return their own [Problem]s
     * instead of throwing, so they never wear the memory-or-space sentence.
     */
    private suspend fun bake(
        context: Context,
        db: SoilDatabase,
        notebookId: String,
        includeTemplate: Boolean,
        bundleVersion: Int,
        pageIds: Set<String>?,
        progress: suspend (Int, Int) -> Unit,
    ): Outcome {
        val dao = db.dao()
        val scoped = ExportScope.pagesInScope(dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE), pageIds)
        if (scoped.isEmpty()) return Outcome.Failed(Problem.EMPTY)
        // Which pages carry a sketch, asked once for the whole notebook and blob-free (arc 43 /
        // K7): the ids only, never the megabytes, because this question is about the shape of the
        // bundle and not yet about any pixels. A notebook with no sketches pays one indexed query.
        val sketched = db.sketchDao().pagesWithSketch(notebookId).toHashSet()
        // Each refusal keeps its own Problem — routing either through the generic render catch
        // would blame memory or space for a data problem (the D3 review).
        val pages = plan(scoped, sketched) ?: return Outcome.Failed(Problem.DAMAGED)
        // Every count from here is the BUNDLE's, not the notebook's: a sketch page is a page of
        // the document like any other, and the container's cap is about what it holds.
        val bundlePages = bundlePages(pages)
        if (bundlePages.size > PageBundle.MAX_PAGES) return Outcome.Failed(Problem.TOO_LONG)
        // The endnotes are planned before the first page is drawn: the bundle declares its page
        // count and its links up front, and both include the notes (D7).
        val endnotes = if (bundleVersion >= PageBundle.VERSION) {
            Endnotes.plan(endnoteSources(dao, pages), bundlePages.size)
        } else {
            Endnotes.Plan(emptyList(), emptyList())
        }
        val total = bundlePages.size + endnotes.notes.size
        if (total > PageBundle.MAX_PAGES) return Outcome.Failed(Problem.TOO_LONG)

        val bundle = File(ExportArtifact.freshDir(context), "$notebookId.pages")

        val metrics = context.resources.displayMetrics
        // One set for the whole bake: the pages are rendered one at a time on this coroutine, and
        // a Paint / Drawable is mutated as it draws (PagePreview.Paints). A sticky icon that will
        // not load costs the note icons, never the export.
        val paints = PagePreview.Paints.of(
            metrics.scaledDensity,
            runCatching { AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.mutate() }
                .getOrNull(),
        )
        // The pages of a notebook share one template row in the ordinary case, so the decode is
        // held across pages that want the same one and dropped the moment they do not — two
        // bitmaps at the high-water mark instead of one per page decoded again and again.
        var templateId: String? = null
        var template: Bitmap? = null
        // The writer owns the stream from the moment it is constructed — but not before: a
        // constructor that refuses (too many pages) or a header write that fails would otherwise
        // leave the fd open with nothing left holding it.
        val out = FileOutputStream(bundle)
        val bundleWriter = try {
            PageBundle.Writer(out, total, endnotes.links)
        } catch (e: Throwable) {
            runCatching { out.close() }
            throw e
        }
        // One entry per BUNDLE page, in its order — the per-page delivery's filenames (arc 31 /
        // HV1, grown arc 43 / K7). Filled from the content this bake already reads; never from a
        // second open.
        val pageNames = ArrayList<ExportNaming.PageName>(bundlePages.size)
        val sketches = db.sketchDao()
        try {
            bundleWriter.use { writer ->
                // The ink page's own heading, held for the sketch page that follows it: the two
                // pages are one page of the notebook and are named after the same thing.
                var title: String? = null
                bundlePages.forEachIndexed { at, entry ->
                    val page = pages[entry.index]
                    progress(at + 1, total)
                    if (entry.sketch) {
                        writer.writePage(page.widthPx, page.heightPx, sketchImage(sketches, page))
                        pageNames += ExportNaming.PageName(page.number, title, sketch = true)
                        return@forEachIndexed
                    }
                    // White ground is the *absence* of the decode, not a decoded bitmap thrown
                    // away: a template the page will not carry must not cost the page's worth of
                    // memory on the way past (the one-page-at-a-time rule cuts both ways).
                    if (includeTemplate && page.templateId != templateId) {
                        template?.recycle()
                        template = null
                        templateId = page.templateId
                        template = PageRaster.decodeTemplate(dao, page.templateId)
                    }
                    val content = PageReads.content(dao, page.id)
                    title = PageLabels.titleOf(content)
                    pageNames += ExportNaming.PageName(page.number, title)
                    val image = PageRaster.toWebp(
                        page.widthPx, page.heightPx, template, content, metrics.density, paints,
                    )
                    writer.writePage(page.widthPx, page.heightPx, image)
                }
                // The template is done with before the first note: a note has no paper.
                template?.recycle()
                template = null
                for (note in endnotes.notes) {
                    progress(note.page, total)
                    val strokes = dao.childrenOfType(note.stickyId, SoilSchema.TYPE_STROKE)
                        .mapNotNull { StrokeRows.toStroke(it) }
                    val image = bakeEndnote(note, strokes)
                    writer.writePage(note.widthPx, note.heightPx, image)
                }
            }
        } finally {
            template?.recycle()
        }
        val bytes = bundle.length()
        Slog.d(TAG) {
            "rendered ${pages.size} page(s) + ${bundlePages.size - pages.size} sketch(es) + " +
                "${endnotes.notes.size} endnote(s) into $bytes bytes"
        }
        return Outcome.Ready(bundle, bytes, pageNames)
    }

    /**
     * [page]'s sketch, drawn — **both rasters flattened** (arc 45 / G2) — or a blank sheet of its
     * size when neither row is there to draw.
     *
     * The blank is the honest answer, not a fallback dressed up as one: the bundle's header
     * declared its page count before the first page was written, so a sketch that has gone missing
     * between the plan and this line (the header guard refused the stored bytes, the row was cleared
     * by another process, a page was resized) leaves a page that **must** be filled — a writer that
     * simply skipped it would close short and the whole export would be refused as truncated. The
     * alternative, reading every sketch's bytes up front to make the count exact, costs the whole
     * notebook's pixels in memory at once, which is the one thing this render will not do. **One
     * raster surviving is not a blank page**: the flatten draws what is there, which is exactly what
     * a page drawn in pencil alone or in the gel pen alone looks like anyway.
     *
     * **The rows are read here rather than through
     * [com.symmetricalpalmtree.notesproutsn.data.soil.SketchRepository.get]**, which applies the
     * same header guard but **soft-deletes** a row that fails it: a render must not mutate what it
     * renders (rule 1 of this file), and an export is the last place a page's drawing should be
     * dated out from. The guard itself is the shared one ([SketchRows]) — an image that is not
     * exactly this page's size cannot be composited onto it, so it is refused here too, per raster,
     * and left alone for the notebook screen to meet and deal with.
     */
    private suspend fun sketchImage(sketches: SketchDao, page: PageBake): ByteArray {
        val graphite = guarded(sketches, page, SketchContract.LAYER_GRAPHITE)
        val ink = guarded(sketches, page, SketchContract.LAYER_INK)
        if (graphite == null && ink == null) {
            // Ids and sizes, never pixels. Log.w rather than Slog.d: a declared page that came out
            // blank is the sort of thing a person reports about a release build.
            Log.w(TAG, "the sketch of ${page.id} is gone or does not fit — exporting a blank page")
            return SketchRaster.blank(page.widthPx, page.heightPx)
        }
        return SketchRaster.toWebp(page.widthPx, page.heightPx, graphite, ink)
    }

    /** One raster of [page], past the shared header guard, or null — read without the repository's
     *  soft-delete, for [sketchImage]'s reason. */
    private suspend fun guarded(sketches: SketchDao, page: PageBake, layer: Int): ByteArray? {
        val bytes = sketches.sketchFor(page.id, SketchRows.typeFor(layer))
            ?.let { SketchRows.imageBytes(it) }
            ?: return null
        return if (SketchRows.fitsPage(bytes, page.widthPx, page.heightPx)) bytes else null
    }

    /**
     * Every sticky note with content, in **page order then z-order** — loose on the page first,
     * then each link's wrapped ones in link order (the draw order's walk, [PagePreview.drawContent]).
     * Icon-only rows: the notes' strokes are read one note at a time when its page is drawn,
     * never here. A note with no content ([SoilDao.stickyIdsWithContent]) gets no endnote.
     */
    internal suspend fun endnoteSources(dao: SoilDao, pages: List<PageBake>): List<Endnotes.Source> {
        val withContent = dao.stickyIdsWithContent().toHashSet()
        if (withContent.isEmpty()) return emptyList()
        // Where each page's ink actually lands in the bundle — its index plus every sketch page
        // before it (arc 43 / K7). A link that addressed the notebook's numbering would point one
        // page short for every sketch above it.
        val positions = bundlePositions(pages)
        val sources = ArrayList<Endnotes.Source>()
        pages.forEachIndexed { index, page ->
            val rows = ArrayList<SoilObjectEntity>(dao.stickiesOf(page.id))
            for (link in dao.linksOf(page.id)) rows += dao.childrenOfType(link.id, SoilSchema.TYPE_STICKY)
            for (row in rows) {
                if (row.id !in withContent) continue
                val sticky = StickyRows.toSticky(row) ?: continue
                sources += Endnotes.Source(
                    stickyId = sticky.id,
                    // Bundle-relative for the link (the container addresses its own pages);
                    // notebook-relative for what the caption says (arc 34 / L15).
                    fromPage = positions[index],
                    fromPageLabel = page.number,
                    iconL = sticky.x, iconT = sticky.y,
                    iconR = sticky.x + sticky.width, iconB = sticky.y + sticky.height,
                    contentW = sticky.contentW, contentH = sticky.contentH,
                    pageW = page.widthPx, pageH = page.heightPx,
                )
            }
        }
        return sources
    }

    /**
     * One endnote page: the note's strokes (local space — `(0,0)` is the content's top-left, which
     * is the bitmap's) on white, a hairline, then the caption strip. Same pixel recipe as a page
     * (RGB_565, WEBP q100), same recycle-before-the-next rule.
     */
    private fun bakeEndnote(note: Endnotes.Note, strokes: List<Stroke>): ByteArray {
        val w = note.widthPx
        val h = note.heightPx
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${w}x$h endnote would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipRect(0, 0, w, note.contentH)
            StrokeRasterizer.draw(canvas, strokes)
            canvas.restore()
            val top = note.contentH.toFloat()
            canvas.drawRect(0f, top, w.toFloat(), top + 1f, captionPaint)
            val metrics = captionPaint.fontMetrics
            val baseline = top + Endnotes.CAPTION_PX / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(Endnotes.caption(note.number, note.fromPageLabel), Endnotes.CAPTION_INSET_PX, baseline, captionPaint)
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The caption strip's text and rule: black sans at [Endnotes.CAPTION_TEXT_PX]. */
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = Endnotes.CAPTION_TEXT_PX
        typeface = Typeface.SANS_SERIF
    }
}
