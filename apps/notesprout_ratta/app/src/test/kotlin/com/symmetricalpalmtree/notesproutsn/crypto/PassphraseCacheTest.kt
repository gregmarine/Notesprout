package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Arc 26 / U4: single use, per notebook, and gone after [PassphraseCache.TTL_MS]. */
class PassphraseCacheTest {
    @After fun reset() = PassphraseCache.clear()

    @Test fun takenExactlyOnce() {
        PassphraseCache.storeOnce("a", "pw", now = 1_000L)
        assertEquals("pw", PassphraseCache.takeOnce("a", now = 2_000L))
        assertNull(PassphraseCache.takeOnce("a", now = 2_000L))
    }

    @Test fun keyedByNotebook() {
        PassphraseCache.storeOnce("a", "pw", now = 0L)
        assertNull(PassphraseCache.takeOnce("b", now = 0L))
        assertEquals("pw", PassphraseCache.takeOnce("a", now = 0L))
    }

    @Test fun expiresAfterTtlAndIsDropped() {
        PassphraseCache.storeOnce("a", "pw", now = 0L)
        assertNull(PassphraseCache.takeOnce("a", now = PassphraseCache.TTL_MS + 1))
        assertNull(PassphraseCache.takeOnce("a", now = 0L))   // dropped on the way out, not kept
    }

    @Test fun replacesAnEarlierValue() {
        PassphraseCache.storeOnce("a", "old", now = 0L)
        PassphraseCache.storeOnce("a", "new", now = 0L)
        assertEquals("new", PassphraseCache.takeOnce("a", now = 0L))
    }
}
