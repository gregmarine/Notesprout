package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.PageInk
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pad's pages over the statement-recording fake (arc 22 / X2): the op log and what it flushes,
 * the writing order a restore has to land back in, the re-flush rule, and both directions of every
 * undo action — now as statements rather than a re-encoded page blob.
 */
class ScratchDocumentTest {

    private val surface = 1404f to 1872f

    private fun docOver(fake: FakeScratchStore) = ScratchDocument(ScratchStore(fake)) { surface }

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value
    private fun long(cell: Cell) = (cell as Cell.Integer).value

    /** The pad as it is on a second open: one sized page, already current. */
    private fun loaded(fake: FakeScratchStore, id: String = "p1", ink: PageInk = PageInk(1404f, 1872f, emptyList())): ScratchDocument {
        fake.page(id, ink)
        val doc = docOver(fake)
        runBlocking { doc.load() }
        fake.execs.clear()
        fake.calls.clear()
        return doc
    }

    private fun puts(fake: FakeScratchStore): List<Statement> =
        fake.statements.filter { it.sql.startsWith("INSERT OR REPLACE INTO stroke") }

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun firstRunLandsOnOneBlankPageSizedToTheSurface() = runBlocking {
        val doc = docOver(FakeScratchStore())
        doc.load()
        assertEquals(1, doc.pageCount)
        assertEquals(1, doc.pageNumber)
        assertEquals(0, doc.strokes.size)
        assertEquals(1404f, doc.pageWidth, 0f)
        assertEquals(1872f, doc.pageHeight, 0f)
        // A page that has just learned its size owes the row an UPDATE.
        assertTrue(doc.hasUnsavedChanges)
    }

    @Test
    fun aStoredPagesInkComesBackInItsWritingOrder() = runBlocking {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(800f, 1000f, listOf(0L to stroke("a"), 4L to stroke("b"), 9L to stroke("c"))))
        val doc = docOver(fake)
        doc.load()
        assertEquals(listOf("a", "b", "c"), doc.strokes.map { it.id })
        assertEquals(4L, doc.orderOf("b"))
        assertFalse(doc.hasUnsavedChanges)
    }

    // ── The op log ───────────────────────────────────────────────────────────

    @Test
    fun oneAddedStrokeFlushesAsOnePut_atTheEndOfTheOrder() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(7L to stroke("old"))))
        doc.addStroke(stroke("a"))
        assertEquals(8L, doc.orderOf("a"))
        doc.flushUntilClean()

        val put = puts(fake).single()
        assertEquals("a", text(put.args[0]))
        assertEquals("p1", text(put.args[1]))
        assertEquals(8L, long(put.args[2]))
        assertFalse(doc.hasUnsavedChanges)
    }

    @Test
    fun addThenEraseTheSameStrokeFlushesAsOneDelete() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        doc.addStroke(stroke("a"))
        doc.erase(listOf("a"))
        doc.flushUntilClean()
        // Coalesced onto one entry, and it is the DELETE — a Put that may already be stored must
        // never be forgotten rather than dropped, and DELETE tolerates a row that never landed.
        assertEquals(listOf("DELETE FROM stroke WHERE id = ?"), fake.sql())
        assertEquals("a", text(fake.statements.single().args[0]))
    }

    @Test
    fun moveRewritesTheRowAtTheOrderItAlreadyHeld() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(3L to stroke("a"))))
        val before = doc.strokes.single().points.first().x
        doc.move(listOf("a"), 12f, -7f)
        doc.flushUntilClean()

        val put = puts(fake).single()
        assertEquals(3L, long(put.args[2]))
        assertEquals(before + 12f, doc.strokes.single().points.first().x, 0f)
        // One statement per touched stroke — not a page.
        assertEquals(1, fake.statements.size)
    }

    @Test
    fun aPageThatLearnsItsSizeUpdatesTheRowOnceAndOnlyOnce() = runBlocking {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk.EMPTY)
        val doc = docOver(fake)
        doc.load()
        fake.execs.clear()
        doc.flushUntilClean()
        assertEquals(listOf("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?"), fake.sql())
        fake.execs.clear()
        doc.flushUntilClean()
        assertTrue(fake.execs.isEmpty())
    }

    // ── Re-flush until clean, and a failed flush ─────────────────────────────

    @Test
    fun everyEditIsWrittenEvenWhenTheyKeepArriving() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        doc.addStroke(stroke("a"))
        doc.addStroke(stroke("b"))
        doc.erase(listOf("a"))
        doc.flushUntilClean()
        assertEquals(
            listOf("DELETE FROM stroke WHERE id = ?", "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)"),
            fake.sql().sorted().reversed().sorted(),
        )
        assertFalse(doc.hasUnsavedChanges)
    }

    @Test
    fun aFailedFlushKeepsItsWorkAndLetsNewerEditsWin() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        doc.addStroke(stroke("a"))
        doc.addStroke(stroke("b"))
        fake.failWith = { SecurityException("revoked") }

        var thrown: Throwable? = null
        try {
            doc.flushUntilClean()
        } catch (e: StoreUnavailable) {
            thrown = e
        }
        assertTrue("expected StoreUnavailable, was $thrown", thrown is StoreUnavailable)
        // Nothing was lost: the snapshot came back and the page is still dirty.
        assertTrue(doc.hasUnsavedChanges)

        // A newer edit to one of the same strokes wins over the entry that came back under it.
        doc.erase(listOf("a"))
        fake.failWith = null
        doc.flushUntilClean()
        val sql = fake.sql()
        assertEquals(1, sql.count { it == "DELETE FROM stroke WHERE id = ?" })
        assertEquals(1, puts(fake).size)
        assertEquals("b", text(puts(fake).single().args[0]))
    }

    // ── Page turns ───────────────────────────────────────────────────────────

    @Test
    fun goToReadsTheTargetBeforeFlushingTheDepartingPage() = runBlocking {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(1404f, 1872f, emptyList()))
        fake.page("p2", PageInk(1404f, 1872f, listOf(0L to stroke("b"))))
        val doc = docOver(fake)
        doc.load()
        fake.calls.clear()
        fake.execs.clear()

        doc.addStroke(stroke("a"))
        doc.goTo("p2")
        // The target's three reads come first; the departing page's flush lands after them, and the
        // new current page is named last.
        assertEquals(listOf("query(size)", "query(lens)", "query(strokes)", "exec(1)", "exec(1)"), fake.calls)
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            fake.sql(),
        )
        assertEquals("p1", text(fake.statements[0].args[1]))
        assertEquals("p2", text(fake.statements[1].args[0]))
        assertEquals(listOf("b"), doc.strokes.map { it.id })
    }

    @Test
    fun insertAndDeleteMoveThePageList() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        val inserted = doc.insert(after = true)
        assertEquals(2, doc.pageCount)
        assertEquals(0, doc.strokes.size)
        assertNotEquals("p1", doc.pageId)
        assertEquals(listOf("p1"), inserted.before)
        assertEquals(doc.pageIds, inserted.after)

        // The new page has just learned the surface size; get that down so the delete stands alone.
        doc.flushUntilClean()
        fake.execs.clear()
        val deleted = doc.deleteCurrent()
        assertEquals(1, doc.pageCount)
        assertEquals("p1", doc.pageId)
        assertEquals("DELETE FROM page WHERE id = ?", fake.sql().first())
        assertEquals(inserted.pageId, deleted.pageId)
    }

    @Test
    fun deletingAPageCarriesItsInkForTheUndo() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(800f, 1000f, listOf(2L to stroke("a"), 3L to stroke("b"))))
        val action = doc.deleteCurrent()
        assertEquals(listOf(2L, 3L), action.ink!!.strokes.map { it.first })
        assertEquals(800f, action.ink!!.width, 0f)
        assertEquals(null, action.afterInk)
    }

    // ── Undo / redo replay ───────────────────────────────────────────────────

    @Test
    fun drewRevertsAndReapplies() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        val s = stroke("a")
        doc.addStroke(s)
        val action = InkAction.Drew(doc.pageId, s)
        doc.revert(action)
        assertEquals(0, doc.strokes.size)
        doc.reapply(action)
        assertEquals(listOf("a"), doc.strokes.map { it.id })
    }

    @Test
    fun anErasedTailStrokesOrderIsNeverHandedOutAgain() = runBlocking {
        // Draw a, b; erase b (the tail); draw c. c must NOT take b's order, or restoring b would
        // collide with it — the order is a high-water mark, not the map's last key.
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        doc.addStroke(stroke("a"))
        doc.addStroke(stroke("b"))
        val erased = doc.erase(listOf("b"))!!
        doc.addStroke(stroke("c"))
        assertEquals(2L, doc.orderOf("c"))
        doc.revert(erased)
        assertEquals(listOf("a", "b", "c"), doc.strokes.map { it.id })
        assertEquals(1L, doc.orderOf("b"))
        assertEquals(2L, doc.orderOf("c"))
    }

    @Test
    fun twoStoredRowsAtOneOrderBothSurvive() = runBlocking {
        // Never written by the pad — but a row is a row. The second is moved past the end and re-put.
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(3L to stroke("a"), 3L to stroke("b"))))
        assertEquals(listOf("a", "b"), doc.strokes.map { it.id })
        assertEquals(4L, doc.orderOf("b"))
        doc.flushUntilClean()
        assertEquals(listOf("b"), puts(fake).map { text(it.args[0]) })
        assertEquals(4L, long(puts(fake)[0].args[2]))
    }

    @Test
    fun erasedEntriesCarryOrdersAndComeBackInPlace() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(0L to stroke("a"), 1L to stroke("b"), 2L to stroke("c"), 3L to stroke("d"))))
        val action = doc.erase(listOf("b", "d"))!!
        assertEquals(listOf(1L, 3L), action.entries.map { it.order })
        assertEquals(listOf("a", "c"), doc.strokes.map { it.id })

        fake.execs.clear()
        doc.revert(action)
        // In place, not appended: the writing order is what recognition and every render read.
        assertEquals(listOf("a", "b", "c", "d"), doc.strokes.map { it.id })
        assertEquals(listOf(1L, 3L), puts(fake).map { long(it.args[2]) })

        doc.reapply(action)
        assertEquals(listOf("a", "c"), doc.strokes.map { it.id })
    }

    @Test
    fun pastedPutsItsStrokesBackAtTheOrdersTheyHeld() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(0L to stroke("mine"), 6L to stroke("a"), 7L to stroke("b"))))
        val arrived = listOf(stroke("a"), stroke("b"))
        val action = InkAction.Pasted("p1", arrived, listOf(6L, 7L))

        doc.revert(action)
        assertEquals(listOf("mine"), doc.strokes.map { it.id })

        fake.execs.clear()
        doc.reapply(action)
        assertEquals(listOf("mine", "a", "b"), doc.strokes.map { it.id })
        assertEquals(listOf(6L, 7L), puts(fake).map { long(it.args[2]) })
    }

    @Test
    fun movedTranslatesBothWays() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(1404f, 1872f, listOf(0L to stroke("a"))))
        val before = doc.strokes.single().points.first().x
        val action = doc.move(listOf("a"), 12f, -7f)!!
        doc.revert(action)
        assertEquals(before, doc.strokes.single().points.first().x, 0f)
        doc.reapply(action)
        assertEquals(before + 12f, doc.strokes.single().points.first().x, 0f)
    }

    /** A `Page` replay with ink re-creates the page and writes every stroke back at its order. */
    @Test
    fun aPageReplayWithInkRebuildsThePage() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        val ink = PageInk(800f, 1000f, listOf(2L to stroke("a"), 5L to stroke("b")))
        val action = ScratchAction.Page(
            before = listOf("p1", "p2"), beforeCurrent = "p2",
            after = listOf("p1"), afterCurrent = "p1",
            pageId = "p2", ink = ink, afterInk = null,
        )
        fake.page("p2", ink)          // it is back in the fake's picture for the reload
        fake.execs.clear()
        doc.revert(action)

        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
                "UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?",
                "DELETE FROM stroke WHERE pageId = ?",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            fake.sql(),
        )
        assertEquals(listOf(2L, 5L), puts(fake).map { long(it.args[2]) })
        assertEquals(listOf("p1", "p2"), doc.pageIds)
        assertEquals("p2", doc.pageId)
    }

    /**
     * The other side: a state with no ink never emits a `sizePage`. That matters for the lone
     * page's delete, whose redo empties a page that already knows its size — a `0 × 0` update
     * there would wipe it.
     */
    @Test
    fun aPageReplayWithoutInkNeverWritesAZeroSize() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake, ink = PageInk(800f, 1000f, listOf(0L to stroke("a"))))
        val action = doc.deleteCurrent()          // the lone page: emptied, before == after
        assertEquals(action.before, action.after)
        fake.execs.clear()
        doc.reapply(action)
        assertTrue(fake.sql().none { it.startsWith("UPDATE page SET width") })
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
                "DELETE FROM stroke WHERE pageId = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            fake.sql(),
        )
    }

    @Test
    fun aPageReplayThatRemovesThePageDeletesTheRow() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        val action = doc.insert(after = true)
        doc.flushUntilClean()
        fake.execs.clear()
        doc.revert(action)                         // undo of an insert: the page is not in `before`
        assertEquals("DELETE FROM page WHERE id = ?", fake.sql().first())
        assertEquals(action.pageId, text(fake.statements.first().args[0]))
        assertEquals(listOf("p1"), doc.pageIds)
    }

    @Test
    fun aReplayForAPageThatIsGoneIsSkipped() = runBlocking {
        val fake = FakeScratchStore()
        val doc = loaded(fake)
        val drew = InkAction.Drew("a-page-that-left", stroke("a"))
        fake.execs.clear()
        doc.revert(drew)
        assertTrue(fake.execs.isEmpty())
        assertEquals("p1", doc.pageId)
    }

    // ── The store is gone ────────────────────────────────────────────────────

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        val fake = FakeScratchStore()
        fake.failWith = { RuntimeException("binder gone") }
        val doc = docOver(fake)
        val thrown = runCatching { runBlocking { doc.load() } }.exceptionOrNull()
        assertTrue("was $thrown", thrown is StoreUnavailable)
    }
}
