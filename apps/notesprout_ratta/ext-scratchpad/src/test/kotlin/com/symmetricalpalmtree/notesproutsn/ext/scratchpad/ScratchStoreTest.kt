package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.ink.PageInk
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScratchStore] over the statement-recording fake (arc 22 / X2): what the pad declares, what it
 * reads on the way in, and the exact statements a placement emits — including the one case a
 * transaction cannot cover on its own, a placement too big for one batch whose second batch fails.
 */
class ScratchStoreTest {

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    // ── load ─────────────────────────────────────────────────────────────────

    @Test
    fun firstRunDeclaresTheSchemaAndCreatesOnePage_inOneBatch() {
        val fake = FakeScratchStore()
        val loaded = ScratchStore(fake).load()

        assertEquals(ScratchSchema.V1, fake.schema)
        assertEquals(listOf(loaded.currentId), loaded.ids)
        // applySchema, the page read, then ONE exec holding both statements.
        assertEquals(listOf("applySchema", "query(pages)", "exec(2)"), fake.calls)
        val statements = fake.execs.single()
        assertTrue(statements[0].sql.startsWith("INSERT OR IGNORE INTO page"))
        assertEquals(loaded.currentId, text(statements[0].args[0]))
        assertEquals(Cell.Integer(0), statements[0].args[1])
        assertEquals(ScratchSql.setCurrent(loaded.currentId).sql, statements[1].sql)
        assertEquals(loaded.currentId, text(statements[1].args[0]))
    }

    @Test
    fun anExistingLibraryIsReadBack_andAnAgreeingCurrentIsNotRewritten() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.page("p2")
        fake.current = "p2"

        val loaded = ScratchStore(fake).load()
        assertEquals(listOf("p1", "p2"), loaded.ids)
        assertEquals("p2", loaded.currentId)
        assertTrue("nothing should have been written", fake.execs.isEmpty())
    }

    @Test
    fun aCurrentThatIsNotAPageIsClampedAndTheRowCorrected() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.page("p2")
        fake.current = "gone"

        val loaded = ScratchStore(fake).load()
        assertEquals("p1", loaded.currentId)
        assertEquals(listOf(ScratchSql.setCurrent("p1").sql), fake.sql())
        assertEquals("p1", text(fake.statements.single().args[0]))
    }

    @Test
    fun aMissingStateRowIsTheSameClamp() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.current = null
        assertEquals("p1", ScratchStore(fake).load().currentId)
        assertEquals(listOf(ScratchSql.setCurrent("p1").sql), fake.sql())
    }

    // ── readPage ─────────────────────────────────────────────────────────────

    @Test
    fun readPage_readsTheSizeThenTheIndexThenTheStrokes() {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(800f, 1000f, listOf(0L to stroke("a"), 5L to stroke("b", 9))))

        val ink = ScratchStore(fake).readPage("p1")
        assertEquals(800f, ink.width, 0f)
        assertEquals(1000f, ink.height, 0f)
        assertEquals(listOf(0L to "a", 5L to "b"), ink.strokes.map { it.first to it.second.id })
        assertEquals(listOf("query(size)", "query(lens)", "query(strokes)"), fake.calls)
    }

    @Test
    fun aMissingPageRowReadsAsEmptyRatherThanThrowing() {
        val fake = FakeScratchStore()
        val ink = ScratchStore(fake).readPage("never-existed")
        assertEquals(0f, ink.width, 0f)
        assertEquals(emptyList<Pair<Long, Stroke>>(), ink.strokes)
    }

    // ── structural ───────────────────────────────────────────────────────────

    @Test
    fun insertPage_createsRenumbersAndNamesTheNewPageCurrent() {
        val fake = FakeScratchStore()
        val (ids, id) = ScratchStore(fake).insertPage(listOf("p1", "p2"), "p1")
        assertEquals(listOf("p1", id, "p2"), ids)
        val statements = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            statements.map { it.sql },
        )
        assertEquals(listOf("p1", id, "p2"), statements.drop(1).take(3).map { text(it.args[1]) })
        assertEquals(listOf(0L, 1L, 2L), statements.drop(1).take(3).map { (it.args[0] as Cell.Integer).value })
        assertEquals(id, text(statements.last().args[0]))
    }

    @Test
    fun deletingAPageDropsItAndRenumbersTheRest() {
        val fake = FakeScratchStore()
        val (rest, landing) = ScratchStore(fake).deletePage(listOf("p1", "p2", "p3"), "p2")
        assertEquals(listOf("p1", "p3"), rest)
        assertEquals("p1", landing)
        val statements = fake.execs.single()
        assertEquals("DELETE FROM page WHERE id = ?", statements[0].sql)
        assertEquals("p2", text(statements[0].args[0]))
        assertEquals(listOf("p1", "p3"), statements.drop(1).dropLast(1).map { text(it.args[1]) })
        assertEquals("p1", text(statements.last().args[0]))
    }

    @Test
    fun deletingTheLonePageEmptiesItInsteadOfRemovingIt() {
        val fake = FakeScratchStore()
        val (rest, landing) = ScratchStore(fake).deletePage(listOf("p1"), "p1")
        assertEquals(listOf("p1"), rest)
        assertEquals("p1", landing)
        // No `DELETE FROM page` — that would take the row and, with the cascade, be a different act.
        assertEquals(
            listOf("DELETE FROM stroke WHERE pageId = ?", "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)"),
            fake.sql(),
        )
    }

    // ── receive ──────────────────────────────────────────────────────────────

    @Test
    fun receiveOnANewPage_isOneBatchInPlacementOrder() {
        val fake = FakeScratchStore()
        fake.page("p1")
        val received = ScratchStore(fake).receive(listOf(stroke("a"), stroke("b", 9)), 1404f, 1872f, newPage = true)

        assertTrue(received.newPage)
        assertEquals(listOf("p1"), received.pagesBefore)
        assertEquals("p1", received.currentBefore)
        assertEquals(listOf("a", "b"), received.strokeIds)
        assertNotEquals("p1", received.pageId)

        val statements = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            statements.map { it.sql },
        )
        // The new page takes the bundle's size, sits after the current one, and its strokes are
        // numbered from 0 in the order they arrived.
        assertEquals(Cell.Real(1404.0), statements[0].args[2])
        assertEquals(Cell.Real(1872.0), statements[0].args[3])
        assertEquals(Cell.Integer(1), statements[0].args[1])
        assertEquals(listOf(0L, 1L), statements.drop(3).take(2).map { (it.args[2] as Cell.Integer).value })
        assertEquals(received.pageId, text(statements.last().args[0]))
    }

    @Test
    fun receiveOnTheCurrentPage_numbersAfterTheMaximumAndInsertsNoPage() {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(800f, 1000f, listOf(4L to stroke("old"))))
        val received = ScratchStore(fake).receive(listOf(stroke("new", 7)), 1404f, 1872f, newPage = false)

        assertFalse(received.newPage)
        assertEquals("p1", received.pageId)
        val statements = fake.execs.single()
        // The page already knows its size, so it is NOT resized: it is the pad's page, not the sender's.
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            statements.map { it.sql },
        )
        assertEquals(Cell.Integer(5), statements[0].args[2])
    }

    @Test
    fun aPageWithNoSizeYetTakesTheBundles() {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk.EMPTY)
        ScratchStore(fake).receive(listOf(stroke("a")), 1404f, 1872f, newPage = false)
        val first = fake.execs.single().first()
        assertEquals("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?", first.sql)
        assertEquals(Cell.Real(1404.0), first.args[0])
        assertEquals(Cell.Real(1872.0), first.args[1])
        // Numbered from 0 on an empty page (COALESCE(MAX(...), -1) + 1).
        assertEquals(Cell.Integer(0), fake.execs.single()[1].args[2])
    }

    /**
     * A placement too big for one batch is several transactions, so the "nothing was placed" promise
     * has to be kept by hand: the new page is deleted (its strokes go with it, by the declared
     * cascade) and the positions put back, and only then is the failure reported.
     */
    @Test
    fun aNewPagePlacementThatFailsMidWayIsCompensated() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.failExecAt = 1
        val store = ScratchStore(fake, maxBatchStatements = 3)

        var thrown: Throwable? = null
        try {
            store.receive(listOf(stroke("a"), stroke("b", 9)), 1404f, 1872f, newPage = true)
        } catch (e: StoreUnavailable) {
            thrown = e
        }
        assertTrue("expected StoreUnavailable, was $thrown", thrown is StoreUnavailable)

        // exec 0 landed (the page + its renumber), exec 1 threw, then the compensation ran.
        assertEquals(2, fake.execs.size)
        val placed = fake.execs[0]
        val compensation = fake.execs[1]
        assertEquals("INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)", placed[0].sql)
        val newId = text(placed[0].args[0])
        assertEquals(
            listOf("DELETE FROM page WHERE id = ?", "UPDATE page SET position = ? WHERE id = ?"),
            compensation.map { it.sql },
        )
        assertEquals(newId, text(compensation[0].args[0]))
        assertEquals("p1", text(compensation[1].args[1]))
        assertEquals(Cell.Integer(0), compensation[1].args[0])
    }

    /** The current-page half of the same rule: each minted stroke is deleted by id, one statement
     *  each — never an `IN (…)` list, which would run into the 999-argument cap. */
    @Test
    fun aCurrentPagePlacementThatFailsMidWayDropsExactlyWhatItMinted() {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(800f, 1000f, emptyList()))
        fake.failExecAt = 1
        val store = ScratchStore(fake, maxBatchStatements = 3)

        var thrown: Throwable? = null
        try {
            store.receive(listOf(stroke("a"), stroke("b", 9), stroke("c", 20)), 1404f, 1872f, newPage = false)
        } catch (e: StoreUnavailable) {
            thrown = e
        }
        assertTrue("expected StoreUnavailable", thrown is StoreUnavailable)
        val compensation = fake.execs.last()
        assertEquals(List(3) { "DELETE FROM stroke WHERE id = ?" }, compensation.map { it.sql })
        assertEquals(listOf("a", "b", "c"), compensation.map { text(it.args[0]) })
    }

    @Test
    fun aFailureOnTheVeryFirstBatchIsTheTransactionsOwnPromise() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.failExecAt = 0
        var thrown: Throwable? = null
        try {
            ScratchStore(fake).receive(listOf(stroke("a")), 1404f, 1872f, newPage = true)
        } catch (e: StoreUnavailable) {
            thrown = e
        }
        assertTrue(thrown is StoreUnavailable)
        // Nothing landed, so nothing is compensated — the rollback already said so.
        assertTrue(fake.execs.isEmpty())
    }

    // ── the store is gone ────────────────────────────────────────────────────

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        for (failure in listOf(SecurityException("revoked"), IllegalArgumentException("refused"), RuntimeException("binder gone"))) {
            val fake = FakeScratchStore()
            fake.failWith = { failure }
            var thrown: Throwable? = null
            try {
                ScratchStore(fake).load()
            } catch (e: StoreUnavailable) {
                thrown = e
            }
            assertTrue("was $thrown for $failure", thrown is StoreUnavailable)
        }
    }
}
