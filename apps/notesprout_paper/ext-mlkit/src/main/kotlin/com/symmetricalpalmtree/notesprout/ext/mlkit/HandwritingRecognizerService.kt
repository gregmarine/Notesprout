package com.symmetricalpalmtree.notesprout.ext.mlkit

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IHandwritingRecognizer
import com.symmetricalpalmtree.notesprout.extension.InkStroke

/**
 * The HANDWRITING_RECOGNIZER extension point, implemented with Google ML Kit Digital Ink
 * Recognition (`en-US`). Bound by the Notesprout Paper core; never launched by a user (no
 * Activity). Every method first proves the caller is the host. Stateless per call; the model and
 * client are process-lifetime ([ModelManager]). AIDL methods run on Binder threads and the
 * recognition runs synchronously on them — the host's timeout is the ceiling.
 *
 * Exceptions that cross Binder intact are the only ones thrown: `SecurityException` (not the
 * host), `IllegalArgumentException` (over the `MAX_INK_*` caps / malformed ink),
 * `IllegalStateException` (not READY, or any engine failure). Anything else would kill the
 * transaction silently (the arc-2 lesson). Logs: counts and durations only — never text.
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
            strokes: List<InkStroke>?, areaWidth: Float, areaHeight: Float, preContext: String?,
        ): String {
            enforce()
            val ink = checkInk(strokes)
            require(areaWidth > 0f && areaHeight > 0f) { "non-positive writing area" }
            val recognizer = ready()
            val t0 = System.currentTimeMillis()
            val text = engine { MlKitEngine.recognizeInk(recognizer, ink, areaWidth, areaHeight, preContext ?: "") }
            if (BuildConfig.DEBUG) Log.d(TAG, "recognizeInk: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            return text
        }

        override fun recognizePage(strokes: List<InkStroke>?, pageWidth: Float, pageHeight: Float): String {
            enforce()
            val ink = checkInk(strokes)
            require(pageWidth > 0f && pageHeight > 0f) { "non-positive page size" }
            val recognizer = ready()
            val t0 = System.currentTimeMillis()
            val text = engine { MlKitEngine.recognizePage(recognizer, ink, pageWidth, pageHeight) }
            if (BuildConfig.DEBUG) Log.d(TAG, "recognizePage: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            return text
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    /** Re-check the host's caps (belt-and-braces) — `IllegalArgumentException` on violation. */
    private fun checkInk(strokes: List<InkStroke>?): List<InkStroke> {
        requireNotNull(strokes) { "strokes is null" }
        require(strokes.size <= ExtensionContract.MAX_INK_STROKES) { "too many strokes" }
        var points = 0L
        for (s in strokes) {
            requireNotNull(s) { "null stroke" }
            // InkStroke's own `require`s (equal, non-empty arrays) already ran at unmarshal time.
            points += s.size
        }
        require(points <= ExtensionContract.MAX_INK_POINTS) { "too many points" }
        return strokes
    }

    private fun ready() = ModelManager.recognizer()
        ?: throw IllegalStateException("recognizer not ready")

    /** Runs an engine call; any failure becomes `IllegalStateException` (carried across Binder). */
    private inline fun engine(block: () -> String): String =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "engine failure: ${e.javaClass.simpleName}")
            throw IllegalStateException("recognition failed: ${e.javaClass.simpleName}")
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "HandwritingRecognizerService"
    }
}
