package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapsTest {

    private val icons: (String) -> Int? = { name -> if (name == IconNames.H1) 101 else null }
    private fun leaf(id: String, label: String = "L", icon: String? = null, applies: Int = ActionApplies.INK, requires: Int = 0) =
        SelectionAction(id, label, icon, applies, requires, emptyList())

    @Test
    fun knownIconResolvesUnknownIsNull() {
        val out = ActionCaps.sanitize(listOf(leaf("a", icon = IconNames.H1), leaf("b", icon = "nope"), leaf("c", icon = null)), "P", icons)
        assertEquals(listOf(101, null, null), out.map { it.iconRes })
    }

    @Test
    fun hintIsLabelDotProviderTruncated() {
        val out = ActionCaps.sanitize(listOf(leaf("a", label = "H")), "Heading", icons)
        assertEquals("H · Heading", out[0].hint)
        val long = ActionCaps.sanitize(listOf(leaf("a", label = "abcdef")), "P".repeat(80), icons)
        assertEquals(ExtensionContract.MAX_ACTION_HINT_CHARS, long[0].hint.length)
        assertTrue(long[0].hint.startsWith("abcdef · "))
    }

    @Test
    fun labelTrimmedAndTruncated() {
        // Constructor rules cap the label at 6; ActionCaps trims surrounding space and re-caps.
        val out = ActionCaps.sanitize(listOf(leaf("a", label = "  ab  ")), "P", icons)
        assertEquals("ab", out[0].label)
    }

    @Test
    fun appliesZeroDroppedAndBitsMasked() {
        val out = ActionCaps.sanitize(listOf(leaf("a", applies = 0), leaf("b", applies = ActionApplies.OBJECT)), "P", icons)
        assertEquals(listOf("b"), out.map { it.id })
        assertEquals(ActionApplies.OBJECT, out[0].appliesTo)
    }

    @Test
    fun duplicateIdsFirstWins() {
        val out = ActionCaps.sanitize(listOf(leaf("a", label = "one"), leaf("a", label = "two")), "P", icons)
        assertEquals(1, out.size)
        assertEquals("one", out[0].label)
    }

    @Test
    fun listCapsApply() {
        val many = (0 until ExtensionContract.MAX_ACTIONS + 5).map { leaf("a$it") }
        assertEquals(ExtensionContract.MAX_ACTIONS, ActionCaps.sanitize(many, "P", icons).size)
        val subs = (0 until ExtensionContract.MAX_SUB_ACTIONS + 3).map { leaf("s$it") }
        val parent = SelectionAction("p", "P", null, ActionApplies.INK, 0, subs)
        assertEquals(ExtensionContract.MAX_SUB_ACTIONS, ActionCaps.sanitize(listOf(parent), "P", icons)[0].subActions.size)
    }

    @Test
    fun depthTwoSubActionsDropped() {
        val grandchild = leaf("g")
        val child = SelectionAction("c", "C", null, ActionApplies.INK, 0, listOf(grandchild))
        val parent = SelectionAction("p", "P", null, ActionApplies.INK, 0, listOf(child))
        val out = ActionCaps.sanitize(listOf(parent), "P", icons)
        assertEquals(1, out[0].subActions.size)
        assertTrue(out[0].subActions[0].subActions.isEmpty())
        assertTrue(out[0].isParent)
    }

    @Test
    fun requiresMasked() {
        val out = ActionCaps.sanitize(listOf(leaf("a", requires = Requires.RECOGNIZER or Requires.MARKDOWN)), "P", icons)
        assertEquals(Requires.ALL, out[0].requires)
    }

    @Test
    fun editCaps() {
        val ok = EditCaps.sanitize(EditSpec("  Edit heading  ", "x".repeat(50), "h".repeat(100), 20, false))
        assertEquals("Edit heading", ok.title)
        assertEquals(20, ok.text.length)
        assertEquals(ExtensionContract.MAX_EDIT_HINT_CHARS, ok.hint.length)
        assertEquals(20, ok.maxChars)
        val big = EditCaps.sanitize(EditSpec("T".repeat(100), "t", "", ExtensionContract.MAX_EDIT_TEXT_CHARS, true))
        assertEquals(ExtensionContract.MAX_EDIT_TITLE_CHARS, big.title.length)
        assertTrue(big.multiLine)
    }
}
