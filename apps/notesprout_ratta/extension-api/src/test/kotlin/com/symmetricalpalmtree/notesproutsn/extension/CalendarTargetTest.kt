package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [CalendarTarget.requireValid] — the constructor's checks, which are also the unmarshal's. */
class CalendarTargetTest {

    @Test
    fun kinds() {
        assertEquals(0, CalendarTarget.KIND_MONTH)
        assertEquals(1, CalendarTarget.KIND_WEEK)
        assertEquals(2, CalendarTarget.KIND_DAY)
        assertEquals(0, CalendarTarget.HALF_AM)
        assertEquals(1, CalendarTarget.HALF_PM)
    }

    @Test
    fun normalizedTargetsAreAccepted() {
        CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
        CalendarTarget(CalendarTarget.KIND_WEEK, "2026-08-30", 0)     // a Sunday
        CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 0)
        CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1)
    }

    @Test
    fun unnormalizedDatesAreRejectedNotCorrected() {
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-15", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_WEEK, "2026-09-01", 0) }   // a Tuesday
    }

    @Test
    fun halfIsLegalOnlyForADay() {
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 1) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_WEEK, "2026-08-30", 1) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 2) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", -1) }
    }

    @Test
    fun badKindsAndBadDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(3, "2026-09-01", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(-1, "2026-09-01", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "2026-9-1", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "2026-02-30", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "", 0) }
        assertThrows(IllegalArgumentException::class.java) { CalendarTarget(CalendarTarget.KIND_DAY, "٢٠٢٦-09-01", 0) }
    }

    @Test
    fun ofNormalizes() {
        val tuesday = LocalDate.of(2026, 9, 1)
        assertEquals("2026-09-01", CalendarTarget.of(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 9, 17)).date)
        assertEquals("2026-08-30", CalendarTarget.of(CalendarTarget.KIND_WEEK, tuesday).date)
        val pm = CalendarTarget.of(CalendarTarget.KIND_DAY, tuesday, CalendarTarget.HALF_PM)
        assertEquals("2026-09-01", pm.date)
        assertEquals(1, pm.half)
        assertEquals(tuesday, pm.localDate)
    }

    @Test
    fun equalityIsByValue() {
        val a = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1)
        val b = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 0))
    }
}
