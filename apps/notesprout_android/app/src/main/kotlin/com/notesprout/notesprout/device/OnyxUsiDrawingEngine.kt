package com.notesprout.notesprout.device

import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import com.onyx.android.sdk.api.device.epd.EpdController

class OnyxUsiDrawingEngine : DrawingEngine {

    companion object {
        private const val TAG = "OnyxUsiEngine"
    }

    private var surfaceView: SurfaceView? = null
    private var engineCallback: DrawingEngineCallback? = null
    private var isDrawingEnabled = false
    private val pendingPoints = mutableListOf<TouchPointData>()

    override var isAvailable: Boolean = true
        private set
    override val supportsLivePreview: Boolean = true

    override fun initialize(surfaceView: SurfaceView, callback: DrawingEngineCallback) {
        this.surfaceView = surfaceView
        this.engineCallback = callback
        try {
            EpdController.enterScribbleMode(surfaceView)
            isDrawingEnabled = true
            Log.i(TAG, "OnyxUsiDrawingEngine initialized")
        } catch (e: Exception) {
            Log.e(TAG, "initialization failed", e)
            isAvailable = false
        }
    }

    override fun setDrawingEnabled(enabled: Boolean) {
        val sv = surfaceView ?: return
        isDrawingEnabled = enabled
        try {
            if (enabled) EpdController.enterScribbleMode(sv)
            else EpdController.leaveScribbleMode(sv)
        } catch (e: Exception) {
            Log.e(TAG, "setDrawingEnabled($enabled) failed", e)
        }
    }

    override fun setEraserEnabled(enabled: Boolean) {
        val sv = surfaceView ?: return
        try {
            if (enabled) {
                isDrawingEnabled = false
                EpdController.leaveScribbleMode(sv)
            } else {
                setDrawingEnabled(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setEraserEnabled($enabled) failed", e)
        }
    }

    override fun setStrokeWidth(width: Float) {}
    override fun setStrokeColor(color: Int) {}
    override fun updateLimitRect(rect: Rect) {}
    override fun resetDrawing() {}

    override fun onTouchEvent(ev: MotionEvent) {
        if (!isDrawingEnabled) return
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return

        val cb = engineCallback ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingPoints.clear()
                pendingPoints.add(TouchPointData(
                    x = ev.x, y = ev.y,
                    pressure = ev.pressure,
                    tilt = ev.getAxisValue(MotionEvent.AXIS_TILT),
                    timestamp = ev.eventTime
                ))
                cb.onStrokeStarted(ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until ev.historySize) {
                    pendingPoints.add(TouchPointData(
                        x = ev.getHistoricalX(h), y = ev.getHistoricalY(h),
                        pressure = ev.getHistoricalPressure(h),
                        tilt = ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h),
                        timestamp = ev.getHistoricalEventTime(h)
                    ))
                    cb.onStrokePoint(
                        ev.getHistoricalX(h), ev.getHistoricalY(h),
                        ev.getHistoricalPressure(h),
                        ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h)
                    )
                }
                pendingPoints.add(TouchPointData(
                    x = ev.x, y = ev.y,
                    pressure = ev.pressure,
                    tilt = ev.getAxisValue(MotionEvent.AXIS_TILT),
                    timestamp = ev.eventTime
                ))
                cb.onStrokePoint(ev.x, ev.y, ev.pressure, ev.getAxisValue(MotionEvent.AXIS_TILT))
                cb.onActiveStrokeUpdated()
            }

            MotionEvent.ACTION_UP -> {
                pendingPoints.add(TouchPointData(
                    x = ev.x, y = ev.y,
                    pressure = ev.pressure,
                    tilt = ev.getAxisValue(MotionEvent.AXIS_TILT),
                    timestamp = ev.eventTime
                ))
                if (pendingPoints.size >= 2) {
                    cb.onStrokeEnded(pendingPoints.toList())
                }
                pendingPoints.clear()
            }

            MotionEvent.ACTION_CANCEL -> {
                pendingPoints.clear()
            }
        }
    }

    override fun onResume() {
        val sv = surfaceView ?: return
        try {
            if (isDrawingEnabled) EpdController.enterScribbleMode(sv)
        } catch (e: Exception) {
            Log.e(TAG, "onResume failed", e)
        }
    }

    override fun onPause() {
        val sv = surfaceView ?: return
        try {
            EpdController.leaveScribbleMode(sv)
        } catch (e: Exception) {
            Log.e(TAG, "onPause failed", e)
        }
    }

    override fun onDestroy() {}
}
