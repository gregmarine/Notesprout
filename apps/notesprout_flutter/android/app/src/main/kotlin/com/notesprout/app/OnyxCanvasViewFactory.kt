package com.notesprout.app

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class OnyxCanvasViewFactory(
    private val context: Context,
    private val onViewCreated: (OnyxCanvasView) -> Unit,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val view = OnyxCanvasView(this.context, viewId, args)
        onViewCreated(view)
        return view
    }
}
