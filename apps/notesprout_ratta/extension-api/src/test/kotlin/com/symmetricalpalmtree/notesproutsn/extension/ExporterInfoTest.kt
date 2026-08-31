package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ExporterInfoTest {

    private val keying = OptionDescriptor(
        ExporterContract.OPTION_KEYING, "Encryption", ExporterContract.KIND_SINGLE_CHOICE,
        listOf(ExporterContract.KEYING_KEEP), listOf("Keep encrypted"), ExporterContract.KEYING_KEEP,
    )

    private fun info(
        formatLabel: String = "Notesprout notebook",
        fileExtension: String = "soil",
        mimeType: String = "application/octet-stream",
        options: List<OptionDescriptor> = listOf(keying),
    ) = ExporterInfo(formatLabel, fileExtension, mimeType, options)

    @Test
    fun acceptsTheSoilDescriptor() {
        val i = info()
        assertEquals("soil", i.fileExtension)
        assertEquals("application/octet-stream", i.mimeType)
        assertEquals(1, i.options.size)
    }

    @Test
    fun rejectsBadFormatLabel() {
        assertThrows(IllegalArgumentException::class.java) { info(formatLabel = "") }
        assertThrows(IllegalArgumentException::class.java) { info(formatLabel = "x".repeat(81)) }
    }

    @Test
    fun rejectsBadFileExtension() {
        assertThrows(IllegalArgumentException::class.java) { info(fileExtension = "") }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtension = ".soil") }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtension = "SOIL") }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtension = "a".repeat(13)) }
    }

    @Test
    fun rejectsBadMime() {
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "") }
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "noslash") }
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "a/b/c") }
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "/octet-stream") }
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "application/") }
        assertThrows(IllegalArgumentException::class.java) { info(mimeType = "application/" + "x".repeat(128)) }
    }

    @Test
    fun sourceKindDefaultsToSoil() {
        // The old constructor shape still compiles and still means the prepared `.soil` — the same
        // statement the wire tail makes for an old-shape parcel (absent tail = SOURCE_SOIL).
        assertEquals(ExporterContract.SOURCE_SOIL, info().sourceKind)
    }

    @Test
    fun acceptsPagesAndDocumentAndRejectsUnknownSourceKinds() {
        val pages = ExporterInfo("PDF document", "pdf", "application/pdf", emptyList(), ExporterContract.SOURCE_PAGES)
        assertEquals(ExporterContract.SOURCE_PAGES, pages.sourceKind)
        // Arc 19 / M9: kind 2 is the document text — accepting it here is exactly what a pre-M9
        // host could not do, which is why the declaring service requires API version 3.
        val document = ExporterInfo("Markdown / text document", "md", "text/markdown", emptyList(), ExporterContract.SOURCE_DOCUMENT)
        assertEquals(ExporterContract.SOURCE_DOCUMENT, document.sourceKind)
        assertThrows(IllegalArgumentException::class.java) {
            ExporterInfo("PDF document", "pdf", "application/pdf", emptyList(), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExporterInfo("PDF document", "pdf", "application/pdf", emptyList(), -1)
        }
    }

    @Test
    fun rejectsTooManyOrDuplicateOptions() {
        val many = (0..ExporterContract.MAX_OPTIONS).map {
            OptionDescriptor("o$it", "Option $it", ExporterContract.KIND_TOGGLE, emptyList(), emptyList(), "0")
        }
        assertThrows(IllegalArgumentException::class.java) { info(options = many) }
        assertThrows(IllegalArgumentException::class.java) { info(options = listOf(keying, keying)) }
    }
}
