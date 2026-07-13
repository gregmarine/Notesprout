package com.notesprout.android.recognition

import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.notesprout.android.core.Slog
import com.notesprout.android.data.LiveStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MlKitHandwritingRecognizer : HandwritingRecognizer {

    override val engineName: String = "mlkit"

    private var recognizer: com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer? = null
    private var modelReady = false

    /** Called by HandwritingRecognizerProvider during app startup. */
    fun initModel(onComplete: (success: Boolean) -> Unit) {
        val modelIdentifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create model identifier", e)
            onComplete(false)
            return
        }

        if (modelIdentifier == null) {
            Log.e(TAG, "Null model identifier for en-US")
            onComplete(false)
            return
        }

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    Slog.d(TAG) { "en-US model already downloaded" }
                    buildRecognizer(model)
                    onComplete(true)
                } else {
                    Slog.d(TAG) { "Downloading en-US model..." }
                    remoteModelManager.download(
                        model,
                        DownloadConditions.Builder().build()
                    )
                        .addOnSuccessListener {
                            Slog.d(TAG) { "en-US model download complete" }
                            buildRecognizer(model)
                            onComplete(true)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "en-US model download failed", e)
                            onComplete(false)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "isModelDownloaded check failed", e)
                onComplete(false)
            }
    }

    private fun buildRecognizer(model: DigitalInkRecognitionModel) {
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        modelReady = true
    }

    override fun isReady(): Boolean = modelReady

    override fun recognize(
        strokes: List<LiveStroke>,
        bounds: RectF,
        onResult: (String) -> Unit
    ) {
        val r = recognizer
        if (!modelReady || r == null) {
            onResult(HandwritingRecognizer.FALLBACK_TEXT)
            return
        }

        val inkBuilder = Ink.builder()
        for (liveStroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for (point in liveStroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        val writingArea = WritingArea(bounds.width(), bounds.height())
        val recognitionContext = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(writingArea)
            .build()

        r.recognize(inkBuilder.build(), recognitionContext)
            .addOnSuccessListener { result ->
                val text = result.candidates.firstOrNull()?.text
                val recognized = if (!text.isNullOrBlank()) text else HandwritingRecognizer.FALLBACK_TEXT
                Slog.d(TAG) { "Recognition result: \"$recognized\"" }
                onResult(recognized)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Recognition failed", e)
                onResult(HandwritingRecognizer.FALLBACK_TEXT)
            }
    }

    override suspend fun recognizeSegment(
        strokes: List<LiveStroke>,
        bounds: RectF,
        preContext: String,
        lineHeightHint: Float,
    ): String {
        val r = recognizer
        if (!modelReady || r == null || strokes.isEmpty()) {
            return HandwritingRecognizer.FALLBACK_TEXT
        }

        val inkBuilder = Ink.builder()
        for (liveStroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for (point in liveStroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        // Prefer a page-consistent line height as the writing-area reference (see interface doc);
        // fall back to the line's own bbox height when no hint is supplied.
        val areaHeight = (if (lineHeightHint > 0f) lineHeightHint else bounds.height()).coerceAtLeast(1f)
        val writingArea = WritingArea(bounds.width().coerceAtLeast(1f), areaHeight)
        val recognitionContext = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(MAX_PRECONTEXT_CHARS))
            .setWritingArea(writingArea)
            .build()

        return suspendCancellableCoroutine { cont ->
            r.recognize(inkBuilder.build(), recognitionContext)
                .addOnSuccessListener { result ->
                    val text = result.candidates.firstOrNull()?.text
                    val recognized = if (!text.isNullOrBlank()) text else HandwritingRecognizer.FALLBACK_TEXT
                    if (cont.isActive) cont.resume(recognized)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Segment recognition failed", e)
                    if (cont.isActive) cont.resume(HandwritingRecognizer.FALLBACK_TEXT)
                }
        }
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
        modelReady = false
    }

    companion object {
        private const val TAG = "MlKitHwRecognizer"
        /** Cap the pre-context handed to ML Kit — only the tail matters, and long strings hurt latency.
         *  Google's guidance: as many chars as possible up to ~20; beyond that gives no benefit. */
        private const val MAX_PRECONTEXT_CHARS = 20
    }
}
