package com.symmetricalpalmtree.notesproutsn.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an imported notebook is called: one line, bounded, and never a name the user did not have. */
class ImportNamesTest {

    @Test
    fun aNormalNameSurvivesWhole() {
        // Unlike an export filename, an index name is not sanitized down to a charset: it never
        // touches the filesystem, and mangling it would rename the user's notebook for nothing.
        assertEquals("Field notes (2)", ImportNames.clean("Field notes (2)"))
        assertEquals("nötes 🌱", ImportNames.clean("nötes 🌱"))
    }

    @Test
    fun controlCharactersBecomeSpacesAndTheEdgesAreTrimmed() {
        assertEquals("a b", ImportNames.clean("a\nb"))
        assertEquals("a b", ImportNames.clean("\ta b\r"))
        assertEquals("", ImportNames.clean("   "))
        assertEquals("", ImportNames.clean(null))
    }

    @Test
    fun theLengthIsBounded() {
        val long = "x".repeat(500)
        assertEquals(ImportNames.MAX_NAME_CHARS, ImportNames.clean(long).length)
    }

    @Test
    fun aFileNameLosesItsExtension() {
        assertEquals("Field notes", ImportNames.fromDisplayName("Field notes.soil"))
        assertEquals("a.b", ImportNames.fromDisplayName("a.b.soil"))
        assertEquals("Field notes", ImportNames.fromDisplayName("/Download/Field notes.soil"))
        assertEquals(ImportNames.FALLBACK, ImportNames.fromDisplayName(".soil"))
        assertEquals(ImportNames.FALLBACK, ImportNames.fromDisplayName(""))
    }

    @Test
    fun theManifestNameWinsAndTheFileNameIsTheFallback() {
        assertEquals("Trip log", ImportNames.notebookName("Trip log", "whatever.soil"))
        assertEquals("whatever", ImportNames.notebookName(null, "whatever.soil"))
        assertEquals("whatever", ImportNames.notebookName("   ", "whatever.soil"))
    }

    @Test
    fun aFolderAlwaysGetsAWord() {
        assertEquals("Work", ImportNames.folderName("Work"))
        assertEquals("Imported", ImportNames.folderName(""))
        assertEquals("Imported", ImportNames.folderName(null))
    }

    @Test
    fun keepBothTakesTheFirstFreeCopy() {
        assertEquals("Notes Copy", ImportNames.keepBothName("Notes") { false })
        assertEquals("Notes Copy 2", ImportNames.keepBothName("Notes") { it == "Notes Copy" })
        val taken = setOf("Notes Copy", "Notes Copy 2", "Notes Copy 3")
        assertEquals("Notes Copy 4", ImportNames.keepBothName("Notes") { it in taken })
    }

    @Test
    fun keepBothKeepsTheSuffixWhenTheNameIsAtTheCap() {
        val long = "x".repeat(ImportNames.MAX_NAME_CHARS)
        val result = ImportNames.keepBothName(long) { false }
        assertTrue(result.endsWith(" Copy"))
        assertTrue(result.length <= ImportNames.MAX_NAME_CHARS)
    }

    @Test
    fun keepBothGivesUpRatherThanLooping() {
        // Every candidate taken: the last one is returned anyway — a duplicate name in the index is
        // cosmetic, and refusing the import over it would be the worse answer.
        val result = ImportNames.keepBothName("Notes") { true }
        assertTrue(result.startsWith("Notes Copy"))
    }

    @Test
    fun theSpecNameIsADisplayNameNeverAPath() {
        // ImportSpec refuses a separator by construction; dropping it here means a document with an
        // odd name still imports.
        assertEquals("notes.soil", ImportNames.specDisplayName("/Download/notes.soil", 200))
        assertEquals("my notes.soil", ImportNames.specDisplayName("my notes.soil", 200))
        assertTrue('/' !in ImportNames.specDisplayName("a/b/c.soil", 200))
        assertEquals(8, ImportNames.specDisplayName("abcdefghijkl.soil", 8).length)
    }
}
