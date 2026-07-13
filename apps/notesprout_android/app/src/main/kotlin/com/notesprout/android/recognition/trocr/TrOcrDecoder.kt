package com.notesprout.android.recognition.trocr

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Autoregressive greedy decoder for the TrOCR text decoder.
 *
 * Pure JVM and backend-agnostic: the ONNX Runtime session is hidden behind [StepFunction]
 * so unit tests can drive the loop with fake logits. Beam search arrives in Phase 1+;
 * the [LogitProcessor] hook is where Phase-2 lexicon biasing plugs in.
 */
object TrOcrDecoder {

    /**
     * One decode step. [prefixIds] is the full generated sequence so far (including the
     * decoder-start token); [state] is backend-opaque KV-cache state — null on the first
     * step. Returns the vocab logits for the next position plus the state to thread into
     * the following step.
     */
    fun interface StepFunction {
        fun step(prefixIds: IntArray, state: Any?): StepResult
    }

    class StepResult(val logits: FloatArray, val state: Any?)

    /** Mutates [logits] in place before the argmax; applied in list order each step. */
    fun interface LogitProcessor {
        fun process(prefixIds: IntArray, logits: FloatArray)
    }

    /**
     * Greedy decode: start from [startId], step until [eosId] or [maxNewTokens].
     * Returns the generated ids **without** the start token (may include [eosId] last).
     * Suspends only to honor cooperative cancellation between steps.
     */
    suspend fun greedy(
        step: StepFunction,
        startId: Int,
        eosId: Int,
        maxNewTokens: Int,
        processors: List<LogitProcessor> = emptyList(),
    ): IntArray {
        val ids = ArrayList<Int>(maxNewTokens + 1)
        ids.add(startId)
        var state: Any? = null
        val repetitionGuard = RepetitionGuard()

        while (ids.size - 1 < maxNewTokens) {
            coroutineContext.ensureActive()
            val prefix = ids.toIntArray()
            val result = step.step(prefix, state)
            state = result.state
            val logits = result.logits
            for (p in processors) p.process(prefix, logits)
            repetitionGuard.process(prefix, logits)

            var best = 0
            var bestV = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > bestV) { bestV = logits[i]; best = i }
            }
            ids.add(best)
            if (best == eosId) break
        }
        return IntArray(ids.size - 1) { ids[it + 1] }
    }

    /**
     * Bans any token that would complete a 4-gram already present in the generated
     * sequence — TrOCR's known failure mode on noisy ink is an infinite repeat loop.
     */
    class RepetitionGuard : LogitProcessor {
        override fun process(prefixIds: IntArray, logits: FloatArray) {
            val n = prefixIds.size
            if (n < NGRAM - 1) return
            // context = last (NGRAM-1) generated ids; ban every id that followed the
            // same context earlier in the sequence.
            val c0 = prefixIds[n - 3]; val c1 = prefixIds[n - 2]; val c2 = prefixIds[n - 1]
            for (i in 0..(n - NGRAM)) {
                if (prefixIds[i] == c0 && prefixIds[i + 1] == c1 && prefixIds[i + 2] == c2) {
                    val banned = prefixIds[i + 3]
                    if (banned < logits.size) logits[banned] = Float.NEGATIVE_INFINITY
                }
            }
        }

        companion object { private const val NGRAM = 4 }
    }
}
