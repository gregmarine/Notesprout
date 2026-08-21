package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure `require`s behind [LinkChoice] + the L0 `LINK_CHROME_*` contract constants (no Parcel on the JVM). */
class LinkChoiceTest {

    @Test
    fun acceptsWellFormed() {
        val maxPayload = "a".repeat(ExtensionContract.MAX_LINK_PAYLOAD_CHARS)
        LinkChoice.requireValid(maxPayload, ExtensionContract.LINK_CHROME_NONE)
        LinkChoice.requireValid(maxPayload, ExtensionContract.LINK_CHROME_UNDERLINE)
    }

    @Test
    fun roundTripsFields() {
        // Parcel is unavailable on the JVM; the write order (payload, chrome) is fixed — the fields survive construction.
        val c = LinkChoice("payload", ExtensionContract.LINK_CHROME_UNDERLINE)
        assertEquals("payload", c.payload)
        assertEquals(ExtensionContract.LINK_CHROME_UNDERLINE, c.chrome)
    }

    @Test
    fun rejectsBlankPayload() {
        assertThrows(IllegalArgumentException::class.java) { LinkChoice.requireValid("", ExtensionContract.LINK_CHROME_NONE) }
        assertThrows(IllegalArgumentException::class.java) { LinkChoice.requireValid("   ", ExtensionContract.LINK_CHROME_NONE) }
    }

    @Test
    fun rejectsOverLongPayload() {
        val tooLong = "a".repeat(ExtensionContract.MAX_LINK_PAYLOAD_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) { LinkChoice.requireValid(tooLong, ExtensionContract.LINK_CHROME_NONE) }
    }

    @Test
    fun rejectsUnknownChrome() {
        assertThrows(IllegalArgumentException::class.java) { LinkChoice.requireValid("payload", 2) }
        assertThrows(IllegalArgumentException::class.java) { LinkChoice.requireValid("payload", -1) }
    }

    @Test
    fun constants() {
        assertEquals(2_000, ExtensionContract.MAX_LINK_PAYLOAD_CHARS)
        assertEquals(0, ExtensionContract.LINK_CHROME_NONE)
        assertEquals(1, ExtensionContract.LINK_CHROME_UNDERLINE)
    }
}
