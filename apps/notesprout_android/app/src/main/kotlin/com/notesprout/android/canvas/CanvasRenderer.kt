package com.notesprout.android.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.json.JSONArray
import org.json.JSONObject

interface CanvasDelegate {
    fun drawStroke(pointsJson: String, styleJson: String)
    fun clear()
    fun redrawAll(strokesJson: String)
    fun refresh()
}

class CanvasRenderer(context: Context) : SurfaceView(context), SurfaceHolder.Callback, CanvasDelegate {

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(bitmap!!)
        bitmapCanvas!!.drawColor(Color.WHITE)
        refresh()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        bitmap?.recycle()
        bitmap = null
        bitmapCanvas = null
    }

    override fun drawStroke(pointsJson: String, styleJson: String) {
        val bc = bitmapCanvas ?: return
        try {
            val points = JSONArray(pointsJson)
            val style = JSONObject(styleJson)
            val baseWidth = style.optDouble("baseWidth", 3.0).toFloat()
            val maxWidth = style.optDouble("maxWidth", 6.0).toFloat()

            if (points.length() == 0) return

            val path = Path()
            val firstPt = points.getJSONObject(0)
            path.moveTo(firstPt.optDouble("x", 0.0).toFloat(), firstPt.optDouble("y", 0.0).toFloat())

            for (i in 1 until points.length()) {
                val pt = points.getJSONObject(i)
                val x = pt.optDouble("x", 0.0).toFloat()
                val y = pt.optDouble("y", 0.0).toFloat()
                val pressure = pt.optDouble("pressure", 0.5).toFloat().coerceIn(0f, 1f)
                val strokeWidth = baseWidth + (maxWidth - baseWidth) * pressure

                paint.strokeWidth = strokeWidth
                paint.color = Color.BLACK

                val prevPt = points.getJSONObject(i - 1)
                val px = prevPt.optDouble("x", 0.0).toFloat()
                val py = prevPt.optDouble("y", 0.0).toFloat()
                bc.drawLine(px, py, x, y, paint)
            }
        } catch (e: Exception) {
            Log.e("CanvasRenderer", "drawStroke error: ${e.message}")
        }
    }

    override fun clear() {
        bitmapCanvas?.drawColor(Color.WHITE)
    }

    override fun redrawAll(strokesJson: String) {
        val bc = bitmapCanvas ?: return
        bc.drawColor(Color.WHITE)
        try {
            val strokes = JSONArray(strokesJson)
            for (i in 0 until strokes.length()) {
                val stroke = strokes.getJSONObject(i)
                val pointsJson = stroke.optJSONArray("points")?.toString() ?: continue
                val styleJson = stroke.optJSONObject("style")?.toString() ?: continue
                drawStroke(pointsJson, styleJson)
            }
        } catch (e: Exception) {
            Log.e("CanvasRenderer", "redrawAll error: ${e.message}")
        }
    }

    override fun refresh() {
        val c = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
        try {
            bitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
                ?: c.drawColor(Color.WHITE)
        } finally {
            try { holder.unlockCanvasAndPost(c) } catch (_: Exception) {}
        }
    }
}
