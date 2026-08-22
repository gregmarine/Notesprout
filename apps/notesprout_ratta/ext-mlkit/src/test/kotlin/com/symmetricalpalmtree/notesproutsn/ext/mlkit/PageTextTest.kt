package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageTextTest {

    @Test
    fun preContextIsCutToTheContractCap() {
        assertEquals("", PageText.preContextTail(""))
        assertEquals("short", PageText.preContextTail("short"))
        val long = "abcdefghijklmnopqrstuvwxyz"   // 26 chars
        assertEquals("ghijklmnopqrstuvwxyz", PageText.preContextTail(long))
        assertEquals(20, PageText.preContextTail(long).length)
    }

    @Test
    fun linesJoinWithNewlinesAndParagraphsWithABlankLine() {
        assertEquals("", PageText.join(emptyList()))
        assertEquals("a", PageText.join(listOf(listOf("a"))))
        assertEquals("a\nb", PageText.join(listOf(listOf("a", "b"))))
        assertEquals("a\nb\n\nc", PageText.join(listOf(listOf("a", "b"), listOf("c"))))
    }

    @Test
    fun aParagraphThatRecognizedToNothingContributesNothing() {
        assertEquals("", PageText.join(listOf(emptyList(), emptyList())))
        assertEquals("a\n\nc", PageText.join(listOf(listOf("a"), emptyList(), listOf("c"))))
        assertEquals("a", PageText.join(listOf(emptyList(), listOf("a"))))
    }

    @Test
    fun waitForIsTheSmallerOfThePerCallBudgetAndTheTimeLeft() {
        assertEquals(10_000L, PageText.waitFor(deadlineMs = 100_000L, nowMs = 0L, perCallMs = 10_000L))
        assertEquals(1_500L, PageText.waitFor(deadlineMs = 11_500L, nowMs = 10_000L, perCallMs = 10_000L))
        assertEquals(0L, PageText.waitFor(deadlineMs = 10_000L, nowMs = 10_000L, perCallMs = 10_000L))
        assertEquals(-5L, PageText.waitFor(deadlineMs = 10_000L, nowMs = 10_005L, perCallMs = 10_000L))
    }

    @Test
    fun onlySinglePointStrokesAreWidened() {
        val tap = InkStroke(floatArrayOf(5f), floatArrayOf(7f))
        val line = InkStroke(floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 0f))
        val out = PageText.widenDots(listOf(tap, line))
        assertEquals(2, out[0].size)
        assertEquals(5f, out[0].x[0], 0f)
        assertEquals(5f, out[0].x[1], 0f)
        assertEquals(7f, out[0].y[0], 0f)
        assertEquals(7f, out[0].y[1], 0f)
        assertSame(line, out[1])
        // Nothing to widen → the very same list comes back.
        val noTaps = listOf(line)
        assertSame(noTaps, PageText.widenDots(noTaps))
    }

    @Test
    fun aWidenedTapReachesTheSegmenterThatWouldOtherwiseDropIt() {
        // Twenty letter-sized strokes on one line, then a lone tap just after them (a period).
        val strokes = ArrayList<InkStroke>()
        for (i in 0 until 20) strokes += InkStroke(floatArrayOf(i * 10f, i * 10f + 8f), floatArrayOf(100f, 120f))
        strokes += InkStroke(floatArrayOf(205f), floatArrayOf(119f))

        val widened = StrokeSegmenter.segment(PageText.widenDots(strokes))
        assertEquals(21, widened.paragraphs.flatMap { it.lines }.sumOf { it.strokes.size })

        // Without widening, the segmenter's two-point minimum drops the tap entirely.
        val raw = StrokeSegmenter.segment(strokes)
        assertEquals(20, raw.paragraphs.flatMap { it.lines }.sumOf { it.strokes.size })
    }
}
