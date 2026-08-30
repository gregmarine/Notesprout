package com.symmetricalpalmtree.notesproutsn.ext.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import java.io.IOException

/**
 * The assembly (arc 18 / D1): a host-rendered [PageBundle] on the read fd becomes a PDF on the
 * write fd, using the framework's [PdfDocument] only — pdfbox is D2's password path and is not a
 * dependency of this phase.
 *
 * **One page in memory at a time**, and the loop is written so that rule cannot slip: read one
 * page, decode it, draw it, `finishPage` (which serializes it — the bitmap is free the moment it
 * returns), recycle, and only then read the next. A whole notebook of full-size bitmaps is an OOM
 * on a 3 GB device.
 *
 * **A page that does not decode, or decodes to a size the bundle did not declare, is a delivery
 * failure, not a page to skip.** The host corroborates what it is told; a PDF quietly short of a
 * page — or holding one drawn at the wrong scale — would be reported as a success. So both are an
 * [IllegalStateException] naming the page number and the sizes; sizes are not content.
 *
 * Every `IOException` is re-thrown as an [IllegalStateException] naming the stage — reading the
 * bundle, or writing the PDF — and never a path or a payload, because only the three marshalable
 * shapes reach the host at all.
 */
internal object PdfAssembly {

    /**
     * Assembles [source]'s pages onto [destination] and returns the bytes actually written there.
     *
     * Both descriptors are owned by the caller's `finally`; the streams here take ownership too and
     * a second close is a no-op. The source is released as soon as the last page is read, before
     * the PDF is written out.
     */
    fun assemble(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, tag: String): Long {
        val startedAt = SystemClock.elapsedRealtime()
        var pages = 0
        var written = 0L
        val document = PdfDocument()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                stage("reading the page bundle") {
                    PageBundle.Reader(input).use { reader ->
                        pages = reader.pageCount
                        for (number in 1..reader.pageCount) {
                            addPage(document, reader.readPage(), number, reader.pageCount)
                        }
                    }
                }
            }
            written = stage("writing the PDF") { deliver(document, destination, tag) }
        } finally {
            // The bytes are already on the destination by here; close only releases native memory,
            // and letting a failure in it mask the real one would cost the host its diagnosis.
            runCatching { document.close() }
                .onFailure { Log.w(tag, "document close failed: ${it.javaClass.simpleName}") }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                tag,
                "assembled $pages page(s) → $written bytes in " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms",
            )
        }
        return written
    }

    /** Decode → draw → finish → recycle. Nothing survives this call but the page inside [document]. */
    private fun addPage(document: PdfDocument, page: PageBundle.Page, number: Int, count: Int) {
        // The host bakes opaque RGB_565 pages (the F5 recipe) — asking for the config the bytes
        // already are halves the decode's footprint and loses nothing.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeByteArray(page.image, 0, page.image.size, options)
            ?: throw IllegalStateException("page $number of $count did not decode")
        try {
            if (bitmap.width != page.widthPx || bitmap.height != page.heightPx) {
                throw IllegalStateException(
                    "page $number of $count decoded ${bitmap.width}x${bitmap.height}, " +
                        "bundle declares ${page.widthPx}x${page.heightPx}",
                )
            }
            // The PDF page is the notebook page's own pixel size — the bundle's declaration, which
            // the decode has just been checked against, so the bitmap lands 1:1 at the origin.
            val info = PdfDocument.PageInfo.Builder(page.widthPx, page.heightPx, number).create()
            val pdfPage = document.startPage(info)
            pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(pdfPage)
        } finally {
            bitmap.recycle()
        }
    }

    /** Write, flush, `fsync`, and report the measured count — the sync is what makes it durable. */
    private fun deliver(document: PdfDocument, destination: ParcelFileDescriptor, tag: String): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            document.writeTo(counting)
            counting.flush()
            try {
                output.fd.sync()
            } catch (e: Exception) {
                // A destination that cannot be synced (a provider handing back a pipe rather than a
                // file) is not an error — there is nothing to flush to.
                Log.w(tag, "destination could not be synced: ${e.javaClass.simpleName}")
            }
            count = counting.count
        }
        return count
    }

    /** Names the stage in the one exception shape that survives the seam. */
    private inline fun <T> stage(name: String, body: () -> T): T =
        try {
            body()
        } catch (e: IOException) {
            throw IllegalStateException("$name failed (${e.javaClass.simpleName})")
        }
}
