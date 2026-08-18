package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.notebook.SelectionActions.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {

    private fun a(id: String, applies: Int, subs: List<ToolbarAction> = emptyList()) =
        ToolbarAction(id, id.take(6), null, id, applies, 0, subs)

    private val delete = a(SelectionActions.CORE_DELETE_ID, ActionApplies.ALL)
    private val heading = Contribution(
        "com.x.heading", "Heading", setOf("heading"),
        listOf(a("h", ActionApplies.ALL, listOf(a("h1", ActionApplies.ALL), a("h2", ActionApplies.ALL)))),
    )
    private val other = Contribution(
        "com.x.text", "Text", setOf("text"),
        listOf(a("ink", ActionApplies.INK), a("obj", ActionApplies.OBJECT)),
    )

    @Test
    fun shapeClassification() {
        assertEquals(Shape.Ink, SelectionActions.shapeOf(3, emptyList()))
        assertEquals(Shape.OneObject("com.x.heading", "heading"), SelectionActions.shapeOf(0, listOf("com.x.heading:heading")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(1, listOf("com.x.heading:heading")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(0, listOf("a:b", "a:b")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(0, listOf("no-colon")))
    }

    @Test
    fun deleteFirstThenProvidersInOrder() {
        val items = SelectionActions.merge(listOf(delete), listOf(heading, other), Shape.Ink)
        assertEquals(listOf(null, "com.x.heading", "com.x.text"), items.map { it.providerKey })
        assertEquals(listOf("delete", "h", "ink"), items.map { it.action.id })
    }

    @Test
    fun objectShowsOnlyOwningProviderObjectActions() {
        val items = SelectionActions.merge(listOf(delete), listOf(heading, other), Shape.OneObject("com.x.text", "text"))
        assertEquals(listOf("delete", "obj"), items.map { it.action.id })
        val none = SelectionActions.merge(listOf(delete), listOf(heading, other), Shape.OneObject("com.x.text", "unknown-type"))
        assertEquals(listOf("delete"), none.map { it.action.id })
    }

    @Test
    fun mixedIsCoreOnly() {
        val items = SelectionActions.merge(listOf(delete), listOf(heading, other), Shape.Mixed)
        assertEquals(listOf("delete"), items.map { it.action.id })
    }

    @Test
    fun parentFilteredThroughLeaves() {
        val p = Contribution("k", "K", setOf("t"), listOf(a("p", ActionApplies.ALL, listOf(a("only-obj", ActionApplies.OBJECT)))))
        assertTrue(SelectionActions.merge(emptyList(), listOf(p), Shape.Ink).isEmpty())
        val obj = SelectionActions.merge(emptyList(), listOf(p), Shape.OneObject("k", "t"))
        assertEquals(1, obj.size)
        assertEquals(listOf("only-obj"), obj[0].action.subActions.map { it.id })
    }
}
