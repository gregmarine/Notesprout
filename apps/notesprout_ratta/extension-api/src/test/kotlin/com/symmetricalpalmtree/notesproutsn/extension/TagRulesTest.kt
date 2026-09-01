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
    @Test
    fun normalizedTextCarriesNoSeparators() {
        val d = TagRules.display("a\tb\nc  d")
        assertFalse('\t' in d)
        assertFalse('\n' in d)
        assertEquals("a b c d", d)
    }
}
