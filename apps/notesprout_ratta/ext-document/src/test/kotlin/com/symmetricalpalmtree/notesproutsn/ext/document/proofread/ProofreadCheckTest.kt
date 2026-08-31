package com.symmetricalpalmtree.notesproutsn.ext.document.proofread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the incremental-recheck arithmetic: how an edit's range grows to a checkable region, when a
 * whole-document pass is forced, and which words in a region earn a flag.
 */
class ProofreadCheckTest {

    // ── lineRegion ────────────────────────────────────────────────────────────

    @Test
    fun region_grows_to_whole_lines() {
        val text = "first line\nsecond line\nthird line"
        val region = ProofreadCheck.lineRegion(text, changedStart = 13, changedEnd = 15)
        assertEquals(11, region.start)
        assertEquals(22, region.end)
        assertEquals("second line", text.substring(region.start, region.end))
    }

    @Test
    fun region_spanning_a_break_takes_both_lines() {
        val text = "one\ntwo\nthree"
        val region = ProofreadCheck.lineRegion(text, changedStart = 2, changedEnd = 5)
        assertEquals(0, region.start)
        assertEquals(7, region.end)
    }

    @Test
    fun region_clamps_to_the_text() {
        val region = ProofreadCheck.lineRegion("short", changedStart = -3, changedEnd = 99)
        assertEquals(0, region.start)
        assertEquals(5, region.end)
    }

    @Test
    fun region_of_empty_text_is_empty() {
        val region = ProofreadCheck.lineRegion("", 0, 0)
        assertEquals(0, region.start)
        assertEquals(0, region.end)
    }

    @Test
    fun a_backwards_range_collapses_instead_of_inverting() {
        // The editor can hand in an end before the start after an odd batch edit; the region must
        // still be a sane forward span of the line it lands on.
        val text = "one\ntwo\nthree"
        val region = ProofreadCheck.lineRegion(text, changedStart = 6, changedEnd = 1)
        assertEquals(4, region.start)
        assertEquals(7, region.end)
    }

    // ── affectsWholeDocument ──────────────────────────────────────────────────

    @Test
    fun fence_characters_force_a_full_pass() {
        assertTrue(ProofreadCheck.affectsWholeDocument("`"))
        assertTrue(ProofreadCheck.affectsWholeDocument("```kotlin"))
        assertTrue(ProofreadCheck.affectsWholeDocument("~~struck~~"))
        assertFalse(ProofreadCheck.affectsWholeDocument("plain words, punctuation!"))
        assertFalse(ProofreadCheck.affectsWholeDocument(""))
    }

    // ── misspelled ────────────────────────────────────────────────────────────

    private val known = setOf("the", "cat", "sat", "code", "line")

    private fun flags(text: String, region: ProofreadCheck.Region, ignored: Set<String> = emptySet()) =
        ProofreadCheck.misspelled(
            ProofreadTokenizer.wordSpans(text),
            region,
            { it.lowercase() in known },
            { it.lowercase() in ignored },
        )

    @Test
    fun flags_only_unknown_words() {
        val text = "the cat szat"
        val found = flags(text, ProofreadCheck.Region(0, text.length))
        assertEquals(listOf("szat"), found.map { it.word })
        assertEquals(8, found.single().start)
        assertEquals(12, found.single().end)
    }

    @Test
    fun words_outside_the_region_are_not_judged() {
        val text = "wrods here\nthe cat\nwrods again"
        val middle = ProofreadCheck.lineRegion(text, 12, 12)
        assertTrue(flags(text, middle).isEmpty())
    }

    @Test
    fun word_straddling_a_region_edge_is_flagged_whole() {
        val text = "the wrods sat"
        // Region cuts into the middle of "wrods"; the flag still covers all of it.
        val found = flags(text, ProofreadCheck.Region(6, text.length))
        assertEquals(listOf("wrods"), found.map { it.word })
        assertEquals(4, found.single().start)
        assertEquals(9, found.single().end)
    }

    @Test
    fun code_context_is_exact_even_for_a_small_region() {
        val text = "the cat\n```\nwrods inside\n```\nthe cat"
        // A region covering only the code line: its content is not prose, so nothing is flagged.
        val region = ProofreadCheck.lineRegion(text, 13, 13)
        assertTrue(flags(text, region).isEmpty())
    }

    @Test
    fun ignored_words_are_not_flagged() {
        val text = "Szat szat"
        // Ignore is case-insensitive through normalization on both sides.
        assertTrue(flags(text, ProofreadCheck.Region(0, text.length), ignored = setOf("szat")).isEmpty())
    }

    @Test
    fun unjudgeable_words_are_left_alone() {
        // Digits, acronyms, single letters: shouldCheck declines them, so no flag.
        val text = "2nd EPD x"
        assertTrue(flags(text, ProofreadCheck.Region(0, text.length)).isEmpty())
    }

    @Test
    fun a_zero_width_region_still_judges_the_word_it_sits_inside() {
        // A caret-sized region intersects the word around it — the straddle rule again, and what
        // keeps a word being typed from going unjudged. Between words it touches nothing.
        val text = "wrods everywhere"
        assertEquals(listOf("wrods"), flags(text, ProofreadCheck.Region(4, 4)).map { it.word })
        assertTrue(flags(text, ProofreadCheck.Region(5, 5)).isEmpty())
    }
}

/** Pins the dirty-range bookkeeping: earlier edits keep pointing at the same characters. */
class ProofreadDirtyTest {

    @Test
    fun starts_empty_and_clears() {
        val dirty = ProofreadDirty()
        assertTrue(dirty.isEmpty)
        dirty.note(3, 0, 2)
        assertFalse(dirty.isEmpty)
        dirty.clear()
        assertTrue(dirty.isEmpty)
    }

    @Test
    fun consecutive_typing_grows_the_range() {
        val dirty = ProofreadDirty()
        dirty.note(0, 0, 3)   // "abc"
        dirty.note(3, 0, 1)   // "abcd"
        assertEquals(0, dirty.start)
        assertEquals(4, dirty.end)
    }

    @Test
    fun an_edit_before_the_range_shifts_it() {
        val dirty = ProofreadDirty()
        dirty.note(10, 0, 2)  // range [10, 12)
        dirty.note(5, 1, 0)   // delete one char at 5 — tracked chars now sit one to the left
        assertEquals(5, dirty.start)
        assertTrue(dirty.end >= 11)
    }

    @Test
    fun an_insertion_inside_the_range_widens_it() {
        val dirty = ProofreadDirty()
        dirty.note(5, 0, 10)  // range [5, 15)
        dirty.note(8, 0, 2)   // insert 2 inside
        assertEquals(5, dirty.start)
        assertEquals(17, dirty.end)
    }

    @Test
    fun an_edit_after_the_range_extends_without_shifting() {
        val dirty = ProofreadDirty()
        dirty.note(2, 0, 2)   // [2, 4)
        dirty.note(20, 3, 5)  // far later edit
        assertEquals(2, dirty.start)
        assertEquals(25, dirty.end)
    }

    @Test
    fun a_deletion_inside_the_range_never_shrinks_it_below_the_edit() {
        // Outward rounding: over-covering costs a few lookups, under-covering leaves a stale flag.
        val dirty = ProofreadDirty()
        dirty.note(5, 0, 10)  // [5, 15)
        dirty.note(8, 3, 0)   // delete 3 inside
        assertEquals(5, dirty.start)
        assertTrue("end must still cover the edit", dirty.end >= 8)
    }
}
