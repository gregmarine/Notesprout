package com.notesprout.android.drawing

import android.view.View

interface DrawingView {
    fun asView(): View
    // Called with the toolbar's pixel height so the engine can exclude that
    // region from pen input (BOOX setLimitRect) or ignore it (GenericDrawingView).
    fun setToolbarHeight(heightPx: Int)
    fun enableDrawing()
    fun disableDrawing()
    // Bakes any hardware-layer strokes into the canvas bitmap, then calls onComplete.
    // Must be called before any action that ends the current writing session
    // (tool switch, clear, page turn, undo, onPause, etc.).
    fun commitStrokes(onComplete: () -> Unit = {})
    fun clearCanvas()
    fun releaseResources()
}
