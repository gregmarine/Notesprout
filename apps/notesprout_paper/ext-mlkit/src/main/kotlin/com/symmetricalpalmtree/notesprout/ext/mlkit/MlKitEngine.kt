package com.symmetricalpalmtree.notesprout.ext.mlkit

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.InkStroke
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The recognition calls, run synchronously on the calling (Binder) thread — the host's call timeout
 * is the ceiling; ML Kit's own executor does the work. Every call carries an absolute **deadline**
 * (M2): each ML Kit wait is bounded by `min(CALL_AWAIT_MS, time left)` and a page call stops at the
 * deadline with a [TimeoutException] instead of grinding on an orphaned Binder thread after the host
 * has given up. Every failure surfaces as `IllegalStateException` (Binder-marshalable) from the
 * service. Never logs text.
 */
internal object MlKitEngine {

    private const val TAG = "MlKitEngine"
    /** Per-ML-Kit-call wait; keeps a wedged engine from pinning a Binder thread forever. */
    private const val CALL_AWAIT_MS = 10_000L

    /** One writing area, no layout analysis; [deadlineMs] is an absolute `currentTimeMillis` bound. */
    fun recognizeInk(
        recognizer: DigitalInkRecognizer,
        strokes: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        preContext: String,
        deadlineMs: Long,
    ): String {
        val wait = PageText.waitFor(deadlineMs, System.currentTimeMillis(), CALL_AWAIT_MS)
        if (wait <= 0L) throw TimeoutException("deadline passed before recognizeInk")
        val inkBuilder = Ink.builder()
        for (s in Dots.round(PageText.widenDots(strokes), areaHeight)) {
            val sb = Ink.Stroke.builder()
            for (i in 0 until s.size) sb.addPoint(Ink.Point.create(s.x[i], s.y[i]))   // no time channel
            inkBuilder.addStroke(sb.build())
        }
        // Floor the area — a dot-only stroke set has a 0×0 box.
        val area = WritingArea(areaWidth.coerceAtLeast(1f), areaHeight.coerceAtLeast(1f))
        val ctx = RecognitionContext.builder()
            .setPreContext(PageText.preContextTail(preContext))
            .setWritingArea(area)
            .build()
        val result = Tasks.await(recognizer.recognize(inkBuilder.build(), ctx), wait, TimeUnit.MILLISECONDS)
        return result.candidates.firstOrNull()?.text ?: ""
    }

    /**
     * Whole page: segment into paragraphs / lines, recognize each line with the previous line's tail
     * as pre-context and the page's median line height as the writing-area height, join.
     * [pageWidth]/[pageHeight] are accepted for the contract; the segmenter needs only the ink.
     *
     * Per-line tolerance (M2, as the original `PageTextRecognizer`): a line whose ML Kit call fails
     * contributes nothing and the page goes on; only if **every** line failed is the first failure
     * rethrown (a real failure is never mistaken for a blank page). The **deadline** is not tolerated
     * per line — running out of time aborts the page with [TimeoutException] (the host has already
     * given up; a warm retry has the model loaded and is much faster).
     */
    fun recognizePage(
        recognizer: DigitalInkRecognizer,
        strokes: List<InkStroke>,
        @Suppress("UNUSED_PARAMETER") pageWidth: Float,
        @Suppress("UNUSED_PARAMETER") pageHeight: Float,
        deadlineMs: Long,
    ): String {
        val layout = StrokeSegmenter.segment(PageText.widenDots(strokes))
        val paragraphs = ArrayList<List<String>>(layout.paragraphs.size)
        var pre = ""
        var attempted = 0
        var succeeded = 0
        var firstFailure: Exception? = null
        for (para in layout.paragraphs) {
            val lines = ArrayList<String>(para.lines.size)
            for (line in para.lines) {
                val h = if (layout.medianLineHeight > 0f) layout.medianLineHeight else line.bounds.height
                attempted++
                val t = try {
                    val raw = recognizeInk(recognizer, line.strokes, line.bounds.width, h, pre, deadlineMs).trim()
                    // A trailing baseline dot is a period whatever ML Kit made of it (see Dots).
                    val trailingDot = Dots.endsWithBaselineDot(line.strokes, line.bounds, h)
                    if (BuildConfig.DEBUG) Log.d(TAG, Dots.describeLine(line.strokes, line.bounds, h, raw, trailingDot))
                    if (raw.isNotEmpty() && trailingDot) Dots.fixTrailingPeriod(raw) else raw
                } catch (e: TimeoutException) {
                    throw e   // out of time for the whole page — never a silent partial result
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (firstFailure == null) firstFailure = e
                    continue
                }
                succeeded++
                if (t.isNotEmpty()) {
                    lines += t
                    pre = t   // feed line N into line N+1
                }
            }
            paragraphs += lines
        }
        if (attempted > 0 && succeeded == 0) firstFailure?.let { throw it }
        return PageText.join(paragraphs)
    }
}

/** Pure text / budget helpers of the page pipeline (JVM-tested). */
internal object PageText {

    /** The tail of [previous] ML Kit should see as pre-context (`MAX_PRECONTEXT_CHARS`). */
    fun preContextTail(previous: String): String = previous.takeLast(ExtensionContract.MAX_PRECONTEXT_CHARS)

    /**
     * Lines joined by `\n`, paragraphs by a blank line; a paragraph whose every line recognized to
     * `""` contributes nothing (no placeholder). `""` when nothing was recognized.
     */
    fun join(paragraphs: List<List<String>>): String =
        paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString("\n") }

    /** How long one ML Kit call may wait: the smaller of [perCallMs] and the time left to [deadlineMs] (≤ 0 = none). */
    fun waitFor(deadlineMs: Long, nowMs: Long, perCallMs: Long): Long = minOf(perCallMs, deadlineMs - nowMs)

    /**
     * A single-point stroke (a pen tap — a visible dot on the paper: a period, an i-dot) becomes a
     * degenerate two-point stroke so the segmenter (which needs ≥ 2 points, verbatim from the
     * original) and ML Kit both see it instead of dropping it (M2). Longer strokes pass through.
     */
    fun widenDots(strokes: List<InkStroke>): List<InkStroke> {
        if (strokes.none { it.size == 1 }) return strokes
        return strokes.map { s ->
            if (s.size == 1) InkStroke(floatArrayOf(s.x[0], s.x[0]), floatArrayOf(s.y[0], s.y[0])) else s
        }
    }
}
