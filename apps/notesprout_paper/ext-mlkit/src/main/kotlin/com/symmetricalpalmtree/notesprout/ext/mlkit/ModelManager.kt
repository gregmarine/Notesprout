package com.symmetricalpalmtree.notesprout.ext.mlkit

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.symmetricalpalmtree.notesprout.extension.RecognizerStatus
import java.util.concurrent.TimeUnit

/**
 * Process-lifetime owner of the `en-US` model and the ML Kit client. The model lives in this
 * extension's own app storage, managed by ML Kit — the recorded exception to "extensions keep data
 * in the host store": an engine asset is not user data, and it is far over the store's value cap.
 * Uninstalling the extension removes it.
 *
 * All methods are called on Binder threads (never main), so `Tasks.await` is legal here.
 * `status()` is bounded to fit inside the host's 2 s call timeout; `prepare()` returns at once.
 * Logs carry class names and counts only — never text.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val LANGUAGE_TAG = "en-US"

    /** `isModelDownloaded` wait — inside the host's 2 s `status()` timeout. */
    private const val STATUS_AWAIT_MS = 1_500L

    private val lock = Any()

    /** Built once the model is on the device; non-null ⇔ READY. */
    @Volatile private var recognizer: DigitalInkRecognizer? = null

    /** The in-flight `prepare()` download, if any. */
    @Volatile private var download: Task<Void>? = null

    private val model: DigitalInkRecognitionModel? by lazy {
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
        } catch (e: Exception) {
            Log.e(TAG, "model identifier failed: ${e.javaClass.simpleName}")
            null
        }
        if (id == null) {
            Log.e(TAG, "no model identifier for $LANGUAGE_TAG")
            null
        } else {
            DigitalInkRecognitionModel.builder(id).build()
        }
    }

    /** The client, or null when not READY. */
    fun recognizer(): DigitalInkRecognizer? = recognizer

    /** One of [RecognizerStatus]. Cheap when READY; otherwise asks ML Kit (≤ [STATUS_AWAIT_MS]). */
    fun status(): Int {
        if (recognizer != null) return RecognizerStatus.READY
        val m = model ?: return RecognizerStatus.UNAVAILABLE
        val d = download
        if (d != null && !d.isComplete) return RecognizerStatus.DOWNLOADING
        return try {
            val downloaded = Tasks.await(
                RemoteModelManager.getInstance().isModelDownloaded(m), STATUS_AWAIT_MS, TimeUnit.MILLISECONDS,
            )
            if (downloaded) {
                buildClient(m)
                RecognizerStatus.READY
            } else {
                RecognizerStatus.NEEDS_DOWNLOAD
            }
        } catch (e: Exception) {
            Log.w(TAG, "isModelDownloaded failed: ${e.javaClass.simpleName}")
            RecognizerStatus.UNAVAILABLE
        }
    }

    /**
     * Start the model download (any network) and return at once. Idempotent: a no-op while READY or
     * while a download is already in flight. Success builds the client (→ READY); failure logs the
     * class and leaves the state retryable (→ NEEDS_DOWNLOAD on the next `status()`).
     */
    fun prepare() {
        if (recognizer != null) return
        val m = model ?: return
        synchronized(lock) {
            val d = download
            if (d != null && !d.isComplete) return
            if (BuildConfig.DEBUG) Log.d(TAG, "download start")
            download = RemoteModelManager.getInstance()
                .download(m, DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) Log.d(TAG, "download complete")
                    buildClient(m)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
                }
        }
    }

    private fun buildClient(m: DigitalInkRecognitionModel) {
        synchronized(lock) {
            if (recognizer != null) return
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
            if (BuildConfig.DEBUG) Log.d(TAG, "client built")
        }
    }
}
