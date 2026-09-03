package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time picker's arithmetic (arc 24 / Z2): a minute of day split three ways and put back
 *  together, and two steppers that wrap without ever carrying. */
class TimeMathTest {

    @Test
    fun theTwelveHourSplitPutsMidnightAndNoonAtTwelve() {
        assertEquals(12, TimeMath.hour12(0))
        assertFalse(TimeMath.isPm(0))
        assertEquals(12, TimeMath.hour12(12 * 60))
        assertTrue(TimeMath.isPm(12 * 60))
        assertEquals(9, TimeMath.hour12(9 * 60))
        assertEquals(1, TimeMath.hour12(13 * 60))
        assertEquals(11, TimeMath.hour12(1439))
        assertTrue(TimeMath.isPm(1439))
    }

    @Test
    fun theMinutePartIsSnappedToTheSteppersOwnGrain() {
        assertEquals(0, TimeMath.minuteOfHour(9 * 60))
        assertEquals(30, TimeMath.minuteOfHour(9 * 60 + 30))
        assertEquals(5, TimeMath.minuteOfHour(9 * 60 + 4))
        // 11:59 PM snaps DOWN to :55 — a picker may not move the hour behind the person's back.
        assertEquals(55, TimeMath.minuteOfHour(1439))
        assertEquals(55, TimeMath.snap(58))
        assertEquals(0, TimeMath.snap(2))
        assertEquals(5, TimeMath.snap(3))
    }

    @Test
    fun thePartsGoBackTogether() {
        assertEquals(0, TimeMath.minuteOfDay(12, 0, pm = false))
        assertEquals(12 * 60, TimeMath.minuteOfDay(12, 0, pm = true))
        assertEquals(9 * 60 + 5, TimeMath.minuteOfDay(9, 5, pm = false))
        assertEquals(13 * 60 + 30, TimeMath.minuteOfDay(1, 30, pm = true))
        assertEquals(23 * 60 + 55, TimeMath.minuteOfDay(11, 55, pm = true))
    }

    @Test
    fun everyMinuteOfTheDaySurvivesTheRoundTrip() {
        var m = 0
        while (m <= 1439) {
            val back = TimeMath.minuteOfDay(TimeMath.hour12(m), TimeMath.minuteOfHour(m), TimeMath.isPm(m))
            // Only the minute part is snapped; the hour and the half must come back exactly.
            assertEquals(m / 60, back / 60)
            assertEquals(TimeMath.snap(m % 60), back % 60)
            m += TimeMath.MINUTE_STEP
        }
    }

    @Test
    fun theHourStepperWrapsInsideOneToTwelve() {
        assertEquals(10, TimeMath.stepHour(9, 1))
        assertEquals(8, TimeMath.stepHour(9, -1))
        assertEquals(1, TimeMath.stepHour(12, 1))
        assertEquals(12, TimeMath.stepHour(1, -1))
    }

    @Test
    fun theMinuteStepperWrapsAndCarriesNothing() {
        assertEquals(5, TimeMath.stepMinute(0, 1))
        assertEquals(55, TimeMath.stepMinute(0, -1))
        assertEquals(0, TimeMath.stepMinute(55, 1))
        // Off-grid input is snapped first, so the stepper always lands on a position it can show.
        assertEquals(10, TimeMath.stepMinute(7, 1))
    }

    @Test
    fun theDefaultIsNineInTheMorning() {
        assertEquals(9, TimeMath.hour12(TimeMath.DEFAULT_MINUTE))
        assertEquals(0, TimeMath.minuteOfHour(TimeMath.DEFAULT_MINUTE))
        assertFalse(TimeMath.isPm(TimeMath.DEFAULT_MINUTE))
        assertEquals("9:00 AM", EventWording.minute(TimeMath.DEFAULT_MINUTE))
    }
}
