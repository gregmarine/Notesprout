package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ImporterInfoTest {

    private fun info(
        formatLabel: String = "Notesprout notebook",
        fileExtensions: List<String> = listOf("soil"),
        mimeTypes: List<String> = listOf("application/octet-stream"),
    ) = ImporterInfo(formatLabel, fileExtensions, mimeTypes)

    @Test
    fun acceptsTheSoilDescriptor() {
        val i = info()
        assertEquals(listOf("soil"), i.fileExtensions)
        assertEquals(listOf("application/octet-stream"), i.mimeTypes)
    }

    @Test
    fun rejectsBadFormatLabel() {
        assertThrows(IllegalArgumentException::class.java) { info(formatLabel = "") }
        assertThrows(IllegalArgumentException::class.java) { info(formatLabel = " ") }
        assertThrows(IllegalArgumentException::class.java) { info(formatLabel = "x".repeat(81)) }
    }

    @Test
    fun rejectsFileExtensionCount() {
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = emptyList()) }
        val many = (0..ImporterContract.MAX_FILE_EXTENSIONS).map { "e$it" }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = many) }
    }

    @Test
    fun rejectsBadFileExtensionChars() {
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = listOf(".soil")) }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = listOf("SOIL")) }
        assertThrows(IllegalArgumentException::class.java) { info(fileExtensions = listOf("a".repeat(13))) }
    }

    @Test
    fun rejectsDuplicateFileExtensions() {
        assertThrows(IllegalArgumentException::class.java) {
            info(fileExtensions = listOf("soil", "soil"))
        }
    }

    @Test
    fun rejectsMimeTypeCount() {
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = emptyList()) }
        val many = (0..ImporterContract.MAX_MIME_TYPES).map { "application/x-$it" }
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = many) }
    }

    @Test
    fun rejectsBadMime() {
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = listOf("noslash")) }
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = listOf("a/b/c")) }
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = listOf("/octet-stream")) }
        assertThrows(IllegalArgumentException::class.java) { info(mimeTypes = listOf("application/")) }
        assertThrows(IllegalArgumentException::class.java) {
            info(mimeTypes = listOf("application/" + "x".repeat(128)))
        }
    }

    @Test
    fun rejectsDuplicateMimeTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            info(mimeTypes = listOf("application/octet-stream", "application/octet-stream"))
        }
    }

    // ── The result-kind compatible tail (arc 19 / M8 — the ExporterInfo.sourceKind recipe).
    //    The default is the wire pin: an old-shape parcel's absent tail must land here, so the
    //    constructor default and the parcel-read fallback are one constant. The device walk
    //    covers the live Binder round trip (this module runs no Robolectric).

    @Test
    fun resultKindDefaultsToNotebook() {
        assertEquals(ImporterContract.RESULT_NOTEBOOK, info().resultKind)
    }

    @Test
    fun acceptsTextDocumentAndRejectsUnknownResultKinds() {
        assertEquals(
            ImporterContract.RESULT_TEXT_DOCUMENT,
            ImporterInfo(
                "Text or Markdown",
                listOf("md", "markdown", "txt"),
                listOf("text/markdown", "text/plain"),
                ImporterContract.RESULT_TEXT_DOCUMENT,
            ).resultKind,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ImporterInfo("Text", listOf("txt"), listOf("text/plain"), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImporterInfo("Text", listOf("txt"), listOf("text/plain"), -1)
        }
    }
}
