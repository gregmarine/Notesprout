package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The importer contract's exact values. The action string is compared verbatim at runtime
 * (discovery filters) — a drift here is a silent "no extension installed", so it is pinned by
 * test, same as [ExtensionContractTest]. The importer point shares its caps with
 * [ExporterContract] by reference — one set of bounds for both directions of the same seam.
 */
class ImporterContractTest {

    @Test
    fun actionStringIsSnNamespaced() {
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.NOTEBOOK_IMPORTER",
            ImporterContract.ACTION_NOTEBOOK_IMPORTER,
        )
    }

    @Test
    fun descriptorCaps() {
        assertEquals(8, ImporterContract.MAX_FILE_EXTENSIONS)
        assertEquals(8, ImporterContract.MAX_MIME_TYPES)
    }

    @Test
    fun timeoutsMirrorTheExporterContract() {
        assertEquals(ExporterContract.DESCRIBE_TIMEOUT_MS, ImporterContract.DESCRIBE_TIMEOUT_MS)
        assertEquals(ExporterContract.EXPORT_TIMEOUT_MS, ImporterContract.IMPORT_TIMEOUT_MS)
    }
}
