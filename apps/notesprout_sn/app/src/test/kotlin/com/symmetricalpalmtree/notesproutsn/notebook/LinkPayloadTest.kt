package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The payload codec against **Paper's v1 grammar, byte-for-byte** — the fixture strings are the
 * exact shapes Paper's `LinkPayload` KDoc and its L5 probe used, so a payload written by either
 * app decodes identically in the other (the K1 family-compat requirement).
 */
class LinkPayloadTest {

    /** A resolved reference's wire form, as the extension's `ReferenceCodec` writes one. */
    private val WIRE = "JHN:3:14-3:18,PRO:3:5-3:6"

    // ── Paper-grammar fixtures (byte-exact) ──────────────────────────────────

    @Test
    fun `encode produces Paper's exact page payload shape`() {
        assertEquals(
            "L1|1|0||page-1-id",
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-1-id"),
        )
    }

    @Test
    fun `encode produces Paper's exact notebook payload shape`() {
        assertEquals(
            "L1|0|1|nb-id|",
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, "nb-id", null),
        )
    }

    @Test
    fun `encode produces Paper's exact notebook-page payload shape`() {
        assertEquals(
            "L1|1|2|nb-id|pg-id",
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_NOTEBOOK_PAGE, "nb-id", "pg-id"),
        )
    }

    // ── Kind 3, the Bible reference (arc 38 / R3) ────────────────────────────

    @Test
    fun `a Bible reference encodes with the wire in the notebookId slot`() {
        assertEquals(
            "L1|1|3|JHN:3:14-3:18,PRO:3:5-3:6|",
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, WIRE, null),
        )
    }

    @Test
    fun `a Bible reference round-trips through reference, not notebookId`() {
        for (chrome in listOf(LinkPayload.CHROME_NONE, LinkPayload.CHROME_UNDERLINE)) {
            val d = LinkPayload.decode(LinkPayload.encode(chrome, LinkPayload.KIND_BIBLE, WIRE, null))!!
            assertEquals(chrome, d.chrome)
            assertEquals(LinkPayload.KIND_BIBLE, d.kind)
            assertEquals(WIRE, d.reference)
            // The slot carried it; the decoded notebookId must not, or a re-pointer would rewrite
            // a reference as though it were a notebook id.
            assertNull(d.notebookId)
            assertNull(d.pageId)
        }
    }

    @Test
    fun `referenceOf answers only for a Bible payload`() {
        assertEquals(
            WIRE,
            LinkPayload.referenceOf(
                LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, WIRE, null)
            ),
        )
        assertNull(LinkPayload.referenceOf("L1|1|0||page-1-id"))
        assertNull(LinkPayload.referenceOf("L1|0|1|nb-id|"))
        assertNull(LinkPayload.referenceOf("garbage"))
    }

    @Test
    fun `encode refuses a wire the payload could not carry`() {
        // The separator above all — a wire holding one would make a six-part payload.
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "JHN:3:16|X", null)
        }
        // Blank, lower case, whitespace and over-cap are all "not a wire" to ResolvedReference.
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "jhn:3:16", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "JHN:3:16 PRO:3:5", null)
        }
        // And a pageId the kind does not carry.
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, WIRE, "pg")
        }
    }

    @Test
    fun `a Bible payload with a pageId or a bad wire decodes null`() {
        assertNull(LinkPayload.decode("L1|1|3|$WIRE|pg"))
        assertNull(LinkPayload.decode("L1|1|3||"))
        assertNull(LinkPayload.decode("L1|1|3|jhn:3:16|"))
        assertNull(LinkPayload.decode("L1|1|3|" + "J".repeat(513) + "|"))
    }

    @Test
    fun `kind 5 is still unknown`() {
        // Kind 4 became KIND_BIBLE_TEXT (arc 40); the next number is the unknown one, and it is
        // refused with a wire that would otherwise pass — the kind alone decides.
        assertNull(LinkPayload.decode("L1|1|5|JHN:3:16-3:16|"))
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_NONE, 5, "JHN:3:16-3:16", null)
        }
    }

    @Test
    fun `decode reads a Paper-composed payload`() {
        val d = LinkPayload.decode("L1|1|0||3f2a7c1e-0000-4000-8000-000000000001")!!
        assertEquals(LinkPayload.CHROME_UNDERLINE, d.chrome)
        assertEquals(LinkPayload.KIND_PAGE, d.kind)
        assertNull(d.notebookId)
        assertEquals("3f2a7c1e-0000-4000-8000-000000000001", d.pageId)
    }

    // ── Round trips ──────────────────────────────────────────────────────────

    @Test
    fun `all three kinds round-trip`() {
        for ((kind, nb, pg) in listOf(
            Triple(LinkPayload.KIND_PAGE, null, "p"),
            Triple(LinkPayload.KIND_NOTEBOOK, "n", null),
            Triple(LinkPayload.KIND_NOTEBOOK_PAGE, "n", "p"),
        )) {
            for (chrome in listOf(LinkPayload.CHROME_NONE, LinkPayload.CHROME_UNDERLINE)) {
                val d = LinkPayload.decode(LinkPayload.encode(chrome, kind, nb, pg))!!
                assertEquals(chrome, d.chrome)
                assertEquals(kind, d.kind)
                assertEquals(nb, d.notebookId)
                assertEquals(pg, d.pageId)
            }
        }
    }

    // ── decode never throws — every malformed shape is null ──────────────────

    @Test
    fun `unknown version decodes null`() {
        assertNull(LinkPayload.decode("L2|1|0||p"))
        assertNull(LinkPayload.decode("X|1|0||p"))
        assertNull(LinkPayload.decode(""))
    }

    @Test
    fun `wrong part count decodes null`() {
        assertNull(LinkPayload.decode("L1|1|0|p"))
        assertNull(LinkPayload.decode("L1|1|0||p|extra"))
    }

    @Test
    fun `out-of-range chrome or kind decodes null`() {
        assertNull(LinkPayload.decode("L1|2|0||p"))
        assertNull(LinkPayload.decode("L1|x|0||p"))
        assertNull(LinkPayload.decode("L1|1|3|n|p"))
        assertNull(LinkPayload.decode("L1|1|x|n|p"))
    }

    @Test
    fun `an id the kind forbids decodes null`() {
        assertNull(LinkPayload.decode("L1|1|0|n|p"))   // KIND_PAGE with a notebookId
        assertNull(LinkPayload.decode("L1|1|1|n|p"))   // KIND_NOTEBOOK with a pageId
    }

    @Test
    fun `a missing required id decodes null`() {
        assertNull(LinkPayload.decode("L1|1|0||"))
        assertNull(LinkPayload.decode("L1|1|1||"))
        assertNull(LinkPayload.decode("L1|1|2|n|"))
    }

    @Test
    fun `over-cap payload and over-cap id decode null`() {
        assertNull(LinkPayload.decode("L1|1|0||" + "a".repeat(LinkPayload.MAX_PAYLOAD_CHARS)))
        assertNull(LinkPayload.decode("L1|1|0||" + "a".repeat(LinkPayload.MAX_ID_CHARS + 1)))
    }

    // ── encode throws on caller bugs ─────────────────────────────────────────

    @Test
    fun `encode rejects bad chrome, kind, blank or forbidden ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(2, LinkPayload.KIND_PAGE, null, "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_NONE, 3, "n", "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, null, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, "n", "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, null, "a|b")
        }
    }

    // ── KIND_BIBLE_TEXT (arc 40 "Verses") ────────────────────────────────────

    @Test
    fun `a verses payload is the Bible shape with kind 4 and the same wire`() {
        val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE_TEXT, WIRE, null)
        assertEquals("L1|1|4|$WIRE|", payload)
        val d = LinkPayload.decode(payload)!!
        assertEquals(LinkPayload.KIND_BIBLE_TEXT, d.kind)
        assertNull(d.notebookId)
        assertNull(d.pageId)
        assertEquals(WIRE, d.reference)
        assertEquals(WIRE, LinkPayload.referenceOf(payload))
        assertEquals(true, LinkPayload.isBibleText(payload))
        assertEquals(false, LinkPayload.isBibleText(LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, WIRE, null)))
        assertEquals(false, LinkPayload.isBibleText("L1|1|0||p"))
    }

    @Test
    fun `a verses payload takes the Bible slot rules`() {
        assertNull(LinkPayload.decode("L1|1|4||"))
        assertNull(LinkPayload.decode("L1|1|4|$WIRE|p"))
        assertNull(LinkPayload.decode("L1|1|4|not a wire|"))
        assertThrows(IllegalArgumentException::class.java) {
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE_TEXT, WIRE, "p")
        }
    }

    // ── chromeOf ─────────────────────────────────────────────────────────────

    @Test
    fun `chromeOf degrades an unusable payload to no chrome`() {
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPayload.chromeOf("L1|1|0||p"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf("L1|0|1|n|"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf("garbage"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf(""))
    }
}
