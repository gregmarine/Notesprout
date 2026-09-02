package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One page of ink and its op log (arc 23 / Y1 — the stroke-level half of the pad's document test,
 * against a statement-recording `exec` and nothing else): the writing order a restore has to land
 * back in, the coalescing, the re-flush rule and a failed flush, and both directions of every
 * [InkAction].
 */
class InkDocumentTest {

    /** A consumer's two statements, as pinned shapes. */
    private object Sql : InkDocument.StrokeSql {
        override fun putStroke(pageId: String, order: Long, stroke: Stroke) =
            Statement("PUT", stroke.id, pageId, order)
        override fun dropStroke(id: String) = Statement("DROP", id)
    }

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value
    private fun long(cell: Cell) = (cell as Cell.Integer).value

    private class Recorder {
        val execs = ArrayList<List<Statement>>()
        var failWith: (() -> Throwable)? = null
        val statements: List<Statement> get() = execs.flatten()
        fun sql() = statements.map { it.sql }
        val exec: suspend (List<Statement>) -> Unit = { s ->
            failWith?.let { throw it() }
            execs += s
        }
    }

    private fun doc(vararg ink: Pair<Long, Stroke>): InkDocument =
        InkDocument(Sql).also { it.reset("p1", ink.toList()) }

    @Test
    fun aResetPageComesBackInItsWritingOrder() {
        val d = doc(0L to stroke("a"), 4L to stroke("b"), 9L to stroke("c"))
        assertEquals("p1", d.pageId)
        assertEquals(listOf("a", "b", "c"), d.strokes.map { it.id })
        assertEquals(4L, d.orderOf("b"))
        assertFalse(d.hasUnsavedChanges)
        assertEquals(listOf(0L, 4L, 9L), d.entries().map { it.first })
    }

    @Test
    fun oneAddedStrokeFlushesAsOnePut_atTheEndOfTheOrder() = runBlocking {
        val r = Recorder()
        val d = doc(7L to stroke("old"))
        d.addStroke(stroke("a"))
        assertEquals(8L, d.orderOf("a"))
        d.flushUntilClean(exec = r.exec)
        val put = r.statements.single()
        assertEquals("PUT", put.sql)
        assertEquals("a", text(put.args[0]))
        assertEquals("p1", text(put.args[1]))
        assertEquals(8L, long(put.args[2]))
        assertFalse(d.hasUnsavedChanges)
    }

    @Test
    fun addThenEraseTheSameStrokeFlushesAsOneDrop() = runBlocking {
        val r = Recorder()
        val d = doc()
        d.addStroke(stroke("a"))
        d.erase(listOf("a"))
        d.flushUntilClean(exec = r.exec)
        assertEquals(listOf("DROP"), r.sql())
        assertEquals("a", text(r.statements.single().args[0]))
    }

    @Test
    fun moveRewritesTheRowAtTheOrderItAlreadyHeld() = runBlocking {
        val r = Recorder()
        val d = doc(3L to stroke("a"))
        val before = d.strokes.single().points.first().x
        d.move(listOf("a"), 12f, -7f)
        d.flushUntilClean(exec = r.exec)
        val put = r.statements.single()
        assertEquals(3L, long(put.args[2]))
        assertEquals(before + 12f, d.strokes.single().points.first().x, 0f)
    }

    @Test
    fun nothingToFlushRunsNoExec_butExtraDirtyRunsOne() = runBlocking {
        val r = Recorder()
        val d = doc()
        d.flushUntilClean(exec = r.exec)
        assertTrue(r.execs.isEmpty())
        var extra = true
        d.flushUntilClean(extraDirty = { extra }) { s -> extra = false; r.exec(s) }
        // One pass with an empty stroke list — the consumer's own lead rides it.
        assertEquals(listOf(emptyList<Statement>()), r.execs)
    }

    @Test
    fun aFailedFlushKeepsItsWorkAndLetsNewerEditsWin() = runBlocking {
        val r = Recorder()
        val d = doc()
        d.addStroke(stroke("a"))
        d.addStroke(stroke("b"))
        r.failWith = { SecurityException("revoked") }
        val thrown = runCatching { d.flushUntilClean(exec = r.exec) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is SecurityException)
        assertTrue(d.hasUnsavedChanges)

        d.erase(listOf("a"))
        r.failWith = null
        d.flushUntilClean(exec = r.exec)
        assertEquals(1, r.sql().count { it == "DROP" })
        assertEquals(listOf("b"), r.statements.filter { it.sql == "PUT" }.map { text(it.args[0]) })
    }

    @Test
    fun anErasedTailStrokesOrderIsNeverHandedOutAgain() {
        val d = doc()
        d.addStroke(stroke("a"))
        d.addStroke(stroke("b"))
        val erased = d.erase(listOf("b"))!!
        d.addStroke(stroke("c"))
        assertEquals(2L, d.orderOf("c"))
        d.revert(erased)
        assertEquals(listOf("a", "b", "c"), d.strokes.map { it.id })
        assertEquals(1L, d.orderOf("b"))
    }

    @Test
    fun twoStoredRowsAtOneOrderBothSurvive() = runBlocking {
        val r = Recorder()
        val d = doc(3L to stroke("a"), 3L to stroke("b"))
        assertEquals(listOf("a", "b"), d.strokes.map { it.id })
        assertEquals(4L, d.orderOf("b"))
        assertTrue(d.hasUnsavedChanges)
        d.flushUntilClean(exec = r.exec)
        assertEquals(listOf("b"), r.statements.map { text(it.args[0]) })
        assertEquals(4L, long(r.statements[0].args[2]))
    }

    @Test
    fun erasedEntriesCarryOrdersAndComeBackInPlace() {
        val d = doc(0L to stroke("a"), 1L to stroke("b"), 2L to stroke("c"), 3L to stroke("d"))
        val action = d.erase(listOf("b", "d"))!!
        assertEquals("p1", action.pageId)
        assertEquals(listOf(1L, 3L), action.entries.map { it.order })
        assertEquals(listOf("a", "c"), d.strokes.map { it.id })
        d.revert(action)
        assertEquals(listOf("a", "b", "c", "d"), d.strokes.map { it.id })
        d.reapply(action)
        assertEquals(listOf("a", "c"), d.strokes.map { it.id })
    }

    @Test
    fun drewAndPastedAndMovedReplayBothWays() {
        val d = doc(0L to stroke("mine"), 6L to stroke("a"), 7L to stroke("b"))
        val pasted = InkAction.Pasted("p1", listOf(stroke("a"), stroke("b")), listOf(6L, 7L))
        d.revert(pasted)
        assertEquals(listOf("mine"), d.strokes.map { it.id })
        d.reapply(pasted)
        assertEquals(listOf("mine", "a", "b"), d.strokes.map { it.id })
        assertEquals(6L, d.orderOf("a"))

        val s = stroke("z")
        d.addStroke(s)
        val drew = InkAction.Drew("p1", s)
        d.revert(drew)
        assertEquals(listOf("mine", "a", "b"), d.strokes.map { it.id })
        d.reapply(drew)
        assertEquals(listOf("mine", "a", "b", "z"), d.strokes.map { it.id })

        val x0 = d.strokes.first().points.first().x
        val moved = d.move(listOf("mine"), 12f, -7f)!!
        d.revert(moved)
        assertEquals(x0, d.strokes.first().points.first().x, 0f)
        d.reapply(moved)
        assertEquals(x0 + 12f, d.strokes.first().points.first().x, 0f)
    }

    @Test
    fun anEraseOfNothingOfOursIsNull() {
        val d = doc(0L to stroke("a"))
        assertEquals(null, d.erase(listOf("not-here")))
        assertEquals(null, d.erase(emptyList()))
        assertEquals(null, d.move(listOf("not-here"), 1f, 1f))
        assertFalse(d.hasUnsavedChanges)
    }
}
