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
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.concurrent.atomic.AtomicInteger

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
        // Suppresses the EPD controller's automatic GC16 ghosting-removal refresh, which
        // fires after this many fast-waveform (A2) updates. We raise it well above any
        // realistic stroke count so the hardware doesn't self-trigger mid-session;
        // the handwritingRepaint in commitStrokes is the controlled quality refresh instead.
        private const val EPD_UPDATE_LIST_SIZE = 512
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

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            // Advance the generation so any in-flight post{} from a commitStrokes call sees
            // a stale generation and skips setRawDrawingRenderEnabled(false). Without this
            // guard the stale post{} can fire after we've already re-enabled render here,
            // leaving the overlay disabled mid-stroke and hiding strokes.
            epdSwapGeneration.incrementAndGet()
            if (isSetup) {
                // Re-enable EPD rendering for the new stroke. By the time the user starts
                // a new stroke the Android canvas is fully composited, so the EPD surface
                // initialises from the correct bitmap (all previous strokes visible).
                touchHelper.setRawDrawingRenderEnabled(true)
                // Re-arm the auto-GC suppression each stroke start so the hardware won't
                // self-trigger GC16 during the writing burst.
                EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
            }
        }

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
    }

    // EPD-to-canvas handoff: draw the bitmap first, then swap the EPD layer from within
    // onDraw once we know the Android canvas has the strokes. Doing it the other way
    // (disable EPD first, then invalidate) causes a visible flash on NoteAir devices because
    // their EPD controller clears the hardware layer before the next Android frame is drawn.
    private var pendingEpdSwap = false
    private var commitCallback: (() -> Unit)? = null

    // Guard against the race where onBeginRawDrawing (Onyx SDK thread) re-enables render
    // while a stale post{} from a previous commitStrokes is still queued on the main thread.
    // Without this, the stale post{} fires setRawDrawingRenderEnabled(false) after the new
    // stroke has already re-enabled it — hiding in-progress strokes until the next commit.
    // Incrementing the generation in onBeginRawDrawing lets the post{} detect it's stale
    // and bail out before touching the render flag. AtomicInteger for cross-thread visibility.
    private val epdSwapGeneration = AtomicInteger(0)

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
            commitStrokes {}
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
            val expectedGen = epdSwapGeneration.incrementAndGet()
            post {
                EpdController.resetUpdListSize()
                // Bake the pen strokes into the EPD base image while the overlay is still
                // active. handwritingRepaint uses A2 waveform and composites the hardware pen
                // layer into the display. Once this settles, the strokes are visible from the
                // EPD base image itself — not from the overlay.
                EpdController.handwritingRepaint(this@OnyxDrawingView, Rect(0, 0, width, height))
                // Second post lets the EPD settle from the repaint before the overlay is
                // pulled. Disabling in the same frame as handwritingRepaint causes a flash
                // because the panel hasn't finished the A2 update when the overlay clears.
                // With the bake already settled, pulling the overlay is invisible — the EPD
                // already shows the correct content from the base image.
                post {
                    if (isSetup && epdSwapGeneration.get() == expectedGen) {
                        touchHelper.setRawDrawingRenderEnabled(false)
                    }
                    val cb = commitCallback
                    commitCallback = null
                    cb?.invoke()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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

    override fun commitStrokes(onComplete: () -> Unit) {
        commitCallback = onComplete
        pendingEpdSwap = true
        invalidate()
    }

    override fun clearCanvas() {
        renderCanvas?.drawColor(Color.WHITE)
        invalidate()
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
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
        Log.d(TAG, "openRawDrawing done — inputEnabled=${touchHelper.isRawDrawingInputEnabled}")
    }
}
