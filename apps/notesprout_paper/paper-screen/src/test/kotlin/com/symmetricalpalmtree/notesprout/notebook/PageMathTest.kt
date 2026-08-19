package com.symmetricalpalmtree.notesprout.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMathTest {

    @Test fun indexAfterDelete_middle_landsOnPrevious() {
        // [A,B,C], delete B (index 1) → land on index 0 (A)
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 1, oldSize = 3))
    }

    @Test fun indexAfterDelete_first_landsOnNewFirst() {
        // [A,B,C], delete A (index 0) → index 0 (was B)
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 0, oldSize = 3))
    }

    @Test fun indexAfterDelete_last_landsOnNewLast() {
        // [A,B,C], delete C (index 2) → index 1 (B), which is the last of the two remaining
        assertEquals(1, PageMath.indexAfterDelete(deletedIndex = 2, oldSize = 3))
    }

    @Test fun indexAfterDelete_twoPages_deleteSecond() {
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 1, oldSize = 2))
    }

    @Test fun indexAfterDelete_twoPages_deleteFirst() {
        assertEquals(0, PageMath.indexAfterDelete(deletedIndex = 0, oldSize = 2))
    }

    @Test fun insertPosition_after_isNextSlot() {
        assertEquals(3, PageMath.insertPosition(currentIndex = 2, after = true))
    }

    @Test fun insertPosition_before_isSameSlot() {
        assertEquals(2, PageMath.insertPosition(currentIndex = 2, after = false))
    }

    @Test fun toRestore_findsPagesMissingFromLiveSet() {
        // undo of a delete: target has B back, currently only A,C alive
        val alive = setOf("A", "C")
        val target = listOf("A", "B", "C")
        assertEquals(listOf("B"), PageMath.toRestore(alive, target))
    }

    @Test fun toRestore_empty_whenAllAlive() {
        assertEquals(emptyList<String>(), PageMath.toRestore(setOf("A", "B"), listOf("A", "B")))
    }

    @Test fun toDelete_findsPagesNotInTarget() {
        // undo of an insert: target [A,B], N is alive and must go
        val alive = listOf("A", "N", "B")
        val target = listOf("A", "B")
        assertEquals(listOf("N"), PageMath.toDelete(alive, target))
    }

    @Test fun toDelete_soleReplacement() {
        // delete-only-page redo: alive [A], target [N]
        assertEquals(listOf("A"), PageMath.toDelete(listOf("A"), listOf("N")))
    }

    @Test fun reconcile_diff_isSymmetric_forInsertUndoRedo() {
        val before = listOf("A", "B")
        val after = listOf("A", "N", "B")
        // redo (reach `after` from `before`)
        assertEquals(listOf("N"), PageMath.toRestore(before.toSet(), after))
        assertEquals(emptyList<String>(), PageMath.toDelete(before, after))
        // undo (reach `before` from `after`)
        assertEquals(emptyList<String>(), PageMath.toRestore(after.toSet(), before))
        assertEquals(listOf("N"), PageMath.toDelete(after, before))
    }
}
