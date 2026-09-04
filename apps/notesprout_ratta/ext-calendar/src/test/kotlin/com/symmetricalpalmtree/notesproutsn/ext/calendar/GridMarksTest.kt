package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The glyph row's arithmetic (arc 24 / Z4): distinct types, og's packing, the overflow `+`, and
 *  the day range each page kind asks its marks for. */
class GridMarksTest {

    private fun mark(glyph: Glyph, title: String = "x") = DayMark(title, allDay = true, startMinute = null, glyph = glyph)

    // ── distinct ─────────────────────────────────────────────────────────────

    @Test
    fun distinctIsFirstSeenOrder_andTwoBirthdaysAreOneCake() {
        val marks = listOf(mark(Glyph.CAKE, "Ann"), mark(Glyph.PEOPLE), mark(Glyph.CAKE, "Bob"))
        assertEquals(listOf(Glyph.CAKE, Glyph.PEOPLE), GridMarks.distinct(marks))
    }

    @Test
    fun distinctOfNothingIsNothing() {
        assertEquals(emptyList<Glyph>(), GridMarks.distinct(emptyList()))
    }

    // ── layout ───────────────────────────────────────────────────────────────

    // A cell with room for four slots: (100 − 0 + 2) / (20 + 2) = 4.
    private val left = 0f
    private val right = 100f
    private val size = 20f
    private val gap = 2f

    private fun layout(vararg glyphs: Glyph) = GridMarks.layout(glyphs.toList(), left, right, size, gap)

    @Test
    fun oneGlyphSitsAgainstTheRightEdge() {
        assertEquals(listOf(GridMarks.Slot(Glyph.CAKE, 80f)), layout(Glyph.CAKE))
    }

    @Test
    fun twoAndThreeGlyphsPackLeftwardFromTheRightEdge() {
        // 2 slots: 100 − 40 − 2 = 58, then +22.
        assertEquals(
            listOf(GridMarks.Slot(Glyph.CAKE, 58f), GridMarks.Slot(Glyph.HEART, 80f)),
            layout(Glyph.CAKE, Glyph.HEART),
        )
        // 3 slots: 100 − 60 − 4 = 36, then +22 each.
        assertEquals(
            listOf(GridMarks.Slot(Glyph.CAKE, 36f), GridMarks.Slot(Glyph.HEART, 58f), GridMarks.Slot(Glyph.DOT, 80f)),
            layout(Glyph.CAKE, Glyph.HEART, Glyph.DOT),
        )
    }

    @Test
    fun overflowKeepsTheFirstMaxMinusOneAndEndsWithThePlus() {
        // Five distinct types into four slots: three glyphs, then the `+` in the last slot.
        val slots = layout(Glyph.CAKE, Glyph.HEART, Glyph.SUITCASE, Glyph.PEOPLE, Glyph.CLOCK)
        assertEquals(4, slots.size)
        assertEquals(listOf(Glyph.CAKE, Glyph.HEART, Glyph.SUITCASE, null), slots.map { it.glyph })
        // Still packed against the right edge: 100 − 80 − 6 = 14.
        assertEquals(listOf(14f, 36f, 58f, 80f), slots.map { it.x })
    }

    @Test
    fun exactlyMaxSlotsDoesNotOverflow() {
        val slots = layout(Glyph.CAKE, Glyph.HEART, Glyph.SUITCASE, Glyph.PEOPLE)
        assertEquals(4, slots.size)
        assertTrue(slots.none { it.glyph == null })
    }

    @Test
    fun roomForOneSlotAndTwoTypesIsALonePlus() {
        // (22 − 0 + 2) / 22 = 1.
        val slots = GridMarks.layout(listOf(Glyph.CAKE, Glyph.HEART), 0f, 22f, 20f, 2f)
        assertEquals(listOf(GridMarks.Slot(null, 2f)), slots)
    }

    @Test
    fun roomForOneSlotAndOneTypeIsThatGlyph() {
        assertEquals(listOf(GridMarks.Slot(Glyph.CAKE, 2f)), GridMarks.layout(listOf(Glyph.CAKE), 0f, 22f, 20f, 2f))
    }

    @Test
    fun noRoomDrawsNothing() {
        // maxSlots = (10 + 2) / 22 = 0.
        assertEquals(emptyList<GridMarks.Slot>(), GridMarks.layout(listOf(Glyph.CAKE), 0f, 10f, 20f, 2f))
        // A left edge past the right one is the same answer, not a negative x.
        assertEquals(emptyList<GridMarks.Slot>(), GridMarks.layout(listOf(Glyph.CAKE), 120f, 100f, 20f, 2f))
    }

    @Test
    fun noGlyphsDrawNothingHoweverMuchRoomThereIs() {
        assertEquals(emptyList<GridMarks.Slot>(), GridMarks.layout(emptyList(), 0f, 1000f, 20f, 2f))
    }

    @Test
    fun aZeroSizedSlotIsRefusedRatherThanDividedBy() {
        assertEquals(emptyList<GridMarks.Slot>(), GridMarks.layout(listOf(Glyph.CAKE), 0f, 100f, 0f, 0f))
    }

    // ── rangeOf ──────────────────────────────────────────────────────────────

    @Test
    fun aMonthAsksForAll42Cells_outOfMonthIncluded() {
        // September 2026 starts on a Tuesday, so the grid opens on Sunday Aug 30 and runs to Oct 10.
        val (from, to) = GridMarks.rangeOf(CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0))
        assertEquals(LocalDate.of(2026, 8, 30), from)
        assertEquals(LocalDate.of(2026, 10, 10), to)
        assertEquals(41L, to.toEpochDay() - from.toEpochDay())
    }

    @Test
    fun aWeekAsksForItsSevenDays() {
        val (from, to) = GridMarks.rangeOf(CalendarTarget(CalendarTarget.KIND_WEEK, "2026-08-30", 0))
        assertEquals(LocalDate.of(2026, 8, 30), from)
        assertEquals(LocalDate.of(2026, 9, 5), to)
    }

    @Test
    fun aDayAsksForItsOneDay_whicheverHalfIsShowing() {
        val am = GridMarks.rangeOf(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", CalendarTarget.HALF_AM))
        val pm = GridMarks.rangeOf(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", CalendarTarget.HALF_PM))
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 1), am)
        assertEquals(am, pm)
    }
}
