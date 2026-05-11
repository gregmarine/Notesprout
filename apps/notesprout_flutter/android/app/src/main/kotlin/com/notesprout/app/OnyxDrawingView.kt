package com.notesprout.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
//
// Coordinate alignment: the drawing screen runs in SystemUiMode.immersiveSticky,
// so the Flutter canvas sits at physical screen (0,0). RawInputCallback touch
// points are also in screen coordinates. No offset subtraction is needed in
// renderStroke — the two coordinate spaces are identical in fullscreen mode.
// The toolbar exclusion rect passed via setCanvasOffset() restricts pen input
// to the area below the floating toolbar without any coordinate math.
class OnyxDrawingView(context: Context) : View(context) {

    companion object {
        private const val TAG = "NoteSprout"
        private const val IDLE_FLUSH_THRESHOLD_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleFlushRunnable = Runnable { commitToScreen() }

    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null

    // Height of the floating toolbar in physical pixels. Used as the top of
    // the setLimitRect exclusion zone so pen strokes cannot be registered
    // over the toolbar. Set by Flutter via setCanvasOffset() before drawing.
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

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            handler.removeCallbacks(idleFlushRunnable)
        }
        override fun onEndRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            handler.removeCallbacks(idleFlushRunnable)
            handler.postDelayed(idleFlushRunnable, IDLE_FLUSH_THRESHOLD_MS)
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
        // In immersive fullscreen mode the view is at screen (0,0), so touch
        // point coordinates are already in view-local space — no subtraction.
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, strokePaint)
        // Hardware overlay shows the stroke in real time; bitmap stays in sync silently.
        // The bitmap surfaces via onDraw whenever the hardware layer resets (clear, focus loss).
        // No toggle here — setRawDrawingEnabled(false) unconditionally triggers an EPD
        // waveform refresh that flashes visibly. Toggle only for clear and focus transitions.
    }

    // Hides the hardware pen overlay, paints the Flutter bitmap, then restores the overlay.
    // Uses setRawDrawingRenderEnabled (render-only flag) rather than setRawDrawingEnabled
    // (which controls both input and render and unconditionally triggers a full EPD waveform
    // refresh). If setRawDrawingRenderEnabled exists and operates independently, the bitmap
    // should surface without the visible flash. If the method doesn't exist the build will
    // fail here, which is the intended signal to fall back to setRawDrawingEnabled.
    private fun commitToScreen() {
        post {
            touchHelper.setRawDrawingRenderEnabled(false)
            invalidate()
            post { touchHelper.setRawDrawingRenderEnabled(true) }
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

    // Called from Flutter with the toolbar height (y) so native can exclude
    // the toolbar region from pen input via setLimitRect. x is always 0 in
    // fullscreen mode and is ignored. Calling this after setup re-applies the
    // limit rect immediately with the updated exclusion zone.
    fun setCanvasOffset(x: Int, y: Int) {
        toolbarHeight = y
        Log.d(TAG, "setCanvasOffset toolbarHeight=$toolbarHeight")
        if (isSetup) applyLimitRect()
    }

    private fun applyLimitRect() {
        val fullScreen = Rect(0, 0, width, height)
        val exclusion = if (toolbarHeight > 0) {
            listOf(Rect(0, 0, width, toolbarHeight))
        } else {
            emptyList()
        }
        touchHelper.setLimitRect(fullScreen, exclusion)
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
        handler.removeCallbacks(idleFlushRunnable)
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
    }

    fun releaseResources() {
        handler.removeCallbacks(idleFlushRunnable)
        if (isSetup) {
            touchHelper.closeRawDrawing()
            isSetup = false
        }
        renderBitmap?.recycle()
        renderBitmap = null
        renderCanvas = null
    }
}
