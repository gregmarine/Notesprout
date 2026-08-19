package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.os.IBinder
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesprout.ext.scratchpad.ScratchUndo.Action
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.LargeValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ScratchDocument] over an in-memory store: pages, the full rule, flush, structural undo / redo. */
class ScratchDocumentTest {

    /** Inline values only (`putLarge` / `getLarge` need SharedMemory — the device covers them). */
    private class FakeStore : IExtensionStore {
        val map = LinkedHashMap<String, ByteArray>()
        var puts = 0
        /** Runs once, inside the next `get` of a page blob — simulates ink landing on Main during a page turn's read hop (S3). */
        var onNextPageRead: (() -> Unit)? = null
        override fun get(key: String): ByteArray? {
            if (key.startsWith(ScratchStore.PAGE_PREFIX)) onNextPageRead?.let { onNextPageRead = null; it() }
            return map[key]
        }
        override fun put(key: String, value: ByteArray) { puts++; map[key] = value }
        override fun delete(key: String) { map.remove(key) }
        override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override fun putLarge(key: String, value: LargeValue) = throw UnsupportedOperationException()
        override fun getLarge(key: String): LargeValue? = throw UnsupportedOperationException()
        override fun asBinder(): IBinder? = null
    }

    private fun stroke(id: String, n: Int = 4) = Stroke(id = id, points = List(n) { StrokePoint(it * 1f, it * 2f) }, width = 3f)

    private fun doc(store: FakeStore = FakeStore()): Pair<ScratchDocument, FakeStore> =
        ScratchDocument(ScratchStore(store)) to store

    @Test
    fun firstRunCreatesOnePageAndRemembersIt() = runBlocking {
        val (d, store) = doc()
        d.load()
        assertEquals(1, d.ids.size)
        assertEquals(0, d.currentIndex)
        assertTrue(d.strokes.isEmpty())
        assertEquals(d.currentId, store.map[ScratchStore.KEY_CURRENT]!!.toString(Charsets.UTF_8))
        // A second document over the same store sees the same page.
        val (d2, _) = doc(store)
        d2.load()
        assertEquals(d.ids, d2.ids)
    }

    @Test
    fun addFlushReload() = runBlocking {
        val (d, store) = doc()
        d.load()
        d.ensurePageSize(1000, 1500)
        assertTrue(d.add(stroke("a")))
        assertTrue(d.add(stroke("b")))
        assertTrue(d.dirty)
        assertEquals(ScratchPageCodec.HEADER_BYTES + ScratchPageCodec.strokeBytes(stroke("a")) + ScratchPageCodec.strokeBytes(stroke("b")), d.pageBytes)
        d.flush()
        assertFalse(d.dirty)
        assertEquals(d.pageBytes, store.map[ScratchStore.pageKey(d.currentId)]!!.size)   // the running total is exact
        d.flush()   // clean → no write
        val putsAfter = store.puts
        d.flush()
        assertEquals(putsAfter, store.puts)

        val (d2, _) = doc(store)
        d2.load()
        assertEquals(listOf("a", "b"), d2.strokes.keys.toList())
        assertEquals(1000f, d2.pageWidth, 0f)
        assertEquals(1500f, d2.pageHeight, 0f)
        assertEquals(d.pageBytes, d2.pageBytes)
    }

    @Test
    fun removeAndTranslateKeepTheTotalHonest() = runBlocking {
        val (d, _) = doc()
        d.load()
        d.add(stroke("a")); d.add(stroke("b", 9))
        d.translate(listOf("a"), 5f, 5f)
        assertEquals(5f, d.strokes["a"]!!.points[0].x, 0f)
        assertEquals(ScratchPageCodec.encode(0f, 0f, d.strokes.values.toList()).size, d.pageBytes)   // a move re-measures
        val taken = d.remove(listOf("b", "zz"))
        assertEquals(listOf("b"), taken.map { it.id })
        assertEquals(ScratchPageCodec.HEADER_BYTES + ScratchPageCodec.strokeBytes(d.strokes["a"]!!), d.pageBytes)
    }

    @Test
    fun fullRuleRefusesTheCrossingStroke() = runBlocking {
        val (d, _) = doc()
        d.load()
        // A stroke whose geometry alone is bigger than the cap can never be added (incompressible
        // pseudo-random floats — the geometry is zlib-compressed per stroke).
        var seed = 12345L
        fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return Float.fromBits(((seed ushr 32).toInt() and 0x3FFFFFFF) or 0x20000000) }
        val huge = Stroke(id = "huge", points = List(ExtensionContract.STORE_MAX_VALUE_BYTES / 16 + 65_536) { StrokePoint(rnd(), rnd(), rnd(), rnd()) }, width = 3f)
        assertFalse(d.add(huge))
        assertTrue(d.strokes.isEmpty())
        assertEquals(ScratchPageCodec.HEADER_BYTES, d.pageBytes)
        assertFalse(d.dirty)
        assertTrue(d.add(stroke("small")))
    }

    @Test
    fun insertDeleteNavigate() = runBlocking {
        val (d, store) = doc()
        d.load()
        val first = d.currentId
        d.add(stroke("a"))
        val ins = d.insert(after = true)
        assertEquals(2, d.ids.size)
        assertEquals(1, d.currentIndex)
        assertTrue(d.strokes.isEmpty())
        assertEquals(listOf(first), ins.before)
        assertEquals(d.ids, ins.after)
        assertNotNull(store.map[ScratchStore.pageKey(first)])   // the first page was flushed on leave
        d.add(stroke("b"))
        d.goTo(first)
        assertEquals(listOf("a"), d.strokes.keys.toList())
        d.goTo(d.ids[1])
        assertEquals(listOf("b"), d.strokes.keys.toList())
        val del = d.deleteCurrent()
        assertEquals(1, d.ids.size)
        assertEquals(first, d.currentId)
        assertEquals(listOf("a"), d.strokes.keys.toList())
        assertNotNull(del.blob)
        assertNull(store.map[ScratchStore.pageKey(del.changedId)])
        // The lone page is emptied, not removed.
        val lone = d.deleteCurrent()
        assertEquals(listOf(first), d.ids)
        assertEquals(first, d.currentId)
        assertTrue(d.strokes.isEmpty())
        assertEquals(lone.before, lone.after)
        assertNotNull(lone.blob)
    }

    @Test
    fun undoRedoStrokesAcrossPages() = runBlocking {
        val (d, _) = doc()
        d.load()
        val first = d.currentId
        d.add(stroke("a"))
        val drew = Action.Drew(first, stroke("a"))
        d.insert(after = true)
        val second = d.currentId
        // Undo the draw from the second page: the document turns back to the first page.
        assertTrue(d.revert(drew))
        assertEquals(first, d.currentId)
        assertTrue(d.strokes.isEmpty())
        assertTrue(d.reapply(drew))
        assertEquals(listOf("a"), d.strokes.keys.toList())
        // An action on a page that no longer exists is dropped.
        d.goTo(second)
        d.deleteCurrent()
        assertFalse(d.revert(Action.Drew(second, stroke("x"))))
        // Erased / Moved round trip.
        val taken = d.remove(listOf("a"))
        val erased = Action.Erased(first, taken)
        assertTrue(d.revert(erased)); assertEquals(listOf("a"), d.strokes.keys.toList())
        assertTrue(d.reapply(erased)); assertTrue(d.strokes.isEmpty())
        d.revert(erased)
        val moved = Action.Moved(first, listOf("a"), 3f, 4f)
        d.translate(listOf("a"), 3f, 4f)
        assertTrue(d.revert(moved)); assertEquals(0f, d.strokes["a"]!!.points[0].x, 0f)
        assertTrue(d.reapply(moved)); assertEquals(3f, d.strokes["a"]!!.points[0].x, 0f)
    }

    @Test
    fun undoRedoPageDeleteRestoresInk() = runBlocking {
        val (d, store) = doc()
        d.load()
        val first = d.currentId
        d.add(stroke("a"))
        d.insert(after = true)
        val second = d.currentId
        d.add(stroke("b"))
        val del = d.deleteCurrent()
        assertEquals(listOf(first), d.ids)
        assertTrue(d.revert(del))
        assertEquals(listOf(first, second), d.ids)
        assertEquals(second, d.currentId)
        assertEquals(listOf("b"), d.strokes.keys.toList())
        assertTrue(d.reapply(del))
        assertEquals(listOf(first), d.ids)
        assertEquals(first, d.currentId)
        assertNull(store.map[ScratchStore.pageKey(second)])
        assertEquals(listOf("a"), d.strokes.keys.toList())
        // Undo of an insert removes the (blank) page and lands where the user was.
        val ins = d.insert(after = false)
        assertEquals(0, d.currentIndex)
        assertTrue(d.revert(ins))
        assertEquals(listOf(first), d.ids)
        assertEquals(first, d.currentId)
        assertTrue(d.reapply(ins))
        assertEquals(2, d.ids.size)
        assertEquals(ins.changedId, d.currentId)
    }

    @Test
    fun lonePageDeleteUndoRefills() = runBlocking {
        val (d, _) = doc()
        d.load()
        val first = d.currentId
        d.add(stroke("a"))
        val lone = d.deleteCurrent()
        assertTrue(d.strokes.isEmpty())
        assertTrue(d.revert(lone))
        assertEquals(first, d.currentId)
        assertEquals(listOf("a"), d.strokes.keys.toList())
        assertTrue(d.reapply(lone))
        assertTrue(d.strokes.isEmpty())
    }
    @Test
    fun inkLandingDuringAPageTurnIsKeptOnThePageLeft() = runBlocking {
        val (d, store) = doc()
        d.load()
        d.ensurePageSize(1000, 1500)
        val first = d.currentId
        d.insert(after = true)
        val second = d.currentId
        // Turn back to the first page; while its blob is being read, a stroke lands (on `second`, the page being left).
        store.onNextPageRead = { assertTrue(d.add(stroke("late"))) }
        d.goTo(first)
        assertEquals(first, d.currentId)
        assertFalse(d.strokes.containsKey("late"))
        assertFalse(d.dirty)
        d.goTo(second)
        assertTrue("the late stroke was written to the page it landed on", d.strokes.containsKey("late"))
    }

    @Test
    fun anUndecodablePageIsUnreadableNotBlank() = runBlocking {
        val (d, store) = doc()
        d.load()
        store.map[ScratchStore.PAGE_PREFIX + d.currentId] = byteArrayOf(9, 0, 0)   // unknown version, short
        val (d2, _) = doc(store)
        try { d2.load(); fail("expected StoreUnavailable") } catch (e: StoreUnavailable) { }
        assertEquals(3, store.map[ScratchStore.PAGE_PREFIX + d.currentId]!!.size)   // untouched
    }
}
