package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** og's sanitize rule, pinned: this is the name the family has always given an exported file. */
class ExportNamingTest {

    private val id = "8f14e45f-ceea-467a-9b8d-8f14e45fceea"

    @Test
    fun keepsAPlainName() {
        assertEquals("Field notes", ExportNaming.base("Field notes", id))
    }

    @Test
    fun spacesInsideSurvive() {
        assertEquals("My great notebook", ExportNaming.base("My great notebook", id))
    }

    @Test
    fun stripsEverythingOutsideTheCharset() {
        assertEquals("aB9_-.", ExportNaming.base("aB9_-./\\:*?\"<>|", id))
        // A separator is removed, not replaced: the two halves close up.
        assertEquals("Meeting2026", ExportNaming.base("Meeting/2026", id))
        assertEquals("ntes", ExportNaming.base("nötes", id))
        assertEquals("emoji", ExportNaming.base("emoji🌱", id))
    }

    @Test
    fun stripsFirstThenTrims() {
        // The stripping is what exposes the outer spaces; trimming first would leave them behind.
        assertEquals("name", ExportNaming.base("  ***name***  ", id))
        assertEquals("name", ExportNaming.base(" name ", id))
    }

    @Test
    fun emptyFallsBackToTheId() {
        assertEquals(id, ExportNaming.base("", id))
        assertEquals(id, ExportNaming.base("   ", id))
        assertEquals(id, ExportNaming.base("/////", id))
        assertEquals(id, ExportNaming.base("🌱🌱", id))
    }

    @Test
    fun dotAndDotDotFallBackToTheId() {
        assertEquals(id, ExportNaming.base(".", id))
        assertEquals(id, ExportNaming.base("..", id))
        // Three dots is a legal (if odd) filename, so it is kept.
        assertEquals("...", ExportNaming.base("...", id))
        // A leading dot is legal too: only bare "." and ".." are the directory names.
        assertEquals(".hidden", ExportNaming.base(".hidden", id))
    }

    @Test
    fun suggestedFileNameAppendsTheExporterExtension() {
        assertEquals("Field notes.soil", ExportNaming.suggestedFileName("Field notes", id, "soil"))
        assertEquals("$id.soil", ExportNaming.suggestedFileName("///", id, "soil"))
        assertEquals("Field notes.pdf", ExportNaming.suggestedFileName("Field notes", id, "pdf"))
    }

    @Test
    fun specNameTruncatesToTheContractCap() {
        val long = "n".repeat(ExporterContract.MAX_NAME_CHARS + 50)
        val spec = ExportNaming.specName(long, id)
        assertEquals(ExporterContract.MAX_NAME_CHARS, spec.length)
        assertTrue(spec.all { it == 'n' })
    }

    @Test
    fun specNameIsTheSameBaseAsTheFilename() {
        assertEquals(ExportNaming.base("Field notes", id), ExportNaming.specName("Field notes", id))
        assertEquals(id, ExportNaming.specName("..", id))
        // What the spec's own constructor demands: no separator, no NUL. Spaces stay.
        val spec = ExportNaming.specName("a/b c d", id)
        assertEquals("ab c d", spec)
        assertTrue('/' !in spec)
    }
}
