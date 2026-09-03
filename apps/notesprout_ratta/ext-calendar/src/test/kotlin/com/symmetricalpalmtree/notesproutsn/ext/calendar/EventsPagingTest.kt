package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What the events band holds and where its pages break (arc 24 / Z2) — the off-by-one part, and
 *  the part a screenshot cannot check. */
class EventsPagingTest {

    private val sep1 = LocalDate.of(2026, 9, 1)

    private fun ev(id: String) = testEvent(id = id, title = id)
    private fun up(id: String, daysUntil: Int) =
        UpcomingEvent(testEvent(id = id, title = id), sep1.plusDays(daysUntil.toLong()), daysUntil)

    // ── Assembly ─────────────────────────────────────────────────────────────

    @Test
    fun bothSectionsEmptyIsNoRowsAtAll() {
        assertEquals(emptyList<EventsRow>(), EventsPaging.rows(emptyList(), emptyList()))
    }

    @Test
    fun aDayOnItsOwnCarriesNoTodayLabel() {
        // A label exists to tell two lists apart; one list has nothing to tell apart.
        val rows = EventsPaging.rows(listOf(ev("a"), ev("b")), emptyList())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is EventsRow.Today })
    }

    @Test
    fun theTodayLabelAppearsOnlyWhenUpcomingFollows() {
        val rows = EventsPaging.rows(listOf(ev("a")), listOf(up("u", 2)))
        assertEquals(
            listOf(
                EventsRow.Header(EventsPaging.Section.TODAY),
                EventsRow.Today(ev("a")),
                EventsRow.Header(EventsPaging.Section.UPCOMING),
                EventsRow.Upcoming(up("u", 2)),
            ),
            rows,
        )
    }

    @Test
    fun upcomingAloneStillCarriesItsLabel() {
        // Those rows are about other days; a row that is not about today has to say so.
        val rows = EventsPaging.rows(emptyList(), listOf(up("u", 1), up("v", 3)))
        assertEquals(EventsRow.Header(EventsPaging.Section.UPCOMING), rows.first())
        assertEquals(3, rows.size)
    }

    // ── Measuring ────────────────────────────────────────────────────────────

    @Test
    fun aHeaderIsShorterThanARow() {
        assertEquals(30, EventsPaging.heightOf(EventsRow.Header(EventsPaging.Section.TODAY), headerPx = 30, rowPx = 100))
        assertEquals(100, EventsPaging.heightOf(EventsRow.Today(ev("a")), headerPx = 30, rowPx = 100))
        assertEquals(100, EventsPaging.heightOf(EventsRow.Upcoming(up("u", 1)), headerPx = 30, rowPx = 100))
    }

    @Test
    fun anEmptyListIsOneEmptyPage() {
        assertEquals(1, EventsPaging.pageCount(emptyList(), bandPx = 500, headerPx = 30, rowPx = 100))
        assertEquals(emptyList<EventsRow>(), EventsPaging.pageOf(emptyList(), 0, 500, 30, 100))
    }

    @Test
    fun pagesFillGreedilyAndNeverHalfDrawARow() {
        val rows = (1..12).map { EventsRow.Today(ev("e$it")) }
        // 500 px of band, 100 px rows: five whole rows, never a sixth clipped one.
        assertEquals(3, EventsPaging.pageCount(rows, bandPx = 500, headerPx = 30, rowPx = 100))
        assertEquals(5, EventsPaging.pageOf(rows, 0, 500, 30, 100).size)
        assertEquals(5, EventsPaging.pageOf(rows, 1, 500, 30, 100).size)
        assertEquals(2, EventsPaging.pageOf(rows, 2, 500, 30, 100).size)
        assertEquals(EventsRow.Today(ev("e12")), EventsPaging.pageOf(rows, 2, 500, 30, 100).last())
        // A band that is 99 px short of a sixth row is still a five-row band.
        assertEquals(5, EventsPaging.pageOf(rows, 0, 599, 30, 100).size)
    }

    @Test
    fun theShorterHeaderLetsOneMoreRowOntoThePage() {
        // 30 + 4 × 100 = 430 fits in 450; a fifth row would be 530.
        val rows = EventsPaging.rows(emptyList(), (1..8).map { up("u$it", it) })
        assertEquals(5, EventsPaging.pageOf(rows, 0, bandPx = 450, headerPx = 30, rowPx = 100).size)
        assertEquals(2, EventsPaging.pageCount(rows, bandPx = 450, headerPx = 30, rowPx = 100))
    }

    @Test
    fun aPageNeverEndsOnAHeader() {
        // Header + four rows = 430, and the Upcoming label itself fits at 460 — but its list does
        // not, so the label would sit last on page 1 as a label about nothing. It travels with the
        // row it labels instead.
        val rows = EventsPaging.rows((1..4).map { ev("e$it") }, listOf(up("u", 2)))
        val first = EventsPaging.pageOf(rows, 0, bandPx = 460, headerPx = 30, rowPx = 100)
        assertEquals(5, first.size)
        assertEquals(EventsRow.Header(EventsPaging.Section.TODAY), first.first())
        assertTrue(first.last() is EventsRow.Today)
        val second = EventsPaging.pageOf(rows, 1, bandPx = 460, headerPx = 30, rowPx = 100)
        assertEquals(listOf(EventsRow.Header(EventsPaging.Section.UPCOMING), EventsRow.Upcoming(up("u", 2))), second)
        assertEquals(2, EventsPaging.pageCount(rows, bandPx = 460, headerPx = 30, rowPx = 100))
    }

    @Test
    fun aBandTooShortForEvenOneRowStillShowsTheOneYouAreLookingAt() {
        val rows = (1..3).map { EventsRow.Today(ev("e$it")) }
        assertEquals(3, EventsPaging.pageCount(rows, bandPx = 10, headerPx = 30, rowPx = 100))
        assertEquals(1, EventsPaging.pageOf(rows, 0, 10, 30, 100).size)
        assertEquals(1, EventsPaging.pageOf(rows, 0, 0, 30, 100).size)
    }

    @Test
    fun clampKeepsThePageInsideTheList() {
        val rows = (1..12).map { EventsRow.Today(ev("e$it")) }
        assertEquals(0, EventsPaging.clampPage(-2, rows, 500, 30, 100))
        assertEquals(2, EventsPaging.clampPage(9, rows, 500, 30, 100))
        // The list shrinking under a standing page is the real case: the last event on page 3
        // deleted must land on page 2, never on an empty one.
        assertEquals(1, EventsPaging.clampPage(2, rows.take(7), 500, 30, 100))
        assertEquals(0, EventsPaging.clampPage(3, emptyList(), 500, 30, 100))
    }

    @Test
    fun aPagePastTheEndIsEmptyRatherThanAThrow() {
        val rows = (1..3).map { EventsRow.Today(ev("e$it")) }
        assertEquals(emptyList<EventsRow>(), EventsPaging.pageOf(rows, 9, 500, 30, 100))
        assertEquals(emptyList<EventsRow>(), EventsPaging.pageOf(rows, -1, 500, 30, 100))
    }

    @Test
    fun everyRowLandsOnExactlyOnePage() {
        val rows = EventsPaging.rows((1..7).map { ev("e$it") }, (1..5).map { up("u$it", it) })
        val pages = EventsPaging.pageCount(rows, bandPx = 380, headerPx = 30, rowPx = 100)
        val walked = (0 until pages).flatMap { EventsPaging.pageOf(rows, it, 380, 30, 100) }
        assertEquals(rows, walked)
    }
}
