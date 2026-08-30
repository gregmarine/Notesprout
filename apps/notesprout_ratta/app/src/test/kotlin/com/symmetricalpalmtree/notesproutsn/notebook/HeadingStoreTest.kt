package com.symmetricalpalmtree.notesproutsn.notebook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heading store on the shared serial [SoilWriter]: z-order assignment, in-place restore,
 * move/update semantics, and cross-store ordering with [StrokeStore] on the one queue.
 */
class HeadingStoreTest {

    private fun heading(id: String, level: Int = 2) = Heading(
        id = id, text = "#".repeat(level) + " T-$id", level = level,
        x = 1f, y = 2f, width = 100f, height = 40f, order = 0,
    )

    private fun make(dao: FakeSoilDao): Pair<HeadingStore, SoilWriter> {
        val writer = SoilWriter {}
        return HeadingStore(dao, writer) to writer
    }

    @Test
    fun `create assigns tail z-order among the page's headings`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a"))
        store.create("page", heading("b"))
        writer.drain()
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order)
        writer.close()
    }

    @Test
    fun `z-order stays monotonic across a delete — deleted rows still count`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a"))
        store.erase(listOf("a"))
        store.create("page", heading("b"))
        writer.drain()
        assertEquals(1, dao.rows["b"]!!.order)
        writer.close()
    }

    @Test
    fun `restore is in place — geometry, level and order survive the round trip`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a", level = 3))
        store.erase(listOf("a"))
        writer.drain()
        assertNotNull(dao.rows["a"]!!.deletedAt)

        store.restore(listOf("a"))
        writer.drain()
        val row = dao.rows["a"]!!
        assertNull(row.deletedAt)
        assertEquals(0, row.order)
        assertEquals(3L, row.flags)
        assertEquals(1f, row.x)
        writer.close()
    }

    @Test
    fun `move shifts live headings only and skips strokes sharing the id list`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a"))
        store.create("page", heading("dead"))
        store.erase(listOf("dead"))
        writer.drain()

        store.move(listOf("a", "dead", "ghost"), 10f, -1f)
        writer.drain()
        assertEquals(11f, dao.rows["a"]!!.x)
        assertEquals(1f, dao.rows["a"]!!.y)
        assertEquals(1f, dao.rows["dead"]!!.x)   // deleted — untouched
        assertNull(dao.rows["ghost"])
        writer.close()
    }

    @Test
    fun `updateContent rewrites text, level and box — position and createdAt kept`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a", level = 2))
        writer.drain()
        val createdAt = dao.rows["a"]!!.createdAt

        store.updateContent(heading("a", level = 5).copy(text = "##### New", width = 200f, height = 80f))
        writer.drain()
        val row = dao.rows["a"]!!
        assertEquals("##### New", row.text)
        assertEquals(5L, row.flags)
        assertEquals(200f, row.width)
        assertEquals(80f, row.height)
        assertEquals(1f, row.x)
        assertEquals(createdAt, row.createdAt)
        writer.close()
    }

    @Test
    fun `loadPage returns live headings in z-order and drops malformed rows`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", heading("a"))
        store.create("page", heading("b"))
        store.create("page", heading("dead"))
        store.erase(listOf("dead"))
        writer.drain()
        dao.rows["b"] = dao.rows["b"]!!.copy(text = null)   // a foreign malformed row

        val loaded = store.loadPage("page")
        assertEquals(listOf("a"), loaded.map { it.id })
        writer.close()
    }

    @Test
    fun `stroke and heading writes share one queue in call order`() = runBlocking {
        val dao = FakeSoilDao()
        dao.upsertDelayMs = 20
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        val headings = HeadingStore(dao, writer)

        // The conversion sequence: ink soft-deleted, then its heading created — must land in order
        // even with a slow queue ahead of it.
        strokes.commit("page", com.symmetricalpalmtree.gpaper.core.model.Stroke(
            id = "s1",
            points = listOf(com.symmetricalpalmtree.gpaper.core.model.StrokePoint(0f, 0f)),
        ))
        strokes.erase(listOf("s1"))
        headings.create("page", heading("h1"))
        writer.drain()

        assertEquals(listOf("upsert:s1", "softDelete:s1", "upsert:h1"), dao.events)
        assertTrue(dao.rows["s1"]!!.deletedAt != null)
        assertNull(dao.rows["h1"]!!.deletedAt)
        writer.close()
    }
}
