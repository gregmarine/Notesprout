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

    private var headingBounds: RectF? = null
    private var maxHeightPx: Int = 0
    private var cachedPaths: List<Path> = emptyList()
    private val cachedMatrix = Matrix()
    private var matrixWidth = -1

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setHeading(heading: HeadingStroke, maxHeightPx: Int) {
        headingBounds = heading.boundingBox
        this.maxHeightPx = maxHeightPx
        matrixWidth = -1  // invalidate cached matrix — width known only after layout
        cachedPaths = heading.strokes.mapNotNull { stroke ->
            val pts = stroke.points
            if (pts.isEmpty()) return@mapNotNull null
            Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val src = headingBounds ?: return
        if (src.width() == 0f || src.height() == 0f || cachedPaths.isEmpty()) return

        canvas.drawColor(0xFFFFFFFF.toInt())

        if (matrixWidth != width) {
            cachedMatrix.setRectToRect(
                src,
                RectF(0f, 0f, width.toFloat(), maxHeightPx.toFloat()),
                Matrix.ScaleToFit.START
            )
            matrixWidth = width
        }

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(cachedMatrix)
        for (path in cachedPaths) canvas.drawPath(path, strokePaint)
        canvas.restore()
    }
}
