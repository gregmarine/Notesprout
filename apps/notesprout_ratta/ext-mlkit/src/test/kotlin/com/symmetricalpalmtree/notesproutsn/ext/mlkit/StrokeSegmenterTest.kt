package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural pins for the segmenter. Every stroke is a rectangle given by its four corners, so the
 * geometry under test is exact: `stroke(left, top, right, bottom)`.
 */
class StrokeSegmenterTest {

    private fun stroke(l: Float, t: Float, r: Float, b: Float) =
        InkStroke(floatArrayOf(l, r, r, l), floatArrayOf(t, t, b, b))

    /** A "line" of [n] letter-sized boxes starting at [top]: each [h] tall, [w] wide, [gap] apart. */
    private fun line(top: Float, n: Int = 8, h: Float = 30f, w: Float = 20f, gap: Float = 6f, left: Float = 100f) =
        List(n) { i -> stroke(left + i * (w + gap), top, left + i * (w + gap) + w, top + h) }

    private fun lines(layout: StrokeSegmenter.PageLayout) = layout.paragraphs.flatMap { it.lines }

    @Test
    fun emptyPage() {
        val layout = StrokeSegmenter.segment(emptyList())
        assertTrue(layout.paragraphs.isEmpty())
        assertEquals(0f, layout.medianLineHeight, 0f)
    }

    @Test
    fun singlePointStrokesAreNotUsable() {
        val tap = InkStroke(floatArrayOf(5f), floatArrayOf(5f))
        assertTrue(StrokeSegmenter.segment(listOf(tap)).paragraphs.isEmpty())
    }

    @Test
    fun oneLineIsOrderedLeftToRightWithUnionBounds() {
        val ink = line(top = 200f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(1, layout.paragraphs.size)
        val ls = lines(layout)
        assertEquals(1, ls.size)
        assertEquals(ink.size, ls[0].strokes.size)
        assertEquals(100f, ls[0].bounds.left, 0f)
        assertEquals(200f, ls[0].bounds.top, 0f)
        assertEquals(230f, ls[0].bounds.bottom, 0f)
        for (i in 1 until ls[0].strokes.size) assertTrue(ls[0].strokes[i - 1].x[0] < ls[0].strokes[i].x[0])
        assertEquals(30f, layout.medianLineHeight, 0f)
    }

    @Test
    fun aDescenderDoesNotMigrateToTheLineBelow() {
        // Line 1 spans y 200–230 with a "g" reaching to 245; line 2 spans 250–280 with an "l" rising
        // from 235. Their extents overlap, yet the writing bands stay distinct — the gap's coverage
        // of 2 sits under the 15 % band threshold against 20 body strokes a line.
        val l1 = line(top = 200f, n = 20) + stroke(650f, 200f, 670f, 245f)
        val l2 = line(top = 250f, n = 20) + stroke(650f, 235f, 670f, 280f)
        val layout = StrokeSegmenter.segment(l1 + l2)
        val ls = lines(layout)
        assertEquals(2, ls.size)
        assertEquals(1, layout.paragraphs.size)   // a 20 px gap is under 0.9 × the line height
        assertEquals(l1.size, ls[0].strokes.size)
        assertEquals(l2.size, ls[1].strokes.size)
        assertTrue(ls[0].bounds.top < ls[1].bounds.top)
        assertTrue(ls[0].strokes.all { s -> l1.any { it === s } })
        assertTrue(ls[1].strokes.all { s -> l2.any { it === s } })
    }

    @Test
    fun aNoticeableGapStartsANewParagraph() {
        // 20 px between lines 1 and 2 (same paragraph), 60 px before line 3 (> 0.9 × 30).
        val ink = line(top = 100f) + line(top = 150f) + line(top = 240f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(2, layout.paragraphs.size)
        assertEquals(2, layout.paragraphs[0].lines.size)
        assertEquals(1, layout.paragraphs[1].lines.size)
        assertEquals(240f, layout.paragraphs[1].lines[0].bounds.top, 0f)
        assertEquals(3, lines(layout).size)
        // The paragraph's bounds cover its lines.
        assertEquals(100f, layout.paragraphs[0].bounds.top, 0f)
        assertEquals(180f, layout.paragraphs[0].bounds.bottom, 0f)
    }

    @Test
    fun aStrayMarkFoldsBackIntoItsLine() {
        // A dense line plus one stray mark that whitespace split into a band of its own but which
        // overlaps the line well (222–230 of its 18 px height = 0.44 > 0.4).
        val body = line(top = 200f, n = 12)
        val stray = stroke(500f, 222f, 506f, 240f)
        val ls = lines(StrokeSegmenter.segment(body + stray))
        assertEquals(1, ls.size)
        assertEquals(body.size + 1, ls[0].strokes.size)
        assertEquals(240f, ls[0].bounds.bottom, 0f)
    }

    @Test
    fun twoRealLinesNeverMergeHoweverClose() {
        // Only a 10 px gap (two profile buckets) — still two lines, because the fragment merge is
        // guarded on stroke count and both lines are well over it.
        val layout = StrokeSegmenter.segment(line(top = 200f) + line(top = 240f))
        assertEquals(2, lines(layout).size)
        assertEquals(1, layout.paragraphs.size)
    }

    @Test
    fun medianLineHeightIsTheTypicalLineNotTheTallest() {
        val ink = line(top = 100f, h = 30f) + line(top = 200f, h = 30f) + line(top = 300f, h = 50f)
        val layout = StrokeSegmenter.segment(ink)
        assertEquals(3, lines(layout).size)
        assertEquals(30f, layout.medianLineHeight, 0f)
    }
}
