package com.notesprout.android.core.proofread

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Exercises [SpellEngine] against the real shipped dictionary (`src/main/assets` is mounted as a
 * test-resource root), so these tests catch a corrupted or re-BOMed asset, not just logic bugs.
 * The engine loads once for the class — load cost is reported by [dictionary_load_baseline].
 */
class SpellEngineTest {

    companion object {
        private lateinit var engine: SpellEngine
        private var loadMillis: Long = 0
        private var loadHeapDeltaMb: Long = 0

        @JvmStatic
        @BeforeClass
        fun loadDictionary() {
            val runtime = Runtime.getRuntime()
            System.gc()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val start = System.nanoTime()
            engine = runBlocking { SpellEngine.load(openDictionary()) }
            loadMillis = (System.nanoTime() - start) / 1_000_000
            System.gc()
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            loadHeapDeltaMb = (heapAfter - heapBefore) / (1024 * 1024)
        }

        private fun openDictionary() =
            checkNotNull(SpellEngineTest::class.java.getResourceAsStream("/proofread/en_82765.txt.gz")) {
                "dictionary asset missing from test classpath"
            }
    }

    @Test
    fun dictionary_load_baseline() {
        // JVM baseline for the Phase-1 record; the on-device number lands in Phase 5's perf check.
        println("SpellEngine baseline: ${engine.wordCount} words, ${loadMillis}ms load, ~${loadHeapDeltaMb}MB heap")
        assertTrue("dictionary did not fully load", engine.wordCount > 80_000)
        assertTrue("load took ${loadMillis}ms — investigate before shipping", loadMillis < 60_000)
    }

    @Test
    fun knows_common_words_case_insensitively() {
        assertTrue(engine.isKnown("the"))
        assertTrue(engine.isKnown("The"))
        assertTrue(engine.isKnown("notebook"))
    }

    @Test
    fun knows_contractions_including_typographic() {
        assertTrue(engine.isKnown("don't"))
        assertTrue(engine.isKnown("don’t"))
        assertTrue(engine.isKnown("It's"))
    }

    @Test
    fun knows_possessives_via_stem() {
        assertTrue(engine.isKnown("gardener's"))
        assertTrue(engine.isKnown("gardeners'"))
    }

    @Test
    fun rejects_misspellings() {
        assertFalse(engine.isKnown("teh"))
        assertFalse(engine.isKnown("notebok"))
        assertFalse(engine.isKnown("xzqv"))
    }

    @Test
    fun suggests_the_obvious_fix_first() {
        assertEquals("the", engine.suggestions("teh").first())
        assertTrue(engine.suggestions("notebok").contains("notebook"))
    }

    @Test
    fun suggestions_preserve_titlecase() {
        assertEquals("The", engine.suggestions("Teh").first())
    }

    @Test
    fun suggestions_never_echo_the_word_and_respect_limit() {
        val s = engine.suggestions("teh")
        assertFalse(s.contains("teh"))
        assertTrue(s.size <= SpellEngine.MAX_SUGGESTIONS)
    }

    @Test
    fun suggestions_empty_for_gibberish() {
        assertTrue(engine.suggestions("qqqqqqqqqq").isEmpty())
    }

    @Test
    fun shouldCheck_gates_what_english_cannot_judge() {
        assertTrue(SpellEngine.shouldCheck("hello"))
        assertTrue(SpellEngine.shouldCheck("Hello"))
        assertTrue(SpellEngine.shouldCheck("don't"))
        assertTrue(SpellEngine.shouldCheck("don’t"))

        assertFalse(SpellEngine.shouldCheck("a"))       // single letter
        assertFalse(SpellEngine.shouldCheck("I"))
        assertFalse(SpellEngine.shouldCheck("EPD"))     // acronym
        assertFalse(SpellEngine.shouldCheck("iPad"))    // branded casing
        assertFalse(SpellEngine.shouldCheck("McBride")) // interior capital
        assertFalse(SpellEngine.shouldCheck("2nd"))     // digits
        assertFalse(SpellEngine.shouldCheck("café"))    // non-ASCII letter
        assertFalse(SpellEngine.shouldCheck(""))
    }
}
