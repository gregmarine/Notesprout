package com.symmetricalpalmtree.notesproutsn.data.soil

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SketchRows] — the border between a page's picture and a row of the `.soil` (arc 43 / K3). The
 * whole file is pure, so the whole file is provable here: the row a save makes, the bytes a read
 * gets back, and the guard that keeps a stranger's image off this page.
 */
class SketchRowsTest {

    private val pageId = "page-1"
    private val now = 7_000L

    // ── toRow ────────────────────────────────────────────────────────────────

    @Test
    fun `the row is the page's child, out of every z-order space, carrying the PNG`() {
        val png = TestPng.png(100, 200, totalBytes = 64)
        val row = SketchRows.toRow(pageId, png, "sk-1", now)
        assertEquals("sk-1", row.id)
        assertEquals(pageId, row.parentId)
        assertEquals(SoilSchema.TYPE_SKETCH, row.type)
        assertEquals(SoilSchema.SKETCH_ORDER, row.order)
        assertEquals(now, row.createdAt)
        assertEquals(now, row.updatedAt)
        assertNull(row.deletedAt)
        assertArrayEquals(png, row.blob)
    }

    /** Everything but the blob is null: a sketch has no text, no geometry and no colour of its own
     *  — the picture is all of it. */
    @Test
    fun `every other column is left null`() {
        val row = SketchRows.toRow(pageId, TestPng.png(10, 10, 64), "sk-1", now)
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
        assertEquals("kept", SketchRows.toRow(pageId, TestPng.png(4, 4, 40), "kept", now).id)
    }

    // ── pngBytes ─────────────────────────────────────────────────────────────

    @Test
    fun `pngBytes gives a sketch row's blob back verbatim`() {
        val png = TestPng.png(100, 200, totalBytes = 90)
        assertArrayEquals(png, SketchRows.pngBytes(SketchRows.toRow(pageId, png, "sk-1", now)))
    }

    @Test
    fun `pngBytes refuses a row of another type rather than trusting its blob`() {
        // A template's WEBP, a stroke's geometry: handing either to an image decoder is how a page
        // comes back as something nobody drew.
        val template = SoilObjectEntity(
            id = "t1", parentId = "nb", type = SoilSchema.TYPE_TEMPLATE, order = 0,
            createdAt = now, updatedAt = now, blob = TestPng.png(100, 200, 90),
        )
        assertNull(SketchRows.pngBytes(template))
    }

    @Test
    fun `an absent or empty blob is no sketch at all`() {
        val noBlob = SketchRows.toRow(pageId, ByteArray(0), "sk-1", now).copy(blob = null)
        assertNull(SketchRows.pngBytes(noBlob))
        assertNull(SketchRows.pngBytes(SketchRows.toRow(pageId, ByteArray(0), "sk-2", now)))
    }

    // ── fitsPage ─────────────────────────────────────────────────────────────

    @Test
    fun `fitsPage is exact in both directions`() {
        val png = TestPng.png(1404, 1685, totalBytes = 64)
        assertTrue(SketchRows.fitsPage(png, 1404, 1685))
        assertFalse(SketchRows.fitsPage(png, 1404, 1686))
        assertFalse(SketchRows.fitsPage(png, 1405, 1685))
        // Not "no larger than": a smaller image is some other page, not this one drawn small.
        assertFalse(SketchRows.fitsPage(TestPng.png(700, 800, 64), 1404, 1685))
        // And not the other way round either.
        assertFalse(SketchRows.fitsPage(TestPng.png(1860, 2480, 64), 1404, 1685))
    }

    @Test
    fun `a page with no size fails the guard — there is nothing to check against`() {
        val png = TestPng.png(0, 0, totalBytes = 64)   // dimensions a PNG cannot legally carry
        assertFalse(SketchRows.fitsPage(TestPng.png(1404, 1685, 64), 0, 1685))
        assertFalse(SketchRows.fitsPage(TestPng.png(1404, 1685, 64), 1404, 0))
        assertFalse(SketchRows.fitsPage(png, 0, 0))
    }

    @Test
    fun `bytes that are not a PNG never fit`() {
        assertFalse(SketchRows.fitsPage(TestPng.notAPng(), 1404, 1685))
        assertFalse(SketchRows.fitsPage(ByteArray(0), 1404, 1685))
        // Truncated inside the header: the parse must never read past what it was given.
        assertFalse(SketchRows.fitsPage(TestPng.png(1404, 1685).copyOf(20), 1404, 1685))
    }
}
