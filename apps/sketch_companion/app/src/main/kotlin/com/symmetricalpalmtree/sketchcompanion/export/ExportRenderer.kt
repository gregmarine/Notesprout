package com.symmetricalpalmtree.sketchcompanion.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import com.symmetricalpalmtree.sketchcompanion.core.Slog
import com.symmetricalpalmtree.sketchcompanion.crop.CropMath
import com.symmetricalpalmtree.sketchcompanion.crop.CropState
import com.symmetricalpalmtree.sketchcompanion.grid.GridLayout
import com.symmetricalpalmtree.sketchcompanion.grid.GridPainter
import com.symmetricalpalmtree.sketchcompanion.grid.GridWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The gridded crop at source resolution (capped at [CropMath.MAX_EXPORT_WIDTH]), decoded straight
 *  from the held file through `ImageDecoder`'s crop — orientation applied, never a full-size decode. */
object ExportRenderer {
    private const val TAG = "SketchCompanion"

    class GridSpec(val kind: Int, val count: Int, val color: Int, val weight: GridWeight)

    suspend fun render(file: File, srcW: Int, srcH: Int, crop: CropState, grid: GridSpec): Bitmap =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = CropMath.sourceRect(crop, srcW, srcH)
            val (outW, outH) = CropMath.exportSize(r[2], r[3])
            val scale = outW.toFloat() / r[2]
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
                // Header size may be the un-oriented one; the crop is in oriented space, so size
                // the target from the oriented (srcW, srcH) the display decode established.
                val tw = if (scale < 1f) (srcW * scale).roundToInt() else srcW
                val th = if (scale < 1f) (srcH * scale).roundToInt() else srcH
                if (tw != info.size.width || th != info.size.height) d.setTargetSize(tw, th)
                val left = (r[0] * scale).roundToInt().coerceIn(0, tw - 1)
                val top = (r[1] * scale).roundToInt().coerceIn(0, th - 1)
                val right = (left + outW).coerceAtMost(tw)
                val bottom = (top + outH).coerceAtMost(th)
                d.crop = Rect(left, top, right, bottom)
            }
            val canvas = Canvas(bitmap)
            val plan = GridLayout.plan(grid.kind, grid.count, bitmap.width, bitmap.height)
            GridPainter.draw(canvas, plan, 0f, 0f, bitmap.width, bitmap.height, grid.color, grid.weight)
            Slog.d(TAG) {
                "export: source ${srcW}x$srcH rect ${r.toList()} → ${bitmap.width}x${bitmap.height} " +
                    "in ${System.currentTimeMillis() - t0} ms"
            }
            bitmap
        }
}
