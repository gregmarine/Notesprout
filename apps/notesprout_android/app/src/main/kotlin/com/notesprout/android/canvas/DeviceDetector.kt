package com.notesprout.android.canvas

import android.content.Context
import android.os.Build
import android.util.Log

enum class DeviceType { BOOX, GENERIC_ANDROID }

object DeviceDetector {
    fun detect(context: Context): DeviceType {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        if (manufacturer.contains("onyx") || brand.contains("onyx")) {
            return DeviceType.BOOX
        }
        return try {
            Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            DeviceType.BOOX
        } catch (e: ClassNotFoundException) {
            DeviceType.GENERIC_ANDROID
        }
    }
}
