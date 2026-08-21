package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalKeyTest {

    /** Fixture vectors produced by Paper's `GlobalKey.format` (byte-compat proof). */
    @Test
    fun format_matchesPaperVectors() {
        assertEquals(
            "NSPT-000G-40R4-0M30-E209-185G-R38E-1W81-24GK",
            GlobalKey.format(ByteArray(20) { it.toByte() }),
        )
        assertEquals(
            "NSPT-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ",
            GlobalKey.format(ByteArray(20) { 0xFF.toByte() }),
        )
    }

    @Test
    fun format_rejectsWrongLength() {
        assertTrue(runCatching { GlobalKey.format(ByteArray(19)) }.isFailure)
        assertTrue(runCatching { GlobalKey.format(ByteArray(21)) }.isFailure)
    }

    @Test
    fun mint_shape() {
        val key = GlobalKey.mint()
        assertTrue(key.startsWith(GlobalKey.PREFIX))
        val groups = key.removePrefix(GlobalKey.PREFIX).split("-")
        assertEquals(8, groups.size)
        assertTrue(groups.all { g -> g.length == 4 && g.all { it in GlobalKey.ALPHABET } })
        assertNotEquals(key, GlobalKey.mint())
    }

    @Test
    fun normalize_foldsConfusables_upperCases() {
        assertEquals("NSPT-0011", GlobalKey.normalize("nspt-OoIl"))
        assertEquals("NSPT-ABCD", GlobalKey.normalize("nspt-abcd"))
    }

    @Test
    fun normalize_isIdentityOnValidKeys() {
        val key = GlobalKey.mint()
        assertEquals(key, GlobalKey.normalize(key))
    }
}
