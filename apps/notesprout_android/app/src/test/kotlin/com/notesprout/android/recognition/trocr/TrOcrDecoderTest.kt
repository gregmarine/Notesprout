package com.notesprout.android.recognition.trocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrOcrDecoderTest {

    private fun logitsFor(vararg pairs: Pair<Int, Float>, size: Int = 16): FloatArray {
        val l = FloatArray(size) { -100f }
        for ((id, v) in pairs) l[id] = v
        return l
    }

    @Test
    fun greedyPicksArgmaxAndStopsAtEos() = runBlocking {
        // script: step1 -> 5, step2 -> 7, step3 -> eos(2)
        val script = listOf(
            logitsFor(5 to 1f),
            logitsFor(7 to 1f),
            logitsFor(2 to 1f),
        )
        var step = 0
        val ids = TrOcrDecoder.greedy(
            step = { _, state -> TrOcrDecoder.StepResult(script[step++], state ?: "s") },
            startId = 2, eosId = 2, maxNewTokens = 10,
        )
        assertEquals(listOf(5, 7, 2), ids.toList())
    }

    @Test
    fun greedyRespectsMaxNewTokens() = runBlocking {
        val ids = TrOcrDecoder.greedy(
            step = { _, _ -> TrOcrDecoder.StepResult(logitsFor(5 to 1f), null) },
            startId = 2, eosId = 3, maxNewTokens = 4,
        )
        assertEquals(4, ids.size)
    }

    @Test
    fun stateIsThreadedBetweenSteps() = runBlocking {
        val seen = ArrayList<Any?>()
        var counter = 0
        TrOcrDecoder.greedy(
            step = { _, state ->
                seen.add(state)
                counter++
                TrOcrDecoder.StepResult(
                    if (counter >= 3) logitsFor(2 to 1f) else logitsFor(counter + 4 to 1f),
                    "state$counter",
                )
            },
            startId = 2, eosId = 2, maxNewTokens = 10,
        )
        assertEquals(listOf<Any?>(null, "state1", "state2"), seen)
    }

    @Test
    fun logitProcessorCanVetoTheArgmax() = runBlocking {
        val ban5 = TrOcrDecoder.LogitProcessor { _, logits -> logits[5] = Float.NEGATIVE_INFINITY }
        val ids = TrOcrDecoder.greedy(
            step = { _, _ -> TrOcrDecoder.StepResult(logitsFor(5 to 2f, 6 to 1f, 2 to 0f), null) },
            startId = 2, eosId = 2, maxNewTokens = 3,
            processors = listOf(ban5),
        )
        assertTrue(5 !in ids.toList())
        assertEquals(6, ids[0])
    }

    @Test
    fun repetitionGuardBreaksFourGramLoop() = runBlocking {
        // Backend always proposes the loop 8,9,8,9,... — the guard must eventually ban
        // the continuation that would repeat a seen 4-gram, letting eos (next best) win.
        val ids = TrOcrDecoder.greedy(
            step = { prefix, _ ->
                val want = if (prefix.size % 2 == 1) 8 else 9
                TrOcrDecoder.StepResult(logitsFor(want to 2f, 2 to 1f), null)
            },
            startId = 2, eosId = 2, maxNewTokens = 32,
        )
        assertTrue("expected early stop, got ${ids.size} tokens", ids.size < 32)
        assertEquals(2, ids.last())
    }
}
