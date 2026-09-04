package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * What the grid knows about an event (arc 24 / Z4), and — the part the bake key stands on — that
 * **two independently built mark maps holding the same thing are `==`**, while a changed title,
 * type or day is not.
 */
class DayMarkTest {

    private val sep1 = LocalDate.of(2026, 9, 1)

    @Test
    fun everyTypeHasItsGlyph() {
        assertEquals(Glyph.CAKE, Glyph.of(EventType.BIRTHDAY))
        assertEquals(Glyph.HEART, Glyph.of(EventType.ANNIVERSARY))
        assertEquals(Glyph.SUITCASE, Glyph.of(EventType.VACATION))
        assertEquals(Glyph.PEOPLE, Glyph.of(EventType.MEETING))
        assertEquals(Glyph.CLOCK, Glyph.of(EventType.APPOINTMENT))
        assertEquals(Glyph.DOT, Glyph.of(EventType.OTHER))
        // Total: every type maps, and no two types are one glyph.
        assertEquals(EventType.entries.size, EventType.entries.map(Glyph::of).toSet().size)
    }

    @Test
    fun aMarkCarriesTheTitleTheTimeAndTheGlyph() {
        val e = testEvent(type = EventType.MEETING, title = "Standup", allDay = false, startMinute = 540)
        assertEquals(DayMark("Standup", allDay = false, startMinute = 540, glyph = Glyph.PEOPLE), DayMark.of(e))
    }

    @Test
    fun anAllDayMarkCarriesNoMinute() {
        val e = testEvent(type = EventType.VACATION, title = "Cornwall", allDay = true)
        val m = DayMark.of(e)
        assertEquals(true, m.allDay)
        assertEquals(null, m.startMinute)
        assertEquals(Glyph.SUITCASE, m.glyph)
    }

    // ── The bake key's guarantee ─────────────────────────────────────────────

    private fun map(title: String, type: EventType = EventType.BIRTHDAY, day: LocalDate = sep1) =
        mapOf(day to listOf(DayMark.of(testEvent(type = type, title = title, start = day))))

    @Test
    fun twoIndependentlyBuiltEqualMarkMapsAreEqual() {
        assertEquals(map("Ann"), map("Ann"))
        assertEquals(map("Ann").hashCode(), map("Ann").hashCode())
    }

    @Test
    fun aChangedTitleTypeOrDayIsNotEqual() {
        assertNotEquals(map("Ann"), map("Bob"))
        assertNotEquals(map("Ann"), map("Ann", type = EventType.MEETING))
        assertNotEquals(map("Ann"), map("Ann", day = sep1.plusDays(1)))
        assertNotEquals(map("Ann"), emptyMap<LocalDate, List<DayMark>>())
    }

    @Test
    fun aChangedTimeIsNotEqual() {
        val nine = listOf(DayMark("Standup", allDay = false, startMinute = 540, glyph = Glyph.PEOPLE))
        val ten = listOf(DayMark("Standup", allDay = false, startMinute = 600, glyph = Glyph.PEOPLE))
        assertNotEquals(mapOf(sep1 to nine), mapOf(sep1 to ten))
    }
}
