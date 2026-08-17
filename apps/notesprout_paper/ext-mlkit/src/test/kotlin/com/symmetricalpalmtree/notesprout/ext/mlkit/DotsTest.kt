package com.symmetricalpalmtree.notesprout.ext.mlkit

import com.symmetricalpalmtree.notesprout.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DotsTest {

    private val h = 100f   // line height
    private fun stroke(vararg xy: Float): InkStroke {
        val x = FloatArray(xy.size / 2) { xy[it * 2] }
        val y = FloatArray(xy.size / 2) { xy[it * 2 + 1] }
        return InkStroke(x, y)
    }
    // "H i" as tall strokes spanning y 0..100, then a dot.
    private val hLeft = stroke(0f, 0f, 0f, 100f)
    private val hBar = stroke(0f, 50f, 30f, 50f)
    private val hRight = stroke(30f, 0f, 30f, 100f)
    private val iStem = stroke(50f, 30f, 50f, 100f)
    private val iDot = stroke(50f, 5f, 52f, 7f)          // tiny, top of the band
    private val period = stroke(70f, 94f, 73f, 97f)      // tiny, baseline, right of everything
    private val commaLike = stroke(70f, 85f, 66f, 118f)  // tail: 33 px tall → not small (the writer's commas measure so)
    private val line = listOf(hLeft, hBar, hRight, iStem, iDot, period)
    private val bounds = Box.of(floatArrayOf(0f, 73f), floatArrayOf(0f, 100f))

    @Test fun tinyIsRelativeToLineHeight() {
        assertTrue(Dots.isTiny(Box.of(period.x, period.y), h))
        assertTrue(Dots.isTiny(Box.of(iDot.x, iDot.y), h))
        assertFalse(Dots.isTiny(Box.of(commaLike.x, commaLike.y), h))
        assertFalse(Dots.isTiny(Box.of(period.x, period.y), 10f))   // a 3 px dot on a 10 px line is not tiny
    }

    @Test fun roundReplacesOnlyTinyStrokesWithACircleAtTheirCentre() {
        val out = Dots.round(line, h)
        assertEquals(line.size, out.size)
        assertSame(hLeft, out[0]); assertSame(iStem, out[3])
        val dot = out[5]
        assertEquals(13, dot.size)
        val box = Box.of(dot.x, dot.y)
        assertEquals(71.5f, (box.left + box.right) / 2f, 0.01f)
        assertEquals(95.5f, box.centerY, 0.01f)
        assertEquals(6f, box.width, 0.05f)   // radius 3 % of 100 px
        // Nothing tiny → same instance; zero line height → untouched.
        val letters = listOf(hLeft, hBar)
        assertSame(letters, Dots.round(letters, h))
        assertSame(line, Dots.round(line, 0f))
    }

    @Test fun trailingBaselineDotIsDetected() {
        assertTrue(Dots.endsWithBaselineDot(line, bounds, h))
        // The same line without the period: the i-dot is tiny but high (and not right-most).
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, iStem, iDot), bounds, h))
        // A trailing i-dot at the top of the band, right of everything → not a period.
        val highDot = stroke(70f, 4f, 72f, 6f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, highDot), bounds, h))
        // A comma: taller than the small limit (30 % of the line) → not our business.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, commaLike), bounds, h))
        // A shaky period: 3 × 18 px (over the tiny threshold), centre at 91 % of the band → period.
        val shaky = stroke(70f, 82f, 73f, 100f)
        assertTrue(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, shaky), bounds, h))
        // The same mark hanging 10 px below the letters is still a period (descent is not the discriminator).
        val shakyLow = stroke(70f, 92f, 73f, 110f)
        assertTrue(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, shakyLow), Box.of(floatArrayOf(0f, 73f), floatArrayOf(0f, 110f)), h))
        // A small trailing i-stem: 2 × 16 px low in the band but WITH a tiny dot above it → not a period.
        val stem = stroke(70f, 84f, 72f, 100f)
        val stemDot = stroke(70f, 60f, 72f, 62f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, stem, stemDot), bounds, h))
        // The same small stroke higher in the band (centre at 60 %) → not a period either.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, stroke(70f, 52f, 72f, 68f)), bounds, h))
        // Too tall (35 px) — an apostrophe-sized tick, not a dot.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, stroke(70f, 65f, 73f, 100f)), bounds, h))
        // A dot well inside the word (left of the last letter's right edge by more than the slant allowance).
        val midDot = stroke(10f, 95f, 12f, 97f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, iStem, midDot), bounds, h))
        // Only dots → nothing to end.
        assertFalse(Dots.endsWithBaselineDot(listOf(iDot, period), bounds, h))
        // Slight overlap with a slanted last letter is allowed: the slash-like stem spans x 40..55, the
        // dot sits at 50..52 — right of the letter's centre, 5 px inside its right edge (within 15 % of 100).
        val slanted = stroke(40f, 100f, 55f, 30f)
        val closeDot = stroke(50f, 95f, 52f, 97f)
        assertTrue(Dots.endsWithBaselineDot(listOf(hLeft, slanted, closeDot), bounds, h))
        // Too far inside the letter (20 px) → not trailing.
        val insideDot = stroke(35f, 95f, 37f, 97f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, slanted, insideDot), bounds, h))
    }

    @Test fun fixTrailingPeriod() {
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi"))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi,"))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi."))
        assertEquals("Hi!", Dots.fixTrailingPeriod("Hi!"))
        assertEquals("Hi?", Dots.fixTrailingPeriod("Hi?"))
        assertEquals("a:", Dots.fixTrailingPeriod("a:"))
        assertEquals("a;", Dots.fixTrailingPeriod("a;"))
        assertEquals("", Dots.fixTrailingPeriod(""))
        assertEquals("   ", Dots.fixTrailingPeriod("   "))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi, "))
    }
}
