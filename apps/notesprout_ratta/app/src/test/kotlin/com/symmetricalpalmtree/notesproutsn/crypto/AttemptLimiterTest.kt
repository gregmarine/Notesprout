package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptLimiterTest {

    /** The Paper-v0 schedule, confirmed at R1 phase start: 1–2 free · 3–4 → 30 s · 5–9 → 5 min · ≥10 → 1 h. */
    @Test
    fun schedule() {
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(1))
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(2))
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(3))
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(4))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(5))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(9))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(10))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(100))
    }
}
