package com.symmetricalpalmtree.notesproutsn.importing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which importer gets the picked document — the extension rule, and the picker's filter. */
class ImporterMatchTest {

    private val soilOnly = listOf(listOf("soil"))

    @Test
    fun readsTheExtension() {
        assertEquals("soil", ImporterMatch.extensionOf("Field notes.soil"))
        assertEquals("soil", ImporterMatch.extensionOf("Field notes.SOIL"))
        assertEquals("soil", ImporterMatch.extensionOf("a.b.soil"))
        assertEquals("soil", ImporterMatch.extensionOf("/storage/emulated/0/Download/a.soil"))
    }

    @Test
    fun noExtensionIsNoExtension() {
        assertEquals("", ImporterMatch.extensionOf("Field notes"))
        assertEquals("", ImporterMatch.extensionOf(""))
        // A dotfile's leading dot is not an extension, and a trailing dot names nothing.
        assertEquals("", ImporterMatch.extensionOf(".soil"))
        assertEquals("", ImporterMatch.extensionOf("notes."))
    }

    @Test
    fun oneMatchIsTheWholeAnswer() {
        assertEquals(listOf(0), ImporterMatch.matching(soilOnly, "Field notes.soil"))
    }

    @Test
    fun caseDoesNotDecideIt() {
        assertEquals(listOf(0), ImporterMatch.matching(listOf(listOf("SOIL")), "notes.soil"))
    }

    @Test
    fun noMatchAnswersEmptyRatherThanGuessing() {
        assertTrue(ImporterMatch.matching(soilOnly, "notes.pdf").isEmpty())
        assertTrue(ImporterMatch.matching(soilOnly, "notes").isEmpty())
        assertTrue(ImporterMatch.matching(emptyList(), "notes.soil").isEmpty())
    }

    @Test
    fun severalMatchesKeepTheRegistryOrder() {
        val declared = listOf(listOf("pdf"), listOf("soil", "zip"), listOf("soil"))
        assertEquals(listOf(1, 2), ImporterMatch.matching(declared, "a.soil"))
    }

    @Test
    fun theFilterIsTheUnionPlusTheWildcard() {
        val declared = listOf(
            listOf("application/octet-stream"),
            listOf("application/octet-stream", "application/x-sqlite3"),
        )
        assertArrayEquals(
            arrayOf("application/octet-stream", "application/x-sqlite3", ImporterMatch.ANY_TYPE),
            ImporterMatch.mimeFilter(declared),
        )
    }

    @Test
    fun theWildcardIsThereEvenWithNothingDeclared() {
        assertArrayEquals(arrayOf(ImporterMatch.ANY_TYPE), ImporterMatch.mimeFilter(emptyList()))
    }
}
