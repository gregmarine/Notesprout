package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's page over the statement-recording fake: **opening writes only the bookmark**, the
 * first stroke's batch is `period` + `page` + the stroke (+ `updatedAt`) in one exec, a page that
 * exists is never re-minted, an undo before the debounce mints nothing, and a replay on another page
 * navigates there first.
 */
class CalendarDocumentTest {

    private val surface = 1404f to 1872f
    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
    private val nextMonth = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-10-01", 0)

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value
    private fun doc(fake: FakeCalendarStore) = CalendarDocument(CalendarStore(fake)) { surface }
    private fun nonState(fake: FakeCalendarStore) = fake.statements.filter { !it.sql.startsWith("INSERT OR REPLACE INTO state") }

    @Test
    fun showingAnEmptyMonthWritesOnlyTheBookmark() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        assertEquals(month, d.target)
        assertEquals(1404f, d.pageWidth, 0f)
        assertEquals(0, d.strokes.size)
        assertFalse(d.hasUnsavedChanges)
        assertEquals(listOf("query(page)", "query(period)", "exec(3)"), fake.calls)
        assertTrue(nonState(fake).isEmpty())
        // Browsing on: still only bookmarks.
        d.show(nextMonth)
        assertTrue(nonState(fake).isEmpty())
        assertEquals(2, fake.execs.size)
    }

    @Test
    fun theFirstStrokeMintsPeriodAndPageAheadOfItself_inOneBatch() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        d.addStroke(stroke("a"))
        assertTrue(d.hasUnsavedChanges)
        d.flushUntilClean()
        val batch = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)",
                "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?, (SELECT id FROM period WHERE kind = ? AND date = ?), ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        assertEquals(Cell.Integer(0), batch[0].args[1])
        assertEquals(Cell.Text("2026-09-01"), batch[0].args[2])
        // The page takes the surface's size, and the stroke lands under the page id just minted.
        assertEquals(Cell.Real(1404.0), batch[1].args[4])
        assertEquals(Cell.Real(1872.0), batch[1].args[5])
        assertEquals(d.pageId, text(batch[1].args[0]))
        assertEquals(d.pageId, text(batch[2].args[1]))
        assertEquals(Cell.Integer(0), batch[2].args[2])
        assertFalse(d.hasUnsavedChanges)

        // The second stroke does not mint again.
        fake.execs.clear()
        d.addStroke(stroke("b"))
        d.flushUntilClean()
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            fake.execs.single().map { it.sql },
        )
    }

    @Test
    fun anExistingPageIsNeverReMinted_andKeepsItsOwnSize() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 800f, 1000f, listOf(3L to stroke("old")))
        val d = doc(fake)
        d.show(month)
        assertEquals("pg", d.pageId)
        assertEquals(800f, d.pageWidth, 0f)
        assertEquals(listOf("old"), d.strokes.map { it.id })
        fake.execs.clear()
        d.addStroke(stroke("a"))
        assertEquals(4L, d.orderOf("a"))
        d.flushUntilClean()
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            fake.execs.single().map { it.sql },
        )
        assertEquals("pg", text(fake.execs.single()[0].args[1]))
    }

    @Test
    fun theOtherHalfOfADayJoinsTheExistingPeriod() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_DAY, "2026-09-01")
        fake.page("am", "per", 0, 1404f, 1872f)
        val d = doc(fake)
        d.show(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1))
        fake.execs.clear()
        d.addStroke(stroke("a"))
        d.flushUntilClean()
        val batch = fake.execs.single()
        // The period INSERT is still sent (OR IGNORE — it lands on the UNIQUE and does nothing); the
        // page resolves its periodId from (kind, date) inside the statement, never from the id here.
        assertEquals("per", text(batch[0].args[0]))
        assertEquals(Cell.Integer(1), batch[1].args[3])
    }

    @Test
    fun aZeroSizePageLearnsTheSurfaceOnceAndOnlyOnce() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 0f, 0f, listOf(0L to stroke("placed")))   // minted by a placement
        val d = doc(fake)
        d.show(month)
        assertEquals(1404f, d.pageWidth, 0f)
        assertTrue(d.hasUnsavedChanges)
        fake.execs.clear()
        d.flushUntilClean()
        assertEquals(listOf("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?"), fake.sql())
        fake.execs.clear()
        d.flushUntilClean()
        assertTrue(fake.execs.isEmpty())
    }

    @Test
    fun aStrokeDrawnAndUndoneBeforeTheDebounceMintsNothing() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        val s = stroke("a")
        d.addStroke(s)
        assertTrue(d.revert(InkAction.Drew(d.pageId, s)))
        // One DELETE for a row that never landed, no period, no page, no touch.
        assertEquals(listOf("DELETE FROM stroke WHERE id = ?"), fake.sql())
        // And the next real stroke still mints.
        fake.execs.clear()
        d.addStroke(stroke("b"))
        d.flushUntilClean()
        assertEquals("INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)", fake.execs.single()[0].sql)
    }

    @Test
    fun leavingAPageFlushesItAfterReadingTheNext() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.calls.clear(); fake.execs.clear()
        d.addStroke(stroke("a"))
        d.show(nextMonth)
        // The target's two reads first; then the departing page's flush (mint + stroke + touch);
        // then the bookmark.
        assertEquals(listOf("query(page)", "query(period)", "exec(4)", "exec(3)"), fake.calls)
        assertEquals(nextMonth, d.target)
        assertEquals(0, d.strokes.size)
    }

    @Test
    fun aReplayOnAnotherPageNavigatesThereFirst() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        val s = stroke("a")
        d.addStroke(s)
        val drew = InkAction.Drew(d.pageId, s)
        d.flushUntilClean()
        // The fake never applies writes, so put the page into its picture as the store now holds it.
        val minted = fake.execs.first { it[0].sql.startsWith("INSERT OR IGNORE INTO period") }
        fake.period(text(minted[0].args[0]), CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page(text(minted[1].args[0]), text(minted[0].args[0]), 0, 1404f, 1872f, listOf(0L to s))
        d.show(nextMonth)
        fake.execs.clear()

        assertTrue(d.revert(drew))
        assertEquals(month, d.target)
        assertEquals(0, d.strokes.size)
        assertTrue(fake.sql().contains("DELETE FROM stroke WHERE id = ?"))

        assertTrue(d.reapply(drew))
        assertEquals(listOf("a"), d.strokes.map { it.id })
    }

    @Test
    fun aReplayForAPageNeverShownIsSkipped() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        assertFalse(d.revert(InkAction.Drew("never-shown", stroke("a"))))
        assertTrue(fake.execs.isEmpty())
        assertEquals(month, d.target)
    }
}
