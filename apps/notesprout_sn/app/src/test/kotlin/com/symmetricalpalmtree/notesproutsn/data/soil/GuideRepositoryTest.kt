package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuideRepository] (arc 51 / J2) — the rasters' rules applied to the two guide rows: minted on
 * first use and never on open, rewritten in place with `createdAt` kept, `updatedAt` untouched by a
 * write that changes nothing, off / Remove as soft deletes, the header guard both ways, and the two
 * typed refusals writing nothing.
 */
class GuideRepositoryTest {

    private val pageId = "page-1"
    private val w = 120
    private val h = 200

    private class Fixture {
        val soil = FakeSoilDao()
        val dao = FakeGuideDao(soil)
        var minted = 0
        val repo = GuideRepository(dao, soil) { "gd-${minted++}" }
        fun live(type: String) = soil.rows.values.filter { it.type == type && it.deletedAt == null }
        fun all(type: String) = soil.rows.values.filter { it.type == type }
    }

    private fun image(totalBytes: Int = 200, filler: Byte = 0x22) =
        TestWebp.webpX(w, h, totalBytes = totalBytes, filler = filler)

    private fun settings(kind: Int = SketchContract.GRID_LINES, count: Int = 8, gridVisible: Boolean = true,
                         opacity: Int = 30, imageVisible: Boolean = true) =
        SketchGuideSettings(kind, count, gridVisible, opacity, imageVisible)

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    fun `a page with no guides reads NONE and creates nothing`() = runBlocking {
        val f = Fixture()
        val g = f.repo.get(pageId, w, h)
        assertEquals(SketchGuideSettings.NONE, g.settings)
        assertNull(g.imageBytes)
        assertTrue(f.soil.rows.isEmpty())
        assertEquals(0, f.minted)
    }

    // ── The grid ─────────────────────────────────────────────────────────────

    @Test
    fun `the first grid setting mints one row and reads back`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(kind = SketchContract.GRID_DOTS, count = 12), now = 1_000L)
        val row = f.live(SoilSchema.TYPE_GUIDE_GRID).single()
        assertEquals("gd-0", row.id)
        assertEquals(pageId, row.parentId)
        assertEquals(1_000L, row.createdAt)
        val g = f.repo.get(pageId, w, h)
        assertEquals(SketchContract.GRID_DOTS, g.settings.gridKind)
        assertEquals(12, g.settings.gridCount)
    }

    @Test
    fun `a changed grid is rewritten in place and keeps createdAt`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(count = 8), now = 1_000L)
        f.repo.putSettings(pageId, settings(count = 16, gridVisible = false), now = 2_000L)
        val row = f.all(SoilSchema.TYPE_GUIDE_GRID).single()
        assertEquals("gd-0", row.id)
        assertEquals(1_000L, row.createdAt)
        assertEquals(2_000L, row.updatedAt)
        assertEquals(GuideGrid(SketchContract.GRID_LINES, 16, false), GuideRows.gridOf(row))
    }

    @Test
    fun `the same grid again writes nothing`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(), now = 1_000L)
        f.repo.putSettings(pageId, settings(), now = 2_000L)
        assertEquals(1_000L, f.live(SoilSchema.TYPE_GUIDE_GRID).single().updatedAt)
    }

    @Test
    fun `GRID_OFF soft-deletes the grid row, and off again writes nothing`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(), now = 1_000L)
        f.repo.putSettings(pageId, SketchGuideSettings.NONE, now = 2_000L)
        val row = f.all(SoilSchema.TYPE_GUIDE_GRID).single()
        assertEquals(2_000L, row.deletedAt)
        val events = f.soil.events.size
        f.repo.putSettings(pageId, SketchGuideSettings.NONE, now = 3_000L)
        assertEquals(events, f.soil.events.size)
        assertEquals(SketchContract.GRID_OFF, f.repo.get(pageId, w, h).settings.gridKind)
    }

    /** A grid turned off and on again is a new row — the old one stays soft-deleted, never revived. */
    @Test
    fun `a grid turned back on mints a fresh row`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(), now = 1_000L)
        f.repo.putSettings(pageId, SketchGuideSettings.NONE, now = 2_000L)
        f.repo.putSettings(pageId, settings(), now = 3_000L)
        assertEquals("gd-1", f.live(SoilSchema.TYPE_GUIDE_GRID).single().id)
        assertEquals(2, f.all(SoilSchema.TYPE_GUIDE_GRID).size)
    }

    // ── The image ────────────────────────────────────────────────────────────

    @Test
    fun `putSettings on a page with no image row leaves the image fields for later`() = runBlocking {
        val f = Fixture()
        f.repo.putSettings(pageId, settings(kind = SketchContract.GRID_OFF, count = 0, opacity = 70))
        assertTrue(f.live(SoilSchema.TYPE_GUIDE_IMAGE).isEmpty())
        assertTrue(f.soil.rows.isEmpty())
    }

    @Test
    fun `the first image mints a row at opacity 0, and the settings push that follows fixes it`() = runBlocking {
        val f = Fixture()
        val bytes = image()
        f.repo.saveImage(pageId, bytes, w, h, now = 1_000L)
        val row = f.live(SoilSchema.TYPE_GUIDE_IMAGE).single()
        assertEquals(GuideImage(0, true), GuideRows.imageOf(row))
        assertEquals(1_000L, row.createdAt)

        f.repo.putSettings(pageId, settings(kind = SketchContract.GRID_OFF, count = 0, opacity = 45), now = 2_000L)
        val g = f.repo.get(pageId, w, h)
        assertEquals(45, g.settings.imageOpacity)
        assertArrayEquals(bytes, g.imageBytes)
        assertEquals(1_000L, f.live(SoilSchema.TYPE_GUIDE_IMAGE).single().createdAt)
    }

    @Test
    fun `the same image settings again write nothing`() = runBlocking {
        val f = Fixture()
        f.repo.saveImage(pageId, image(), w, h, now = 1_000L)
        f.repo.putSettings(pageId, settings(opacity = 45), now = 2_000L)
        f.repo.putSettings(pageId, settings(opacity = 45), now = 3_000L)
        assertEquals(2_000L, f.live(SoilSchema.TYPE_GUIDE_IMAGE).single().updatedAt)
    }

    @Test
    fun `a new image is rewritten in place, settings and createdAt kept`() = runBlocking {
        val f = Fixture()
        f.repo.saveImage(pageId, image(filler = 1), w, h, now = 1_000L)
        f.repo.putSettings(pageId, settings(opacity = 45), now = 2_000L)
        val second = image(filler = 2)
        f.repo.saveImage(pageId, second, w, h, now = 3_000L)
        val row = f.all(SoilSchema.TYPE_GUIDE_IMAGE).single()
        assertEquals("gd-0", row.id)
        assertEquals(1_000L, row.createdAt)
        assertEquals(3_000L, row.updatedAt)
        assertEquals(45, GuideRows.imageOf(row)!!.opacity)
        assertArrayEquals(second, row.blob)
    }

    @Test
    fun `identical image bytes write nothing`() = runBlocking {
        val f = Fixture()
        f.repo.saveImage(pageId, image(), w, h, now = 1_000L)
        val events = f.soil.events.size
        f.repo.saveImage(pageId, image(), w, h, now = 2_000L)
        assertEquals(1_000L, f.live(SoilSchema.TYPE_GUIDE_IMAGE).single().updatedAt)
        assertEquals(events, f.soil.events.size)
    }

    @Test
    fun `empty bytes remove the image, and removing nothing writes nothing`() = runBlocking {
        val f = Fixture()
        f.repo.saveImage(pageId, ByteArray(0), w, h, now = 500L)
        assertTrue(f.soil.rows.isEmpty())
        f.repo.saveImage(pageId, image(), w, h, now = 1_000L)
        f.repo.saveImage(pageId, ByteArray(0), w, h, now = 2_000L)
        assertEquals(2_000L, f.all(SoilSchema.TYPE_GUIDE_IMAGE).single().deletedAt)
        assertNull(f.repo.get(pageId, w, h).imageBytes)
    }

    @Test
    fun `an image over the cap is SKETCH_TOO_LARGE and writes nothing`() = runBlocking {
        val f = Fixture()
        val first = image()
        f.repo.saveImage(pageId, first, w, h, now = 1_000L)
        val thrown = runCatching {
            f.repo.saveImage(pageId, image(totalBytes = SketchContract.MAX_BYTES + 1), w, h, now = 2_000L)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(SketchContract.SKETCH_TOO_LARGE, thrown!!.message)
        val row = f.live(SoilSchema.TYPE_GUIDE_IMAGE).single()
        assertEquals(1_000L, row.updatedAt)
        assertArrayEquals(first, row.blob)
    }

    @Test
    fun `a mis-sized or foreign image is SKETCH_BAD_IMAGE and writes nothing`() = runBlocking {
        val f = Fixture()
        for (bad in listOf(TestWebp.webpX(w, h + 1), TestWebp.notAWebp(), TestWebp.legacyPng(w, h, 64))) {
            val thrown = runCatching { f.repo.saveImage(pageId, bad, w, h) }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
            assertEquals(SketchContract.SKETCH_BAD_IMAGE, thrown!!.message)
        }
        assertTrue(f.soil.rows.isEmpty())
    }

    /** A stored image that fails the guard is soft-deleted on the way past, never overwritten. */
    @Test
    fun `a stored bad image is soft-deleted on read, its bytes kept`() = runBlocking {
        val f = Fixture()
        val stranger = TestWebp.webpX(w * 2, h)
        f.soil.upsert(GuideRows.toImageRow(pageId, GuideImage(30), stranger, "bad", 1L))
        val g = f.repo.get(pageId, w, h)
        assertNull(g.imageBytes)
        assertEquals(0, g.settings.imageOpacity)
        val row = f.soil.rows.getValue("bad")
        assertNotNull(row.deletedAt)
        assertArrayEquals(stranger, row.blob)
    }

    /** Damaged settings text keeps the pixels: the picture is good, the next push rewrites it. */
    @Test
    fun `an image row with damaged settings keeps its pixels`() = runBlocking {
        val f = Fixture()
        val bytes = image()
        f.soil.upsert(GuideRows.toImageRow(pageId, GuideImage(30), bytes, "img", 1L).copy(text = "garbage"))
        val g = f.repo.get(pageId, w, h)
        assertArrayEquals(bytes, g.imageBytes)
        assertEquals(0, g.settings.imageOpacity)
        f.repo.putSettings(pageId, settings(opacity = 55), now = 2_000L)
        assertEquals(55, f.repo.get(pageId, w, h).settings.imageOpacity)
    }

    /** The grid and the image are independent rows: a grid change never dates the image. */
    @Test
    fun `a grid change never touches the image row`() = runBlocking {
        val f = Fixture()
        f.repo.saveImage(pageId, image(), w, h, now = 1_000L)
        f.repo.putSettings(pageId, settings(count = 8, opacity = 0), now = 2_000L)
        f.repo.putSettings(pageId, settings(count = 20, opacity = 0), now = 3_000L)
        assertEquals(1_000L, f.live(SoilSchema.TYPE_GUIDE_IMAGE).single().updatedAt)
    }
}
