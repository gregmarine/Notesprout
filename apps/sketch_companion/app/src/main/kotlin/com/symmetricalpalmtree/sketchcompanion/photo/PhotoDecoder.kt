package com.symmetricalpalmtree.sketchcompanion.photo

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.symmetricalpalmtree.sketchcompanion.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes the held photo for the screen: `ImageDecoder` applies the EXIF orientation itself, and a
 *  power-of-two sample size keeps a 200 MP shot near [MAX_LONG_EDGE]. */
object PhotoDecoder {
    private const val TAG = "SketchCompanion"
    const val MAX_LONG_EDGE = 3072

    class Decoded(val bitmap: Bitmap, val srcW: Int, val srcH: Int)

    /** The display bitmap plus the photo's full oriented size. */
    suspend fun forDisplay(file: File): Decoded = withContext(Dispatchers.IO) {
        var srcW = 0
        var srcH = 0
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // `info.size` is the size after EXIF orientation (hwui swaps it for the rotating origins).
            srcW = info.size.width; srcH = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(srcW, srcH, MAX_LONG_EDGE))
        }
        // Belt and braces: the decoded bitmap IS oriented, so if the header's parity disagrees, swap.
        if ((bitmap.height > bitmap.width) != (srcH > srcW)) { val t = srcW; srcW = srcH; srcH = t }
        Slog.d(TAG) { "decoded ${file.name}: source ${srcW}x$srcH, display ${bitmap.width}x${bitmap.height}" }
        Decoded(bitmap, srcW, srcH)
    }

    /** The largest power of two that keeps the long edge at or under [max]. */
    fun sampleSize(w: Int, h: Int, max: Int): Int {
        var sample = 1
        var edge = maxOf(w, h)
        while (edge > max) { edge /= 2; sample *= 2 }
        return sample
    }
}
