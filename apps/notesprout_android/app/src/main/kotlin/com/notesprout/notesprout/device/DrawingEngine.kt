package com.notesprout.notesprout.device

import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceView

data class TouchPointData(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val timestamp: Long
)

interface DrawingEngineCallback {
    fun onStrokeStarted(x: Float, y: Float)
    fun onStrokePoint(x: Float, y: Float, pressure: Float, tilt: Float)
    fun onActiveStrokeUpdated()
    fun onStrokeEnded(points: List<TouchPointData>)
    fun onEraserMoved(x: Float, y: Float)
    fun onEraserEnded()
}

interface DrawingEngine {
    fun initialize(surfaceView: SurfaceView, callback: DrawingEngineCallback)
    fun setDrawingEnabled(enabled: Boolean)
    fun setEraserEnabled(enabled: Boolean)
    fun setStrokeWidth(width: Float)
    fun setStrokeColor(color: Int)
    fun updateLimitRect(rect: Rect)
    /** Called from dispatchTouchEvent for pen mode. Onyx forwards to TouchHelper; Generic is a no-op. */
    fun onTouchEvent(ev: MotionEvent)
    /** Cycles raw drawing to flush the SDK overlay after bitmap mutations (Onyx only; no-op for Generic). */
    fun resetDrawing()
    fun onResume()
    fun onPause()
    fun onDestroy()
    val isAvailable: Boolean
    /** True for Generic (CanvasActivity must blit live path); false for Onyx (SDK renders it natively). */
    val supportsLivePreview: Boolean
}
