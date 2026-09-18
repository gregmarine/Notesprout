package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import java.io.ByteArrayOutputStream

/**
 * A page's pixels turned into bytes, and back (arc 43 / K5, ported from Paintsprout Onyx's
 * `sketchbook/RasterImage.kt`) — the half of the raster border that needs Android. The half that
 * does not is [ImageHeader], in `:extension-api`, shared with the host.
 *
 * **PNG, because the drawing has to come back exactly.** It is lossless, so a page saved and
 * reopened is the page that was drawn rather than a very good likeness of it; a raster page has no
 * rows to re-render from, so what is in the blob is the only copy of the work.
 *
 * **Nothing here logs a pixel** — byte counts and sizes only, the seam's standing rule.
 */
object RasterImage {

    private const val TAG = "RasterImage"

    /**
     * The page image as PNG bytes — **or an empty array for a blank page**, which is the wire form
     * of "this page has no sketch" ([com.symmetricalpalmtree.notesproutsn.extension.ByteChunks]:
     * one empty chunk, and the host soft-deletes the row rather than storing a page of nothing).
     * `getPageRaster()` answers null for a page nothing has been drawn on, so null in is empty out.
     *
     * The quality argument is ignored by the PNG encoder — it is lossless — and 100 is passed for
     * the reader rather than the codec, so nobody later reads a lower number as a tuning that was
     * chosen.
     *
     * A picture over [SketchContract.WATCH_BYTES] gets a line in the log and is **pushed anyway**;
     * the refusal line is [SketchContract.MAX_BYTES] and it is the host's to draw.
     *
     * **Call this off the main thread.** A page encode is hundreds of milliseconds.
     */
    fun encode(bitmap: Bitmap?): ByteArray {
        if (bitmap == null) return ByteArray(0)
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        if (bytes.size > SketchContract.WATCH_BYTES) {
            Log.w(TAG, "this page's sketch is ${bytes.size} bytes, over the ${SketchContract.WATCH_BYTES}-byte watch line; saved anyway")
        }
        return bytes
    }

    /**
     * The bytes as a page image, or null when they are not this page's.
     *
     * **The guard runs before the decoder, always, and that order is the whole of this function.**
     * [ImageHeader.matches] answers from the PNG's own thirty-three-byte header; only once it has said
     * yes does anything ask `BitmapFactory` for memory. The other order — decode and then check the
     * dimensions — hands a damaged or foreign blob's idea of how big it is straight to the
     * allocator, on a device where the honest page is already ~9.5 MB, which is a way to take the
     * process down while somebody is drawing.
     *
     * `ARGB_8888` because the page image is a **layer over the paper**: its unmarked pixels are
     * transparent so the white shows through, and the eraser clears to transparent rather than
     * painting white. A config without an alpha channel would turn every erased patch into a white
     * hole in whatever one day sits under it.
     */
    fun decode(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        if (!ImageHeader.matches(bytes, pageWidth, pageHeight)) {
            Log.w(TAG, "a stored sketch is ${ImageHeader.size(bytes)} and the page is ${pageWidth}x$pageHeight — refused before decoding")
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            // Throwable, not Exception: the one that matters on this device — not enough memory for
            // the page — is an Error. A null here opens the page blank, which is the honest state
            // of a page nothing could be read onto, and the pixels on disk are left exactly as they
            // are: the screen only overwrites them when the hand draws something.
            Log.e(TAG, "a stored sketch passed the header guard and would not decode", t)
            null
        }
        if (bitmap == null) Log.e(TAG, "a stored sketch decoded to nothing")
        return bitmap
    }

    /** What the encoder's buffer starts at. A page's PNG measures in the hundreds of kilobytes on
     *  real drawing, so starting at the default handful of bytes means a dozen array copies of a
     *  growing buffer for every save. */
    private const val INITIAL_BUFFER_BYTES = 512 * 1024
}
