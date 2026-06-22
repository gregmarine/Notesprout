package com.notesprout.android.data.backup

import org.junit.Assert.*
import org.junit.Test

class NeedsBackupLogicTest {

    @Test
    fun needsBackup_whenNeverBackedUp() {
        assertTrue(needsBackup(updatedAt = 1000L, lastBackedUp = null, excludeFromBackup = false))
    }

    @Test
    fun needsBackup_whenModifiedAfterLastBackup() {
        assertTrue(needsBackup(updatedAt = 2000L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    @Test
    fun doesNotNeedBackup_whenUpToDate() {
        assertFalse(needsBackup(updatedAt = 1000L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    @Test
    fun doesNotNeedBackup_whenBackedUpAfterLastModification() {
        assertFalse(needsBackup(updatedAt = 500L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    @Test
    fun doesNotNeedBackup_whenExcluded_neverBackedUp() {
        assertFalse(needsBackup(updatedAt = 1000L, lastBackedUp = null, excludeFromBackup = true))
    }

    @Test
    fun doesNotNeedBackup_whenExcluded_modifiedAfterBackup() {
        assertFalse(needsBackup(updatedAt = 2000L, lastBackedUp = 1000L, excludeFromBackup = true))
    }
}
