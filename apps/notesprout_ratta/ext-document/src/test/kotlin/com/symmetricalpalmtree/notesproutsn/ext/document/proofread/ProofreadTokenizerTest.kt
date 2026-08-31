package com.symmetricalpalmtree.notesproutsn.ext.document.proofread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the tokenizer's two contracts: offsets always index the original text, and non-prose
 * (code, URLs, link targets) never yields a span.
 */
class ProofreadTokenizerTest {

    private fun words(text: String) = ProofreadTokenizer.wordSpans(text).map { it.word }

    @Test
    fun spans_index_the_original_text() {
        val text = "One two,  three."
        for (span in ProofreadTokenizer.wordSpans(text)) {
            assertEquals(span.word, text.substring(span.start, span.end))
        }
        assertEquals(listOf("One", "two", "three"), words(text))
    }

    @Test
    fun contraction_is_one_token() {
        assertEquals(listOf("don't", "stop"), words("don't stop"))
    }

    @Test
    fun typographic_apostrophe_is_one_token() {
        assertEquals(listOf("don’t"), words("don’t"))
    }

    @Test
    fun hyphen_and_underscore_separate() {
        assertEquals(listOf("e", "ink", "snake", "case"), words("e-ink snake_case"))
    }

    @Test
    fun quotation_apostrophes_are_not_word_chars() {
        assertEquals(listOf("hello"), words("'hello'"))
        assertEquals(listOf("gardeners", "tools"), words("the gardeners' tools".removePrefix("the ")))
    }

    @Test
    fun digits_stay_inside_their_token() {
        // "2nd" must be one token so the engine can decline it whole — never a bare "nd".
        assertEquals(listOf("2nd", "place"), words("2nd place"))
    }

    @Test
    fun fenced_code_is_skipped() {
        val text = "before\n```\nval xyzzy = 1\n```\nafter"
        assertEquals(listOf("before", "after"), words(text))
    }

    @Test
    fun unclosed_fence_skips_to_end() {
        assertEquals(listOf("prose"), words("prose\n```\ncode wrods"))
    }

    @Test
    fun tilde_fence_is_a_fence_too() {
        assertEquals(listOf("before", "after"), words("before\n~~~\nval xyzzy = 1\n~~~\nafter"))
    }

    @Test
    fun indented_code_line_is_skipped() {
        assertEquals(listOf("text", "more"), words("text\n    indentedcode()\nmore"))
        assertEquals(listOf("text"), words("text\n\ttabbed code"))
    }

    @Test
    fun inline_code_is_skipped() {
        assertEquals(listOf("run", "now"), words("run `some_cmd --flag` now"))
    }

    @Test
    fun unmatched_backtick_is_literal() {
        assertEquals(listOf("a", "stray", "tick"), words("a stray ` tick"))
    }

    @Test
    fun stray_backtick_cannot_pair_into_masked_code() {
        // The stray prose backtick must not find its partner inside the indented code line and
        // swallow the prose between them — masked backticks are invisible to inline-code pairing.
        assertEquals(
            listOf("a", "stray", "tick", "here", "prose", "after"),
            words("a stray ` tick here\n    val x = `1`\nprose after"),
        )
    }

    @Test
    fun double_backtick_span_is_skipped() {
        assertEquals(listOf("see", "here"), words("see ``code `with` tick`` here"))
    }

    @Test
    fun urls_and_emails_are_skipped() {
        assertEquals(listOf("visit", "or", "mail"), words("visit https://exmple.com/pth or mail sombody@exmple.com"))
        assertEquals(listOf("go"), words("go www.exmple.com"))
    }

    @Test
    fun link_target_skipped_label_checked() {
        val text = "see [the labell](https://exmple.com/zzz) here"
        assertEquals(listOf("see", "the", "labell", "here"), words(text))
    }

    @Test
    fun image_target_skipped_alt_checked() {
        assertEquals(listOf("an", "altt", "text"), words("an ![altt text](sketch.png)"))
    }

    @Test
    fun empty_and_no_word_input() {
        assertTrue(words("").isEmpty())
        assertTrue(words("... --- !!!").isEmpty())
    }

    @Test
    fun markdown_emphasis_does_not_join_words() {
        assertEquals(listOf("really", "important", "stuff"), words("**really** *important* stuff"))
    }

    @Test
    fun the_shared_mask_overload_agrees_with_the_convenience_one() {
        // The editor's pass computes one mask and hands it to both the spelling check and the
        // grammar rules; the two entry points must never disagree about what is prose.
        val text = "prose `code` more\nsee [label](https://exmple.com) end"
        val mask = ProofreadTokenizer.skipMask(text)
        assertEquals(ProofreadTokenizer.wordSpans(text), ProofreadTokenizer.wordSpans(text, mask))
    }

    @Test
    fun empty_text_has_an_empty_mask() {
        assertEquals(0, ProofreadTokenizer.skipMask("").size)
        assertTrue(ProofreadTokenizer.wordSpans("", BooleanArray(0)).isEmpty())
    }
}
