package com.symmetricalpalmtree.notesprout

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class OnyxDrawingPlatformViewFactory(
    private val messenger: BinaryMessenger,
    private val channelName: String = "notesprout/onyx",
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return OnyxDrawingPlatformView(context, messenger, channelName)
    }
}
