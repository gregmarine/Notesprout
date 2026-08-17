package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import com.symmetricalpalmtree.notesprout.core.Slog

/** The extension could not become READY within the call (still downloading / loading, or the engine failed). */
class RecognizerNotReadyException(cause: Throwable) : ExtensionCallException("recognizer not ready", cause)

/**
 * Bind-per-operation client for the one handwriting recognizer, over the shared [ExtensionBinder]
 * (signature re-check at bind, bind ≤ 3 s, call on IO under a per-method timeout, unbind in
 * `finally`, every failure → one [ExtensionCallException]). Stateless point — no store.
 *
 * **Outward caps run before the bind** ([InkCaps]): over `MAX_INK_STROKES` / `MAX_INK_POINTS`,
 * malformed strokes, non-positive sizes → [InkTooLargeException] without a bind; `preContext` is cut
 * to its last `MAX_PRECONTEXT_CHARS`. **Inward is untrusted**: a status outside `0..3` is
 * `UNAVAILABLE`, text is `?: ""` and truncated to `MAX_RECOGNIZED_CHARS`; the extension's
 * `IllegalStateException` (could not become ready within the call, or the engine failed) surfaces as
 * [RecognizerNotReadyException]. The recognize calls **wait for readiness inside the extension**
 * (M1) — a caller may go straight to `recognize*` after `status()` says DOWNLOADING.
 *
 * Logs (tag [TAG]): bind/unbind, counts and durations — **never text**.
 */
class RecognizerClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** One of `RecognizerStatus.*`; anything unexpected is `UNAVAILABLE`. */
    suspend fun status(): Int = call(STATUS_TIMEOUT_MS) { InkCaps.status(it.status()) }

    /** Ask the extension to acquire what it needs (model download). Returns at once. */
    suspend fun prepare() = call(STATUS_TIMEOUT_MS) { it.prepare() }

    /** Recognize one writing area; the top candidate or "". */
    suspend fun recognizeInk(strokes: List<InkStroke>, areaWidth: Float, areaHeight: Float, preContext: String): String {
        InkCaps.check(strokes, areaWidth, areaHeight)
        val pre = InkCaps.preContext(preContext)
        val t0 = System.currentTimeMillis()
        val text = call(INK_TIMEOUT_MS) { InkCaps.text(it.recognizeInk(strokes, areaWidth, areaHeight, pre)) }
        Slog.d(TAG) { "recognizeInk: ${strokes.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms" }
        return text
    }

    /** Recognize a whole page (the extension segments); lines joined by '\n', paragraphs by a blank line, or "". */
    suspend fun recognizePage(strokes: List<InkStroke>, pageWidth: Float, pageHeight: Float): String {
        InkCaps.check(strokes, pageWidth, pageHeight)
        val t0 = System.currentTimeMillis()
        val text = call(PAGE_TIMEOUT_MS) { InkCaps.text(it.recognizePage(strokes, pageWidth, pageHeight)) }
        Slog.d(TAG) { "recognizePage: ${strokes.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms" }
        return text
    }

    private suspend fun <T> call(timeoutMs: Long, block: (IHandwritingRecognizer) -> T): T =
        ExtensionBinder.call(
            appContext, ref, ExtensionContract.ACTION_HANDWRITING_RECOGNIZER, TAG,
            asInterface = { IHandwritingRecognizer.Stub.asInterface(it) },
            callTimeoutMs = timeoutMs,
        ) { recognizer ->
            try {
                block(recognizer)
            } catch (e: IllegalStateException) {
                // Binder-marshalable by contract: "recognizer not ready" (could not become ready within the
                // call) is typed so the caller can say "still downloading"; any other engine failure is generic.
                if (e.message?.contains("not ready") == true) throw RecognizerNotReadyException(e)
                throw ExtensionCallException("${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

    companion object {
        private const val TAG = "RecognizerClient"
        const val BIND_TIMEOUT_MS = ExtensionBinder.BIND_TIMEOUT_MS
        const val STATUS_TIMEOUT_MS = 2_000L
        const val INK_TIMEOUT_MS = 10_000L
        /** One ML Kit call per line; the first call after process start also loads the model. */
        const val PAGE_TIMEOUT_MS = 30_000L
    }
}
