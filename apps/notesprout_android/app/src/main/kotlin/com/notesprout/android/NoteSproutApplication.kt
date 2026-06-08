package com.notesprout.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NoteSproutApplication : Application() {
    companion object {
        /**
         * Application-scoped coroutine scope for IO work that must outlive an Activity —
         * notably the notebook file-seal in [DrawingActivity.closeNotebook], which finishes
         * the activity immediately and lets the heavy save/checkpoint complete here instead
         * of blocking the UI thread. SupervisorJob so one failed seal can't cancel others;
         * never cancelled (lives as long as the process).
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onTerminate() {
        super.onTerminate()
        com.notesprout.android.recognition.HandwritingRecognizerProvider.shutdown()
    }

    override fun onCreate() {
        super.onCreate()
        // BOOX SDK uses reflection to call hidden Android system APIs (VMRuntime,
        // RawInputManager). Android 14+ blocks VMRuntime.setHiddenApiExemptions, so the
        // SDK cannot bootstrap itself. This bypasses the enforcement at the JNI level
        // before any SDK code runs. Pattern ported directly from BOOXDemo.
        HiddenApiBypass.addHiddenApiExemptions("")

        val mlKitRecognizer = com.notesprout.android.recognition.MlKitHandwritingRecognizer()
        com.notesprout.android.recognition.HandwritingRecognizerProvider.init(mlKitRecognizer)
        mlKitRecognizer.initModel { success ->
            if (!success) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this,
                        "Handwriting recognition model unavailable. Check your connection and relaunch the app.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
