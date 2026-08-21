package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure `require`s behind [CatalogEntry] + the L0 `CATALOG_*` contract constants (no Parcel on the JVM). */
class CatalogEntryTest {

    @Test
    fun acceptsWellFormed() {
        CatalogEntry.requireValid("id-1", ExtensionContract.CATALOG_FOLDER, "Folder")
        CatalogEntry.requireValid("id-2", ExtensionContract.CATALOG_NOTEBOOK, "Notebook")
        CatalogEntry.requireValid("id-3", ExtensionContract.CATALOG_PAGE, "Page")
    }

    @Test
    fun acceptsBlankLabel() {
        // A page with no name — legal, the picker falls back to "Page n" from position.
        CatalogEntry.requireValid("id-1", ExtensionContract.CATALOG_PAGE, "")
    }

    @Test
    fun acceptsMaxLengthLabel() {
        CatalogEntry.requireValid("id-1", ExtensionContract.CATALOG_PAGE, "a".repeat(ExtensionContract.MAX_CATALOG_LABEL_CHARS))
    }

    @Test
    fun roundTripsFields() {
        // Parcel is unavailable on the JVM; the write order (id, kind, label) is fixed — the fields survive construction.
        val e = CatalogEntry("id-1", ExtensionContract.CATALOG_NOTEBOOK, "My Notebook")
        assertEquals("id-1", e.id)
        assertEquals(ExtensionContract.CATALOG_NOTEBOOK, e.kind)
        assertEquals("My Notebook", e.label)
    }

    @Test
    fun rejectsBlankId() {
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry.requireValid("", ExtensionContract.CATALOG_FOLDER, "x") }
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry.requireValid("  ", ExtensionContract.CATALOG_FOLDER, "x") }
    }

    @Test
    fun rejectsOverLongId() {
        val tooLong = "a".repeat(ExtensionContract.MAX_LINK_ID_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry.requireValid(tooLong, ExtensionContract.CATALOG_FOLDER, "x") }
    }

    @Test
    fun rejectsUnknownKind() {
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry.requireValid("id-1", 3, "x") }
    }

    @Test
    fun rejectsOverLongLabel() {
        val tooLong = "a".repeat(ExtensionContract.MAX_CATALOG_LABEL_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry.requireValid("id-1", ExtensionContract.CATALOG_FOLDER, tooLong) }
    }

    @Test
    fun constants() {
        assertEquals(0, ExtensionContract.CATALOG_FOLDER)
        assertEquals(1, ExtensionContract.CATALOG_NOTEBOOK)
        assertEquals(2, ExtensionContract.CATALOG_PAGE)
        assertEquals(200, ExtensionContract.MAX_CATALOG_LABEL_CHARS)
    }
}
