package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuideRows] — the border between a page's guides and the `.soil` (arc 51 / J2): the codecs, the
 * never-throw reads of a stored row, the header guard on a `VP8X` image, and the settings mapping
 * both ways.
 */
class GuideRowsTest {

    private val pageId = "page-1"
    private val now = 9_000L

    private fun row(type: String, text: String?, blob: ByteArray? = null) = SoilObjectEntity(
        id = "r", parentId = pageId, type = type, order = SoilSchema.SKETCH_ORDER,
        createdAt = 1L, updatedAt = 1L, text = text, blob = blob,
    )

    @Test
    fun `the row names are pinned`() {
        assertEquals("guide_grid", SoilSchema.TYPE_GUIDE_GRID)
        assertEquals("guide_image", SoilSchema.TYPE_GUIDE_IMAGE)
    }

    @Test
    fun `a grid round-trips through its row`() {
        val grid = GuideGrid(SketchContract.GRID_DOTS, 12, visible = false)
        val r = GuideRows.toGridRow(pageId, grid, "g-1", now)
        assertEquals("g-1", r.id)
        assertEquals(pageId, r.parentId)
        assertEquals(SoilSchema.TYPE_GUIDE_GRID, r.type)
        assertEquals(SoilSchema.SKETCH_ORDER, r.order)
        assertEquals(now, r.createdAt)
        assertNull(r.blob)
        assertEquals(grid, GuideRows.gridOf(r))
    }

    @Test
    fun `an image round-trips through its row, settings and pixels`() {
        val bytes = TestWebp.webpX(100, 200, totalBytes = 64)
        val image = GuideImage(opacity = 40, visible = false)
        val r = GuideRows.toImageRow(pageId, image, bytes, "i-1", now)
        assertEquals(SoilSchema.TYPE_GUIDE_IMAGE, r.type)
        assertEquals(SoilSchema.SKETCH_ORDER, r.order)
        assertEquals(image, GuideRows.imageOf(r))
        assertArrayEquals(bytes, GuideRows.imageBytes(r))
    }

    @Test
    fun `foreign rows read as nothing`() {
        val gridText = GuideRows.encodeGrid(GuideGrid(SketchContract.GRID_LINES, 8))
        val imageText = GuideRows.encodeImage(GuideImage(30))
        assertNull(GuideRows.gridOf(row(SoilSchema.TYPE_TEXT, gridText)))
        assertNull(GuideRows.gridOf(row(SoilSchema.TYPE_GUIDE_IMAGE, gridText)))
        assertNull(GuideRows.imageOf(row(SoilSchema.TYPE_GUIDE_GRID, imageText)))
        // A raster's blob is never a guide image, and a guide image's never a raster.
        val raster = row(SoilSchema.TYPE_SKETCH_GRAPHITE, null, TestWebp.webp(10, 10))
        assertNull(GuideRows.imageBytes(raster))
        assertNull(SketchRows.imageBytes(row(SoilSchema.TYPE_GUIDE_IMAGE, imageText, TestWebp.webpX(10, 10))))
    }

    @Test
    fun `unparsable or out-of-range text never throws, it reads as nothing`() {
        for (text in listOf(null, "", "not json", "{", "[]", "{\"kind\":\"x\"}")) {
            assertNull("grid <$text>", GuideRows.gridOf(row(SoilSchema.TYPE_GUIDE_GRID, text)))
            assertNull("image <$text>", GuideRows.imageOf(row(SoilSchema.TYPE_GUIDE_IMAGE, text)))
        }
        assertNull(GuideRows.decodeGrid("""{"kind":0,"count":8}"""))      // off is no row
        assertNull(GuideRows.decodeGrid("""{"kind":7,"count":8}"""))
        assertNull(GuideRows.decodeGrid("""{"kind":1,"count":0}"""))
        assertNull(GuideRows.decodeGrid("""{"kind":1,"count":256}"""))
        assertNull(GuideRows.decodeImage("""{"opacity":101}"""))
        assertNull(GuideRows.decodeImage("""{"opacity":-1}"""))
    }

    @Test
    fun `a stored row with a field this build does not know still reads`() {
        assertEquals(GuideGrid(1, 8, true), GuideRows.decodeGrid("""{"kind":1,"count":8,"tone":3}"""))
        assertEquals(GuideImage(20, true), GuideRows.decodeImage("""{"opacity":20,"zoom":2}"""))
    }

    @Test
    fun `an empty image blob is no image`() {
        assertNull(GuideRows.imageBytes(row(SoilSchema.TYPE_GUIDE_IMAGE, null, ByteArray(0))))
        assertNull(GuideRows.imageBytes(row(SoilSchema.TYPE_GUIDE_IMAGE, null, null)))
    }

    /** The guide image is a lossy WebP with alpha — `VP8X`-headed — and the rasters' guard reads
     *  that form already, so the same exact-size rule applies unchanged. */
    @Test
    fun `a VP8X page-sized header passes the guard`() {
        assertTrue(GuideRows.fitsPage(TestWebp.webpX(1404, 1872, totalBytes = 80), 1404, 1872))
    }

    @Test
    fun `a lossless page-sized header passes too`() {
        assertTrue(GuideRows.fitsPage(TestWebp.webp(1404, 1872), 1404, 1872))
    }

    @Test
    fun `a mis-sized, foreign or unsized image fails the guard`() {
        assertFalse(GuideRows.fitsPage(TestWebp.webpX(1404, 1871), 1404, 1872))
        assertFalse(GuideRows.fitsPage(TestWebp.webpX(1872, 1404), 1404, 1872))
        assertFalse(GuideRows.fitsPage(TestWebp.notAWebp(), 1404, 1872))
        assertFalse(GuideRows.fitsPage(TestWebp.legacyPng(1404, 1872, 64), 1404, 1872))
        assertFalse(GuideRows.fitsPage(TestWebp.webpX(1404, 1872), 0, 0))
    }

    // ── Settings ↔ rows ──────────────────────────────────────────────────────

    @Test
    fun `no rows is NONE`() {
        assertSame(SketchGuideSettings.NONE, GuideRows.toSettings(null, null))
    }

    @Test
    fun `settings carry each half from its own row`() {
        val both = GuideRows.toSettings(GuideGrid(SketchContract.GRID_LINES, 6, false), GuideImage(35, false))
        assertEquals(SketchGuideSettings(SketchContract.GRID_LINES, 6, false, 35, false), both)

        val gridOnly = GuideRows.toSettings(GuideGrid(SketchContract.GRID_DOTS, 10), null)
        assertEquals(SketchGuideSettings(SketchContract.GRID_DOTS, 10, true, 0, true), gridOnly)

        val imageOnly = GuideRows.toSettings(null, GuideImage(50))
        assertEquals(SketchGuideSettings(SketchContract.GRID_OFF, 0, true, 50, true), imageOnly)
    }

    @Test
    fun `settings split back into a grid and an image`() {
        val s = SketchGuideSettings(SketchContract.GRID_DOTS, 9, false, 60, true)
        assertEquals(GuideGrid(SketchContract.GRID_DOTS, 9, false), GuideRows.gridFrom(s))
        assertEquals(GuideImage(60, true), GuideRows.imageFrom(s))
        assertNull(GuideRows.gridFrom(SketchGuideSettings.NONE))
    }
}
