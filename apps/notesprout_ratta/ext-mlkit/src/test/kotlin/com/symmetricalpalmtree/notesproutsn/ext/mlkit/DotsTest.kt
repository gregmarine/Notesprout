package com.symmetricalpalmtree.notesproutsn.ext.mlkit

import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dot rules on a synthetic line "H i." — a 100 px band with tall letters, an i-dot at the top
 * and a period on the baseline. Measurements come from real Nomad handwriting (a 62 px line whose
 * periods measured 3 × 10–15 px), scaled to 100.
 */
class DotsTest {

    private val h = 100f

    private fun mark(x0: Float, y0: Float, x1: Float, y1: Float) =
        InkStroke(floatArrayOf(x0, x1), floatArrayOf(y0, y1))

    private val hLeft = mark(0f, 0f, 0f, 100f)
    private val hBar = mark(0f, 50f, 30f, 50f)
    private val hRight = mark(30f, 0f, 30f, 100f)
    private val iStem = mark(50f, 30f, 50f, 100f)
    private val iDot = mark(50f, 5f, 52f, 7f)            // tiny, at the top of the band
    private val period = mark(70f, 94f, 73f, 97f)        // tiny, on the baseline, right of everything
    private val comma = mark(70f, 85f, 66f, 118f)        // a 33 px tail — taller than any dot rule allows

    private val line = listOf(hLeft, hBar, hRight, iStem, iDot, period)
    private val bounds = Box(0f, 0f, 73f, 100f)

    @Test
    fun tinyIsMeasuredAgainstTheLineHeight() {
        assertTrue(Dots.isTiny(Box.of(period.x, period.y), h))
        assertTrue(Dots.isTiny(Box.of(iDot.x, iDot.y), h))
        assertFalse(Dots.isTiny(Box.of(comma.x, comma.y), h))
        assertFalse(Dots.isTiny(Box.of(period.x, period.y), 10f))   // a 3 px mark on a 10 px line is not tiny
    }

    @Test
    fun roundReplacesOnlyTinyStrokes() {
        val out = Dots.round(line, h)
        assertEquals(line.size, out.size)
        assertSame(hLeft, out[0])
        assertSame(iStem, out[3])

        val dot = out[5]
        assertEquals(13, dot.size)                       // 12 points plus the closing one
        val box = Box.of(dot.x, dot.y)
        assertEquals(71.5f, box.centerX, 0.01f)          // centred on the mark it replaced
        assertEquals(95.5f, box.centerY, 0.01f)
        assertEquals(6f, box.width, 0.05f)               // radius = 3 % of a 100 px line

        assertSame(line, Dots.round(line, 0f))           // no usable line height → untouched
        val letters = listOf(hLeft, hBar)
        assertSame(letters, Dots.round(letters, h))      // nothing tiny → the same list instance
    }

    @Test
    fun aTrailingBaselineDotIsAPeriod() {
        assertTrue(Dots.endsWithBaselineDot(line, bounds, h))
    }

    @Test
    fun anIDotIsNotAPeriod() {
        // The same line without the period: the i-dot is tiny, but high in the band and not right-most.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, iStem, iDot), bounds, h))
        // A tiny mark right of everything but at the top of the band is still not a period.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, mark(70f, 4f, 72f, 6f)), bounds, h))
    }

    @Test
    fun aCommaIsLeftAlone() {
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, comma), bounds, h))
        // And so is an apostrophe-sized 35 px tick.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, mark(70f, 65f, 73f, 100f)), bounds, h))
    }

    @Test
    fun aShakyPeriodCounts() {
        // 3 × 18 px — over the tiny threshold — with its centre at 91 % of the band.
        assertTrue(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, mark(70f, 82f, 73f, 100f)), bounds, h))
        // Hanging 10 px below the letters is still a period: descent is not the discriminator.
        assertTrue(
            Dots.endsWithBaselineDot(
                listOf(hLeft, hBar, hRight, mark(70f, 92f, 73f, 110f)),
                Box(0f, 0f, 73f, 110f),
                h,
            )
        )
        // Too high in the band (centre at 60 %) → not a period.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, mark(70f, 52f, 72f, 68f)), bounds, h))
    }

    @Test
    fun aTrailingIStemIsNotAShakyPeriod() {
        // 2 × 16 px low in the band, but with its tiny dot above it — that is an "i", not a period.
        val stem = mark(70f, 84f, 72f, 100f)
        val stemDot = mark(70f, 60f, 72f, 62f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, stem, stemDot), bounds, h))
    }

    @Test
    fun aDotInsideTheWordIsNotTrailing() {
        val midDot = mark(10f, 95f, 12f, 97f)
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, hBar, hRight, iStem, midDot), bounds, h))
        // A slanted last letter is allowed a little overlap: the stem spans x 40–55 and the dot sits
        // at 50–52, five px inside its right edge — within 15 % of the line height.
        val slanted = mark(40f, 100f, 55f, 30f)
        assertTrue(Dots.endsWithBaselineDot(listOf(hLeft, slanted, mark(50f, 95f, 52f, 97f)), bounds, h))
        // Twenty px inside it is too far.
        assertFalse(Dots.endsWithBaselineDot(listOf(hLeft, slanted, mark(35f, 95f, 37f, 97f)), bounds, h))
    }

    @Test
    fun aLineOfNothingButDotsHasNothingToEnd() {
        assertFalse(Dots.endsWithBaselineDot(listOf(iDot, period), bounds, h))
        // And a one-stroke line is never asked at all.
        assertFalse(Dots.endsWithBaselineDot(listOf(period), bounds, h))
    }

    @Test
    fun fixTrailingPeriod() {
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi"))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi,"))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi, "))
        assertEquals("Hi.", Dots.fixTrailingPeriod("Hi."))
        assertEquals("Hi!", Dots.fixTrailingPeriod("Hi!"))
        assertEquals("Hi?", Dots.fixTrailingPeriod("Hi?"))
        assertEquals("a:", Dots.fixTrailingPeriod("a:"))
        assertEquals("a;", Dots.fixTrailingPeriod("a;"))
        assertEquals("…", Dots.fixTrailingPeriod("…"))
        assertEquals("", Dots.fixTrailingPeriod(""))
        assertEquals("   ", Dots.fixTrailingPeriod("   "))
    }

    @Test
    fun describeLineCarriesGeometryAndNeverTheText() {
        val text = "Hi."
        val summary = Dots.describeLine(line, bounds, h, text, trailingDot = true)
        assertTrue(summary.contains("6 strokes"))
        assertTrue(summary.contains("tiny=2"))
        assertTrue(summary.contains("ends=period"))
        assertTrue(summary.contains("trailingDot=true"))
        assertFalse(summary.contains("Hi"))
        assertEquals("none", Dots.describeLine(line, bounds, h, "", false).substringAfter("ends=").substringBefore(","))
    }
}
