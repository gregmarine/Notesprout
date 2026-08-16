package com.symmetricalpalmtree.notesprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptLimiterTest {
    @Test
    fun schedule_isReferenceVerbatim() {
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(1))
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(2))
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(3))
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(4))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(5))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(9))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(10))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(99))
    }
}
