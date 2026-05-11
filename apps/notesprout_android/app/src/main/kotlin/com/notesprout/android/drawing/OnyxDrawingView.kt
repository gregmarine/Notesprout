package com.notesprout.android.drawing

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

// Ported from notesprout_flutter's OnyxDrawingView (which was itself ported from BOOXDemo).
// TouchHelper, RawInputCallback, limit rect, and EPD commit logic are unchanged.
//
// Coordinate note: DrawingActivity runs in fullscreen immersive mode, so the view
// sits at physical screen (0,0). Onyx touch points are also in screen coordinates.
// No offset subtraction is needed — the two spaces are identical.
// The toolbar exclusion rect passed via setToolbarHeight() restricts pen input to
// the area below the floating toolbar without any additional coordinate math.
class OnyxDrawingView(context: Context) : View(context), DrawingView {

    companion object {
        private const val TAG = "NoteSprout"
        private const val IDLE_FLUSH_THRESHOLD_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleFlushRunnable = Runnable { commitToScreen() }

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

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            handler.removeCallbacks(idleFlushRunnable)
            // Re-enable EPD rendering for the new stroke. By the time the user starts
            // a new stroke the Android canvas is fully composited, so the EPD surface
            // initialises from the correct bitmap (all previous strokes visible).
            if (isSetup) touchHelper.setRawDrawingRenderEnabled(true)
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
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, strokePaint)
    }

    // EPD-to-canvas handoff: draw the bitmap first, then swap the EPD layer from within
    // onDraw once we know the Android canvas has the strokes. Doing it the other way
    // (disable EPD first, then invalidate) causes a visible flash on NoteAir devices because
    // their EPD controller clears the hardware layer before the next Android frame is drawn.
    private var pendingEpdSwap = false

    // Uses setRawDrawingRenderEnabled (render-only flag) rather than setRawDrawingEnabled
    // (which toggles both input and render and always triggers a full EPD waveform refresh).
    private fun commitToScreen() {
        pendingEpdSwap = true
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

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isSetup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // renderBitmap is initialised white — no need to drawColor(WHITE) first,
        // which would create an intermediate blank state visible as a flash on e-ink.
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Color.WHITE)
        if (pendingEpdSwap) {
            pendingEpdSwap = false
            // Bitmap is now on the Android canvas. Disable EPD rendering so the canvas
            // becomes visible. Do NOT re-enable here — onBeginRawDrawing re-enables it
            // at the next stroke start, by which time the canvas is fully composited.
            post { touchHelper.setRawDrawingRenderEnabled(false) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(idleFlushRunnable)
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

    override fun clearCanvas() {
        renderCanvas?.drawColor(Color.WHITE)
        if (isSetup) commitToScreen() else invalidate()
    }

    override fun releaseResources() {
        handler.removeCallbacks(idleFlushRunnable)
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
        val exclusion = if (toolbarHeight > 0) {
            listOf(Rect(0, 0, width, toolbarHeight))
        } else {
            emptyList()
        }
        touchHelper.setLimitRect(Rect(0, 0, width, height), exclusion)
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
