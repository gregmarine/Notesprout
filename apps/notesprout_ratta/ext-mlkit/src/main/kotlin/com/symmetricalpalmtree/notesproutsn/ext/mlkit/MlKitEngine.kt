package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The recognition calls themselves, run synchronously on the calling (Binder) thread while ML Kit's
 * own executor does the work. Every call carries an **absolute deadline**: each individual ML Kit
 * wait is bounded by `min(CALL_AWAIT_MS, time left)`, and a page that runs out of time stops with a
 * [TimeoutException] rather than grinding on an orphaned Binder thread after the host has already
 * given up and unbound. The service turns every failure into a Binder-marshalable
 * `IllegalStateException`. Text is never logged.
 */
internal object MlKitEngine {

    private const val TAG = "MlKitEngine"

    /** The longest any single ML Kit call may be waited on — a wedged engine never pins a thread forever. */
    private const val CALL_AWAIT_MS = 10_000L

    /**
     * One writing area, no layout analysis. [deadlineMs] is an absolute `System.currentTimeMillis`
     * bound shared by every call in the same host request. [dotLineHeight] is the height [Dots]
     * scales its tiny-stroke threshold from — a **line** height, never a multi-line area's: the
     * page path passes its per-line height (the default keeps that behaviour), and the direct
     * host path derives one from the ink, because a selection spanning two written lines would
     * double the threshold and swallow real punctuation into dot circles.
     */
    fun recognizeInk(
        recognizer: DigitalInkRecognizer,
        strokes: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        preContext: String,
        deadlineMs: Long,
        dotLineHeight: Float = areaHeight,
    ): String {
        val wait = PageText.waitFor(deadlineMs, System.currentTimeMillis(), CALL_AWAIT_MS)
        if (wait <= 0L) throw TimeoutException("deadline passed before recognizeInk")

        val builder = Ink.builder()
        for (s in Dots.round(PageText.widenDots(strokes), dotLineHeight)) {
            val sb = Ink.Stroke.builder()
            for (i in 0 until s.size) sb.addPoint(Ink.Point.create(s.x[i], s.y[i]))   // no time channel travels
            builder.addStroke(sb.build())
        }
        // Floor the area: a dot-only stroke set has a 0 × 0 box and ML Kit will not take one.
        val area = WritingArea(areaWidth.coerceAtLeast(1f), areaHeight.coerceAtLeast(1f))
        val context = RecognitionContext.builder()
            .setPreContext(PageText.preContextTail(preContext))
            .setWritingArea(area)
            .build()
        val result = Tasks.await(recognizer.recognize(builder.build(), context), wait, TimeUnit.MILLISECONDS)
        return result.candidates.firstOrNull()?.text ?: ""
    }

    /**
     * A whole page: segment it into paragraphs and lines, recognize each line with the previous
     * line's tail as pre-context and the page's median line height as the writing-area height, then
     * join. [pageWidth] / [pageHeight] are part of the contract; the segmenter needs only the ink.
     *
     * **Per-line tolerance:** a line whose ML Kit call fails contributes nothing and the page carries
     * on; only when *every* attempted line failed is the first failure rethrown, so a real engine
     * failure is never served to the host as a blank page. The **deadline** is not tolerated that
     * way — running out of time aborts the whole page with [TimeoutException], because the host has
     * already given up and a warm retry (model loaded) is far faster.
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
        var preContext = ""
        var attempted = 0
        var succeeded = 0
        var firstFailure: Exception? = null

        for (paragraph in layout.paragraphs) {
            val lines = ArrayList<String>(paragraph.lines.size)
            for (line in paragraph.lines) {
                val h = if (layout.medianLineHeight > 0f) layout.medianLineHeight else line.bounds.height
                attempted++
                val text = try {
                    val raw = recognizeInk(recognizer, line.strokes, line.bounds.width, h, preContext, deadlineMs).trim()
                    // A trailing baseline dot is a period whatever ML Kit made of it (see Dots).
                    val trailingDot = Dots.endsWithBaselineDot(line.strokes, line.bounds, h)
                    if (BuildConfig.DEBUG) Log.d(TAG, Dots.describeLine(line.strokes, line.bounds, h, raw, trailingDot))
                    if (raw.isNotEmpty() && trailingDot) Dots.fixTrailingPeriod(raw) else raw
                } catch (e: TimeoutException) {
                    throw e            // out of time for the page — never a silent partial result
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (firstFailure == null) firstFailure = e
                    continue
                }
                succeeded++
                if (text.isNotEmpty()) {
                    lines += text
                    preContext = text  // line N feeds line N+1
                }
            }
            paragraphs += lines
        }

        if (attempted > 0 && succeeded == 0) firstFailure?.let { throw it }
        if (BuildConfig.DEBUG) Log.d(TAG, "page: $attempted line(s), $succeeded recognized")
        return PageText.join(paragraphs)
    }
}
