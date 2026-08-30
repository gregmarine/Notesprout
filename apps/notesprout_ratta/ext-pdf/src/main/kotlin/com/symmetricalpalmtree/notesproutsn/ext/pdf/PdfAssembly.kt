package com.symmetricalpalmtree.notesproutsn.ext.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The assembly (arc 18 / D1, reworked at the D3 review): a host-rendered [PageBundle] on the read
 * fd becomes a PDF on the write fd, built with pdfbox on **both** paths.
 *
 * pdfbox everywhere is the D3 review's memory finding made structural. The framework's
 * `PdfDocument` looked like the lighter tool but holds **every page's full-size raster** until
 * `writeTo` — `finishPage` only records a picture that keeps referencing the bitmap, so
 * `recycle()` freed nothing and a long notebook accumulated hundreds of megabytes of native
 * memory before a byte was written (the 13-page walk was far too small to show it). pdfbox holds
 * a page as its **compressed JPEG stream** instead, so what accumulates across the loop is
 * roughly the finished document's own size, and the one full-size bitmap alive at a time really
 * is the high-water mark. The re-encode (WEBP q100 in the bundle → JPEG q100 in the PDF) is the
 * price; at q100 with grey ink it is not a visible one, and it is what lets a photo-templated
 * page stay compressed instead of ballooning through a lossless pass.
 *
 * **One page in memory at a time**, and now the document keeps the rule too: read one page,
 * decode it, re-encode it into the document, recycle, and only then read the next.
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
     * [exportSecret] non-null is the protected path: the same document, saved under **AES-128**
     * standard security with the same password as owner and user. `setPreferAES(true)` is what
     * makes 128-bit mean AES — without it pdfbox emits the deprecated RC4 cipher at the same key
     * length, a file every reader still opens and no checklist can tell apart (the D3 review's
     * cipher finding). The secret is **held only for that call and dropped in `finally`** — a
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
        val written: Long
        var secret = exportSecret
        val document = PDDocument()
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
                stage("writing the PDF") { deliver(document, destination, tag) }
            } else {
                stage("protecting the PDF") {
                    // Owner and user password the same: the file opens with one password in any
                    // reader, and there is no second, weaker way in (og's shape). The encryption
                    // runs as the save streams each object out — no second copy of the document.
                    val policy = StandardProtectionPolicy(secret, secret, AccessPermission())
                    policy.encryptionKeyLength = 128
                    policy.setPreferAES(true)
                    document.protect(policy)
                    deliver(document, destination, tag)
                }
            }
        } finally {
            // A String cannot be zeroed; dropping every reference this frame holds is the whole of
            // what this side can do, and it is done whichever way the assembly ended.
            secret = null
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

    /** Decode → re-encode → attach to the document → recycle. Nothing survives this call but the
     *  page's compressed JPEG stream inside [document]. */
    private fun addPage(document: PDDocument, page: PageBundle.Page, number: Int, count: Int) {
        // The host bakes opaque RGB_565 pages (the F5 recipe) — asking for the config the bytes
        // already are halves the decode's footprint and loses nothing.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeByteArray(page.image, 0, page.image.size, options)
            ?: throw IllegalStateException("page $number of $count did not decode")
        val jpeg: ByteArray
        try {
            if (bitmap.width != page.widthPx || bitmap.height != page.heightPx) {
                throw IllegalStateException(
                    "page $number of $count decoded ${bitmap.width}x${bitmap.height}, " +
                        "bundle declares ${page.widthPx}x${page.heightPx}",
                )
            }
            // JPEG so the document holds the page compressed (the memory rule applied to the
            // document itself); q100 because the bundle already paid its one lossy pass and the
            // second must not be a visible one.
            val buffer = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, buffer)) {
                throw IllegalStateException("page $number of $count would not re-encode")
            }
            jpeg = buffer.toByteArray()
        } finally {
            bitmap.recycle()
        }
        stage("assembling page $number of $count") {
            val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
            // The PDF page is the notebook page's own pixel size — the bundle's declaration, which
            // the decode has just been checked against, so the image lands 1:1 at the origin.
            val pdfPage = PDPage(PDRectangle(page.widthPx.toFloat(), page.heightPx.toFloat()))
            document.addPage(pdfPage)
            PDPageContentStream(document, pdfPage).use { content ->
                content.drawImage(image, 0f, 0f, page.widthPx.toFloat(), page.heightPx.toFloat())
            }
        }
    }

    /**
     * Save, flush, `fsync`, and report the measured count — the sync is what makes the count mean
     * something durable. The save writes through a close-shield: pdfbox's `COSWriter` closes
     * whatever stream it is given (the D3 review's sync finding — the old shape synced a fd the
     * save had already closed, so no protected export was ever actually synced), so the shield
     * turns that close into a flush and this method keeps the fd alive for the sync it owes.
     *
     * The sync itself is answered by what the fd **is** (`fstat`): a regular file must sync, and a
     * failure there is a real one — `ENOSPC`/`EIO` on the flash — reported as a delivery failure
     * rather than swallowed into a claimed success. Anything else (a provider handing back a pipe)
     * has nothing to force to storage, and the sync is skipped rather than attempted-and-excused.
     */
    private fun deliver(
        document: PDDocument,
        destination: ParcelFileDescriptor,
        tag: String,
    ): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            document.save(CloseShield(counting))
            counting.flush()
            val regular = try {
                OsConstants.S_ISREG(Os.fstat(output.fd).st_mode)
            } catch (e: Exception) {
                Log.w(tag, "destination could not be stat'd: ${e.javaClass.simpleName}")
                false
            }
            if (regular) {
                try {
                    output.fd.sync()
                } catch (e: IOException) {
                    throw IllegalStateException("syncing the PDF failed (${e.javaClass.simpleName})")
                }
            }
            count = counting.count
        }
        return count
    }

    /** Passes every write through and turns `close()` into a flush — the stream underneath belongs
     *  to [deliver], which still has a sync to run on it after pdfbox lets go. */
    private class CloseShield(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    /** Names the stage in the one exception shape that survives the seam. */
    private inline fun <T> stage(name: String, body: () -> T): T =
        try {
            body()
        } catch (e: IOException) {
            throw IllegalStateException("$name failed (${e.javaClass.simpleName})")
        }
}
