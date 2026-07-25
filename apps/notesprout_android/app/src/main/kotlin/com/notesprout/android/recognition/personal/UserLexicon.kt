package com.notesprout.android.recognition.personal

import com.notesprout.android.recognition.trocr.SentencePieceTokenizer
import com.notesprout.android.recognition.trocr.TrOcrDecoder

/**
 * The user's personal vocabulary as a token-id prefix trie, built from confirmed
 * training-pair labels (corrections, enrollment, lab references).
 *
 * [processor] returns a per-decode [TrOcrDecoder.LogitProcessor] that adds a **bounded**
 * bias to token ids that extend a lexicon word — it nudges near-ties toward words the
 * user actually writes (project names, technical terms, proper nouns) without ever
 * forcing them. Pure JVM; unit-testable with a fixture tokenizer.
 */
class UserLexicon private constructor(private val root: Node) {

    private class Node {
        val children = HashMap<Int, Node>()
    }

    val isEmpty: Boolean get() = root.children.isEmpty()

    /**
     * Fresh processor for one decode. Tracks the set of trie nodes consistent with the
     * tokens generated so far; every step biases the ids that extend any active path.
     * A new word can start at any step (the root stays active), which is correct because
     * word-start pieces carry the `▁` marker — mid-word ids never match root children.
     */
    fun processor(bias: Float = DEFAULT_BIAS): TrOcrDecoder.LogitProcessor {
        var active: List<Node> = listOf(root)
        var lastSeenLen = 0
        return TrOcrDecoder.LogitProcessor { prefixIds, logits ->
            // Advance state by tokens generated since the previous call (normally one).
            // prefixIds[0] is the decoder-start token — skip it.
            for (i in maxOf(lastSeenLen, 1) until prefixIds.size) {
                val id = prefixIds[i]
                val next = ArrayList<Node>(active.size + 1)
                next.add(root)
                for (n in active) n.children[id]?.let { next.add(it) }
                active = next
            }
            lastSeenLen = prefixIds.size
            for (n in active) {
                for (id in n.children.keys) {
                    if (id < logits.size) logits[id] += bias
                }
            }
        }
    }

    companion object {
        /** Additive logit bias — strong enough to win near-ties, too weak to force a beam. */
        const val DEFAULT_BIAS = 2.0f

        /** Words shorter than this are skipped (function words don't need biasing). */
        private const val MIN_WORD_LEN = 3

        private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}._'-]+")

        /**
         * Build from confirmed labels. Words are whitespace/punctuation-split; short and
         * purely-numeric tokens are dropped; each surviving word is tokenized in its
         * word-start form (leading `▁`) and inserted into the trie.
         */
        fun build(labels: Collection<String>, tokenizer: SentencePieceTokenizer): UserLexicon {
            val root = Node()
            val seen = HashSet<String>()
            for (label in labels) {
                for (raw in label.split(WORD_SPLIT)) {
                    val word = raw.trim('.', '_', '\'', '-')
                    if (word.length < MIN_WORD_LEN) continue
                    if (word.all { it.isDigit() }) continue
                    if (!seen.add(word)) continue
                    val ids = tokenizer.encode(word)
                    if (ids.isEmpty()) continue
                    var node = root
                    for (id in ids) node = node.children.getOrPut(id) { Node() }
                }
            }
            return UserLexicon(root)
        }
    }
}
