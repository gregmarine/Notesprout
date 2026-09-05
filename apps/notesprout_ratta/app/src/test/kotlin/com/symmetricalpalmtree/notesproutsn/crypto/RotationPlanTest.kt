package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.crypto.RotationPlan.CommitStep
import com.symmetricalpalmtree.notesproutsn.crypto.RotationPlan.Failure
import com.symmetricalpalmtree.notesproutsn.crypto.RotationPlan.Kind
import com.symmetricalpalmtree.notesproutsn.crypto.RotationPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D2's tables: the id order, the id kinds, the per-file outcome, the commit's side-effect list. */
class RotationPlanTest {

    @Test
    fun orderIsNotebooksThenStoresThenIndexLast() {
        val ids = RotationPlan.order(listOf("nb-b", "nb-a"), listOf("com.x.pad", "com.x.tags"))
        assertEquals(listOf("nb-b", "nb-a", "ext:com.x.pad", "ext:com.x.tags", RotationPlan.INDEX_ID), ids)
        assertEquals(RotationPlan.INDEX_ID, ids.last())
    }

    @Test
    fun orderDropsDuplicatesAndForeignIds() {
        // A notebook id that happens to look like the index or a store id can never be walked twice
        // or as the wrong kind.
        val ids = RotationPlan.order(listOf("nb-1", "nb-1", RotationPlan.INDEX_ID, "ext:evil"), emptyList())
        assertEquals(listOf("nb-1", RotationPlan.INDEX_ID), ids)
    }

    @Test
    fun emptyLibraryStillRotatesTheIndex() {
        assertEquals(listOf(RotationPlan.INDEX_ID), RotationPlan.order(emptyList(), emptyList()))
    }

    @Test
    fun kinds() {
        assertEquals(Kind.INDEX, RotationPlan.kindOf(RotationPlan.INDEX_ID))
        assertEquals(Kind.STORE, RotationPlan.kindOf("ext:com.x.pad"))
        assertEquals(Kind.NOTEBOOK, RotationPlan.kindOf("0f3c-uuid"))
        assertEquals("com.x.pad", RotationPlan.storePackage("ext:com.x.pad"))
        assertNull(RotationPlan.storePackage("nb"))
        assertNull(RotationPlan.storePackage("ext:"))
        assertEquals("ext:com.x.pad", RotationPlan.storeId("com.x.pad"))
    }

    @Test
    fun perFileOutcomeTable() {
        for (kind in Kind.values()) {
            // Already under the new key wins whatever the old key says (a resume after a late commit).
            assertEquals(Step.SKIP, RotationPlan.decide(kind, opensUnderNew = true, opensUnderOld = false))
            assertEquals(Step.SKIP, RotationPlan.decide(kind, opensUnderNew = true, opensUnderOld = true))
            assertEquals(Step.REKEY, RotationPlan.decide(kind, opensUnderNew = false, opensUnderOld = true))
        }
        assertEquals(Step.QUARANTINE, RotationPlan.decide(Kind.NOTEBOOK, opensUnderNew = false, opensUnderOld = false))
        assertEquals(Step.STOP, RotationPlan.decide(Kind.STORE, opensUnderNew = false, opensUnderOld = false))
        assertEquals(Step.STOP, RotationPlan.decide(Kind.INDEX, opensUnderNew = false, opensUnderOld = false))
    }

    @Test
    fun afterFailureTable() {
        for (kind in Kind.values()) assertEquals(Failure.TRANSIENT, RotationPlan.afterFailure(kind, opensUnderOld = true))
        assertEquals(Failure.QUARANTINE, RotationPlan.afterFailure(Kind.NOTEBOOK, opensUnderOld = false))
        assertEquals(Failure.STOP, RotationPlan.afterFailure(Kind.STORE, opensUnderOld = false))
        assertEquals(Failure.STOP, RotationPlan.afterFailure(Kind.INDEX, opensUnderOld = false))
    }

    @Test
    fun resumeCandidatesAreNewRowsOrLiveRawKeys() {
        val startedAt = 1_000L
        val library = listOf(
            "pending" to 10L,      // still in the marker — never a candidate
            "done-old" to 10L,     // older than the marker, raw key invalidated by its rekey — done
            "created-since" to 2_000L, // minted under the old key after the marker — must join
            "imported-since" to 1_000L, // updatedAt == startedAt counts as since
            "warm-raw" to 10L,     // old row whose cached raw key still opens it — under the old key
        )
        val out = RotationPlan.resumeCandidates(
            globalNotebooks = library,
            pendingIds = setOf("pending"),
            startedAt = startedAt,
            rawKeyOpens = { it == "warm-raw" || it == "pending" },
        )
        assertEquals(listOf("created-since", "imported-since", "warm-raw"), out)
    }

    @Test
    fun resumeCandidatesWithNoStartedAtChecksEveryRow() {
        // A marker from a build before `startedAt` existed reads 0: every non-pending row is a
        // candidate (a KDF each — the safe direction).
        val out = RotationPlan.resumeCandidates(listOf("a" to 5L, "b" to 5L), setOf("b"), startedAt = 0L) { false }
        assertEquals(listOf("a"), out)
    }

    @Test
    fun commitStepsInOrder() {
        assertEquals(
            listOf(CommitStep.SET_GLOBAL, CommitStep.CLEAR_ACK, CommitStep.CLEAR_RAW_KEYS, CommitStep.SET_SESSION, CommitStep.CLEAR_MARKER),
            RotationPlan.commitSteps(minted = true),
        )
        assertEquals(
            listOf(CommitStep.SET_GLOBAL, CommitStep.CLEAR_RAW_KEYS, CommitStep.SET_SESSION, CommitStep.CLEAR_MARKER),
            RotationPlan.commitSteps(minted = false),
        )
        // The invariants the order encodes: the global is set before the marker goes, and the
        // marker goes last.
        for (minted in listOf(true, false)) {
            val steps = RotationPlan.commitSteps(minted)
            assertEquals(CommitStep.SET_GLOBAL, steps.first())
            assertEquals(CommitStep.CLEAR_MARKER, steps.last())
        }
    }
}
