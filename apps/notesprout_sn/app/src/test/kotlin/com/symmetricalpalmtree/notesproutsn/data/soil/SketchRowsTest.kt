package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SketchRows] — the border between a page's picture and a row of the `.soil` (arc 43 / K3,
 * per-layer since arc 45 / G2). The whole file is pure, so the whole file is provable here: which
 * row each layer becomes, the bytes a read gets back, and the guard that keeps a stranger's image
 * off this page.
 */
class SketchRowsTest {

    private val pageId = "page-1"
    private val now = 7_000L
    private val graphite = SketchContract.LAYER_GRAPHITE
    private val ink = SketchContract.LAYER_INK

    // ── typeFor ──────────────────────────────────────────────────────────────

    @Test
    fun `each layer names its own row type`() {
        assertEquals(SoilSchema.TYPE_SKETCH_GRAPHITE, SketchRows.typeFor(graphite))
        assertEquals(SoilSchema.TYPE_SKETCH_INK, SketchRows.typeFor(ink))
    }

    /** Unknown is refused, never defaulted to graphite: filing a stranger's pixels under the
     *  pencil's row is how a drawing gets overwritten by something nobody drew. */
    @Test
    fun `an unknown layer is refused rather than defaulted`() {
        for (layer in listOf(-1, 2, 99, Int.MAX_VALUE)) {
            val thrown = runCatching { SketchRows.typeFor(layer) }.exceptionOrNull()
            assertTrue("layer $layer", thrown is IllegalArgumentException)
        }
    }

    /** The two names are not the same name — the whole of the layering lives in them. */
    @Test
    fun `the two row types are distinct and are never the dead one`() {
        assertTrue(SoilSchema.TYPE_SKETCH_GRAPHITE != SoilSchema.TYPE_SKETCH_INK)
        assertTrue(SoilSchema.TYPE_SKETCH_GRAPHITE != SoilSchema.TYPE_SKETCH_DEAD)
        assertTrue(SoilSchema.TYPE_SKETCH_INK != SoilSchema.TYPE_SKETCH_DEAD)
    }

    // ── toRow ────────────────────────────────────────────────────────────────

    @Test
    fun `the row is the page's child, out of every z-order space, carrying the image`() {
        val bytes = TestWebp.webp(100, 200, totalBytes = 64)
        val row = SketchRows.toRow(pageId, graphite, bytes, "sk-1", now)
        assertEquals("sk-1", row.id)
        assertEquals(pageId, row.parentId)
        assertEquals(SoilSchema.TYPE_SKETCH_GRAPHITE, row.type)
        assertEquals(SoilSchema.SKETCH_ORDER, row.order)
        assertEquals(now, row.createdAt)
        assertEquals(now, row.updatedAt)
        assertNull(row.deletedAt)
        assertArrayEquals(bytes, row.blob)
    }

    /** The ink row is the graphite row in every respect but its type — including its `"order"`,
     *  because neither raster is above the other (the flatten is order-independent). */
    @Test
    fun `the ink row differs from the graphite row only in its type`() {
        val bytes = TestWebp.webp(100, 200, totalBytes = 64)
        val g = SketchRows.toRow(pageId, graphite, bytes, "sk-g", now)
        val i = SketchRows.toRow(pageId, ink, bytes, "sk-i", now)
        assertEquals(SoilSchema.TYPE_SKETCH_INK, i.type)
        assertEquals(g.order, i.order)
        assertEquals(g.parentId, i.parentId)
        assertEquals(g.createdAt, i.createdAt)
    }

    /** Everything but the blob is null: a raster has no text, no geometry and no colour of its own
     *  — the picture is all of it. */
    @Test
    fun `every other column is left null`() {
        val row = SketchRows.toRow(pageId, ink, TestWebp.webp(10, 10, 64), "sk-1", now)
        assertNull(row.text)
        assertNull(row.refId)
        assertNull(row.x)
        assertNull(row.y)
        assertNull(row.width)
        assertNull(row.height)
        assertNull(row.color)
        assertNull(row.strokeWidth)
        assertNull(row.style)
        assertNull(row.flags)
    }

    /** The id is the caller's — the live row's on a replace, a fresh one on the first save. A
     *  border that minted its own would make a second row per save. */
    @Test
    fun `the id is the caller's, verbatim`() {
        assertEquals("kept", SketchRows.toRow(pageId, graphite, TestWebp.webp(4, 4, 40), "kept", now).id)
    }

    @Test
    fun `toRow refuses an unknown layer before it builds anything`() {
        val thrown = runCatching {
            SketchRows.toRow(pageId, 7, TestWebp.webp(4, 4, 40), "sk-1", now)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    // ── imageBytes ───────────────────────────────────────────────────────────

    @Test
    fun `imageBytes gives either live raster's blob back verbatim`() {
        val bytes = TestWebp.webp(100, 200, totalBytes = 90)
        assertArrayEquals(bytes, SketchRows.imageBytes(SketchRows.toRow(pageId, graphite, bytes, "g", now)))
        assertArrayEquals(bytes, SketchRows.imageBytes(SketchRows.toRow(pageId, ink, bytes, "i", now)))
    }

    @Test
    fun `imageBytes refuses a row of another type rather than trusting its blob`() {
        // A template's WEBP, a stroke's geometry: handing either to an image decoder is how a page
        // comes back as something nobody drew.
        val template = SoilObjectEntity(
            id = "t1", parentId = "nb", type = SoilSchema.TYPE_TEMPLATE, order = 0,
            createdAt = now, updatedAt = now, blob = TestWebp.webp(100, 200, 90),
        )
        assertNull(SketchRows.imageBytes(template))
    }

    /** The arc-43 row name is refused with every other foreign type (decision 4: no legacy). Its
     *  blob *is* an image — a PNG — which is exactly why it must never reach a reader. */
    @Test
    fun `imageBytes refuses the dead arc-43 sketch row`() {
        val dead = SoilObjectEntity(
            id = "sk-old", parentId = pageId, type = SoilSchema.TYPE_SKETCH_DEAD,
            order = SoilSchema.SKETCH_ORDER, createdAt = now, updatedAt = now,
            blob = TestWebp.legacyPng(100, 200, 90),
        )
        assertNull(SketchRows.imageBytes(dead))
    }

    @Test
    fun `an absent or empty blob is no raster at all`() {
        val noBlob = SketchRows.toRow(pageId, graphite, ByteArray(0), "sk-1", now).copy(blob = null)
        assertNull(SketchRows.imageBytes(noBlob))
        assertNull(SketchRows.imageBytes(SketchRows.toRow(pageId, ink, ByteArray(0), "sk-2", now)))
    }

    // ── fitsPage ─────────────────────────────────────────────────────────────

    @Test
    fun `fitsPage is exact in both directions`() {
        val bytes = TestWebp.webp(1404, 1685, totalBytes = 64)
        assertTrue(SketchRows.fitsPage(bytes, 1404, 1685))
        assertFalse(SketchRows.fitsPage(bytes, 1404, 1686))
        assertFalse(SketchRows.fitsPage(bytes, 1405, 1685))
        // Not "no larger than": a smaller image is some other page, not this one drawn small.
        assertFalse(SketchRows.fitsPage(TestWebp.webp(700, 800, 64), 1404, 1685))
        // And not the other way round either.
        assertFalse(SketchRows.fitsPage(TestWebp.webp(1860, 2480, 64), 1404, 1685))
    }

    @Test
    fun `a page with no size fails the guard — there is nothing to check against`() {
        assertFalse(SketchRows.fitsPage(TestWebp.webp(1404, 1685, 64), 0, 1685))
        assertFalse(SketchRows.fitsPage(TestWebp.webp(1404, 1685, 64), 1404, 0))
        assertFalse(SketchRows.fitsPage(TestWebp.webp(1, 1, 64), 0, 0))
    }

    @Test
    fun `bytes that are not a WebP never fit`() {
        assertFalse(SketchRows.fitsPage(TestWebp.notAWebp(), 1404, 1685))
        assertFalse(SketchRows.fitsPage(ByteArray(0), 1404, 1685))
        // Truncated inside the header: the parse must never read past what it was given.
        assertFalse(SketchRows.fitsPage(TestWebp.webp(1404, 1685).copyOf(20), 1404, 1685))
    }

    /** A PNG of exactly the page's size is still not a WebP, so it still does not fit — the whole
     *  of "no legacy" at this level, with no sniffing anywhere. */
    @Test
    fun `an arc-43 PNG of the right size never fits`() {
        assertFalse(SketchRows.fitsPage(TestWebp.legacyPng(1404, 1685, 200), 1404, 1685))
    }
}
