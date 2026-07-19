package com.notesprout.android.recognition

import android.content.Context
import com.notesprout.android.recognition.trocr.TrOcrHandwritingRecognizer

/**
 * App-level singleton that holds the available [HandwritingRecognizer]s and routes
 * callers to the **active** one.
 *
 * Routing is the ONLY thing that changes when the user flips the Handwriting Engine
 * toggle: every consumer (NotebookActivity conversions, RTR, export, viewer) re-fetches
 * [instance] per use, so no call site knows more than the interface.
 *
 * Selection rule: TrOCR when the settings toggle says so AND a model bundle is
 * installed; ML Kit otherwise. ML Kit therefore remains the always-there fallback —
 * deleting the TrOCR model can never leave the app without recognition.
 *
 * Initialized once in NotesproutApplication.onCreate().
 */
object HandwritingRecognizerProvider {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var mlKit: HandwritingRecognizer? = null

    @Volatile
    private var trOcr: TrOcrHandwritingRecognizer? = null

    /** The active recognizer per the settings toggle, or null before init. */
    val instance: HandwritingRecognizer?
        get() {
            val ctx = appContext ?: return mlKit
            val t = trOcr
            return if (t != null && HwrSettings.engine(ctx) == HwrSettings.ENGINE_TROCR && t.isReady()) {
                t
            } else {
                mlKit
            }
        }

    /**
     * The ML Kit engine regardless of the toggle — used by [PageTextRecognizer] as a
     * per-line retry when the active engine returns FALLBACK_TEXT.
     */
    val mlKitFallback: HandwritingRecognizer?
        get() = mlKit

    /** The TrOCR engine (for settings UI / memory trimming), independent of the toggle. */
    val trOcrEngine: TrOcrHandwritingRecognizer?
        get() = trOcr

    /** Called only from NotesproutApplication. */
    internal fun init(context: Context, mlKitRecognizer: HandwritingRecognizer, trOcrRecognizer: TrOcrHandwritingRecognizer) {
        appContext = context.applicationContext
        mlKit = mlKitRecognizer
        trOcr = trOcrRecognizer
    }

    /**
     * Preload the active engine ahead of a likely recognition (e.g. the user just selected
     * strokes). Only TrOCR has a meaningful cold-start cost; ML Kit no-ops.
     */
    fun warmUpActive() {
        val ctx = appContext ?: return
        val t = trOcr ?: return
        if (HwrSettings.engine(ctx) == HwrSettings.ENGINE_TROCR) t.warmUp()
    }

    /** Called from NotesproutApplication.onTrimMemory — drop TrOCR sessions if idle. */
    fun onTrimMemory() {
        trOcr?.releaseIfIdle()
    }

    /** Called from NotesproutApplication.onTerminate(). */
    internal fun shutdown() {
        mlKit?.close(); mlKit = null
        trOcr?.close(); trOcr = null
        appContext = null
    }
}
