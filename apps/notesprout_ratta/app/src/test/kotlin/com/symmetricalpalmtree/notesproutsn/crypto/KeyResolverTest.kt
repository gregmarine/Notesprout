package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 26 / U4: the resolver's decision table (scope × global × marker × unlocked × raw key). */
class KeyResolverTest {

    private val raw = ByteArray(32) { it.toByte() }

    @Test fun globalWithNoPassphraseIsNoKey() {
        assertSame(KeyResolver.Resolved.NoKey, KeyResolver.decide(KeyScope.GLOBAL, null, null, false, null))
    }

    @Test fun globalIsTheCachedPassphraseAlone() {
        assertEquals(
            KeyResolver.Resolved.Passphrases(listOf("g")),
            KeyResolver.decide(KeyScope.GLOBAL, "g", null, false, null),
        )
    }

    @Test fun globalMidRotationTriesTheMarkerSecond() {
        assertEquals(
            KeyResolver.Resolved.Passphrases(listOf("g", "n")),
            KeyResolver.decide(KeyScope.GLOBAL, "g", "n", false, null),
        )
    }

    @Test fun globalIgnoresAMarkerThatEqualsTheGlobal() {
        assertEquals(
            KeyResolver.Resolved.Passphrases(listOf("g")),
            KeyResolver.decide(KeyScope.GLOBAL, "g", "g", false, null),
        )
    }

    @Test fun globalNeverLooksAtUnlocksOrRawKeys() {
        assertEquals(
            KeyResolver.Resolved.Passphrases(listOf("g")),
            KeyResolver.decide(KeyScope.GLOBAL, "g", null, true, raw),
        )
    }

    @Test fun notebookNotUnlockedNeedsPrompt() {
        assertSame(KeyResolver.Resolved.NeedsPrompt, KeyResolver.decide(KeyScope.NOTEBOOK, "g", null, false, null))
        assertSame(KeyResolver.Resolved.NeedsPrompt, KeyResolver.decide(KeyScope.NOTEBOOK, "g", null, false, raw))
    }

    @Test fun notebookUnlockedButNotYetWarmNeedsPrompt() {
        assertSame(KeyResolver.Resolved.NeedsPrompt, KeyResolver.decide(KeyScope.NOTEBOOK, "g", null, true, null))
    }

    @Test fun notebookUnlockedAndWarmIsTheRawKey() {
        val r = KeyResolver.decide(KeyScope.NOTEBOOK, "g", "n", true, raw)
        assertTrue(r is KeyResolver.Resolved.Unlocked)
        assertSame(raw, (r as KeyResolver.Resolved.Unlocked).rawKey)
    }

    @Test fun notebookIgnoresTheGlobalAndTheMarker() {
        assertSame(KeyResolver.Resolved.NeedsPrompt, KeyResolver.decide(KeyScope.NOTEBOOK, null, "n", false, null))
    }

    @Test fun singleCandidateConstructor() {
        assertEquals(KeyResolver.Resolved.Passphrases(listOf("p")), KeyResolver.Resolved.Passphrases("p"))
    }
}
