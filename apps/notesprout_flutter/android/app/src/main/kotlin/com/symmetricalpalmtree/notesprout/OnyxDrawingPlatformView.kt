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
) : PlatformView, MethodChannel.MethodCallHandler {

    private val spikeView = OnyxSpikeView(context)
    private val channel = MethodChannel(messenger, "notesprout/onyx").apply {
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
            "setDrawingEnabled" -> {
                spikeView.setDrawingEnabled(call.arguments as? Boolean ?: true)
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
