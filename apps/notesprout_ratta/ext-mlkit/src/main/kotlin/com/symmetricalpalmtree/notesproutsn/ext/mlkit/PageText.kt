package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke

/**
 * The pure text and budget helpers of the page pipeline — no ML Kit, no Android, JVM-tested. Kept
 * apart from [MlKitEngine] so the parts that decide *what* the page becomes can be pinned by tests
 * without an engine.
 */
internal object PageText {

    /** The tail of the previous line ML Kit may see as pre-context (`MAX_PRECONTEXT_CHARS`). */
    fun preContextTail(previous: String): String = previous.takeLast(ExtensionContract.MAX_PRECONTEXT_CHARS)

    /**
     * Lines joined by `\n`, paragraphs by a blank line. A paragraph whose every line recognized to
     * `""` contributes nothing at all — no placeholder, no stray blank line. `""` when nothing was
     * recognized anywhere.
     */
    fun join(paragraphs: List<List<String>>): String =
        paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString("\n") }

    /**
     * How long one ML Kit call may wait: the smaller of [perCallMs] and the time left until the
     * absolute [deadlineMs]. Zero or negative means the budget is spent — the caller must not start.
     */
    fun waitFor(deadlineMs: Long, nowMs: Long, perCallMs: Long): Long = minOf(perCallMs, deadlineMs - nowMs)

    /**
     * A single-point stroke — a pen tap, which on paper is a visible dot: a period, an i-dot —
     * becomes a degenerate two-point stroke, so neither the segmenter (which needs ≥ 2 points) nor
     * ML Kit drops it. Longer strokes pass through by identity, and a list with no taps in it is
     * returned unchanged.
     */
    fun widenDots(strokes: List<InkStroke>): List<InkStroke> {
        if (strokes.none { it.size == 1 }) return strokes
        return strokes.map { s ->
            if (s.size == 1) InkStroke(floatArrayOf(s.x[0], s.x[0]), floatArrayOf(s.y[0], s.y[0])) else s
        }
    }
}
