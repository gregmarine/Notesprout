package com.notesprout.notesprout.device

import android.os.Build

object DeviceDetector {

    enum class DeviceType {
        BOOX,
        GENERIC_ANDROID
    }

    fun detect(): DeviceType {
        val isBooxManufacturer = Build.MANUFACTURER.contains("onyx", ignoreCase = true) ||
            Build.BRAND.contains("onyx", ignoreCase = true) ||
            Build.MODEL.contains("boox", ignoreCase = true)

        if (!isBooxManufacturer) return DeviceType.GENERIC_ANDROID

        val hasOnyxClass = try {
            Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        if (!hasOnyxClass) return DeviceType.GENERIC_ANDROID

        return DeviceType.BOOX
    }
}
