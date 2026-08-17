package com.symmetricalpalmtree.notesprout.ext.mlkit

import android.content.Context
import android.content.SharedPreferences
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
 * Readiness is **one async chain** — `isModelDownloaded` → `download` (if needed) → build the client
 * — the same chain the original app runs once at startup. It is started by the service's `onCreate`
 * (the moment the host first binds) and by `prepare()`, and it runs on ML Kit's own executor.
 * **`status()` never waits on ML Kit** (M1 finding: the first `isModelDownloaded` in a fresh process
 * can take tens of seconds on a Nomad — a bounded synchronous wait timed out every time and was
 * misreported as `UNAVAILABLE`). `awaitReady` is what the recognize calls use: they wait for the
 * chain inside the host's timeout instead of failing "not ready" while the model is seconds away.
 *
 * **Model-present memory:** once the chain has seen the model on disk (present or just downloaded)
 * a flag is kept in this extension's own `SharedPreferences` (engine state — the same sandbox
 * exception as the model itself). A fresh process with the flag set builds the client at once —
 * READY without ML Kit's cold `isModelDownloaded` (28 s on a Nomad, M1) — and the host never
 * mistakes "checking" for "downloading". If the model turns out to be gone (an engine failure on the
 * shortcut client), the flag is cleared and the next `start()` runs the full chain again.
 *
 * All methods are called on Binder threads (never main), so `Tasks.await` is legal here.
 * Logs carry class names and durations only — never text.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val LANGUAGE_TAG = "en-US"
    private const val PREFS = "model"
    private const val KEY_PRESENT = "present:$LANGUAGE_TAG"

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    /** True when the current client was built from the flag alone (no ML Kit check this process). */
    @Volatile private var shortcut = false

    /** Called once by the service on create; wires the model-present memory. */
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Built once the model is on the device; non-null ⇔ READY. */
    @Volatile private var recognizer: DigitalInkRecognizer? = null

    /** The in-flight (or last finished) ensure-ready chain, if any. */
    @Volatile private var chain: Task<Void>? = null

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

    /** One of [RecognizerStatus]. Never blocks. */
    fun status(): Int {
        if (recognizer != null) return RecognizerStatus.READY
        if (model == null) return RecognizerStatus.UNAVAILABLE
        val c = chain
        return if (c != null && !c.isComplete) RecognizerStatus.DOWNLOADING else RecognizerStatus.NEEDS_DOWNLOAD
    }

    /**
     * Start (or restart after a failure) the ensure-ready chain and return at once. Idempotent: a
     * no-op while READY or while a chain is in flight. Success → READY; failure logs the exception
     * class and leaves the state retryable (`NEEDS_DOWNLOAD` on the next `status()`).
     */
    fun prepare() {
        start()
    }

    /** [prepare] under its internal name; also called by the service on create (the startup warm-up). */
    fun start(): Task<Void>? {
        if (recognizer != null) return chain
        val m = model ?: return null
        synchronized(lock) {
            val c = chain
            if (c != null && !c.isComplete) return c
            if (prefs?.getBoolean(KEY_PRESENT, false) == true) {
                // Seen on disk before: skip ML Kit's cold check and be READY now.
                if (BuildConfig.DEBUG) Log.d(TAG, "model remembered as present — building client directly")
                shortcut = true
                buildClient(m)
                return Tasks.forResult<Void>(null).also { chain = it }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "ensure-ready chain start")
            val t0 = System.currentTimeMillis()
            val manager = RemoteModelManager.getInstance()
            val started: Task<Void> = manager.isModelDownloaded(m)
                .onSuccessTask { downloaded ->
                    if (downloaded == true) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "model present (${System.currentTimeMillis() - t0} ms)")
                        Tasks.forResult<Void>(null)
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "download start (${System.currentTimeMillis() - t0} ms)")
                        manager.download(m, DownloadConditions.Builder().build())
                    }
                }
                .onSuccessTask {
                    prefs?.edit()?.putBoolean(KEY_PRESENT, true)?.apply()
                    shortcut = false
                    buildClient(m)
                    if (BuildConfig.DEBUG) Log.d(TAG, "ready (${System.currentTimeMillis() - t0} ms)")
                    Tasks.forResult<Void>(null)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ensure-ready failed: ${e.javaClass.simpleName} (${System.currentTimeMillis() - t0} ms)")
                }
            chain = started
            return started
        }
    }

    /**
     * The client, waiting up to [timeoutMs] for the chain (starting it if needed); null if it is not
     * READY by then or the chain failed. The caller's Binder timeout is the outer ceiling.
     */
    fun awaitReady(timeoutMs: Long): DigitalInkRecognizer? {
        recognizer?.let { return it }
        val c = start() ?: return null
        try {
            Tasks.await(c, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "awaitReady: ${e.javaClass.simpleName} after $timeoutMs ms")
        }
        return recognizer
    }

    /**
     * The engine failed on a call. If the client came from the model-present shortcut the model may
     * be gone: forget the flag and drop the client so the next `start()` runs the real chain (which
     * re-downloads if needed). A failure on a fully-checked client is left alone (transient).
     */
    fun onEngineFailure() {
        if (!shortcut) return
        synchronized(lock) {
            if (!shortcut) return
            Log.w(TAG, "engine failure on the remembered model — forgetting it, will re-check")
            prefs?.edit()?.remove(KEY_PRESENT)?.apply()
            shortcut = false
            recognizer?.close()
            recognizer = null
            chain = null
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
