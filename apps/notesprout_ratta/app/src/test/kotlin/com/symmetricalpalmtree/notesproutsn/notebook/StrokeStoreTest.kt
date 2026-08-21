package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-serial-writer contract: rows land in callback order, [StrokeStore.drain] really
 * waits, and the `updatedAt` bump is debounced but flushable. Runs against an in-memory
 * [SoilDao] fake — the queue and ordering logic are what is under test, not Room.
 */
class StrokeStoreTest {

    /** Minimal in-memory `notebook` table + an event log in apply order. */
    private class FakeDao : SoilDao {
        val rows = LinkedHashMap<String, SoilObjectEntity>()
        val events = mutableListOf<String>()
        var upsertDelayMs = 0L

        override suspend fun upsert(row: SoilObjectEntity) {
            if (upsertDelayMs > 0) delay(upsertDelayMs)
            rows[row.id] = row
            events += "upsert:${row.id}"
        }
        override suspend fun upsertAll(rows: List<SoilObjectEntity>) = rows.forEach { upsert(it) }
        override suspend fun byId(id: String) = rows[id]
        override suspend fun byIds(ids: List<String>) = ids.mapNotNull { rows[it] }
        override suspend fun childrenOfType(parentId: String, type: String) =
            rows.values.filter { it.parentId == parentId && it.type == type && it.deletedAt == null }
                .sortedBy { it.order }
        override suspend fun notebookRow() = rows.values.firstOrNull { it.type == "notebook" }
        override suspend fun livePageCount() = rows.values.count { it.type == "page" && it.deletedAt == null }
        override suspend fun softDelete(ids: List<String>, at: Long) {
            for (id in ids) rows[id]?.let { if (it.deletedAt == null) rows[id] = it.copy(deletedAt = at, updatedAt = at) }
            events += "softDelete:${ids.joinToString(",")}"
        }
        override suspend fun restore(ids: List<String>, at: Long) {
            for (id in ids) rows[id]?.let { if (it.deletedAt != null) rows[id] = it.copy(deletedAt = null, updatedAt = at) }
            events += "restore:${ids.joinToString(",")}"
        }
        override suspend fun liveStrokeIds(pageId: String) =
            rows.values.filter { it.parentId == pageId && it.type == "stroke" && it.deletedAt == null }.map { it.id }
        override suspend fun setRefId(id: String, refId: String?, at: Long) {
            rows[id]?.let { rows[id] = it.copy(refId = refId, updatedAt = at) }
        }
        override suspend fun setText(id: String, text: String?, at: Long) {
            rows[id]?.let { rows[id] = it.copy(text = text, updatedAt = at) }
        }
        override suspend fun setOrder(id: String, order: Int, at: Long) {
            rows[id]?.let { rows[id] = it.copy(order = order, updatedAt = at) }
        }
        override suspend fun setBlob(id: String, blob: ByteArray?, at: Long) {
            rows[id]?.let { rows[id] = it.copy(blob = blob, updatedAt = at) }
        }
        override suspend fun maxOrder(parentId: String, type: String) =
            rows.values.filter { it.parentId == parentId && it.type == type }.maxOfOrNull { it.order } ?: -1
    }

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
    )

    @Test
    fun `commits land in call order with sequential orders`() = runBlocking {
        val dao = FakeDao()
        val store = StrokeStore(dao) {}
        store.commit("page", stroke("a"))
        store.commit("page", stroke("b"))
        store.commit("page", stroke("c"))
        store.drain()
        assertEquals(listOf("upsert:a", "upsert:b", "upsert:c"), dao.events)
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order)
        assertEquals(2, dao.rows["c"]!!.order)
        store.close()
    }

    @Test
    fun `a commit followed by an erase of the same stroke can never race`() = runBlocking {
        val dao = FakeDao()
        dao.upsertDelayMs = 30 // even with a slow insert, the erase must wait its turn
        val store = StrokeStore(dao) {}
        store.commit("page", stroke("a"))
        store.erase(listOf("a"))
        store.drain()
        assertEquals(listOf("upsert:a", "softDelete:a"), dao.events)
        assertNotNull(dao.rows["a"]!!.deletedAt)
        store.close()
    }

    @Test
    fun `order stays monotonic across an erase — deleted rows still count`() = runBlocking {
        val dao = FakeDao()
        val store = StrokeStore(dao) {}
        store.commit("page", stroke("a"))
        store.erase(listOf("a"))
        store.commit("page", stroke("b"))
        store.drain()
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order) // not 0 — "a" is soft-deleted, not gone
        store.close()
    }

    @Test
    fun `drain waits for every write queued before it`() = runBlocking {
        val dao = FakeDao()
        dao.upsertDelayMs = 20
        val store = StrokeStore(dao) {}
        repeat(5) { store.commit("page", stroke("s$it")) }
        store.drain()
        assertEquals(5, dao.rows.size)
        store.close()
    }

    @Test
    fun `move rewrites geometry, keeps createdAt, skips deleted rows`() = runBlocking {
        val dao = FakeDao()
        val store = StrokeStore(dao) {}
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
        store.close()
    }

    @Test
    fun `empty and zero-delta writes are not queued`() = runBlocking {
        val dao = FakeDao()
        var touches = 0
        val store = StrokeStore(dao) { touches++ }
        store.erase(emptyList())
        store.move(emptyList(), 1f, 1f)
        store.move(listOf("a"), 0f, 0f)
        store.drain()
        assertTrue(dao.events.isEmpty())
        store.flushTouch()
        assertEquals(0, touches) // nothing was queued, so no bump was ever scheduled
        store.close()
    }

    @Test
    fun `writes after close are dropped without crashing`() = runBlocking {
        val dao = FakeDao()
        val store = StrokeStore(dao) {}
        store.commit("page", stroke("a"))
        store.drain()
        store.close()
        store.commit("page", stroke("b")) // dropped
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `touch is debounced but flushTouch bumps immediately`() = runBlocking {
        val dao = FakeDao()
        var touches = 0
        val store = StrokeStore(dao) { touches++ }
        store.commit("page", stroke("a"))
        store.drain()
        assertEquals(0, touches) // inside the 2 s debounce window
        store.flushTouch()
        assertEquals(1, touches)
        store.flushTouch()
        assertEquals(1, touches) // idempotent — no pending job left
        store.close()
    }
}
