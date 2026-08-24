package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture → envelope → plan for a lasso selection: fresh ids, parent rewiring, the `"order"` rebase,
 * and the stroke round trip through the format-B blob — the parts of an object paste that can
 * corrupt a page silently.
 */
class ObjectClipTest {

    private val notebookId = "nb-src"
    private val srcPage = "page-1"
    private val dstPage = "page-2"
    private val now = 5_000L

    private fun stroke(id: String, x: Float, y: Float, width: Float = 4f) = Stroke(
        id = id,
        points = listOf(
            StrokePoint(x, y, pressure = 0.5f, tilt = 0.1f),
            StrokePoint(x + 10f, y + 20f, pressure = 1f, tilt = 0f),
        ),
        color = 0xFF000000.toInt(), width = width, style = StrokeStyle.PEN,
    )

    private fun headingRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        HeadingRows.toRow(
            Heading(id = id, text = "## Title", level = 2, x = x, y = y, width = 120f, height = 40f, order = order),
            parentId, now,
        )

    private fun linkRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        LinkRows.toRow(
            PageLink(
                id = id,
                payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9"),
                chrome = LinkPayload.CHROME_UNDERLINE,
                x = x, y = y, width = 200f, height = 90f, order = order,
                strokes = emptyList(), headings = emptyList(),
            ),
            parentId, now,
        )

    /** Loose ink + a heading + a link wrapping ink and a heading of its own (two levels). */
    private fun top(): List<SoilObjectEntity> = listOf(
        StrokeRows.toRow(stroke("s-a", 100f, 100f), srcPage, 3, now),
        StrokeRows.toRow(stroke("s-b", 140f, 100f), srcPage, 7, now),
        headingRow("h-1", srcPage, 2, 100f, 200f),
        linkRow("lnk-1", srcPage, 1, 300f, 300f),
    )

    private fun children(): List<SoilObjectEntity> = listOf(
        StrokeRows.toRow(stroke("s-wrapped", 310f, 310f), "lnk-1", 5, now),
        headingRow("h-wrapped", "lnk-1", 4, 320f, 340f),
    )

    private fun envelope(): ClipEnvelope =
        ObjectClip.capture(top(), children(), notebookId, now)!!

    private fun ids(): () -> String {
        var n = 0
        return { "new-${n++}" }
    }

    /** No shift, so a plan's geometry is comparable to the source's. */
    private val noMove: (Bounds) -> ObjectPlacement.Offset = { ObjectPlacement.Offset.NONE }

    private fun plan(
        env: ClipEnvelope = envelope(),
        bases: Map<String, Int> = emptyMap(),
        place: (Bounds) -> ObjectPlacement.Offset = noMove,
    ) = ObjectClip.plan(
        env = env, pageId = dstPage,
        baseOrder = { bases[it] ?: -1 },
        now = 9_000L, newId = ids(), place = place,
    )

    // ── capture ──────────────────────────────────────────────────────────────

    @Test
    fun `capture writes an objects envelope carrying every row and the source notebook`() {
        val env = envelope()
        assertEquals(ClipEnvelope.KIND_OBJECTS, env.kind)
        assertEquals(ClipEnvelope.VERSION, env.version)
        assertEquals(notebookId, env.sourceNotebookId)
        assertEquals(6, env.rows.size)
        assertTrue(env.rows.any { it.id == "s-wrapped" && it.parentId == "lnk-1" })
    }

    @Test
    fun `capture of nothing is no clipboard at all`() {
        assertNull(ObjectClip.capture(emptyList(), emptyList(), notebookId, now))
    }

    // ── ids and parents ──────────────────────────────────────────────────────

    @Test
    fun `every pasted row gets a fresh id`() {
        val p = plan()!!
        val old = (top() + children()).map { it.id }.toSet()
        for (row in p.rows) assertTrue("$row kept a source id", row.id !in old)
        assertEquals(6, p.rows.map { it.id }.toSet().size)
        assertEquals(6, p.contentIds.size)
    }

    @Test
    fun `top-level rows parent onto the destination page and wrapped children onto the copied link`() {
        val p = plan()!!
        val link = p.links.single()
        assertNotEquals("lnk-1", link.id)
        for (row in p.rows) {
            val expected = if (row.type == SoilSchema.TYPE_STROKE && row.parentId != dstPage) link.id else row.parentId
            assertTrue(row.parentId == dstPage || row.parentId == link.id)
            assertEquals(expected, row.parentId)
        }
        assertEquals(1, link.strokes.size)
        assertEquals(1, link.headings.size)
        assertEquals(2, p.strokes.size)      // the wrapped stroke is the link's, not the page's
        assertEquals(1, p.headings.size)
    }

    @Test
    fun `a child whose parent did not travel is dropped, never re-parented onto the page`() {
        val orphan = StrokeRows.toRow(stroke("s-orphan", 10f, 10f), "lnk-gone", 0, now)
        val env = ObjectClip.capture(top(), children() + orphan, notebookId, now)!!
        val p = plan(env)!!
        // Two loose + one wrapped = three strokes; the orphan is not one of them.
        assertEquals(3, p.rows.count { it.type == SoilSchema.TYPE_STROKE })
    }

    @Test
    fun `a link nested inside a link is refused rather than flattened`() {
        val nested = linkRow("lnk-2", "lnk-1", 0, 400f, 400f)
        val env = ObjectClip.capture(top(), children() + nested, notebookId, now)!!
        val p = plan(env)!!
        assertEquals(1, p.links.size)
    }

    // ── order rebase ─────────────────────────────────────────────────────────

    @Test
    fun `order is rebased per type, keeping the relative sequence`() {
        val bases = mapOf(
            SoilSchema.TYPE_STROKE to 11,
            SoilSchema.TYPE_HEADING to 4,
            SoilSchema.TYPE_LINK to 0,
        )
        val p = plan(bases = bases)!!
        val strokes = p.rows.filter { it.type == SoilSchema.TYPE_STROKE && it.parentId == dstPage }
        // s-a (order 3) then s-b (order 7) → 12, 13.
        assertEquals(listOf(12, 13), strokes.map { it.order })
        assertEquals(5, p.rows.single { it.type == SoilSchema.TYPE_HEADING && it.parentId == dstPage }.order)
        assertEquals(1, p.rows.single { it.type == SoilSchema.TYPE_LINK }.order)
    }

    @Test
    fun `wrapped children keep their own order verbatim`() {
        val p = plan(bases = mapOf(SoilSchema.TYPE_STROKE to 40))!!
        val link = p.links.single()
        assertEquals(5, p.rows.single { it.parentId == link.id && it.type == SoilSchema.TYPE_STROKE }.order)
        assertEquals(4, p.rows.single { it.parentId == link.id && it.type == SoilSchema.TYPE_HEADING }.order)
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    @Test
    fun `a stroke survives the decode-translate-re-encode round trip`() {
        val p = plan(place = { ObjectPlacement.Offset(25f, -10f) })!!
        val moved = p.strokes.sortedBy { it.bounds.left }
        assertEquals(125f, moved[0].bounds.left, 0.01f)
        assertEquals(90f, moved[0].bounds.top, 0.01f)
        assertEquals(2, moved[0].points.size)
        assertEquals(0.5f, moved[0].points[0].pressure, 0.01f)
        assertEquals(0.1f, moved[0].points[0].tilt, 0.01f)
        assertEquals(4f, moved[0].width, 0.01f)
    }

    @Test
    fun `headings and links translate by their columns, wrapped children with them`() {
        val p = plan(place = { ObjectPlacement.Offset(25f, -10f) })!!
        assertEquals(125f, p.headings.single().x, 0.01f)
        assertEquals(190f, p.headings.single().y, 0.01f)
        val link = p.links.single()
        assertEquals(325f, link.x, 0.01f)
        assertEquals(290f, link.y, 0.01f)
        assertEquals(345f, link.headings.single().x, 0.01f)
        assertEquals(335f, link.strokes.single().bounds.left, 0.01f)
    }

    @Test
    fun `the box handed to the placement is the ink extent, not the point-tight bounds`() {
        var seen: Bounds? = null
        plan(place = { seen = it; ObjectPlacement.Offset.NONE })
        // s-a starts at x=100 with width 4 → the ink reaches 98; the heading's box starts at 100.
        assertEquals(98f, seen!!.left, 0.01f)
        assertEquals(98f, seen!!.top, 0.01f)
        // The link's box (300..500) is the rightmost thing; wrapped children never widen it.
        assertEquals(500f, seen!!.right, 0.01f)
    }

    // ── untrusted payloads ───────────────────────────────────────────────────

    @Test
    fun `a payload with only a page row plans nothing`() {
        val pageRow = SoilObjectEntity(
            id = srcPage, parentId = notebookId, type = SoilSchema.TYPE_PAGE,
            createdAt = 1L, updatedAt = 1L,
        )
        val env = ObjectClip.capture(listOf(pageRow), emptyList(), notebookId, now)!!
        assertNull(plan(env))
    }

    @Test
    fun `a stroke whose blob is unusable costs that stroke, not the paste`() {
        val bad = StrokeRows.toRow(stroke("s-bad", 500f, 500f), srcPage, 9, now).copy(blob = byteArrayOf(1, 2))
        val env = ObjectClip.capture(top() + bad, children(), notebookId, now)!!
        val p = plan(env)!!
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
        assertEquals(1, p.links.size)
    }

    @Test
    fun `the envelope round-trips through the clipboard codec unchanged`() {
        val bytes = ClipEnvelope.encode(envelope())!!
        val back = ClipEnvelope.decode(bytes)!!
        assertEquals(ClipEnvelope.KIND_OBJECTS, back.kind)
        val p = ObjectClip.plan(
            env = back, pageId = dstPage, baseOrder = { -1 },
            now = 9_000L, newId = ids(), place = noMove,
        )!!
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
        assertEquals(1, p.links.size)
        assertEquals(1, p.links.single().strokes.size)
    }
}
