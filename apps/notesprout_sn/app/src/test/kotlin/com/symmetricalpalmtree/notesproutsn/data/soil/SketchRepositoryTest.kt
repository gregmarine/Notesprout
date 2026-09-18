package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SketchRepository] — the four rules the sketch feature rests on (arc 43 / K3), every one of them
 * provable with no tablet in the room: blank means absent, minted on the first save, the header
 * guard on the way in **and** on the way out, and `updatedAt` is sacred. Plus the one deliberate
 * refusal in the app ([SketchContract.MAX_BYTES]), which must write nothing at all.
 *
 * Since arc 45 / G2 every one of those is **per raster**, and the suite says so twice over: the
 * rules are run against each layer in turn, and a section of its own pins what the split itself
 * has to guarantee — that saving one layer never touches the other's row, that clearing one leaves
 * the other, that [SketchRepository.has] is "either", and that the dead arc-43 row is neither read
 * nor repaired.
 */
class SketchRepositoryTest {

    private val pageId = "page-1"
    private val w = 120
    private val h = 200
    private val graphite = SketchContract.LAYER_GRAPHITE
    private val ink = SketchContract.LAYER_INK

    private class Fixture {
        val soil = FakeSoilDao()
        val dao = FakeSketchDao(soil)
        var minted = 0
        val repo = SketchRepository(dao, soil) { "sk-${minted++}" }
        fun rowFor(pageId: String, layer: Int) = soil.rows.values.firstOrNull {
            it.type == SketchRows.typeFor(layer) && it.parentId == pageId
        }
    }

    private fun webp(totalBytes: Int = 200, filler: Byte = 0x11) =
        TestWebp.webp(w, h, totalBytes = totalBytes, filler = filler)

    /** Every rule below holds for both rasters; running each case twice is cheaper than trusting
     *  that a layer parameter is threaded through everywhere it should be. */
    private fun bothLayers(body: (Int) -> Unit) = SketchContract.LAYERS.forEach(body)

    // ── The first save ───────────────────────────────────────────────────────

    @Test
    fun `the first save mints one row and the read gives the bytes back`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            assertFalse(f.repo.has(pageId))
            assertNull(f.repo.get(pageId, layer, w, h))

            val bytes = webp()
            f.repo.save(pageId, layer, bytes, w, h, now = 1_000L)

            val row = f.rowFor(pageId, layer)!!
            assertEquals("sk-0", row.id)
            assertEquals(SoilSchema.SKETCH_ORDER, row.order)
            assertEquals(1_000L, row.createdAt)
            assertTrue(f.repo.has(pageId))
            assertArrayEquals(bytes, f.repo.get(pageId, layer, w, h))
        }
    }

    /** Minted on the first save, **never on open**: a page nobody has drawn on has no row. */
    @Test
    fun `a read never creates a row`() = runBlocking {
        val f = Fixture()
        for (layer in SketchContract.LAYERS) f.repo.get(pageId, layer, w, h)
        f.repo.has(pageId)
        for (layer in SketchContract.LAYERS) assertNull(f.rowFor(pageId, layer))
        assertEquals(0, f.minted)
    }

    @Test
    fun `an unknown layer is refused on every door`() = runBlocking {
        val f = Fixture()
        for (call in listOf<suspend () -> Unit>(
            { f.repo.get(pageId, 9, w, h) },
            { f.repo.save(pageId, 9, webp(), w, h, now = 1L) },
            { f.repo.clear(pageId, 9, now = 1L) },
        )) {
            val thrown = runCatching { call() }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException)
        }
        assertEquals(emptyList<String>(), f.soil.events)
    }

    // ── Replaced in place ────────────────────────────────────────────────────

    @Test
    fun `a second save replaces the pixels in place and keeps createdAt`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.save(pageId, layer, webp(200, 0x11), w, h, now = 1_000L)
            val first = f.rowFor(pageId, layer)!!.id

            val second = webp(300, 0x22)
            f.repo.save(pageId, layer, second, w, h, now = 2_000L)

            val row = f.rowFor(pageId, layer)!!
            assertEquals(
                "one row per page per layer",
                1,
                f.soil.rows.values.count { it.type == SketchRows.typeFor(layer) },
            )
            assertEquals(first, row.id)
            assertEquals(1_000L, row.createdAt)
            assertEquals(2_000L, row.updatedAt)
            assertArrayEquals(second, row.blob)
            assertEquals("only one id was ever minted", 1, f.minted)
        }
    }

    /** `updatedAt` is sacred: a save of the bytes already stored writes nothing, so a sketch opened
     *  and closed untouched cannot re-flag the notebook for backup. */
    @Test
    fun `identical bytes write nothing at all`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            val bytes = webp(300)
            f.repo.save(pageId, layer, bytes, w, h, now = 1_000L)
            f.soil.events.clear()

            f.repo.save(pageId, layer, bytes.copyOf(), w, h, now = 9_999L)

            assertEquals(emptyList<String>(), f.soil.events)
            assertEquals(1_000L, f.rowFor(pageId, layer)!!.updatedAt)
        }
    }

    /** Same length, different pixels — the digest cannot settle it, so the blob compare must. */
    @Test
    fun `bytes of the same length but different content are written`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.save(pageId, layer, webp(300, 0x11), w, h, now = 1_000L)
            val other = webp(300, 0x22)
            f.repo.save(pageId, layer, other, w, h, now = 2_000L)
            assertArrayEquals(other, f.rowFor(pageId, layer)!!.blob)
            assertEquals(2_000L, f.rowFor(pageId, layer)!!.updatedAt)
        }
    }

    // ── Blank means absent ───────────────────────────────────────────────────

    @Test
    fun `an empty save clears the live row`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.save(pageId, layer, webp(), w, h, now = 1_000L)
            f.repo.save(pageId, layer, ByteArray(0), w, h, now = 2_000L)

            assertEquals(2_000L, f.rowFor(pageId, layer)!!.deletedAt)
            assertFalse(f.repo.has(pageId))
            assertNull(f.repo.get(pageId, layer, w, h))
        }
    }

    @Test
    fun `an empty save on a page with no raster writes nothing`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.save(pageId, layer, ByteArray(0), w, h, now = 2_000L)
            assertEquals(emptyList<String>(), f.soil.events)
            assertNull(f.rowFor(pageId, layer))
        }
    }

    @Test
    fun `clear is the same act, and is a no-op on a page with no raster`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.clear(pageId, layer, now = 5L)
            assertEquals(emptyList<String>(), f.soil.events)

            f.repo.save(pageId, layer, webp(), w, h, now = 1_000L)
            f.repo.clear(pageId, layer, now = 3_000L)
            assertEquals(3_000L, f.rowFor(pageId, layer)!!.deletedAt)
        }
    }

    /** A stored row whose blob is empty reads as no raster — blank means absent on the way out too. */
    @Test
    fun `a stored empty blob reads as absent`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.soil.upsert(SketchRows.toRow(pageId, layer, ByteArray(0), "sk-x", 1L))
            assertFalse(f.repo.has(pageId))
            assertNull(f.repo.get(pageId, layer, w, h))
            // Nothing was refused, so nothing was soft-deleted either — an empty row is not a bad one.
            assertNull(f.rowFor(pageId, layer)!!.deletedAt)
        }
    }

    // ── The two refusals ─────────────────────────────────────────────────────

    @Test
    fun `an image over the cap is refused with the contract's exact string and nothing is written`() =
        bothLayers { layer ->
            runBlocking {
                val f = Fixture()
                val kept = webp(300)
                f.repo.save(pageId, layer, kept, w, h, now = 1_000L)
                f.soil.events.clear()

                val huge = TestWebp.webp(w, h, totalBytes = SketchContract.MAX_BYTES + 1)
                val thrown =
                    runCatching { f.repo.save(pageId, layer, huge, w, h, now = 2_000L) }.exceptionOrNull()

                assertTrue(thrown is IllegalStateException)
                assertEquals(SketchContract.SKETCH_TOO_LARGE, thrown!!.message)
                assertEquals(emptyList<String>(), f.soil.events)
                assertArrayEquals("the stored row is what it was", kept, f.rowFor(pageId, layer)!!.blob)
                assertEquals(1_000L, f.rowFor(pageId, layer)!!.updatedAt)
            }
        }

    /** Exactly the cap is accepted: the refusal is *over* the window, not at it — and the cap is
     *  per row, so both rasters may sit at it on the same page. */
    @Test
    fun `an image of exactly the cap is accepted, on each row`() = runBlocking {
        val f = Fixture()
        val atCap = TestWebp.webp(w, h, totalBytes = SketchContract.MAX_BYTES)
        f.repo.save(pageId, graphite, atCap, w, h, now = 1_000L)
        f.repo.save(pageId, ink, atCap, w, h, now = 1_000L)
        assertEquals(SketchContract.MAX_BYTES, f.rowFor(pageId, graphite)!!.blob!!.size)
        assertEquals(SketchContract.MAX_BYTES, f.rowFor(pageId, ink)!!.blob!!.size)
    }

    @Test
    fun `a mis-sized image is refused with the contract's exact string and nothing is written`() =
        bothLayers { layer ->
            runBlocking {
                val f = Fixture()
                val thrown = runCatching {
                    f.repo.save(pageId, layer, TestWebp.webp(w + 1, h, totalBytes = 200), w, h, now = 1_000L)
                }.exceptionOrNull()

                assertTrue(thrown is IllegalStateException)
                assertEquals(SketchContract.SKETCH_BAD_IMAGE, thrown!!.message)
                assertNull(f.rowFor(pageId, layer))
            }
        }

    @Test
    fun `bytes that are not a WebP are refused the same way`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            val thrown = runCatching {
                f.repo.save(pageId, layer, TestWebp.notAWebp(), w, h, now = 1_000L)
            }.exceptionOrNull()
            assertEquals(SketchContract.SKETCH_BAD_IMAGE, (thrown as IllegalStateException).message)
            assertNull(f.rowFor(pageId, layer))
        }
    }

    /** A PNG of exactly the page's size is refused like anything else that is not a WebP — the
     *  whole of "no legacy" on the way in (decision 4). */
    @Test
    fun `an arc-43 PNG is refused on save`() = runBlocking {
        val f = Fixture()
        val thrown = runCatching {
            f.repo.save(pageId, graphite, TestWebp.legacyPng(w, h, 200), w, h, now = 1_000L)
        }.exceptionOrNull()
        assertEquals(SketchContract.SKETCH_BAD_IMAGE, (thrown as IllegalStateException).message)
        assertNull(f.rowFor(pageId, graphite))
    }

    /** The watch line is a log, not a refusal: over it, the save lands exactly as any other does. */
    @Test
    fun `an image over the watch line is accepted`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            val watched = TestWebp.webp(w, h, totalBytes = SketchContract.WATCH_BYTES + 1)
            f.repo.save(pageId, layer, watched, w, h, now = 1_000L)
            assertEquals(watched.size, f.rowFor(pageId, layer)!!.blob!!.size)
        }
    }

    // ── The guard on the way out ─────────────────────────────────────────────

    /** A refused row is **soft-deleted, never overwritten**: the pixels are unreadable for this
     *  page either way, and leaving them would hand them to the next reader as well. */
    @Test
    fun `a stored mis-sized row is soft-deleted on read and answers null`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            val foreign = TestWebp.webp(999, 999, totalBytes = 200)
            f.soil.upsert(SketchRows.toRow(pageId, layer, foreign, "sk-foreign", 1L))

            assertNull(f.repo.get(pageId, layer, w, h))

            val row = f.soil.rows.getValue("sk-foreign")
            assertNotNull("the row is dated out", row.deletedAt)
            assertArrayEquals("and never overwritten", foreign, row.blob)
            assertFalse(f.repo.has(pageId))
        }
    }

    @Test
    fun `a stored row that is not a WebP is refused the same way`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.soil.upsert(SketchRows.toRow(pageId, layer, TestWebp.notAWebp(), "sk-junk", 1L))
            assertNull(f.repo.get(pageId, layer, w, h))
            assertNotNull(f.soil.rows.getValue("sk-junk").deletedAt)
        }
    }

    /** A page resized under a stored raster is the same refusal — and the reason the page size
     *  always comes from this notebook's own row, never from whatever produced the bytes. */
    @Test
    fun `the guard is asked with the page's size, not the image's`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            f.repo.save(pageId, layer, webp(200), w, h, now = 1_000L)
            assertNotNull(f.repo.get(pageId, layer, w, h))
            assertNull(f.repo.get(pageId, layer, w, h + 1))
        }
    }

    // ── The two rasters are independent (arc 45 / G2) ─────────────────────────

    @Test
    fun `saving one raster never touches the other's row`() = runBlocking {
        val f = Fixture()
        val g = webp(200, 0x11)
        f.repo.save(pageId, graphite, g, w, h, now = 1_000L)
        val graphiteRow = f.rowFor(pageId, graphite)!!

        f.repo.save(pageId, ink, webp(300, 0x22), w, h, now = 2_000L)

        assertEquals("a second row, not a second write", 2, f.minted)
        val after = f.rowFor(pageId, graphite)!!
        assertEquals(graphiteRow.id, after.id)
        assertEquals(1_000L, after.updatedAt)
        assertArrayEquals(g, after.blob)
        // …and the same the other way round.
        f.repo.save(pageId, graphite, webp(400, 0x33), w, h, now = 3_000L)
        assertEquals(2_000L, f.rowFor(pageId, ink)!!.updatedAt)
    }

    @Test
    fun `clearing one raster leaves the other alone`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, graphite, webp(200), w, h, now = 1_000L)
        val kept = webp(300, 0x22)
        f.repo.save(pageId, ink, kept, w, h, now = 1_000L)

        f.repo.clear(pageId, graphite, now = 2_000L)

        assertEquals(2_000L, f.rowFor(pageId, graphite)!!.deletedAt)
        assertNull(f.rowFor(pageId, ink)!!.deletedAt)
        assertArrayEquals(kept, f.repo.get(pageId, ink, w, h))
        // Still a drawn page: `has` is "either".
        assertTrue(f.repo.has(pageId))
    }

    @Test
    fun `has is either raster, and false only when neither is live`() = runBlocking {
        val f = Fixture()
        assertFalse(f.repo.has(pageId))
        f.repo.save(pageId, ink, webp(), w, h, now = 1_000L)
        assertTrue("ink alone is a drawn page", f.repo.has(pageId))
        f.repo.clear(pageId, ink, now = 2_000L)
        assertFalse(f.repo.has(pageId))
        f.repo.save(pageId, graphite, webp(), w, h, now = 3_000L)
        assertTrue(f.repo.has(pageId))
    }

    @Test
    fun `has is blob-free and says no for an absent, empty or deleted row`() = bothLayers { layer ->
        runBlocking {
            val f = Fixture()
            assertFalse(f.repo.has(pageId))
            f.soil.upsert(SketchRows.toRow(pageId, layer, ByteArray(0), "sk-empty", 1L))
            assertFalse(f.repo.has(pageId))
            f.soil.softDelete(listOf("sk-empty"), 2L)
            f.repo.save(pageId, layer, webp(), w, h, now = 3_000L)
            assertTrue(f.repo.has(pageId))
            f.repo.clear(pageId, layer, now = 4_000L)
            assertFalse(f.repo.has(pageId))
        }
    }

    /** No legacy, no opinion: a stored arc-43 row is not reached by a read at all, so it is neither
     *  returned nor soft-deleted — it simply is not this repository's business (decision 4). */
    @Test
    fun `a stored row of the dead type is never read and never dated out`() = runBlocking {
        val f = Fixture()
        f.soil.upsert(
            SoilObjectEntity(
                id = "sk-old", parentId = pageId, type = SoilSchema.TYPE_SKETCH_DEAD,
                order = SoilSchema.SKETCH_ORDER, createdAt = 1L, updatedAt = 1L,
                blob = TestWebp.legacyPng(w, h, 200),
            )
        )
        f.soil.events.clear()

        assertFalse(f.repo.has(pageId))
        assertNull(f.repo.get(pageId, graphite, w, h))
        assertNull(f.repo.get(pageId, ink, w, h))

        assertEquals(emptyList<String>(), f.soil.events)
        assertNull(f.soil.rows.getValue("sk-old").deletedAt)
    }

    // ── pagesWithSketch ──────────────────────────────────────────────────────

    @Test
    fun `pagesWithSketch names the notebook's drawn pages, by either raster`() = runBlocking {
        val f = Fixture()
        val nb = "nb"
        suspend fun page(id: String) = f.soil.upsert(
            SoilObjectEntity(
                id = id, parentId = nb, type = SoilSchema.TYPE_PAGE, order = 0,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        page("p1"); page("p2"); page("p3"); page("p4")
        f.repo.save("p1", graphite, webp(), w, h, now = 1L)
        f.repo.save("p2", ink, webp(), w, h, now = 1L)          // ink alone still counts
        f.repo.save("p3", graphite, webp(), w, h, now = 1L)
        f.repo.clear("p3", graphite, now = 2L)
        assertEquals(listOf("p1", "p2"), f.dao.pagesWithSketch(nb).sorted())
    }
}
