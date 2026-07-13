package com.notesprout.android

import android.app.Application
import com.notesprout.android.data.toClipboardContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NotesproutApplication : Application() {
    companion object {
        /**
         * Application-scoped coroutine scope for IO work that must outlive an Activity —
         * notably the notebook file-seal in [NotebookActivity.closeNotebook], which finishes
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TrOCR's ORT sessions hold ~model-size native heap; drop them when the app goes
        // to the background (they reload lazily on the next recognition).
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            com.notesprout.android.recognition.HandwritingRecognizerProvider.onTrimMemory()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NotesproutApplication", "SQLCipher native lib failed to load", e)
        }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        // BOOX SDK uses reflection to call hidden Android system APIs (VMRuntime,
        // RawInputManager). Android 14+ blocks VMRuntime.setHiddenApiExemptions, so the
        // SDK cannot bootstrap itself. This bypasses the enforcement at the JNI level
        // before any SDK code runs.
        HiddenApiBypass.addHiddenApiExemptions("")

        com.notesprout.android.data.index.NotesproutIndex.open(this)

        val repository = com.notesprout.android.data.index.IndexRepository(
            com.notesprout.android.data.index.NotesproutIndex.dao()
        )
        appScope.launch { repository.ensurePinnedListExists() }
        appScope.launch { repository.ensurePinnedTemplatesListExists() }
        appScope.launch {
            val payload = repository.loadClipboard()
            if (payload != null && NotesproutClipboard.content == null) {
                NotesproutClipboard.content = payload.toClipboardContent()
            }
        }

        // Both engines register with the Provider; the settings toggle decides routing.
        // TrOCR does zero work here — its ORT sessions load lazily on first recognition.
        val mlKitRecognizer = com.notesprout.android.recognition.MlKitHandwritingRecognizer()
        val trOcrRecognizer = com.notesprout.android.recognition.trocr.TrOcrHandwritingRecognizer(this, appScope)
        com.notesprout.android.recognition.HandwritingRecognizerProvider.init(this, mlKitRecognizer, trOcrRecognizer)
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
