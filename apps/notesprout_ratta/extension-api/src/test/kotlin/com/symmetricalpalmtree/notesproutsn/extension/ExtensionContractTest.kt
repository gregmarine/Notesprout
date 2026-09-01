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
        // The declared meta-data is the version an extension REQUIRES of the host (a host accepts
        // 1..API_VERSION). 2 since arc 18 / D3 (the PDF exporter's sourceKind tail); 3 since
        // arc 19 / M8 (the text importer's ImporterInfo.resultKind tail — a version-2 host would
        // read text bytes as a .soil); 4 since arc 21 / W1 (the TAG_MANAGER point — an older host
        // knows no `ITagManager` at all); 5 since arc 21 / W4 (TagShowing's wire form — the first
        // non-tail break); 6 since arc 22 / X1 (IExtensionStore REPLACED — the second non-tail
        // break, and the first with a floor). Bumping this again is a contract event.
        assertEquals(6, ExtensionContract.API_VERSION)
        assertEquals(6, ExtensionContract.MIN_API_VERSION_FOR_STORE)
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
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD",
            ExtensionContract.ACTION_SCRATCH_PAD,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD_SCREEN",
            ExtensionContract.ACTION_SCRATCH_PAD_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER",
            ExtensionContract.ACTION_TAG_MANAGER,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER_SCREEN",
            ExtensionContract.ACTION_TAG_MANAGER_SCREEN,
        )
    }

    /** The tag caps (arc 21 / W1). They are not taste — `TagCodecTest` proves the worst legal index
     *  built from them still fits one store value, and moving one without moving that proof is how
     *  a library's tags would quietly stop fitting where they are kept. */
    @Test
    fun tagConstants() {
        assertEquals(64, ExtensionContract.MAX_TAG_CHARS)
        assertEquals(5_000, ExtensionContract.MAX_TAGS)
        assertEquals(50_000, ExtensionContract.MAX_TAG_ASSIGNMENTS)
        assertEquals(200, ExtensionContract.MAX_TARGET_LABEL_CHARS)
        assertEquals("tag index full", ExtensionContract.TAG_INDEX_FULL)
        // Since W4 a target id has no length cap of its own: it is a canonical UUID or it is not a
        // target, and 22 base64url characters is what the codec pays for one.
        assertEquals(22, CompactId.CHARS)
    }

    /** The scratch-pad transfer values: Paper's **shipped** constants (its S2 outcome), not the
     *  pre-S2 table in its plan appendix — copying the wrong ones silently halves every transfer. */
    @Test
    fun scratchPadConstants() {
        assertEquals("sendEnabled", ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED)
        assertEquals("openReceived", ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED)
        assertEquals(1, ExtensionContract.RESULT_SCRATCH_SEND)
        assertEquals(0, ExtensionContract.PLACEMENT_NEW_PAGE)
        assertEquals(1, ExtensionContract.PLACEMENT_CURRENT_PAGE)
        assertEquals(10_000, ExtensionContract.MAX_TRANSFER_STROKES)
        assertEquals(400_000, ExtensionContract.MAX_TRANSFER_POINTS)
        assertEquals(300, ExtensionContract.TRANSFER_CHUNK_STROKES)
        assertEquals(20_000, ExtensionContract.TRANSFER_CHUNK_POINTS)
        // Not a hand-picked number: the bound counts BOTH reasons a chunk closes (see the constant).
        assertEquals(74, ExtensionContract.TRANSFER_MAX_CHUNKS)
        // A chunk must never be able to exceed a whole transfer.
        assertTrue(ExtensionContract.TRANSFER_CHUNK_STROKES <= ExtensionContract.MAX_TRANSFER_STROKES)
        assertTrue(ExtensionContract.TRANSFER_CHUNK_POINTS <= ExtensionContract.MAX_TRANSFER_POINTS)
    }

    /** The store's caps and its typed messages (extensions compare them verbatim, not by substring). */
    @Test
    fun storeConstants() {
        assertEquals(512 * 1024, ExtensionContract.STORE_MAX_INLINE_BYTES)
        assertEquals(4 * 1024 * 1024, ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertEquals(32 * 1024 * 1024, ExtensionContract.STORE_MAX_RESULT_BYTES)
        assertEquals(ExtensionContract.STORE_MAX_VALUE_BYTES, ExtensionContract.STORE_MAX_ROW_BYTES)
        assertEquals(10_000, ExtensionContract.STORE_MAX_BATCH_STATEMENTS)
        assertEquals(8_192, ExtensionContract.STORE_MAX_SQL_CHARS)
        assertEquals(999, ExtensionContract.STORE_MAX_ARGS)
        assertEquals(64, ExtensionContract.STORE_MAX_TABLES)
        assertEquals(256, ExtensionContract.STORE_MAX_SCHEMA_STEPS)
        assertEquals(64, ExtensionContract.STORE_MAX_STEP_STATEMENTS)
        assertEquals(4, ExtensionContract.STORE_MAX_OPEN_RESULTS)
        assertEquals("store result large", ExtensionContract.STORE_RESULT_LARGE)
        assertEquals("store row large", ExtensionContract.STORE_ROW_LARGE)
        assertEquals("store schema newer", ExtensionContract.STORE_SCHEMA_NEWER)
        assertEquals("store schema unapplied", ExtensionContract.STORE_SCHEMA_UNAPPLIED)
        assertEquals("store results open", ExtensionContract.STORE_RESULTS_OPEN)
        // The inline carrier must be a strict subset of the region one, or a payload could be too
        // big to ride inline and too big for a region at once.
        assertTrue(ExtensionContract.STORE_MAX_INLINE_BYTES < ExtensionContract.STORE_MAX_VALUE_BYTES)
        // A chunk is a payload; a result is at least a chunk.
        assertTrue(ExtensionContract.STORE_MAX_ROW_BYTES <= ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertTrue(ExtensionContract.STORE_MAX_VALUE_BYTES <= ExtensionContract.STORE_MAX_RESULT_BYTES)
    }

    /** The floor rule (arc 22 / X1): a store-taking point's service is listed only at 6 and above;
     *  the stateless points keep accepting 1..API_VERSION. */
    @Test
    fun storeTakingPointsHaveTheFloor() {
        for (action in listOf(
            ExtensionContract.ACTION_SCRATCH_PAD,
            DocumentContract.ACTION_DOCUMENT_EDITOR,
            ExtensionContract.ACTION_TAG_MANAGER,
        )) {
            assertEquals(action, 6, ExtensionContract.minApiVersion(action))
            assertTrue(action, !ExtensionContract.accepts(action, 5))
            assertTrue(action, ExtensionContract.accepts(action, 6))
            assertTrue(action, !ExtensionContract.accepts(action, ExtensionContract.API_VERSION + 1))
        }
        for (action in listOf(
            ExtensionContract.ACTION_HANDWRITING_RECOGNIZER,
            ExporterContract.ACTION_NOTEBOOK_EXPORTER,
            ImporterContract.ACTION_NOTEBOOK_IMPORTER,
        )) {
            assertEquals(action, 1, ExtensionContract.minApiVersion(action))
            assertTrue(action, ExtensionContract.accepts(action, 1))
            assertTrue(action, ExtensionContract.accepts(action, ExtensionContract.API_VERSION))
            assertTrue(action, !ExtensionContract.accepts(action, 0))
            assertTrue(action, !ExtensionContract.accepts(action, ExtensionContract.API_VERSION + 1))
        }
    }

    @Test
    fun statusConstants() {
        assertEquals(0, RecognizerStatus.READY)
        assertEquals(1, RecognizerStatus.NEEDS_DOWNLOAD)
        assertEquals(2, RecognizerStatus.DOWNLOADING)
        assertEquals(3, RecognizerStatus.UNAVAILABLE)
    }
}
