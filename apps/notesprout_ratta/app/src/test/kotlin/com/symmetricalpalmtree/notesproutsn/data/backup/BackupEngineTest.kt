package com.symmetricalpalmtree.notesproutsn.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine itself is IO against a device, so what is testable here is the one piece of its
 * result that is a rule rather than a side effect: [BackupEngine.Result.succeeded], which decides
 * whether the run stamps `lastRunAt` and moves the status line.
 */
class BackupEngineTest {

    @Test
    fun aRunThatWroteNothingDidNotSucceed() {
        assertFalse(BackupEngine.Result().succeeded)
        assertFalse(BackupEngine.Result(upToDate = 3, excluded = 1).succeeded)
        assertFalse(BackupEngine.Result(failed = 2, storesFailed = 1).succeeded)
    }

    @Test
    fun anyLandedWriteCounts() {
        assertTrue(BackupEngine.Result(copied = 1).succeeded)
        assertTrue(BackupEngine.Result(indexCopied = true).succeeded)
    }

    /**
     * Arc 21 / W5: a run in which every notebook was already up to date and the index copy failed
     * still moved bytes if a store landed — and a status line that said otherwise would be lying
     * about a backup that exists.
     */
    @Test
    fun aStoreCopyIsALandedWrite() {
        assertTrue(BackupEngine.Result(upToDate = 5, storesCopied = 1).succeeded)
    }
}
