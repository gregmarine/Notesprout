package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract's exact values. The action string and the not-ready message are compared verbatim at
 * runtime (discovery filters, `RecognizerClient`'s message match) — a drift here is a silent
 * "no extension installed", so the strings are pinned by test.
 */
class ExtensionContractTest {

    @Test
    fun contractConstants() {
        assertEquals(1, ExtensionContract.API_VERSION)
        assertEquals(2_000, ExtensionContract.MAX_INK_STROKES)
        assertEquals(60_000, ExtensionContract.MAX_INK_POINTS)
        assertEquals(20, ExtensionContract.MAX_PRECONTEXT_CHARS)
        assertEquals(20_000, ExtensionContract.MAX_RECOGNIZED_CHARS)
        assertEquals("recognizer not ready", ExtensionContract.RECOGNIZER_NOT_READY)
    }

    @Test
    fun actionStringsAreSnNamespaced() {
        // SN and Paper extensions coexist on the Nomad under the same debug signature — only the
        // namespace keeps each family discovering its own.
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.HANDWRITING_RECOGNIZER",
            ExtensionContract.ACTION_HANDWRITING_RECOGNIZER,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.API_VERSION",
            ExtensionContract.META_API_VERSION,
        )
    }

    /** The store's caps and its one exact message (extensions compare it verbatim, not by substring). */
    @Test
    fun storeConstants() {
        assertEquals(512, ExtensionContract.STORE_MAX_KEY_CHARS)
        assertEquals(4 * 1024 * 1024, ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertEquals(512 * 1024, ExtensionContract.STORE_MAX_INLINE_BYTES)
        assertEquals(50_000, ExtensionContract.STORE_MAX_KEYS)
        assertEquals("value is large — use getLarge", ExtensionContract.STORE_VALUE_LARGE)
        // The inline path must be a strict subset of the large one, or `get` could refuse a value
        // `put` was willing to take.
        assertTrue(ExtensionContract.STORE_MAX_INLINE_BYTES < ExtensionContract.STORE_MAX_VALUE_BYTES)
    }

    @Test
    fun statusConstants() {
        assertEquals(0, RecognizerStatus.READY)
        assertEquals(1, RecognizerStatus.NEEDS_DOWNLOAD)
        assertEquals(2, RecognizerStatus.DOWNLOADING)
        assertEquals(3, RecognizerStatus.UNAVAILABLE)
    }
}
