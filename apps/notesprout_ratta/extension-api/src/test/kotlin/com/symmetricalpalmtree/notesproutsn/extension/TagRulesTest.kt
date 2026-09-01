package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The one definition of "the same tag" (arc 21 / W1). Both sides of the seam ask these questions and
 * must get identical answers, so every rule the wizard fixed is pinned here.
 */
class TagRulesTest {

    @Test
    fun displayTrimsEndsAndCollapsesRuns() {
        assertEquals("Reading List", TagRules.display("  Reading   List  "))
        assertEquals("a b", TagRules.display("a\t\t b"))
        assertEquals("a b", TagRules.display("a\n b"))
        assertEquals("", TagRules.display("   "))
        assertEquals("", TagRules.display(""))
        // Already normal text is returned unchanged — the round trip has to be a fixed point.
        assertEquals("Reading List", TagRules.display("Reading List"))
    }

    @Test
    fun displayKeepsCase() {
        assertEquals("Reading List", TagRules.display("Reading List"))
        assertEquals("READING", TagRules.display("READING"))
    }

    @Test
    fun identityFoldsCaseAndWhitespaceTogether() {
        assertEquals(TagRules.identityKey("  Reading   List "), TagRules.identityKey("reading list"))
        assertEquals("reading list", TagRules.identityKey("Reading List"))
    }

    /** Locale-neutral, never the device locale: a Turkish device must not decide "I" and "ı" are one
     *  tag when the same library on another device says they are two. */
    @Test
    fun identityIsLocaleNeutral() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("title", TagRules.identityKey("TITLE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun validityIsMeasuredOnTheNormalizedForm() {
        assertTrue(TagRules.isValid("reading list"))
        assertFalse(TagRules.isValid("   "))
        assertFalse(TagRules.isValid(""))
        // 64 is the cap, and it counts what would be STORED — so padding does not push a legal tag over.
        val exactly = "x".repeat(ExtensionContract.MAX_TAG_CHARS)
        assertTrue(TagRules.isValid("   $exactly   "))
        assertFalse(TagRules.isValid(exactly + "x"))
    }

    /** The codec relies on this: nothing that has been normalized can carry a tab or a newline, which
     *  is why records are dropped rather than escaped. */
    // ── isId (arc 22 / X3 — carried over from W4's CompactId.isId when the codec went) ──────

    /** The one shape a tag id, a notebook id or a page id may take, at every door on both sides. */
    @Test
    fun isIdAcceptsACanonicalUuid() {
        assertTrue(TagRules.isId("11111111-1111-4111-8111-111111111111"))
        assertTrue(TagRules.isId(java.util.UUID.randomUUID().toString()))
        assertTrue(TagRules.isId(java.util.UUID(0L, 0L).toString()))
        assertTrue(TagRules.isId(java.util.UUID(-1L, -1L).toString()))
    }

    /**
     * `UUID.fromString` is famously lenient — it accepts `1-2-3-4-5` and pads it out — so the parse
     * is round-tripped through `toString()` and only the canonical 8-4-4-4-12 form gets through.
     */
    @Test
    fun isIdRefusesEverythingThatIsNotOne() {
        for (bad in listOf(
            "",
            " ",
            "n1",
            "1-2-3-4-5",
            "11111111111141118111111111111111",                 // no dashes
            "{11111111-1111-4111-8111-111111111111}",           // braces
            "urn:uuid:11111111-1111-4111-8111-111111111111",
            "11111111-1111-4111-8111-11111111111",              // one short
            "11111111-1111-4111-8111-1111111111111",            // one long
            "zzzzzzzz-1111-4111-8111-111111111111",             // not hex
            "11111111-1111-4111-8111-111111111111 ",            // not trimmed into acceptance
            "11111111-1111-4111-8111-111111111111\u0000",       // a NUL is not an id character
        )) {
            assertFalse("accepted '$bad'", TagRules.isId(bad))
        }
    }

    /**
     * Hex **case is not significant** — arc 21's `CompactId.isId` compared the re-rendered canonical
     * form case-insensitively and this carries that over unchanged. It matters: arc 16's
     * `SafeImportId` accepts upper-case hex out of a stranger's `.soil`, so an imported notebook may
     * legitimately carry upper-case ids and its pages must still be taggable.
     */
    @Test
    fun isIdIgnoresHexCase() {
        assertTrue(TagRules.isId("AAAAAAAA-1111-4111-8111-111111111111"))
        assertTrue(TagRules.isId("aaaaaaaa-1111-4111-8111-111111111111"))
    }

    @Test
    fun normalizedTextCarriesNoSeparators() {
        val d = TagRules.display("a\tb\nc  d")
        assertFalse('\t' in d)
        assertFalse('\n' in d)
        assertEquals("a b c d", d)
    }
}
