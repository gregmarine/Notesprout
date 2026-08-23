package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.library.SchemeEngine.Error
import com.symmetricalpalmtree.notesproutsn.library.SchemeEngine.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * The scheme language, end to end. Everything is computed in the **default time zone**, because
 * that is where `SimpleDateFormat` formats: the fixed clock is built with a [Calendar] and the
 * expected month / weekday names are read back from the same calendar, so the suite gives the same
 * answer in every zone rather than passing only where it was written.
 */
class SchemeEngineTest {

    /** 2026-08-16 (a Sunday) 14:30:05 in the default zone. */
    private val calendar: Calendar = Calendar.getInstance(Locale.ROOT).apply {
        clear()
        set(2026, Calendar.AUGUST, 16, 14, 30, 5)
    }
    private val now: Long = calendar.timeInMillis

    private fun errorOf(scheme: String): Error? = SchemeEngine.validate(scheme)?.error

    // ── parse ────────────────────────────────────────────────────────────────

    @Test fun `parses each token`() {
        assertEquals(
            listOf(Part.Literal("Meeting "), Part.Date, Part.Literal(" "), Part.Time, Part.Literal(" "), Part.Counter(2)),
            SchemeEngine.parse("Meeting {date} {time} {n:2}"),
        )
        assertEquals(listOf(Part.Counter(1)), SchemeEngine.parse("{n}"))
        assertEquals(listOf(Part.Counter(9)), SchemeEngine.parse("{n:9}"))
    }

    @Test fun `parses the v2 date-part tokens`() {
        assertEquals(
            listOf(Part.Year, Part.Literal("-"), Part.Month, Part.Literal("-"), Part.Day),
            SchemeEngine.parse("{year}-{month}-{day}"),
        )
        assertEquals(
            listOf(Part.MonthName, Part.Literal(" "), Part.Weekday, Part.Literal(" "), Part.Mon, Part.Literal(" "), Part.Wd),
            SchemeEngine.parse("{monthname} {weekday} {mon} {wd}"),
        )
    }

    @Test fun `literal only is fine`() {
        assertEquals(listOf(Part.Literal("Ideas")), SchemeEngine.parse("Ideas"))
        assertNull(errorOf("Ideas"))
    }

    @Test fun `unknown token`() {
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("Bad {foo}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{Date}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{n:0}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{n:10}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("a } b"))
        assertEquals("{foo}", SchemeEngine.validate("Bad {foo}")!!.detail)
    }

    @Test fun `v2 token names are exact`() {
        // Near-misses of the new tokens are unknown, not silently accepted.
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{months}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{weekdays}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{Mon}"))
        assertEquals(Error.UNKNOWN_TOKEN, errorOf("{hour}"))
    }

    @Test fun `unclosed brace`() {
        assertEquals(Error.UNCLOSED_BRACE, errorOf("Note {date"))
    }

    @Test fun `counter twice`() {
        assertEquals(Error.COUNTER_TWICE, errorOf("{n} {n}"))
        assertEquals(Error.COUNTER_TWICE, errorOf("{n:2}-{n}"))
    }

    @Test fun `illegal literal`() {
        assertEquals(Error.ILLEGAL_CHAR, errorOf("A/B"))
        assertEquals(Error.ILLEGAL_CHAR, errorOf("Note: {date}"))
        assertEquals(Error.ILLEGAL_CHAR, errorOf("Ünïcode"))
    }

    @Test fun `empty result`() {
        assertEquals(Error.EMPTY, errorOf(""))
        assertEquals(Error.EMPTY, errorOf("."))
        assertEquals(Error.EMPTY, errorOf(".."))
        assertEquals(Error.EMPTY, errorOf("   "))
    }

    @Test fun `too long`() {
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(101)))
        assertNull(errorOf("a".repeat(100)))
        // The expansion counts too: {date} is 6 chars of scheme but 8 of name.
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(93) + "{date}"))
        assertNull(errorOf("a".repeat(92) + "{date}"))
    }

    @Test fun `the cap is counted on the expansion, not the source`() {
        // The only tokens that *grow* are {date} (6 → 8) and a wide counter ({n:9} is 5 → 9); for
        // every other token the source is the longer of the two, so the raw-length check catches it
        // first. These are the cases where the two counts genuinely disagree.
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(95) + "{n:9}"))   // raw 100, name 104
        assertNull(errorOf("a".repeat(91) + "{n:9}"))                     // raw  96, name 100
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(95) + "{n:6}"))   // raw 100, name 101
        assertNull(errorOf("a".repeat(94) + "{n:6}"))                     // raw  99, name 100
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(85) + "{date}{date}"))  // raw 97, name 101
        assertNull(errorOf("a".repeat(84) + "{date}{date}"))                    // raw 96, name 100
    }

    @Test fun `the shrinking tokens are not charged their source length`() {
        // …and the mirror case: a token whose name is shorter than its source must not be refused
        // just because the scheme reaches the cap.
        assertNull(errorOf("a".repeat(89) + "{monthname}"))   // raw 100, name  98
        assertNull(errorOf("a".repeat(91) + "{weekday}"))     // raw 100, name 100
        assertNull(errorOf("a".repeat(95) + "{mon}"))         // raw 100, name  98
        assertNull(errorOf("a".repeat(96) + "{wd}"))          // raw 100, name  99
        assertNull(errorOf("a".repeat(94) + "{year}"))        // raw 100, name  98
        assertNull(errorOf("a".repeat(93) + "{month}"))       // raw 100, name  95
        assertNull(errorOf("a".repeat(95) + "{day}"))         // raw 100, name  97
        // One character more of scheme and the raw cap is what refuses it.
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(90) + "{monthname}"))
        assertEquals(Error.TOO_LONG, errorOf("a".repeat(92) + "{weekday}"))
    }

    // ── expand ───────────────────────────────────────────────────────────────

    @Test fun `expands date and time with a fixed clock`() {
        assertEquals("Meeting 20260816 143005", SchemeEngine.expand("Meeting {date} {time}", now, emptyList()))
    }

    @Test fun `expands the year month day parts`() {
        assertEquals("2026-08-16", SchemeEngine.expand("{year}-{month}-{day}", now, emptyList()))
        // …and they compose to exactly what {date} gives.
        assertEquals(
            SchemeEngine.expand("{date}", now, emptyList()),
            SchemeEngine.expand("{year}{month}{day}", now, emptyList()),
        )
    }

    @Test fun `expands the month and weekday names`() {
        // August 16 2026 is a Sunday — asserted here so a wrong clock fails loudly.
        assertEquals(Calendar.SUNDAY, calendar.get(Calendar.DAY_OF_WEEK))
        assertEquals("August", SchemeEngine.expand("{monthname}", now, emptyList()))
        assertEquals("Sunday", SchemeEngine.expand("{weekday}", now, emptyList()))
        assertEquals("Aug", SchemeEngine.expand("{mon}", now, emptyList()))
        assertEquals("Sun", SchemeEngine.expand("{wd}", now, emptyList()))
        assertEquals("Journal Aug Sun 16", SchemeEngine.expand("Journal {mon} {wd} {day}", now, emptyList()))
    }

    @Test fun `counter with no siblings starts at 1`() {
        assertEquals("Meeting 20260816 01", SchemeEngine.expand("Meeting {date} {n:2}", now, emptyList()))
        assertEquals("Note 1", SchemeEngine.expand("Note {n}", now, emptyList()))
    }

    @Test fun `counter is highest plus one, gaps ignored`() {
        val siblings = listOf("Meeting 20260816 01", "Meeting 20260816 07", "Meeting 20260816 03", "Other 20260816 99")
        assertEquals("Meeting 20260816 08", SchemeEngine.expand("Meeting {date} {n:2}", now, siblings))
    }

    @Test fun `counter continues across days - date position is a wildcard`() {
        val siblings = listOf("Meeting 20260815 01", "Meeting 20260815 02")
        assertEquals("Meeting 20260816 03", SchemeEngine.expand("Meeting {date} {n:2}", now, siblings))
    }

    @Test fun `time position is a wildcard too`() {
        val siblings = listOf("Note 093000 5")
        assertEquals("Note 143005 6", SchemeEngine.expand("Note {time} {n}", now, siblings))
    }

    @Test fun `counter continues across months - monthname is a wildcard`() {
        val siblings = listOf("Log January 04", "Log March 09", "Log December 02")
        assertEquals("Log August 10", SchemeEngine.expand("Log {monthname} {n:2}", now, siblings))
    }

    @Test fun `counter continues across weekdays and abbreviations`() {
        assertEquals("Day Sun 4", SchemeEngine.expand("Day {wd} {n}", now, listOf("Day Tue 3", "Day Fri 1")))
        assertEquals("Wk Sunday 8", SchemeEngine.expand("Wk {weekday} {n}", now, listOf("Wk Wednesday 7")))
        assertEquals("M Aug 3", SchemeEngine.expand("M {mon} {n}", now, listOf("M Feb 2", "M Dec 1")))
        assertEquals("Y 2026 6", SchemeEngine.expand("Y {year} {n}", now, listOf("Y 2024 5")))
        assertEquals("D 08-16 3", SchemeEngine.expand("D {month}-{day} {n}", now, listOf("D 01-31 2")))
    }

    @Test fun `a name whose word is not a real month does not count`() {
        // "Augustus" is not one of the twelve; the skeleton is anchored, so it cannot match.
        val siblings = listOf("Log Augustus 40", "Log Aug 40")
        assertEquals("Log August 01", SchemeEngine.expand("Log {monthname} {n:2}", now, siblings))
    }

    @Test fun `padded siblings and unpadded siblings both count`() {
        assertEquals("Note 010", SchemeEngine.expand("Note {n:3}", now, listOf("Note 9")))
        assertEquals("Note 010", SchemeEngine.expand("Note {n:3}", now, listOf("Note 009")))
    }

    @Test fun `width does not truncate`() {
        assertEquals("Note 100", SchemeEngine.expand("Note {n:2}", now, listOf("Note 99")))
    }

    @Test fun `skeleton is exact on literals and anchored`() {
        val siblings = listOf("XMeeting 20260816 05", "Meeting 20260816 05 copy", "meeting 20260816 05")
        assertEquals("Meeting 20260816 01", SchemeEngine.expand("Meeting {date} {n:2}", now, siblings))
    }

    @Test fun `literal with regex metacharacters is quoted`() {
        assertEquals("a.b 3", SchemeEngine.expand("a.b {n}", now, listOf("a.b 2", "axb 9")))
    }

    @Test fun `no counter ignores siblings`() {
        assertEquals("Daily 20260816", SchemeEngine.expand("Daily {date}", now, listOf("Daily 20260816")))
    }

    @Test fun `the counter is the only capture group however many name tokens precede it`() {
        val parts = SchemeEngine.parse("{monthname} {weekday} {mon} {wd} {n:2}")
        val m = SchemeEngine.skeleton(parts).matchEntire("August Sunday Aug Sun 42")
        assertEquals("42", m!!.groupValues[1])
    }

    // ── the expansion is always a name the library would accept ──────────────

    @Test fun `every expansion satisfies the core name rule`() {
        val schemes = listOf(
            "Meeting {date} {n:2}", "{year}-{month}-{day}", "{monthname} {weekday}",
            "{mon} {wd} {time}", "Ideas", "{n:9}",
        )
        for (s in schemes) {
            val name = SchemeEngine.expand(s, now, emptyList())
            assertTrue("'$s' expanded to an invalid name: '$name'", NameRules.isValid(name))
            assertTrue("'$s' expanded past the cap: '$name'", name.length <= SchemeEngine.MAX_SCHEME_CHARS)
        }
    }
}
