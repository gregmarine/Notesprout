package com.symmetricalpalmtree.notesprout

import android.content.Context
import android.util.Log
import android.view.View
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

/**
 * Wraps [OnyxSpikeView] as a Flutter PlatformView. One MethodChannel `notesprout/onyx` carries
 * both directions: Dart→native tool commands, and native→Dart `penEvent` pushes (committed-stroke /
 * eraser points). Using invokeMethod for the push avoids the EventChannel registration-ordering
 * race that silently dropped every stroke.
 */
class OnyxDrawingPlatformView(
    context: Context,
    messenger: BinaryMessenger,
    channelName: String = "notesprout/onyx",
) : PlatformView, MethodChannel.MethodCallHandler {

    private val spikeView = OnyxSpikeView(context)
    private val channel = MethodChannel(messenger, channelName).apply {
        setMethodCallHandler(this@OnyxDrawingPlatformView)
    }

    init {
        // Onyx callbacks fire on the main thread — invoke straight up to Dart.
        spikeView.onEvent = { event ->
            Log.d("OnyxSpike", "penEvent → Dart type=${event["type"]} n=${(event["points"] as List<*>).size / 2}")
            channel.invokeMethod("penEvent", event)
        }
    }

    override fun getView(): View = spikeView

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "setPen" -> { spikeView.setPen(); result.success(null) }
            "setEraser" -> { spikeView.setEraser(); result.success(null) }
            "clear" -> { spikeView.clear(); result.success(null) }
            "repaintPanel" -> { spikeView.repaintPanel(); result.success(null) }
            "resume" -> { spikeView.resume(); result.success(null) }
            "setDrawingEnabled" -> {
                spikeView.setDrawingEnabled(call.arguments as? Boolean ?: true)
                result.success(null)
            }
            "setToolbarExclusion" -> {
                val a = call.arguments as? List<*>
                fun px(i: Int) = (a?.getOrNull(i) as? Number)?.toInt() ?: 0
                spikeView.setToolbarExclusion(px(0), px(1), px(2), px(3))
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun dispose() {
        channel.setMethodCallHandler(null)
        spikeView.onEvent = null
    }
}
