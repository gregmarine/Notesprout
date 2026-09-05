package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-leg run's rules (arc 25 / V4) — which legs exist, what the progress dialog counts, the
 * stale-sidecar lookup, the mid-leg stop table, and which blocks the report draws.
 */
class CloudBackupRulesTest {

    private fun file(name: String, size: Long = 10L) = CloudEntry("id-$name", name, false, size, 1L)
    private fun folder(name: String) = CloudEntry("id-$name", name, true, 0L, 1L)

    // ── legs ────────────────────────────────────────────────────────────

    @Test
    fun `a folder alone is the local leg`() {
        val legs = CloudBackupRules.legs(hasFolder = true, cloudEnabled = false, hasProvider = true)
        assertTrue(legs.local)
        assertFalse(legs.cloud)
        assertFalse(legs.none)
    }

    @Test
    fun `the tick alone is not a cloud leg without a provider`() {
        val legs = CloudBackupRules.legs(hasFolder = false, cloudEnabled = true, hasProvider = false)
        assertFalse(legs.cloud)
        assertTrue(legs.none)
    }

    @Test
    fun `a provider alone is not a cloud leg without the tick`() {
        val legs = CloudBackupRules.legs(hasFolder = false, cloudEnabled = false, hasProvider = true)
        assertFalse(legs.cloud)
        assertTrue(legs.none)
    }

    @Test
    fun `both destinations give both legs`() {
        val legs = CloudBackupRules.legs(hasFolder = true, cloudEnabled = true, hasProvider = true)
        assertTrue(legs.local)
        assertTrue(legs.cloud)
        assertFalse(legs.none)
    }

    // ── progress ────────────────────────────────────────────────────────

    @Test
    fun `a leg counts its notebooks, its stores and the one index`() {
        assertEquals(1, CloudBackupRules.units(0, 0))
        assertEquals(9, CloudBackupRules.units(5, 3))
    }

    @Test
    fun `the total is both legs, and a leg that does not run adds nothing`() {
        assertEquals(18, CloudBackupRules.total(9, 9))
        assertEquals(9, CloudBackupRules.total(9, 0))
    }

    // ── the stale sidecar ───────────────────────────────────────────────

    @Test
    fun `the sidecar is found by its exact name`() {
        val listing = listOf(file("nb.soil"), file("nb.soil-wal"), file("other.soil-wal"))
        assertEquals("nb.soil-wal", CloudBackupRules.staleSidecar(listing, "nb.soil")?.name)
    }

    @Test
    fun `no sidecar means nothing to delete`() {
        assertNull(CloudBackupRules.staleSidecar(listOf(file("nb.soil")), "nb.soil"))
        assertNull(CloudBackupRules.staleSidecar(emptyList(), "nb.soil"))
    }

    @Test
    fun `a match is never case-folded — upload resolves names exactly too`() {
        assertNull(CloudBackupRules.staleSidecar(listOf(file("NB.soil-wal")), "nb.soil"))
    }

    /** The one destructive call in the arc must never be pointed at a folder. */
    @Test
    fun `a folder of that name is not a sidecar`() {
        assertNull(CloudBackupRules.staleSidecar(listOf(folder("nb.soil-wal")), "nb.soil"))
    }

    // ── the stop table ──────────────────────────────────────────────────

    @Test
    fun `each failure has its own problem`() {
        assertEquals(
            BackupEngine.Problem.CLOUD_NOT_CONNECTED,
            CloudBackupRules.problemFor(CloudBackupRules.Failure.NOT_CONNECTED),
        )
        assertEquals(
            BackupEngine.Problem.CLOUD_NETWORK,
            CloudBackupRules.problemFor(CloudBackupRules.Failure.NETWORK),
        )
        assertEquals(
            BackupEngine.Problem.CLOUD_UNANSWERED,
            CloudBackupRules.problemFor(CloudBackupRules.Failure.UNANSWERED),
        )
        assertEquals(
            BackupEngine.Problem.CLOUD_GONE,
            CloudBackupRules.problemFor(CloudBackupRules.Failure.GONE),
        )
    }

    @Test
    fun `every cloud problem ends the leg, and nothing else does`() {
        for (failure in CloudBackupRules.Failure.entries) {
            assertTrue(CloudBackupRules.endsLeg(CloudBackupRules.problemFor(failure)))
        }
        assertFalse(CloudBackupRules.endsLeg(null))
        assertFalse(CloudBackupRules.endsLeg(BackupEngine.Problem.FOLDER_GONE))
        assertFalse(CloudBackupRules.endsLeg(BackupEngine.Problem.NO_KEY))
        assertFalse(CloudBackupRules.endsLeg(BackupEngine.Problem.NO_DESTINATION))
    }

    // ── the report ──────────────────────────────────────────────────────

    @Test
    fun `a leg that did not run is clean and draws no block`() {
        assertTrue(CloudBackupRules.legClean(null))
        assertFalse(CloudBackupRules.showsBlock(null))
    }

    @Test
    fun `a leg that landed everything is clean`() {
        val r = BackupEngine.Result(copied = 3, upToDate = 2, storesCopied = 1, indexCopied = true)
        assertTrue(CloudBackupRules.legClean(r))
        assertTrue(CloudBackupRules.showsBlock(r))
    }

    @Test
    fun `a failed copy, a failed store, a missing index or a problem is not clean`() {
        assertFalse(CloudBackupRules.legClean(BackupEngine.Result(copied = 1, failed = 1, indexCopied = true)))
        assertFalse(CloudBackupRules.legClean(BackupEngine.Result(storesFailed = 1, indexCopied = true)))
        assertFalse(CloudBackupRules.legClean(BackupEngine.Result(copied = 1, indexCopied = false)))
        assertFalse(
            CloudBackupRules.legClean(
                BackupEngine.Result(problem = BackupEngine.Problem.CLOUD_NETWORK, indexCopied = true)
            )
        )
    }

    @Test
    fun `the run is clean only when both legs are`() {
        val good = BackupEngine.Result(copied = 1, indexCopied = true)
        val bad = BackupEngine.Result(copied = 1, failed = 1, indexCopied = true)
        assertTrue(CloudBackupRules.clean(BackupEngine.Outcome(local = good, cloud = good)))
        assertTrue(CloudBackupRules.clean(BackupEngine.Outcome(local = good, cloud = null)))
        assertFalse(CloudBackupRules.clean(BackupEngine.Outcome(local = good, cloud = bad)))
        assertFalse(CloudBackupRules.clean(BackupEngine.Outcome(local = bad, cloud = good)))
        assertFalse(
            CloudBackupRules.clean(BackupEngine.Outcome(problem = BackupEngine.Problem.NO_DESTINATION))
        )
    }
}
