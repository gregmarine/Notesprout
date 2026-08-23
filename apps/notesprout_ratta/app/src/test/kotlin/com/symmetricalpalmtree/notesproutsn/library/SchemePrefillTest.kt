package com.symmetricalpalmtree.notesproutsn.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * The prefill a folder's naming scheme yields for a create (K3) — shared by the library's
 * +Notebook and the link picker's New notebook.
 *
 * Two behaviours matter more than the expansion itself, because both are invisible on screen and
 * only provable here: the sibling names are read **only** when the scheme actually holds a counter,
 * and anything the library would refuse comes back as null so the caller's own default stands.
 */
class SchemePrefillTest {

    /** 2026-08-16 (a Sunday) 14:30:05 in the default zone — the [SchemeEngineTest] clock. */
    private val now: Long = Calendar.getInstance(Locale.ROOT).apply {
        clear()
        set(2026, Calendar.AUGUST, 16, 14, 30, 5)
    }.timeInMillis

    /** A siblings source that fails the test if it is ever asked. */
    private val forbidden: suspend () -> List<String> = { error("siblings must not be read") }

    @Test
    fun `no scheme is no prefill`() = runBlocking {
        assertNull(SchemePrefill.expand(null, now, forbidden))
    }

    @Test
    fun `a scheme without a counter expands without ever reading the siblings`() = runBlocking {
        var asked = false
        val name = SchemePrefill.expand("Journal {year}-{month}", now) { asked = true; emptyList() }
        assertEquals("Journal 2026-08", name)
        assertFalse("a counter-free scheme must not pay for the sibling read", asked)
    }

    @Test
    fun `a literal-only scheme is its own prefill`() = runBlocking {
        assertEquals("Ideas", SchemePrefill.expand("Ideas", now, forbidden))
    }

    @Test
    fun `a counter continues from the highest matching sibling`() = runBlocking {
        val siblings = listOf("Log 003", "Log 007", "Log 002", "Notes 099", "Log seven")
        assertEquals("Log 008", SchemePrefill.expand("Log {n:3}", now) { siblings })
    }

    @Test
    fun `a counter with no matching siblings starts at one`() = runBlocking {
        assertEquals("Log 001", SchemePrefill.expand("Log {n:3}", now) { listOf("Other 004") })
    }

    @Test
    fun `a scheme that does not parse is a null prefill, never a throw`() = runBlocking {
        assertNull(SchemePrefill.expand("Bad {foo}", now, forbidden))
        assertNull(SchemePrefill.expand("Unclosed {date", now, forbidden))
        assertNull(SchemePrefill.expand("{n} and {n}", now, forbidden))
    }

    @Test
    fun `a scheme the library would refuse as a name is a null prefill`() = runBlocking {
        // "." is reserved and "/" is off the charset (NameRules), so a scheme that could only ever
        // expand to such a name yields nothing — the caller's own default stands rather than a name
        // the create screen would reject.
        assertNull(SchemePrefill.expand(".", now, forbidden))
        assertNull(SchemePrefill.expand("bad/name", now, forbidden))
    }

    @Test
    fun `a counter that outgrew its declared width falls back to the default`() = runBlocking {
        // The one over-cap case a scheme cannot be checked for at parse time: the source is 100
        // characters and its declared worst case fits, but the next number is six digits wide.
        val literal = "a".repeat(95)
        val scheme = "$literal{n:1}"
        assertNull(SchemeEngine.validate(scheme))          // the scheme itself is acceptable…
        assertEquals("${literal}1", SchemePrefill.expand(scheme, now) { emptyList() })
        // …but with that sibling the expansion is 101 characters, past the cap: no prefill.
        assertNull(SchemePrefill.expand(scheme, now) { listOf("${literal}99999") })
    }
}
