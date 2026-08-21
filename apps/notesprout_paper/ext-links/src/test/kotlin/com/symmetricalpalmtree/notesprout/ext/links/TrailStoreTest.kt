package com.symmetricalpalmtree.notesprout.ext.links

import android.os.IBinder
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.LargeValue
import com.symmetricalpalmtree.notesprout.extension.TrailEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TrailStore] over an in-memory store: order, the drop-oldest cap, clear, and tolerant decoding. */
class TrailStoreTest {

    /** Inline values only — the trail is tiny and never goes near `putLarge`. */
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

    private fun entry(n: String) = TrailEntry("nb-$n", "pg-$n")

    @Test
    fun pushPopIsNewestFirst() {
        val store = FakeStore()
        assertNull(TrailStore.pop(store))
        TrailStore.push(store, entry("a"))
        TrailStore.push(store, entry("b"))
        TrailStore.push(store, entry("c"))
        assertEquals("nb-c", TrailStore.pop(store)!!.notebookId)
        assertEquals("nb-b", TrailStore.pop(store)!!.notebookId)
        val last = TrailStore.pop(store)!!
        assertEquals("nb-a", last.notebookId)
        assertEquals("pg-a", last.pageId)
        assertNull(TrailStore.pop(store))
        // The key is gone once the trail emptied — no stub value left behind.
        assertFalse(store.map.containsKey(TrailStore.KEY))
    }

    @Test
    fun capDropsTheOldest() {
        val store = FakeStore()
        val n = ExtensionContract.MAX_TRAIL_ENTRIES + 5
        for (i in 0 until n) TrailStore.push(store, entry(i.toString()))
        // Newest first, down to the oldest that survived — the first five were dropped.
        for (i in n - 1 downTo n - ExtensionContract.MAX_TRAIL_ENTRIES) {
            assertEquals("nb-$i", TrailStore.pop(store)!!.notebookId)
        }
        assertNull(TrailStore.pop(store))
    }

    @Test
    fun clearEmptiesTheTrail() {
        val store = FakeStore()
        TrailStore.push(store, entry("a"))
        TrailStore.push(store, entry("b"))
        TrailStore.clear(store)
        assertFalse(store.map.containsKey(TrailStore.KEY))
        assertNull(TrailStore.pop(store))
    }

    @Test
    fun malformedValuesReadAsEmptyAndAPushStartsFresh() {
        for (blob in listOf(
            ByteArray(0),
            "garbage".toByteArray(Charsets.UTF_8),                        // wrong version byte
            byteArrayOf(9, 0, 4, 'n'.code.toByte()),                      // known shape, unknown version
            byteArrayOf(1, 0, 40, 'n'.code.toByte(), 'b'.code.toByte()),  // truncated entry
        )) {
            val store = FakeStore()
            store.map[TrailStore.KEY] = blob
            assertNull("blob ${blob.toList()}", TrailStore.pop(store))
            TrailStore.push(store, entry("fresh"))
            val popped = TrailStore.pop(store)
            assertNotNull(popped)
            assertEquals("nb-fresh", popped!!.notebookId)
            assertNull(TrailStore.pop(store))
        }
    }

    @Test
    fun anEntryWithABadIdIsSkipped() {
        // A valid blob whose middle entry has a blank pageId: it is dropped, the others survive.
        val good = TrailStore.encode(listOf(entry("a"), entry("c")))
        val blob = spliceBlankEntryAfterFirst(TrailStore.encode(listOf(entry("a"), entry("c"))))
        val store = FakeStore()
        store.map[TrailStore.KEY] = blob
        assertEquals("nb-c", TrailStore.pop(store)!!.notebookId)
        assertEquals("nb-a", TrailStore.pop(store)!!.notebookId)
        assertNull(TrailStore.pop(store))
        assertTrue(blob.size > good.size)
    }

    /** Insert a `("nb-b", "")` entry after the first one — the format's own encoding, by hand. */
    private fun spliceBlankEntryAfterFirst(blob: ByteArray): ByteArray {
        val nb = "nb-b".toByteArray(Charsets.UTF_8)
        val bad = ByteArray(2 + nb.size + 2)
        bad[0] = 0; bad[1] = nb.size.toByte()
        System.arraycopy(nb, 0, bad, 2, nb.size)
        // The trailing two zero bytes are a zero-length pageId — blank, so the entry is skipped.
        val firstEnd = 1 + entryBytes(entry("a"))
        return blob.copyOfRange(0, firstEnd) + bad + blob.copyOfRange(firstEnd, blob.size)
    }

    private fun entryBytes(e: TrailEntry): Int =
        2 + e.notebookId.toByteArray(Charsets.UTF_8).size + 2 + e.pageId.toByteArray(Charsets.UTF_8).size
}
