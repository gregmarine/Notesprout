package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note's copy into a freshly minted event (arc 24 / Z3) — a *this occurrence* override or a new
 * *following* series. The thing being pinned is that it is a **copy**: `note_stroke.id` is the
 * primary key, so a re-parented row would move the original series' note rather than duplicate it,
 * and the person would open the series they did not edit to find its note gone.
 */
class NoteWriteTest {

    private fun stroke(id: String, seed: Int) = Stroke(
        id = id,
        points = List(3) { StrokePoint((it + seed).toFloat(), it * 2f + seed, 0.5f, 0.25f, 0L) },
        color = if (seed % 2 == 0) Stroke.BLACK else -0x777778,
        width = 2f + seed,
        style = if (seed % 2 == 0) StrokeStyle.PEN else StrokeStyle.FOUNTAIN,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    private val entries = listOf(0L to stroke("s1", 0), 4L to stroke("s2", 1), 9L to stroke("s3", 2))

    /** Ids in the order [NoteWrite.copy] asks for them, so a test can name what it expects. */
    private fun minter(): () -> String {
        var n = 0
        return { "mint${n++}" }
    }

    @Test
    fun copyRemintsEveryStrokeId() {
        val write = NoteWrite.copy(entries, "new1", minter())
        val ids = write.statements.map { text(it.args[0]) }
        assertEquals(listOf("mint0", "mint1", "mint2"), ids)
        assertEquals("every id is its own", ids.size, ids.toSet().size)
        for (id in ids) assertTrue("$id collides with an original", id !in listOf("s1", "s2", "s3"))
        assertNotEquals(entries.map { it.second.id }, ids)
    }

    @Test
    fun copyTargetsTheNewEventAndKeepsOrdersAndContent() {
        val write = NoteWrite.copy(entries, "new1", minter())
        assertEquals(3, write.statements.size)
        for ((i, s) in write.statements.withIndex()) {
            val (order, original) = entries[i]
            assertEquals(
                "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                s.sql,
            )
            assertEquals("the copy is parented on the new event", Cell.Text("new1"), s.args[1])
            assertEquals(Cell.Integer(order), s.args[2])
            assertEquals(Cell.Integer(original.color.toLong()), s.args[3])
            assertEquals(Cell.Real(original.width.toDouble()), s.args[4])
            assertEquals(Cell.Text(original.style.name), s.args[5])
            assertArrayEquals("the geometry is the same ink", StrokeBlob.encode(original), (s.args[6] as Cell.Blob).value)
        }
    }

    @Test
    fun aCopyMintsNothingToGiveBack() {
        // The copy lands under an id this save created; its compensation is that row's own delete
        // and the cascade, so listing the strokes would be a second, redundant undo.
        assertEquals(emptyList<String>(), NoteWrite.copy(entries, "new1", minter()).mintedStrokeIds)
    }

    @Test
    fun noneIsEmpty() {
        assertEquals(emptyList<Any>(), NoteWrite.NONE.statements)
        assertEquals(emptyList<String>(), NoteWrite.NONE.mintedStrokeIds)
        assertEquals(emptyList<Any>(), NoteWrite.copy(emptyList(), "new1", minter()).statements)
    }
}
