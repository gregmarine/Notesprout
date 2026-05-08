package com.notesprout.notesprout.device

import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

class OnyxDrawingEngine : DrawingEngine {

    companion object {
        private const val TAG = "OnyxDrawingEngine"
    }

    private var touchHelper: TouchHelper? = null
    private var surfaceView: SurfaceView? = null
    private var engineCallback: DrawingEngineCallback? = null
    private val internalPoints = mutableListOf<TouchPointData>()

    override var isAvailable: Boolean = true
        private set
    override val supportsLivePreview: Boolean = false

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint) {
            internalPoints.clear()
            internalPoints.add(TouchPointData(
                x = p1.x, y = p1.y,
                pressure = p1.pressure.toFloat(), tilt = p1.tiltX.toFloat(),
                timestamp = System.currentTimeMillis()
            ))
            engineCallback?.onStrokeStarted(p1.x, p1.y)
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint) {}

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint) {
            val pt = TouchPointData(
                x = p0.x, y = p0.y,
                pressure = p0.pressure.toFloat(), tilt = p0.tiltX.toFloat(),
                timestamp = System.currentTimeMillis()
            )
            internalPoints.add(pt)
            engineCallback?.onStrokePoint(p0.x, p0.y, p0.pressure.toFloat(), p0.tiltX.toFloat())
        }

        override fun onRawDrawingTouchPointListReceived(p0: TouchPointList) {
            Log.i(TAG, "onRawDrawingTouchPointListReceived: ${p0.size()} points, internal=${internalPoints.size}")
            val pts = if (internalPoints.size >= 2) {
                internalPoints.toList()
            } else {
                (0 until p0.size()).map { i ->
                    val tp = p0.get(i)
                    TouchPointData(
                        x = tp.x, y = tp.y,
                        pressure = tp.pressure.toFloat(), tilt = tp.tiltX.toFloat(),
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
            internalPoints.clear()
            if (pts.size >= 2) {
                engineCallback?.onStrokeEnded(pts)
            }
        }

        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(p0: TouchPointList) {}
    }

    override fun initialize(surfaceView: SurfaceView, callback: DrawingEngineCallback) {
        this.surfaceView = surfaceView
        this.engineCallback = callback
        try {
            val limitRect = getUsableLimitRect(surfaceView)
            touchHelper = TouchHelper.create(surfaceView, TouchHelper.FEATURE_SF_TOUCH_RENDER, rawInputCallback)
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .setLimitRect(listOf(limitRect), emptyList())
                .openRawDrawing()
            Log.i(TAG, "openRawDrawing done — isRawDrawingCreated=${touchHelper?.isRawDrawingCreated()}")
            EpdController.enterScribbleMode(surfaceView)
            touchHelper?.setRawDrawingEnabled(true)
            touchHelper?.setRawInputReaderEnable(true)
            Log.i(TAG, "OnyxDrawingEngine initialized")
        } catch (e: Exception) {
            Log.e(TAG, "OnyxDrawingEngine initialization failed", e)
            isAvailable = false
        }
    }

    override fun setDrawingEnabled(enabled: Boolean) {
        val sv = surfaceView ?: return
        try {
            if (enabled) {
                EpdController.enterScribbleMode(sv)
                touchHelper?.setRawDrawingEnabled(true)
                touchHelper?.setRawInputReaderEnable(true)
            } else {
                touchHelper?.setRawDrawingEnabled(false)
                EpdController.leaveScribbleMode(sv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setDrawingEnabled($enabled) failed", e)
        }
    }

    override fun setEraserEnabled(enabled: Boolean) {
        val sv = surfaceView ?: return
        try {
            if (enabled) {
                touchHelper?.setRawInputReaderEnable(false)
                touchHelper?.setRawDrawingEnabled(false)
                EpdController.leaveScribbleMode(sv)
            } else {
                setDrawingEnabled(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setEraserEnabled($enabled) failed", e)
        }
    }

    override fun setStrokeWidth(width: Float) {
        try {
            touchHelper?.setStrokeWidth(width)
        } catch (e: Exception) {
            Log.e(TAG, "setStrokeWidth failed", e)
        }
    }

    override fun setStrokeColor(color: Int) {
        try {
            touchHelper?.setStrokeColor(color)
        } catch (e: Exception) {
            Log.e(TAG, "setStrokeColor failed", e)
        }
    }

    override fun updateLimitRect(rect: Rect) {
        try {
            touchHelper?.setLimitRect(listOf(rect), emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "updateLimitRect failed", e)
        }
    }

    override fun onTouchEvent(ev: MotionEvent) {
        try {
            touchHelper?.onTouchEvent(ev)
        } catch (e: Exception) {
            Log.e(TAG, "onTouchEvent failed", e)
        }
    }

    override fun resetDrawing() {
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
            touchHelper?.setRawInputReaderEnable(true)
        } catch (e: Exception) {
            Log.e(TAG, "resetDrawing failed", e)
        }
    }

    override fun onResume() {
        val sv = surfaceView ?: return
        try {
            EpdController.enterScribbleMode(sv)
            touchHelper?.setRawDrawingEnabled(true)
            touchHelper?.setRawInputReaderEnable(true)
        } catch (e: Exception) {
            Log.e(TAG, "onResume failed", e)
        }
    }

    override fun onPause() {
        val sv = surfaceView ?: return
        try {
            touchHelper?.setRawInputReaderEnable(false)
            touchHelper?.setRawDrawingEnabled(false)
            EpdController.leaveScribbleMode(sv)
        } catch (e: Exception) {
            Log.e(TAG, "onPause failed", e)
        }
    }

    override fun onDestroy() {
        try {
            touchHelper?.closeRawDrawing()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy failed", e)
        }
    }

    private fun getUsableLimitRect(sv: SurfaceView): Rect {
        val frame = Rect()
        sv.getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)
        val left = maxOf(0, frame.left - loc[0])
        val top = maxOf(0, frame.top - loc[1])
        val right = frame.right - loc[0]
        val bottom = frame.bottom - loc[1]
        return Rect(left, top, right, bottom)
    }
}
