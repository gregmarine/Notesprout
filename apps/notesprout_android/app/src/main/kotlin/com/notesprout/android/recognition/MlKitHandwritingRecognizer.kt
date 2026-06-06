package com.notesprout.android.recognition

import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.notesprout.android.data.LiveStroke

class MlKitHandwritingRecognizer : HandwritingRecognizer {

    private var recognizer: com.google.mlkit.vision.digitalink.DigitalInkRecognizer? = null
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
                    Log.d(TAG, "en-US model already downloaded")
                    buildRecognizer(model)
                    onComplete(true)
                } else {
                    Log.d(TAG, "Downloading en-US model...")
                    remoteModelManager.download(
                        model,
                        DownloadConditions.Builder().build()
                    )
                        .addOnSuccessListener {
                            Log.d(TAG, "en-US model download complete")
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
                Log.d(TAG, "Recognition result: \"$recognized\"")
                onResult(recognized)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Recognition failed", e)
                onResult(HandwritingRecognizer.FALLBACK_TEXT)
            }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        modelReady = false
    }

    companion object {
        private const val TAG = "MlKitHwRecognizer"
    }
}
