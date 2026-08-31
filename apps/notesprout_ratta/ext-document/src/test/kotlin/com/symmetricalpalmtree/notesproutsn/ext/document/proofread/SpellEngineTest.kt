package com.symmetricalpalmtree.notesproutsn.ext.document.proofread

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Exercises [SpellEngine] against the real dictionary this extension ships (`src/main/assets` is
 * mounted as a test-resource root), so these tests catch a corrupted or re-BOMed asset, not just
 * logic bugs. The engine loads once for the class — load cost is reported by
 * [dictionary_load_baseline].
 */
class SpellEngineTest {

    companion object {
        private lateinit var engine: SpellEngine
        private var loadMillis: Long = 0
        private var loadHeapDeltaMb: Long = 0

        private var stageOneMillis: Long = 0

        @JvmStatic
        @BeforeClass
        fun loadDictionary() {
            val runtime = Runtime.getRuntime()
            System.gc()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val start = System.nanoTime()
            engine = runBlocking { SpellEngine.load(openDictionary()) }
            stageOneMillis = (System.nanoTime() - start) / 1_000_000
            check(!engine.suggestionsReady) { "index cannot be ready before loadSuggestionIndex" }
            check(engine.suggestions("teh").isEmpty()) { "suggestions must be empty before the index" }
            runBlocking { engine.loadSuggestionIndex() }
            loadMillis = (System.nanoTime() - start) / 1_000_000
            System.gc()
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            loadHeapDeltaMb = (heapAfter - heapBefore) / (1024 * 1024)
        }

        private fun openDictionary() =
            checkNotNull(SpellEngineTest::class.java.getResourceAsStream("/proofread/en_82765.dict")) {
                "dictionary asset missing from test classpath"
            }
    }

    @Test
    fun dictionary_load_baseline() {
        // JVM baseline; the device numbers live in the arc plan. Stage 1 is what gates the first
        // flags on a Supernote — it must stay a small fraction of the full load.
        println(
            "SpellEngine baseline: ${engine.wordCount} words, " +
                "${stageOneMillis}ms word map + ${loadMillis - stageOneMillis}ms index, ~${loadHeapDeltaMb}MB heap"
        )
        assertTrue("dictionary did not fully load", engine.wordCount > 80_000)
        assertTrue("load took ${loadMillis}ms — investigate before shipping", loadMillis < 60_000)
        assertTrue("word-map stage took ${stageOneMillis}ms — it must be cheap", stageOneMillis * 3 < loadMillis)
    }

    @Test
    fun suggestion_index_ready_after_second_stage() {
        assertTrue(engine.suggestionsReady)
    }

    @Test
    fun loading_the_index_twice_is_a_no_op() {
        runBlocking { engine.loadSuggestionIndex() }
        assertTrue(engine.suggestionsReady)
        assertEquals("the", engine.suggestions("teh").first())
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
    fun a_bare_apostrophe_is_not_a_known_word() {
        // The possessive rule strips the tail; an empty stem must never count as known.
        assertFalse(engine.isKnown("'"))
        assertFalse(engine.isKnown("'s"))
    }

    @Test
    fun knows_both_regional_spellings() {
        // The upstream SymSpell list shipped "favourite" without "favorite" (and ~790 similar
        // gaps), flagging American text with British suggestions. The asset is patched (og's
        // tools/proofread/patch_dictionary.py) so both spellings of every standard pair are
        // known; this pins the pairs that surfaced on-device against a future dictionary swap.
        for (word in listOf(
            "favorite", "favourite", "theater", "theatre", "neighbor", "neighbour",
            "analyze", "analyse", "color", "colour", "airplane", "aeroplane", "mom", "mum",
        )) {
            assertTrue("dictionary lost \"$word\"", engine.isKnown(word))
        }
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
    fun suggestions_honour_a_smaller_limit() {
        assertEquals(2, engine.suggestions("teh", limit = 2).size)
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

    @Test
    fun normalizeWord_is_the_storage_form_everywhere() {
        // The user dictionary and every ignore set compare in this form on both sides.
        assertEquals("don't", SpellEngine.normalizeWord("Don’t"))
        assertEquals("garden", SpellEngine.normalizeWord("GARDEN"))
    }
}
