package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload budget (arc 25 / V2) — the one number on the cloud seam a caller must compute rather
 * than read, and therefore the one worth a table.
 */
class CloudTimeoutsTest {

    private val mib = 1024L * 1024

    @Test
    fun `an empty file gets the small budget`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(0))
    }

    @Test
    fun `one byte gets the small budget`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(1))
    }

    @Test
    fun `the small ceiling itself is still small`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(5 * mib))
    }

    @Test
    fun `one byte over the ceiling is one large slice`() {
        assertEquals(CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(5 * mib + 1))
    }

    @Test
    fun `twenty mebibytes is exactly one slice`() {
        assertEquals(CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(20 * mib))
    }

    @Test
    fun `a partial slice is charged in full`() {
        assertEquals(2 * CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(20 * mib + 1))
    }

    @Test
    fun `a hundred mebibytes is five slices`() {
        assertEquals(5 * CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(100 * mib))
    }

    @Test
    fun `a nonsense byte count is charged the small budget, not an exception`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(-1))
    }

    @Test
    fun `the budget never decreases as the file grows`() {
        var previous = 0L
        var bytes = 0L
        while (bytes <= 200 * mib) {
            val budget = CloudTimeouts.uploadBudgetMs(bytes)
            assertTrue("budget shrank at $bytes B", budget >= previous)
            previous = budget
            bytes += mib
        }
    }
}
