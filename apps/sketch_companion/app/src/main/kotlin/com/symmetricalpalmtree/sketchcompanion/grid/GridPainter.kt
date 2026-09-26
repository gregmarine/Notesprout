package com.symmetricalpalmtree.sketchcompanion.grid

import android.graphics.Canvas
import android.graphics.Paint

/** Draws a [GridLayout.Plan] onto a canvas at `(left, top)` over a `width × height` area — the one
 *  painter for both the on-screen frame and the export, so they cannot drift apart. */
object GridPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, plan: GridLayout.Plan?, left: Float, top: Float, width: Int, height: Int,
        color: Int, weight: GridWeight,
    ) {
        if (plan == null) return
        paint.color = color
        if (plan.kind == Grid.DOTS) {
            paint.style = Paint.Style.FILL
            val r = weight.dotRadius(width)
            for (x in plan.xs) for (y in plan.ys) canvas.drawCircle(left + x, top + y, r, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = weight.lineWidth(width)
            val bottom = top + height
            val right = left + width
            for (x in plan.xs) canvas.drawLine(left + x, top, left + x, bottom, paint)
            for (y in plan.ys) canvas.drawLine(left, top + y, right, top + y, paint)
        }
    }
}
