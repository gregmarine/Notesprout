package com.notesprout.notesprout.device

import com.notesprout.notesprout.device.DeviceDetector.DeviceType

object DrawingEngineFactory {
    fun create(): DrawingEngine {
        return when (DeviceDetector.detect()) {
            DeviceType.BOOX -> OnyxDrawingEngine()
            DeviceType.GENERIC_ANDROID -> GenericDrawingEngine()
        }
    }
}
