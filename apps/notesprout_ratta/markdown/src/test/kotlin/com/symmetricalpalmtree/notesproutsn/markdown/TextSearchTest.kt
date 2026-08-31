package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Find, replace-all, and the word count — offsets and caret arithmetic in particular. */
class TextSearchTest {

    // ── matches ───────────────────────────────────────────────────────────────

    @Test
    fun everyHitIsFoundRegardlessOfCase() {
        val ms = TextSearch.matches("The cat sat. The CAT ran. Concatenate.", "cat")
        assertEquals(listOf(4, 17, 29), ms.map { it.start })
        assertEquals(listOf(7, 20, 32), ms.map { it.end })
    }

    @Test
    fun hitsDoNotOverlap() {
        // "aaaa" holds "aa" at 0, 1 and 2; a non-overlapping walk takes 0 and 2.
        assertEquals(listOf(0, 2), TextSearch.matches("aaaa", "aa").map { it.start })
    }

    @Test
    fun anEmptyQueryOrAMissingOneFindsNothing() {
        assertEquals(0, TextSearch.matches("anything", "").size)
        assertEquals(0, TextSearch.matches("anything", "zebra").size)
        assertEquals(0, TextSearch.matches("", "zebra").size)
    }

    @Test
    fun aQueryLongerThanTheTextFindsNothing() {
        assertEquals(0, TextSearch.matches("ab", "abcdef").size)
    }

    @Test
    fun hitsAtBothEndsAreFound() {
        assertEquals(listOf(0, 5), TextSearch.matches("cat, cat", "cat").map { it.start })
    }

    // ── navigation ────────────────────────────────────────────────────────────

    @Test
    fun nextTakesTheHitAtOrAfterThePositionAndWrapsPastTheLast() {
        val ms = TextSearch.matches("cat cat cat", "cat")   // starts 0, 4, 8
        assertEquals(0, TextSearch.nextFrom(ms, 0))
        assertEquals(1, TextSearch.nextFrom(ms, 1))
        assertEquals(1, TextSearch.nextFrom(ms, 4))
        assertEquals(2, TextSearch.nextFrom(ms, 5))
        assertEquals(0, TextSearch.nextFrom(ms, 9))          // wrapped round
        assertEquals(-1, TextSearch.nextFrom(emptyList(), 0))
    }

    @Test
    fun previousTakesTheHitStrictlyBeforeThePositionAndWrapsToTheLast() {
        val ms = TextSearch.matches("cat cat cat", "cat")
        assertEquals(2, TextSearch.previousFrom(ms, 0))      // wrapped round
        assertEquals(0, TextSearch.previousFrom(ms, 4))
        assertEquals(1, TextSearch.previousFrom(ms, 8))
        assertEquals(2, TextSearch.previousFrom(ms, 11))
        assertEquals(-1, TextSearch.previousFrom(emptyList(), 3))
    }

    // ── replaceAll ────────────────────────────────────────────────────────────

    @Test
    fun everyHitIsReplacedAndCounted() {
        val r = TextSearch.replaceAll("cat, CAT, cattle", "cat", "dog", caret = 0)
        assertEquals("dog, dog, dogtle", r.text)
        assertEquals(3, r.count)
    }

    @Test
    fun aReplacementContainingTheQueryCannotLoop() {
        val r = TextSearch.replaceAll("a b a", "a", "aa", caret = 0)
        assertEquals("aa b aa", r.text)
        assertEquals(2, r.count)
    }

    @Test
    fun theCaretIsCarriedByReplacementsAheadOfIt() {
        // The caret sits at the start of the second hit — behind it lies one replacement, five
        // characters longer than what it replaced.
        val r = TextSearch.replaceAll("cat, cat end", "cat", "elephant", caret = 5)
        assertEquals("elephant, elephant end", r.text)
        assertEquals(10, r.caret)
    }

    @Test
    fun aCaretBeforeEveryHitDoesNotMove() {
        val r = TextSearch.replaceAll("ab cat cat", "cat", "elephant", caret = 1)
        assertEquals("ab elephant elephant", r.text)
        assertEquals(1, r.caret)
    }

    @Test
    fun aCaretInsideAHitLandsAtThatReplacementsEnd() {
        val r = TextSearch.replaceAll("xxcatxx", "cat", "dog", caret = 3)
        assertEquals("xxdogxx", r.text)
        assertEquals(5, r.caret)
    }

    @Test
    fun aCaretInsideALaterHitIsMeasuredInTheNewText() {
        // The hit the caret is in starts at 4 in the old text and at 9 in the new one; the end of
        // its replacement is the only position that means anything afterwards.
        val r = TextSearch.replaceAll("cat cat", "cat", "elephant", caret = 5)
        assertEquals("elephant elephant", r.text)
        assertEquals(17, r.caret)
    }

    @Test
    fun aCaretInsideALaterHitFollowsAShrinkingReplacementToo() {
        val r = TextSearch.replaceAll("elephant elephant", "elephant", "cat", caret = 12)
        assertEquals("cat cat", r.text)
        assertEquals(7, r.caret)
    }

    @Test
    fun aCaretPastTheEndOfAShrunkenTextIsPulledBackIn() {
        // Deleting every hit can leave the carried caret beyond the new length; it is coerced.
        val r = TextSearch.replaceAll("cat cat", "cat", "", caret = 7)
        assertEquals(" ", r.text)
        assertEquals(2, r.count)
        assertEquals(1, r.caret)
    }

    @Test
    fun noHitsLeavesTheTextAndTheCaretAlone() {
        val r = TextSearch.replaceAll("hello", "zebra", "dog", caret = 2)
        assertEquals("hello", r.text)
        assertEquals(0, r.count)
        assertEquals(2, r.caret)
    }

    // ── counts ────────────────────────────────────────────────────────────────

    @Test
    fun wordsAreMaximalRunsOfNonWhitespace() {
        assertEquals(0 to 0, TextSearch.counts(""))
        assertEquals(0 to 3, TextSearch.counts("   "))
        assertEquals(2 to 11, TextSearch.counts("hello world"))
        assertEquals(4 to 22, TextSearch.counts("one\ttwo\nthree   four  "))
    }

    @Test
    fun markdownMarkersAreCountedAsPartOfTheirWord() {
        // The source is what is being measured, punctuation and markup included.
        assertEquals(3 to 16, TextSearch.counts("**bold** _it_ x."))
    }
}
