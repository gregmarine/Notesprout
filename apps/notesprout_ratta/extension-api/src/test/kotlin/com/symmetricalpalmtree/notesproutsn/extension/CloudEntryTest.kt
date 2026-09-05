package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [CloudEntry]'s constructor `require`s — unmarshal is the validation (family rule). */
class CloudEntryTest {

    private fun assertRefused(build: () -> CloudEntry) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aFileAndAFolder() {
        val file = CloudEntry("1abc", "trip.soil", isFolder = false, sizeBytes = 4_096, modifiedAt = 1_700_000_000_000)
        val folder = CloudEntry("2def", "Exports", isFolder = true, sizeBytes = 0, modifiedAt = 0)
        assertFalse(file.isFolder)
        assertEquals(4_096L, file.sizeBytes)
        assertTrue(folder.isFolder)
        assertEquals(0L, folder.sizeBytes)
    }

    @Test
    fun theIdMustBeAnId() {
        assertRefused { CloudEntry("", "a", false, 0, 0) }
        assertRefused { CloudEntry("a b", "a", false, 0, 0) }
        assertRefused { CloudEntry("x".repeat(CloudContract.MAX_ENTRY_ID_CHARS + 1), "a", false, 0, 0) }
    }

    @Test
    fun theNameMustBeAName() {
        assertRefused { CloudEntry("1", "", false, 0, 0) }
        assertRefused { CloudEntry("1", "a/b", false, 0, 0) }
        assertRefused { CloudEntry("1", "..", true, 0, 0) }
    }

    @Test
    fun sizesAndTimesAreNonNegativeAndAFolderHasNoSize() {
        assertRefused { CloudEntry("1", "a", false, -1, 0) }
        assertRefused { CloudEntry("1", "a", false, 0, -1) }
        assertRefused { CloudEntry("1", "a", true, 12, 0) }
    }

    @Test
    fun theNameNeverReachesToString() {
        val e = CloudEntry("1", "secret plans.soil", false, 10, 0)
        assertFalse(e.toString().contains("secret"))
    }

    @Test
    fun equalityIsByValue() {
        val a = CloudEntry("1", "a", false, 10, 5)
        assertEquals(a, CloudEntry("1", "a", false, 10, 5))
        assertEquals(a.hashCode(), CloudEntry("1", "a", false, 10, 5).hashCode())
        assertFalse(a == CloudEntry("1", "a", false, 11, 5))
        assertFalse(a == CloudEntry("1", "a", true, 0, 5))
    }
}
