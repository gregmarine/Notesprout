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

    // ── chromeOf ─────────────────────────────────────────────────────────────

    @Test
    fun `chromeOf degrades an unusable payload to no chrome`() {
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPayload.chromeOf("L1|1|0||p"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf("L1|0|1|n|"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf("garbage"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf(""))
    }
}
