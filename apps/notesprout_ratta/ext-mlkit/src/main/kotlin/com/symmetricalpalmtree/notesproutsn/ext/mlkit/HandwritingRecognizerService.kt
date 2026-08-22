package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IHandwritingRecognizer
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import java.util.concurrent.TimeoutException

/**
 * Notesprout SN's `HANDWRITING_RECOGNIZER` point, implemented with Google ML Kit Digital Ink
 * Recognition (`en-US`). Bound by the SN core, never launched by a user (this APK has no Activity).
 * Every stub method proves the caller is the host before anything else. The calls themselves are
 * stateless; the model and the client are process-lifetime and belong to [ModelManager] — a
 * remembered model is built in [onCreate], i.e. on the host's first bind, while the ensure-ready
 * chain (check → download → build) starts **only** on `prepare()`. The recognize calls **wait** for
 * a chain already in flight rather than failing while the model is seconds away, but they never
 * start a download.
 *
 * AIDL methods arrive on Binder threads and the recognition runs synchronously on them, under a
 * whole-call budget sized just below the host's own timeout (`*_BUDGET_MS` against the host's 10 s
 * ink / 30 s page): the extension stops at its own deadline instead of grinding on after the host
 * has given up and unbound.
 *
 * **Only Binder-marshalable exceptions may leave a stub** — `SecurityException` (not the host),
 * `IllegalArgumentException` (over the `MAX_INK_*` caps or malformed ink), `IllegalStateException`
 * (not ready, or an engine failure). Anything else kills the transaction silently and the host sees
 * a dead call with no reason. Logs carry counts and durations only, never recognized text.
 */
class HandwritingRecognizerService : Service() {

    private val binder = object : IHandwritingRecognizer.Stub() {

        override fun status(): Int {
            enforce()
            return ModelManager.status()
        }

        override fun prepare() {
            enforce()
            ModelManager.prepare()
        }

        override fun recognizeInk(
            strokes: List<InkStroke>?,
            areaWidth: Float,
            areaHeight: Float,
            preContext: String?,
        ): String {
            enforce()
            val t0 = System.currentTimeMillis()
            val deadline = t0 + INK_BUDGET_MS
            val ink = checkInk(strokes)
            require(areaWidth > 0f && areaHeight > 0f) { "non-positive writing area" }
            val recognizer = ready(INK_READY_WAIT_MS)
            // The host's area is the whole selection box, which can span more than one written
            // line — Dots must scale its tiny-stroke threshold from a LINE height, so derive one
            // from the ink itself (the same segmenter the page path trusts).
            val layout = StrokeSegmenter.segment(ink)
            val dotLineHeight = if (layout.medianLineHeight > 0f) layout.medianLineHeight else areaHeight
            val text = engine {
                MlKitEngine.recognizeInk(recognizer, ink, areaWidth, areaHeight, preContext ?: "", deadline, dotLineHeight)
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "recognizeInk: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            }
            return text
        }

        override fun recognizePage(strokes: List<InkStroke>?, pageWidth: Float, pageHeight: Float): String {
            enforce()
            val t0 = System.currentTimeMillis()
            val deadline = t0 + PAGE_BUDGET_MS
            val ink = checkInk(strokes)
            require(pageWidth > 0f && pageHeight > 0f) { "non-positive page size" }
            val recognizer = ready(PAGE_READY_WAIT_MS)
            val text = engine {
                MlKitEngine.recognizePage(recognizer, ink, pageWidth, pageHeight, deadline)
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "recognizePage: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            }
            return text
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    /**
     * Re-check the caps the host already enforced outbound — belt and braces, because trusting the
     * caller's arithmetic is how an extension gets wedged. Violations are `IllegalArgumentException`.
     */
    private fun checkInk(strokes: List<InkStroke>?): List<InkStroke> {
        requireNotNull(strokes) { "strokes is null" }
        require(strokes.size <= ExtensionContract.MAX_INK_STROKES) { "too many strokes" }
        var points = 0L
        for (s in strokes) {
            requireNotNull(s) { "null stroke" }
            // InkStroke's own `require`s (equal-length, non-empty arrays) already ran at unmarshal time.
            points += s.size
        }
        require(points <= ExtensionContract.MAX_INK_POINTS) { "too many points" }
        return strokes
    }

    /**
     * The client, waiting up to [waitMs] for an ensure-ready chain already in flight — never starting
     * a download. Not ready by then → `IllegalStateException` carrying exactly
     * [ExtensionContract.RECOGNIZER_NOT_READY], the one message the host types as "still downloading".
     */
    private fun ready(waitMs: Long) =
        ModelManager.awaitReady(waitMs) ?: throw IllegalStateException(ExtensionContract.RECOGNIZER_NOT_READY)

    /**
     * Run an engine call and reduce every failure to a marshalable `IllegalStateException`. A timeout
     * or an interrupt is slow-but-alive and must **not** count as an engine failure — it would
     * otherwise make a perfectly good model look "gone". Everything else tells [ModelManager] so a
     * vanished model can be re-acquired.
     */
    private inline fun engine(block: () -> String): String =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: TimeoutException) {
            Log.w(TAG, "recognition timed out")
            throw IllegalStateException("recognition timed out")
        } catch (e: InterruptedException) {
            throw IllegalStateException("recognition interrupted")
        } catch (e: Exception) {
            Log.w(TAG, "engine failure: ${e.javaClass.simpleName}")
            ModelManager.onEngineFailure()
            throw IllegalStateException("recognition failed: ${e.javaClass.simpleName}")
        }

    /**
     * Consent-safe start-up: a remembered model builds its client the moment the host first binds
     * (which is what the notebook's open-time warm-up bind is for), and nothing else — no check, no
     * download — happens until the host calls `prepare()`.
     */
    override fun onCreate() {
        super.onCreate()
        ModelManager.init(this)
        ModelManager.warmUp()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "HandwritingRecognizerService"

        /** Readiness waits, sized under the host's per-call timeouts (10 s ink · 30 s page) so the
         *  recognition itself still has room after the wait. */
        const val INK_READY_WAIT_MS = 6_000L
        const val PAGE_READY_WAIT_MS = 22_000L

        /** Whole-call budgets (the readiness wait plus every ML Kit call), just under those timeouts. */
        const val INK_BUDGET_MS = 9_500L
        const val PAGE_BUDGET_MS = 28_000L
    }
}
