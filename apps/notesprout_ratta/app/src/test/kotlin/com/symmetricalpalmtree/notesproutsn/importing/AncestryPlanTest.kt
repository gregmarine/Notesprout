package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Notebook's folders", planned. The rule under test has no exceptions: **create, never mutate**,
 * and a blocked segment stops the descent one level up.
 */
class AncestryPlanTest {

    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "aaaaaaaa-0000-4000-8000-000000000002"
    private val c = "aaaaaaaa-0000-4000-8000-000000000003"

    private fun path(vararg ids: Pair<String, String>): List<FolderRef> =
        ids.map { (id, name) -> FolderRef(id, name, null) }

    @Test
    fun anEmptyPathLandsAtTheRoot() {
        val plan = AncestryPlan.plan(emptyList()) { AncestryPlan.Slot.MISSING }
        assertNull(plan.parentId)
        assertTrue(plan.create.isEmpty())
        assertFalse(plan.truncated)
    }

    @Test
    fun everythingMissingIsCreatedParentsFirst() {
        val plan = AncestryPlan.plan(path(a to "Work", b to "2026", c to "Trips")) {
            AncestryPlan.Slot.MISSING
        }
        assertEquals(c, plan.parentId)
        assertEquals(listOf(a, b, c), plan.create.map { it.id })
        assertEquals(listOf(null, a, b), plan.create.map { it.parentId })
        assertEquals(listOf("Work", "2026", "Trips"), plan.create.map { it.name })
        assertFalse(plan.truncated)
    }

    @Test
    fun aLiveFolderIsDescendedThroughAndNeverTouched() {
        val plan = AncestryPlan.plan(path(a to "Work", b to "2026")) { id ->
            if (id == a) AncestryPlan.Slot.LIVE_FOLDER else AncestryPlan.Slot.MISSING
        }
        assertEquals(b, plan.parentId)
        // Only the missing one is created; the existing folder is not in the list at all.
        assertEquals(listOf(b), plan.create.map { it.id })
        assertEquals(a, plan.create.single().parentId)
    }

    @Test
    fun anAncestryThatAlreadyExistsIsANoOp() {
        val plan = AncestryPlan.plan(path(a to "Work", b to "2026")) { AncestryPlan.Slot.LIVE_FOLDER }
        assertEquals(b, plan.parentId)
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun aBlockedSegmentStopsTheDescentOneLevelUp() {
        // b is a notebook, or a soft-deleted folder, or a list sentinel — never mutated either way.
        val plan = AncestryPlan.plan(path(a to "Work", b to "2026", c to "Trips")) { id ->
            when (id) {
                a -> AncestryPlan.Slot.LIVE_FOLDER
                b -> AncestryPlan.Slot.BLOCKED
                else -> AncestryPlan.Slot.MISSING
            }
        }
        assertEquals(a, plan.parentId)
        assertTrue(plan.create.isEmpty())
        assertTrue(plan.truncated)
    }

    @Test
    fun aBlockAtTheTopLandsAtTheRoot() {
        val plan = AncestryPlan.plan(path(a to "Work")) { AncestryPlan.Slot.BLOCKED }
        assertNull(plan.parentId)
        assertTrue(plan.create.isEmpty())
        assertTrue(plan.truncated)
    }

    @Test
    fun anUnsafeIdIsNeitherCreatedNorDescendedInto() {
        val plan = AncestryPlan.plan(path(a to "Work", "../../etc" to "evil", c to "Trips")) {
            AncestryPlan.Slot.MISSING
        }
        assertEquals(a, plan.parentId)
        assertEquals(listOf(a), plan.create.map { it.id })
        assertTrue(plan.truncated)
    }

    @Test
    fun aBlankSegmentNameStillGetsAWord() {
        val plan = AncestryPlan.plan(path(a to "  ")) { AncestryPlan.Slot.MISSING }
        assertEquals("Imported", plan.create.single().name)
    }

    @Test
    fun theWalkIsBounded() {
        val deep = (1..AncestryPlan.MAX_DEPTH + 5).map {
            FolderRef(String.format("aaaaaaaa-0000-4000-8000-%012d", it), "f$it", null)
        }
        val plan = AncestryPlan.plan(deep) { AncestryPlan.Slot.MISSING }
        assertEquals(AncestryPlan.MAX_DEPTH, plan.create.size)
        assertTrue(plan.truncated)
    }
}
