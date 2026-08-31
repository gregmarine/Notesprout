package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.markdown.HeadingTypography
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [LinkRows] mapping + [PageLink.unionBounds] — incl. the two locked family deltas from Paper:
 *  `style` written null and read leniently. */
class LinkRowsTest {

    private val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "target")

    private fun link(id: String = "l1") = PageLink(
        id = id, payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
        x = 10f, y = 20f, width = 100f, height = 50f, order = 3,
        strokes = emptyList(), headings = emptyList(),
    )

    private fun row(
        type: String = SoilSchema.TYPE_LINK,
        text: String? = payload,
        style: String? = null,
        x: Float? = 10f, y: Float? = 20f, w: Float? = 100f, h: Float? = 50f,
    ) = SoilObjectEntity(
        id = "l1", parentId = "page", type = type, order = 3,
        createdAt = 1L, updatedAt = 1L,
        text = text, style = style, x = x, y = y, width = w, height = h,
    )

    @Test
    fun `toRow writes the locked column contract — style and flags null`() {
        val r = LinkRows.toRow(link(), "page", 42L)
        assertEquals(SoilSchema.TYPE_LINK, r.type)
        assertEquals("page", r.parentId)
        assertEquals(payload, r.text)
        assertNull(r.style)
        assertNull(r.flags)
        assertNull(r.refId)
        assertNull(r.blob)
        assertEquals(10f, r.x)
        assertEquals(50f, r.height)
        assertEquals(3, r.order)
    }

    @Test
    fun `toLink round-trips and decodes chrome from the payload`() {
        val l = LinkRows.toLink(row(), emptyList(), emptyList())!!
        assertEquals("l1", l.id)
        assertEquals(payload, l.payload)
        assertEquals(LinkPayload.CHROME_UNDERLINE, l.chrome)
        assertEquals(10f, l.x)
        assertEquals(3, l.order)
    }

    @Test
    fun `style is read leniently — a Paper provider identity decodes fine`() {
        // Paper wrote "<pkg>:link" into style; SN must not require or route on it.
        assertNotNull(LinkRows.toLink(row(style = "com.example.notesprout.ext.links:link"), emptyList(), emptyList()))
    }

    @Test
    fun `an unusable payload degrades to no chrome, not a dropped link`() {
        val l = LinkRows.toLink(row(text = "L9|future|stuff"), emptyList(), emptyList())!!
        assertEquals(LinkPayload.CHROME_NONE, l.chrome)
        val nullText = LinkRows.toLink(row(text = null), emptyList(), emptyList())!!
        assertEquals("", nullText.payload)
    }

    @Test
    fun `wrong type or unusable bounds are dropped`() {
        assertNull(LinkRows.toLink(row(type = "heading"), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row(x = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row(w = -1f), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row(h = Float.NaN), emptyList(), emptyList()))
    }

    @Test
    fun `cap truncates only past the family cap`() {
        val long = "x".repeat(LinkPayload.MAX_PAYLOAD_CHARS + 10)
        assertEquals(LinkPayload.MAX_PAYLOAD_CHARS, LinkRows.cap(long).length)
        assertEquals(payload, LinkRows.cap(payload))
    }

    // ── unionBounds ──────────────────────────────────────────────────────────

    private fun stroke(id: String, x1: Float, y1: Float, x2: Float, y2: Float) =
        Stroke(id = id, points = listOf(StrokePoint(x1, y1), StrokePoint(x2, y2)))

    private fun heading(id: String, x: Float, y: Float, w: Float, h: Float) = Heading(
        id = id, text = "# T", level = 1, x = x, y = y, width = w, height = h, order = 0,
    )

    /** 2f keeps the arithmetic readable: `PADDING_DP` 8 → 16 px, `UNDERLINE_CLEARANCE_DP` 4 → 8 px. */
    private val density = 2f
    private val pad = HeadingTypography.PADDING_DP * density
    private val clear = PageLink.UNDERLINE_CLEARANCE_DP * density

    @Test
    fun `unionBounds spans strokes and headings and carries the bottom to the band`() {
        val b = PageLink.unionBounds(
            strokes = listOf(stroke("s", 10f, 10f, 50f, 30f).copy(width = 4f)),
            headings = listOf(heading("h", 40f, 5f, 60f, 20f)),
            density = density,
        )!!
        assertEquals(10f, b.left)
        assertEquals(5f, b.top)
        assertEquals(100f, b.right)
        // Stroke box = ink 30 + width/2 + the heading pad, then the clearance; the heading's own
        // box bottom (25) is higher, so the ink decides.
        assertEquals(30f + 2f + pad + clear, b.bottom)
    }

    @Test
    fun `unionBounds of nothing is null`() {
        assertNull(PageLink.unionBounds(emptyList(), emptyList(), density))
    }

    @Test
    fun `bandBottom gives ink the padding a heading box builds in`() {
        val wide = stroke("a", 0f, 0f, 1f, 40f).copy(width = 6f)
        val thin = stroke("b", 0f, 0f, 1f, 40f).copy(width = 2f)
        // Point-tight bounds: the widest stroke's ink overhangs by width/2, then the heading pad.
        assertEquals(40f + 3f + pad + clear, PageLink.bandBottom(listOf(wide, thin), emptyList(), density))
        // A heading's box IS its bounds — the pad is already inside it, so only the clearance.
        assertEquals(25f + clear, PageLink.bandBottom(emptyList(), listOf(heading("h", 40f, 5f, 60f, 20f)), density))
        assertNull(PageLink.bandBottom(emptyList(), emptyList(), density))
    }

    @Test
    fun `withUnderlineBand grows a short band and never shrinks a long one`() {
        val s = stroke("s", 10f, 10f, 50f, 30f).copy(width = 4f)
        val needed = PageLink.bandBottom(listOf(s), emptyList(), density)!! - 10f
        // Written under an earlier, tighter band.
        val short = link().copy(x = 10f, y = 10f, width = 40f, height = 21f, strokes = listOf(s))
        assertEquals(needed, short.withUnderlineBand(density).height)
        // Idempotent — re-applying the wrap-time formula changes nothing.
        assertEquals(needed, short.withUnderlineBand(density).withUnderlineBand(density).height)
        // Never shrinks: a foreign link may wrap children this build cannot decode.
        assertEquals(200f, short.copy(height = 200f).withUnderlineBand(density).height)
        // Nothing decodable to measure — the stored bounds are taken on trust.
        assertEquals(21f, short.copy(strokes = emptyList()).withUnderlineBand(density).height)
    }

    @Test
    fun `translated shifts the link and every wrapped child`() {
        val l = link().copy(
            strokes = listOf(stroke("s", 0f, 0f, 5f, 5f)),
            headings = listOf(heading("h", 1f, 2f, 3f, 4f)),
        ).translated(10f, -5f)
        assertEquals(20f, l.x)
        assertEquals(15f, l.y)
        assertEquals(10f, l.strokes[0].points[0].x)
        assertEquals(11f, l.headings[0].x)
        assertEquals(listOf("s", "h"), l.childIds)
    }
}
