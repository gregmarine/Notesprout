package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ScratchStore.receive` — the notebook → pad placement (arc 11 / J5), run on the Binder thread
 * before the screen exists. What is pinned here is what the screen and the host both depend on:
 * where the ink lands, which page becomes current, what the [ScratchStore.Received] record says so
 * the screen can record **one** undo step from it, and — the arc's own trap — that a placement over
 * the value cap **leaves nothing behind at all**: no ink, no inserted page, no moved current.
 */
class ScratchStoreReceiveTest {

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK, width = 3f,
    )

    /** A stroke whose geometry does not compress — the full rule needs real bytes. */
    private fun fatStroke(id: String, n: Int = 2_000): Stroke {
        var x = id.hashCode()
        fun next(): Float {
            x = x * 1_103_515_245 + 12_345
            return (x ushr 8).toFloat() / 977f
        }
        return Stroke(id = id, points = List(n) { StrokePoint(next(), next(), next(), next(), 0L) })
    }

    private fun pageIds(store: FakeExtensionStore): List<String> =
        store.values[ScratchStore.KEY_PAGES]!!.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }

    private fun current(store: FakeExtensionStore): String =
        store.values[ScratchStore.KEY_CURRENT]!!.toString(Charsets.UTF_8)

    private fun strokesOn(store: FakeExtensionStore, id: String): List<Stroke> =
        ScratchPageCodec.decode(store.values[ScratchStore.pageKey(id)]!!).strokes

    // ── New page ─────────────────────────────────────────────────────────────

    @Test
    fun newPageIsInsertedAfterTheCurrentOneAndBecomesCurrent() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val first = s.load().currentId
        val received = s.receive(listOf(stroke("a"), stroke("b", seed = 40)), 1404f, 1872f, newPage = true)

        assertTrue(received.newPage)
        assertEquals(listOf(first, received.pageId), pageIds(store))
        assertEquals(received.pageId, current(store))
        assertEquals(listOf("a", "b"), received.strokeIds)
        assertEquals(listOf("a", "b"), strokesOn(store, received.pageId).map { it.id })
        // The page it was sent from is untouched — a send is a copy.
        assertNull(store.values[ScratchStore.pageKey(first)])
    }

    @Test
    fun aNewPageTakesTheBundlesPageSize() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        s.load()
        val received = s.receive(listOf(stroke("a")), 1404f, 1872f, newPage = true)
        val page = ScratchPageCodec.decode(store.values[ScratchStore.pageKey(received.pageId)]!!)
        assertEquals(1404f, page.pageWidth, 0f)
        assertEquals(1872f, page.pageHeight, 0f)
    }

    /** The record has to carry the state the placement moved *from*, or the screen cannot record a
     *  single undo step that takes the page away again. */
    @Test
    fun theRecordCarriesTheStateBeforeThePlacement() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val loaded = s.load()
        val received = s.receive(listOf(stroke("a")), 1404f, 1872f, newPage = true)
        assertEquals(loaded.ids, received.pagesBefore)
        assertEquals(loaded.currentId, received.currentBefore)
        assertEquals(1, received.pagesBefore.size)
    }

    // ── Current page ─────────────────────────────────────────────────────────

    @Test
    fun currentPagePlacementAppendsAndInsertsNoPage() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val first = s.load().currentId
        s.savePage(first, ScratchPageCodec.encode(800f, 1000f, listOf(stroke("old"))))

        val received = s.receive(listOf(stroke("new", seed = 9)), 1404f, 1872f, newPage = false)

        assertFalse(received.newPage)
        assertEquals(first, received.pageId)
        assertEquals(listOf(first), pageIds(store))
        // Appended, in order, after what was already there.
        assertEquals(listOf("old", "new"), strokesOn(store, first).map { it.id })
    }

    /** The page keeps the size it already had: it is the pad's page, not the sender's. */
    @Test
    fun currentPageKeepsItsOwnSize() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val first = s.load().currentId
        s.savePage(first, ScratchPageCodec.encode(800f, 1000f, listOf(stroke("old"))))
        s.receive(listOf(stroke("new")), 1404f, 1872f, newPage = false)
        val page = ScratchPageCodec.decode(store.values[ScratchStore.pageKey(first)]!!)
        assertEquals(800f, page.pageWidth, 0f)
        assertEquals(1000f, page.pageHeight, 0f)
    }

    /** A page that has never been written has no size of its own, so it takes the bundle's. */
    @Test
    fun aPageWithNoSizeYetTakesTheBundles() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val first = s.load().currentId
        s.receive(listOf(stroke("a")), 1404f, 1872f, newPage = false)
        val page = ScratchPageCodec.decode(store.values[ScratchStore.pageKey(first)]!!)
        assertEquals(1404f, page.pageWidth, 0f)
        assertEquals(1872f, page.pageHeight, 0f)
    }

    // ── The full rule ────────────────────────────────────────────────────────

    /** Over the cap on a **new page**: nothing placed, and — the half that is easy to get wrong —
     *  no page inserted and the current page not moved. */
    @Test
    fun aNewPagePlacementOverTheCapLeavesNothingBehind() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val loaded = s.load()
        val fat = (0 until 700).map { fatStroke("f$it") }

        var threw = false
        try {
            s.receive(fat, 1404f, 1872f, newPage = true)
        } catch (e: PageFullException) {
            threw = true
            assertTrue(e.bytes > ExtensionContract.STORE_MAX_VALUE_BYTES)
        }
        assertTrue("expected PageFullException", threw)
        assertEquals(loaded.ids, pageIds(store))
        assertEquals(loaded.currentId, current(store))
        assertTrue(store.values.keys.none { it.startsWith(ScratchStore.PAGE_PREFIX) })
    }

    /** Over the cap on the **current** page: the page keeps exactly the bytes it had. */
    @Test
    fun aCurrentPagePlacementOverTheCapDoesNotTouchThePage() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        val first = s.load().currentId
        val before = ScratchPageCodec.encode(1404f, 1872f, listOf(stroke("old")))
        s.savePage(first, before)
        val fat = (0 until 700).map { fatStroke("f$it") }

        var threw = false
        try {
            s.receive(fat, 1404f, 1872f, newPage = false)
        } catch (e: PageFullException) {
            threw = true
        }
        assertTrue("expected PageFullException", threw)
        assertArrayEquals(before, store.values[ScratchStore.pageKey(first)])
    }

    // ── The store is gone ────────────────────────────────────────────────────

    @Test
    fun anyStoreFailureDuringAPlacementReadsAsUnavailable() {
        val store = FakeExtensionStore()
        val s = ScratchStore(store)
        s.load()
        store.failWith = { SecurityException("revoked") }
        var threw = false
        try {
            s.receive(listOf(stroke("a")), 1404f, 1872f, newPage = true)
        } catch (e: StoreUnavailable) {
            threw = true
        }
        assertTrue("expected StoreUnavailable", threw)
    }
}
