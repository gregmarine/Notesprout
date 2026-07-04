package com.symmetricalpalmtree.notesprout

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Phase 1A pen bridge: a **transparent** Onyx drawing surface that owns ONLY the live EPD nib
 * overlay. It holds no committed bitmap — committed ink is rendered by Flutter's `CustomPainter`
 * behind this view. On pen-lift the raw points are emitted to Dart ([onEvent]); Dart repaints its
 * canvas and, once its frame is up, calls back [repaintPanel] to hand the panel off from the fast
 * overlay to the Flutter-rendered committed layer.
 *
 * EPD contract (see native app `docs/drawing-engine.md`): pen ⇒ renderEnabled=true; eraser ⇒
 * renderEnabled=false; the handoff needs `EpdController.handwritingRepaint` — toggling render alone
 * does not clear the hardware buffer.
 */
class OnyxSpikeView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OnyxSpike"
        private const val EPD_UPDATE_LIST_SIZE = 512
    }

    /** Emits pen events up to the PlatformView → Dart EventChannel. Set by the host. */
    var onEvent: ((Map<String, Any>) -> Unit)? = null

    private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, rawInputCallback) }
    private var isSetup = false
    private var isEraserMode = false

    /**
     * When true, raw pen input is suspended so the stylus does NOT draw — used while placing/editing
     * a text object (Dart captures the tap instead). Survives focus/size re-openings so a paused
     * surface never silently re-enables drawing under a dialog.
     */
    private var drawingPaused = false

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            if (isSetup && !isEraserMode) touchHelper.setRawDrawingRenderEnabled(true)
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) {}

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            // Pen-lift: whole stroke delivered at once. Hand the points to Dart; keep the overlay
            // rendered so the ink stays visible until Dart's committed frame is ready.
            emit(if (isEraserMode) "erase" else "stroke", list)
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {
            if (isSetup) {
                touchHelper.setRawDrawingRenderEnabled(false)
                invalidate()
            }
        }

        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint) {}

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            emit("erase", list)
        }
    }

    private fun emit(type: String, list: TouchPointList) {
        val pts = list.points ?: return
        // Interleaved [x0,y0,x1,y1,...] as doubles — compact over the channel.
        val coords = ArrayList<Double>(pts.size * 2)
        for (p in pts) { coords.add(p.x.toDouble()); coords.add(p.y.toDouble()) }
        onEvent?.invoke(mapOf("type" to type, "points" to coords))
    }

    // ── Tool commands (from Dart) ──────────────────────────────────────────────

    fun setPen() {
        isEraserMode = false
        if (isSetup) {
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.setRawDrawingRenderEnabled(true)
        }
    }

    fun setEraser() {
        isEraserMode = true
        if (isSetup) {
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.setRawDrawingRenderEnabled(false)
            invalidate()
        }
    }

    /**
     * Hand the panel off to Flutter's committed layer. Called by Dart AFTER it has painted the new
     * committed frame: release the fast overlay's hardware buffer and quality-refresh the region
     * from the window framebuffer (which now holds Flutter's ink). The next pen-down re-enables
     * render via [onBeginRawDrawing].
     */
    fun repaintPanel() {
        if (!isSetup) return
        touchHelper.setRawDrawingRenderEnabled(false)
        invalidate()
        EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
    }

    /** Clear = Dart empties its model + repaints blank, then this refreshes the panel. */
    fun clear() = repaintPanel()

    /**
     * Suspend or resume raw pen input. While suspended the stylus produces no strokes (text
     * placement mode); resuming restores the current tool's render state. Idempotent.
     */
    fun setDrawingEnabled(enabled: Boolean) {
        drawingPaused = !enabled
        if (!isSetup) return
        touchHelper.setRawDrawingEnabled(enabled)
        if (enabled) {
            touchHelper.setRawDrawingRenderEnabled(!isEraserMode)
        } else {
            touchHelper.setRawDrawingRenderEnabled(false)
        }
        invalidate()
    }

    // ── Onyx pipeline lifecycle ────────────────────────────────────────────────

    private fun applyLimitRect() {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val limitRect = Rect(
            maxOf(0, frame.left - loc[0]),
            maxOf(0, frame.top - loc[1]),
            minOf(width, frame.right - loc[0]),
            minOf(height, frame.bottom - loc[1]),
        )
        touchHelper.setLimitRect(limitRect, listOf(Rect(-1, -1, 0, 0)))
    }

    private fun openRawDrawing() {
        if (width == 0 || height == 0) return
        Log.d(TAG, "openRawDrawing isSetup=$isSetup size=${width}x$height")
        if (!isSetup) {
            applyLimitRect()
            touchHelper.setStrokeWidth(3.0f).setStrokeColor(Color.BLACK).openRawDrawing()
            isSetup = true
        } else {
            applyLimitRect()
            touchHelper.restartRawDrawing()
        }
        // Respect a paused state across re-openings (e.g. focus regained under a text dialog).
        touchHelper.setRawDrawingEnabled(!drawingPaused)
        if (isEraserMode || drawingPaused) touchHelper.setRawDrawingRenderEnabled(false)
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
    }

    private fun closeRawDrawing() {
        if (!isSetup) return
        touchHelper.closeRawDrawing()
        isSetup = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !isSetup) openRawDrawing()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0) {
            openRawDrawing()
        } else {
            viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (width > 0 && height > 0) {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (!isSetup) openRawDrawing()
                    }
                }
            })
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) openRawDrawing()
        else if (isSetup) touchHelper.setRawDrawingEnabled(false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeRawDrawing()
    }
}
