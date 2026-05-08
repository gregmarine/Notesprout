package com.notesprout.notesprout.device

import android.content.Context
import com.notesprout.notesprout.device.DeviceDetector.DeviceType

object DrawingEngineFactory {
    fun create(context: Context): DrawingEngine {
        return when (DeviceDetector.detect(context)) {
            DeviceType.BOOX_EMR -> OnyxDrawingEngine()
            DeviceType.BOOX_USI -> OnyxUsiDrawingEngine()
            DeviceType.GENERIC_ANDROID -> GenericDrawingEngine()
        }
    }
}
