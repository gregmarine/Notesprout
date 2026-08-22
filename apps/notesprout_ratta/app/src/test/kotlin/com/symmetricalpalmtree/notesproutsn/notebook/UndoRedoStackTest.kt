package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History ordering only — the stack never touches the paper or the rows. What matters here is that
 * undo is strict LIFO, that a fresh edit invalidates the redo side, and that the bound drops the
 * *oldest* entry rather than refusing the new one.
 */
class UndoRedoStackTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
    )

    private fun drew(id: String, page: String = "p") = Action.Drew(page, stroke(id))

    @Test
    fun `a fresh stack can do neither`() {
        val s = UndoRedoStack()
        assertFalse(s.canUndo())
        assertFalse(s.canRedo())
        assertNull(s.popUndo())
        assertNull(s.popRedo())
    }

    @Test
    fun `recording makes undo available`() {
        val s = UndoRedoStack()
        s.record(drew("a"))
        assertTrue(s.canUndo())
        assertFalse(s.canRedo())
    }

    @Test
    fun `undo pops newest first`() {
        val s = UndoRedoStack()
        val a = drew("a"); val b = drew("b"); val c = drew("c")
        s.record(a); s.record(b); s.record(c)
        assertSame(c, s.popUndo())
        assertSame(b, s.popUndo())
        assertSame(a, s.popUndo())
        assertFalse(s.canUndo())
    }

    @Test
    fun `undo then redo round-trips in the original order`() {
        val s = UndoRedoStack()
        val a = drew("a"); val b = drew("b")
        s.record(a); s.record(b)
        // Undo both, moving each to the redo side as the caller does.
        s.popUndo()!!.let { s.pushRedo(it) }
        s.popUndo()!!.let { s.pushRedo(it) }
        assertFalse(s.canUndo())
        assertTrue(s.canRedo())
        // Redo pops them back in the order they were originally performed.
        assertSame(a, s.popRedo()!!.also { s.pushUndo(it) })
        assertSame(b, s.popRedo()!!.also { s.pushUndo(it) })
        assertFalse(s.canRedo())
        assertSame(b, s.popUndo())
    }

    @Test
    fun `a new edit clears the redo side`() {
        val s = UndoRedoStack()
        s.record(drew("a"))
        s.pushRedo(drew("stale"))
        assertTrue(s.canRedo())
        s.record(drew("b"))
        assertFalse(s.canRedo())
        assertNull(s.popRedo())
    }

    @Test
    fun `the bound drops the oldest entry`() {
        val s = UndoRedoStack()
        repeat(120) { s.record(drew("s$it")) }
        val popped = generateSequence { s.popUndo() }.toList()
        assertEquals(100, popped.size)
        // Newest first, and the oldest 20 are gone: s119 down to s20.
        assertEquals("s119", (popped.first() as Action.Drew).stroke.id)
        assertEquals("s20", (popped.last() as Action.Drew).stroke.id)
    }

    @Test
    fun `clear empties both sides`() {
        val s = UndoRedoStack()
        s.record(drew("a"))
        s.pushRedo(drew("b"))
        s.clear()
        assertFalse(s.canUndo())
        assertFalse(s.canRedo())
    }

    @Test
    fun `a page action's pageId is where the op landed`() {
        val snap = NotebookSession.Structural(
            before = listOf("A", "B"),
            after = listOf("A", "N", "B"),
            objectIds = emptyList(),
            beforeCurrentId = "A",
            afterCurrentId = "N",
        )
        assertEquals("N", Action.Page(snap).pageId)
    }

    @Test
    fun `every action kind reports its own page`() {
        val h = Heading("h", "# T", 1, 0f, 0f, 10f, 10f, 0)
        assertEquals("p1", Action.Drew("p1", stroke("a")).pageId)
        assertEquals("p2", Action.Erased("p2", listOf(stroke("a"))).pageId)
        assertEquals("p3", Action.Moved("p3", listOf("a"), 5f, -5f).pageId)
        assertEquals("p4", Action.Deleted("p4", listOf(stroke("a"))).pageId)
        assertEquals("p5", Action.HeadingCreated("p5", h, listOf("a")).pageId)
        assertEquals("p6", Action.HeadingDeleted("p6", listOf("h")).pageId)
        assertEquals("p7", Action.HeadingTextEdited("p7", h, h.copy(text = "# U")).pageId)
        assertEquals("p8", Action.HeadingLevelChanged("p8", h, h.copy(level = 2)).pageId)
    }

    /**
     * The stack is action-agnostic, so a lasso delete has to queue and pop exactly like anything
     * else — and it must stay *distinguishable* from an erase, which is the whole reason it is its
     * own kind rather than a reused [Action.Erased].
     */
    @Test
    fun `a lasso delete rides the stack like any other action`() {
        val s = UndoRedoStack()
        val erased = Action.Erased("p", listOf(stroke("a")))
        val deleted = Action.Deleted("p", listOf(stroke("b"), stroke("c")))
        s.record(erased)
        s.record(deleted)

        val first = s.popUndo()!!
        assertSame(deleted, first)
        assertTrue(first is Action.Deleted)
        s.pushRedo(first)
        assertSame(erased, s.popUndo())

        assertSame(deleted, s.popRedo())
        // Two carried strokes, both still there: a delete undo is only as good as its geometry.
        assertEquals(listOf("b", "c"), (deleted as Action.Deleted).strokes.map { it.id })
    }

    @Test
    fun `only record moves the generation`() {
        val s = UndoRedoStack()
        val g0 = s.generation
        s.record(drew("a"))
        assertTrue(s.generation != g0)
        // Replay traffic — pop/push — must not move it, or every undo would look like an edit.
        val g1 = s.generation
        val a = s.popUndo()!!
        s.pushRedo(a)
        s.popRedo()!!.let { s.pushUndo(it) }
        s.clear()
        assertEquals(g1, s.generation)
    }

    @Test
    fun `the mid-replay protocol drops redo when an edit interleaves`() {
        // The activity's doUndo: pop, snapshot generation, replay, pushRedo only if unchanged.
        val s = UndoRedoStack()
        s.record(drew("a"))
        val a = s.popUndo()!!
        val g = s.generation
        s.record(drew("b"))                       // pen-up landed mid-replay
        if (s.generation == g) s.pushRedo(a)      // must NOT run
        assertFalse(s.canRedo())                  // record-clears-redo holds
        assertTrue(s.canUndo())                   // the fresh edit is still undoable
    }
}
