package com.notesprout.android.canvas

import android.graphics.Rect
import android.view.View

interface DrawingEngine {
    fun attach(view: View, callback: StrokeCallback)
    fun detach()
    fun setToolType(tool: ToolType)
    fun refreshCanvas()
    fun onResume() {}
    fun onPause() {}
    fun setExcludeRects(rects: List<Rect>) {}
}

interface StrokeCallback {
    fun onStrokeBegin(x: Float, y: Float, pressure: Float)
    fun onStrokeMove(x: Float, y: Float, pressure: Float)
    fun onStrokeEnd(points: List<StrokePoint>)
}

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class StrokeStyle(
    val color: Int = android.graphics.Color.BLACK,
    val baseWidth: Float = 3f,
    val maxWidth: Float = 6f
)

data class StrokeData(
    val id: String,
    val points: List<StrokePoint>,
    val style: StrokeStyle
)

enum class ToolType { GEL_PEN, ERASER }
