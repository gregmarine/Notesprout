package com.symmetricalpalmtree.sketchcompanion.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
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

/**
 * The gridded crop at source resolution (capped by [CropMath.exportScale]): the region behind the
 * turned frame is decoded straight from the held file through `ImageDecoder`'s crop — orientation
 * applied, never a full-size decode — then drawn into the output through the same turn the screen
 * shows, and the grid over it.
 */
object ExportRenderer {
    private const val TAG = "SketchCompanion"

    class GridSpec(val kind: Int, val count: Int, val color: Int, val weight: GridWeight)

    suspend fun render(file: File, srcW: Int, srcH: Int, crop: CropState, grid: GridSpec): Bitmap =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val g = CropMath.exportGeometry(crop, srcW, srcH)
            val k = CropMath.exportScale(g)
            val outW = (g.cropW * k).roundToInt().coerceAtLeast(1)
            val outH = (g.cropH * k).roundToInt().coerceAtLeast(1)
            val region = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Size the target from the oriented (srcW, srcH) the display decode established.
                val tw = if (k < 1f) (srcW * k).roundToInt() else srcW
                val th = if (k < 1f) (srcH * k).roundToInt() else srcH
                if (tw != info.size.width || th != info.size.height) d.setTargetSize(tw, th)
                val left = (g.regionLeft * k).roundToInt().coerceIn(0, tw - 1)
                val top = (g.regionTop * k).roundToInt().coerceIn(0, th - 1)
                val right = (left + g.regionW * k).roundToInt().coerceIn(left + 1, tw)
                val bottom = (top + g.regionH * k).roundToInt().coerceIn(top + 1, th)
                d.crop = Rect(left, top, right, bottom)
            }
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            // The same forward transform as the screen: region px → relative to the frame's centre
            // (in scaled source px) → turned → placed at the output's centre.
            val m = Matrix().apply {
                postTranslate(g.regionLeft * k - g.centreX * k, g.regionTop * k - g.centreY * k)
                postRotate(g.angle)
                postTranslate(outW / 2f, outH / 2f)
            }
            canvas.drawBitmap(region, m, Paint(Paint.FILTER_BITMAP_FLAG))
            region.recycle()
            val plan = GridLayout.plan(grid.kind, grid.count, outW, outH)
            GridPainter.draw(canvas, plan, 0f, 0f, outW, outH, grid.color, grid.weight)
            Slog.d(TAG) {
                "export: source ${srcW}x$srcH crop ${g.cropW}x${g.cropH} @ (${g.centreX.roundToInt()}, " +
                    "${g.centreY.roundToInt()}) turn ${g.angle}° region ${g.regionW}x${g.regionH} k=$k → " +
                    "${outW}x$outH in ${System.currentTimeMillis() - t0} ms"
            }
            out
        }
}
