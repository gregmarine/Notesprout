package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook's own action set (arc 11 / J1 — the ordering rules moved to `:sn-screen` with the
 * now-generic `UndoRedoStack<A>`; what stays pinned here is the *shape* of the kinds the
 * notebook records, since the replay in [NotebookActivity] switches on exactly these).
 */
class NotebookUndoTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
    )

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
        // A paste replays through the same snapshot but runs the opposite direction — same rule
        // for where it landed.
        assertEquals("N", Action.PagePasted(snap).pageId)
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
     * A lasso delete has to queue and pop exactly like anything else — and it must stay
     * *distinguishable* from an erase, which is the whole reason it is its own kind rather than a
     * reused [Action.Erased].
     */
    @Test
    fun `a lasso delete rides the stack like any other action`() {
        val s = UndoRedoStack<Action>()
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

    /**
     * A re-papering (arc 12) carries both template ids, and blank is one of them — `""` is the
     * format's answer for blank paper, so an entry that dropped it could not undo a page back to
     * blank.
     */
    @Test
    fun `a template change carries both ids, blank included`() {
        val s = UndoRedoStack<Action>()
        val toGrid = Action.TemplateChanged("p1", from = "", to = "t-grid")
        s.record(toGrid)

        val popped = s.popUndo()!!
        assertSame(toGrid, popped)
        assertEquals("p1", popped.pageId)
        assertEquals("", (popped as Action.TemplateChanged).from)
        assertEquals("t-grid", popped.to)
    }

    /**
     * A scribble (arc 14) can take ink, a heading and a link in **one** gesture, and the entry has
     * to carry all three or an undo would put back only part of what vanished. Its own kind, not a
     * reused [Action.Deleted] — a scribble is a different act to the user than a Delete tap.
     */
    @Test
    fun `a scribble erase carries all three kinds in one entry`() {
        val h = Heading("h1", "# T", 1, 0f, 0f, 10f, 10f, 0)
        val link = PageLink(
            id = "l1", payload = "p", chrome = 0,
            x = 0f, y = 0f, width = 20f, height = 20f, order = 0,
            strokes = listOf(stroke("wrapped")), headings = emptyList(),
        )
        val s = UndoRedoStack<Action>()
        val scribbled = Action.ScribbleErased(
            "p1", listOf(stroke("a"), stroke("b")), listOf(h.id), listOf(link),
        )
        s.record(scribbled)

        val popped = s.popUndo()!!
        assertSame(scribbled, popped)
        assertEquals("p1", popped.pageId)
        popped as Action.ScribbleErased
        // The strokes ride whole: an undo restores geometry, not just ids.
        assertEquals(listOf("a", "b"), popped.strokes.map { it.id })
        assertEquals(listOf("h1"), popped.headingIds)
        // The link rides as a full snapshot — restoring it has to bring its wrapped children back.
        assertEquals(listOf("wrapped"), popped.links.single().strokes.map { it.id })
    }

    /**
     * The kind matters as much as the payload: a scribble and a Delete tap replay identically but
     * must stay tellable apart, the same rule that keeps [Action.Erased] and [Action.Deleted]
     * separate. A `when` arm that folded them would lose the label a future undo hint needs.
     */
    @Test
    fun `a scribble erase is distinguishable from an erase and a delete`() {
        val strokes = listOf(stroke("a"))
        val erased: Action = Action.Erased("p", strokes)
        val deleted: Action = Action.Deleted("p", strokes)
        val scribbled: Action = Action.ScribbleErased("p", strokes)

        assertTrue(scribbled is Action.ScribbleErased)
        assertTrue(scribbled !is Action.Deleted)
        assertTrue(scribbled !is Action.Erased)
        assertTrue(erased !is Action.ScribbleErased)
        assertTrue(deleted !is Action.ScribbleErased)
    }

    /** Ink-only and content-only scribbles are both legal; the engine never reports two empties. */
    @Test
    fun `a scribble erase defaults its content lists to empty`() {
        val inkOnly = Action.ScribbleErased("p", listOf(stroke("a")))
        assertEquals(emptyList<String>(), inkOnly.headingIds)
        assertEquals(emptyList<PageLink>(), inkOnly.links)

        val contentOnly = Action.ScribbleErased("p", emptyList(), listOf("h1"))
        assertEquals(emptyList<Stroke>(), contentOnly.strokes)
        assertEquals(listOf("h1"), contentOnly.headingIds)
    }
}
