package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Pure geometry: a flat list of a page's [InkStroke]s → reading-order lines and paragraphs. No ML,
 * no Android types, single column; unit-testable on its own.
 *
 * **Lines come from a vertical projection profile, not from sorting.** Sorting strokes by their
 * vertical centre and greedily merging interleaves strokes from adjacent lines the moment a
 * descender on one line dips past an ascender on the next, which fragments sentences that would
 * otherwise recognize cleanly. Instead a coverage histogram over Y finds the dense *bands* of
 * writing separated by whitespace, and each stroke joins the band its centre falls in (the nearest
 * band when it falls in a gap). That is robust to ascenders/descenders and to tight line spacing.
 *
 * The constants below are the values the recognition pipeline has been tuned to on real e-ink
 * handwriting — treat a change to any of them as a behaviour change, not a tweak.
 */
object StrokeSegmenter {

    /** A blank gap between consecutive lines wider than medianLineHeight × this reads as an
     *  intentional paragraph break ("a noticeable gap means a new paragraph"). */
    private const val PARA_GAP_FRAC = 0.9f

    /** A Y bucket counts as writing when its stroke coverage reaches peakCoverage × this (floor 1) —
     *  which filters the sparse ascender/descender tails out of the band edges. */
    private const val BAND_COVERAGE_FRAC = 0.15f

    /** A fragment of at most this many strokes that vertically overlaps its neighbour line is folded
     *  into it — that recombines a stray trailing mark which split off as a band of its own. */
    private const val FRAGMENT_MAX_STROKES = 3

    /** Vertical overlap (as a fraction of the shorter box) above which a fragment merges. */
    private const val MERGE_OVERLAP_FRAC = 0.4f

    /** A stroke together with its bounding box, computed exactly once. */
    private class Boxed(val stroke: InkStroke, val box: Box)

    /** One text line: its strokes in left→right order and their union bounds. */
    data class Segment(val strokes: List<InkStroke>, val bounds: Box)

    /** Consecutive lines with no intentional gap between them. */
    data class Paragraph(val lines: List<Segment>) {
        val bounds: Box
            get() {
                var b = lines.first().bounds
                for (i in 1 until lines.size) b = b.union(lines[i].bounds)
                return b
            }
    }

    /**
     * A whole page's layout, top → bottom.
     *
     * [medianLineHeight] is the page's typical single-line height in page px (0 when there is no
     * writing). It is what the recognizer should use as the `WritingArea` height: ML Kit judges a
     * glyph by its size relative to the writing area ("o" vs "O", comma vs slash), so one consistent
     * line-height reference beats each line's own tight box — a line of only short letters would
     * otherwise declare a too-small area and skew the reading toward tall glyphs.
     */
    data class PageLayout(val paragraphs: List<Paragraph>, val medianLineHeight: Float = 0f)

    fun segment(strokes: List<InkStroke>): PageLayout {
        // Two points is the minimum a band can be measured from; single-point taps are widened
        // upstream (PageText.widenDots) rather than being special-cased here.
        val usable = strokes.filter { it.size >= 2 }.map { Boxed(it, Box.of(it.x, it.y)) }
        if (usable.isEmpty()) return PageLayout(emptyList())

        val medianStrokeH = median(usable.map { it.box.height }).coerceAtLeast(1f)

        // ── 1 · Vertical projection profile ──────────────────────────────────
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in usable) {
            if (s.box.top < minY) minY = s.box.top
            if (s.box.bottom > maxY) maxY = s.box.bottom
        }
        // Bucket at a fraction of the writing size: a band is several buckets tall while the whole
        // profile stays a few hundred buckets on a typical page.
        val bucketPx = max(2f, medianStrokeH / 6f)
        val bucketCount = (ceil(((maxY - minY) / bucketPx).toDouble()).toInt() + 1).coerceAtLeast(1)
        val coverage = IntArray(bucketCount)
        for (s in usable) {
            val lo = ((s.box.top - minY) / bucketPx).toInt().coerceIn(0, bucketCount - 1)
            val hi = ((s.box.bottom - minY) / bucketPx).toInt().coerceIn(0, bucketCount - 1)
            for (i in lo..hi) coverage[i]++
        }
        val peak = coverage.maxOrNull() ?: 0
        val threshold = max(1, ceil(peak * BAND_COVERAGE_FRAC.toDouble()).toInt())

        // ── 2 · Bands = contiguous runs of buckets at or above the threshold ──
        val bands = ArrayList<Band>()
        var runStart = -1
        for (i in 0 until bucketCount) {
            val on = coverage[i] >= threshold
            if (on && runStart < 0) runStart = i
            if (!on && runStart >= 0) {
                bands += Band(minY + runStart * bucketPx, minY + i * bucketPx)
                runStart = -1
            }
        }
        if (runStart >= 0) bands += Band(minY + runStart * bucketPx, minY + bucketCount * bucketPx)

        // ── 3 · Every stroke joins a band by its centre (nearest band if in a gap) ──
        val members: List<MutableList<Boxed>> =
            if (bands.isEmpty()) {
                listOf(usable.toMutableList())
            } else {
                val buckets = List(bands.size) { mutableListOf<Boxed>() }
                for (s in usable) buckets[bandFor(bands, s.box.centerY)] += s
                buckets
            }

        // ── 4 · Lines: order each band left→right, union the bounds, drop empties ──
        val raw = members.filter { it.isNotEmpty() }.map { makeLine(it) }.sortedBy { it.bounds.top }
        if (raw.isEmpty()) return PageLayout(emptyList())

        // 4b · Fold a tiny fragment into a vertically-overlapping neighbour (a stray mark that
        //      whitespace split into its own band). The stroke-count guard keeps two real lines
        //      from ever merging.
        val lines = ArrayList<BoxedLine>()
        lines += raw.first()
        for (i in 1 until raw.size) {
            val line = raw[i]
            val previous = lines.last()
            val fragment = minOf(line.strokes.size, previous.strokes.size) <= FRAGMENT_MAX_STROKES
            if (fragment && previous.bounds.verticalOverlapFrac(line.bounds) > MERGE_OVERLAP_FRAC) {
                lines[lines.size - 1] = makeLine(previous.strokes + line.strokes)
            } else {
                lines += line
            }
        }

        // ── 5 · Paragraphs: a blank gap beyond the ratio starts a new one ──────
        val medianLineH = median(lines.map { it.bounds.height }).coerceAtLeast(medianStrokeH)
        val paraGap = medianLineH * PARA_GAP_FRAC
        val paragraphs = ArrayList<Paragraph>()
        var current = mutableListOf(lines.first().toSegment())
        for (i in 1 until lines.size) {
            val gap = lines[i].bounds.top - lines[i - 1].bounds.bottom
            if (gap > paraGap) {
                paragraphs += Paragraph(current)
                current = mutableListOf(lines[i].toSegment())
            } else {
                current += lines[i].toSegment()
            }
        }
        paragraphs += Paragraph(current)

        return PageLayout(paragraphs, medianLineH)
    }

    /** A run of writing on the Y axis, as bucket-centre coordinates. */
    private class Band(val topY: Float, val bottomY: Float)

    /** The band containing [centerY], else the band whose middle is nearest to it. */
    private fun bandFor(bands: List<Band>, centerY: Float): Int {
        for (i in bands.indices) if (centerY >= bands[i].topY && centerY <= bands[i].bottomY) return i
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in bands.indices) {
            val d = abs(centerY - (bands[i].topY + bands[i].bottomY) / 2f)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    /** A line still carrying its [Boxed] strokes, so no bounding box is ever recomputed. */
    private class BoxedLine(val strokes: List<Boxed>, val bounds: Box) {
        fun toSegment() = Segment(strokes.map { it.stroke }, bounds)
    }

    private fun makeLine(strokes: List<Boxed>): BoxedLine {
        val ordered = strokes.sortedBy { it.box.left }
        var bounds = ordered.first().box
        for (i in 1 until ordered.size) bounds = bounds.union(ordered[i].box)
        return BoxedLine(ordered, bounds)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
