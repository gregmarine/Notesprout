package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the FIFTH point's constants (arc 19 / M3) — a moved cap is a wire-contract change. */
class DocumentContractTest {

    @Test
    fun actionsAreSnNamespaced() {
        // A Paper extension on the same device must never be discovered by SN's query.
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.DOCUMENT_EDITOR",
            DocumentContract.ACTION_DOCUMENT_EDITOR,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.DOCUMENT_EDITOR_SCREEN",
            DocumentContract.ACTION_DOCUMENT_EDITOR_SCREEN,
        )
    }

    @Test
    fun documentCapMatchesTheImportCap() {
        // Locked at the M3 wizard: aligned with the 10 MB text-import byte cap (M8) — UTF-8
        // chars <= bytes, so anything the importer admits stays editable.
        assertEquals(10_000_000, DocumentContract.MAX_DOCUMENT_CHARS)
    }

    @Test
    fun chunkSizeStaysUnderTheBinderBudget() {
        // ~2 bytes per char as UTF-16 on the wire; 100k chars ≈ 200 KB against the ~1 MB budget.
        assertEquals(100_000, DocumentContract.TEXT_CHUNK_CHARS)
        assertTrue(DocumentContract.TEXT_CHUNK_CHARS * 2 < 512 * 1024)
    }

    @Test
    fun maxChunksIsComputedFromTheOtherTwo() {
        // The J6 lesson: the bound is derived, never hand-written. The `+ 1` covers the
        // surrogate backoff (a chunk may run one char short of the cap).
        assertEquals(
            DocumentContract.MAX_DOCUMENT_CHARS / DocumentContract.TEXT_CHUNK_CHARS + 1,
            DocumentContract.TEXT_MAX_CHUNKS,
        )
        // And it really is an upper bound on what the chunker can produce at the document cap:
        // worst case every chunk closes one short.
        val worstCaseChunks =
            DocumentContract.MAX_DOCUMENT_CHARS / (DocumentContract.TEXT_CHUNK_CHARS - 1) + 1
        assertTrue(worstCaseChunks <= DocumentContract.TEXT_MAX_CHUNKS)
    }

    @Test
    fun pageKeyCapHoldsAUuid() {
        assertTrue(36 <= DocumentContract.MAX_PAGE_KEY_CHARS)
        assertEquals(64, DocumentContract.MAX_PAGE_KEY_CHARS)
    }

    @Test
    fun enumConstantsAreDistinct() {
        assertTrue(DocumentContract.SCOPE_PAGE != DocumentContract.SCOPE_NOTEBOOK)
        assertEquals(
            3,
            setOf(
                DocumentContract.SOURCE_NONE,
                DocumentContract.SOURCE_DRAFTED,
                DocumentContract.SOURCE_STALE,
            ).size,
        )
        assertTrue(DocumentContract.PAGE_PREV != DocumentContract.PAGE_NEXT)
        assertTrue(DocumentContract.BRING_REPLACE != DocumentContract.BRING_APPEND)
        assertTrue(DocumentContract.CLOSE_SHOW_PAGES != DocumentContract.CLOSE_TO_LIBRARY)
    }
}
