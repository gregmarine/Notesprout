package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import java.io.ByteArrayOutputStream

/**
 * One of a page's two rasters turned into bytes, and back (arc 43 / K5, ported from Paintsprout
 * Onyx's `sketchbook/RasterImage.kt`; **WebP since arc 45 "Ink" / G3**) — the half of the raster
 * border that needs Android. The half that does not is [ImageHeader], in `:extension-api`, shared
 * with the host.
 *
 * **Lossless WebP with alpha, because the drawing has to come back exactly.** A raster page has no
 * rows to re-render from: what is in the blob is the only copy of the work, so a codec that is
 * "very nearly" right is a codec that quietly rewrites the artist's marks every time the page is
 * saved. Lossless is the whole requirement, and WebP's lossless mode meets it in appreciably fewer
 * bytes than PNG on exactly the kind of image this is — large, mostly transparent, with soft
 * graphite grain over it. **RGBA, not grey+alpha** (the user's decision 3): the ink raster is black
 * today and the pencil greyscale, but colour is coming on other platforms and a grey encoding would
 * have to be migrated the day it does.
 *
 * **Alpha matters as much as losslessness.** Each raster is a *layer over the paper*: its unmarked
 * pixels are transparent so the white shows through, the rubber lifts alpha rather than painting
 * white, and the two rasters are flattened with a darken composite. A config or a codec without an
 * alpha channel would turn every unmarked pixel into an opaque hole in the other layer.
 *
 * **The quality argument of `WEBP_LOSSLESS` is an *effort* dial, not a quality one** — see
 * [WEBP_EFFORT]. [encodeTable] is the debug-only instrument that measures it on the real device.
 *
 * **Nothing here logs a pixel** — byte counts, sizes and durations only, the seam's standing rule.
 * That holds for [encodeTable] too: it reports bytes and milliseconds and nothing else.
 */
object RasterImage {

    private const val TAG = "RasterImage"

    /**
     * What `Bitmap.CompressFormat.WEBP_LOSSLESS` is told to spend.
     *
     * **This is an effort, not a quality.** For the lossy formats the argument is picture quality;
     * for `WEBP_LOSSLESS` the image that comes back is identical whatever is passed, and the number
     * buys *search*: 0 is the fastest encode and the largest file, 100 the slowest and the
     * smallest. So the only question it can ever be wrong about is how long a save blocks the IO
     * thread against how much of the `.soil` a page costs — never how the drawing looks.
     *
     * Its value is a **Nomad measurement** (G3's phase-start question): the encode table for a
     * fully shaded page at 0 / 25 / 50 / 75 / 100, taken through [encodeTable]'s debug door, and
     * read against the `MAX_BYTES` refusal a page has to stay under.
     *
     * **100, the user's answer (2026-09-18) on the Nomad table** — a 1404×1872 fill-door lattice,
     * bytes / ms: graphite 0 → 217 924 / 426 · 50 → 216 084 / 405 · 75 → 284 888 / 431 ·
     * **100 → 146 498 / 619** · PNG 222 066 / 656; ink 0 → 116 542 / 393 · 50 → 105 236 / 341 ·
     * **100 → 88 642 / 346** · PNG 53 065 / 602. The top of the dial is the smallest on both
     * rasters and still no slower than the PNG it replaced, on an IO thread three seconds behind
     * the last mark. The dial is not monotonic (75 is an outlier upward), and PNG beats WebP on a
     * pure-black lattice — real ink is sparser, so neither changes the answer.
     */
    const val WEBP_EFFORT: Int = 100

    /**
     * One raster as WebP bytes — **or an empty array for a blank layer**, which is the wire form of
     * "this page has no such raster" ([com.symmetricalpalmtree.notesproutsn.extension.ByteChunks]:
     * one empty chunk, and the host soft-deletes that layer's row rather than storing a page of
     * nothing). `getPageRaster(layer)` answers null for a layer nothing has been drawn on, so null
     * in is empty out — and the other layer is untouched by all of it.
     *
     * `WEBP_LOSSLESS` from API 30; on 29 (the module's `minSdk`) the encoder has no such constant
     * and `WEBP` at quality 100 is documented as lossless, which is the one value that branch ever
     * passes.
     *
     * A picture over [SketchContract.WATCH_BYTES] gets a line in the log and is **pushed anyway**;
     * the refusal line is [SketchContract.MAX_BYTES] and it is the host's to draw. Both are per
     * raster (the user's decision 3) — each one is its own row in the `.soil`.
     *
     * **Call this off the main thread.** A page encode is hundreds of milliseconds.
     */
    fun encode(bitmap: Bitmap?): ByteArray {
        if (bitmap == null) return ByteArray(0)
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        compressLossless(bitmap, WEBP_EFFORT, out)
        val bytes = out.toByteArray()
        if (bytes.size > SketchContract.WATCH_BYTES) {
            Log.w(TAG, "this page's raster is ${bytes.size} bytes, over the ${SketchContract.WATCH_BYTES}-byte watch line; saved anyway")
        }
        return bytes
    }

    /**
     * The bytes as one raster's page image, or null when they are not this page's.
     *
     * **The guard runs before the decoder, always, and that order is the whole of this function.**
     * [ImageHeader.matches] answers from the WebP container's own header — `RIFF`/`WEBP` and then
     * either a `VP8L` or a `VP8X` chunk, twenty-five or thirty bytes of it — and only once it has
     * said yes does anything ask `BitmapFactory` for memory. The other order — decode and then
     * check the dimensions — hands a damaged or foreign blob's idea of how big it is straight to
     * the allocator, on a device where the honest page is already ~9.5 MB, which is a way to take
     * the process down while somebody is drawing. **A PNG is simply not a WebP** and fails the
     * guard here like any other foreign blob: there is no sniffing and no legacy (decision 4).
     *
     * `ARGB_8888` because each raster is a **layer over the paper**: its unmarked pixels are
     * transparent so the white shows through, the eraser clears to transparent rather than painting
     * white, and the ink raster is darkened over the graphite one. A config without an alpha
     * channel would turn every unmarked pixel into a white hole in whatever sits under it.
     */
    fun decode(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        if (!ImageHeader.matches(bytes, pageWidth, pageHeight)) {
            Log.w(TAG, "a stored raster is ${ImageHeader.size(bytes)} and the page is ${pageWidth}x$pageHeight — refused before decoding")
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            // Throwable, not Exception: the one that matters on this device — not enough memory for
            // the page — is an Error. A null here opens that layer blank, which is the honest state
            // of a layer nothing could be read onto, and the pixels on disk are left exactly as they
            // are: the screen only overwrites them when the hand draws something.
            Log.e(TAG, "a stored raster passed the header guard and would not decode", t)
            null
        }
        if (bitmap == null) Log.e(TAG, "a stored raster decoded to nothing")
        return bitmap
    }

    /**
     * **Debug only — the measurement instrument for [WEBP_EFFORT], never a release path.**
     *
     * Encodes the very same bitmap at every effort worth trying, and once as PNG for the number the
     * arc is trading against, and hands back one line per row:
     *
     * ```
     * <format> <effort> <bytes> <ms>
     * ```
     *
     * Wall clock, because what the dial buys is time on the IO thread and wall clock is what the
     * hand waits through. The caller logs the rows; **no pixel ever touches a log** — a row is two
     * numbers, a word and a millisecond count.
     *
     * On API 29 there is no `WEBP_LOSSLESS` constant to sweep, so the table has the one `webp`
     * row at quality 100 (lossless there) beside the PNG one. Call it off the main thread: this is
     * six page encodes in a row.
     */
    fun encodeTable(bitmap: Bitmap): String {
        if (!BuildConfig.DEBUG) return ""
        val rows = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (effort in EFFORTS) {
                rows.append(measure("webp-lossless", effort) { out -> compressLossless(bitmap, effort, out) })
            }
        } else {
            rows.append(measure("webp", 100) { out -> compressLossless(bitmap, 100, out) })
        }
        rows.append(measure("png", 100) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) })
        return rows.toString()
    }

    /** One row of [encodeTable]: the encode, timed, reported as bytes and milliseconds. */
    private inline fun measure(format: String, effort: Int, encode: (ByteArrayOutputStream) -> Unit): String {
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val t0 = SystemClock.elapsedRealtime()
        encode(out)
        val ms = SystemClock.elapsedRealtime() - t0
        return "$format $effort ${out.size()} $ms\n"
    }

    /**
     * The one place the encoder is chosen. `WEBP_LOSSLESS` arrived in API 30; on 29 the plain
     * `WEBP` constant at quality 100 is documented as lossless, and 100 is the only value this
     * branch is ever given (see [encode] and [encodeTable]).
     */
    private fun compressLossless(bitmap: Bitmap, effort: Int, out: ByteArrayOutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, effort, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }

    /** The efforts [encodeTable] sweeps — the ends and the thirds of the dial, which is enough to
     *  see the shape of the bytes-against-milliseconds curve without six minutes of encoding. */
    private val EFFORTS = intArrayOf(0, 25, 50, 75, 100)

    /** What the encoder's buffer starts at. A page's raster measures in the hundreds of kilobytes
     *  on real drawing, so starting at the default handful of bytes means a dozen array copies of a
     *  growing buffer for every save. */
    private const val INITIAL_BUFFER_BYTES = 512 * 1024
}
