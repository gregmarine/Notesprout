package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SoilDao.liveContentIds] and [SoilDao.liveDescendantIds] — the two kind lists widened for arc 28
 * (H1): both now know text, shape and sticky rows, and a sticky's content strokes are reached only
 * by the deeper list, never the shallow one. Grown by arc 43 / K3 with the `sketch` row and the
 * third list it needed: [SoilDao.liveErasableIds], which is [SoilDao.liveDescendantIds] minus the
 * sketch (Erase page is ink only, decision 11), and [SoilDao.childrenOf], which must never see one
 * at all. Runs against [FakeSoilDao], which mirrors the `@Query` strings by hand — the point of the
 * suite is to keep the fake and the SQL from drifting apart.
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

    // ── The sketch (arc 43 / K3) ────────────────────────────────────────────

    @Test
    fun `liveDescendantIds carries the page's sketch — a copy and a delete take the drawing`() =
        runBlocking {
            val dao = FakeSoilDao()
            dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
            dao.put("sk1", pageId, SoilSchema.TYPE_SKETCH)
            assertEquals(setOf("s1", "sk1"), dao.liveDescendantIds(pageId).toSet())
        }

    /** Decision 11: Erase page is ink only. The two lists differ in exactly one row, and in nothing
     *  else — a link's wrapped children and a note's content ride both. */
    @Test
    fun `liveErasableIds is liveDescendantIds minus the sketch, and nothing else`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("h1", pageId, SoilSchema.TYPE_HEADING)
        dao.put("d1", pageId, SoilSchema.TYPE_DOCUMENT)
        dao.put("l1", pageId, SoilSchema.TYPE_LINK)
        dao.put("ls1", "l1", SoilSchema.TYPE_STROKE)
        dao.put("sn1", pageId, SoilSchema.TYPE_STICKY)
        dao.put("c1", "sn1", SoilSchema.TYPE_STROKE)
        dao.put("sk1", pageId, SoilSchema.TYPE_SKETCH)

        val deep = dao.liveDescendantIds(pageId).toSet()
        val erasable = dao.liveErasableIds(pageId).toSet()
        assertEquals(setOf("sk1"), deep - erasable)
        assertEquals(emptySet<String>(), erasable - deep)
        assertEquals(setOf("s1", "h1", "d1", "l1", "ls1", "sn1", "c1"), erasable)
    }

    /** [SoilDao.childrenOf] is untyped **and** blob-inclusive — it feeds every page read there is,
     *  and a page-sized PNG riding those would be megabytes per flip. */
    @Test
    fun `childrenOf never sees a sketch`() = runBlocking {
        val dao = FakeSoilDao()
        dao.put("s1", pageId, SoilSchema.TYPE_STROKE)
        dao.put("t1", pageId, SoilSchema.TYPE_TEXT)
        dao.put("sk1", pageId, SoilSchema.TYPE_SKETCH)
        assertEquals(setOf("s1", "t1"), dao.childrenOf(pageId).map { it.id }.toSet())
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
