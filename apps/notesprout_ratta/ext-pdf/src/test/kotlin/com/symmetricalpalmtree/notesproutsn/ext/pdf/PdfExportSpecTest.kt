package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules this exporter enforces on its way in — refuse what this build cannot actually do. */
class PdfExportSpecTest {

    private val template = ExporterContract.OPTION_PAGE_TEMPLATE
    private val protect = ExporterContract.OPTION_PROTECT

    @Test
    fun theSupportedSetIsExactlyTheDescriptorsPair() {
        // A control exists in both places or in neither: a supported id the descriptor never offers
        // could only arrive from a host that made it up, and an offered id missing here would be
        // refused the moment a user touched it.
        assertEquals(PdfDescriptor.options.map { it.id }.toSet(), PdfExportSpec.SUPPORTED_OPTIONS)
    }

    @Test
    fun theDeclaredOptionsAreAccepted() {
        PdfExportSpec.require(mapOf(template to "1", protect to "0"), null)
        PdfExportSpec.require(mapOf(template to "0", protect to "0"), null)
        PdfExportSpec.require(mapOf(template to "0"), null)
        PdfExportSpec.require(emptyMap(), null)
    }

    @Test
    fun anOptionThisBuildCannotActOnIsRefused() {
        // Not ignored, unlike the soil exporter's host-executed options: these name work that has
        // to happen, so silence would ship a PDF that is not the one asked for.
        for (values in listOf(
            mapOf("keying" to "keep"),
            mapOf(template to "1", "future" to "x"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfExportSpec.require(values, null)
            }
        }
    }

    @Test
    fun theRefusalNamesTheOptionIdsAndNothingElse() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PdfExportSpec.require(mapOf("shred" to "1", "watermark" to "0"), null)
        }
        val message = e.message ?: ""
        // Ids, sorted, and no value: a value is the caller's data, an id is the contract.
        assertTrue(message.contains("shred"))
        assertTrue(message.contains("watermark"))
        assertTrue(message.indexOf("shred") < message.indexOf("watermark"))
        assertTrue("=" !in message && "0" !in message && "1" !in message)
    }

    // ── The toggle and the secret are one answer in two pieces (D2) ──────────

    @Test
    fun armedProtectWithNoSecretIsRefused() {
        // The dangerous half: exporting anyway would hand back an unprotected PDF the user believes
        // is locked, and report it as a success.
        assertThrows(IllegalArgumentException::class.java) {
            PdfExportSpec.require(mapOf(protect to "1"), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfExportSpec.require(mapOf(template to "1", protect to "1"), null)
        }
    }

    @Test
    fun aSecretNothingAskedForIsRefused() {
        for (values in listOf(emptyMap(), mapOf(protect to "0"), mapOf(template to "1"))) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                PdfExportSpec.require(values, "hunter2")
            }
            // The refusal never echoes the secret, in any form — not its text, not its length.
            val message = e.message ?: ""
            assertTrue("hunter2" !in message)
            assertTrue("7" !in message)
        }
    }

    @Test
    fun armedProtectWithASecretIsTheProtectedExport() {
        PdfExportSpec.require(mapOf(protect to "1"), "hunter2")
        PdfExportSpec.require(mapOf(template to "0", protect to "1"), "hunter2")
    }
}
