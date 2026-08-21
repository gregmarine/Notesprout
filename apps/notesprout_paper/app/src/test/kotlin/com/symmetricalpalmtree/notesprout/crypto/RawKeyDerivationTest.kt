package com.symmetricalpalmtree.notesprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RawKeyDerivationTest {

    @Test
    fun hex_isLowercaseAscii_localeIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val hex = RawKeyDerivation.toHex(byteArrayOf(0x00, 0x0A, 0x7F, 0xFF.toByte()))
            assertEquals("000a7fff", hex)
            assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun rawKeyLiteral_shape() {
        assertEquals("x'0102'", RawKeyDerivation.rawKeyLiteral(byteArrayOf(1, 2)))
    }

    @Test
    fun pbkdf2_matchesKnownVector() {
        // RFC 6070-style vector for PBKDF2-HMAC-SHA512("password", "salt", 1, 64) — first bytes.
        val out = RawKeyDerivation.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals("867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252",
            RawKeyDerivation.toHex(out.copyOf(32)))
    }

    @Test
    fun constants_areStockSqlcipher4() {
        assertEquals(256_000, RawKeyDerivation.KDF_ITER)
        assertEquals(32, RawKeyDerivation.KEY_LEN)
        assertEquals(16, RawKeyDerivation.SALT_LEN)
    }
}
