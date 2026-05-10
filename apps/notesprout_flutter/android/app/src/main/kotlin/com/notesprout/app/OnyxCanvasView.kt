package com.notesprout.app

import android.content.Context
import android.view.View
import io.flutter.plugin.platform.PlatformView

class OnyxCanvasView(context: Context, id: Int, args: Any?) : PlatformView {

    private val drawingView = OnyxDrawingView(context)

    override fun getView(): View = drawingView

    fun clear() = drawingView.clearCanvas()

    override fun dispose() {
        drawingView.releaseResources()
    }
}
