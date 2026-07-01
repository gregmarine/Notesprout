package com.notesprout.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.util.Base64
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.CoverObject
import com.notesprout.android.core.markdown.TextObjectRenderer
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LinkObject
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.ShapeObject
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.toLineRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.notebook.ShapeGeometry
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.StickyNoteObject
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.ScratchpadPageContent
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.R
import kotlinx.serialization.json.Json
import com.notesprout.android.data.parseBoundingBox
import java.io.File
import java.io.FileOutputStream

object NotebookExporter {

    /** Collects one sticky note's export metadata for the pdfbox post-process pass. */
    private data class StickyExport(
        val pdfPageIndex: Int,   // 0-based index of the source page in the final PDF
        val iconBox: RectF,      // icon bounding box in source-page pixels (top-left origin)
        val note: StickyNoteRender,
        val pageWidth: Int,      // source page width — fallback when contentWidth == 0
        val pageHeight: Int,     // source page height — fallback when contentHeight == 0
    )

    /**
     * Renders every page of [db] to a PDF and writes it to [context.cacheDir].
     * If the notebook has a `type="cover"` object it becomes page 1 of the PDF.
     * [onProgress] is called on the calling thread (IO) with (currentPage, totalPages)
     * before each notebook page is rendered — callers must post to main thread for UI updates.
     * When [exportPassword] is non-null, post-processes the PDF with AES-128 password protection
     * using PdfBox-Android; the intermediate plaintext PDF is deleted before returning.
     * Returns the written PDF [File].
     */
    suspend fun export(
        context: Context,
        db: SoilDatabase,
        onProgress: (current: Int, total: Int) -> Unit,
        exportPassword: String? = null,
        includeTemplate: Boolean = true,
    ): File {
        val dao = db.notebookDao()

        // Notebook metadata → title for filename
        val notebookObj = dao.getNotebookObject()
        val title = notebookObj?.let {
            runCatching { NotebookMetadata.fromJson(it.id, it.data).title }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: "notebook"
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }

        // Cover page bitmap (null = no cover)
        val notebookId = notebookObj?.id ?: ""
        val coverBitmap: Bitmap? = if (notebookId.isNotEmpty()) {
            val coverRow = dao.getCoverForNotebook(notebookId)
            coverRow?.let { row ->
                runCatching {
                    val co = Json.decodeFromString<CoverObject>(row.data)
                    val bytes = Base64.decode(co.image, Base64.DEFAULT)
                    // Bounded decode (M-1): the cover sizes the PDF page, so cap to MAX_DIMENSION.
                    BitmapDecode.decodeSampled(bytes, BitmapDecode.MAX_DIMENSION, BitmapDecode.MAX_DIMENSION)
                }.getOrNull()
            }
        } else null

        val pages = dao.getPagesSorted()
        val totalPages = pages.size

        val pdf = PdfDocument()
        var pdfPageNumber = 1
        var pdfPageIndex = 0  // 0-based, tracks current page for annotation wiring
        val stickyExports = mutableListOf<StickyExport>()

        // Cover
        if (coverBitmap != null) {
            val w = coverBitmap.width.coerceAtLeast(1)
            val h = coverBitmap.height.coerceAtLeast(1)
            val pageInfo = PdfDocument.PageInfo.Builder(w, h, pdfPageNumber++).create()
            val pdfPage = pdf.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(coverBitmap, 0f, 0f, null)
            pdf.finishPage(pdfPage)
            coverBitmap.recycle()
            pdfPageIndex++
        }

        // Notebook pages
        for ((i, pageRow) in pages.withIndex()) {
            onProgress(i + 1, totalPages)

            val (bitmap, templateBitmap, stickyNotes) = renderPageBitmap(dao, pageRow, context, includeTemplate)

            for (note in stickyNotes) {
                stickyExports += StickyExport(pdfPageIndex, RectF(note.boundingBox), note, bitmap.width, bitmap.height)
            }

            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pdfPageNumber++).create()
            val pdfPage = pdf.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(pdfPage)

            bitmap.recycle()
            templateBitmap?.recycle()
            pdfPageIndex++
        }

        val outDir = File(context.cacheDir, "exported_pdfs").also { it.deleteRecursively(); it.mkdirs() }
        val outFile = File(outDir, "$safeTitle.pdf")

        if (exportPassword != null) {
            // Order: annotate → encrypt
            val tmpA = File(outDir, "${safeTitle}_tmp.pdf")
            FileOutputStream(tmpA).use { pdf.writeTo(it) }
            pdf.close()
            if (stickyExports.isNotEmpty()) {
                val tmpB = File(outDir, "${safeTitle}_noted.pdf")
                addStickyEndnotes(tmpA, tmpB, stickyExports, context)
                encryptPdfFile(tmpB, outFile, exportPassword)
            } else {
                encryptPdfFile(tmpA, outFile, exportPassword)
            }
        } else {
            if (stickyExports.isNotEmpty()) {
                val tmpA = File(outDir, "${safeTitle}_tmp.pdf")
                FileOutputStream(tmpA).use { pdf.writeTo(it) }
                pdf.close()
                addStickyEndnotes(tmpA, outFile, stickyExports, context)
            } else {
                FileOutputStream(outFile).use { pdf.writeTo(it) }
                pdf.close()
            }
        }

        return outFile
    }

    /**
     * Renders selected pages to a single PDF using a transient Room connection for [soilPath].
     * [pageIds] is the ordered list of page UUIDs to include (in display order, caller's
     * responsibility to sort by page index). [notebookTitle] names the output file.
     * [onProgress] is called on the IO thread with (currentPage, totalInSelection) before each
     * page render — callers must post to main thread for UI updates.
     * Does NOT checkpoint on close since NotebookActivity may hold the canonical connection.
     * Returns the written PDF [File].
     */
    suspend fun exportPagesPdf(
        context: Context,
        soilPath: String,
        pageIds: List<String>,
        notebookTitle: String,
        onProgress: (current: Int, total: Int) -> Unit,
        passphrase: String? = null,
        exportPassword: String? = null,
        includeTemplate: Boolean = true,
    ): File {
        val safeTitle = notebookTitle.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }

        val builder = SoilDatabase.builder(context, soilPath)
        if (passphrase != null) builder.openHelperFactory(SoilCrypto.roomFactory(passphrase))
        val db = builder.build()

        try {
            val dao = db.notebookDao()
            val pdf = PdfDocument()
            var pdfPageNumber = 1
            var pdfPageIndex = 0
            val stickyExports = mutableListOf<StickyExport>()
            val total = pageIds.size

            for ((i, pageId) in pageIds.withIndex()) {
                onProgress(i + 1, total)
                val pageRow = dao.getObjectById(pageId) ?: continue
                val (bitmap, templateBitmap, stickyNotes) = renderPageBitmap(dao, pageRow, context, includeTemplate)

                for (note in stickyNotes) {
                    stickyExports += StickyExport(pdfPageIndex, RectF(note.boundingBox), note, bitmap.width, bitmap.height)
                }

                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pdfPageNumber++).create()
                val pdfPage = pdf.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(pdfPage)

                // Recycle per-page bitmaps immediately to keep memory flat across large selections.
                bitmap.recycle()
                templateBitmap?.recycle()
                pdfPageIndex++
            }

            val outDir = File(context.cacheDir, "exported_pdfs").also { it.deleteRecursively(); it.mkdirs() }
            val outFile = File(outDir, "$safeTitle.pdf")

            if (exportPassword != null) {
                val tmpA = File(outDir, "${safeTitle}_tmp.pdf")
                FileOutputStream(tmpA).use { pdf.writeTo(it) }
                pdf.close()
                if (stickyExports.isNotEmpty()) {
                    val tmpB = File(outDir, "${safeTitle}_noted.pdf")
                    addStickyEndnotes(tmpA, tmpB, stickyExports, context)
                    encryptPdfFile(tmpB, outFile, exportPassword)
                } else {
                    encryptPdfFile(tmpA, outFile, exportPassword)
                }
            } else {
                if (stickyExports.isNotEmpty()) {
                    val tmpA = File(outDir, "${safeTitle}_tmp.pdf")
                    FileOutputStream(tmpA).use { pdf.writeTo(it) }
                    pdf.close()
                    addStickyEndnotes(tmpA, outFile, stickyExports, context)
                } else {
                    FileOutputStream(outFile).use { pdf.writeTo(it) }
                    pdf.close()
                }
            }

            return outFile
        } finally {
            db.close()
        }
    }

    /**
     * Renders selected pages to individual PNG files in [context.cacheDir/exported_pngs/].
     * [pages] is a list of (pageId, filenameBase) pairs; [filenameBase] is already sanitized and
     * de-duplicated by the caller. Each file is named `<filenameBase>.png`.
     * Opens a transient Room connection for [soilPath]; does not checkpoint on close.
     * [onProgress] is called on the IO thread with (current, total).
     * Returns the list of written [File]s in input order (null entries are skipped/absent).
     * Note: sticky note icons are drawn on the page; endnote pages are PDF-only and not exported here.
     */
    suspend fun exportPagesPng(
        context: Context,
        soilPath: String,
        pages: List<Pair<String, String>>,   // (pageId, filenameBase)
        onProgress: (current: Int, total: Int) -> Unit,
        passphrase: String? = null,
        includeTemplate: Boolean = true,
    ): List<File> {
        val builder = SoilDatabase.builder(context, soilPath)
        if (passphrase != null) builder.openHelperFactory(SoilCrypto.roomFactory(passphrase))
        val db = builder.build()

        val outDir = File(context.cacheDir, "exported_pngs").also { it.deleteRecursively(); it.mkdirs() }
        val results = mutableListOf<File>()
        val total = pages.size

        try {
            val dao = db.notebookDao()
            for ((i, entry) in pages.withIndex()) {
                val (pageId, fileBase) = entry
                onProgress(i + 1, total)

                val pageRow = dao.getObjectById(pageId) ?: continue
                val (bitmap, templateBitmap, _) = renderPageBitmap(dao, pageRow, context, includeTemplate)

                val outFile = File(outDir, "$fileBase.png")
                FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                // Recycle per-page bitmaps immediately — large selections can be 50+ pages.
                bitmap.recycle()
                templateBitmap?.recycle()

                results.add(outFile)
            }
        } finally {
            db.close()
        }

        return results
    }

    /**
     * Post-processes [input] into an AES-128 password-protected PDF at [output], then deletes
     * [input]. Both [ownerPassword] and [userPassword] are set to [password] so the file opens
     * with a single password in any standard PDF reader. Must be called on a background thread.
     */
    private fun encryptPdfFile(input: File, output: File, password: String) {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(input)
        val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
        val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(password, password, ap)
        policy.encryptionKeyLength = 128
        doc.protect(policy)
        doc.save(output)
        doc.close()
        input.delete()
    }

    /**
     * pdfbox post-process: appends endnote pages for every sticky note in [stickyExports] and
     * wires GoTo link annotations so tapping the on-page icon jumps to the endnote, and tapping
     * the endnote caption returns to the source page. Deletes [input] after saving [output].
     */
    private fun addStickyEndnotes(
        input: File,
        output: File,
        stickyExports: List<StickyExport>,
        context: Context,
    ) {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(input)

        data class EndnoteEntry(
            val endnotePage: com.tom_roush.pdfbox.pdmodel.PDPage,
            val sourcePdfPageIndex: Int,
            val iconBox: RectF,
        )
        val entries = mutableListOf<EndnoteEntry>()

        for ((idx, export) in stickyExports.withIndex()) {
            val noteNumber = idx + 1
            val sourcePageNumber = export.pdfPageIndex + 1  // 1-based for caption text

            val contentW = export.note.contentWidth.takeIf { it > 0 } ?: export.pageWidth.toFloat()
            val contentH = export.note.contentHeight.takeIf { it > 0 } ?: export.pageHeight.toFloat()

            // Render the note's content to a bitmap using the same pipeline as full-page render.
            val contentBitmap = renderPage(
                contentW.toInt().coerceAtLeast(1),
                contentH.toInt().coerceAtLeast(1),
                null,
                export.note.headings,
                export.note.textObjects,
                export.note.lines,
                export.note.strokes,
                context,
                shapeObjects = export.note.shapes,
            )

            // Append a caption strip below the content so the back-link has a visible target.
            val captionH = 60f
            val totalH = contentH + captionH
            val fullBitmap = Bitmap.createBitmap(
                contentW.toInt().coerceAtLeast(1),
                totalH.toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val fullCanvas = Canvas(fullBitmap)
            fullCanvas.drawColor(Color.WHITE)
            fullCanvas.drawBitmap(contentBitmap, 0f, 0f, null)
            contentBitmap.recycle()

            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 32f
                typeface = Typeface.SANS_SERIF
            }
            fullCanvas.drawText(
                "Note $noteNumber — from page $sourcePageNumber (tap to return)",
                16f,
                contentH + captionH * 0.68f,
                captionPaint,
            )

            // Compress to JPEG for the PDF image stream.
            val jpegOut = java.io.ByteArrayOutputStream()
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegOut)
            fullBitmap.recycle()
            val jpegBytes = jpegOut.toByteArray()

            val endnotePage = com.tom_roush.pdfbox.pdmodel.PDPage(
                com.tom_roush.pdfbox.pdmodel.common.PDRectangle(contentW, totalH),
            )
            doc.addPage(endnotePage)

            val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(doc, jpegBytes)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                doc,
                endnotePage,
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.OVERWRITE,
                false,
            ).use { cs ->
                cs.drawImage(pdImage, 0f, 0f, contentW, totalH)
            }

            entries += EndnoteEntry(endnotePage, export.pdfPageIndex, export.iconBox)
        }

        // Wire annotations now that all endnote pages exist in the document.
        for ((noteIdx, entry) in entries.withIndex()) {
            val endnotePage = entry.endnotePage
            val sourcePdfPage = doc.pages[entry.sourcePdfPageIndex]
            val sourcePageH = sourcePdfPage.mediaBox.height
            val endnotePageH = endnotePage.mediaBox.height
            val endnotePageW = endnotePage.mediaBox.width
            val captionH = 60f
            val iconBox = entry.iconBox

            // Source page: icon area → jump to endnote page.
            // Convert from top-left pixel coords to bottom-left PDF points (1px ≈ 1pt here).
            val iconLink = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink()
            iconLink.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(
                iconBox.left,
                sourcePageH - iconBox.bottom,
                iconBox.right,
                sourcePageH - iconBox.top,
            )
            iconLink.borderStyle = invisibleBorder()
            val iconDest = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
            iconDest.page = endnotePage
            val iconAction = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo()
            iconAction.destination = iconDest
            iconLink.action = iconAction
            sourcePdfPage.annotations.add(iconLink)

            // Endnote page: caption strip → jump back to source page.
            val backLink = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink()
            backLink.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(
                0f, 0f, endnotePageW, captionH,
            )
            backLink.borderStyle = invisibleBorder()
            val backDest = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
            backDest.page = sourcePdfPage
            val backAction = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo()
            backAction.destination = backDest
            backLink.action = backAction
            endnotePage.annotations.add(backLink)
        }

        doc.save(output)
        doc.close()
        input.delete()
    }

    /** Returns a new zero-width border style dictionary (invisible link border for e-ink). */
    private fun invisibleBorder(): com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary {
        val b = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary()
        b.width = 0f
        return b
    }

    private fun parseLineRender(id: String, box: RectF, lo: LineObject, densityDp: Float): LineRender {
        val startX: Float; val startY: Float; val endX: Float; val endY: Float
        when (lo.orientation) {
            com.notesprout.android.data.LineOrientation.HORIZONTAL -> {
                startX = box.left; endX = box.right
                startY = box.centerY(); endY = box.centerY()
            }
            com.notesprout.android.data.LineOrientation.VERTICAL -> {
                startY = box.top; endY = box.bottom
                startX = box.centerX(); endX = box.centerX()
            }
        }
        return LineRender(id, box, startX, startY, endX, endY, lo.style, lo.orientation, lo.strokeWidthDp, lo.dotSpacingDp * densityDp)
    }

    private fun parseLinkRender(id: String, box: RectF, lo: LinkObject, densityDp: Float): LinkRender =
        LinkRender(
            id = id,
            boundingBox = box,
            target = lo.target,
            chrome = lo.chrome,
            strokes = lo.strokes,
            headings = lo.headings,
            textObjects = lo.textObjects,
            lines = lo.lines.map { it.toLineRender(densityDp) },
        )

    private fun parseDimensions(boundingBoxJson: String): Pair<Int, Int> {
        val box = BoundingBox.fromJson(boundingBoxJson)
            ?: return Pair(1404, 1872) // safe fallback
        return Pair(box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1))
    }

    private suspend fun loadTemplate(
        dao: NotebookDao,
        pageData: String,
        pageWidth: Int,
        pageHeight: Int,
    ): Bitmap? {
        val templateId = TemplateDialog.parseTemplateId(pageData).takeIf { it.isNotEmpty() }
            ?: return null
        val templateRow = dao.getTemplateById(templateId) ?: return null
        return runCatching {
            val b64 = com.notesprout.android.data.TemplateData.fromJson(templateRow.data)?.image
                ?.takeIf { it.isNotEmpty() } ?: return@runCatching null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // Bounded decode (M-1): cap to the page size this template renders into.
            BitmapDecode.decodeSampled(bytes, pageWidth, pageHeight)
        }.getOrNull()
    }

    /**
     * Shared per-page render used by [export], [exportPage], [exportPagesPdf], and
     * [exportPagesPng]. Loads every content layer from [dao] for [pageRow], renders the page
     * to a new [Bitmap], and returns it together with the (possibly null) template bitmap and
     * the list of sticky notes on the page (for the pdfbox endnote pass). The caller is
     * responsible for recycling both bitmaps after use.
     *
     * When [includeTemplate] is false the page template image (lines/grid) is suppressed — only
     * the content layers (headings, text, lines, shapes, links, sticky icons, strokes) render on
     * white. Used by the "strokes only" export option.
     *
     * Must be called on a background (IO) dispatcher; never call from the UI thread.
     */
    private suspend fun renderPageBitmap(
        dao: NotebookDao,
        pageRow: NotebookObject,
        context: Context,
        includeTemplate: Boolean = true,
    ): Triple<Bitmap, Bitmap?, List<StickyNoteRender>> {
        val (pw, ph) = parseDimensions(pageRow.boundingBox)
        val templateBitmap = if (includeTemplate) loadTemplate(dao, pageRow.data, pw, ph) else null
        val density = context.resources.displayMetrics.density

        val layer = dao.getLayerForPage(pageRow.id)

        val headings: List<HeadingStroke> = if (layer != null) {
            dao.getHeadingsForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val ho = runCatching { HeadingObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                HeadingStroke(
                    id = row.id,
                    boundingBox = box,
                    strokes = ho.strokes,
                    recognizedText = ho.recognizedText,
                    level = ho.level,
                )
            }
        } else emptyList()

        val textObjects: List<TextRender> = if (layer != null) {
            dao.getTextObjectsForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val to = runCatching { TextObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                TextRender(id = row.id, boundingBox = box, text = to.text)
            }
        } else emptyList()

        val lineObjects: List<LineRender> = if (layer != null) {
            dao.getLineObjectsForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val lo = runCatching { LineObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                parseLineRender(row.id, box, lo, density)
            }
        } else emptyList()

        val shapeObjects: List<ShapeRender> = if (layer != null) {
            dao.getShapeObjectsForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val so = runCatching { ShapeObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                ShapeRender.from(row.id, so, density)
            }
        } else emptyList()

        val links: List<LinkRender> = if (layer != null) {
            dao.getLinkObjectsForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val lo = runCatching { LinkObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                parseLinkRender(row.id, box, lo, density)
            }
        } else emptyList()

        val stickyNotes: List<StickyNoteRender> = if (layer != null) {
            dao.getStickyNotesForLayer(layer.id).mapNotNull { row ->
                val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                val obj = runCatching { StickyNoteObject.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                StickyNoteRender(
                    id = row.id,
                    boundingBox = box,
                    strokes = obj.strokes,
                    headings = obj.headings,
                    textObjects = obj.textObjects,
                    lines = obj.lines.map { it.toLineRender(density) },
                    shapes = obj.shapes,
                    contentWidth = obj.contentWidth,
                    contentHeight = obj.contentHeight,
                )
            }
        } else emptyList()

        val strokes: List<LiveStroke> = if (layer != null) {
            dao.getStrokesForLayer(layer.id).mapNotNull { row ->
                val sd = runCatching { StrokeData.fromJson(row.data) }.getOrNull()
                    ?: return@mapNotNull null
                LiveStroke.fromStrokeData(row.id, sd)
            }
        } else emptyList()

        val bitmap = renderPage(pw, ph, templateBitmap, headings, textObjects, lineObjects, strokes, context, links, stickyNotes, shapeObjects)
        return Triple(bitmap, templateBitmap, stickyNotes)
    }

    /**
     * Render already-loaded page content (template + every object layer) to a standalone [Bitmap]
     * at [w]×[h] on white — no live drawing engine. Used by the Day-Detail History▸Notes read-only
     * view to show a past year's day note. Caller owns the returned bitmap (recycle when done).
     * Pure Canvas work; safe to call off the UI thread.
     */
    fun renderContentBitmap(
        w: Int,
        h: Int,
        templateBitmap: Bitmap?,
        content: ScratchpadPageContent,
        context: Context,
    ): Bitmap = renderPage(
        w, h, templateBitmap,
        content.headings, content.textObjects, content.lineObjects, content.strokes,
        context, content.links, content.stickyNotes, content.shapeObjects,
    )

    private fun renderPage(
        w: Int,
        h: Int,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        lineObjects: List<LineRender>,
        strokes: List<LiveStroke>,
        context: Context,
        links: List<LinkRender> = emptyList(),
        stickyNotes: List<StickyNoteRender> = emptyList(),
        shapeObjects: List<ShapeRender> = emptyList(),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.WHITE)

        templateBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        }

        val strokePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.5f
        }
        val densityDp = context.resources.displayMetrics.density
        val textObjectTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
        )
        val textObjectPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textObjectTextSizePx
        }

        fun drawHeadingList(list: List<HeadingStroke>) {
            for (heading in list) {
                if (heading.recognizedText != null) {
                    val box = heading.boundingBox
                    val paddingPx = 8f * densityDp
                    val innerBox = android.graphics.RectF(box.left + paddingPx, box.top + paddingPx, box.right - paddingPx, box.bottom - paddingPx)
                    val widthPx = kotlin.math.ceil(innerBox.width().toDouble()).toInt().coerceAtLeast(1)
                    canvas.save()
                    canvas.clipRect(box)
                    TextObjectRenderer.draw(canvas, TextRender(heading.id, innerBox, heading.recognizedText), widthPx, textObjectPaint, densityDp, maxLines = 1)
                    canvas.restore()
                } else {
                    for (liveStroke in heading.strokes) {
                        val pts = liveStroke.points
                        if (pts.size < 2) continue
                        val path = Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        canvas.drawPath(path, strokePaint)
                    }
                }
            }
        }

        fun drawTextList(list: List<TextRender>) {
            for (textObj in list) {
                TextObjectRenderer.draw(canvas, textObj, w, textObjectPaint, densityDp)
            }
        }

        fun drawLineList(list: List<LineRender>) {
            for (lineObj in list) {
                val sw = lineObj.strokeWidthDp * densityDp
                val linePaint = Paint().apply {
                    isAntiAlias = true
                    color = context.getColor(R.color.inkLight)
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = sw
                }
                when (lineObj.style) {
                    LineStyle.SOLID -> {
                        linePaint.style = Paint.Style.STROKE
                        canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, linePaint)
                    }
                    LineStyle.DASHED -> {
                        linePaint.style = Paint.Style.STROKE
                        linePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f * densityDp, 8f * densityDp), 0f)
                        canvas.drawLine(lineObj.startX, lineObj.startY, lineObj.endX, lineObj.endY, linePaint)
                    }
                    LineStyle.DOTTED -> {
                        linePaint.style = Paint.Style.FILL
                        val spacing = lineObj.dotSpacingPx.takeIf { it > 0f } ?: (sw * 4f)
                        val r = sw / 2f
                        when (lineObj.orientation) {
                            LineOrientation.HORIZONTAL -> {
                                var x = lineObj.startX
                                while (x <= lineObj.endX) { canvas.drawCircle(x, lineObj.startY, r, linePaint); x += spacing }
                            }
                            LineOrientation.VERTICAL -> {
                                var y = lineObj.startY
                                while (y <= lineObj.endY) { canvas.drawCircle(lineObj.startX, y, r, linePaint); y += spacing }
                            }
                        }
                    }
                }
            }
        }

        fun drawShapeList(list: List<ShapeRender>) {
            for (shape in list) {
                val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.BLACK
                    strokeWidth = shape.strokeWidthPx
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawPath(ShapeGeometry.pathFor(shape), shapePaint)
            }
        }

        fun drawStrokeList(list: List<LiveStroke>) {
            for (liveStroke in list) {
                val pts = liveStroke.points
                if (pts.size < 2) continue
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, strokePaint)
            }
        }

        drawHeadingList(headings)
        drawTextList(textObjects)
        drawLineList(lineObjects)
        drawShapeList(shapeObjects)

        // Links render their embedded content only — NO chrome (per Session 5 / export rule).
        // Mirrors the view's drawLinkObject order (headings → text → lines → strokes), after the
        // page's own lines and before its top-level strokes.
        for (link in links) {
            drawHeadingList(link.headings)
            drawTextList(link.textObjects)
            drawLineList(link.lines)
            drawStrokeList(link.strokes)
        }

        // Sticky notes: render the icon only (content is on endnote pages in the PDF post-process).
        // Placed after links, before top-level strokes — mirrors the on-screen render order.
        for (note in stickyNotes) {
            val box = note.boundingBox
            AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.let { drawable ->
                drawable.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
                drawable.draw(canvas)
            }
        }

        drawStrokeList(strokes)

        return bmp
    }

    /**
     * Renders a single page to a PNG and writes it to [context.cacheDir/exported_pngs/].
     * Opens a transient Room instance for [soilPath]; does not checkpoint on close since
     * NotebookActivity's canonical connection is still live.
     * [pageNumber] is the 1-based display index used in the filename.
     * Returns the written PNG [File].
     */
    suspend fun exportPage(
        context: Context,
        soilPath: String,
        pageId: String,
        pageNumber: Int,
        notebookTitle: String,
        passphrase: String? = null,
        includeTemplate: Boolean = true,
    ): File {
        val safeTitle = notebookTitle.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }

        val builder = SoilDatabase.builder(context, soilPath)
        if (passphrase != null) builder.openHelperFactory(SoilCrypto.roomFactory(passphrase))
        val db = builder.build()

        try {
            val dao = db.notebookDao()
            val pageRow = dao.getObjectById(pageId)
                ?: throw IllegalStateException("Page not found: $pageId")

            val (bitmap, templateBitmap, _) = renderPageBitmap(dao, pageRow, context, includeTemplate)
            templateBitmap?.recycle()

            val outDir = File(context.cacheDir, "exported_pngs").also { it.deleteRecursively(); it.mkdirs() }
            val outFile = File(outDir, "${safeTitle}_page${pageNumber}.png")
            FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            return outFile
        } finally {
            db.close()
        }
    }
}
