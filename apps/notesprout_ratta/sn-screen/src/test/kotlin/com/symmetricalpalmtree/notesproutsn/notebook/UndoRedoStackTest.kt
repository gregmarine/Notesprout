package com.symmetricalpalmtree.notesproutsn.notebook

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
 *
 * Typed on a **test-local** action set (arc 11 / J1): the stack is generic now, and the point of
 * these tests is that it works for any screen's actions. The notebook's own kinds are exercised
 * where they live, in `:app`'s `NotebookUndoTest`.
 */
class UndoRedoStackTest {

    private sealed interface Act {
        val pageId: String

        data class Drew(override val pageId: String, val id: String) : Act
        data class Erased(override val pageId: String, val ids: List<String>) : Act
    }

    private fun drew(id: String, page: String = "p") = Act.Drew(page, id)

    @Test
    fun `a fresh stack can do neither`() {
        val s = UndoRedoStack<Act>()
        assertFalse(s.canUndo())
        assertFalse(s.canRedo())
        assertNull(s.popUndo())
        assertNull(s.popRedo())
    }

    @Test
    fun `recording makes undo available`() {
        val s = UndoRedoStack<Act>()
        s.record(drew("a"))
        assertTrue(s.canUndo())
        assertFalse(s.canRedo())
    }

    @Test
    fun `undo pops newest first`() {
        val s = UndoRedoStack<Act>()
        val a = drew("a"); val b = drew("b"); val c = drew("c")
        s.record(a); s.record(b); s.record(c)
        assertSame(c, s.popUndo())
        assertSame(b, s.popUndo())
        assertSame(a, s.popUndo())
        assertFalse(s.canUndo())
    }

    @Test
    fun `undo then redo round-trips in the original order`() {
        val s = UndoRedoStack<Act>()
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
        val s = UndoRedoStack<Act>()
        s.record(drew("a"))
        s.pushRedo(drew("stale"))
        assertTrue(s.canRedo())
        s.record(drew("b"))
        assertFalse(s.canRedo())
        assertNull(s.popRedo())
    }

    @Test
    fun `the bound drops the oldest entry`() {
        val s = UndoRedoStack<Act>()
        repeat(120) { s.record(drew("s$it")) }
        val popped = generateSequence { s.popUndo() }.toList()
        assertEquals(100, popped.size)
        // Newest first, and the oldest 20 are gone: s119 down to s20.
        assertEquals("s119", (popped.first() as Act.Drew).id)
        assertEquals("s20", (popped.last() as Act.Drew).id)
    }

    @Test
    fun `clear empties both sides`() {
        val s = UndoRedoStack<Act>()
        s.record(drew("a"))
        s.pushRedo(drew("b"))
        s.clear()
        assertFalse(s.canUndo())
        assertFalse(s.canRedo())
    }

    /**
     * The stack is action-agnostic, so one kind has to queue and pop exactly like any other — and
     * each must stay *distinguishable* on the way back out, which is the whole reason a screen's
     * acts are separate kinds rather than one reused entry.
     */
    @Test
    fun `two kinds ride the stack side by side`() {
        val s = UndoRedoStack<Act>()
        val erased = Act.Erased("p", listOf("a"))
        val deleted = Act.Erased("p", listOf("b", "c"))
        s.record(erased)
        s.record(deleted)

        val first = s.popUndo()!!
        assertSame(deleted, first)
        assertTrue(first is Act.Erased)
        s.pushRedo(first)
        assertSame(erased, s.popUndo())

        assertSame(deleted, s.popRedo())
        // Two carried ids, both still there: an undo is only as good as what it carries.
        assertEquals(listOf("b", "c"), (deleted as Act.Erased).ids)
    }

    @Test
    fun `only record moves the generation`() {
        val s = UndoRedoStack<Act>()
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
        val s = UndoRedoStack<Act>()
        s.record(drew("a"))
        val a = s.popUndo()!!
        val g = s.generation
        s.record(drew("b"))                       // pen-up landed mid-replay
        if (s.generation == g) s.pushRedo(a)      // must NOT run
        assertFalse(s.canRedo())                  // record-clears-redo holds
        assertTrue(s.canUndo())                   // the fresh edit is still undoable
    }
}
