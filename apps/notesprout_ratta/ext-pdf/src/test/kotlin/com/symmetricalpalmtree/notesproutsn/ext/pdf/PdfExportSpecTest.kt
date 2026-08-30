package com.symmetricalpalmtree.notesproutsn.ext.pdf

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules this exporter enforces on its way in — refuse what this build cannot actually do. */
class PdfExportSpecTest {

    @Test
    fun anEmptySpecIsTheOnlyOneD1Offers() {
        PdfExportSpec.require(emptyMap(), null)
    }

    @Test
    fun d1DeclaresNoOptions() {
        // The descriptor and this set flip together in D2 — a control exists in both or in neither.
        assertTrue(PdfExportSpec.SUPPORTED_OPTIONS.isEmpty())
    }

    @Test
    fun anOptionThisBuildCannotActOnIsRefused() {
        // Not ignored, unlike the soil exporter's host-executed options: these name work THIS side
        // would have to do, so silence would ship a PDF that is not the one asked for.
        for (values in listOf(
            mapOf("template" to "1"),
            mapOf("protect" to "0"),
            mapOf("template" to "1", "future" to "x"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfExportSpec.require(values, null)
            }
        }
    }

    @Test
    fun theRefusalNamesTheOptionIdsAndNothingElse() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PdfExportSpec.require(mapOf("protect" to "1", "template" to "0"), null)
        }
        val message = e.message ?: ""
        // Ids, sorted, and no value: a value is the caller's data, an id is the contract.
        assertTrue(message.contains("protect"))
        assertTrue(message.contains("template"))
        assertTrue(message.indexOf("protect") < message.indexOf("template"))
        assertTrue("=" !in message && "0" !in message && "1" !in message)
    }

    @Test
    fun anExportSecretIsRefusedUntilD2CanServeIt() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PdfExportSpec.require(emptyMap(), "hunter2")
        }
        // The refusal never echoes the secret, in any form.
        assertTrue("hunter2" !in (e.message ?: ""))
    }
}
