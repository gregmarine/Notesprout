package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The index and set math behind insert / delete / reconcile. The interesting cases are the edges —
 * deleting the first or last page, and the two diffs an undo/redo of a structural edit needs to be
 * exact mirrors of each other.
 */
class PageMathTest {

    // ── indexAfterDelete ─────────────────────────────────────────────────────

    @Test fun `deleting a middle page lands on the previous one`() {
        // [A,B,C] delete B (1) → 0 (A)
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 1, oldSize = 3))
    }

    @Test fun `deleting the first page lands on the new first`() {
        // [A,B,C] delete A (0) → 0, which is now B
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 0, oldSize = 3))
    }

    @Test fun `deleting the last page lands on the new last`() {
        // [A,B,C] delete C (2) → 1 (B), the last of the two remaining
        assertEquals(1, PageMath.indexAfterDelete(deletedIndex = 2, oldSize = 3))
    }

    @Test fun `two pages, deleting the second lands on the first`() {
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 1, oldSize = 2))
    }

    @Test fun `two pages, deleting the first lands on the survivor`() {
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 0, oldSize = 2))
    }

    // ── insertPosition ───────────────────────────────────────────────────────

    @Test fun `insert after takes the next slot`() {
        assertEquals(3, PageMath.insertPosition(currentIndex = 2, after = true))
    }

    @Test fun `insert before takes the current slot`() {
        assertEquals(2, PageMath.insertPosition(currentIndex = 2, after = false))
    }

    // ── toRestore / toDelete ─────────────────────────────────────────────────

    @Test fun `toRestore names the pages missing from the live set`() {
        // undo of a delete: B must come back, A and C are already alive
        assertEquals(listOf("B"), PageMath.toRestore(setOf("A", "C"), listOf("A", "B", "C")))
    }

    @Test fun `toRestore is empty when the target is already alive`() {
        assertEquals(emptyList<String>(), PageMath.toRestore(setOf("A", "B"), listOf("A", "B")))
    }

    @Test fun `toDelete names the live pages the target doesn't want`() {
        // undo of an insert: N is alive and must go
        assertEquals(listOf("N"), PageMath.toDelete(listOf("A", "N", "B"), listOf("A", "B")))
    }

    @Test fun `toDelete handles the only-page replacement`() {
        // redo of deleting the only page: A goes, its blank replacement N stays
        assertEquals(listOf("A"), PageMath.toDelete(listOf("A"), listOf("N")))
    }

    // ── Symmetry: the property undo/redo of a page op relies on ──────────────

    @Test fun `insert undo and redo diffs are exact mirrors`() {
        val before = listOf("A", "B")
        val after = listOf("A", "N", "B")
        // redo: reach `after` from `before`
        assertEquals(listOf("N"), PageMath.toRestore(before.toSet(), after))
        assertEquals(emptyList<String>(), PageMath.toDelete(before, after))
        // undo: reach `before` from `after`
        assertEquals(emptyList<String>(), PageMath.toRestore(after.toSet(), before))
        assertEquals(listOf("N"), PageMath.toDelete(after, before))
    }

    @Test fun `delete undo and redo diffs are exact mirrors`() {
        val before = listOf("A", "B", "C")
        val after = listOf("A", "C")
        assertEquals(emptyList<String>(), PageMath.toRestore(before.toSet(), after))
        assertEquals(listOf("B"), PageMath.toDelete(before, after))
        assertEquals(listOf("B"), PageMath.toRestore(after.toSet(), before))
        assertEquals(emptyList<String>(), PageMath.toDelete(after, before))
    }

    @Test fun `toRestore keeps the target's order, not the alive set's`() {
        assertEquals(listOf("X", "Y"), PageMath.toRestore(setOf("A"), listOf("X", "A", "Y")))
    }
}
