package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.os.IBinder
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.LargeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ScratchStore.receive] — the notebook → pad placement (arc 6 / S2) over an in-memory store. */
class ScratchStoreReceiveTest {

    private class FakeStore : IExtensionStore {
        val map = LinkedHashMap<String, ByteArray>()
        override fun get(key: String): ByteArray? = map[key]
        override fun put(key: String, value: ByteArray) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
        override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override fun putLarge(key: String, value: LargeValue) = throw UnsupportedOperationException()
        override fun getLarge(key: String): LargeValue? = throw UnsupportedOperationException()
        override fun asBinder(): IBinder? = null
    }

    private fun stroke(id: String, n: Int = 4) = Stroke(id = id, points = List(n) { StrokePoint(it * 1f, it * 2f) }, width = 3f)

    @Test
    fun newPage_insertsAfterCurrent_andMakesItCurrent() {
        val fake = FakeStore()
        val store = ScratchStore(fake)
        val first = store.load().currentId
        store.savePage(first, ScratchPageCodec.encode(100f, 200f, listOf(stroke("old"))))
        val r = store.receive(listOf(stroke("a"), stroke("b")), 800f, 1200f, newPage = true)
        assertEquals(listOf("a", "b"), r.strokeIds)
        val after = store.load()
        assertEquals(2, after.ids.size)
        assertEquals(listOf(first, r.pageId), after.ids)
        assertEquals(r.pageId, after.currentId)
        val page = ScratchPageCodec.decode(store.readPage(r.pageId)!!)
        assertEquals(800f, page.pageWidth, 0f)
        assertEquals(listOf("a", "b"), page.strokes.map { it.id })
        assertEquals(listOf("old"), ScratchPageCodec.decode(store.readPage(first)!!).strokes.map { it.id })   // untouched
    }

    @Test
    fun currentPage_appends_keepsItsOwnSize_orTakesTheBundlesWhenNone() {
        val fake = FakeStore()
        val store = ScratchStore(fake)
        val first = store.load().currentId
        // No ink yet → the bundle's size is adopted.
        val r1 = store.receive(listOf(stroke("a")), 800f, 1200f, newPage = false)
        assertEquals(first, r1.pageId)
        assertEquals(1, store.load().ids.size)
        val p1 = ScratchPageCodec.decode(store.readPage(first)!!)
        assertEquals(800f, p1.pageWidth, 0f)
        // Existing ink + size → appended, size kept.
        val r2 = store.receive(listOf(stroke("b")), 999f, 999f, newPage = false)
        assertEquals(first, r2.pageId)
        val p2 = ScratchPageCodec.decode(store.readPage(first)!!)
        assertEquals(800f, p2.pageWidth, 0f)
        assertEquals(listOf("a", "b"), p2.strokes.map { it.id })
    }

    @Test
    fun fullRule_refusesWholePlacement_nothingInserted() {
        val fake = FakeStore()
        val store = ScratchStore(fake)
        val first = store.load().currentId
        var seed = 777L
        fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return Float.fromBits(((seed ushr 32).toInt() and 0x3FFFFFFF) or 0x20000000) }
        val huge = Stroke(id = "huge", points = List(ExtensionContract.STORE_MAX_VALUE_BYTES / 16 + 65_536) { StrokePoint(rnd(), rnd(), rnd(), rnd()) }, width = 3f)
        try { store.receive(listOf(huge), 0f, 0f, newPage = true); fail("expected PageFullException") } catch (e: PageFullException) { }
        assertEquals(listOf(first), store.load().ids)   // no page inserted
        assertNull(store.readPage(first))
        try { store.receive(listOf(huge), 0f, 0f, newPage = false); fail("expected PageFullException") } catch (e: PageFullException) { }
        assertNull(store.readPage(first))
        assertTrue(fake.map.keys.none { it.startsWith(ScratchStore.PAGE_PREFIX) })
    }
}
