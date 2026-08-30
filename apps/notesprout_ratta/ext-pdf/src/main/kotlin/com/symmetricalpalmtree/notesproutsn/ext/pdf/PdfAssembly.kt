package com.symmetricalpalmtree.notesproutsn.ext.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The assembly (arc 18 / D1, password path D2): a host-rendered [PageBundle] on the read fd becomes
 * a PDF on the write fd. The pages are always drawn with the framework's [PdfDocument]; pdfbox
 * appears only when a password was asked for, and only to re-save the finished document encrypted.
 *
 * **One page in memory at a time**, and the loop is written so that rule cannot slip: read one
 * page, decode it, draw it, `finishPage` (which serializes it — the bitmap is free the moment it
 * returns), recycle, and only then read the next. A whole notebook of full-size bitmaps is an OOM
 * on a 3 GB device.
 *
 * **The protected path's memory shape, stated honestly.** This process writes nothing to disk —
 * ever, by the extension rule — so the intermediate PDF pdfbox has to load cannot be a temp file:
 * it is a byte array. The high-water is therefore roughly *twice the finished document* ([PdfDocument]'s
 * own buffer plus the array taken from it, the buffer released before pdfbox loads), where the plain
 * path pays one. That is far under what the page loop already costs — a single full-size page bitmap
 * dwarfs a whole notebook of WEBP-backed PDF pages — so the one-page rule remains the binding
 * constraint and this is not a second one.
 *
 * **A page that does not decode, or decodes to a size the bundle did not declare, is a delivery
 * failure, not a page to skip.** The host corroborates what it is told; a PDF quietly short of a
 * page — or holding one drawn at the wrong scale — would be reported as a success. So both are an
 * [IllegalStateException] naming the page number and the sizes; sizes are not content.
 *
 * Every `IOException` is re-thrown as an [IllegalStateException] naming the stage — reading the
 * bundle, writing the PDF, or protecting it — and never a path, a payload or a secret, because only
 * the three marshalable shapes reach the host at all.
 */
internal object PdfAssembly {

    /**
     * Assembles [source]'s pages onto [destination] and returns the bytes actually written there.
     *
     * [exportSecret] non-null is the protected path: the same pages, saved through pdfbox with
     * AES-128 standard security. It is **held only for that call and dropped in `finally`** — a
     * String cannot be zeroed, so releasing the reference is the whole of what this side can do,
     * and it is done whichever way the assembly ended. It is never logged, in any form.
     *
     * Both descriptors are owned by the caller's `finally`; the streams here take ownership too and
     * a second close is a no-op. The source is released as soon as the last page is read, before
     * the PDF is written out.
     */
    fun assemble(
        source: ParcelFileDescriptor,
        destination: ParcelFileDescriptor,
        exportSecret: String?,
        tag: String,
    ): Long {
        val startedAt = SystemClock.elapsedRealtime()
        var pages = 0
        var written = 0L
        var secret = exportSecret
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
            written = if (secret == null) {
                stage("writing the PDF") { deliver(destination, tag) { document.writeTo(it) } }
            } else {
                stage("protecting the PDF") { protect(document, destination, secret, tag) }
            }
        } finally {
            // A String cannot be zeroed; dropping every reference this frame holds is the whole of
            // what this side can do, and it is done whichever way the assembly ended.
            secret = null
            // The bytes are already on the destination by here; close only releases native memory,
            // and letting a failure in it mask the real one would cost the host its diagnosis.
            // (The protected path has already closed it — a second close is a no-op.)
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

    /**
     * The protected path: the assembled pages, re-saved by pdfbox under AES-128 standard security
     * with the same password as owner and user, so the file opens with one password in any reader.
     *
     * The intermediate is a byte array because this process writes nothing to disk (see the class
     * KDoc's note on the high-water). [PdfDocument] is closed the moment its bytes are in hand —
     * before pdfbox allocates anything — so the two buffers overlap for as short a time as the
     * shape allows, and the [PDDocument] goes in a `finally` of its own.
     */
    private fun protect(
        document: PdfDocument,
        destination: ParcelFileDescriptor,
        password: String,
        tag: String,
    ): Long {
        val assembled = ByteArrayOutputStream().use { buffer ->
            document.writeTo(buffer)
            buffer.toByteArray()
        }
        runCatching { document.close() }
        // Assigned before anything can throw past it, so the `finally` always has the handle it
        // needs — a PDDocument left open holds the whole document's native memory.
        var loaded: PDDocument? = null
        try {
            val doc = PDDocument.load(assembled)
            loaded = doc
            // Owner and user password the same: the file opens with one password in any reader,
            // and there is no second, weaker way in (og's shape).
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            return deliver(destination, tag) { doc.save(it) }
        } finally {
            runCatching { loaded?.close() }
                .onFailure { Log.w(tag, "protected document close failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Write, flush, `fsync`, and report the measured count — the sync is what makes it durable.
     *  [write] is whichever document has the bytes; both paths end here so both are counted, synced
     *  and reported the same way, and the honest count is measured rather than claimed. */
    private inline fun deliver(
        destination: ParcelFileDescriptor,
        tag: String,
        write: (OutputStream) -> Unit,
    ): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            write(counting)
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
