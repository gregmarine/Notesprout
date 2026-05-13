package com.notesprout.android.drawing

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

class OnyxDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val TAG = "NoteSprout"
    }

    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private var toolbarHeight = 0

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

    private val idleReleaseRunnable = Runnable {
        if (isSetup) {
            Log.d(TAG, "idle release — handing overlay back to canvas")
            touchHelper.setRawDrawingRenderEnabled(false)
            invalidate()
        }
    }

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            removeCallbacks(idleReleaseRunnable)
            if (isSetup) touchHelper.setRawDrawingRenderEnabled(true)
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            postDelayed(idleReleaseRunnable, 1500)
        }

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
        invalidate()
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
            if (isSetup) {
                removeCallbacks(idleReleaseRunnable)
                invalidate()
                touchHelper.setRawDrawingEnabled(false)
            }
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

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Color.WHITE)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleReleaseRunnable)
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
    }

    // ── DrawingView interface ──

    override fun asView(): View = this

    override fun setToolbarHeight(heightPx: Int) {
        toolbarHeight = heightPx
        Log.d(TAG, "setToolbarHeight toolbarHeight=$toolbarHeight")
        if (isSetup) applyLimitRect()
    }

    override fun enableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(true)
    }

    override fun disableDrawing() {
        if (isSetup) touchHelper.setRawDrawingEnabled(false)
    }

    override fun resetOverlay() {
        if (!isSetup) return
        invalidate()
        touchHelper.setRawDrawingRenderEnabled(false)
        post { touchHelper.setRawDrawingRenderEnabled(true) }
    }

    override fun clearCanvas() {
        if (isSetup) touchHelper.setRawDrawingRenderEnabled(false)
        renderCanvas?.drawColor(Color.WHITE)
        invalidate()
        post { if (isSetup) touchHelper.setRawDrawingEnabled(true) }
    }

    override fun releaseResources() {
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }

    // ── Private helpers ──

    private fun applyLimitRect() {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val limitRect = Rect(
            maxOf(0, frame.left - loc[0]),
            maxOf(0, frame.top - loc[1]),
            frame.right - loc[0],
            frame.bottom - loc[1]
        )
        val exclusion = if (toolbarHeight > 0) {
            listOf(Rect(0, 0, width, toolbarHeight))
        } else {
            emptyList()
        }
        Log.d(TAG, "applyLimitRect: limitRect=$limitRect exclusion=$exclusion")
        touchHelper.setLimitRect(limitRect, exclusion)
    }

    private fun openRawDrawing() {
        Log.d(TAG, "openRawDrawing isSetup=$isSetup toolbarHeight=$toolbarHeight size=${width}x${height}")
        if (!isSetup) {
            applyLimitRect()
            touchHelper
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .openRawDrawing()
            isSetup = true
        } else {
            applyLimitRect()
            touchHelper.restartRawDrawing()
        }
        touchHelper.setRawDrawingEnabled(true)
        Log.d(TAG, "openRawDrawing done — inputEnabled=${touchHelper.isRawDrawingInputEnabled}")
    }
}
