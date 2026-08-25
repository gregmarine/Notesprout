package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pad's pages over a fake store: the load/flip/insert/delete cycle, the three correctness rules
 * the arc carries as traps (re-flush until clean, the full rule, an unreadable page is never
 * written over) and both directions of every undo action.
 */
class ScratchDocumentTest {

    private val surface = 1404f to 1872f

    private fun docOver(store: FakeExtensionStore) = ScratchDocument(ScratchStore(store)) { surface }

    private fun stroke(id: String, n: Int = 4, seed: Int = 0) = Stroke(
        id = id,
        points = List(n) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK, width = 3f,
    )

    /** A stroke whose geometry does not compress: the full rule needs real bytes, fast. */
    private fun fatStroke(id: String, n: Int = 2_000): Stroke {
        var x = id.hashCode()
        fun next(): Float {
            x = x * 1_103_515_245 + 12_345
            return (x ushr 8).toFloat() / 977f
        }
        return Stroke(id = id, points = List(n) { StrokePoint(next(), next(), next(), next(), 0L) })
    }

    // ── Loading and page turns ───────────────────────────────────────────────

    @Test
    fun firstRunLandsOnOneBlankPageSizedToTheSurface() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        assertEquals(1, doc.pageCount)
        assertEquals(1, doc.pageNumber)
        assertEquals(0, doc.strokes.size)
        assertEquals(1404f, doc.pageWidth, 0f)
        assertEquals(1872f, doc.pageHeight, 0f)
    }

    @Test
    fun inkSurvivesAPageTurnAndComesBack() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        val first = doc.currentPageId
        doc.addStroke(stroke("a"))
        doc.insert(after = true)          // flushes the departing page on the way out
        assertEquals(2, doc.pageCount)
        assertEquals(0, doc.strokes.size)
        doc.addStroke(stroke("b"))
        doc.goTo(first)
        assertEquals(listOf("a"), doc.strokes.map { it.id })
        doc.goToIndex(1)
        assertEquals(listOf("b"), doc.strokes.map { it.id })
    }

    /**
     * The re-flush rule: a stroke committed inside the window a flush's IO hop opens must not be
     * left in a map that was already encoded. [FakeExtensionStore.onPut] drops one in exactly there.
     */
    @Test
    fun aStrokeCommittedDuringAFlushIsWrittenByTheNextPass() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        val pageKey = ScratchStore.pageKey(doc.currentPageId)
        doc.addStroke(stroke("a"))
        var dropped = false
        store.onPut = { key ->
            if (key == pageKey && !dropped) {
                dropped = true
                doc.addStroke(stroke("late", seed = 40))
            }
        }
        doc.flushUntilClean()
        store.onPut = null
        assertTrue(dropped)
        assertFalse(doc.hasUnsavedChanges)
        val written = ScratchPageCodec.decode(store.values.getValue(pageKey)).strokes.map { it.id }
        assertEquals(listOf("a", "late"), written)
    }

    @Test
    fun insertBeforePutsTheNewPageFirst() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        val first = doc.currentPageId
        doc.insert(after = false)
        assertEquals(2, doc.pageCount)
        assertEquals(1, doc.pageNumber)
        assertEquals(first, doc.pageIds[1])
    }

    @Test
    fun deletingTheLastPageEmptiesItInsteadOfRemovingIt() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        val only = doc.currentPageId
        doc.addStroke(stroke("a"))
        doc.deleteCurrent()
        assertEquals(1, doc.pageCount)
        assertEquals(only, doc.currentPageId)
        assertEquals(0, doc.strokes.size)
        assertNull(store.values[ScratchStore.pageKey(only)])
    }

    // ── The full rule ────────────────────────────────────────────────────────

    @Test
    fun aStrokeThatWouldCrossTheValueCapIsRefusedWhole() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        var taken = 0
        var refused = false
        // Bounded well above what it takes to fill 4 MiB with incompressible geometry.
        for (i in 0 until 4_000) {
            when (doc.addStroke(fatStroke("s$i"))) {
                ScratchDocument.Add.OK -> taken++
                ScratchDocument.Add.PAGE_FULL -> { refused = true; break }
                ScratchDocument.Add.UNREADABLE -> error("not unreadable")
            }
        }
        assertTrue("the page never filled", refused)
        assertEquals(taken, doc.strokes.size)
        // The refusal has to be exact, not approximate: what is held encodes inside the cap, and
        // the running total is the encoder's own answer (zlib per stroke — never assumed).
        val encoded = ScratchPageCodec.encode(doc.pageWidth, doc.pageHeight, doc.strokes)
        assertTrue(encoded.size <= ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertTrue(encoded.size > ExtensionContract.STORE_MAX_VALUE_BYTES - 200_000)
    }

    // ── An unreadable page ───────────────────────────────────────────────────

    @Test
    fun anUnreadablePageIsShownEmptyAndNeverWrittenOver() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        val id = doc.currentPageId
        val key = ScratchStore.pageKey(id)
        // A blob whose version byte is not the one we write — long enough to pass the header check.
        val damaged = ByteArray(ScratchPageCodec.HEADER_BYTES + 8) { if (it == 0) 9 else 1 }
        store.values[key] = damaged

        val reopened = docOver(store)
        reopened.load()
        assertTrue(reopened.isUnreadable)
        assertEquals(0, reopened.strokes.size)
        assertEquals(ScratchDocument.Add.UNREADABLE, reopened.addStroke(stroke("a")))
        reopened.flushUntilClean()
        assertTrue(damaged.contentEquals(store.values.getValue(key)))
    }

    // ── Undo / redo replay ───────────────────────────────────────────────────

    @Test
    fun drewRevertsAndReapplies() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        val s = stroke("a")
        doc.addStroke(s)
        val action = ScratchAction.Drew(doc.currentPageId, s)
        doc.revert(action)
        assertEquals(0, doc.strokes.size)
        doc.reapply(action)
        assertEquals(listOf("a"), doc.strokes.map { it.id })
    }

    @Test
    fun erasedComesBackWhereItWas() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        listOf("a", "b", "c", "d").forEach { doc.addStroke(stroke(it)) }
        val action = doc.erase(listOf("b", "d"))!!
        assertEquals(listOf("a", "c"), doc.strokes.map { it.id })
        assertEquals(listOf(1, 3), action.entries.map { it.index })
        doc.revert(action)
        // In place, not appended: the page's stroke order is what makes its blob stable.
        assertEquals(listOf("a", "b", "c", "d"), doc.strokes.map { it.id })
        doc.reapply(action)
        assertEquals(listOf("a", "c"), doc.strokes.map { it.id })
    }

    @Test
    fun moveTranslatesBothWaysAndReMeasures() = runBlocking {
        val store = FakeExtensionStore()
        val doc = docOver(store)
        doc.load()
        doc.addStroke(stroke("a"))
        val before = doc.strokes.first().points.first().x
        val action = doc.move(listOf("a"), 12f, -7f)!!
        assertEquals(before + 12f, doc.strokes.first().points.first().x, 0f)
        doc.revert(action)
        assertEquals(before, doc.strokes.first().points.first().x, 0f)
        // The flush that the revert ran is the proof the re-measure held: the store's blob is the
        // encoder's own output for what is now in memory.
        val key = ScratchStore.pageKey(doc.currentPageId)
        val expected = ScratchPageCodec.encode(doc.pageWidth, doc.pageHeight, doc.strokes)
        assertTrue(expected.contentEquals(store.values.getValue(key)))
    }

    @Test
    fun undoingAnInsertTakesThePageAwayAgain() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        val first = doc.currentPageId
        val action = doc.insert(after = true)
        assertEquals(2, doc.pageCount)
        doc.revert(action)
        assertEquals(1, doc.pageCount)
        assertEquals(first, doc.currentPageId)
        doc.reapply(action)
        assertEquals(2, doc.pageCount)
        assertNotEquals(first, doc.currentPageId)
    }

    @Test
    fun undoingADeletePutsThePageAndItsInkBack() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        doc.addStroke(stroke("a"))
        doc.insert(after = true)
        doc.addStroke(stroke("b"))
        val second = doc.currentPageId
        val action = doc.deleteCurrent()
        assertEquals(1, doc.pageCount)
        doc.revert(action)
        assertEquals(2, doc.pageCount)
        assertEquals(second, doc.currentPageId)
        assertEquals(listOf("b"), doc.strokes.map { it.id })
        doc.reapply(action)
        assertEquals(1, doc.pageCount)
        assertEquals(listOf("a"), doc.strokes.map { it.id })
    }

    @Test
    fun undoingTheEmptyingOfTheLonePagePutsItsInkBack() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        doc.addStroke(stroke("a"))
        val action = doc.deleteCurrent()
        assertEquals(0, doc.strokes.size)
        doc.revert(action)
        assertEquals(1, doc.pageCount)
        assertEquals(listOf("a"), doc.strokes.map { it.id })
    }

    @Test
    fun aReplayForAPageThatIsGoneIsSkipped() = runBlocking {
        val doc = docOver(FakeExtensionStore())
        doc.load()
        val first = doc.currentPageId
        val s = stroke("a")
        doc.addStroke(s)
        val drew = ScratchAction.Drew(first, s)
        doc.insert(after = true)
        val onSecond = doc.currentPageId
        // The first page leaves; the stroke action that named it has nothing to reverse.
        doc.goTo(first)
        doc.deleteCurrent()
        assertEquals(listOf(onSecond), doc.pageIds)
        doc.revert(drew)
        assertEquals(onSecond, doc.currentPageId)
        assertEquals(0, doc.strokes.size)
    }

    // ── Store failure ────────────────────────────────────────────────────────

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        val store = FakeExtensionStore()
        store.failWith = { RuntimeException("binder gone") }
        val doc = docOver(store)
        val thrown = runCatching { runBlocking { doc.load() } }.exceptionOrNull()
        assertTrue("was $thrown", thrown is StoreUnavailable)
    }
}
