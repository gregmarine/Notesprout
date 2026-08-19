package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesprout.notebook.NotebookUndo.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notebook's action set over the generic stack (the shapes the arc-4 tests pinned). */
class NotebookUndoTest {

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))
    private fun obj(id: String, order: Int = 0) =
        PageObject(id, "debug:box", "test", 0f, 0f, 200f, 100f, order)

    @Test
    fun objectActionsRoundTripThroughTheStack() {
        val s = UndoRedoStack<Action>()
        val created = Action.ObjectCreated("p", obj("o1"), removedStrokes = listOf(stroke("s1")))
        val edited = Action.ObjectEdited("p", before = obj("o1"), after = obj("o1").copy(payload = "test2", width = 220f))
        val deleted = Action.ObjectsDeleted("p", strokes = listOf(stroke("s2")), objects = listOf(obj("o1")))
        s.record(created); s.record(edited); s.record(deleted)
        assertEquals(deleted, s.popUndo()); s.pushRedo(deleted)
        assertEquals(edited, s.popUndo()); s.pushRedo(edited)
        assertEquals(created, s.popUndo()); s.pushRedo(created)
        assertEquals(created, s.popRedo())
    }

    @Test
    fun pastedIsOneStepForTheWholePaste() {   // arc 6 / S2
        val s = UndoRedoStack<Action>()
        val pasted = Action.Pasted("p", listOf(stroke("n1"), stroke("n2")))
        s.record(Action.Drew("p", stroke("s1"))); s.record(pasted)
        assertEquals(pasted, s.popUndo())
        assertEquals("p", pasted.pageId)
        assertEquals(listOf("n1", "n2"), pasted.strokes.map { it.id })
    }

    @Test
    fun movedCarriesObjectIdsAndDefaultsEmpty() {
        val legacy = Action.Moved("p", listOf("s1"), 1f, 2f)
        assertTrue(legacy.objectIds.isEmpty())
        val both = Action.Moved("p", listOf("s1"), 1f, 2f, listOf("o1"))
        assertEquals(listOf("o1"), both.objectIds)
        assertEquals("p", both.pageId)
    }
}
