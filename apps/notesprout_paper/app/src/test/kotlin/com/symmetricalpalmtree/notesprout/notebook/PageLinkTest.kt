package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageLinkTest {

    private fun stroke(id: String, vararg pts: Pair<Float, Float>) =
        Stroke(id = id, points = pts.map { StrokePoint(it.first, it.second) })

    private val s1 = stroke("s1", 10f to 20f, 40f to 60f)
    private val s2 = stroke("s2", 5f to 80f, 30f to 90f)
    private val o1 = PageObject(
        id = "o1", providerIdentity = "com.example.ext:heading", payload = "## Title",
        x = 50f, y = 10f, width = 100f, height = 30f, order = 0,
    )

    private val link = PageLink(
        id = "l1", providerIdentity = "com.example.ext:link", payload = "L1|Page 3|page|nb-1|pg-3",
        x = 5f, y = 10f, width = 145f, height = 96f, order = 2,
        strokes = listOf(s1, s2), objects = listOf(o1),
    )

    // ── unionBounds ──────────────────────────────────────────────────────────

    @Test
    fun unionOfStrokesOnly() {
        val b = PageLink.unionBounds(listOf(s1, s2), emptyList(), 0f)!!
        assertEquals(5f, b.left, 0f); assertEquals(20f, b.top, 0f)
        assertEquals(40f, b.right, 0f); assertEquals(90f, b.bottom, 0f)
    }

    @Test
    fun unionOfObjectsOnly() {
        val b = PageLink.unionBounds(emptyList(), listOf(o1), 0f)!!
        assertEquals(50f, b.left, 0f); assertEquals(10f, b.top, 0f)
        assertEquals(150f, b.right, 0f); assertEquals(40f, b.bottom, 0f)
    }

    @Test
    fun unionOfBothTakesMinAndMax() {
        val b = PageLink.unionBounds(listOf(s1, s2), listOf(o1), 0f)!!
        assertEquals(5f, b.left, 0f); assertEquals(10f, b.top, 0f)
        assertEquals(150f, b.right, 0f); assertEquals(90f, b.bottom, 0f)
    }

    @Test
    fun unionOfNothingIsNull() {
        assertNull(PageLink.unionBounds(emptyList(), emptyList(), 12f))
    }

    @Test
    fun bottomClearanceExtendsOnlyTheBottom() {
        val plain = PageLink.unionBounds(listOf(s1, s2), listOf(o1), 0f)!!
        val cleared = PageLink.unionBounds(listOf(s1, s2), listOf(o1), 16f)!!
        assertEquals(plain.left, cleared.left, 0f)
        assertEquals(plain.top, cleared.top, 0f)
        assertEquals(plain.right, cleared.right, 0f)
        assertEquals(plain.bottom + 16f, cleared.bottom, 0f)
    }

    // ── model helpers ────────────────────────────────────────────────────────

    @Test
    fun boundsFromXywh() {
        val b = link.bounds
        assertEquals(5f, b.left, 0f); assertEquals(10f, b.top, 0f)
        assertEquals(150f, b.right, 0f); assertEquals(106f, b.bottom, 0f)
    }

    @Test
    fun translatedShiftsLinkAndEveryChild() {
        val moved = link.translated(7f, -3f)
        assertEquals(12f, moved.x, 0f); assertEquals(7f, moved.y, 0f)
        assertEquals(link.width, moved.width, 0f); assertEquals(link.height, moved.height, 0f)
        assertEquals(link.id, moved.id); assertEquals(link.order, moved.order)
        assertEquals(17f, moved.strokes[0].points[0].x, 0f)
        assertEquals(17f, moved.strokes[0].points[0].y, 0f)
        assertEquals(47f, moved.strokes[0].points[1].x, 0f)
        assertEquals(12f, moved.strokes[1].points[0].x, 0f)
        assertEquals(57f, moved.objects[0].x, 0f); assertEquals(7f, moved.objects[0].y, 0f)
        // Child ids ride along untouched — a move is not a re-mint.
        assertEquals(listOf("s1", "s2", "o1"), moved.childIds)
    }

    @Test
    fun childIdsAreStrokesThenObjects() {
        assertEquals(listOf("s1", "s2", "o1"), link.childIds)
    }

    // ── LinkRows ─────────────────────────────────────────────────────────────

    @Test
    fun roundTrip() {
        val row = LinkRows.toRow(link, "page-1", now = 1234L)
        assertEquals(SoilSchema.TYPE_LINK, row.type)
        assertEquals("page-1", row.parentId)
        assertEquals("com.example.ext:link", row.style)
        assertEquals("L1|Page 3|page|nb-1|pg-3", row.text)
        assertEquals(2, row.order)
        assertEquals(1234L, row.createdAt)
        assertNull(row.deletedAt)
        assertNull(row.refId); assertNull(row.color); assertNull(row.strokeWidth); assertNull(row.flags); assertNull(row.blob)
        assertEquals(link, LinkRows.toLink(row, listOf(s1, s2), listOf(o1)))
    }

    @Test
    fun wrongTypeOrNoIdentityRejected() {
        val row = LinkRows.toRow(link, "p", 1L)
        assertNull(LinkRows.toLink(row.copy(type = SoilSchema.TYPE_OBJECT), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(style = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(style = "  "), emptyList(), emptyList()))
    }

    @Test
    fun missingBoundsRejected() {
        val row = LinkRows.toRow(link, "p", 1L)
        assertNull(LinkRows.toLink(row.copy(x = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(y = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(width = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(height = null), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(height = -1f), emptyList(), emptyList()))
        assertNull(LinkRows.toLink(row.copy(y = Float.NaN), emptyList(), emptyList()))
    }

    @Test
    fun nullPayloadReadsAsEmpty() {
        val row = LinkRows.toRow(link, "p", 1L).copy(text = null)
        assertEquals("", LinkRows.toLink(row, emptyList(), emptyList())!!.payload)
    }

    @Test
    fun payloadCappedBothWays() {
        val long = "x".repeat(ExtensionContract.MAX_LINK_PAYLOAD_CHARS + 500)
        val row = LinkRows.toRow(link.copy(payload = long), "p", 1L)
        assertEquals(ExtensionContract.MAX_LINK_PAYLOAD_CHARS, row.text!!.length)
        // An over-long payload already in the file (untrusted input) is capped on the way in too.
        val fromFile: SoilObjectEntity = row.copy(text = long)
        assertEquals(
            ExtensionContract.MAX_LINK_PAYLOAD_CHARS,
            LinkRows.toLink(fromFile, emptyList(), emptyList())!!.payload.length,
        )
        val exact = "y".repeat(ExtensionContract.MAX_LINK_PAYLOAD_CHARS)
        assertEquals(exact, LinkRows.cap(exact))
    }

    @Test
    fun zOrderPreserved() {
        val row = LinkRows.toRow(link.copy(order = 7), "p", 1L)
        assertNotNull(LinkRows.toLink(row, emptyList(), emptyList()))
        assertEquals(7, LinkRows.toLink(row, emptyList(), emptyList())!!.order)
    }
}
