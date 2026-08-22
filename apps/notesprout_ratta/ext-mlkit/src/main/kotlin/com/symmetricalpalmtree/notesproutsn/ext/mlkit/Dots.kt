package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dot handling around ML Kit. Observed on every device (and in the original app): ML Kit **skips**
 * a hand-written period, or reads it as a **comma**. A pen-tap period on e-ink is a 1–4 point stroke
 * with a couple of px of drag in the lift direction — a shape the model half-reads as a comma tail
 * or ignores as zero-length ink. Two pure, geometry-only remedies, no text analysis beyond the last
 * character:
 *
 * 1. [round] — a **tiny** stroke (bounding box ≤ [TINY_FRAC] × line height in *both* directions) is
 *    replaced by a small circle at its centre, so ML Kit sees an unambiguous period / i-dot instead
 *    of a tick. Commas, apostrophes and hyphens are taller or wider than that and pass through.
 * 2. [endsWithBaselineDot] + [fixTrailingPeriod] — when the line's **right-most** stroke is a dot
 *    sitting in the baseline zone (below [BASELINE_FRAC] of the band, so an i-dot at the top is
 *    excluded), the line should end in a period: a trailing `,` becomes `.`, a missing terminator
 *    gets one, and `. ! ? : ; …` are left alone. A **shaky period** (measured on the Nomad at
 *    3 × 10–15 px on a 62 px line — over the tiny threshold) also counts when it is *small*
 *    (≤ [TINY_FRAC] wide, ≤ [SHAKY_FRAC] tall — that writer's real commas were all taller or wider),
 *    its centre sits in the bottom [SHAKY_ZONE_FRAC] of the band, and **nothing tiny sits above it**:
 *    the one other small mark that ends a word is an i-stem, and an i-stem always has its dot.
 *
 * Nothing here is logged but counts and geometry.
 */
internal object Dots {

    /** "Tiny" = width **and** height at most this fraction of the line height. */
    const val TINY_FRAC = 0.15f

    /** The baseline zone is the part of the line band below this fraction of its height. */
    const val BASELINE_FRAC = 0.45f

    /** Radius of the circle [round] draws, as a fraction of the line height (floored at 0.75 px). */
    const val ROUND_RADIUS_FRAC = 0.03f

    /** A trailing dot may start this far (× line height) inside the last letter's right edge (slant). */
    const val OVERLAP_FRAC = 0.15f

    /** A shaky period may be this tall (× line height); the same hand's commas exceed it. */
    const val SHAKY_FRAC = 0.3f

    /** A shaky period's centre must sit in the bottom this-fraction of the band (an i-stem's is higher). */
    const val SHAKY_ZONE_FRAC = 0.3f

    private const val ROUND_POINTS = 12

    fun isTiny(box: Box, lineHeight: Float): Boolean {
        val limit = TINY_FRAC * lineHeight
        return box.width <= limit && box.height <= limit
    }

    /** Narrow and no taller than a comma — the shaky-period candidate set (every tiny stroke included). */
    fun isSmall(box: Box, lineHeight: Float): Boolean =
        box.width <= TINY_FRAC * lineHeight && box.height <= SHAKY_FRAC * lineHeight

    /**
     * [strokes] with every tiny stroke replaced by a closed [ROUND_POINTS]-point circle at its
     * centre. Returns the input list untouched when [lineHeight] is unusable or nothing is tiny.
     */
    fun round(strokes: List<InkStroke>, lineHeight: Float): List<InkStroke> {
        if (lineHeight <= 0f) return strokes
        var replaced = false
        val out = ArrayList<InkStroke>(strokes.size)
        for (s in strokes) {
            val box = Box.of(s.x, s.y)
            if (!isTiny(box, lineHeight)) {
                out += s
                continue
            }
            replaced = true
            val cx = box.centerX
            val cy = box.centerY
            val r = (ROUND_RADIUS_FRAC * lineHeight).coerceAtLeast(0.75f)
            val n = ROUND_POINTS + 1   // the last point closes the ring
            val x = FloatArray(n)
            val y = FloatArray(n)
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / ROUND_POINTS
                x[i] = (cx + r * cos(a)).toFloat()
                y[i] = (cy + r * sin(a)).toFloat()
            }
            out += InkStroke(x, y)
        }
        return if (replaced) out else strokes
    }

    /**
     * True when the right-most stroke of the line (by centre x) is a period-shaped mark: **tiny**
     * anywhere in the baseline zone of [lineBounds], or **small** with its centre in the bottom
     * [SHAKY_ZONE_FRAC] of the band and no tiny stroke dotted above it. It must also begin no more
     * than [OVERLAP_FRAC] × [lineHeight] left of the right edge of the line's non-small strokes —
     * i.e. it follows the last letter rather than dotting an "i" inside the word.
     */
    fun endsWithBaselineDot(strokes: List<InkStroke>, lineBounds: Box, lineHeight: Float): Boolean {
        if (strokes.size < 2 || lineHeight <= 0f) return false
        val boxes = strokes.map { Box.of(it.x, it.y) }
        var dot: Box? = null
        var rightMostCx = Float.NEGATIVE_INFINITY
        var lettersRight = Float.NEGATIVE_INFINITY
        var letters = 0
        for (b in boxes) {
            if (b.centerX > rightMostCx) { rightMostCx = b.centerX; dot = b }
            if (!isSmall(b, lineHeight)) { lettersRight = maxOf(lettersRight, b.right); letters++ }
        }
        val last = dot ?: return false
        if (!isSmall(last, lineHeight)) return false
        if (letters == 0) return false   // a line of nothing but dots — there is nothing to end
        if (last.left < lettersRight - OVERLAP_FRAC * lineHeight) return false
        if (last.centerY < lineBounds.top + BASELINE_FRAC * lineBounds.height) return false
        if (isTiny(last, lineHeight)) return true
        // Shaky period vs. i-stem: low in the band, and nothing dotted above it.
        if (last.centerY < lineBounds.top + (1f - SHAKY_ZONE_FRAC) * lineBounds.height) return false
        for (b in boxes) {
            if (b === last) continue
            if (isTiny(b, lineHeight) && b.centerY < last.top && b.right >= last.left && b.left <= last.right) return false
        }
        return true
    }

    /** `"text,"` → `"text."` · `"text"` → `"text."` · endings in `. ! ? : ; …` and blank text unchanged. */
    fun fixTrailingPeriod(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return text
        return when (trimmed.last()) {
            '.', '!', '?', ':', ';', '…' -> text
            ',' -> trimmed.dropLast(1) + "."
            else -> "$trimmed."
        }
    }

    /**
     * A debug-only geometry summary of one recognized line — **never the text**: stroke count, line
     * height, how many strokes are tiny, the right-most stroke's size and where its centre sits in
     * the band (0 = top, 1 = bottom), how far it hangs below the band, every small stroke's
     * size@position, the *class* of the text's last character, and whether the trailing-dot rule fired.
     */
    fun describeLine(strokes: List<InkStroke>, lineBounds: Box, lineHeight: Float, text: String, trailingDot: Boolean): String {
        val boxes = strokes.map { Box.of(it.x, it.y) }
        var last: Box? = null
        var rightMostCx = Float.NEGATIVE_INFINITY
        var tiny = 0
        for (b in boxes) {
            if (b.centerX > rightMostCx) { rightMostCx = b.centerX; last = b }
            if (isTiny(b, lineHeight)) tiny++
        }
        fun rel(b: Box): Float = if (lineBounds.height > 0f) (b.centerY - lineBounds.top) / lineBounds.height else -1f
        val smalls = boxes.filter { isSmall(it, lineHeight) }.joinToString(" ") { b ->
            "${fmt0(b.width)}x${fmt0(b.height)}@${fmt2(rel(b))}"
        }
        val end = when (text.trimEnd().lastOrNull()) {
            null -> "none"
            '.' -> "period"
            ',' -> "comma"
            else -> "other"
        }
        val l = last
        return "line: ${strokes.size} strokes, h=${fmt0(lineHeight)}, tiny=$tiny, " +
            "last=${fmt0(l?.width ?: 0f)}x${fmt0(l?.height ?: 0f)} @${fmt2(if (l != null) rel(l) else -1f)} " +
            "bottom${if (l != null) fmtSigned(l.bottom - lineBounds.bottom) else "+0"}, " +
            "ends=$end, trailingDot=$trailingDot, small=[$smalls]"
    }

    private fun fmt0(v: Float) = String.format("%.0f", v)
    private fun fmt2(v: Float) = String.format("%.2f", v)
    private fun fmtSigned(v: Float) = String.format("%+.0f", v)
}
