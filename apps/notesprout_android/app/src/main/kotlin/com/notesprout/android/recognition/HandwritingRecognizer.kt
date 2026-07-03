package com.notesprout.android.recognition

import android.graphics.RectF
import com.notesprout.android.data.LiveStroke

/**
 * General-purpose handwriting recognition interface.
 * Operates on raw stroke data with no knowledge of the calling context
 * (headings, text boxes, search indexing, etc.).
 *
 * Implementations:
 *   - MlKitHandwritingRecognizer (Google ML Kit, all devices)
 *   - Future: OnyxHwrHandwritingRecognizer (BOOX firmware AIDL bridge)
 */
interface HandwritingRecognizer : AutoCloseable {

    /**
     * Returns true if this recognizer is ready to process strokes.
     * May return false if the ML model has not been downloaded yet.
     */
    fun isReady(): Boolean

    /**
     * Recognize handwritten text from a list of strokes within a bounding box.
     *
     * @param strokes  The strokes to recognize. Must not be empty.
     * @param bounds   The writing area bounding box (used to improve accuracy).
     * @param onResult Called on the main thread with the recognized string,
     *                 or with FALLBACK_TEXT if recognition fails or the
     *                 recognizer is not ready.
     */
    fun recognize(
        strokes: List<LiveStroke>,
        bounds: RectF,
        onResult: (String) -> Unit
    )

    /**
     * Recognize a single text line, feeding [preContext] (the previously recognized line)
     * into the model for per-line context chaining — the single biggest free accuracy win
     * for page-scale recognition. Suspends until the model returns.
     *
     * Returns the recognized string, or [FALLBACK_TEXT] if recognition fails or the
     * recognizer is not ready. Safe to call off the main thread.
     *
     * Used by the page/notebook text pipeline (StrokeSegmenter → PageTextRecognizer).
     */
    suspend fun recognizeSegment(
        strokes: List<LiveStroke>,
        bounds: RectF,
        preContext: String,
    ): String

    companion object {
        /** Returned when recognition fails or the model is unavailable. */
        const val FALLBACK_TEXT = "unrecognized"
    }
}
