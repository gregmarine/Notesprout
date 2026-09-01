package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher two screens share (arc 20 / Q1). The tests are written the way the decision was made:
 * **what must be findable**, **what must not be** (typos — declined on purpose), and **what must
 * come first** when several names answer.
 */
class FuzzyRankTest {

    private fun tier(name: String, query: String): Int? = FuzzyRank.match(name, query)?.tier

    // ── Tiers ────────────────────────────────────────────────────────────────

    @Test
    fun `an exact name is the strongest match`() {
        assertEquals(FuzzyRank.TIER_EXACT, tier("Meeting", "meeting"))
        assertEquals(FuzzyRank.TIER_EXACT, tier("meeting", "  MEETING  "))
    }

    @Test
    fun `a prefix beats a word start beats a substring`() {
        assertEquals(FuzzyRank.TIER_PREFIX, tier("Meeting Notes", "meet"))
        assertEquals(FuzzyRank.TIER_WORD_START, tier("Meeting Notes", "notes"))
        assertEquals(FuzzyRank.TIER_SUBSTRING, tier("Meeting Notes", "eeting"))
    }

    /** A whole-query hit that starts a word beats one buried mid-word, wherever each one sits. */
    @Test
    fun `a later word-start hit outranks an earlier mid-word one`() {
        assertEquals(FuzzyRank.TIER_WORD_START, tier("Denotes the Notes", "notes"))
    }

    @Test
    fun `letters in order with gaps are a subsequence match`() {
        assertEquals(FuzzyRank.TIER_SUBSEQUENCE, tier("Meeting Notes", "mtg"))
        assertEquals(FuzzyRank.TIER_SUBSEQUENCE, tier("Blog 20251008", "blg"))
    }

    // ── What must NOT match ──────────────────────────────────────────────────

    /**
     * Fuzzy is subsequence, not edit distance — offered and declined (arc 20). If either of these
     * ever starts matching, someone has added typo tolerance without the decision that requires.
     *
     * The line falls where subsequence puts it, which is worth knowing: a **dropped** letter still
     * finds its name (a subsequence is exactly "these letters, in order, some missing"), while a
     * **swapped or wrong** one does not.
     */
    @Test
    fun `a wrong or swapped letter does not match`() {
        assertNull(FuzzyRank.match("Blog", "bolg"))
        assertNull(FuzzyRank.match("Meeting Notes", "meelting"))
    }

    @Test
    fun `a dropped letter still matches, as a subsequence`() {
        assertEquals(FuzzyRank.TIER_SUBSEQUENCE, tier("Meeting Notes", "meting"))
    }

    @Test
    fun `out-of-order letters do not match`() {
        assertNull(FuzzyRank.match("Grid", "gird"))
    }

    @Test
    fun `a query longer than the name never matches`() {
        assertNull(FuzzyRank.match("Grid", "gridiron"))
    }

    /** No wildcards, ever. `_` and `%` are ordinary characters here — the family's name charset
     *  allows `_`, and under the old SQL `LIKE` an unescaped one matched anything at all. */
    @Test
    fun `punctuation in the query is literal`() {
        assertTrue(FuzzyRank.matches("my_grid", "my_grid"))
        assertFalse(FuzzyRank.matches("myXgrid", "my_grid"))
        assertFalse(FuzzyRank.matches("anything", "%"))
    }

    @Test
    fun `a blank query matches nothing and is not runnable`() {
        assertNull(FuzzyRank.match("Anything", "   "))
        assertFalse(FuzzyRank.isRunnable(""))
        assertFalse(FuzzyRank.isRunnable(" \t\n"))
        assertTrue(FuzzyRank.isRunnable(" a "))
    }

    // ── Word starts ──────────────────────────────────────────────────────────

    @Test
    fun `separators, camel case and the first digit all start a word`() {
        // Each of these is the query's letters landing on word starts, which is what lifts a
        // deliberate abbreviation above an accidental one.
        val separators = FuzzyRank.match("my_grid paper.v2-final", "mgpvf")!!
        assertEquals(5, separators.wordStarts)
        assertEquals(2, FuzzyRank.match("BlogPost", "bp")!!.wordStarts)
        assertEquals(2, FuzzyRank.match("Blog2025", "b2")!!.wordStarts)
    }

    @Test
    fun `word-start hits break a tie between two subsequence matches`() {
        val deliberate = FuzzyRank.match("Meeting Team Group", "mtg")!!
        val accidental = FuzzyRank.match("Amount Given", "mtg")!!
        assertTrue(deliberate < accidental)
    }

    @Test
    fun `a tighter run wins when the word starts are equal`() {
        val tight = FuzzyRank.match("Motog", "mtg")!!
        val loose = FuzzyRank.match("Moootog", "mtg")!!
        assertEquals(tight.wordStarts, loose.wordStarts)
        assertTrue(tight < loose)
    }

    // ── rank ─────────────────────────────────────────────────────────────────

    @Test
    fun `rank drops non-matches and orders best first`() {
        val names = listOf("Groceries", "Meeting Notes", "Meet", "Meeting")
        assertEquals(
            listOf("Meet", "Meeting", "Meeting Notes"),
            FuzzyRank.rank(names, "meet") { it },
        )
    }

    /** Length is the last resort, not the rule: "Meeting Notes" holds the query whole at a word
     *  start and is the longer name — it still beats a scattered subsequence in a shorter one. */
    @Test
    fun `a better match beats a shorter name`() {
        assertEquals(
            listOf("Meeting Notes", "No Time Ever"),
            FuzzyRank.rank(listOf("No Time Ever", "Meeting Notes"), "note") { it },
        )
    }

    @Test
    fun `ties are broken by name so the same search always gives the same page`() {
        val names = listOf("zeta", "beta", "meta")
        assertEquals(listOf("beta", "meta", "zeta"), FuzzyRank.rank(names, "a") { it })
    }

    @Test
    fun `an unrunnable query ranks nothing at all`() {
        assertTrue(FuzzyRank.rank(listOf("Anything"), "  ") { it }.isEmpty())
    }
}
