package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InkColorCodecTest {

    @Test
    fun encode_opaque_isRgb() {
        assertEquals("#000000", InkColorCodec.encode(0xFF000000.toInt()))
        assertEquals("#FF0080", InkColorCodec.encode(0xFFFF0080.toInt()))
    }

    @Test
    fun encode_translucent_isArgb() {
        assertEquals("#80FF0080", InkColorCodec.encode(0x80FF0080.toInt()))
        assertEquals("#00000000", InkColorCodec.encode(0x00000000))
    }

    @Test
    fun decode_bothShapes_caseInsensitive() {
        assertEquals(0xFFFF0080.toInt(), InkColorCodec.decode("#ff0080"))
        assertEquals(0xFFFF0080.toInt(), InkColorCodec.decode("#FF0080"))
        assertEquals(0x80FF0080.toInt(), InkColorCodec.decode("#80FF0080"))
    }

    @Test
    fun decode_garbage_isBlack() {
        for (s in listOf(null, "", "black", "#12345", "#GGGGGG", "123456", "#1234567890")) {
            assertEquals("for input $s", InkColorCodec.BLACK, InkColorCodec.decode(s))
        }
    }

    @Test
    fun roundTrip() {
        for (c in intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x80808080.toInt(), 0xFF123456.toInt())) {
            assertEquals(c, InkColorCodec.decode(InkColorCodec.encode(c)))
        }
    }
}
