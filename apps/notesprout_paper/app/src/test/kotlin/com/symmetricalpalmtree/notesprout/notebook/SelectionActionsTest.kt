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
    fun coreActionsFilteredByAppliesTo() {   // arc 6 / S2: `scratch` (INK) shows for ink only; Delete (ALL) everywhere
        val scratch = a(SelectionActions.CORE_SCRATCH_ID, ActionApplies.INK)
        val core = listOf(delete, scratch)
        assertEquals(listOf("delete", "scratch", "h", "ink"), SelectionActions.merge(core, listOf(heading, other), Shape.Ink).map { it.action.id })
        assertEquals(listOf("delete", "obj"), SelectionActions.merge(core, listOf(heading, other), Shape.OneObject("com.x.text", "text")).map { it.action.id })
        assertEquals(listOf("delete"), SelectionActions.merge(core, listOf(heading, other), Shape.Mixed).map { it.action.id })
        assertEquals(listOf(null, null, "com.x.heading", "com.x.text"), SelectionActions.merge(core, listOf(heading, other), Shape.Ink).map { it.providerKey })
    }

    @Test
    fun parentFilteredThroughLeaves() {
        val p = Contribution("k", "K", setOf("t"), listOf(a("p", ActionApplies.ALL, listOf(a("only-obj", ActionApplies.OBJECT)))))
        assertTrue(SelectionActions.merge(emptyList(), listOf(p), Shape.Ink).isEmpty())
        val obj = SelectionActions.merge(emptyList(), listOf(p), Shape.OneObject("k", "t"))
        assertEquals(1, obj.size)
        assertEquals(listOf("only-obj"), obj[0].action.subActions.map { it.id })
    }

    // ── Links (arc 7 / L1) ──────────────────────────────────────────────────

    @Test
    fun shapeOfLinkClassification() {
        assertEquals(Shape.OneLink("l1"), SelectionActions.shapeOf(0, emptyList(), listOf("l1")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(2, emptyList(), listOf("l1")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(0, listOf("com.x.heading:heading"), listOf("l1")))
        assertEquals(Shape.Mixed, SelectionActions.shapeOf(0, emptyList(), listOf("l1", "l2")))
        // The legacy two-arg call (no linkIds) still classifies exactly as before the arc-7 param.
        assertEquals(Shape.Ink, SelectionActions.shapeOf(3, emptyList()))
        assertEquals(Shape.OneObject("com.x.heading", "heading"), SelectionActions.shapeOf(0, listOf("com.x.heading:heading")))
    }

    @Test
    fun mergeOneLinkShowsCoreAllActionsOnly() {
        val scratch = a(SelectionActions.CORE_SCRATCH_ID, ActionApplies.INK)
        val linkEdit = a(SelectionActions.CORE_LINK_EDIT_ID, ActionApplies.ALL)
        val core = listOf(delete, scratch, linkEdit)
        val allProvider = Contribution("com.x.all", "All", setOf("t"), listOf(a("everything", ActionApplies.ALL)))
        val items = SelectionActions.merge(core, listOf(heading, other, allProvider), Shape.OneLink("l1"))
        assertEquals(listOf("delete", "link_edit"), items.map { it.action.id })
        assertTrue(items.all { it.providerKey == null })
    }

    @Test
    fun mergeMixedFromLinksStillCoreAllOnly() {   // guard-rail: a link-bearing Mixed shape behaves exactly like any other Mixed
        val scratch = a(SelectionActions.CORE_SCRATCH_ID, ActionApplies.INK)
        val core = listOf(delete, scratch)
        val shape = SelectionActions.shapeOf(0, emptyList(), listOf("l1", "l2"))
        val items = SelectionActions.merge(core, listOf(heading, other), shape)
        assertEquals(listOf("delete"), items.map { it.action.id })
    }

    @Test
    fun linkConstantValues() {   // persisted nowhere but the toolbar switches on these — pin them
        assertEquals("link", SelectionActions.CORE_LINK_ID)
        assertEquals("link_edit", SelectionActions.CORE_LINK_EDIT_ID)
        assertEquals("link_unlink", SelectionActions.CORE_LINK_UNLINK_ID)
    }
}
