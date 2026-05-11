package com.notesprout.android.drawing

import android.view.View

interface DrawingView {
    fun asView(): View
    // Called with the toolbar's pixel height so the engine can exclude that
    // region from pen input (BOOX setLimitRect) or ignore it (GenericDrawingView).
    fun setToolbarHeight(heightPx: Int)
    fun enableDrawing()
    fun disableDrawing()
    fun clearCanvas()
    fun releaseResources()
}
