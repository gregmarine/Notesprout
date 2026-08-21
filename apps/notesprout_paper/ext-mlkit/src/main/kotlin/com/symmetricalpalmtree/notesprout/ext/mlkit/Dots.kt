package com.symmetricalpalmtree.notesprout.ext.mlkit

import com.symmetricalpalmtree.notesprout.extension.InkStroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dot handling around ML Kit (M2 addendum, user-observed on every device incl. the original app):
 * ML Kit tends to **skip** a period or read it as a **comma**. A pen-tap period on e-ink is a 1–4
 * point stroke with a couple of px of drag in the lift direction — a shape the model half-reads as
 * a comma tail, or ignores as zero-length ink. Two pure, geometry-only helpers:
 *
 * 1. [round] — every **tiny** stroke (bounding box ≤ [TINY_FRAC] × line height in both directions)
 *    is replaced by a small circular dot at its centre, so ML Kit sees an unambiguous period /
 *    i-dot instead of a tick. Commas, apostrophes, hyphens are taller or wider than the threshold and
 *    pass through untouched.
 * 2. [fixTrailingPeriod] + [endsWithBaselineDot] — if the line's **right-most** stroke is a tiny
 *    dot sitting in the line's baseline zone (below [BASELINE_FRAC] of the line band — an i-dot
 *    sits at the top and is excluded), the recognized text should end in a period: a trailing `,`
 *    becomes `.` and a missing terminator gets one. Text already ending in `. ! ? : ; …` is left alone.
 *    A **shaky period** (measured on the Nomad: 3 × 10–15 px on a 62 px line — not tiny) also
 *    counts when it is *small* (≤ [TINY_FRAC] wide, ≤ [SHAKY_FRAC] tall — the same writer's real
 *    commas were all taller or wider than that), its centre sits in the bottom [SHAKY_ZONE_FRAC] of
 *    the band, and **no tiny stroke sits above it** — the one other small thing that ends a word is an
 *    i-stem (4 × 16 px on the same page), and an i-stem always has its dot.
 *
 * Nothing here reads the text beyond its last character; nothing is logged but counts.
 */
internal object Dots {

    /** A stroke is "tiny" when both its width and height are ≤ this fraction of the line height. */
    const val TINY_FRAC = 0.15f
    /** Baseline zone = the part of the line band below this fraction of its height. */
    const val BASELINE_FRAC = 0.45f
    /** Radius of the round dot [round] draws, as a fraction of the line height (floored at 0.75 px). */
    const val ROUND_RADIUS_FRAC = 0.03f
    /** A trailing dot may start this far (× line height) left of the last letter's right edge (slant). */
    const val OVERLAP_FRAC = 0.15f
    /** A shaky period may be this tall (× line height); the same writer's commas exceed it (or are wider than tiny). */
    const val SHAKY_FRAC = 0.3f
    /** A shaky period's centre must sit in the bottom this-fraction of the band (an i-stem's centre is higher). */
    const val SHAKY_ZONE_FRAC = 0.3f
    private const val ROUND_POINTS = 12

    fun isTiny(box: Box, lineHeight: Float): Boolean {
        val lim = TINY_FRAC * lineHeight
        return box.width <= lim && box.height <= lim
    }

    /** [strokes] with every tiny stroke replaced by a [ROUND_POINTS]-point circle at its centre. */
    fun round(strokes: List<InkStroke>, lineHeight: Float): List<InkStroke> {
        if (lineHeight <= 0f) return strokes
        var any = false
        val out = ArrayList<InkStroke>(strokes.size)
        for (s in strokes) {
            val box = Box.of(s.x, s.y)
            if (!isTiny(box, lineHeight)) { out += s; continue }
            any = true
            val cx = (box.left + box.right) / 2f
            val cy = box.centerY
            val r = (ROUND_RADIUS_FRAC * lineHeight).coerceAtLeast(0.75f)
            val n = ROUND_POINTS + 1   // closed
            val x = FloatArray(n)
            val y = FloatArray(n)
            for (i in 0 until n) {
                val a = (2.0 * Math.PI * i / ROUND_POINTS)
                x[i] = (cx + r * cos(a)).toFloat()
                y[i] = (cy + r * sin(a)).toFloat()
            }
            out += InkStroke(x, y)
        }
        return if (any) out else strokes
    }

    /** Narrow and at most comma-height — a candidate shaky period (includes every tiny stroke). */
    fun isSmall(box: Box, lineHeight: Float): Boolean =
        box.width <= TINY_FRAC * lineHeight && box.height <= SHAKY_FRAC * lineHeight

    /**
     * True when the right-most stroke of the line (by centre x) is a period-shaped mark: **tiny**
     * anywhere in the baseline zone of [lineBounds] (below [BASELINE_FRAC]), or **small** (narrow,
     * up to [SHAKY_FRAC] tall) with its centre in the bottom [SHAKY_ZONE_FRAC] of the band and no tiny
     * stroke above it (that would make it an i-stem). It must also start no further than
     * [OVERLAP_FRAC] × line height left of the right edge of every non-small stroke — i.e. it follows
     * the last letter rather than dotting an "i" inside it.
     */
    fun endsWithBaselineDot(strokes: List<InkStroke>, lineBounds: Box, lineHeight: Float): Boolean {
        if (strokes.size < 2 || lineHeight <= 0f) return false
        var last: Box? = null
        var lastCx = Float.NEGATIVE_INFINITY
        var lettersRight = Float.NEGATIVE_INFINITY
        var letters = 0
        val boxes = strokes.map { Box.of(it.x, it.y) }
        for (b in boxes) {
            val cx = (b.left + b.right) / 2f
            if (cx > lastCx) { lastCx = cx; last = b }
            if (!isSmall(b, lineHeight)) { lettersRight = maxOf(lettersRight, b.right); letters++ }
        }
        val dot = last ?: return false
        if (!isSmall(dot, lineHeight)) return false
        if (letters == 0) return false   // a line of only dots — nothing to end
        if (dot.left < lettersRight - OVERLAP_FRAC * lineHeight) return false
        val zoneTop = lineBounds.top + BASELINE_FRAC * lineBounds.height
        if (dot.centerY < zoneTop) return false
        if (isTiny(dot, lineHeight)) return true
        // Shaky period vs i-stem: low in the band, and nothing dotted above it.
        if (dot.centerY < lineBounds.top + (1f - SHAKY_ZONE_FRAC) * lineBounds.height) return false
        for (b in boxes) {
            if (b === dot) continue
            if (isTiny(b, lineHeight) && b.centerY < dot.top && b.right >= dot.left && b.left <= dot.right) return false
        }
        return true
    }

    /**
     * Debug-only geometry line for one recognized line — **no text**: stroke count, line height, the
     * right-most stroke's box (w×h) and its centre's position in the band (0 = top, 1 = bottom), how
     * many strokes are tiny, the class of the text's last char (period / comma / other / none) and
     * whether the trailing-dot rule fired.
     */
    fun describeLine(strokes: List<InkStroke>, lineBounds: Box, lineHeight: Float, text: String, trailingDot: Boolean): String {
        var last: Box? = null
        var lastCx = Float.NEGATIVE_INFINITY
        var tiny = 0
        for (s in strokes) {
            val b = Box.of(s.x, s.y)
            val cx = (b.left + b.right) / 2f
            if (cx > lastCx) { lastCx = cx; last = b }
            if (isTiny(b, lineHeight)) tiny++
        }
        val l = last
        val rel = if (l != null && lineBounds.height > 0f) (l.centerY - lineBounds.top) / lineBounds.height else -1f
        val descend = if (l != null) (l.bottom - lineBounds.bottom) else 0f
        // Every small stroke: w×h and its centre's position in the band.
        val boxes = strokes.map { Box.of(it.x, it.y) }
        val smalls = strokes.indices.filter { isSmall(boxes[it], lineHeight) }.joinToString(" ") { i ->
            val b = boxes[i]
            val r = if (lineBounds.height > 0f) (b.centerY - lineBounds.top) / lineBounds.height else -1f
            "${"%.0f".format(b.width)}x${"%.0f".format(b.height)}@${"%.2f".format(r)}"
        }
        val end = when (text.trimEnd().lastOrNull()) { null -> "none"; '.' -> "period"; ',' -> "comma"; else -> "other" }
        return "line: ${strokes.size} strokes, h=${"%.0f".format(lineHeight)}, tiny=$tiny, last=${"%.0f".format(l?.width ?: 0f)}x${"%.0f".format(l?.height ?: 0f)} @${"%.2f".format(rel)} bottom${"%+.0f".format(descend)}, ends=$end, trailingDot=$trailingDot, small=[$smalls]"
    }

    /** `"text,"` → `"text."`; `"text"` → `"text."`; endings in `. ! ? : ; …` and blank text unchanged. */
    fun fixTrailingPeriod(text: String): String {
        val t = text.trimEnd()
        if (t.isEmpty()) return text
        return when (t.last()) {
            '.', '!', '?', ':', ';', '…' -> text
            ',' -> t.dropLast(1) + "."
            else -> "$t."
        }
    }
}
