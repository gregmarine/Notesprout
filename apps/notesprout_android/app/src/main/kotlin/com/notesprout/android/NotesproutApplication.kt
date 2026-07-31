package com.notesprout.android

import android.app.Application
import com.notesprout.android.data.toClipboardContent
import kotlinx.coroutines.CoroutineExceptionHandler
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
         *
         * The exception handler is the last line of defense: work here is fire-and-forget with
         * no UI to surface into, so an escaped exception (disk full during a seal, a repo call on
         * a just-sealed index) would otherwise crash the whole app seconds after the user moved
         * on. Log it and keep the process alive — the data-side effects are the individual jobs'
         * responsibility to contain.
         */
        val appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                android.util.Log.e("NotesproutApplication", "Uncaught exception in appScope job", e)
            }
        )
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
        // Resolve whether this screen can show colour, before any Activity reads it. Must follow the
        // hidden-API bypass: the detection asks the Onyx SDK for the panel's colour type, and that
        // call reflects into a hidden framework API.
        com.notesprout.android.core.DisplayColor.init(this)

        // The index is encrypted (Phase 1b) and may need an async plaintext→encrypted migration or an
        // unlock prompt, so it can no longer open synchronously here. BootstrapActivity (launcher) and
        // MainActivity (deep-link entry) drive NotesproutIndex.ensureReady(); these index-dependent
        // startup tasks just wait until it's open.
        appScope.launch {
            com.notesprout.android.data.index.NotesproutIndex.awaitReady()
            val repository = com.notesprout.android.data.index.IndexRepository(
                com.notesprout.android.data.index.NotesproutIndex.dao()
            )
            repository.ensurePinnedListExists()
            repository.ensurePinnedTemplatesListExists()
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
