package com.notesprout.android.canvas

import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

class OnyxDrawingEngine : DrawingEngine {

    private var touchHelper: TouchHelper? = null
    private var surfaceView: SurfaceView? = null
    private var callback: StrokeCallback? = null
    private val currentPoints = mutableListOf<StrokePoint>()
    private var currentTool = ToolType.GEL_PEN
    private var activeLimitRect = Rect()
    private var excludeRects: List<Rect> = emptyList()

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint) {
            if (currentTool == ToolType.ERASER) return
            currentPoints.clear()
            val pt = StrokePoint(x = p1.x, y = p1.y, pressure = p1.pressure.toFloat(), tilt = p1.tiltX.toFloat())
            currentPoints.add(pt)
            callback?.onStrokeBegin(p1.x, p1.y, p1.pressure.toFloat())
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint) {}

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint) {
            if (currentTool == ToolType.ERASER) return
            val pt = StrokePoint(x = p0.x, y = p0.y, pressure = p0.pressure.toFloat(), tilt = p0.tiltX.toFloat())
            currentPoints.add(pt)
            callback?.onStrokeMove(p0.x, p0.y, p0.pressure.toFloat())
        }

        override fun onRawDrawingTouchPointListReceived(p0: TouchPointList) {
            if (currentTool == ToolType.ERASER) return
            val pts = if (currentPoints.size >= 2) {
                currentPoints.toList()
            } else {
                (0 until p0.size()).map { i ->
                    val tp = p0.get(i)
                    StrokePoint(x = tp.x, y = tp.y, pressure = tp.pressure.toFloat(), tilt = tp.tiltX.toFloat())
                }
            }
            currentPoints.clear()
            if (pts.size >= 2) {
                callback?.onStrokeEnd(pts)
            }
        }

        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(p0: TouchPointList) {}
    }

    override fun attach(view: View, callback: StrokeCallback) {
        detach()
        val sv = view as? SurfaceView ?: run {
            Log.e("OnyxDrawingEngine", "OnyxDrawingEngine requires a SurfaceView")
            return
        }
        surfaceView = sv
        this.callback = callback

        try {
            activeLimitRect = getUsableLimitRect(sv)
            touchHelper = TouchHelper.create(sv, TouchHelper.FEATURE_SF_TOUCH_RENDER, rawInputCallback)
                .setStrokeWidth(3.0f)
                .setStrokeColor(Color.BLACK)
                .setLimitRect(listOf(activeLimitRect), excludeRects)
                .openRawDrawing()
            EpdController.enterScribbleMode(sv)
            touchHelper?.setRawDrawingEnabled(true)
            touchHelper?.setRawInputReaderEnable(true)
            sv.setOnTouchListener { _, ev -> touchHelper?.onTouchEvent(ev); true }
            Log.i("OnyxDrawingEngine", "initialized — isRawDrawingCreated=${touchHelper?.isRawDrawingCreated()}")
        } catch (e: Exception) {
            Log.e("OnyxDrawingEngine", "initialization failed", e)
        }
    }

    override fun detach() {
        surfaceView?.let { sv ->
            sv.setOnTouchListener(null)
            try {
                touchHelper?.setRawInputReaderEnable(false)
                touchHelper?.setRawDrawingEnabled(false)
                EpdController.leaveScribbleMode(sv)
                touchHelper?.closeRawDrawing()
            } catch (e: Exception) {
                Log.e("OnyxDrawingEngine", "detach failed", e)
            }
        }
        touchHelper = null
        surfaceView = null
        callback = null
        currentPoints.clear()
    }

    override fun setToolType(tool: ToolType) {
        currentTool = tool
        val sv = surfaceView ?: return
        try {
            when (tool) {
                ToolType.GEL_PEN -> {
                    sv.setOnTouchListener { _, ev -> touchHelper?.onTouchEvent(ev); true }
                    EpdController.enterScribbleMode(sv)
                    touchHelper?.setRawDrawingEnabled(true)
                    touchHelper?.setRawInputReaderEnable(true)
                }
                ToolType.ERASER -> {
                    touchHelper?.setRawInputReaderEnable(false)
                    touchHelper?.setRawDrawingEnabled(false)
                    EpdController.leaveScribbleMode(sv)
                    sv.setOnTouchListener { _, ev -> handleEraserEvent(ev); true }
                }
            }
        } catch (e: Exception) {
            Log.e("OnyxDrawingEngine", "setToolType($tool) failed", e)
        }
    }

    private fun handleEraserEvent(event: MotionEvent) {
        val cb = callback ?: return
        val isStylusInput = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val pressure = if (isStylusInput) event.pressure else 0.5f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints.clear()
                currentPoints.add(StrokePoint(event.x, event.y, pressure))
                cb.onStrokeBegin(event.x, event.y, pressure)
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(StrokePoint(event.x, event.y, pressure))
                cb.onStrokeMove(event.x, event.y, pressure)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPoints.add(StrokePoint(event.x, event.y, pressure))
                if (currentPoints.size >= 2) cb.onStrokeEnd(currentPoints.toList())
                currentPoints.clear()
            }
        }
    }

    override fun refreshCanvas() {
        surfaceView?.let { sv ->
            try {
                EpdController.enterScribbleMode(sv)
            } catch (e: Exception) {
                Log.d("OnyxDrawingEngine", "refreshCanvas failed: ${e.message}")
            }
        }
    }

    override fun onResume() {
        surfaceView?.let { setToolType(currentTool) }
    }

    override fun onPause() {
        val sv = surfaceView ?: return
        try {
            touchHelper?.setRawInputReaderEnable(false)
            touchHelper?.setRawDrawingEnabled(false)
            EpdController.leaveScribbleMode(sv)
        } catch (e: Exception) {
            Log.e("OnyxDrawingEngine", "onPause failed", e)
        }
    }

    override fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects
        try {
            touchHelper?.setLimitRect(listOf(activeLimitRect), rects)
        } catch (e: Exception) {
            Log.e("OnyxDrawingEngine", "setExcludeRects failed", e)
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
