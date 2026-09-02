package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [CalendarStore] over the statement-recording fake: opening writes nothing, the bookmark, the page read, the placement. */
class CalendarStoreTest {

    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
    private val dayPm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1)

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    @Test
    fun openDeclaresTheSchemaReadsTheBookmark_andWritesNothing() {
        val fake = FakeCalendarStore()
        assertNull(CalendarStore(fake).open())
        assertEquals(CalendarSchema.V1, fake.schema)
        assertEquals(listOf("applySchema", "query(state)"), fake.calls)
        assertTrue(fake.execs.isEmpty())

        fake.state["lastView"] = "2"; fake.state["lastDate"] = "2026-09-01"; fake.state["lastHalf"] = "1"
        val at = CalendarStore(fake).open()!!
        assertEquals(2, at.kind)
        assertEquals(LocalDate.of(2026, 9, 1), at.date)
        assertEquals(1, at.half)
        assertEquals(dayPm, at.target)
    }

    @Test
    fun aBadBookmarkReadsAsNone() {
        for (bad in listOf(
            mapOf("lastView" to "0", "lastDate" to "2026-09-15", "lastHalf" to "0"),   // not a month start
            mapOf("lastView" to "1", "lastDate" to "2026-09-01", "lastHalf" to "0"),   // not a Sunday
            mapOf("lastView" to "0", "lastDate" to "2026-09-01", "lastHalf" to "1"),   // a half on a month
            mapOf("lastView" to "7", "lastDate" to "2026-09-01", "lastHalf" to "0"),   // unknown kind
            mapOf("lastView" to "0", "lastDate" to "yesterday", "lastHalf" to "0"),
            mapOf("lastView" to "0", "lastDate" to "2026-09-01"),                      // a row missing
        )) {
            val fake = FakeCalendarStore()
            fake.state.putAll(bad)
            assertNull("$bad", CalendarStore(fake).open())
        }
    }

    @Test
    fun readingAPageThatDoesNotExistWritesNothing_andSaysSo() {
        val fake = FakeCalendarStore()
        val stored = CalendarStore(fake).readPage(month)
        assertNull(stored.periodId)
        assertNull(stored.pageId)
        assertEquals(0f, stored.width, 0f)
        assertTrue(stored.strokes.isEmpty())
        assertEquals(listOf("query(page)", "query(period)"), fake.calls)
        assertTrue(fake.execs.isEmpty())
    }

    @Test
    fun readingTheOtherHalfOfADayFindsThePeriodButNoPage() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_DAY, "2026-09-01")
        fake.page("am", "per", 0, 1404f, 1872f)
        val stored = CalendarStore(fake).readPage(dayPm)
        assertEquals("per", stored.periodId)
        assertNull(stored.pageId)
    }

    @Test
    fun readingAnExistingPageIsTheJoinThenTheStrokes() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 800f, 1000f, listOf(0L to stroke("a"), 5L to stroke("b", 9)))
        val stored = CalendarStore(fake).readPage(month)
        assertEquals("per", stored.periodId)
        assertEquals("pg", stored.pageId)
        assertEquals(800f, stored.width, 0f)
        assertEquals(listOf(0L to "a", 5L to "b"), stored.strokes.map { it.first to it.second.id })
        assertEquals(listOf("query(page)", "query(lens)", "query(strokes)"), fake.calls)
    }

    @Test
    fun saveStateIsOneBatchOfThreeRows() {
        val fake = FakeCalendarStore()
        CalendarStore(fake).saveState(dayPm)
        val batch = fake.execs.single()
        assertEquals(3, batch.size)
        assertEquals(listOf("lastView" to "2", "lastDate" to "2026-09-01", "lastHalf" to "1"), batch.map { text(it.args[0]) to text(it.args[1]) })
    }

    @Test
    fun mintRowsIsPeriodThenPage_bothOrIgnore() {
        val fake = FakeCalendarStore()
        val rows = CalendarStore(fake).mintRows(dayPm, "per", "pg", 1404f, 1872f, 5L)
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)",
                "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?, (SELECT id FROM period WHERE kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            ),
            rows.map { it.sql },
        )
        assertEquals(Cell.Integer(1), rows[1].args[3])   // the PM half
    }

    @Test
    fun receiveOnAPageWithNoRows_mintsThemAtZeroSizeInOneBatch() {
        val fake = FakeCalendarStore()
        val received = CalendarStore(fake).receive(listOf(stroke("a"), stroke("b", 9)), month)
        assertTrue(received.mintedPage)
        assertEquals(listOf("a", "b"), received.strokeIds)
        val batch = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)",
                "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?, (SELECT id FROM period WHERE kind = ? AND date = ?), ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        // 0 × 0: the screen's first showing gives it the surface's size, not the sender's.
        assertEquals(Cell.Real(0.0), batch[1].args[4])
        assertEquals(Cell.Real(0.0), batch[1].args[5])
        assertEquals(received.pageId, text(batch[1].args[0]))
        assertEquals(received.pageId, text(batch[2].args[1]))
        assertEquals(listOf(0L, 1L), batch.drop(2).take(2).map { (it.args[2] as Cell.Integer).value })
    }

    @Test
    fun receiveOnAnExistingPage_numbersAfterTheMaximumAndMintsNothing() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 1404f, 1872f, listOf(4L to stroke("old")))
        val received = CalendarStore(fake).receive(listOf(stroke("new", 7)), month)
        assertFalse(received.mintedPage)
        assertEquals("pg", received.pageId)
        val batch = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        assertEquals(Cell.Integer(5), batch[0].args[2])
        // The placement reads the page's header and its max order — never its ink (a page holding
        // megabytes must not cost a full stroke read inside the host's placement budget).
        assertTrue(fake.queries.none { it.sql.contains("blob") || it.sql.contains("LENGTH(") })
    }

    @Test
    fun aPlacementThatFailsMidWayDropsExactlyWhatItMinted() {
        val fake = FakeCalendarStore()
        fake.failExecAt = 1
        val store = CalendarStore(fake, maxBatchStatements = 3)
        val thrown = runCatching { store.receive(listOf(stroke("a"), stroke("b", 9), stroke("c", 20)), month) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is StoreUnavailable)
        val compensation = fake.execs.last()
        assertEquals(List(3) { "DELETE FROM stroke WHERE id = ?" }, compensation.map { it.sql })
        assertEquals(listOf("a", "b", "c"), compensation.map { text(it.args[0]) })
    }

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        for (failure in listOf(SecurityException("revoked"), IllegalArgumentException("refused"), RuntimeException("binder gone"))) {
            val fake = FakeCalendarStore()
            fake.failWith = { failure }
            val thrown = runCatching { CalendarStore(fake).open() }.exceptionOrNull()
            assertTrue("was $thrown for $failure", thrown is StoreUnavailable)
        }
    }
}
