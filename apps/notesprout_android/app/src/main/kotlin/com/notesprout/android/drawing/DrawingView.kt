package com.notesprout.android.drawing

import android.graphics.PointF
import android.view.View

interface DrawingView {
    fun asView(): View
    fun setToolbarHeight(heightPx: Int)
    fun enableDrawing()
    fun disableDrawing()
    fun resetOverlay() {}
    fun clearCanvas()
    fun setEraserMode(active: Boolean) {}
    fun releaseResources()

    /**
     * Replace the in-memory stroke list with [strokes] loaded from the database,
     * then redraw the canvas bitmap immediately.
     * Must be called on the main thread (triggers invalidate).
     */
    fun loadStrokes(strokes: List<List<PointF>>)

    /**
     * Return a snapshot of the current in-memory stroke list.
     * Each inner list is the ordered sequence of (x, y) points for one stroke.
     * Safe to call from any thread — implementations return a copy.
     */
    fun getStrokes(): List<List<PointF>>
}
