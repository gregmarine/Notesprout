package com.notesprout.notesprout.device

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView

class GenericDrawingEngine : DrawingEngine {

    companion object {
        private const val TAG = "GenericDrawingEngine"
        private const val DEBUG_TAG = "NSStylusDebug"

        private fun toolTypeName(toolType: Int): String = when (toolType) {
            MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            else -> "OTHER($toolType)"
        }

        private fun actionName(action: Int): String = when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> "OTHER($action)"
        }
    }

    private var surfaceView: SurfaceView? = null
    private var engineCallback: DrawingEngineCallback? = null
    private var isDrawingEnabled = false
    private var isEraserEnabled = false
    private val strokePoints = mutableListOf<TouchPointData>()

    private var stylusEventReceived = false
    private val noStylusWatchdog = Handler(Looper.getMainLooper())
    private val noStylusRunnable = Runnable {
        if (!stylusEventReceived) {
            Log.w(DEBUG_TAG, "No stylus events detected — check tool type filtering")
        }
    }

    override val isAvailable: Boolean = true
    override val supportsLivePreview: Boolean = true

    override fun initialize(surfaceView: SurfaceView, callback: DrawingEngineCallback) {
        this.surfaceView = surfaceView
        this.engineCallback = callback
        surfaceView.setOnTouchListener { _, event ->
            val consumed = handleTouchEvent(event)
            Log.d(DEBUG_TAG, "touch listener returning consumed=$consumed for action=${actionName(event.actionMasked)}")
            consumed
        }
        noStylusWatchdog.postDelayed(noStylusRunnable, 3000L)
        Log.i(TAG, "GenericDrawingEngine initialized")
    }

    // Returns true if the event was consumed (stylus handled), false to pass through.
    private fun handleTouchEvent(ev: MotionEvent): Boolean {
        val toolType = ev.getToolType(0)
        val toolTypeName = toolTypeName(toolType)
        val actionName = actionName(ev.actionMasked)

        Log.d(DEBUG_TAG, "handleTouchEvent: action=$actionName toolType=$toolType ($toolTypeName) pointers=${ev.pointerCount} x=%.1f y=%.1f isDrawingEnabled=$isDrawingEnabled isEraserEnabled=$isEraserEnabled".format(ev.x, ev.y))

        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            Log.d(DEBUG_TAG, "  → filtered out (not STYLUS, got $toolTypeName) — passing through")
            return false
        }

        if (!stylusEventReceived) {
            stylusEventReceived = true
            noStylusWatchdog.removeCallbacks(noStylusRunnable)
            Log.i(DEBUG_TAG, "First stylus event received: action=$actionName x=${ev.x} y=${ev.y}")
        }

        val cb = engineCallback ?: run {
            Log.w(DEBUG_TAG, "  → no callback set — dropping stylus event")
            return false
        }

        if (isDrawingEnabled && !isEraserEnabled) {
            Log.d(DEBUG_TAG, "  → routing to draw path: action=$actionName")
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    strokePoints.clear()
                    strokePoints.add(TouchPointData(ev.x, ev.y, ev.pressure, 0f, System.currentTimeMillis()))
                    cb.onStrokeStarted(ev.x, ev.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until ev.historySize) {
                        val pt = TouchPointData(
                            ev.getHistoricalX(i), ev.getHistoricalY(i),
                            ev.getHistoricalPressure(i), 0f,
                            ev.getHistoricalEventTime(i)
                        )
                        strokePoints.add(pt)
                        cb.onStrokePoint(pt.x, pt.y, pt.pressure, pt.tilt)
                    }
                    val pt = TouchPointData(ev.x, ev.y, ev.pressure, 0f, System.currentTimeMillis())
                    strokePoints.add(pt)
                    cb.onStrokePoint(ev.x, ev.y, ev.pressure, 0f)
                    cb.onActiveStrokeUpdated()
                }
                MotionEvent.ACTION_UP -> {
                    val pts = strokePoints.toList()
                    strokePoints.clear()
                    Log.d(DEBUG_TAG, "  → stroke ended with ${pts.size} points")
                    cb.onStrokeEnded(pts)
                }
            }
            return true
        } else if (isEraserEnabled) {
            Log.d(DEBUG_TAG, "  → routing to eraser path: action=$actionName")
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> cb.onEraserMoved(ev.x, ev.y)
                MotionEvent.ACTION_UP -> cb.onEraserEnded()
            }
            return true
        } else {
            Log.w(DEBUG_TAG, "  → stylus event dropped: neither draw nor eraser mode active")
            return false
        }
    }

    override fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        isEraserEnabled = false
    }

    override fun setEraserEnabled(enabled: Boolean) {
        isEraserEnabled = enabled
        isDrawingEnabled = false
    }

    override fun setStrokeWidth(width: Float) {}
    override fun setStrokeColor(color: Int) {}
    override fun updateLimitRect(rect: Rect) {}
    override fun onTouchEvent(ev: MotionEvent) {}
    override fun resetDrawing() {}
    override fun onResume() {}
    override fun onPause() {}

    override fun onDestroy() {
        noStylusWatchdog.removeCallbacks(noStylusRunnable)
        surfaceView?.setOnTouchListener(null)
    }
}
