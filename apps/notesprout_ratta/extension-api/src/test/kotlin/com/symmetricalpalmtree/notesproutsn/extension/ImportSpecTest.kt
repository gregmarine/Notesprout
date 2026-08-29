package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ImportSpecTest {

    @Test
    fun acceptsAValidSpec() {
        val s = ImportSpec(mapOf("dedupe" to "1"), "Field Notes 2026.soil")
        assertEquals("1", s.values["dedupe"])
        assertEquals("Field Notes 2026.soil", s.displayName)
    }

    @Test
    fun acceptsAnEmptySpec() {
        ImportSpec(emptyMap(), "")
    }

    @Test
    fun rejectsBadKeys() {
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(mapOf("" to "x"), "n") }
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(mapOf("has space" to "x"), "n") }
        assertThrows(IllegalArgumentException::class.java) {
            ImportSpec(mapOf(("k").repeat(ExporterContract.MAX_ID_CHARS + 1) to "x"), "n")
        }
    }

    @Test
    fun rejectsOversizeValueOrEntryCount() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportSpec(mapOf("k" to "x".repeat(ExporterContract.MAX_SPEC_VALUE_CHARS + 1)), "n")
        }
        val many = (0..ExporterContract.MAX_OPTIONS).associate { "k$it" to "v" }
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(many, "n") }
    }

    @Test
    fun displayNameIsDisplayOnly() {
        // Spaces are fine — a picked document's own name; a path separator or NUL never is.
        ImportSpec(emptyMap(), "My daily notes.soil")
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(emptyMap(), "a/b") }
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(emptyMap(), "a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) {
            ImportSpec(emptyMap(), "x".repeat(ExporterContract.MAX_NAME_CHARS + 1))
        }
    }
}
