package com.symmetricalpalmtree.notesprout.ext.mlkit

import com.symmetricalpalmtree.notesprout.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural pins for the verbatim port. Strokes are simple boxes (four corner points) so the
 * geometry is exact: `stroke(left, top, right, bottom)`.
 */
class StrokeSegmenterTest {

    private fun stroke(l: Float, t: Float, r: Float, b: Float) =
        InkStroke(floatArrayOf(l, r, r, l), floatArrayOf(t, t, b, b))

    /** A "line" of [n] letter-sized boxes at [top], each [h] tall, [w] wide, [gap] apart. */
    private fun line(top: Float, n: Int = 8, h: Float = 30f, w: Float = 20f, gap: Float = 6f, left: Float = 100f) =
        List(n) { i -> stroke(left + i * (w + gap), top, left + i * (w + gap) + w, top + h) }

    private fun lines(layout: StrokeSegmenter.PageLayout) = layout.paragraphs.flatMap { it.lines }

    @Test
    fun emptyInput() {
        val layout = StrokeSegmenter.segment(emptyList())
        assertTrue(layout.paragraphs.isEmpty())
        assertEquals(0f, layout.medianLineHeight, 0f)
    }

    @Test
    fun singlePointStrokesAreIgnored() {
        val dot = InkStroke(floatArrayOf(5f), floatArrayOf(5f))
        assertTrue(StrokeSegmenter.segment(listOf(dot)).paragraphs.isEmpty())
    }

    @Test
    fun singleLine() {
        val ink = line(top = 200f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(1, layout.paragraphs.size)
        val ls = lines(layout)
        assertEquals(1, ls.size)
        assertEquals(ink.size, ls[0].strokes.size)
        // Ordered left → right, union bounds cover the line.
        assertEquals(100f, ls[0].bounds.left, 0f)
        assertEquals(200f, ls[0].bounds.top, 0f)
        assertEquals(230f, ls[0].bounds.bottom, 0f)
        for (i in 1 until ls[0].strokes.size) assertTrue(ls[0].strokes[i - 1].x[0] < ls[0].strokes[i].x[0])
        assertEquals(30f, layout.medianLineHeight, 0f)
    }

    @Test
    fun twoLinesWithDescenderOverlap() {
        // Line 1 at y 200–230 with one descender ("g") reaching to 245; line 2 at y 250–280 with one
        // ascender ("l") reaching up from 235. Their extents overlap yet the writing bands are distinct
        // (20 body strokes per line → the gap's coverage of 2 sits under the 15 % band threshold).
        val l1 = line(top = 200f, n = 20) + stroke(650f, 200f, 670f, 245f)
        val l2 = line(top = 250f, n = 20) + stroke(650f, 235f, 670f, 280f)
        val layout = StrokeSegmenter.segment(l1 + l2)
        val ls = lines(layout)
        assertEquals(2, ls.size)
        assertEquals(1, layout.paragraphs.size)   // 20 px gap < 0.9 × line height
        assertEquals(l1.size, ls[0].strokes.size)
        assertEquals(l2.size, ls[1].strokes.size)
        assertTrue(ls[0].bounds.top < ls[1].bounds.top)
        // Every stroke of l1 lands in line 0 (the descender did not migrate to line 1).
        assertTrue(ls[0].strokes.all { s -> l1.any { it === s } })
        assertTrue(ls[1].strokes.all { s -> l2.any { it === s } })
    }

    @Test
    fun paragraphGap() {
        // Three lines: 20 px between 1 and 2 (same paragraph), 60 px between 2 and 3 (> 0.9 × 30).
        val ink = line(top = 100f) + line(top = 150f) + line(top = 240f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(2, layout.paragraphs.size)
        assertEquals(2, layout.paragraphs[0].lines.size)
        assertEquals(1, layout.paragraphs[1].lines.size)
        assertEquals(240f, layout.paragraphs[1].lines[0].bounds.top, 0f)
        assertEquals(3, lines(layout).size)
    }

    @Test
    fun fragmentMerge() {
        // A dense line plus a lone stray mark that vertically overlaps it well but is separated
        // by whitespace in the projection profile — it must fold back into the line, not become
        // its own line. The stray sits below the line's body (like a dropped comma) with > 0.4
        // overlap of the shorter box.
        val body = line(top = 200f, n = 12)
        val stray = stroke(500f, 222f, 506f, 240f)   // 18 tall; overlaps the line 222–230 = 8/18 = 0.44
        val layout = StrokeSegmenter.segment(body + stray)
        val ls = lines(layout)
        assertEquals(1, ls.size)
        assertEquals(body.size + 1, ls[0].strokes.size)
        assertEquals(240f, ls[0].bounds.bottom, 0f)
    }

    @Test
    fun twoCloseLinesStayApart() {
        // Two full lines with only a 10 px gap (two profile buckets) stay two lines — and the fragment
        // merge never fires between real lines (both have > FRAGMENT_MAX_STROKES strokes).
        val a = line(top = 200f)
        val b = line(top = 240f)
        val layout = StrokeSegmenter.segment(a + b)
        val ls = lines(layout)
        assertEquals(2, ls.size)
        assertEquals(1, layout.paragraphs.size)   // 10 px < 0.9 × 30
    }

    @Test
    fun medianLineHeight() {
        // Lines 30, 30, 50 tall → median 30; and never below the median stroke height.
        val ink = line(top = 100f, h = 30f) + line(top = 200f, h = 30f) + line(top = 300f, h = 50f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(3, lines(layout).size)
        assertEquals(30f, layout.medianLineHeight, 0f)
    }
}
