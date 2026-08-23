package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link store's re-parent model on the shared serial [SoilWriter]: a wrap flips children's
 * `parentId` page → link (never copies), unlink flips back, remove/restore carry the wrapped
 * children, move shifts row + children together, and [LinkStore.deepChildIds] sees grandchildren.
 * Runs against [FakeSoilDao] with a pass-through transaction — the ordering and re-parent logic
 * are what is under test, not Room.
 */
class LinkStoreTest {

    private val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "target")

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))

    private fun heading(id: String) = Heading(
        id = id, text = "## T-$id", level = 2, x = 1f, y = 2f, width = 100f, height = 40f, order = 0,
    )

    private fun link(id: String, strokes: List<Stroke>, headings: List<Heading>) = PageLink(
        id = id, payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
        x = 0f, y = 0f, width = 120f, height = 60f, order = 0,
        strokes = strokes, headings = headings,
    )

    /** Store + writer + the sibling stores that seed the fake table the way the screen does. */
    private fun make(dao: FakeSoilDao): Triple<LinkStore, SoilWriter, Pair<StrokeStore, HeadingStore>> {
        val writer = SoilWriter {}
        val links = LinkStore(dao, writer) { block -> block() }
        return Triple(links, writer, StrokeStore(dao, writer) to HeadingStore(dao, writer))
    }

    private suspend fun seed(dao: FakeSoilDao, writer: SoilWriter, stores: Pair<StrokeStore, HeadingStore>) {
        val (strokes, headings) = stores
        strokes.commit("page", stroke("s1"))
        strokes.commit("page", stroke("s2"))
        headings.create("page", heading("h1"))
        writer.drain()
    }

    @Test
    fun `create re-parents the children under the link in one enqueue`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)

        links.create("page", link("l1", listOf(stroke("s1"), stroke("s2")), listOf(heading("h1"))))
        writer.drain()

        assertEquals("page", dao.rows["l1"]!!.parentId)
        assertEquals("l1", dao.rows["s1"]!!.parentId)
        assertEquals("l1", dao.rows["s2"]!!.parentId)
        assertEquals("l1", dao.rows["h1"]!!.parentId)
        // The page's loose content no longer contains the wrapped rows…
        assertTrue(dao.liveContentIds("page").isEmpty())
        // …but the deep set carries link + grandchildren.
        assertEquals(setOf("l1", "s1", "s2", "h1"), links.deepChildIds("page").toSet())
        writer.close()
    }

    @Test
    fun `create assigns tail z-order among the page's links`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        links.create("page", link("l1", listOf(stroke("s1")), emptyList()))
        links.create("page", link("l2", listOf(stroke("s2")), emptyList()))
        writer.drain()
        assertEquals(0, dao.rows["l1"]!!.order)
        assertEquals(1, dao.rows["l2"]!!.order)
        writer.close()
    }

    @Test
    fun `unlink releases the children back to the page and soft-deletes the row`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1"), stroke("s2")), listOf(heading("h1")))
        links.create("page", l)
        links.unlink("page", l)
        writer.drain()

        assertNotNull(dao.rows["l1"]!!.deletedAt)
        assertEquals("page", dao.rows["s1"]!!.parentId)
        assertEquals("page", dao.rows["h1"]!!.parentId)
        assertNull(dao.rows["s1"]!!.deletedAt)
        assertEquals(setOf("s1", "s2", "h1"), dao.liveContentIds("page").toSet())
        writer.close()
    }

    @Test
    fun `relink reverses an unlink in place`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1")), listOf(heading("h1")))
        links.create("page", l)
        links.unlink("page", l)
        links.relink("page", l)
        writer.drain()

        assertNull(dao.rows["l1"]!!.deletedAt)
        assertEquals("l1", dao.rows["s1"]!!.parentId)
        assertEquals("l1", dao.rows["h1"]!!.parentId)
        writer.close()
    }

    @Test
    fun `remove soft-deletes the link and everything it wraps, restore revives all of it`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1"), stroke("s2")), listOf(heading("h1")))
        links.create("page", l)
        links.remove(listOf(l))
        writer.drain()
        assertNotNull(dao.rows["l1"]!!.deletedAt)
        assertNotNull(dao.rows["s1"]!!.deletedAt)
        assertNotNull(dao.rows["h1"]!!.deletedAt)
        assertTrue(links.deepChildIds("page").isEmpty())

        links.restore("page", listOf(l))
        writer.drain()
        assertNull(dao.rows["l1"]!!.deletedAt)
        assertNull(dao.rows["s1"]!!.deletedAt)
        assertNull(dao.rows["h1"]!!.deletedAt)
        // Children still hang off the link, not the page.
        assertEquals("l1", dao.rows["s1"]!!.parentId)
        writer.close()
    }

    @Test
    fun `move shifts the row, re-encodes stroke children and shifts heading children`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1")), listOf(heading("h1")))
        links.create("page", l)
        writer.drain()

        links.move(listOf("l1"), 10f, -5f)
        writer.drain()
        assertEquals(10f, dao.rows["l1"]!!.x)
        assertEquals(-5f, dao.rows["l1"]!!.y)
        assertEquals(11f, dao.rows["h1"]!!.x)   // heading seeded at x=1
        val movedStroke = StrokeRows.toStroke(dao.rows["s1"]!!)!!
        assertEquals(11f, movedStroke.points[0].x)   // stroke point seeded at x=1
        assertEquals(-3f, movedStroke.points[0].y)   // y=2 - 5
        writer.close()
    }

    @Test
    fun `move in either direction round-trips (the undo replay contract)`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1")), listOf(heading("h1")))
        links.create("page", l)
        links.move(listOf("l1"), 7f, 3f)
        links.move(listOf("l1"), -7f, -3f)
        writer.drain()
        assertEquals(0f, dao.rows["l1"]!!.x)
        assertEquals(1f, dao.rows["h1"]!!.x)
        assertEquals(1f, StrokeRows.toStroke(dao.rows["s1"]!!)!!.points[0].x)
        writer.close()
    }

    @Test
    fun `loadPage decodes links with their children, strokes in writing order`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        links.create("page", link("l1", listOf(stroke("s1"), stroke("s2")), listOf(heading("h1"))))
        writer.drain()

        val loaded = links.loadPage("page")
        assertEquals(1, loaded.size)
        val l = loaded[0]
        assertEquals("l1", l.id)
        assertEquals(LinkPayload.CHROME_UNDERLINE, l.chrome)
        assertEquals(listOf("s1", "s2"), l.strokes.map { it.id })
        assertEquals(listOf("h1"), l.headings.map { it.id })
        writer.close()
    }

    @Test
    fun `updatePayload rewrites text only`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        val l = link("l1", listOf(stroke("s1")), emptyList())
        links.create("page", l)
        val edited = LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, "nb", null)
        links.updatePayload("l1", edited)
        writer.drain()
        assertEquals(edited, dao.rows["l1"]!!.text)
        assertEquals(0f, dao.rows["l1"]!!.x)
        writer.close()
    }

    @Test
    fun `relink revives in place — the snapshot's stale order never rewrites the row's z-order`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        // Two links so the second lands at a store-assigned order the host snapshot (order = 0)
        // does not know — the K5 review's overlap-tap scenario.
        links.create("page", link("l1", listOf(stroke("s1")), emptyList()))
        val snapshot = link("l2", listOf(stroke("s2")), emptyList())   // order = 0 in the snapshot
        links.create("page", snapshot)
        writer.drain()
        assertEquals(1, dao.rows["l2"]!!.order)

        links.unlink("page", snapshot)
        links.relink("page", snapshot)                                  // undo of the unlink
        writer.drain()
        assertNull(dao.rows["l2"]!!.deletedAt)
        assertEquals("l2", dao.rows["s2"]!!.parentId)
        assertEquals(1, dao.rows["l2"]!!.order)                         // kept, not the snapshot's 0
        writer.close()
    }

    @Test
    fun `restore revives in place too, and still upserts a row that never existed`() = runBlocking {
        val dao = FakeSoilDao()
        val (links, writer, stores) = make(dao)
        seed(dao, writer, stores)
        links.create("page", link("l1", listOf(stroke("s1")), emptyList()))
        val snapshot = link("l2", listOf(stroke("s2")), emptyList())
        links.create("page", snapshot)
        writer.drain()

        links.remove(listOf(snapshot))
        links.restore("page", listOf(snapshot))                         // undo of the delete
        writer.drain()
        assertNull(dao.rows["l2"]!!.deletedAt)
        assertEquals(1, dao.rows["l2"]!!.order)                         // kept, not the snapshot's 0

        // A row missing entirely (never written) still lands from the snapshot.
        val ghost = link("l9", emptyList(), emptyList())
        links.restore("page", listOf(ghost))
        writer.drain()
        assertNotNull(dao.rows["l9"])
        writer.close()
    }
}
