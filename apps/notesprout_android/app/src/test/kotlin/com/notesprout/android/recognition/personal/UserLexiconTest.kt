package com.notesprout.android.recognition.personal

import com.notesprout.android.recognition.trocr.SentencePieceTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLexiconTest {

    // ids: 0-3 specials; 4=▁SQL 5=ite 6=▁JSON 7=▁note 8=sprout 9=▁the 10=▁a
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
              ["▁SQL", -8.0], ["ite", -8.0], ["▁JSON", -8.0],
              ["▁note", -8.0], ["sprout", -8.0], ["▁the", -4.0], ["▁a", -4.0]
            ]
          }
        }
    """.trimIndent()

    private val tokenizer = SentencePieceTokenizer.fromTokenizerJson(fixture.byteInputStream())

    @Test
    fun biasesWordStartAndContinuationTokens() {
        val lex = UserLexicon.build(listOf("SQLite tables", "notesprout"), tokenizer)
        val p = lex.processor(bias = 2f)

        // Step 1: prefix = [start]. Word-start ids of lexicon words get biased.
        val logits1 = FloatArray(11)
        p.process(intArrayOf(2), logits1)
        assertEquals(2f, logits1[4]) // ▁SQL (from SQLite)
        assertEquals(2f, logits1[7]) // ▁note (from notesprout)
        assertEquals(0f, logits1[9]) // ▁the — not in lexicon (too short / not present)

        // Step 2: we generated ▁SQL → continuation "ite" gets biased; new-word starts stay biased.
        val logits2 = FloatArray(11)
        p.process(intArrayOf(2, 4), logits2)
        assertEquals(2f, logits2[5]) // ite continues SQL→SQLite
        assertEquals(2f, logits2[4]) // a new word could start again
    }

    @Test
    fun nearTieFlipsTowardLexiconWord() {
        val lex = UserLexicon.build(listOf("JSON"), tokenizer)
        val p = lex.processor(bias = 2f)
        val logits = FloatArray(11)
        logits[9] = 1.0f  // ▁the slightly ahead
        logits[6] = 0.5f  // ▁JSON behind by 0.5
        p.process(intArrayOf(2), logits)
        assertTrue(logits[6] > logits[9]) // bias flips the near-tie
    }

    @Test
    fun shortAndNumericWordsAreExcluded() {
        val lex = UserLexicon.build(listOf("a an 42 1234"), tokenizer)
        assertTrue(lex.isEmpty)
    }

    @Test
    fun emptyLabelsGiveEmptyLexicon() {
        assertTrue(UserLexicon.build(emptyList(), tokenizer).isEmpty)
    }
}
