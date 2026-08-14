package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSearchTest {

    // ── matches ───────────────────────────────────────────────────────────────

    @Test
    fun findsAllMatchesCaseInsensitively() {
        val ms = TextSearch.matches("The cat sat. The CAT ran. Concatenate.", "cat")
        assertEquals(listOf(4, 17, 29), ms.map { it.start })
        assertEquals(3, ms.size)
    }

    @Test
    fun matchesAreNonOverlapping() {
        // "aaaa" contains "aa" at 0,1,2 — non-overlapping search takes 0 and 2 only.
        assertEquals(listOf(0, 2), TextSearch.matches("aaaa", "aa").map { it.start })
    }

    @Test
    fun emptyQueryOrNoHitsYieldsNothing() {
        assertEquals(0, TextSearch.matches("anything", "").size)
        assertEquals(0, TextSearch.matches("anything", "zebra").size)
        assertEquals(0, TextSearch.matches("", "zebra").size)
    }

    // ── navigation ────────────────────────────────────────────────────────────

    @Test
    fun nextWrapsToFirstPastTheLastMatch() {
        val ms = TextSearch.matches("cat cat cat", "cat")   // starts 0, 4, 8
        assertEquals(0, TextSearch.nextFrom(ms, 0))
        assertEquals(1, TextSearch.nextFrom(ms, 1))
        assertEquals(2, TextSearch.nextFrom(ms, 5))
        assertEquals(0, TextSearch.nextFrom(ms, 9))          // wrapped
        assertEquals(-1, TextSearch.nextFrom(emptyList(), 0))
    }

    @Test
    fun previousWrapsToLastBeforeTheFirstMatch() {
        val ms = TextSearch.matches("cat cat cat", "cat")
        assertEquals(2, TextSearch.previousFrom(ms, 0))      // wrapped
        assertEquals(0, TextSearch.previousFrom(ms, 4))
        assertEquals(1, TextSearch.previousFrom(ms, 8))
        assertEquals(-1, TextSearch.previousFrom(emptyList(), 3))
    }

    // ── replaceAll ────────────────────────────────────────────────────────────

    @Test
    fun replacesEveryMatchAndCounts() {
        val r = TextSearch.replaceAll("cat, CAT, cattle", "cat", "dog", caret = 0)
        assertEquals("dog, dog, dogtle", r.text)
        assertEquals(3, r.count)
    }

    @Test
    fun replacementContainingQueryCannotLoop() {
        val r = TextSearch.replaceAll("a b a", "a", "aa", caret = 0)
        assertEquals("aa b aa", r.text)
        assertEquals(2, r.count)
    }

    @Test
    fun caretIsCarriedByEarlierReplacements() {
        // caret after the first "cat" (position 5, on the comma-space boundary)
        val r = TextSearch.replaceAll("cat, cat end", "cat", "elephant", caret = 5)
        assertEquals("elephant, elephant end", r.text)
        assertEquals(10, r.caret)     // 5 + (8 - 3)
    }

    @Test
    fun caretInsideAMatchLandsAtThatReplacementsEnd() {
        val r = TextSearch.replaceAll("xxcatxx", "cat", "dog", caret = 3)  // inside "cat"
        assertEquals("xxdogxx", r.text)
        assertEquals(5, r.caret)      // end of "dog"
    }

    @Test
    fun noMatchesLeavesTextAndCaretAlone() {
        val r = TextSearch.replaceAll("hello", "zebra", "dog", caret = 2)
        assertEquals("hello", r.text)
        assertEquals(0, r.count)
        assertEquals(2, r.caret)
    }

    // ── counts ────────────────────────────────────────────────────────────────

    @Test
    fun countsWordsAsRunsOfNonWhitespace() {
        assertEquals(0 to 0, TextSearch.counts(""))
        assertEquals(0 to 3, TextSearch.counts("   "))
        assertEquals(2 to 11, TextSearch.counts("hello world"))
        assertEquals(4 to 22, TextSearch.counts("one\ttwo\nthree   four  "))
        // Markdown punctuation counts as part of a word — the source is what's being measured.
        assertEquals(3 to 16, TextSearch.counts("**bold** _it_ x."))
    }
}
