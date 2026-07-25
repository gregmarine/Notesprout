package com.notesprout.android.recognition.trocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven tests for the tokenizer.json (Unigram) parser and decode/encode.
 * The fixture mirrors the real file's structure (verified against the HF tokenizer:
 * model.vocab index == token id; ▁ marks word starts; specials in added_tokens).
 */
class SentencePieceTokenizerTest {

    // ids: 0=<s> 1=<pad> 2=</s> 3=<unk> 4=▁hello 5=▁world 6=▁ 7=he 8=llo 9=! 10=▁6 11=:30
    private val fixture = """
        {
          "added_tokens": [
            {"id": 0, "content": "<s>", "special": true},
            {"id": 1, "content": "<pad>", "special": true},
            {"id": 2, "content": "</s>", "special": true},
            {"id": 3, "content": "<unk>", "special": true}
          ],
          "model": {
            "type": "Unigram",
            "unk_id": 3,
            "vocab": [
              ["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", 0.0],
              ["▁hello", -8.1], ["▁world", -8.5], ["▁", -2.0],
              ["he", -9.0], ["llo", -9.5], ["!", -7.0], ["▁6", -9.9], [":30", -10.0]
            ]
          }
        }
    """.trimIndent()

    private fun tokenizer() = SentencePieceTokenizer.fromTokenizerJson(fixture.byteInputStream())

    @Test
    fun decodeJoinsPiecesAndMapsWordBoundaryToSpace() {
        assertEquals("hello world!", tokenizer().decode(intArrayOf(4, 5, 9)))
    }

    @Test
    fun decodeSkipsSpecialsAndOutOfRangeIds() {
        // 0/2 are specials; 999 is out of range (real model: 64044 logits vs 64002 pieces)
        assertEquals("hello world", tokenizer().decode(intArrayOf(0, 4, 999, 5, 2)))
    }

    @Test
    fun decodeEmptyAndAllSpecialGivesBlank() {
        assertEquals("", tokenizer().decode(intArrayOf()))
        assertEquals("", tokenizer().decode(intArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun decodeTrimsLeadingWordBoundary() {
        assertEquals("6:30", tokenizer().decode(intArrayOf(10, 11)))
    }

    @Test
    fun encodeGreedyPrefersLongestMatch() {
        val ids = tokenizer().encode("hello world")
        assertEquals(listOf(4, 5), ids.toList()) // ▁hello + ▁world, not ▁+he+llo
    }

    @Test
    fun encodeSkipsUnknownCharacters() {
        val ids = tokenizer().encode("hello ∆")
        assertTrue(ids.toList().containsAll(listOf(4)))
    }

    @Test
    fun extraSpecialIdsAreRespected() {
        val tok = SentencePieceTokenizer.fromTokenizerJson(
            fixture.byteInputStream(), extraSpecialIds = listOf(9),
        )
        assertEquals("hello", tok.decode(intArrayOf(4, 9))) // "!" suppressed as special
    }
}
