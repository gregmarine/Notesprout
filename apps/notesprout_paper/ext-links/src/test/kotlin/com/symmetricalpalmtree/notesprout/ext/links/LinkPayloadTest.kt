package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** [LinkPayload]: round trip for every kind × chrome, and every malformed shape → null / a throw. */
class LinkPayloadTest {

    private val nb = "11111111-2222-3333-4444-555555555555"
    private val pg = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun roundTripEveryKindAndChrome() {
        for (chrome in intArrayOf(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.LINK_CHROME_UNDERLINE)) {
            val cases = listOf(
                Triple(ExtensionContract.DEST_PAGE, null, pg),
                Triple(ExtensionContract.DEST_NOTEBOOK, nb, null),
                Triple(ExtensionContract.DEST_NOTEBOOK_PAGE, nb, pg),
            )
            for ((kind, n, p) in cases) {
                val payload = LinkPayload.encode(chrome, kind, n, p)
                val decoded = LinkPayload.decode(payload)
                assertNotNull("decode of $payload", decoded)
                assertEquals(chrome, decoded!!.chrome)
                assertEquals(kind, decoded.kind)
                assertEquals(n, decoded.notebookId)
                assertEquals(p, decoded.pageId)
            }
        }
    }

    @Test
    fun grammarIsExactlyAsRecorded() {
        assertEquals("L1|1|0||$pg", LinkPayload.encode(1, ExtensionContract.DEST_PAGE, null, pg))
        assertEquals("L1|0|1|$nb|", LinkPayload.encode(0, ExtensionContract.DEST_NOTEBOOK, nb, null))
        assertEquals("L1|1|2|$nb|$pg", LinkPayload.encode(1, ExtensionContract.DEST_NOTEBOOK_PAGE, nb, pg))
    }

    @Test
    fun decodeIsNullForEveryMalformedShape() {
        // A future version is not an error — resolve answers null and the core says "dead link".
        assertNull(LinkPayload.decode("L2|1|0||$pg"))
        assertNull(LinkPayload.decode("garbage"))
        assertNull(LinkPayload.decode(""))
        // Wrong part count.
        assertNull(LinkPayload.decode("L1|1|0|$pg"))
        assertNull(LinkPayload.decode("L1|1|0||$pg|extra"))
        // Out-of-range chrome / kind, and non-numeric.
        assertNull(LinkPayload.decode("L1|2|0||$pg"))
        assertNull(LinkPayload.decode("L1|x|0||$pg"))
        assertNull(LinkPayload.decode("L1|1|3|$nb|$pg"))
        assertNull(LinkPayload.decode("L1|1|y|$nb|$pg"))
        // A required id blank.
        assertNull(LinkPayload.decode("L1|1|0||"))
        assertNull(LinkPayload.decode("L1|1|1||"))
        assertNull(LinkPayload.decode("L1|1|2|$nb|"))
        assertNull(LinkPayload.decode("L1|1|2||$pg"))
        assertNull(LinkPayload.decode("L1|1|0|| "))
        // An id the kind forbids.
        assertNull(LinkPayload.decode("L1|1|0|$nb|$pg"))
        assertNull(LinkPayload.decode("L1|1|1|$nb|$pg"))
        // An id over the id cap.
        val longId = "x".repeat(ExtensionContract.MAX_LINK_ID_CHARS + 1)
        assertNull(LinkPayload.decode("L1|1|0||$longId"))
        // A payload over the payload cap.
        val huge = "L1|1|0||" + "y".repeat(ExtensionContract.MAX_LINK_PAYLOAD_CHARS)
        assertNull(LinkPayload.decode(huge))
    }

    @Test
    fun encodeThrowsOnACallerBug() {
        expectThrow { LinkPayload.encode(2, ExtensionContract.DEST_PAGE, null, pg) }        // bad chrome
        expectThrow { LinkPayload.encode(1, 3, nb, pg) }                                     // bad kind
        expectThrow { LinkPayload.encode(1, ExtensionContract.DEST_PAGE, null, "a|b") }      // separator in an id
        expectThrow { LinkPayload.encode(1, ExtensionContract.DEST_PAGE, null, null) }       // missing required id
        expectThrow { LinkPayload.encode(1, ExtensionContract.DEST_PAGE, null, "  ") }       // blank required id
        expectThrow { LinkPayload.encode(1, ExtensionContract.DEST_PAGE, nb, pg) }           // forbidden id present
        expectThrow { LinkPayload.encode(1, ExtensionContract.DEST_NOTEBOOK, nb, pg) }       // forbidden id present
        expectThrow {
            LinkPayload.encode(1, ExtensionContract.DEST_PAGE, null, "x".repeat(ExtensionContract.MAX_LINK_ID_CHARS + 1))
        }
    }

    private fun expectThrow(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }
}
