package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure `require`s behind [LinkDestination] + the L0 `DEST_*` contract constants (no Parcel on the JVM). */
class LinkDestinationTest {

    @Test
    fun acceptsWellFormed() {
        LinkDestination.requireValid(ExtensionContract.DEST_PAGE, null, "page-1")
        LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK, "nb-1", null)
        LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK_PAGE, "nb-1", "page-1")
    }

    @Test
    fun roundTripsFields() {
        // Parcel is unavailable on the JVM; the write order (kind, notebookId, pageId) is fixed — the fields survive construction.
        val d = LinkDestination(ExtensionContract.DEST_NOTEBOOK_PAGE, "nb-1", "page-1")
        assertEquals(ExtensionContract.DEST_NOTEBOOK_PAGE, d.kind)
        assertEquals("nb-1", d.notebookId)
        assertEquals("page-1", d.pageId)
    }

    @Test
    fun rejectsUnknownKind() {
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(3, null, "page-1") }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(-1, null, "page-1") }
    }

    @Test
    fun rejectsDestPageMisshapen() {
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_PAGE, "nb-1", "page-1") }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_PAGE, null, null) }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_PAGE, null, "  ") }
    }

    @Test
    fun rejectsDestNotebookMisshapen() {
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK, "nb-1", "page-1") }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK, "  ", null) }
    }

    @Test
    fun rejectsDestNotebookPageMissingEither() {
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK_PAGE, null, "page-1") }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK_PAGE, "nb-1", null) }
    }

    @Test
    fun rejectsOverLongId() {
        val tooLong = "a".repeat(ExtensionContract.MAX_LINK_ID_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_PAGE, null, tooLong) }
        assertThrows(IllegalArgumentException::class.java) { LinkDestination.requireValid(ExtensionContract.DEST_NOTEBOOK, tooLong, null) }
    }

    @Test
    fun constants() {
        assertEquals(0, ExtensionContract.DEST_PAGE)
        assertEquals(1, ExtensionContract.DEST_NOTEBOOK)
        assertEquals(2, ExtensionContract.DEST_NOTEBOOK_PAGE)
        assertEquals(64, ExtensionContract.MAX_LINK_ID_CHARS)
    }
}
