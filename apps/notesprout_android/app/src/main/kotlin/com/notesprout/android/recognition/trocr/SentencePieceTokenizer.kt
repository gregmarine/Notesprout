package com.notesprout.android.recognition.trocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * SentencePiece **unigram** tokenizer for the TrOCR decoder — parses the Hugging Face
 * `tokenizer.json` that travels inside the model bundle (`trocr-small-handwritten` uses
 * XLMRobertaTokenizer; pieces mark word starts with `▁` U+2581).
 *
 * Pure JVM (no Android imports) so it is unit-testable like [com.notesprout.android.core.StrokeCodec].
 *
 * Phase 0/1 needs [decode] only. [encode] is a greedy longest-match approximation —
 * sufficient for the Phase-2 lexicon-biasing trie (a biasing heuristic, not a parity
 * requirement); a true unigram-Viterbi encode can replace it later without API change.
 *
 * Note: the decoder's logit width can exceed the piece count (64044 vs 64002 for the
 * base model — HF pads the embedding); [decode] must tolerate out-of-range ids.
 */
class SentencePieceTokenizer private constructor(
    /** Piece strings indexed by token id. */
    private val pieces: Array<String>,
    /** Unigram log-probabilities, index-aligned with [pieces] (for a future Viterbi encode). */
    val scores: FloatArray,
    /** Ids never emitted into decoded text: <s>, </s>, <pad>, <unk>, <mask>, added specials. */
    private val specialIds: Set<Int>,
) {
    val vocabSize: Int get() = pieces.size

    /** Token id → piece string, or null when out of range. */
    fun piece(id: Int): String? = pieces.getOrNull(id)

    fun isSpecial(id: Int): Boolean = id in specialIds

    /**
     * Decode token ids to text: skip specials and out-of-range ids, join pieces,
     * `▁` → space, trim.
     */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder(ids.size * 4)
        for (id in ids) {
            if (id in specialIds) continue
            val piece = pieces.getOrNull(id) ?: continue
            sb.append(piece)
        }
        return sb.toString().replace(WORD_BOUNDARY, ' ').trim()
    }

    /**
     * Greedy longest-match encode. [text] is normalized the SentencePiece way first:
     * spaces become `▁` and a leading `▁` is added. Unknown characters are skipped.
     */
    fun encode(text: String): IntArray {
        if (pieceToId == null) pieceToId = HashMap<String, Int>(pieces.size * 2).also { m ->
            for (i in pieces.indices) m.putIfAbsent(pieces[i], i)
        }
        val map = pieceToId!!
        val norm = WORD_BOUNDARY + text.trim().replace(' ', WORD_BOUNDARY)
        val out = ArrayList<Int>(norm.length / 3 + 4)
        var i = 0
        while (i < norm.length) {
            var end = minOf(norm.length, i + MAX_PIECE_CHARS)
            var matched = false
            while (end > i) {
                val id = map[norm.substring(i, end)]
                if (id != null) {
                    out.add(id); i = end; matched = true; break
                }
                end--
            }
            if (!matched) i++ // unknown char — skip (biasing use only, exactness not required)
        }
        return out.toIntArray()
    }

    @Volatile
    private var pieceToId: HashMap<String, Int>? = null

    companion object {
        const val WORD_BOUNDARY = '▁' // ▁
        private const val MAX_PIECE_CHARS = 24

        /**
         * Parse a Hugging Face `tokenizer.json` (model.type == "Unigram").
         * [extraSpecialIds] adds manifest-declared ids (bos/eos/pad/decoder-start) on top
         * of the file's `added_tokens`.
         */
        fun fromTokenizerJson(input: InputStream, extraSpecialIds: Collection<Int> = emptyList()): SentencePieceTokenizer {
            val root = Json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
            val model = root.getValue("model").jsonObject
            val type = model["type"]?.jsonPrimitive?.content
            require(type == "Unigram") { "unsupported tokenizer model type: $type" }

            val vocab = model.getValue("vocab").jsonArray
            val pieces = Array(vocab.size) { i -> vocab[i].jsonArray[0].jsonPrimitive.content }
            val scores = FloatArray(vocab.size) { i -> vocab[i].jsonArray[1].jsonPrimitive.content.toFloat() }

            val specials = HashSet<Int>(extraSpecialIds)
            root["added_tokens"]?.jsonArray?.forEach { tok ->
                val obj = tok.jsonObject
                if (obj["special"]?.jsonPrimitive?.content?.toBoolean() != false) {
                    obj["id"]?.jsonPrimitive?.content?.toIntOrNull()?.let { specials.add(it) }
                }
            }
            // unigram unk_id is special-adjacent: never emit its piece ("<unk>") as text
            model["unk_id"]?.jsonPrimitive?.content?.toIntOrNull()?.let { specials.add(it) }

            return SentencePieceTokenizer(pieces, scores, specials)
        }
    }
}
