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

    // ── The byte budget and the put-back-beneath rule (arc 43 / K2) ──────

    /** A costed action set: [Act.Drew] is free (ids), [Heavy] holds pixels. */
    private data class Heavy(val id: String, val bytes: Long) : Act {
        override val pageId: String get() = "p"
    }

    private fun costed(budget: Long) =
        UndoRedoStack<Act>(cost = { (it as? Heavy)?.bytes ?: 0L }, budgetBytes = budget)

    @Test
    fun `the default stack counts nothing and never evicts`() {
        // Every screen that records ids is exactly what it was: no cost, no budget, no eviction.
        val s = UndoRedoStack<Act>()
        repeat(5) { s.record(drew("s$it")) }
        assertEquals(0L, s.undoBytes)
    }

    @Test
    fun `bytes are counted on record, pop, push and clear`() {
        val s = costed(budget = 1_000)
        s.record(Heavy("a", 100))
        s.record(drew("free"))
        s.record(Heavy("b", 250))
        assertEquals(350L, s.undoBytes)
        // A pop hands the entry to the caller and stops holding its bytes...
        val b = s.popUndo()!!
        assertEquals(100L, s.undoBytes)
        // ...and a push back counts them again — a total that did not move would drift.
        s.pushUndo(b)
        assertEquals(350L, s.undoBytes)
        s.clear()
        assertEquals(0L, s.undoBytes)
    }

    @Test
    fun `eviction drops the oldest costed entry and skips the free ones`() {
        val s = costed(budget = 250)
        s.record(Heavy("old", 100))
        s.record(drew("free"))
        s.record(Heavy("mid", 100))
        assertEquals(200L, s.undoBytes)
        // This one takes it over budget: the OLDEST costed entry goes, not the free one in between.
        s.record(Heavy("new", 100))
        assertEquals(200L, s.undoBytes)
        val left = generateSequence { s.popUndo() }.toList()
        assertEquals(listOf("new", "mid", "free"), left.map { (it as? Heavy)?.id ?: (it as Act.Drew).id })
    }

    @Test
    fun `the newest entry is never evicted even alone over budget`() {
        // A single entry over budget is the case where everything older has gone already. Dropping
        // it would leave an undo that does nothing for the mark just made, which reads as broken.
        val s = costed(budget = 10)
        s.record(Heavy("only", 5_000))
        assertTrue(s.canUndo())
        assertEquals(5_000L, s.undoBytes)
        s.record(Heavy("next", 5_000))
        // The older one went; the newest stayed, over budget and on purpose.
        assertEquals(5_000L, s.undoBytes)
        assertEquals("next", (s.popUndo() as Heavy).id)
        assertFalse(s.canUndo())
    }

    @Test
    fun `pushUndoBeneath with nothing recorded since is a plain push on top`() {
        val s = costed(budget = 10_000)
        s.record(Heavy("a", 10))
        val popped = s.popUndo()!!
        val g = s.generation
        // Nothing landed while the replay was in flight — the entry goes back where it came from.
        s.pushUndoBeneath(popped, g)
        assertEquals(10L, s.undoBytes)
        assertSame(popped, s.popUndo())
    }

    @Test
    fun `pushUndoBeneath puts the entry under what landed while the replay waited`() {
        val s = costed(budget = 10_000)
        val a = Heavy("a", 10)
        s.record(a)
        val popped = s.popUndo()!!
        val g = s.generation
        val fresh = Heavy("fresh", 20)
        s.record(fresh)                    // a mark landed mid-replay
        s.pushUndoBeneath(popped, g)
        assertEquals(30L, s.undoBytes)
        // The fresh mark is still the newest: the entry that never landed went underneath it.
        assertSame(fresh, s.popUndo())
        assertSame(popped, s.popUndo())
    }

    @Test
    fun `pushUndoBeneath goes under two marks when two landed`() {
        val s = costed(budget = 10_000)
        s.record(Heavy("a", 10))
        val popped = s.popUndo()!!
        val g = s.generation
        val first = Heavy("first", 1)
        val second = Heavy("second", 2)
        s.record(first)
        s.record(second)
        s.pushUndoBeneath(popped, g)
        assertSame(second, s.popUndo())
        assertSame(first, s.popUndo())
        assertSame(popped, s.popUndo())
    }

    @Test
    fun `pushUndoBeneath moves neither the generation nor the redo side`() {
        // Nothing new happened (the entry never landed), so nothing forward became unreachable.
        val s = costed(budget = 10_000)
        s.record(Heavy("a", 10))
        val popped = s.popUndo()!!
        s.pushRedo(drew("kept"))
        val g = s.generation
        s.pushUndoBeneath(popped, g)
        assertEquals(g, s.generation)
        assertTrue(s.canRedo())
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
