package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two undo steps a **received** placement records (arc 11 / J5) — the screen builds them in
 * `ScratchPadActivity.consumeReceived`, and both replay through [ScratchDocument] like any other
 * pad edit. What is pinned here is the pair of shapes, because they are the arc's one asymmetry:
 *
 *  - a **current-page** placement is a [ScratchAction.Pasted] — undo removes exactly what arrived
 *    and leaves everything that was already on the page;
 *  - a **new-page** placement is a [ScratchAction.Page] whose `afterBlob` is the ink that came with
 *    it — undo takes the page away with its cargo, and redo brings **both** back. That second half
 *    is what the J4 shape could not do: its redo always dropped the blob.
 */
class ScratchReceivedUndoTest {

    private val surface = 1404f to 1872f

    private fun docOver(store: FakeExtensionStore) = ScratchDocument(ScratchStore(store)) { surface }

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK, width = 3f,
    )

    /** The screen's own sentence, in test form: place, load, build the entry. */
    private suspend fun placeAndOpen(
        store: FakeExtensionStore,
        arriving: List<Stroke>,
        newPage: Boolean,
    ): Pair<ScratchDocument, ScratchAction> {
        val received = ScratchStore(store).receive(arriving, surface.first, surface.second, newPage)
        val doc = docOver(store)
        doc.load()
        val action = if (received.newPage) {
            ScratchAction.Page(
                before = received.pagesBefore, beforeCurrent = received.currentBefore,
                after = doc.pageIds, afterCurrent = received.pageId,
                pageId = received.pageId, blob = null, afterBlob = doc.encodeCurrentPage(),
            )
        } else {
            val ids = received.strokeIds.toHashSet()
            ScratchAction.Pasted(received.pageId, doc.strokes.filter { it.id in ids })
        }
        return doc to action
    }

    // ── Current page ─────────────────────────────────────────────────────────

    @Test
    fun undoingACurrentPagePlacementRemovesOnlyWhatArrived() = runBlocking {
        val store = FakeExtensionStore()
        // Something already on the page, so "only what arrived" has teeth.
        run {
            val doc = docOver(store)
            doc.load()
            doc.addStroke(stroke("mine"))
            doc.flushUntilClean()
        }
        val (doc, action) = placeAndOpen(store, listOf(stroke("a", 9), stroke("b", 20)), newPage = false)
        assertEquals(listOf("mine", "a", "b"), doc.strokes.map { it.id })

        doc.revert(action)
        assertEquals(listOf("mine"), doc.strokes.map { it.id })

        doc.reapply(action)
        assertEquals(listOf("mine", "a", "b"), doc.strokes.map { it.id })
    }

    /** The store is the source of truth: what a reopen would show has to match what the screen holds. */
    @Test
    fun aCurrentPageUndoIsWrittenThrough() = runBlocking {
        val store = FakeExtensionStore()
        val (doc, action) = placeAndOpen(store, listOf(stroke("a")), newPage = false)
        doc.revert(action)

        val reopened = docOver(store)
        reopened.load()
        assertEquals(emptyList<String>(), reopened.strokes.map { it.id })
    }

    // ── New page ─────────────────────────────────────────────────────────────

    @Test
    fun undoingANewPagePlacementTakesThePageAwayWithItsInk() = runBlocking {
        val store = FakeExtensionStore()
        val (doc, action) = placeAndOpen(store, listOf(stroke("a"), stroke("b", 30)), newPage = true)
        val landed = doc.currentPageId
        assertEquals(2, doc.pageCount)
        assertEquals(listOf("a", "b"), doc.strokes.map { it.id })

        doc.revert(action)
        assertEquals(1, doc.pageCount)
        assertTrue(landed !in doc.pageIds)
        assertEquals(emptyList<String>(), doc.strokes.map { it.id })
        assertNull(store.values[ScratchStore.pageKey(landed)])
    }

    /** The half the J4 shape could not do: a redo of a received page is not a blank page. */
    @Test
    fun redoingANewPagePlacementBringsThePageBackWithItsInk() = runBlocking {
        val store = FakeExtensionStore()
        val (doc, action) = placeAndOpen(store, listOf(stroke("a"), stroke("b", 30)), newPage = true)
        val landed = doc.currentPageId
        doc.revert(action)
        doc.reapply(action)

        assertEquals(2, doc.pageCount)
        assertEquals(landed, doc.currentPageId)
        assertEquals(listOf("a", "b"), doc.strokes.map { it.id })

        val reopened = docOver(store)
        reopened.load()
        assertEquals(landed, reopened.currentPageId)
        assertEquals(listOf("a", "b"), reopened.strokes.map { it.id })
    }

    /** An ordinary insert still redoes blank — the two-blob shape must not have changed J4's cases. */
    @Test
    fun anOrdinaryInsertStillRedoesBlank() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        val action = doc.insert(after = true)
        doc.addStroke(stroke("written-after"))
        doc.flushUntilClean()

        doc.revert(action)
        doc.reapply(action)
        assertEquals(2, doc.pageCount)
        assertEquals(emptyList<String>(), doc.strokes.map { it.id })
    }
}
