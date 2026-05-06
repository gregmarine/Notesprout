package com.notesprout.notesprout_mobile

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class OnyxChannelHandler {
    companion object {
        private const val CHANNEL = "com.notesprout/onyx"

        private fun invokeEpd(methodName: String) {
            try {
                val cls = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
                cls.getMethod(methodName).invoke(null)
            } catch (_: Throwable) {}
        }
    }

    fun register(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "initialize" -> invokeEpd("setAppOptimizationMode")
                    "setDrawingMode" -> invokeEpd("setNormalFreshMode")
                    "setEraserMode" -> invokeEpd("setNormalFreshMode")
                    "dispose" -> invokeEpd("setAppOptimizationMode")
                }
                result.success(null)
            }
    }
}
