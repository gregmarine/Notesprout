package com.symmetricalpalmtree.notesprout

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application for the Flutter Onyx pen spike.
 *
 * The BOOX SDK reflects into hidden Android system APIs (VMRuntime / RawInputManager). Android 14+
 * blocks `VMRuntime.setHiddenApiExemptions`, so the SDK cannot bootstrap itself unless we relax the
 * enforcement at the JNI level *before any SDK code runs*. Mirrors the native app's
 * `NotesproutApplication`.
 */
class NotesproutSpikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("")
    }
}
