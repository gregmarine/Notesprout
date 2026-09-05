package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookUnlocksTest {
    @After fun reset() = NotebookUnlocks.clear()

    @Test fun markHasForget() {
        assertFalse(NotebookUnlocks.has("a"))
        NotebookUnlocks.mark("a")
        assertTrue(NotebookUnlocks.has("a"))
        assertFalse(NotebookUnlocks.has("b"))
        NotebookUnlocks.forget("a")
        assertFalse(NotebookUnlocks.has("a"))
    }

    @Test fun clearForgetsEverything() {
        NotebookUnlocks.mark("a"); NotebookUnlocks.mark("b")
        NotebookUnlocks.clear()
        assertFalse(NotebookUnlocks.has("a")); assertFalse(NotebookUnlocks.has("b"))
    }
}
