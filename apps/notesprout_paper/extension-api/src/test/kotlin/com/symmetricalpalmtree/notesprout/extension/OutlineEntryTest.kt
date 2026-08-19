package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure `require`s behind [OutlineEntry] + the C0 contract constants (no Parcel on the JVM). */
class OutlineEntryTest {

    @Test
    fun acceptsWellFormed() {
        OutlineEntry.requireValid("Meeting notes", 2)
        OutlineEntry.requireValid("", 0)                                    // NONE
        OutlineEntry.requireValid("x", ExtensionContract.MAX_OUTLINE_LEVEL)
        OutlineEntry.requireValid("a".repeat(ExtensionContract.MAX_OUTLINE_LABEL_CHARS), 1)
    }

    @Test
    fun rejectsLevelOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { OutlineEntry.requireValid("x", ExtensionContract.MAX_OUTLINE_LEVEL + 1) }
        assertThrows(IllegalArgumentException::class.java) { OutlineEntry.requireValid("x", -1) }
    }

    @Test
    fun rejectsOverLongLabel() {
        assertThrows(IllegalArgumentException::class.java) {
            OutlineEntry.requireValid("a".repeat(ExtensionContract.MAX_OUTLINE_LABEL_CHARS + 1), 1)
        }
    }

    @Test
    fun roundTripsFields() {
        // Parcel is unavailable on the JVM; the write order (label, level) is fixed — the fields survive construction.
        val e = OutlineEntry("Meeting notes", 2)
        assertEquals("Meeting notes", e.label)
        assertEquals(2, e.level)
        assertEquals(0, OutlineEntry.NONE.level)
        assertEquals("", OutlineEntry.NONE.label)
    }

    @Test
    fun constants() {
        assertEquals(200, ExtensionContract.MAX_OUTLINE_LABEL_CHARS)
        assertEquals(6, ExtensionContract.MAX_OUTLINE_LEVEL)
        assertEquals(200, ExtensionContract.MAX_OUTLINE_BATCH)
        assertEquals(100_000, ExtensionContract.MAX_OUTLINE_BATCH_CHARS)
        assertEquals(2_000, ExtensionContract.MAX_OUTLINE_ENTRIES)
        assertEquals("list", IconNames.LIST)
        assertTrue(IconNames.LIST in IconNames.ALL)
    }
}
