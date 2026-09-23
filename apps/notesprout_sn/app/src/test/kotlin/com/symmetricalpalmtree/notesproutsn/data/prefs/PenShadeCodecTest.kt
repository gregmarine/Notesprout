package com.symmetricalpalmtree.notesproutsn.data.prefs

import com.symmetricalpalmtree.notesproutsn.core.InkTones
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored level reads as itself when this build offers it, and as black — never a throw — otherwise. */
class PenShadeCodecTest {

    @Test
    fun nothingRememberedIsBlack() {
        assertEquals(InkTones.BLACK, PenShadeCodec.decode(null))
    }

    @Test
    fun everyOfferedLevelIsItself() {
        InkTones.LEVELS.forEach { assertEquals(it, PenShadeCodec.decode(it)) }
    }

    @Test
    fun aLevelThisBuildDoesNotOfferIsBlack() {
        assertEquals(InkTones.BLACK, PenShadeCodec.decode(16))
        assertEquals(InkTones.BLACK, PenShadeCodec.decode(-7))
        assertEquals(InkTones.BLACK, PenShadeCodec.decode(Int.MIN_VALUE))
    }
}
