package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ExportSpecTest {

    @Test
    fun acceptsTheKeepSpec() {
        val s = ExportSpec(
            mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_KEEP),
            "Field Notes 2026",
        )
        assertEquals(ExporterContract.KEYING_KEEP, s.values[ExporterContract.OPTION_KEYING])
        assertEquals("Field Notes 2026", s.notebookName)
    }

    @Test
    fun acceptsAnEmptySpec() {
        ExportSpec(emptyMap(), "")
    }

    @Test
    fun rejectsBadKeys() {
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(mapOf("" to "x"), "n") }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(mapOf("has space" to "x"), "n") }
    }

    @Test
    fun rejectsOversizeValueOrEntryCount() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportSpec(mapOf("k" to "x".repeat(ExporterContract.MAX_SPEC_VALUE_CHARS + 1)), "n")
        }
        val many = (0..ExporterContract.MAX_OPTIONS).associate { "k$it" to "v" }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(many, "n") }
    }

    @Test
    fun notebookNameIsDisplayOnly() {
        // Spaces are fine — og's sanitize rule preserves them; a path separator never is.
        ExportSpec(emptyMap(), "My daily notes")
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "a/b") }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) {
            ExportSpec(emptyMap(), "x".repeat(ExporterContract.MAX_NAME_CHARS + 1))
        }
    }
}
