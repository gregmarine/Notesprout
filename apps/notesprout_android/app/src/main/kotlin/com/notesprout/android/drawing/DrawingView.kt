package com.notesprout.android.drawing

import android.view.View
import com.notesprout.android.data.LiveStroke

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
     * Called by the activity when a stroke is erased in memory.
     * Receives the UUID of the erased stroke so the activity can soft-delete its DB row.
     * Set this before the first user interaction; null by default.
     */
    var onStrokeErased: ((strokeId: String) -> Unit)?

    /**
     * Called after the pen has been idle for ~1.5 s (Onyx idle-release timer; Generic
     * equivalent posted on ACTION_UP).  The activity uses this to incrementally save
     * new strokes to the database without blocking the drawing thread.
     * Set this in onCreate; null by default.
     */
    var onIdleSave: (() -> Unit)?

    /**
     * Replace the in-memory stroke list with [strokes] loaded from the database,
     * then redraw the canvas bitmap immediately.
     * Must be called on the main thread (triggers invalidate).
     */
    fun loadStrokes(strokes: List<LiveStroke>)

    /**
     * Return a snapshot of the current in-memory stroke list.
     * Safe to call from any thread — implementations return a copy.
     */
    fun getStrokes(): List<LiveStroke>
}
