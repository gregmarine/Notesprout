package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure `require`s behind [SelectionAction] / [EditSpec] + the H2 constants (no Parcel on the JVM). */
class SelectionActionTest {

    @Test
    fun acceptsWellFormedAction() {
        SelectionAction.requireValid("heading", "H", ActionApplies.INK or ActionApplies.OBJECT, Requires.RECOGNIZER or Requires.MARKDOWN)
        SelectionAction.requireValid("h-1.x_Y", "H1", ActionApplies.INK, 0)
        SelectionAction.requireValid("a".repeat(ExtensionContract.MAX_ACTION_ID_CHARS), "abcdef", 0, 0)
    }

    @Test
    fun rejectsBadIds() {
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("", "H", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("has space", "H", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("colon:no", "H", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction.requireValid("a".repeat(ExtensionContract.MAX_ACTION_ID_CHARS + 1), "H", 1, 0)
        }
    }

    @Test
    fun rejectsBadLabels() {
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "   ", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "toolong", 1, 0) }
    }

    @Test
    fun rejectsUnknownBits() {
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "H", 4, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "H", -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionAction.requireValid("id", "H", 1, 4) }
    }

    @Test
    fun editSpecRequires() {
        EditSpec.requireValid("Edit heading", 500)
        EditSpec.requireValid("t", ExtensionContract.MAX_EDIT_TEXT_CHARS)
        assertThrows(IllegalArgumentException::class.java) { EditSpec.requireValid(" ", 500) }
        assertThrows(IllegalArgumentException::class.java) { EditSpec.requireValid("t", 0) }
        assertThrows(IllegalArgumentException::class.java) { EditSpec.requireValid("t", ExtensionContract.MAX_EDIT_TEXT_CHARS + 1) }
    }

    @Test
    fun contractConstants() {
        assertEquals(16, ExtensionContract.MAX_ACTIONS)
        assertEquals(16, ExtensionContract.MAX_SUB_ACTIONS)
        assertEquals(32, ExtensionContract.MAX_ACTION_ID_CHARS)
        assertEquals(6, ExtensionContract.MAX_ACTION_LABEL_CHARS)
        assertEquals(40, ExtensionContract.MAX_ACTION_HINT_CHARS)
        assertEquals(40, ExtensionContract.MAX_EDIT_TITLE_CHARS)
        assertEquals(60, ExtensionContract.MAX_EDIT_HINT_CHARS)
        assertEquals(4_000, ExtensionContract.MAX_EDIT_TEXT_CHARS)
        assertEquals(1, ActionApplies.INK); assertEquals(2, ActionApplies.OBJECT)
        assertEquals(1, Requires.RECOGNIZER); assertEquals(2, Requires.MARKDOWN)
        assertEquals(listOf("heading", "h-1", "h-2", "h-3", "h-4", "h-5", "h-6", "text", "edit", "x", "check", "plus", "trash", "list", "sketching"), IconNames.ALL)
    }
}
