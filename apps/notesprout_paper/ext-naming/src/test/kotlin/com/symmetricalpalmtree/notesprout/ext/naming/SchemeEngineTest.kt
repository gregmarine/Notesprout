package com.symmetricalpalmtree.notesprout.ext.naming

import com.symmetricalpalmtree.notesprout.ext.naming.SchemeEngine.Error
import com.symmetricalpalmtree.notesprout.ext.naming.SchemeEngine.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SchemeEngineTest {

    /** 2026-08-16 14:30:05 in the default zone (SimpleDateFormat formats in the default zone). */
    private val now: Long = SimpleDateFormat("yyyyMMdd HHmmss", Locale.ROOT)
        .apply { timeZone = TimeZone.getDefault() }
        .parse("20260816 143005")!!.time

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
    }

    // ── expand ───────────────────────────────────────────────────────────────

    @Test fun `expands date and time with a fixed clock`() {
        assertEquals("Meeting 20260816 143005", SchemeEngine.expand("Meeting {date} {time}", now, emptyList()))
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
}
