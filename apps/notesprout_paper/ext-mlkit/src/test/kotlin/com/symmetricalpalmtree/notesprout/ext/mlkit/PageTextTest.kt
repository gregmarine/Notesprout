package com.symmetricalpalmtree.notesprout.ext.mlkit

import com.symmetricalpalmtree.notesprout.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageTextTest {

    @Test
    fun preContextTailIsAtMost20Chars() {
        assertEquals("", PageText.preContextTail(""))
        assertEquals("short", PageText.preContextTail("short"))
        val long = "abcdefghijklmnopqrstuvwxyz"   // 26
        assertEquals("ghijklmnopqrstuvwxyz", PageText.preContextTail(long))
        assertEquals(20, PageText.preContextTail(long).length)
    }

    @Test
    fun joinLinesAndParagraphs() {
        assertEquals("", PageText.join(emptyList()))
        assertEquals("a", PageText.join(listOf(listOf("a"))))
        assertEquals("a\nb", PageText.join(listOf(listOf("a", "b"))))
        assertEquals("a\nb\n\nc", PageText.join(listOf(listOf("a", "b"), listOf("c"))))
    }

    @Test
    fun emptyParagraphsContributeNothing() {
        assertEquals("", PageText.join(listOf(emptyList(), emptyList())))
        assertEquals("a\n\nc", PageText.join(listOf(listOf("a"), emptyList(), listOf("c"))))
    }

    @Test
    fun waitForIsTheSmallerOfPerCallAndTimeLeft() {
        assertEquals(10_000L, PageText.waitFor(deadlineMs = 100_000L, nowMs = 0L, perCallMs = 10_000L))
        assertEquals(1_500L, PageText.waitFor(deadlineMs = 11_500L, nowMs = 10_000L, perCallMs = 10_000L))
        assertEquals(0L, PageText.waitFor(deadlineMs = 10_000L, nowMs = 10_000L, perCallMs = 10_000L))
        assertEquals(-5L, PageText.waitFor(deadlineMs = 10_000L, nowMs = 10_005L, perCallMs = 10_000L))
    }

    @Test
    fun widenDotsPromotesSinglePointStrokesOnly() {
        val dot = InkStroke(floatArrayOf(5f), floatArrayOf(7f))
        val line = InkStroke(floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 0f))
        val out = PageText.widenDots(listOf(dot, line))
        assertEquals(2, out[0].size)
        assertEquals(5f, out[0].x[0]); assertEquals(5f, out[0].x[1])
        assertEquals(7f, out[0].y[0]); assertEquals(7f, out[0].y[1])
        assertSame(line, out[1])
        // No dots → the same list instance, untouched.
        val noDots = listOf(line)
        assertSame(noDots, PageText.widenDots(noDots))
    }

    @Test
    fun widenedDotSurvivesTheSegmenter() {
        // A line of 20 strokes plus a lone tap (a period) after it: the tap must land in that line.
        val strokes = ArrayList<InkStroke>()
        for (i in 0 until 20) strokes += InkStroke(floatArrayOf(i * 10f, i * 10f + 8f), floatArrayOf(100f, 120f))
        strokes += InkStroke(floatArrayOf(205f), floatArrayOf(119f))
        val layout = StrokeSegmenter.segment(PageText.widenDots(strokes))
        val all = layout.paragraphs.flatMap { it.lines }.sumOf { it.strokes.size }
        assertEquals(21, all)
        // Verbatim segmenter: the raw single-point stroke is dropped.
        val raw = StrokeSegmenter.segment(strokes).paragraphs.flatMap { it.lines }.sumOf { it.strokes.size }
        assertEquals(20, raw)
    }
}
