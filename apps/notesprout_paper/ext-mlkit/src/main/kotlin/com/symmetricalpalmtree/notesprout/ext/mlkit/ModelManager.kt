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
 * — the same chain the original app runs once at startup. **Only [prepare] starts it** (M2: the host
 * asks the user before a ~20 MB download, so nothing may download before `prepare()`; M1 had the
 * service's `onCreate` start the whole chain, which made the host's consent dialog cosmetic). It runs
 * on ML Kit's own executor, is idempotent while in flight and restartable after a failure.
 * **`status()` never waits on ML Kit** (M1 finding: the first `isModelDownloaded` in a fresh process
 * can take tens of seconds on a Nomad — a bounded synchronous wait timed out every time and was
 * misreported as `UNAVAILABLE`). `awaitReady` is what the recognize calls use: they wait for a chain
 * already in flight inside the host's timeout instead of failing "not ready" while the model is
 * seconds away — but they never start one.
 *
 * **Model-present memory:** once the chain has seen the model on disk (present or just downloaded)
 * a flag is kept in this extension's own `SharedPreferences` (engine state — the same sandbox
 * exception as the model itself). A fresh process with the flag set builds the client at once in
 * [warmUp] (the service's `onCreate`) — READY without ML Kit's cold `isModelDownloaded` (28 s on a
 * Nomad, M1) — and the host never mistakes "checking" for "downloading". Without the flag the
 * process starts nothing and reports `NEEDS_DOWNLOAD`: the host asks, `prepare()` runs the real
 * chain, and if the model was in fact on disk (a build that predates the flag) the chain finds it
 * without downloading. If the model turns out to be gone (an engine failure on the shortcut client
 * **confirmed** by a real `isModelDownloaded == false` — M2: a slow first inference is not "gone"),
 * the flag is cleared and the client dropped, so the next `status()` is `NEEDS_DOWNLOAD` again.
 *
 * All methods are called on Binder threads (never main), so `Tasks.await` is legal here.
 * Logs carry class names and durations only — never text.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** How long the throwaway priming inference may take before it is abandoned (it keeps running; the thread is a daemon). */

    private const val PRIME_AWAIT_MS = 30_000L
    private const val LANGUAGE_TAG = "en-US"
    private const val PREFS = "model"
    private const val KEY_PRESENT = "present:$LANGUAGE_TAG"

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    /** True when the current client was built from the flag alone (no ML Kit check this process). */
    @Volatile private var shortcut = false
    /** An `isModelDownloaded` re-check triggered by an engine failure on the shortcut client, while in flight. */
    @Volatile private var verifying = false

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
     * The startup warm-up (service `onCreate`, i.e. the host's first bind): if the model is
     * remembered as present, build the client now — READY at once, no ML Kit check. Otherwise do
     * **nothing** — no check, no download — until the host calls [prepare].
     */
    fun warmUp() {
        if (recognizer != null) return
        val m = model ?: return
        synchronized(lock) {
            if (recognizer != null) return
            if (prefs?.getBoolean(KEY_PRESENT, false) == true) {
                if (BuildConfig.DEBUG) Log.d(TAG, "model remembered as present — building client directly")
                shortcut = true
                buildClient(m)
                chain = Tasks.forResult<Void>(null)
            }
        }
    }

    /**
     * Start (or restart after a failure) the ensure-ready chain — `isModelDownloaded` → `download`
     * (any network, as the original) if needed → build the client — and return at once. Idempotent: a
     * no-op while READY or while a chain is in flight. Success → READY (and the model-present flag);
     * failure logs the exception class and leaves the state retryable (`NEEDS_DOWNLOAD` on the next
     * `status()`). **The only method that may start a download.**
     */
    fun prepare() {
        if (recognizer != null) return
        val m = model ?: return
        synchronized(lock) {
            if (recognizer != null) return
            val c = chain
            if (c != null && !c.isComplete) return
            if (BuildConfig.DEBUG) Log.d(TAG, "ensure-ready chain start")
            val t0 = System.currentTimeMillis()
            val manager = RemoteModelManager.getInstance()
            chain = manager.isModelDownloaded(m)
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
        }
    }

    /**
     * The client, waiting up to [timeoutMs] for a chain **already in flight** (or the flag shortcut);
     * null if nothing is in flight, it is not READY by then, or the chain failed. Never starts a
     * download — that is [prepare]'s job, behind the host's consent. The caller's Binder timeout is
     * the outer ceiling.
     */
    fun awaitReady(timeoutMs: Long): DigitalInkRecognizer? {
        recognizer?.let { return it }
        warmUp()
        recognizer?.let { return it }
        val c = chain ?: return null
        if (c.isComplete) return recognizer
        try {
            Tasks.await(c, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "awaitReady: ${e.javaClass.simpleName} after $timeoutMs ms")
        }
        return recognizer
    }

    /**
     * The engine failed on a call (not a timeout — the service keeps slow-but-alive out of here). If
     * the client came from the model-present shortcut the model *may* be gone: run the real
     * `isModelDownloaded` once, asynchronously, and only if it says **absent** forget the flag and drop
     * the client, so the next `status()` is `NEEDS_DOWNLOAD` and `prepare()` re-downloads. A failure
     * on a fully-checked client, or a transient failure with the model still on disk, changes nothing.
     */
    fun onEngineFailure() {
        if (!shortcut || verifying) return
        val m = model ?: return
        synchronized(lock) {
            if (!shortcut || verifying) return
            verifying = true
        }
        Log.w(TAG, "engine failure on the remembered model — verifying it is still on disk")
        RemoteModelManager.getInstance().isModelDownloaded(m)
            .addOnSuccessListener { downloaded ->
                synchronized(lock) {
                    verifying = false
                    if (downloaded == true) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "model still present — failure was transient")
                        shortcut = false   // verified now; later failures don't re-check
                    } else {
                        Log.w(TAG, "model gone — forgetting it; prepare() will re-download")
                        prefs?.edit()?.remove(KEY_PRESENT)?.apply()
                        shortcut = false
                        recognizer?.close()
                        recognizer = null
                        chain = null
                    }
                }
            }
            .addOnFailureListener { e ->
                synchronized(lock) { verifying = false }
                Log.w(TAG, "model re-check failed: ${e.javaClass.simpleName} — leaving the client in place")
            }
    }

    private fun buildClient(m: DigitalInkRecognitionModel) {
        val built: DigitalInkRecognizer
        synchronized(lock) {
            if (recognizer != null) return
            built = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
            recognizer = built
            if (BuildConfig.DEBUG) Log.d(TAG, "client built")
        }
        prime(built)
    }

    /**
     * Prime the engine (H5): ML Kit loads the model into the recognizer lazily, on the **first**
     * `recognize` — 1.5–4 s on the fleet, which the first heading of a session used to pay. One
     * throwaway inference on a synthetic dot, off the Binder thread, right after the client is built
     * (process start / consent path), so a real call that follows is warm. Result discarded, never
     * logged; a failure here is only a log line — `status()` and the real calls are unaffected.
     */
    private fun prime(r: DigitalInkRecognizer) {
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                val stroke = com.google.mlkit.vision.digitalink.recognition.Ink.Stroke.builder()
                    .addPoint(com.google.mlkit.vision.digitalink.recognition.Ink.Point.create(10f, 10f))
                    .addPoint(com.google.mlkit.vision.digitalink.recognition.Ink.Point.create(12f, 12f))
                    .build()
                val ink = com.google.mlkit.vision.digitalink.recognition.Ink.builder().addStroke(stroke).build()
                Tasks.await(r.recognize(ink), PRIME_AWAIT_MS, TimeUnit.MILLISECONDS)
                if (BuildConfig.DEBUG) Log.d(TAG, "engine primed in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                Log.w(TAG, "engine prime failed: ${e.javaClass.simpleName}")
            }
        }, "mlkit-prime").apply { isDaemon = true }.start()
    }
}
