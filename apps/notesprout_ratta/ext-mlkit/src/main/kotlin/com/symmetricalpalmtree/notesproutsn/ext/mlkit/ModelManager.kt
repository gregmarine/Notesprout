package com.symmetricalpalmtree.notesproutsn.ext.mlkit

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
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerStatus
import java.util.concurrent.TimeUnit

/**
 * Process-lifetime owner of the `en-US` model and the ML Kit client. The model itself lives in this
 * extension's own app storage, managed by ML Kit — an engine asset is not user data, and
 * uninstalling the extension takes it with it.
 *
 * Readiness is **one async chain**: `isModelDownloaded` → `download` (only when needed) → build the
 * client. **Only [prepare] may start it.** The host asks the user before a ~20 MB download, so
 * nothing may begin before that consent — which is also why the notebook's warm-up at open can only
 * ever *use* a model that is already there. The chain runs on ML Kit's executor, is idempotent while
 * in flight, and is restartable after a failure.
 *
 * **[status] never waits on ML Kit.** A cold `isModelDownloaded` in a fresh process takes tens of
 * seconds on a Nomad, so a synchronous check would time out and be misreported as `UNAVAILABLE`.
 * [awaitReady] is what the recognize calls use: it waits for a chain **already in flight**, inside
 * the caller's timeout, rather than answering "not ready" while the model is seconds away — but it
 * never starts one.
 *
 * **Model-present memory:** once the chain has seen the model on disk, a flag is kept in this
 * extension's own `SharedPreferences`. A fresh process with the flag set builds the client
 * immediately in [warmUp] (the service's `onCreate`) — READY with no ML Kit check at all. Without
 * the flag the process starts nothing and reports `NEEDS_DOWNLOAD`: the host asks, [prepare] runs
 * the real chain, and if the model turns out to be on disk already the chain finds it without
 * downloading. If it is really gone — an engine failure on a shortcut-built client **confirmed** by
 * a real `isModelDownloaded == false`, because a merely slow first inference is not "gone" — the
 * flag is cleared and the client dropped, so the next [status] is `NEEDS_DOWNLOAD` again.
 *
 * Every method here is called from Binder threads (never main), so `Tasks.await` is legal.
 * Logs carry class names, counts and durations — never text.
 */
internal object ModelManager {

    private const val TAG = "ModelManager"
    private const val LANGUAGE_TAG = "en-US"
    private const val PREFS = "model"
    private const val KEY_PRESENT = "present:$LANGUAGE_TAG"

    /** How long the throwaway priming inference is waited on before it is abandoned (its thread is a daemon). */
    private const val PRIME_AWAIT_MS = 30_000L

    private val lock = Any()

    @Volatile private var prefs: SharedPreferences? = null

    /** The client; non-null ⇔ READY. */
    @Volatile private var recognizer: DigitalInkRecognizer? = null

    /** The ensure-ready chain, in flight or last finished. */
    @Volatile private var chain: Task<Void>? = null

    /** True while the current client was built from the flag alone, with no ML Kit check this process. */
    @Volatile private var shortcut = false

    /** True while an engine-failure-triggered `isModelDownloaded` re-check is in flight. */
    @Volatile private var verifying = false

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

    /** Wires the model-present memory; called once from the service's `onCreate`. */
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** One of [RecognizerStatus]. Never blocks on ML Kit. */
    fun status(): Int {
        if (recognizer != null) return RecognizerStatus.READY
        if (model == null) return RecognizerStatus.UNAVAILABLE
        val c = chain
        return if (c != null && !c.isComplete) RecognizerStatus.DOWNLOADING else RecognizerStatus.NEEDS_DOWNLOAD
    }

    /**
     * The startup warm-up (service `onCreate`, i.e. the host's first bind): with the model
     * remembered as present, build the client now — READY at once, no ML Kit check. Otherwise do
     * **nothing**: no check, no download, until the host calls [prepare].
     */
    fun warmUp() {
        if (recognizer != null) return
        val m = model ?: return
        synchronized(lock) {
            if (recognizer != null) return
            if (prefs?.getBoolean(KEY_PRESENT, false) != true) return
            if (BuildConfig.DEBUG) Log.d(TAG, "model remembered as present — building the client directly")
            shortcut = true
            // Client first, then the completed chain: never leave a window where `recognizer` is
            // still null while `chain` already says "finished" — that reads as NEEDS_DOWNLOAD.
            buildClient(m)
            chain = Tasks.forResult<Void>(null)
        }
    }

    /**
     * Start — or restart after a failure — the ensure-ready chain and return at once. Idempotent: a
     * no-op while READY or while a chain is in flight. Success means READY and the model-present
     * flag; a failure logs the exception class and leaves the state retryable (`NEEDS_DOWNLOAD` on
     * the next [status]). **This is the only method that may start a download.**
     */
    fun prepare() {
        if (recognizer != null) return
        val m = model ?: return
        synchronized(lock) {
            if (recognizer != null) return
            val inFlight = chain
            if (inFlight != null && !inFlight.isComplete) return
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
     * The client, waiting up to [timeoutMs] for a chain **already in flight** (or for the flag
     * shortcut); null when nothing is in flight, when it is not READY by then, or when the chain
     * failed. Never starts a download — that is [prepare]'s job, behind the host's consent. The
     * caller's Binder timeout is the outer ceiling.
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
     * The engine failed on a real call — not a timeout; the service keeps slow-but-alive out of here.
     * If this client came from the model-present shortcut the model *may* actually be gone: run the
     * real `isModelDownloaded` once, asynchronously, and only when it says **absent** forget the flag
     * and drop the client, so the next [status] is `NEEDS_DOWNLOAD` and [prepare] re-downloads. A
     * failure on a fully-checked client, or a transient failure with the model still on disk, changes
     * nothing.
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "model still present — the failure was transient")
                        shortcut = false   // verified now; later failures need no re-check
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
     * Prime the engine. ML Kit loads the model into the recognizer lazily, on the **first**
     * `recognize` — 1.5–4 s on this fleet, which the first real call of a session would otherwise
     * pay. One throwaway inference on a synthetic two-point stroke, on a daemon thread off the Binder
     * thread, right after the client is built, leaves the engine warm for the call that matters. The
     * result is discarded and never logged; a failure here is one log line — [status] and the real
     * calls are unaffected.
     */
    private fun prime(r: DigitalInkRecognizer) {
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                val stroke = Ink.Stroke.builder()
                    .addPoint(Ink.Point.create(10f, 10f))
                    .addPoint(Ink.Point.create(12f, 12f))
                    .build()
                val ink = Ink.builder().addStroke(stroke).build()
                Tasks.await(r.recognize(ink), PRIME_AWAIT_MS, TimeUnit.MILLISECONDS)
                if (BuildConfig.DEBUG) Log.d(TAG, "engine primed in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                Log.w(TAG, "engine prime failed: ${e.javaClass.simpleName}")
            }
        }, "mlkit-prime").apply { isDaemon = true }.start()
    }
}
