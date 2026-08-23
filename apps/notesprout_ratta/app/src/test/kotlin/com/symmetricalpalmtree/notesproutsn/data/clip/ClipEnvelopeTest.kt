package com.symmetricalpalmtree.notesproutsn.data.clip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard payload codec. Round-trips including a **binary** stroke blob (the reason the rows
 * carry Base64 at all), and the decode's refusals — the whole point of which is that an unusable
 * payload reads as an empty clipboard and never as a half-applied paste.
 */
class ClipEnvelopeTest {

    private fun strokeBytes() = ByteArray(256) { (it * 7 % 256 - 128).toByte() }

    private fun envelope(rows: List<ClipRow>) = ClipEnvelope(
        version = ClipEnvelope.VERSION,
        kind = ClipEnvelope.KIND_PAGE,
        sourceNotebookId = "nb-1",
        copiedAt = 1_700_000_000_000L,
        rows = rows,
    )

    @Test
    fun `round-trips a page with a binary stroke blob`() {
        val blob = strokeBytes()
        val env = envelope(listOf(
            ClipRow(id = "page-1", parentId = "nb-1", type = "page", refId = "tpl-1", width = 1404f, height = 1872f),
            ClipRow(
                id = "s-1", parentId = "page-1", type = "stroke", order = 3,
                color = "#000000", strokeWidth = 3f, style = "PEN", blob = ClipRow.encodeBlob(blob),
            ),
        ))

        val bytes = ClipEnvelope.encode(env)
        assertNotNull(bytes)
        val back = ClipEnvelope.decode(bytes)
        assertNotNull(back)
        assertEquals(env, back)

        val stroke = back!!.rows.first { it.type == "stroke" }
        assertArrayEquals(blob, stroke.blobBytes())
        assertEquals(3, stroke.order)
        assertEquals("PEN", stroke.style)
    }

    @Test
    fun `a row with no blob decodes to no bytes`() {
        val row = ClipRow(id = "h-1", parentId = "page-1", type = "heading", text = "## Title", flags = 2)
        val back = ClipEnvelope.decode(ClipEnvelope.encode(envelope(listOf(row))))!!
        assertNull(back.rows.single().blobBytes())
        assertEquals(2, back.rows.single().flags)
    }

    @Test
    fun `a malformed blob costs that row's bytes, not the payload`() {
        val row = ClipRow(id = "s-1", parentId = "page-1", type = "stroke", blob = "not base64!!!")
        val back = ClipEnvelope.decode(ClipEnvelope.encode(envelope(listOf(row))))
        assertNotNull(back)
        assertNull(back!!.rows.single().blobBytes())
    }

    @Test
    fun `null empty and garbage decode to nothing`() {
        assertNull(ClipEnvelope.decode(null))
        assertNull(ClipEnvelope.decode(ByteArray(0)))
        assertNull(ClipEnvelope.decode("not json at all".toByteArray()))
        assertNull(ClipEnvelope.decode(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `a truncated payload decodes to nothing`() {
        val bytes = ClipEnvelope.encode(envelope(listOf(
            ClipRow(id = "page-1", parentId = "nb-1", type = "page"),
        )))!!
        assertNull(ClipEnvelope.decode(bytes.copyOf(bytes.size / 2)))
    }

    @Test
    fun `a newer envelope version is refused`() {
        val bytes = ClipEnvelope.encode(envelope(listOf(ClipRow(id = "p", parentId = "n", type = "page")))
            .copy(version = ClipEnvelope.VERSION + 1))!!
        assertNull(ClipEnvelope.decode(bytes))
    }

    @Test
    fun `an empty row set or a blank kind is refused`() {
        assertNull(ClipEnvelope.decode(ClipEnvelope.encode(envelope(emptyList()))!!))
        val blankKind = envelope(listOf(ClipRow(id = "p", parentId = "n", type = "page"))).copy(kind = "")
        assertNull(ClipEnvelope.decode(ClipEnvelope.encode(blankKind)!!))
    }

    @Test
    fun `an unknown field is ignored rather than fatal`() {
        val json = """
            {"version":1,"kind":"page","sourceNotebookId":"nb-1","copiedAt":1,
             "rows":[{"id":"p","parentId":"nb-1","type":"page","somethingNew":42}],
             "futureField":"whatever"}
        """.trimIndent()
        val back = ClipEnvelope.decode(json.toByteArray())
        assertNotNull(back)
        assertEquals("p", back!!.rows.single().id)
    }

    @Test
    fun `an over-cap payload is refused on write and on read`() {
        // One row whose blob alone blows the cap: encode returns null, and bytes that big never
        // even get parsed on the way back.
        val huge = ClipRow(
            id = "s-1", parentId = "page-1", type = "stroke",
            blob = ClipRow.encodeBlob(ByteArray(ClipEnvelope.MAX_BYTES)),
        )
        assertNull(ClipEnvelope.encode(envelope(listOf(huge))))
        assertNull(ClipEnvelope.decode(ByteArray(ClipEnvelope.MAX_BYTES + 1)))
    }

    @Test
    fun `a payload just under the cap still encodes`() {
        val row = ClipRow(
            id = "s-1", parentId = "page-1", type = "stroke",
            blob = ClipRow.encodeBlob(ByteArray(1024 * 1024)),
        )
        val bytes = ClipEnvelope.encode(envelope(listOf(row)))
        assertNotNull(bytes)
        assertTrue(bytes!!.size <= ClipEnvelope.MAX_BYTES)
        assertNotNull(ClipEnvelope.decode(bytes))
    }
}
