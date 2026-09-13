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

    private fun linkRow(
        id: String,
        parentId: String,
        order: Int,
        x: Float,
        y: Float,
        payload: String =
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9"),
    ) = LinkRows.toRow(
        PageLink(
            id = id,
            payload = payload,
            chrome = LinkPayload.chromeOf(payload),
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
        into: String = notebookId,
    ) = ObjectClip.plan(
        env = env, notebookId = into, pageId = dstPage,
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
    fun `orphans cannot outvote the page they are not parented to`() {
        // The O2 review's case: a payload whose link row is missing but whose wrapped children are
        // present. Under a majority vote the two orphans won, so they were written loose onto the
        // page while the one genuine top-level row was dropped — the untrusted-payload rule exactly
        // backwards. Capture writes top-level rows first, and that is the signal.
        val env = ObjectClip.capture(
            listOf(StrokeRows.toRow(stroke("s-a", 100f, 100f), srcPage, 3, now)),
            listOf(
                StrokeRows.toRow(stroke("orphan-a", 10f, 10f), "lnk-gone", 0, now),
                StrokeRows.toRow(stroke("orphan-b", 20f, 20f), "lnk-gone", 1, now),
            ),
            notebookId, now,
        )!!
        val p = plan(env)!!
        assertEquals(1, p.strokes.size)
        assertEquals(listOf(dstPage), p.rows.map { it.parentId })
        assertEquals(100f, p.strokes.single().bounds.left, 0.01f)
    }

    @Test
    fun `a link row's parent names the page outright — a link is top-level by definition`() {
        // Even outnumbered: one link and four rows parented to an absent link id.
        val orphans = (0 until 4).map {
            StrokeRows.toRow(stroke("orphan-$it", 10f, 10f), "lnk-gone", it, now)
        }
        val env = ObjectClip.capture(
            orphans + linkRow("lnk-1", srcPage, 9, 300f, 300f), emptyList(), notebookId, now,
        )!!
        val p = plan(env)!!
        assertEquals(1, p.links.size)
        assertTrue(p.strokes.isEmpty())
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

    // ── links across notebooks (O2) ──────────────────────────────────────────

    private val otherNotebook = "nb-dst"

    /** One link, alone on the page, carrying [payload]; planned into [into]. */
    private fun pastedLinkPayload(payload: String, into: String): String {
        val env = ObjectClip.capture(
            listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f, payload)),
            emptyList(), notebookId, now,
        )!!
        return plan(env, into = into)!!.links.single().payload
    }

    @Test
    fun `an own-notebook link is re-pointed at the source notebook when it crosses`() {
        val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")
        val out = LinkPayload.decode(pastedLinkPayload(payload, otherNotebook))!!
        assertEquals(LinkPayload.KIND_NOTEBOOK_PAGE, out.kind)
        assertEquals(notebookId, out.notebookId)
        assertEquals("page-9", out.pageId)
        assertEquals(LinkPayload.CHROME_UNDERLINE, out.chrome)
    }

    @Test
    fun `a link to the source page itself is re-pointed like any other — no page travels`() {
        // PageClip's self-page exception cannot arise here: an objects payload carries no page row.
        val payload = LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, null, srcPage)
        val out = LinkPayload.decode(pastedLinkPayload(payload, otherNotebook))!!
        assertEquals(LinkPayload.KIND_NOTEBOOK_PAGE, out.kind)
        assertEquals(notebookId, out.notebookId)
        assertEquals(srcPage, out.pageId)
    }

    @Test
    fun `a same-notebook paste leaves every payload verbatim`() {
        val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")
        assertEquals(payload, pastedLinkPayload(payload, notebookId))
    }

    @Test
    fun `a payload that already names its notebook crosses unchanged`() {
        for (payload in listOf(
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, "nb-far", null),
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_NOTEBOOK_PAGE, "nb-far", "page-9"),
            // Including one naming the source page explicitly: the original is still there.
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK_PAGE, notebookId, srcPage),
        )) {
            assertEquals(payload, pastedLinkPayload(payload, otherNotebook))
        }
    }

    @Test
    fun `a Bible reference crosses verbatim — it names no notebook to re-point`() {
        // Arc 38 / R3: kind 3's notebookId *slot* holds a reference wire, so a re-pointer that
        // rewrote it would turn a passage into a notebook id. Only KIND_PAGE is ever re-pointed.
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "JHN:3:14-3:18,PRO:3:5-3:6", null,
        )
        assertEquals(payload, pastedLinkPayload(payload, otherNotebook))
        assertEquals(payload, pastedLinkPayload(payload, notebookId))
    }

    @Test
    fun `a payload this build cannot read crosses verbatim rather than being invented a target`() {
        for (payload in listOf("L9|1|0||page-9", "", "not a payload at all")) {
            assertEquals(payload, pastedLinkPayload(payload, otherNotebook))
        }
    }

    @Test
    fun `a blank source notebook leaves own-notebook links alone — there is nothing to name`() {
        val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")
        val env = ObjectClip.capture(
            listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f, payload)), emptyList(), "", now,
        )!!
        assertEquals(payload, plan(env, into = otherNotebook)!!.links.single().payload)
    }

    @Test
    fun `the rewrite touches links only — a heading's text crosses untouched`() {
        val p = plan(into = otherNotebook)!!
        assertEquals("## Title", p.headings.single().text)
        assertEquals("## Title", p.links.single().headings.single().text)
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

    // ── Arc 28 (H1): text objects, shapes and sticky notes ──────────────────

    private fun textRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        TextRows.toRow(
            PageText(id = id, text = "an **object**", x = x, y = y, width = 80f, height = 30f, order = order),
            parentId, now,
        )

    private fun shapeRow(
        id: String, parentId: String, order: Int, cx: Float, cy: Float,
        rotation: Float = 0f, strokeWidth: Float = 4f,
    ) = ShapeRows.toRow(
        PageShape(
            id = id, type = ShapeType.RECTANGLE, cx = cx, cy = cy, width = 100f, height = 50f,
            strokeWidth = strokeWidth, rotationDeg = rotation, aspectLocked = true,
            pointCount = ShapeFlags.DEFAULT_POINTS, order = order,
        ),
        parentId, now,
    )

    private fun stickyRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        StickyRows.toRow(
            PageSticky(
                id = id, x = x, y = y, width = 72f, height = 72f,
                contentW = 1404, contentH = 1800, order = order,
            ),
            parentId, now,
        )

    /** One of each new kind loose on the page, the note holding a stroke of its own. */
    private fun objectEnvelope(): ClipEnvelope = ObjectClip.capture(
        listOf(
            textRow("t-1", srcPage, 2, 100f, 100f),
            shapeRow("sh-1", srcPage, 4, 300f, 300f),
            stickyRow("n-1", srcPage, 6, 500f, 100f),
        ),
        listOf(StrokeRows.toRow(stroke("n-ink", 5f, 5f), "n-1", 0, now)),
        notebookId, now,
    )!!

    @Test
    fun `the three new kinds travel with fresh ids and a per-type order rebase`() {
        val bases = mapOf(
            SoilSchema.TYPE_TEXT to 3,
            SoilSchema.TYPE_SHAPE to 0,
            SoilSchema.TYPE_STICKY to 11,
        )
        val p = plan(objectEnvelope(), bases = bases)!!
        assertEquals(1, p.texts.size)
        assertEquals(1, p.shapes.size)
        assertEquals(1, p.stickies.size)
        val old = setOf("t-1", "sh-1", "n-1", "n-ink")
        for (row in p.rows) assertTrue("$row kept a source id", row.id !in old)
        assertEquals(4, p.contentIds.size)     // the note's ink is a pasted row too
        assertEquals(4, p.rows.single { it.type == SoilSchema.TYPE_TEXT }.order)
        assertEquals(1, p.rows.single { it.type == SoilSchema.TYPE_SHAPE }.order)
        assertEquals(12, p.rows.single { it.type == SoilSchema.TYPE_STICKY }.order)
        // The shape's packed flags survive the trip.
        assertEquals(ShapeType.RECTANGLE, p.shapes.single().type)
        assertTrue(p.shapes.single().aspectLocked)
    }

    @Test
    fun `a shape is placed by its rotated outline, not by its columns`() {
        // A shape's x/y is a CENTRE and its outline is a centre line: read as a box the payload
        // would be placed a whole half-size and half a stroke out.
        var seen: Bounds? = null
        val env = ObjectClip.capture(
            listOf(shapeRow("sh-1", srcPage, 0, 300f, 300f, strokeWidth = 4f)), emptyList(), notebookId, now,
        )!!
        plan(env, place = { seen = it; ObjectPlacement.Offset.NONE })
        assertEquals(300f - 50f - 2f, seen!!.left, 0.01f)
        assertEquals(300f - 25f - 2f, seen!!.top, 0.01f)
        assertEquals(300f + 50f + 2f, seen!!.right, 0.01f)
        assertEquals(300f + 25f + 2f, seen!!.bottom, 0.01f)
    }

    /**
     * The same rule with the shape turned (arc 28 / H4): a rotated star is placed by the **rotated**
     * outline's box ([ShapeGeometry.tightBounds] grown by half its own stroke — the density-free
     * pad, since a clipboard payload is measured in page px and not on any one device's screen),
     * never by the un-rotated box its four columns describe. A paste placed from the columns would
     * land the star out by however far the rotation carried its points, and rotation is exactly
     * what H4 gave the user.
     */
    @Test
    fun `a rotated shape is placed by its rotated outline`() {
        var seen: Bounds? = null
        val star = PageShape(
            id = "st-1", type = ShapeType.STAR, cx = 300f, cy = 300f, width = 100f, height = 50f,
            strokeWidth = 4f, rotationDeg = 37f, aspectLocked = false,
            pointCount = ShapeFlags.DEFAULT_POINTS, order = 0,
        )
        val env = ObjectClip.capture(
            listOf(ShapeRows.toRow(star, srcPage, now)), emptyList(), notebookId, now,
        )!!
        val p = plan(env, place = { seen = it; ObjectPlacement.Offset.NONE })!!
        val expected = ShapeGeometry.tightBounds(star).inflated(star.strokeWidth / 2f)
        assertEquals(expected.left, seen!!.left, 0.01f)
        assertEquals(expected.top, seen!!.top, 0.01f)
        assertEquals(expected.right, seen!!.right, 0.01f)
        assertEquals(expected.bottom, seen!!.bottom, 0.01f)
        // …and the angle itself survives the trip through the packed flags word.
        assertEquals(37f, p.shapes.single().rotationDeg, 0f)
        assertEquals(ShapeType.STAR, p.shapes.single().type)
    }

    @Test
    fun `a note's content travels with fresh ids, un-shifted, re-parented onto the copy`() {
        val p = plan(objectEnvelope(), place = { ObjectPlacement.Offset(25f, -10f) })!!
        val note = p.stickies.single()
        assertEquals(525f, note.x, 0.01f)          // the icon moves…
        assertEquals(90f, note.y, 0.01f)
        val ink = note.strokes.single()
        assertNotEquals("n-ink", ink.id)
        assertEquals(5f, ink.bounds.left, 0.01f)   // …its local ink does not
        assertEquals(5f, ink.bounds.top, 0.01f)
        // The pasted stroke row hangs off the pasted note, never off the page.
        val inkRow = p.rows.single { it.type == SoilSchema.TYPE_STROKE }
        assertEquals(note.id, inkRow.parentId)
        assertTrue(ink.id in p.contentIds)
    }

    @Test
    fun `a sticky inside a link travels three levels deep`() {
        val env = ObjectClip.capture(
            listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f)),
            listOf(
                stickyRow("n-1", "lnk-1", 0, 320f, 320f),
                textRow("t-1", "lnk-1", 0, 330f, 330f),
                StrokeRows.toRow(stroke("n-ink", 7f, 7f), "n-1", 0, now),
            ),
            notebookId, now,
        )!!
        val p = plan(env, place = { ObjectPlacement.Offset(10f, 10f) })!!
        val link = p.links.single()
        assertEquals(1, link.stickies.size)
        assertEquals(1, link.texts.size)
        assertTrue(p.stickies.isEmpty())           // it is the link's, not the page's
        val note = link.stickies.single()
        assertNotEquals("n-1", note.id)
        assertEquals(330f, note.x, 0.01f)          // wrapped children are page-absolute
        assertEquals(340f, link.texts.single().x, 0.01f)
        val ink = note.strokes.single()
        assertEquals(7f, ink.bounds.left, 0.01f)   // …the note's own ink is not
        assertEquals(note.id, p.rows.single { it.type == SoilSchema.TYPE_STROKE }.parentId)
    }

    @Test
    fun `a note's orphaned ink is dropped, never re-parented onto the page`() {
        val env = ObjectClip.capture(
            listOf(StrokeRows.toRow(stroke("s-a", 100f, 100f), srcPage, 0, now)),
            listOf(StrokeRows.toRow(stroke("orphan", 10f, 10f), "sticky-gone", 0, now)),
            notebookId, now,
        )!!
        val p = plan(env)!!
        assertEquals(1, p.strokes.size)
        assertEquals(listOf(dstPage), p.rows.map { it.parentId })
        assertEquals(100f, p.strokes.single().bounds.left, 0.01f)
    }

    @Test
    fun `a row of the wrong kind under a note is refused rather than pasted`() {
        // Only stroke rows may be a note's children (decision 3).
        val env = ObjectClip.capture(
            listOf(stickyRow("n-1", srcPage, 0, 500f, 100f)),
            listOf(headingRow("h-inside", "n-1", 0, 10f, 10f)),
            notebookId, now,
        )!!
        val p = plan(env)!!
        assertEquals(1, p.stickies.size)
        assertTrue(p.stickies.single().strokes.isEmpty())
        assertTrue(p.headings.isEmpty())
        assertEquals(1, p.rows.size)
    }

    @Test
    fun `the envelope round-trips through the clipboard codec unchanged`() {
        val bytes = ClipEnvelope.encode(envelope())!!
        val back = ClipEnvelope.decode(bytes)!!
        assertEquals(ClipEnvelope.KIND_OBJECTS, back.kind)
        val p = ObjectClip.plan(
            env = back, notebookId = notebookId, pageId = dstPage, baseOrder = { -1 },
            now = 9_000L, newId = ids(), place = noMove,
        )!!
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
        assertEquals(1, p.links.size)
        assertEquals(1, p.links.single().strokes.size)
    }
}
