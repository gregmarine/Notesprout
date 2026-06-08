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
import android.util.Base64
import android.util.TypedValue
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.data.CoverObject
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.drawing.HEADING_BACKGROUND_COLOR
import kotlinx.serialization.json.Json
import org.json.JSONObject
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

            val strokes: List<LiveStroke> = if (layer != null) {
                dao.getStrokesForLayer(layer.id).mapNotNull { row ->
                    val sd = runCatching { StrokeData.fromJson(row.data) }.getOrNull()
                        ?: return@mapNotNull null
                    LiveStroke(id = row.id, points = sd.toPointFs())
                }
            } else emptyList()

            val bitmap = renderPage(pw, ph, templateBitmap, headings, strokes, context)

            val pageInfo = PdfDocument.PageInfo.Builder(pw, ph, pdfPageNumber++).create()
            val pdfPage = pdf.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(pdfPage)

            bitmap.recycle()
            templateBitmap?.recycle()
        }

        val outDir = File(context.cacheDir, "exported_pdfs").also { it.mkdirs() }
        val outFile = File(outDir, "$safeTitle.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()

        return outFile
    }

    private fun parseDimensions(boundingBoxJson: String): Pair<Int, Int> {
        return try {
            val obj = JSONObject(boundingBoxJson)
            val w = obj.getDouble("width").toInt().coerceAtLeast(1)
            val h = obj.getDouble("height").toInt().coerceAtLeast(1)
            Pair(w, h)
        } catch (e: Exception) {
            Pair(1404, 1872) // safe fallback
        }
    }

    private fun parseBoundingBox(boundingBoxJson: String): RectF? {
        return try {
            val obj = JSONObject(boundingBoxJson)
            val x = obj.getDouble("x").toFloat()
            val y = obj.getDouble("y").toFloat()
            val w = obj.getDouble("width").toFloat()
            val h = obj.getDouble("height").toFloat()
            RectF(x, y, x + w, y + h)
        } catch (e: Exception) {
            null
        }
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
            val dataObj = JSONObject(templateRow.data)
            val b64 = dataObj.getString("image")
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
        val headingTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics
        )
        val headingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.LEFT
            textSize = headingTextSizePx
        }
        val densityDp = context.resources.displayMetrics.density

        for (heading in headings) {
            canvas.drawRect(heading.boundingBox, headingPaint)
            if (heading.recognizedText != null) {
                val box = heading.boundingBox
                canvas.save()
                canvas.clipRect(box)
                val fm = headingTextPaint.fontMetrics
                val textHeight = fm.descent - fm.ascent
                val y = box.top + (box.height() - textHeight) / 2f - fm.ascent
                canvas.drawText(heading.recognizedText, box.left + 8f * densityDp, y, headingTextPaint)
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
}
