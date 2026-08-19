package com.symmetricalpalmtree.notesprout.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic stack (arc 6 / S0), typed on a test-local action set — the ordering rules only. */
class UndoRedoStackTest {

    private sealed interface Act {
        data class Drew(val id: String) : Act
        data class Erased(val ids: List<String>) : Act
        data class Moved(val ids: List<String>, val dx: Float) : Act
    }

    @Test
    fun undoInReverseOrderAndRedoForward() {
        val s = UndoRedoStack<Act>()
        val a = Act.Drew("s1"); val b = Act.Moved(listOf("s1"), 5f); val c = Act.Erased(listOf("s1"))
        s.record(a); s.record(b); s.record(c)

        assertTrue(s.canUndo()); assertFalse(s.canRedo())
        assertEquals(c, s.popUndo()); s.pushRedo(c)
        assertEquals(b, s.popUndo()); s.pushRedo(b)
        assertEquals(a, s.popUndo()); s.pushRedo(a)
        assertNull(s.popUndo())
        assertTrue(s.canRedo())

        assertEquals(a, s.popRedo()); s.pushUndo(a)
        assertEquals(b, s.popRedo()); s.pushUndo(b)
        assertEquals(c, s.popRedo()); s.pushUndo(c)
        assertNull(s.popRedo())
    }

    @Test
    fun newEditClearsRedo() {
        val s = UndoRedoStack<Act>()
        s.record(Act.Drew("s1"))
        val a = s.popUndo()!!; s.pushRedo(a)
        assertTrue(s.canRedo())
        s.record(Act.Drew("s9"))
        assertFalse(s.canRedo())
    }

    @Test
    fun boundedAtMaxOldestDropped() {
        val s = UndoRedoStack<Act>()
        for (i in 0 until 150) s.record(Act.Drew("s$i"))
        var n = 0
        var last: Act? = null
        while (true) { val a = s.popUndo() ?: break; n++; last = a }
        assertEquals(100, n)
        assertEquals(Act.Drew("s50"), last)
    }

    @Test
    fun clearDropsBothSides() {
        val s = UndoRedoStack<Act>()
        s.record(Act.Erased(listOf("s1")))
        val a = s.popUndo()!!; s.pushRedo(a)
        s.record(Act.Drew("s2"))
        s.clear()
        assertFalse(s.canUndo()); assertFalse(s.canRedo())
    }
}
