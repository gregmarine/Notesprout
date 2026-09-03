package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * What the events screen's band holds, and how much of it fits (arc 24 / Z2) — pure and
 * JVM-tested, because this is the part that gets an off-by-one and the part a screenshot cannot
 * check.
 *
 * The pager idiom is `TagPaging`'s, and every reason carries over: **rows are measured against the
 * real band**, never a guessed count, so the list never half-draws a row and never leaves a gap;
 * the arrows **never disable** (a disabled control is invisible on e-ink), they simply have nothing
 * to do at the ends; and with one page the pager is `INVISIBLE`, not `GONE`, so the band keeps its
 * height and the rows above it do not shift when the count crosses the boundary.
 *
 * The one thing that is *not* the tag list: rows here are not all the same height. A section header
 * is shorter than an event row, so a page cannot be `total / perPage` — it is a greedy walk that
 * adds heights until the next row would not fit. Two sections, two heights, one arithmetic.
 */

/** One line of the events band. Three kinds, because the band has three: the two section labels,
 *  the day's own events, and the look-ahead rows. */
sealed class EventsRow {

    /** A section label. The label *text* is not here — a pure, JVM-tested file does not reach for
     *  `R`; the screen maps the section to its string, which is also where the two names live. */
    data class Header(val section: EventsPaging.Section) : EventsRow()

    /** One of the day's own events. */
    data class Today(val event: Event) : EventsRow()

    /** One look-ahead row: the event, the occurrence it is looking ahead to, and the countdown. */
    data class Upcoming(val upcoming: UpcomingEvent) : EventsRow()
}

object EventsPaging {

    /** The two sections, in the order they appear. */
    enum class Section { TODAY, UPCOMING }

    // ── Assembly ─────────────────────────────────────────────────────────────

    /**
     * The band's rows for one day: the day's own events, then the look-ahead.
     *
     * **The "Today" label appears only when Upcoming follows** (the wizard's rule): a label exists
     * to tell two lists apart, and a screen showing one list has nothing to tell apart. The
     * "Upcoming" label is always there when there is an Upcoming section, because those rows are
     * about *other* days and a row that is not about today must say so.
     *
     * Both empty is an empty list — the screen shows its empty line instead, and never an
     * orphaned header over nothing.
     */
    fun rows(today: List<Event>, upcoming: List<UpcomingEvent>): List<EventsRow> {
        if (today.isEmpty() && upcoming.isEmpty()) return emptyList()
        val out = ArrayList<EventsRow>(today.size + upcoming.size + 2)
        if (today.isNotEmpty()) {
            if (upcoming.isNotEmpty()) out += EventsRow.Header(Section.TODAY)
            for (e in today) out += EventsRow.Today(e)
        }
        if (upcoming.isNotEmpty()) {
            out += EventsRow.Header(Section.UPCOMING)
            for (u in upcoming) out += EventsRow.Upcoming(u)
        }
        return out
    }

    // ── Measuring ────────────────────────────────────────────────────────────

    /** What [row] costs in the band: a header is shorter than an event row. */
    fun heightOf(row: EventsRow, headerPx: Int, rowPx: Int): Int =
        if (row is EventsRow.Header) headerPx else rowPx

    /** How many pages [rows] needs in a band of [bandPx]. Always at least one — an empty list is
     *  one empty page, which is the page the empty line is drawn on. */
    fun pageCount(rows: List<EventsRow>, bandPx: Int, headerPx: Int, rowPx: Int): Int =
        pages(rows, bandPx, headerPx, rowPx).size

    /** The rows on [page]; empty when the page is past the end (the screen clamps first, but a
     *  helper that throws on a stale page number is a helper that crashes on a race). */
    fun pageOf(rows: List<EventsRow>, page: Int, bandPx: Int, headerPx: Int, rowPx: Int): List<EventsRow> {
        val pages = pages(rows, bandPx, headerPx, rowPx)
        return pages.getOrNull(page).orEmpty()
    }

    /** [page] brought inside the real page range. */
    fun clampPage(page: Int, rows: List<EventsRow>, bandPx: Int, headerPx: Int, rowPx: Int): Int =
        page.coerceIn(0, pageCount(rows, bandPx, headerPx, rowPx) - 1)

    /**
     * Every page, in order — the one walk both [pageOf] and [pageCount] answer from, so the count
     * and the contents can never disagree about where a page ends.
     *
     * Greedy by measured height: a row goes on the page it fits on, and the page ends the moment
     * the next row would overflow the band. **Never a half row.**
     *
     * One refinement over plain greed: a page never *ends* on a header. A label with its list on
     * the next page is a label about nothing, so the header is carried over with the row it
     * labels — but only when the page it leaves still holds something, which is what keeps the
     * walk terminating on a band too short for a header plus a row.
     */
    private fun pages(rows: List<EventsRow>, bandPx: Int, headerPx: Int, rowPx: Int): List<List<EventsRow>> {
        if (rows.isEmpty()) return listOf(emptyList())
        val band = bandPx.coerceAtLeast(1)
        val out = ArrayList<List<EventsRow>>()
        var page = ArrayList<EventsRow>()
        var used = 0
        for (row in rows) {
            val h = heightOf(row, headerPx, rowPx)
            if (page.isNotEmpty() && used + h > band) {
                var carried: EventsRow? = null
                if (page.size > 1 && page.last() is EventsRow.Header) {
                    carried = page.removeAt(page.lastIndex)
                }
                out += page
                page = ArrayList()
                used = 0
                if (carried != null) {
                    page += carried
                    used += heightOf(carried, headerPx, rowPx)
                }
            }
            page += row
            used += h
        }
        out += page
        return out
    }
}
