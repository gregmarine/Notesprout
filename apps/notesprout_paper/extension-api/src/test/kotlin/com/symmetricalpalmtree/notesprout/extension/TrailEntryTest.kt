package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure `require`s behind [TrailEntry] + the L0 `MAX_LINK_ID_CHARS` contract constant (no Parcel on the JVM). */
class TrailEntryTest {

    @Test
    fun acceptsWellFormed() {
        TrailEntry.requireValid("550e8400-e29b-41d4-a716-446655440000", "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    }

    @Test
    fun roundTripsFields() {
        // Parcel is unavailable on the JVM; the write order (notebookId, pageId) is fixed — the fields survive construction.
        val e = TrailEntry("nb-1", "page-1")
        assertEquals("nb-1", e.notebookId)
        assertEquals("page-1", e.pageId)
    }

    @Test
    fun rejectsBlankNotebookId() {
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid("", "page-1") }
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid("   ", "page-1") }
    }

    @Test
    fun rejectsBlankPageId() {
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid("nb-1", "") }
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid("nb-1", "   ") }
    }

    @Test
    fun rejectsOverLongIds() {
        val tooLong = "a".repeat(ExtensionContract.MAX_LINK_ID_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid(tooLong, "page-1") }
        assertThrows(IllegalArgumentException::class.java) { TrailEntry.requireValid("nb-1", tooLong) }
    }

    @Test
    fun constants() {
        assertEquals(64, ExtensionContract.MAX_LINK_ID_CHARS)
        assertEquals(50, ExtensionContract.MAX_TRAIL_ENTRIES)
    }
}
