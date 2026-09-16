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
 */
class SketchRepositoryTest {

    private val pageId = "page-1"
    private val w = 120
    private val h = 200

    private class Fixture {
        val soil = FakeSoilDao()
        val dao = FakeSketchDao(soil)
        var minted = 0
        val repo = SketchRepository(dao, soil) { "sk-${minted++}" }
        fun rowFor(pageId: String) = soil.rows.values.firstOrNull {
            it.type == SoilSchema.TYPE_SKETCH && it.parentId == pageId
        }
    }

    private fun png(totalBytes: Int = 200, filler: Byte = 0x11) =
        TestPng.png(w, h, totalBytes = totalBytes, filler = filler)

    // ── The first save ───────────────────────────────────────────────────────

    @Test
    fun `the first save mints one row and the read gives the bytes back`() = runBlocking {
        val f = Fixture()
        assertFalse(f.repo.has(pageId))
        assertNull(f.repo.get(pageId, w, h))

        val bytes = png()
        f.repo.save(pageId, bytes, w, h, now = 1_000L)

        val row = f.rowFor(pageId)!!
        assertEquals("sk-0", row.id)
        assertEquals(SoilSchema.SKETCH_ORDER, row.order)
        assertEquals(1_000L, row.createdAt)
        assertTrue(f.repo.has(pageId))
        assertArrayEquals(bytes, f.repo.get(pageId, w, h))
    }

    /** Minted on the first save, **never on open**: a page nobody has drawn on has no row. */
    @Test
    fun `a read never creates a row`() = runBlocking {
        val f = Fixture()
        f.repo.get(pageId, w, h)
        f.repo.has(pageId)
        assertNull(f.rowFor(pageId))
        assertEquals(0, f.minted)
    }

    // ── Replaced in place ────────────────────────────────────────────────────

    @Test
    fun `a second save replaces the pixels in place and keeps createdAt`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, png(200, 0x11), w, h, now = 1_000L)
        val first = f.rowFor(pageId)!!.id

        val second = png(300, 0x22)
        f.repo.save(pageId, second, w, h, now = 2_000L)

        val row = f.rowFor(pageId)!!
        assertEquals("one row per page", 1, f.soil.rows.values.count { it.type == SoilSchema.TYPE_SKETCH })
        assertEquals(first, row.id)
        assertEquals(1_000L, row.createdAt)
        assertEquals(2_000L, row.updatedAt)
        assertArrayEquals(second, row.blob)
        assertEquals("only one id was ever minted", 1, f.minted)
    }

    /** `updatedAt` is sacred: a save of the bytes already stored writes nothing, so a sketch opened
     *  and closed untouched cannot re-flag the notebook for backup. */
    @Test
    fun `identical bytes write nothing at all`() = runBlocking {
        val f = Fixture()
        val bytes = png(300)
        f.repo.save(pageId, bytes, w, h, now = 1_000L)
        f.soil.events.clear()

        f.repo.save(pageId, bytes.copyOf(), w, h, now = 9_999L)

        assertEquals(emptyList<String>(), f.soil.events)
        assertEquals(1_000L, f.rowFor(pageId)!!.updatedAt)
    }

    /** Same length, different pixels — the digest cannot settle it, so the blob compare must. */
    @Test
    fun `bytes of the same length but different content are written`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, png(300, 0x11), w, h, now = 1_000L)
        val other = png(300, 0x22)
        f.repo.save(pageId, other, w, h, now = 2_000L)
        assertArrayEquals(other, f.rowFor(pageId)!!.blob)
        assertEquals(2_000L, f.rowFor(pageId)!!.updatedAt)
    }

    // ── Blank means absent ───────────────────────────────────────────────────

    @Test
    fun `an empty save clears the live row`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, png(), w, h, now = 1_000L)
        f.repo.save(pageId, ByteArray(0), w, h, now = 2_000L)

        assertEquals(2_000L, f.rowFor(pageId)!!.deletedAt)
        assertFalse(f.repo.has(pageId))
        assertNull(f.repo.get(pageId, w, h))
    }

    @Test
    fun `an empty save on a page with no sketch writes nothing`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, ByteArray(0), w, h, now = 2_000L)
        assertEquals(emptyList<String>(), f.soil.events)
        assertNull(f.rowFor(pageId))
    }

    @Test
    fun `clear is the same act, and is a no-op on a page with no sketch`() = runBlocking {
        val f = Fixture()
        f.repo.clear(pageId, now = 5L)
        assertEquals(emptyList<String>(), f.soil.events)

        f.repo.save(pageId, png(), w, h, now = 1_000L)
        f.repo.clear(pageId, now = 3_000L)
        assertEquals(3_000L, f.rowFor(pageId)!!.deletedAt)
    }

    /** A stored row whose blob is empty reads as no sketch — blank means absent on the way out too. */
    @Test
    fun `a stored empty blob reads as absent`() = runBlocking {
        val f = Fixture()
        f.soil.upsert(SketchRows.toRow(pageId, ByteArray(0), "sk-x", 1L))
        assertFalse(f.repo.has(pageId))
        assertNull(f.repo.get(pageId, w, h))
        // Nothing was refused, so nothing was soft-deleted either — an empty row is not a bad one.
        assertNull(f.rowFor(pageId)!!.deletedAt)
    }

    // ── The two refusals ─────────────────────────────────────────────────────

    @Test
    fun `a PNG over the cap is refused with the contract's exact string and nothing is written`() =
        runBlocking {
            val f = Fixture()
            val kept = png(300)
            f.repo.save(pageId, kept, w, h, now = 1_000L)
            f.soil.events.clear()

            val huge = TestPng.png(w, h, totalBytes = SketchContract.MAX_BYTES + 1)
            val thrown = runCatching { f.repo.save(pageId, huge, w, h, now = 2_000L) }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
            assertEquals(SketchContract.SKETCH_TOO_LARGE, thrown!!.message)
            assertEquals(emptyList<String>(), f.soil.events)
            assertArrayEquals("the stored row is what it was", kept, f.rowFor(pageId)!!.blob)
            assertEquals(1_000L, f.rowFor(pageId)!!.updatedAt)
        }

    /** Exactly the cap is accepted: the refusal is *over* the window, not at it. */
    @Test
    fun `a PNG of exactly the cap is accepted`() = runBlocking {
        val f = Fixture()
        val atCap = TestPng.png(w, h, totalBytes = SketchContract.MAX_BYTES)
        f.repo.save(pageId, atCap, w, h, now = 1_000L)
        assertEquals(SketchContract.MAX_BYTES, f.rowFor(pageId)!!.blob!!.size)
    }

    @Test
    fun `a mis-sized PNG is refused with the contract's exact string and nothing is written`() =
        runBlocking {
            val f = Fixture()
            val thrown = runCatching {
                f.repo.save(pageId, TestPng.png(w + 1, h, totalBytes = 200), w, h, now = 1_000L)
            }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
            assertEquals(SketchContract.SKETCH_BAD_PNG, thrown!!.message)
            assertNull(f.rowFor(pageId))
        }

    @Test
    fun `bytes that are not a PNG are refused the same way`() = runBlocking {
        val f = Fixture()
        val thrown = runCatching {
            f.repo.save(pageId, TestPng.notAPng(), w, h, now = 1_000L)
        }.exceptionOrNull()
        assertEquals(SketchContract.SKETCH_BAD_PNG, (thrown as IllegalStateException).message)
        assertNull(f.rowFor(pageId))
    }

    /** The watch line is a log, not a refusal: over it, the save lands exactly as any other does. */
    @Test
    fun `a PNG over the watch line is accepted`() = runBlocking {
        val f = Fixture()
        val watched = TestPng.png(w, h, totalBytes = SketchContract.WATCH_BYTES + 1)
        f.repo.save(pageId, watched, w, h, now = 1_000L)
        assertEquals(watched.size, f.rowFor(pageId)!!.blob!!.size)
    }

    // ── The guard on the way out ─────────────────────────────────────────────

    /** A refused row is **soft-deleted, never overwritten**: the pixels are unreadable for this
     *  page either way, and leaving them would hand them to the next reader as well. */
    @Test
    fun `a stored mis-sized row is soft-deleted on read and answers null`() = runBlocking {
        val f = Fixture()
        val foreign = TestPng.png(999, 999, totalBytes = 200)
        f.soil.upsert(SketchRows.toRow(pageId, foreign, "sk-foreign", 1L))

        assertNull(f.repo.get(pageId, w, h))

        val row = f.soil.rows.getValue("sk-foreign")
        assertNotNull("the row is dated out", row.deletedAt)
        assertArrayEquals("and never overwritten", foreign, row.blob)
        assertFalse(f.repo.has(pageId))
    }

    @Test
    fun `a stored row that is not a PNG is refused the same way`() = runBlocking {
        val f = Fixture()
        f.soil.upsert(SketchRows.toRow(pageId, TestPng.notAPng(), "sk-junk", 1L))
        assertNull(f.repo.get(pageId, w, h))
        assertNotNull(f.soil.rows.getValue("sk-junk").deletedAt)
    }

    /** A page resized under a stored sketch is the same refusal — and the reason the page size
     *  always comes from this notebook's own row, never from whatever produced the bytes. */
    @Test
    fun `the guard is asked with the page's size, not the image's`() = runBlocking {
        val f = Fixture()
        f.repo.save(pageId, png(200), w, h, now = 1_000L)
        assertNotNull(f.repo.get(pageId, w, h))
        assertNull(f.repo.get(pageId, w, h + 1))
    }

    // ── has / pagesWithSketch ────────────────────────────────────────────────

    @Test
    fun `has is blob-free and says no for an absent, empty or deleted row`() = runBlocking {
        val f = Fixture()
        assertFalse(f.repo.has(pageId))
        f.soil.upsert(SketchRows.toRow(pageId, ByteArray(0), "sk-empty", 1L))
        assertFalse(f.repo.has(pageId))
        f.soil.softDelete(listOf("sk-empty"), 2L)
        f.repo.save(pageId, png(), w, h, now = 3_000L)
        assertTrue(f.repo.has(pageId))
        f.repo.clear(pageId, now = 4_000L)
        assertFalse(f.repo.has(pageId))
    }

    @Test
    fun `pagesWithSketch names the notebook's drawn pages and nothing else`() = runBlocking {
        val f = Fixture()
        val nb = "nb"
        suspend fun page(id: String) = f.soil.upsert(
            SoilObjectEntity(
                id = id, parentId = nb, type = SoilSchema.TYPE_PAGE, order = 0,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        page("p1"); page("p2"); page("p3")
        f.repo.save("p1", png(), w, h, now = 1L)
        f.repo.save("p3", png(), w, h, now = 1L)
        f.repo.clear("p3", now = 2L)
        assertEquals(listOf("p1"), f.dao.pagesWithSketch(nb))
    }
}
