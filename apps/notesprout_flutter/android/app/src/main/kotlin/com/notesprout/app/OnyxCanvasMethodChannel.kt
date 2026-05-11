package com.notesprout.app

import android.content.Context
import android.os.Build
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class OnyxCanvasMethodChannel(flutterEngine: FlutterEngine, context: Context) {

    private var currentView: OnyxCanvasView? = null

    init {
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "com.notesprout/onyx_canvas_view",
            OnyxCanvasViewFactory(context) { view -> currentView = view },
        )

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.notesprout/onyx_canvas",
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "ping" -> {
                    // Returns "ok" only on BOOX devices so the Dart factory can
                    // distinguish hardware-accelerated drawing from generic fallback.
                    val isBoox = Build.MANUFACTURER
                        .lowercase(Locale.getDefault())
                        .contains("onyx")
                    result.success(if (isBoox) "ok" else "not_boox")
                }
                "initialize" -> result.success(null)
                "setCanvasOffset" -> {
                    val x = call.argument<Int>("x") ?: 0
                    val y = call.argument<Int>("y") ?: 0
                    currentView?.setCanvasOffset(x, y)
                    result.success(null)
                }
                "clear" -> {
                    currentView?.clear()
                    result.success(null)
                }
                "dispose" -> {
                    currentView?.dispose()
                    currentView = null
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
