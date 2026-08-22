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
 * The single-serial-writer contract: rows land in callback order, [SoilWriter.drain] really
 * waits, and the `updatedAt` bump is debounced but flushable. Runs against the in-memory
 * [FakeSoilDao] — the queue and ordering logic are what is under test, not Room.
 */
class StrokeStoreTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
    )

    private fun store(dao: FakeSoilDao, onEdited: suspend () -> Unit = {}): Pair<StrokeStore, SoilWriter> {
        val writer = SoilWriter(onEdited)
        return StrokeStore(dao, writer) to writer
    }

    @Test
    fun `commits land in call order with sequential orders`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = store(dao)
        store.commit("page", stroke("a"))
        store.commit("page", stroke("b"))
        store.commit("page", stroke("c"))
        store.drain()
        assertEquals(listOf("upsert:a", "upsert:b", "upsert:c"), dao.events)
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order)
        assertEquals(2, dao.rows["c"]!!.order)
        writer.close()
    }

    @Test
    fun `a commit followed by an erase of the same stroke can never race`() = runBlocking {
        val dao = FakeSoilDao()
        dao.upsertDelayMs = 30 // even with a slow insert, the erase must wait its turn
        val (store, writer) = store(dao)
        store.commit("page", stroke("a"))
        store.erase(listOf("a"))
        store.drain()
        assertEquals(listOf("upsert:a", "softDelete:a"), dao.events)
        assertNotNull(dao.rows["a"]!!.deletedAt)
        writer.close()
    }

    @Test
    fun `order stays monotonic across an erase — deleted rows still count`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = store(dao)
        store.commit("page", stroke("a"))
        store.erase(listOf("a"))
        store.commit("page", stroke("b"))
        store.drain()
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order) // not 0 — "a" is soft-deleted, not gone
        writer.close()
    }

    @Test
    fun `drain waits for every write queued before it`() = runBlocking {
        val dao = FakeSoilDao()
        dao.upsertDelayMs = 20
        val (store, writer) = store(dao)
        repeat(5) { store.commit("page", stroke("s$it")) }
        store.drain()
        assertEquals(5, dao.rows.size)
        writer.close()
    }

    @Test
    fun `move rewrites geometry, keeps createdAt, skips deleted rows`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = store(dao)
        store.commit("page", stroke("live"))
        store.commit("page", stroke("dead"))
        store.erase(listOf("dead"))
        store.drain()
        val createdAt = dao.rows["live"]!!.createdAt

        store.move(listOf("live", "dead", "ghost"), 10f, -5f)
        store.drain()

        val moved = StrokeRows.toStroke(dao.rows["live"]!!)!!
        assertEquals(11f, moved.points[0].x, 0f)
        assertEquals(-3f, moved.points[0].y, 0f)
        assertEquals(createdAt, dao.rows["live"]!!.createdAt)
        assertNotNull(dao.rows["dead"]!!.deletedAt) // untouched
        assertNull(dao.rows["ghost"])
        writer.close()
    }

    @Test
    fun `revive restores rows in place — order and geometry survive`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = store(dao)
        store.commit("page", stroke("a"))
        store.commit("page", stroke("b"))
        store.erase(listOf("a", "b"))
        store.commit("page", stroke("c"))
        store.drain()

        store.revive(listOf("a", "b"))
        store.drain()

        assertNull(dao.rows["a"]!!.deletedAt)
        assertNull(dao.rows["b"]!!.deletedAt)
        // In place: the original writing order is intact (the heading-undo contract), so a page
        // load — and a later re-recognize — still sees a, b, c as the sequence they were written.
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order)
        assertEquals(2, dao.rows["c"]!!.order)
        writer.close()
    }

    @Test
    fun `empty and zero-delta writes are not queued`() = runBlocking {
        val dao = FakeSoilDao()
        var touches = 0
        val (store, writer) = store(dao) { touches++ }
        store.erase(emptyList())
        store.revive(emptyList())
        store.move(emptyList(), 1f, 1f)
        store.move(listOf("a"), 0f, 0f)
        store.drain()
        assertTrue(dao.events.isEmpty())
        writer.flushTouch()
        assertEquals(0, touches) // nothing was queued, so no bump was ever scheduled
        writer.close()
    }

    @Test
    fun `writes after close are dropped without crashing`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = store(dao)
        store.commit("page", stroke("a"))
        store.drain()
        writer.close()
        store.commit("page", stroke("b")) // dropped
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `touch is debounced but flushTouch bumps immediately`() = runBlocking {
        val dao = FakeSoilDao()
        var touches = 0
        val (store, writer) = store(dao) { touches++ }
        store.commit("page", stroke("a"))
        store.drain()
        assertEquals(0, touches) // inside the 2 s debounce window
        writer.flushTouch()
        assertEquals(1, touches)
        writer.flushTouch()
        assertEquals(1, touches) // idempotent — no pending job left
        writer.close()
    }
}
