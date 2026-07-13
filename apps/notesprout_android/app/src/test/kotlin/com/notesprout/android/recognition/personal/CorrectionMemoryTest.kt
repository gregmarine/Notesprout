package com.notesprout.android.recognition.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionMemoryTest {

    @Test
    fun exactLineMatchAppliesImmediately() {
        val mem = CorrectionMemory.build(listOf("grocery lst" to "Grocery List"))
        assertEquals("Grocery List", mem.apply("grocery lst"))
        assertEquals("Grocery List", mem.apply("  Grocery   LST ")) // normalized match
    }

    @Test
    fun wordSubstitutionNeedsTwoConfirmations() {
        val once = CorrectionMemory.build(listOf("use 750N here" to "use JSON here"))
        assertEquals("750N appears", once.apply("750N appears")) // 1 confirmation — not yet

        val twice = CorrectionMemory.build(
            listOf(
                "use 750N here" to "use JSON here",
                "the 750N file" to "the JSON file",
            )
        )
        assertEquals("JSON appears", twice.apply("750N appears"))
    }

    @Test
    fun substitutionIsWholeWordOnly() {
        val mem = CorrectionMemory.build(
            listOf("a lst here" to "a list here", "my lst too" to "my list too")
        )
        assertEquals("list and blstx", mem.apply("lst and blstx")) // "blstx" untouched
    }

    @Test
    fun unequalWordCountPairsContributeNoSubstitutions() {
        val mem = CorrectionMemory.build(
            listOf(
                "one two" to "one two three",
                "one two" to "one two three",
            )
        )
        assertEquals("two", mem.apply("two"))
    }

    @Test
    fun newestExactCorrectionWins() {
        val mem = CorrectionMemory.build(
            listOf("titel" to "Title v1", "titel" to "Title v2")
        )
        assertEquals("Title v2", mem.apply("titel"))
    }

    @Test
    fun emptyMemoryIsIdentity() {
        val mem = CorrectionMemory.build(emptyList())
        assertTrue(mem.isEmpty)
        assertEquals("anything", mem.apply("anything"))
    }
}
