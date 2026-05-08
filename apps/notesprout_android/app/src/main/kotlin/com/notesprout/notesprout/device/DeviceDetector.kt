package com.notesprout.notesprout.device

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice

object DeviceDetector {

    enum class DeviceType {
        BOOX_EMR,        // Wacom EMR digitizer — use OnyxDrawingEngine (FEATURE_SF_TOUCH_RENDER)
        BOOX_USI,        // InkSense / USI 2.0 — use OnyxUsiDrawingEngine (standard MotionEvent)
        GENERIC_ANDROID  // use GenericDrawingEngine
    }

    fun detect(context: Context): DeviceType {
        val isBoox = Build.MANUFACTURER.contains("onyx", ignoreCase = true) ||
            Build.BRAND.contains("onyx", ignoreCase = true) ||
            Build.MODEL.contains("boox", ignoreCase = true)

        if (!isBoox) return DeviceType.GENERIC_ANDROID

        val hasOnyxSdk = try {
            Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        if (!hasOnyxSdk) return DeviceType.GENERIC_ANDROID

        // Wacom EMR digitizers always use vendor ID 0x056A. USI / InkSense controllers
        // use different vendor IDs. Checking live input devices is more reliable than
        // SDK reflection or device-name lists and covers any future EMR or USI hardware.
        // Wacom EMR digitizers enumerate as an InputDevice whose name contains "wacom".
        // On NA5C (I2C) the vendor ID is 0x2d1f (Onyx), not 0x056A (Wacom USB), so
        // name-matching is more reliable across both I2C and USB Wacom hardware.
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val hasWacom = im.inputDeviceIds.any { id ->
            val device = im.getInputDevice(id) ?: return@any false
            device.name.contains("wacom", ignoreCase = true) &&
                (device.sources and InputDevice.SOURCE_STYLUS) != 0
        }

        return if (hasWacom) DeviceType.BOOX_EMR else DeviceType.BOOX_USI
    }
}
