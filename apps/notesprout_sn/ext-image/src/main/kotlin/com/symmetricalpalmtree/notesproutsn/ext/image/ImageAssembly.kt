package com.symmetricalpalmtree.notesproutsn.ext.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import java.io.IOException

/**
 * The assembly (arc 31 / D1): a host-rendered one-page [PageBundle] on the read fd becomes a PNG on
 * the write fd. No third-party encoder — the framework's own `Bitmap.compress` is the whole of it,
 * which is why this module's only dependency is the contract library.
 *
 * **Exactly one page, or a failure.** A PNG is one picture, so this exporter declares
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.DELIVERY_PER_PAGE] and the host
 * splits a multi-page bake into one-page bundles before it calls. A bundle carrying more than one
 * page therefore means the host is not the one this exporter's manifest asked for (an API-8 host
 * that read the `delivery` tail as absent) — and writing its first page would report a whole
 * notebook as exported when one page was. That is [requireOnePage], kept pure so a JVM test pins
 * it, and it fails as loudly as it can inside the three marshalable shapes.
 *
 * **A page that does not decode, or decodes to a size the bundle did not declare, is a delivery
 * failure, not a page to fix up.** The host corroborates what it is told; a PNG drawn at the wrong
 * scale, or absent, would otherwise be reported as a success. Bytes arriving over the seam are
 * untrusted whichever way they came, so the decode is bounded to the config the host bakes and the
 * result is checked against the declaration. Sizes are not content.
 *
 * A version-1 bundle has no link trailer, and this exporter asks for exactly that
 * ([ImageDescriptor]): the trailer is read and dropped so the stream is consumed as the format says
 * it is, and a PNG has nowhere to put an endnote link.
 *
 * Every `IOException` is re-thrown as an [IllegalStateException] naming the stage — reading the
 * bundle or writing the PNG — and never a path, a payload or a name, because only the three
 * marshalable shapes reach the host at all.
 */
internal object ImageAssembly {

    /**
     * Assembles [source]'s one page onto [destination] and returns the bytes actually written
     * there.
     *
     * Both descriptors are owned by the caller's `finally`; the streams here take ownership too and
     * a second close is a no-op. The source is released as soon as the page is read, before the PNG
     * is written out — one full-size bitmap is alive at a time and it is recycled whichever way the
     * assembly ended.
     */
    fun assemble(
        source: ParcelFileDescriptor,
        destination: ParcelFileDescriptor,
        tag: String,
    ): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val page: PageBundle.Page
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            page = stage("reading the page bundle") {
                PageBundle.Reader(input).use { reader ->
                    requireOnePage(reader.pageCount)
                    val only = reader.readPage()
                    // Read and dropped: a v1 bundle's trailer is empty by construction, and a PNG
                    // has nowhere to put a link. Reading it keeps this side honest about consuming
                    // the format as declared.
                    reader.readLinks()
                    only
                }
            }
        }
        // The host bakes opaque ARGB_8888 pages since arc 49 / P4 (RGB_565 until then, the F5
        // recipe): the pen writes in sixteen greys, and a 5/6-bit decode here would round them a
        // second time on the way into the PNG.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(page.image, 0, page.image.size, options)
            ?: throw IllegalStateException("page did not decode")
        val written: Long
        try {
            if (bitmap.width != page.widthPx || bitmap.height != page.heightPx) {
                throw IllegalStateException(
                    "page decoded ${bitmap.width}x${bitmap.height}, " +
                        "bundle declares ${page.widthPx}x${page.heightPx}",
                )
            }
            written = stage("writing the PNG") { deliver(bitmap, destination, tag) }
        } finally {
            bitmap.recycle()
        }
        // `:ext-image` depends on `:extension-api` only, so there is no `Slog` here — the hand-written
        // gate is the same one `Slog.d` compiles to (CLAUDE.md § Standing rules, arc 34 / L20).
        if (BuildConfig.DEBUG) {
            Log.d(
                tag,
                "assembled ${page.widthPx}x${page.heightPx} → $written bytes in " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms",
            )
        }
        return written
    }

    /**
     * The host guarantees one page per call for a per-page exporter. Anything else is a host that
     * cannot split, and its bundle must never quietly become its first page.
     *
     * @throws IllegalStateException if [pageCount] is not exactly 1.
     */
    fun requireOnePage(pageCount: Int) {
        if (pageCount != 1) {
            throw IllegalStateException("bundle carries $pageCount pages; one expected")
        }
    }

    /**
     * Compress, flush, `fsync`, and report the measured count — the sync is what makes the count
     * mean something durable.
     *
     * The sync itself is answered by what the fd **is** (`fstat`): a regular file must sync, and a
     * failure there is a real one — `ENOSPC`/`EIO` on the flash — reported as a delivery failure
     * rather than swallowed into a claimed success. Anything else (a provider handing back a pipe)
     * has nothing to force to storage, and the sync is skipped rather than attempted-and-excused.
     *
     * PNG at quality 100: the parameter is ignored by a lossless encoder, and passing the maximum
     * says the intent plainly.
     */
    private fun deliver(
        bitmap: Bitmap,
        destination: ParcelFileDescriptor,
        tag: String,
    ): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, counting)) {
                throw IllegalStateException("page would not encode")
            }
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
                    throw IllegalStateException("syncing the PNG failed (${e.javaClass.simpleName})")
                }
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
