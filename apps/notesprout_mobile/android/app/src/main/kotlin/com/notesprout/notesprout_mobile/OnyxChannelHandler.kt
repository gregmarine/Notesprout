package com.notesprout.notesprout_mobile

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class OnyxChannelHandler {
    companion object {
        private const val CHANNEL = "com.notesprout/onyx"
    }

    fun register(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "initialize" -> {
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.setAppOptimizationMode()
                        } catch (_: Throwable) {}
                        result.success(null)
                    }
                    "setDrawingMode" -> {
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.setNormalFreshMode()
                        } catch (_: Throwable) {}
                        result.success(null)
                    }
                    "setEraserMode" -> {
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.setNormalFreshMode()
                        } catch (_: Throwable) {}
                        result.success(null)
                    }
                    "dispose" -> {
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.setAppOptimizationMode()
                        } catch (_: Throwable) {}
                        result.success(null)
                    }
                    else -> result.success(null)
                }
            }
    }
}
