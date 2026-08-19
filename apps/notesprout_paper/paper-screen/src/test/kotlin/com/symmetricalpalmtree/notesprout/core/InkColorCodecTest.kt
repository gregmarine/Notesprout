package com.symmetricalpalmtree.notesprout.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class InkColorCodecTest {

    @Test
    fun encodesOpaque_asRRGGBB() {
        assertEquals("#000000", InkColorCodec.encode(0xFF000000.toInt()))
        assertEquals("#1A2B3C", InkColorCodec.encode(0xFF1A2B3C.toInt()))
    }

    @Test
    fun encodesTranslucent_asAARRGGBB() {
        assertEquals("#801A2B3C", InkColorCodec.encode(0x801A2B3C.toInt()))
    }

    @Test
    fun decodes_bothForms_caseInsensitive() {
        assertEquals(0xFF1A2B3C.toInt(), InkColorCodec.decode("#1a2b3c"))
        assertEquals(0x801A2B3C.toInt(), InkColorCodec.decode("#801A2B3C"))
        assertEquals(0xFF000000.toInt(), InkColorCodec.decode(" #000000 "))
    }

    @Test
    fun garbage_decodesToBlack() {
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode(null))
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode(""))
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode("red"))
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode("#12"))
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode("#GGGGGG"))
    }

    @Test
    fun roundTrip_isStable() {
        for (c in intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x00000000, 0x7F123456))
            assertEquals(c, InkColorCodec.decode(InkColorCodec.encode(c)))
    }

    @Test
    fun localeIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("#1A2B3C", InkColorCodec.encode(0xFF1A2B3C.toInt()))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
