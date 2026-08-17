package com.symmetricalpalmtree.notesprout.ext.mlkit

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.InkStroke
import java.util.concurrent.TimeUnit

/**
 * The recognition calls, run synchronously on the calling (Binder) thread — the host's call timeout
 * is the ceiling; ML Kit's own executor does the work. Every failure surfaces as
 * `IllegalStateException` (Binder-marshalable) from the service. Never logs text.
 */
internal object MlKitEngine {

    /** Per-ML-Kit-call wait; keeps a wedged engine from pinning a Binder thread forever. Sized to the
     *  host's `recognizeInk` timeout — a page call chains several of these under its 30 s ceiling. */
    private const val CALL_AWAIT_MS = 10_000L

    /** One writing area, no layout analysis. */
    fun recognizeInk(
        recognizer: DigitalInkRecognizer,
        strokes: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        preContext: String,
    ): String {
        val inkBuilder = Ink.builder()
        for (s in strokes) {
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
        val result = Tasks.await(recognizer.recognize(inkBuilder.build(), ctx), CALL_AWAIT_MS, TimeUnit.MILLISECONDS)
        return result.candidates.firstOrNull()?.text ?: ""
    }

    /**
     * Whole page: segment into paragraphs / lines, recognize each line with the previous line's tail
     * as pre-context and the page's median line height as the writing-area height, join.
     * [pageWidth]/[pageHeight] are accepted for the contract; the segmenter needs only the ink.
     */
    fun recognizePage(
        recognizer: DigitalInkRecognizer,
        strokes: List<InkStroke>,
        @Suppress("UNUSED_PARAMETER") pageWidth: Float,
        @Suppress("UNUSED_PARAMETER") pageHeight: Float,
    ): String {
        val layout = StrokeSegmenter.segment(strokes)
        val paragraphs = ArrayList<List<String>>(layout.paragraphs.size)
        var pre = ""
        for (para in layout.paragraphs) {
            val lines = ArrayList<String>(para.lines.size)
            for (line in para.lines) {
                val h = if (layout.medianLineHeight > 0f) layout.medianLineHeight else line.bounds.height
                val t = recognizeInk(recognizer, line.strokes, line.bounds.width, h, pre).trim()
                if (t.isNotEmpty()) {
                    lines += t
                    pre = t   // feed line N into line N+1
                }
            }
            paragraphs += lines
        }
        return PageText.join(paragraphs)
    }
}

/** Pure text helpers of the page pipeline (JVM-tested). */
internal object PageText {

    /** The tail of [previous] ML Kit should see as pre-context (`MAX_PRECONTEXT_CHARS`). */
    fun preContextTail(previous: String): String = previous.takeLast(ExtensionContract.MAX_PRECONTEXT_CHARS)

    /**
     * Lines joined by `\n`, paragraphs by a blank line; a paragraph whose every line recognized to
     * `""` contributes nothing (no placeholder). `""` when nothing was recognized.
     */
    fun join(paragraphs: List<List<String>>): String =
        paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString("\n") }
}
