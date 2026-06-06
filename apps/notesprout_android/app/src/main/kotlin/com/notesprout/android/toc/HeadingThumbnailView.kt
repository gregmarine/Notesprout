package com.notesprout.android.toc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.notesprout.android.data.HeadingStroke

class HeadingThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var heading: HeadingStroke? = null
    private var maxHeightPx: Int = 0

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setHeading(heading: HeadingStroke, maxHeightPx: Int) {
        this.heading = heading
        this.maxHeightPx = maxHeightPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = heading ?: return
        val src = h.boundingBox
        if (src.width() == 0f || src.height() == 0f) return

        canvas.drawColor(0xFFFFFFFF.toInt())

        val dst = RectF(0f, 0f, width.toFloat(), maxHeightPx.toFloat())
        val matrix = Matrix()
        matrix.setRectToRect(src, dst, Matrix.ScaleToFit.START)

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(matrix)

        for (stroke in h.strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].x, pts[i].y)
            }
            canvas.drawPath(path, strokePaint)
        }

        canvas.restore()
    }
}
