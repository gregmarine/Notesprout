package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SoilDao.liveContentIds] and [SoilDao.liveDescendantIds] — the two kind lists widened for arc 28
 * (H1): both now know text, shape and sticky rows, and a sticky's content strokes are reached only
 * by the deeper list, never the shallow one. Grown by arc 43 / K3 with the sketch row and the
 * third list it needed: [SoilDao.liveErasableIds], which is [SoilDao.liveDescendantIds] minus the
 * sketch (Erase page is ink only, decision 11), and [SoilDao.childrenOf], which must never see one
 * at all — and by arc 45 / G2, where the one sketch row became **two** (`sketch_graphite` and
 * `sketch_ink`) beside the **dead** arc-43 name `sketch`, which `childrenOf` still excludes and
 * nothing else carries. Runs against [FakeSoilDao], which mirrors the `@Query` strings by hand —
 * the point of the suite is to keep the fake and the SQL from drifting apart.
 */
class SoilDaoKindListsTest {

    private val pageId = "page"

    private suspend fun FakeSoilDao.put(
        id: String,
        parentId: String,
        type: String,
        deletedAt: Long? = null,
    ) = upsert(
        SoilObjectEntity(
            id = id, parentId = parentId, type = type, order = 0,
            createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt,
        )
    )

    // ── liveContentIds — the page's own loose content ───────────────────────

    @Test
    fun `liveContentIds includes the page's own text, shape and sticky rows`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("t1", pageId, SoilSchema.TYPE_TEXT)
        dao.put("sh1", pageId, SoilSchema.TYPE_SHAPE)
        dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
        assertEquals(setOf("t1", "sh1", "sn1"), dao.liveContentIds(pageId).toSet())
    }

    @Test
    fun `liveContentIds excludes links and documents`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("l1", pageId, SoilSchema.TYPE_LINK)
        dao.put("d1", pageId, SoilSchema.TYPE_DOCUMENT)
        assertEquals(emptySet<String>(), dao.liveContentIds(pageId).toSet())
    }

    @Test
    fun `liveContentIds excludes a sticky's children — they are not loose page content`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
            dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
            assertEquals(setOf("sn1"), dao.liveContentIds(pageId).toSet())
        }

    // ── liveDescendantIds — two levels deeper, plus stickies' great-grandchildren ──

    @Test
    fun `liveDescendantIds carries the page's loose kinds, including text, shape and sticky`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
            dao.put("h1", pageId, SoilSchema.TYPE_HEADING)
            dao.put("t1", pageId, SoilSchema.TYPE_TEXT)
            dao.put("sh1", pageId, SoilSchema.TYPE_SHAPE)
            dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
            dao.put("d1", pageId, SoilSchema.TYPE_DOCUMENT)
            assertEquals(
                setOf("s1", "h1", "t1", "sh1", "sn1", "d1"),
                dao.liveDescendantIds(pageId).toSet(),
            )
        }

    @Test
    fun `liveDescendantIds carries a link's own children`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("l1", pageId, SoilSchema.TYPE_LINK)
        dao.put("s1", "l1", SoilSchema.TYPE_STROKE)
        dao.put("t1", "l1", SoilSchema.TYPE_TEXT)
        assertEquals(setOf("l1", "s1", "t1"), dao.liveDescendantIds(pageId).toSet())
    }

    @Test
    fun `liveDescendantIds reaches a sticky's content whether the sticky sits on the page`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
            dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
            assertEquals(setOf("sn1", "c1"), dao.liveDescendantIds(pageId).toSet())
        }

    @Test
    fun `liveDescendantIds reaches a sticky's content when the sticky is wrapped in a link`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("l1", pageId, SoilSchema.TYPE_LINK)
            dao.put("sn1", "l1", SoilSchema.TYPE_STICKY)
            dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
            assertEquals(setOf("l1", "sn1", "c1"), dao.liveDescendantIds(pageId).toSet())
        }

    // ── The two sketch rasters (arc 43 / K3, split at arc 45 / G2) ───────────

    /** The names themselves, pinned here as well as in `FamilyConstantsTest`: these three strings
     *  are what every list below is about, and a typo in one of them is a row that quietly stops
     *  travelling. */
    @Test
    fun `the row names are the two live rasters and the dead arc-43 one`() {
        assertEquals("sketch_graphite", SoilSchema.TYPE_SKETCH_GRAPHITE)
        assertEquals("sketch_ink", SoilSchema.TYPE_SKETCH_INK)
        assertEquals("sketch", SoilSchema.TYPE_SKETCH_DEAD)
    }

    @Test
    fun `liveDescendantIds carries both rasters — a copy and a delete take the drawing`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
            dao.put("skg", pageId, SoilSchema.TYPE_SKETCH_GRAPHITE)
            dao.put("ski", pageId, SoilSchema.TYPE_SKETCH_INK)
            assertEquals(setOf("s1", "skg", "ski"), dao.liveDescendantIds(pageId).toSet())
        }

    /** A row nothing can read is a row nothing should copy (decision 4): the dead arc-43 name
     *  travels with nothing, so a page copy never carries a PNG no reader will open. */
    @Test
    fun `liveDescendantIds does not carry the dead arc-43 row`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("skOld", pageId, SoilSchema.TYPE_SKETCH_DEAD)
        assertEquals(setOf("s1"), dao.liveDescendantIds(pageId).toSet())
    }

    /** Decision 11: Erase page is ink only. The two lists differ in exactly the two raster rows,
     *  and in nothing else — a link's wrapped children and a note's content ride both. G2's ink
     *  raster is the *sketch's* ink, not the page's, and is excluded with the graphite. */
    @Test
    fun `liveErasableIds is liveDescendantIds minus both rasters, and nothing else`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("h1", pageId, SoilSchema.TYPE_HEADING)
        dao.put("d1", pageId, SoilSchema.TYPE_DOCUMENT)
        dao.put("l1", pageId, SoilSchema.TYPE_LINK)
        dao.put("ls1", "l1", SoilSchema.TYPE_STROKE)
        dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
        dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
        dao.put("skg", pageId, SoilSchema.TYPE_SKETCH_GRAPHITE)
        dao.put("ski", pageId, SoilSchema.TYPE_SKETCH_INK)

        val deep = dao.liveDescendantIds(pageId).toSet()
        val erasable = dao.liveErasableIds(pageId).toSet()
        assertEquals(setOf("skg", "ski"), deep - erasable)
        assertEquals(emptySet<String>(), erasable - deep)
        assertEquals(setOf("s1", "h1", "d1", "l1", "ls1", "sn1", "c1"), erasable)
    }

    /** [SoilDao.childrenOf] is untyped **and** blob-inclusive — it feeds every page read there is,
     *  and a page-sized image riding those would be megabytes per flip. All three names are
     *  excluded, the dead one included: a leftover arc-43 row must never surface as a child. */
    @Test
    fun `childrenOf never sees a sketch row of any of the three names`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("t1", pageId, SoilSchema.TYPE_TEXT)
        dao.put("skg", pageId, SoilSchema.TYPE_SKETCH_GRAPHITE)
        dao.put("ski", pageId, SoilSchema.TYPE_SKETCH_INK)
        dao.put("skOld", pageId, SoilSchema.TYPE_SKETCH_DEAD)
        assertEquals(setOf("s1", "t1"), dao.childrenOf(pageId).map { it.id }.toSet())
    }

    /** `hasLiveSketch` is "either raster, with pixels in it" — and never the dead name. */
    @Test
    fun `hasLiveSketch answers for either raster and ignores the dead one`() = runBlocking {
        val dao = FakeSoilDao()
        suspend fun raster(id: String, type: String, blob: ByteArray?) = dao.upsert(
            SoilObjectEntity(
                id = id, parentId = pageId, type = type, order = SoilSchema.SKETCH_ORDER,
                createdAt = 1L, updatedAt = 1L, blob = blob,
            )
        )
        assertEquals(false, dao.hasLiveSketch(pageId))
        raster("skOld", SoilSchema.TYPE_SKETCH_DEAD, ByteArray(9))
        assertEquals(false, dao.hasLiveSketch(pageId))
        raster("ski", SoilSchema.TYPE_SKETCH_INK, ByteArray(0))
        assertEquals("an empty blob is no raster", false, dao.hasLiveSketch(pageId))
        raster("skg", SoilSchema.TYPE_SKETCH_GRAPHITE, ByteArray(9))
        assertEquals(true, dao.hasLiveSketch(pageId))
    }

    // ── The two guide rows (arc 51 / J2) ────────────────────────────────────

    /** Guides are tools, never marks: no untyped page read may carry them. */
    @Test
    fun `childrenOf never sees a guide row`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("gg", pageId, SoilSchema.TYPE_GUIDE_GRID)
        dao.put("gi", pageId, SoilSchema.TYPE_GUIDE_IMAGE)
        assertEquals(setOf("s1"), dao.childrenOf(pageId).map { it.id }.toSet())
    }

    /** A page copied, deleted or undone keeps the guides set for it. */
    @Test
    fun `liveDescendantIds carries both guide rows`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("gg", pageId, SoilSchema.TYPE_GUIDE_GRID)
        dao.put("gi", pageId, SoilSchema.TYPE_GUIDE_IMAGE)
        dao.put("gOld", pageId, SoilSchema.TYPE_GUIDE_IMAGE, deletedAt = 9L)
        assertEquals(setOf("s1", "gg", "gi"), dao.liveDescendantIds(pageId).toSet())
    }

    /** Erase page never touches the guides, and a page with only guides has no ink to warn about. */
    @Test
    fun `liveErasableIds leaves both guide rows out`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("gg", pageId, SoilSchema.TYPE_GUIDE_GRID)
        dao.put("gi", pageId, SoilSchema.TYPE_GUIDE_IMAGE)
        assertEquals(emptyList<String>(), dao.liveErasableIds(pageId))
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        assertEquals(setOf("gg", "gi"), dao.liveDescendantIds(pageId).toSet() - dao.liveErasableIds(pageId).toSet())
    }

    /** The guides are not content: the loose-content list and the sketch question never see them. */
    @Test
    fun `liveContentIds and hasLiveSketch ignore the guides`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("gg", pageId, SoilSchema.TYPE_GUIDE_GRID)
        dao.upsert(
            SoilObjectEntity(
                id = "gi", parentId = pageId, type = SoilSchema.TYPE_GUIDE_IMAGE, order = SoilSchema.SKETCH_ORDER,
                createdAt = 1L, updatedAt = 1L, blob = ByteArray(9),
            )
        )
        assertEquals(emptyList<String>(), dao.liveContentIds(pageId))
        assertEquals(false, dao.hasLiveSketch(pageId))
    }

    @Test
    fun `liveDescendantIds excludes soft-deleted rows at every level`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE, deletedAt = 9L)
        dao.put("l1", pageId, SoilSchema.TYPE_LINK, deletedAt = 9L)
        dao.put("ls1", "l1", SoilSchema.TYPE_STROKE)
        dao.put("sn1", pageId, SoilSchema.TYPE_STICKY, deletedAt = 9L)
        dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
        dao.put("sn2", pageId, SoilSchema.TYPE_STICKY)
        dao.put("c2", "sn2", SoilSchema.TYPE_STROKE, deletedAt = 9L)
        assertEquals(setOf("sn2"), dao.liveDescendantIds(pageId).toSet())
    }
}
