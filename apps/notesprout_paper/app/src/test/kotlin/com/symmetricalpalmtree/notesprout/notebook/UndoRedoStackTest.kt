package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesprout.notebook.UndoRedoStack.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoRedoStackTest {

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))
    private fun obj(id: String, order: Int = 0) =
        PageObject(id, "debug:box", "test", 0f, 0f, 200f, 100f, order)

    @Test
    fun objectActionsUndoInReverseOrderAndRedoForward() {
        val s = UndoRedoStack()
        val created = Action.ObjectCreated("p", obj("o1"), removedStrokes = listOf(stroke("s1")))
        val moved = Action.Moved("p", ids = listOf("s2"), dx = 5f, dy = 6f, objectIds = listOf("o1"))
        val edited = Action.ObjectEdited("p", before = obj("o1"), after = obj("o1").copy(payload = "test2", width = 220f))
        val deleted = Action.ObjectsDeleted("p", strokes = listOf(stroke("s2")), objects = listOf(obj("o1")))
        s.record(created); s.record(moved); s.record(edited); s.record(deleted)

        assertTrue(s.canUndo()); assertFalse(s.canRedo())
        assertEquals(deleted, s.popUndo()); s.pushRedo(deleted)
        assertEquals(edited, s.popUndo()); s.pushRedo(edited)
        assertEquals(moved, s.popUndo()); s.pushRedo(moved)
        assertEquals(created, s.popUndo()); s.pushRedo(created)
        assertNull(s.popUndo())
        assertTrue(s.canRedo())

        assertEquals(created, s.popRedo()); s.pushUndo(created)
        assertEquals(moved, s.popRedo()); s.pushUndo(moved)
        assertEquals(edited, s.popRedo()); s.pushUndo(edited)
        assertEquals(deleted, s.popRedo()); s.pushUndo(deleted)
        assertNull(s.popRedo())
    }

    @Test
    fun newEditClearsRedo() {
        val s = UndoRedoStack()
        s.record(Action.ObjectCreated("p", obj("o1"), emptyList()))
        val a = s.popUndo()!!; s.pushRedo(a)
        assertTrue(s.canRedo())
        s.record(Action.Drew("p", stroke("s9")))
        assertFalse(s.canRedo())
    }

    @Test
    fun movedCarriesObjectIdsAndDefaultsEmpty() {
        val legacy = Action.Moved("p", listOf("s1"), 1f, 2f)
        assertTrue(legacy.objectIds.isEmpty())
        val both = Action.Moved("p", listOf("s1"), 1f, 2f, listOf("o1"))
        assertEquals(listOf("o1"), both.objectIds)
        assertEquals("p", both.pageId)
    }

    @Test
    fun clearDropsBothSides() {
        val s = UndoRedoStack()
        s.record(Action.ObjectsDeleted("p", emptyList(), listOf(obj("o1"))))
        val a = s.popUndo()!!; s.pushRedo(a)
        s.record(Action.ObjectCreated("p", obj("o2"), emptyList()))
        s.clear()
        assertFalse(s.canUndo()); assertFalse(s.canRedo())
    }
}
