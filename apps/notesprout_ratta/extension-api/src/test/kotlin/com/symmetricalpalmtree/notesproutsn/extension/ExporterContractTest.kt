package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exporter contract's exact values (arc 15 / E1). The action string is compared verbatim by
 * discovery and the keying ids by the host's option renderer and the E2 transform switch — a drift
 * is a silent "no exporter installed" or a mis-keyed export, so everything is pinned.
 */
class ExporterContractTest {

    @Test
    fun actionStringIsSnNamespaced() {
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.NOTEBOOK_EXPORTER",
            ExporterContract.ACTION_NOTEBOOK_EXPORTER,
        )
    }

    @Test
    fun descriptorCaps() {
        assertEquals(8, ExporterContract.MAX_OPTIONS)
        assertEquals(8, ExporterContract.MAX_CHOICES)
        assertEquals(32, ExporterContract.MAX_ID_CHARS)
        assertEquals(80, ExporterContract.MAX_LABEL_CHARS)
        assertEquals(12, ExporterContract.MAX_FILE_EXTENSION_CHARS)
        assertEquals(128, ExporterContract.MAX_MIME_CHARS)
        assertEquals(64, ExporterContract.MAX_SPEC_VALUE_CHARS)
        assertEquals(200, ExporterContract.MAX_NAME_CHARS)
    }

    @Test
    fun optionKinds() {
        assertEquals(0, ExporterContract.KIND_SINGLE_CHOICE)
        assertEquals(1, ExporterContract.KIND_TOGGLE)
        assertEquals(2, ExporterContract.KIND_PASSPHRASE)
    }

    @Test
    fun keyingIds() {
        assertEquals("keying", ExporterContract.OPTION_KEYING)
        assertEquals("keep", ExporterContract.KEYING_KEEP)
        assertEquals("rekey", ExporterContract.KEYING_REKEY)
        assertEquals("plain", ExporterContract.KEYING_PLAIN)
    }

    @Test
    fun sourceKindsAndTheTextFormatIds() {
        // The kinds are wire values (ExporterInfo's compatible tail) and the format ids are
        // host-executed twice over (assembly + destination naming) — all pinned (arc 19 / M9).
        assertEquals(0, ExporterContract.SOURCE_SOIL)
        assertEquals(1, ExporterContract.SOURCE_PAGES)
        assertEquals(2, ExporterContract.SOURCE_DOCUMENT)
        assertEquals("textFormat", ExporterContract.OPTION_TEXT_FORMAT)
        assertEquals("md", ExporterContract.TEXT_FORMAT_MARKDOWN)
        assertEquals("txt", ExporterContract.TEXT_FORMAT_PLAIN)
    }

    @Test
    fun timeouts() {
        assertEquals(3_000L, ExporterContract.DESCRIBE_TIMEOUT_MS)
        // Generous by design — a Binder call cannot be cancelled (measured: ~0.45 s per 100 MB on
        // the Nomad's flash; two minutes covers 1 GB at 10 MB/s through a slow provider).
        assertEquals(120_000L, ExporterContract.EXPORT_TIMEOUT_MS)
        assertTrue(ExporterContract.EXPORT_TIMEOUT_MS > ExporterContract.DESCRIBE_TIMEOUT_MS)
    }
}
