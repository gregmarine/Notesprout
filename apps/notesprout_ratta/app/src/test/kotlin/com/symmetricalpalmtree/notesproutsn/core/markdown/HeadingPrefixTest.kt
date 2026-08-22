package com.symmetricalpalmtree.notesproutsn.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `text` ↔ level contract: level is authoritative, the prefix is derived, never stacked. */
class HeadingPrefixTest {

    @Test
    fun `prefix per level`() {
        assertEquals("# ", HeadingPrefix.headingPrefix(1))
        assertEquals("### ", HeadingPrefix.headingPrefix(3))
        assertEquals("###### ", HeadingPrefix.headingPrefix(6))
    }

    @Test
    fun `out-of-range levels clamp — flags is a stored column`() {
        assertEquals("# ", HeadingPrefix.headingPrefix(0))
        assertEquals("# ", HeadingPrefix.headingPrefix(-3))
        assertEquals("###### ", HeadingPrefix.headingPrefix(7))
    }

    @Test
    fun `strip removes exactly one heading prefix`() {
        assertEquals("Title", HeadingPrefix.stripHeadingPrefix("## Title"))
        assertEquals("Title", HeadingPrefix.stripHeadingPrefix("###### Title"))
        assertEquals("# nested", HeadingPrefix.stripHeadingPrefix("# # nested"))
    }

    @Test
    fun `strip passes non-heading text through`() {
        assertEquals("plain", HeadingPrefix.stripHeadingPrefix("plain"))
        assertEquals("", HeadingPrefix.stripHeadingPrefix(""))
        // Seven hashes is not a heading (the parser rule) — so it is not a prefix either.
        assertEquals("####### seven", HeadingPrefix.stripHeadingPrefix("####### seven"))
        // No space after the hashes → no prefix.
        assertEquals("#tag", HeadingPrefix.stripHeadingPrefix("#tag"))
    }

    @Test
    fun `strip only touches the start`() {
        assertEquals("a ## b", HeadingPrefix.stripHeadingPrefix("a ## b"))
    }

    @Test
    fun `applyLevel replaces an existing prefix, never stacks`() {
        assertEquals("### Title", HeadingPrefix.applyLevel("# Title", 3))
        assertEquals("# Title", HeadingPrefix.applyLevel("###### Title", 1))
    }

    @Test
    fun `applyLevel prefixes bare text`() {
        assertEquals("## Title", HeadingPrefix.applyLevel("Title", 2))
    }

    @Test
    fun `applyLevel round-trips with strip`() {
        val text = HeadingPrefix.applyLevel("Meeting notes", 4)
        assertEquals("#### Meeting notes", text)
        assertEquals("Meeting notes", HeadingPrefix.stripHeadingPrefix(text))
        assertEquals("## Meeting notes", HeadingPrefix.applyLevel(text, 2))
    }
}
