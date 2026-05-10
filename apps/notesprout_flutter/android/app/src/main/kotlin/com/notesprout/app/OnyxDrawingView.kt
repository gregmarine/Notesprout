package com.notesprout.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

// Ported directly from BOOXDemo DrawingView.kt.
// TouchHelper, RawInputCallback, limit rect, and EPD commit logic are
// unchanged — only naming and lifecycle hooks differ for the PlatformView context.
class OnyxDrawingView(context: Context) : View(context) {

    companion object {
        private const val TAG = "NoteSprout"
    }

    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f
    }

    private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, rawInputCallback) }
    private var isSetup = false

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {}
        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {}
        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            Log.d(TAG, "onRawDrawingTouchPointListReceived count=${pointList.size()}")
            renderStroke(pointList)
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {}
        override fun onEndRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {}
    }

    private fun renderStroke(pointList: TouchPointList) {
        val canvas = renderCanvas ?: return
        val points = pointList.points
        if (points.isNullOrEmpty()) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, strokePaint)
        // Hardware overlay shows the stroke in real time; bitmap stays in sync silently.
        // commitToScreen() is only needed for clear and focus transitions.
    }

    private fun commitToScreen() {
        post {
            touchHelper.setRawDrawingEnabled(false)
            invalidate()
            post { touchHelper.setRawDrawingEnabled(true) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && height > 0) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    Log.d(TAG, "onGlobalLayout: ${width}x${height} — calling openRawDrawing")
                    openRawDrawing()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.d(TAG, "onWindowFocusChanged hasFocus=$hasWindowFocus isSetup=$isSetup")
        if (hasWindowFocus) {
            if (width > 0 && height > 0) {
                openRawDrawing()
                invalidate()
            }
        } else {
            if (isSetup) touchHelper.setRawDrawingEnabled(false)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        renderBitmap?.recycle()
        renderBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            renderCanvas = Canvas(it)
            renderCanvas!!.drawColor(Color.WHITE)
        }
    }

    private fun openRawDrawing() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenRect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        Log.d(TAG, "openRawDrawing isSetup=$isSetup screenRect=$screenRect")

        if (!isSetup) {
            touchHelper
                .setLimitRect(screenRect, emptyList())
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .openRawDrawing()
            isSetup = true
        } else {
            touchHelper.setLimitRect(screenRect, emptyList())
            touchHelper.restartRawDrawing()
        }
        touchHelper.setRawDrawingEnabled(true)
        Log.d(TAG, "openRawDrawing done — inputEnabled=${touchHelper.isRawDrawingInputEnabled}")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)
    }

    fun enableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(true)
    }

    fun disableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(false)
    }

    fun clearCanvas() {
        renderCanvas?.drawColor(Color.WHITE)
        if (isSetup) commitToScreen() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
    }

    fun releaseResources() {
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }
}
