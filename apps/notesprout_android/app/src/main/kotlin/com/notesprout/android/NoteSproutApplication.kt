package com.notesprout.android

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NoteSproutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // BOOX SDK uses reflection to call hidden Android system APIs (VMRuntime,
        // RawInputManager). Android 14+ blocks VMRuntime.setHiddenApiExemptions, so the
        // SDK cannot bootstrap itself. This bypasses the enforcement at the JNI level
        // before any SDK code runs. Pattern ported directly from BOOXDemo.
        HiddenApiBypass.addHiddenApiExemptions("")
    }
}
