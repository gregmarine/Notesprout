package com.symmetricalpalmtree.notesprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalKeyTest {

    private val shape = Regex("^NSPT(-[0-9A-HJKMNP-TV-Z]{4}){8}$")

    @Test
    fun mintedKey_hasShape_prefixAnd8GroupsOf4Crockford() {
        repeat(50) {
            val k = GlobalKey.mint()
            assertTrue(k, shape.matches(k))
            assertEquals(5 + 32 + 7, k.length)
        }
    }

    @Test
    fun alphabet_isCrockford_noILOU() {
        assertEquals(32, GlobalKey.ALPHABET.length)
        for (c in "ILOU") assertTrue(!GlobalKey.ALPHABET.contains(c))
    }

    @Test
    fun format_isDeterministic_and_distinctEntropyGivesDistinctKeys() {
        val a = ByteArray(20) { it.toByte() }
        val b = ByteArray(20) { (it + 1).toByte() }
        assertEquals(GlobalKey.format(a), GlobalKey.format(a))
        assertNotEquals(GlobalKey.format(a), GlobalKey.format(b))
        assertEquals("NSPT-000G-40R4-0M30-E209-185G-R38E-1W81-24GK", GlobalKey.format(a))
    }

    @Test
    fun consecutiveMints_differ() {
        assertNotEquals(GlobalKey.mint(), GlobalKey.mint())
    }

    @Test
    fun normalize_upperCasesAndFoldsCrockfordConfusables() {
        // A hand-transcription that used O for 0 and I/l for 1 recovers the canonical key.
        assertEquals("NSPT-0011-VWXY", GlobalKey.normalize("nspt-oOIl-vwxy"))
        // A correct minted key (never contains I/L/O/U) is unchanged by normalization.
        val k = GlobalKey.format(ByteArray(20) { it.toByte() })
        assertEquals(k, GlobalKey.normalize(k))
    }
}
