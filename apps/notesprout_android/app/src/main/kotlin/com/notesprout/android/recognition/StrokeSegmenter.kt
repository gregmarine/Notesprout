package com.notesprout.android.recognition

import android.graphics.RectF
import com.notesprout.android.core.Slog
import com.notesprout.android.data.LiveStroke
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure geometry: turns a flat list of a page's [LiveStroke]s into reading-order lines and
 * paragraphs. No ML, no Android UI beyond [RectF]; unit-testable in isolation.
 *
 * **Line detection uses a vertical projection profile.** Sorting strokes by their vertical
 * center and greedily merging (the first cut) interleaves strokes from adjacent lines whenever a
 * descender on one line dips below an ascender on the next — fragmenting sentences that would
 * otherwise recognize cleanly. Instead we build a coverage histogram over Y, find the dense
 * "bands" of writing (separated by whitespace), and assign each stroke to the nearest band. This
 * is robust to ascenders/descenders and to lines that are close together.
 *
 * Single-column in v1. See docs/handwriting-recognition.md § "StrokeSegmenter".
 */
object StrokeSegmenter {

    private const val TAG = "StrokeSegmenter"

    /** A blank vertical gap between consecutive lines greater than medianLineHeight × this
     *  is treated as an intentional paragraph break (the "noticeable gap = new paragraph" rule). */
    private const val PARA_GAP_FRAC = 0.9f

    /** A Y bucket belongs to a writing band when its stroke-coverage is at least
     *  peakCoverage × this (with a floor of 1) — filters out sparse descender/ascender tails. */
    private const val BAND_COVERAGE_FRAC = 0.15f

    /** A tiny fragment (≤ this many strokes) that vertically overlaps a neighbor line is folded
     *  into it — recombines a stray trailing mark that split off as its own band. */
    private const val FRAGMENT_MAX_STROKES = 3

    /** Vertical-overlap fraction (of the shorter box) above which a fragment merges into a neighbor. */
    private const val MERGE_OVERLAP_FRAC = 0.4f

    /** One recognized text line: the strokes that compose it and their union bounds. */
    data class Segment(val strokes: List<LiveStroke>, val bounds: RectF)

    /** A run of consecutive lines with no intentional gap between them. */
    data class Paragraph(val lines: List<Segment>) {
        val bounds: RectF
            get() {
                val r = RectF(lines.first().bounds)
                for (l in lines.drop(1)) r.union(l.bounds)
                return r
            }
    }

    /** Whole-page layout, top → bottom. */
    data class PageLayout(val paragraphs: List<Paragraph>)

    fun segment(strokes: List<LiveStroke>): PageLayout {
        val usable = strokes.filter { it.points.size >= 2 && it.boundingBox.height() >= 0f }
        if (usable.isEmpty()) return PageLayout(emptyList())

        val medianStrokeH = medianOf(usable.map { it.boundingBox.height() }).coerceAtLeast(1f)

        // ── 1. Vertical projection profile ──────────────────────────────────────
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in usable) {
            if (s.boundingBox.top < minY) minY = s.boundingBox.top
            if (s.boundingBox.bottom > maxY) maxY = s.boundingBox.bottom
        }
        // Bucket at a fraction of the writing size so a band is several buckets wide but the
        // profile stays cheap (a few hundred buckets on a typical page).
        val bucketPx = max(2f, medianStrokeH / 6f)
        val bucketCount = (ceil(((maxY - minY) / bucketPx).toDouble()).toInt() + 1).coerceAtLeast(1)
        val coverage = IntArray(bucketCount)
        for (s in usable) {
            val b = s.boundingBox
            val lo = (((b.top - minY) / bucketPx).toInt()).coerceIn(0, bucketCount - 1)
            val hi = (((b.bottom - minY) / bucketPx).toInt()).coerceIn(0, bucketCount - 1)
            for (i in lo..hi) coverage[i]++
        }
        val peak = coverage.maxOrNull() ?: 0
        val threshold = max(1, ceil(peak * BAND_COVERAGE_FRAC.toDouble()).toInt())

        // ── 2. Bands = contiguous runs of buckets at/above threshold ────────────
        //    Each band is [startBucketCenterY, endBucketCenterY].
        data class Band(val topY: Float, val bottomY: Float)
        val bands = mutableListOf<Band>()
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

        // ── 3. Assign every stroke to a band by its center (nearest band if in a gap) ──
        val members: List<MutableList<LiveStroke>> = when {
            bands.isEmpty() -> listOf(usable.toMutableList())
            else -> {
                val buckets = List(bands.size) { mutableListOf<LiveStroke>() }
                for (s in usable) {
                    val cy = s.boundingBox.centerY()
                    var idx = bands.indexOfFirst { cy in it.topY..it.bottomY }
                    if (idx < 0) {
                        // In a gap — pick the band whose center is closest.
                        var best = 0; var bestDist = Float.MAX_VALUE
                        bands.forEachIndexed { i, band ->
                            val d = kotlin.math.abs(cy - (band.topY + band.bottomY) / 2f)
                            if (d < bestDist) { bestDist = d; best = i }
                        }
                        idx = best
                    }
                    buckets[idx] += s
                }
                buckets
            }
        }

        // ── 4. Segments: order each line L→R, union bounds; drop empty bands ─────
        val rawSegments = members.filter { it.isNotEmpty() }.map { m ->
            makeSegment(m)
        }.sortedBy { it.bounds.top }

        if (rawSegments.isEmpty()) return PageLayout(emptyList())

        // 4b. Merge a tiny fragment into a vertically-overlapping neighbor (stray trailing mark
        //     that split into its own band). Guarded so two real lines never merge.
        val segments = mutableListOf(rawSegments.first())
        for (i in 1 until rawSegments.size) {
            val seg = rawSegments[i]
            val last = segments.last()
            val fragment = minOf(seg.strokes.size, last.strokes.size) <= FRAGMENT_MAX_STROKES
            if (fragment && verticalOverlapFrac(last.bounds, seg.bounds) > MERGE_OVERLAP_FRAC) {
                segments[segments.size - 1] = makeSegment(last.strokes + seg.strokes)
            } else {
                segments += seg
            }
        }

        // ── 5. Paragraph breaks: a blank gap beyond the ratio splits paragraphs ──
        val medianLineH = medianOf(segments.map { it.bounds.height() }).coerceAtLeast(medianStrokeH)
        val paraGap = medianLineH * PARA_GAP_FRAC
        val paragraphs = mutableListOf<Paragraph>()
        var cur = mutableListOf(segments.first())
        for (i in 1 until segments.size) {
            val gap = segments[i].bounds.top - segments[i - 1].bounds.bottom
            if (gap > paraGap) {
                paragraphs += Paragraph(cur)
                cur = mutableListOf(segments[i])
            } else cur += segments[i]
        }
        paragraphs += Paragraph(cur)

        Slog.d(TAG) {
            "segment: ${usable.size} strokes → ${segments.size} lines / ${paragraphs.size} paras " +
                "(medStrokeH=${medianStrokeH.roundToInt()}, peak=$peak, thr=$threshold, bands=${bands.size})"
        }
        return PageLayout(paragraphs)
    }

    /** Build a [Segment] from strokes: order left→right and union their bounds. */
    private fun makeSegment(strokes: List<LiveStroke>): Segment {
        val ordered = strokes.sortedBy { it.boundingBox.left }
        val bounds = RectF(ordered.first().boundingBox)
        for (x in ordered.drop(1)) bounds.union(x.boundingBox)
        return Segment(ordered, bounds)
    }

    /** Vertical overlap of two boxes as a fraction of the shorter box's height (0 = disjoint). */
    private fun verticalOverlapFrac(a: RectF, b: RectF): Float {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0f) return 0f
        val shorter = minOf(a.height(), b.height()).coerceAtLeast(1f)
        return overlap / shorter
    }

    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }
}
