package com.notesprout.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.util.Base64
import android.util.TypedValue
import androidx.room.Room
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.data.CoverObject
import com.notesprout.android.core.markdown.TextObjectRenderer
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.R
import com.notesprout.android.notebook.HEADING_BACKGROUND_COLOR
import kotlinx.serialization.json.Json
import com.notesprout.android.data.parseBoundingBox
import java.io.File
import java.io.FileOutputStream

object NotebookExporter {

    /**
     * Renders every page of [db] to a PDF and writes it to [context.cacheDir].
     * If the notebook has a `type="cover"` object it becomes page 1 of the PDF.
     * [onProgress] is called on the calling thread (IO) with (currentPage, totalPages)
     * before each notebook page is rendered — callers must post to main thread for UI updates.
     * Returns the written PDF [File].
     */
    suspend fun export(
        context: Context,
        db: SoilDatabase,
        onProgress: (current: Int, total: Int) -> Unit,
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

        // Cover
        if (coverBitmap != null) {
            val w = coverBitmap.width.coerceAtLeast(1)
            val h = coverBitmap.height.coerceAtLeast(1)
            val pageInfo = PdfDocument.PageInfo.Builder(w, h, pdfPageNumber++).create()
            val pdfPage = pdf.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(coverBitmap, 0f, 0f, null)
            pdf.finishPage(pdfPage)
            coverBitmap.recycle()
        }

        // Notebook pages
        for ((i, pageRow) in pages.withIndex()) {
            onProgress(i + 1, totalPages)

            val (pw, ph) = parseDimensions(pageRow.boundingBox)
            val templateBitmap = loadTemplate(dao, pageRow.data, pw, ph)

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
                    parseLineRender(row.id, box, lo, context.resources.displayMetrics.density)
                }
            } else emptyList()

            val strokes: List<LiveStroke> = if (layer != null) {
                dao.getStrokesForLayer(layer.id).mapNotNull { row ->
                    val sd = runCatching { StrokeData.fromJson(row.data) }.getOrNull()
                        ?: return@mapNotNull null
                    LiveStroke.fromStrokeData(row.id, sd)
                }
            } else emptyList()

            val bitmap = renderPage(pw, ph, templateBitmap, headings, textObjects, lineObjects, strokes, context)

            val pageInfo = PdfDocument.PageInfo.Builder(pw, ph, pdfPageNumber++).create()
            val pdfPage = pdf.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(pdfPage)

            bitmap.recycle()
            templateBitmap?.recycle()
        }

        val outDir = File(context.cacheDir, "exported_pdfs").also { it.deleteRecursively(); it.mkdirs() }
        val outFile = File(outDir, "$safeTitle.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()

        return outFile
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

    private fun parseDimensions(boundingBoxJson: String): Pair<Int, Int> {
        val box = com.notesprout.android.data.BoundingBox.fromJson(boundingBoxJson)
            ?: return Pair(1404, 1872) // safe fallback
        return Pair(box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1))
    }

    private suspend fun loadTemplate(
        dao: com.notesprout.android.data.NotebookDao,
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

    private fun renderPage(
        w: Int,
        h: Int,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke>,
        textObjects: List<TextRender>,
        lineObjects: List<LineRender>,
        strokes: List<LiveStroke>,
        context: Context,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.WHITE)

        templateBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        }

        val headingPaint = Paint().apply {
            style = Paint.Style.FILL
            color = HEADING_BACKGROUND_COLOR
            isAntiAlias = false
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

        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
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

        for (textObj in textObjects) {
            TextObjectRenderer.draw(canvas, textObj, w, textObjectPaint, densityDp)
        }

        for (lineObj in lineObjects) {
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

        for (liveStroke in strokes) {
            val pts = liveStroke.points
            if (pts.size < 2) continue
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, strokePaint)
        }

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
    ): File {
        val safeTitle = notebookTitle.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }

        val db = Room.databaseBuilder(
            context.applicationContext,
            SoilDatabase::class.java,
            soilPath,
        )
            .addCallback(SoilDatabase.openCallback())
            .build()

        try {
            val dao = db.notebookDao()
            val pageRow = dao.getObjectById(pageId)
                ?: throw IllegalStateException("Page not found: $pageId")

            val (pw, ph) = parseDimensions(pageRow.boundingBox)
            val templateBitmap = loadTemplate(dao, pageRow.data, pw, ph)

            val layer = dao.getLayerForPage(pageId)

            val headings: List<HeadingStroke> = if (layer != null) {
                dao.getHeadingsForLayer(layer.id).mapNotNull { row ->
                    val box = parseBoundingBox(row.boundingBox) ?: return@mapNotNull null
                    val ho = runCatching { HeadingObject.fromJson(row.data) }.getOrNull()
                        ?: return@mapNotNull null
                    HeadingStroke(id = row.id, boundingBox = box, strokes = ho.strokes, recognizedText = ho.recognizedText)
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
                    parseLineRender(row.id, box, lo, context.resources.displayMetrics.density)
                }
            } else emptyList()

            val strokes: List<LiveStroke> = if (layer != null) {
                dao.getStrokesForLayer(layer.id).mapNotNull { row ->
                    val sd = runCatching { StrokeData.fromJson(row.data) }.getOrNull()
                        ?: return@mapNotNull null
                    LiveStroke.fromStrokeData(row.id, sd)
                }
            } else emptyList()

            val bitmap = renderPage(pw, ph, templateBitmap, headings, textObjects, lineObjects, strokes, context)
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
