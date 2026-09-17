package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the TENTH point's constants (arc 43 / K2) — a moved cap is a wire-contract change. */
class SketchContractTest {

    @Test
    fun actionsAreSnNamespaced() {
        // A Paper extension on the same device must never be discovered by SN's query.
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SKETCH",
            SketchContract.ACTION_SKETCH,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SKETCH_SCREEN",
            SketchContract.ACTION_SKETCH_SCREEN,
        )
    }

    @Test
    fun theFloorIsTheBirthNumber() {
        // Floors pin to their birth number, never to API_VERSION (the arc-31 / HV1 lesson that made
        // CloudContractTest re-pin the cloud's to 8). Bumping API_VERSION must not move this.
        assertEquals(17, SketchContract.MIN_API_VERSION_FOR_SKETCH)
    }

    @Test
    fun `the page method floor is its own birth number, above the point's`() {
        // K5b: insertPage / deletePage / pageContent are a METHOD floor, not an action floor. The
        // point's own floor must not have moved with it — a screen declaring 17 still binds and
        // still draws, it simply never makes a page.
        assertEquals(18, SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES)
        assertEquals(17, SketchContract.MIN_API_VERSION_FOR_SKETCH)
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES > SketchContract.MIN_API_VERSION_FOR_SKETCH)
        // A floor can never ask for a host that does not exist yet.
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES <= ExtensionContract.API_VERSION)
    }

    @Test
    fun `the tools method floor is its own birth number, above the pages'`() {
        // Arc 44 / T2: toolSettings / putToolSettings are a METHOD floor. Neither earlier floor
        // moved — a screen declaring 18 still binds, draws and turns pages; it just forgets its
        // tools between showings.
        assertEquals(19, SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS)
        assertEquals(18, SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES)
        assertEquals(17, SketchContract.MIN_API_VERSION_FOR_SKETCH)
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS <= ExtensionContract.API_VERSION)
        // The point is still listed at its birth floor and nowhere else.
        assertEquals(17, ExtensionContract.minApiVersion(SketchContract.ACTION_SKETCH))
        assertTrue(ExtensionContract.accepts(SketchContract.ACTION_SKETCH, 18))
    }

    @Test
    fun `the tool numbers are pinned and distinct`() {
        // Stored in the host's prefs across app updates — a renumbering would re-arm the wrong tool.
        assertEquals(0, SketchContract.TOOL_PENCIL)
        assertEquals(1, SketchContract.TOOL_PEN)
        // A sanity bound on an unmarshalled int, not the palette's size (that is :ext-sketch's).
        assertEquals(255, SketchContract.MAX_TOOL_SETTING_INDEX)
        assertTrue(SketchContract.TOOL_PEN <= SketchContract.MAX_TOOL_SETTING_INDEX)
    }

    @Test
    fun `the structural token is bounded well above what the host mints`() {
        // The host's tokens are "s1", "s2", … — the bound is a guard on an unmarshalled string, not
        // a budget, and it must never be so tight that a long showing runs into it.
        assertEquals(64, SketchContract.MAX_STRUCTURAL_TOKEN_CHARS)
        assertTrue("s${Int.MAX_VALUE}".length < SketchContract.MAX_STRUCTURAL_TOKEN_CHARS)
    }

    @Test
    fun `the page-content bits are disjoint single bits`() {
        // They are OR-ed into one int and read back with `and`, so each must be exactly one bit and
        // no two may be the same one. Zero is the meaningful answer "nothing else is on this page".
        assertEquals(1, SketchContract.PAGE_HAS_INK)
        assertEquals(2, SketchContract.PAGE_HAS_DOCUMENT)
        assertEquals(0, SketchContract.PAGE_HAS_INK and SketchContract.PAGE_HAS_DOCUMENT)
        for (bit in listOf(SketchContract.PAGE_HAS_INK, SketchContract.PAGE_HAS_DOCUMENT)) {
            assertEquals("a bit must be a single bit", 0, bit and (bit - 1))
        }
        // Both together still read back as both — the "handwriting and document" wording's case.
        val both = SketchContract.PAGE_HAS_INK or SketchContract.PAGE_HAS_DOCUMENT
        assertTrue(both and SketchContract.PAGE_HAS_INK != 0)
        assertTrue(both and SketchContract.PAGE_HAS_DOCUMENT != 0)
    }

    @Test
    fun theChunkSizeStaysUnderTheBinderBudget() {
        assertEquals(512 * 1024, SketchContract.SKETCH_CHUNK_BYTES)
        assertTrue(SketchContract.SKETCH_CHUNK_BYTES < 1024 * 1024)
    }

    @Test
    fun theHardRefusalIsTheCursorWindow() {
        // 6 MiB — the SQLCipher cursor window. Above it a stored PNG can never be read back, which
        // is the one place in this app where refusing beats absorbing. Changing it is a data event.
        assertEquals(6 * 1024 * 1024, SketchContract.MAX_BYTES)
        // The watch line is quieter and strictly below the refusal, or it would never be reached.
        assertEquals(4_000_000, SketchContract.WATCH_BYTES)
        assertTrue(SketchContract.WATCH_BYTES < SketchContract.MAX_BYTES)
        // A chunk must never be able to exceed a whole image.
        assertTrue(SketchContract.SKETCH_CHUNK_BYTES <= SketchContract.MAX_BYTES)
    }

    @Test
    fun maxChunksIsComputedFromTheOtherTwo() {
        // The J6 lesson: the bound is derived, never hand-written.
        assertEquals(
            SketchContract.MAX_BYTES / SketchContract.SKETCH_CHUNK_BYTES + 1,
            SketchContract.MAX_CHUNKS,
        )
        assertEquals(13, SketchContract.MAX_CHUNKS)
        // And it really does bound what the chunker produces, at the cap and one under it.
        assertTrue(ByteChunks.countFor(SketchContract.MAX_BYTES) <= SketchContract.MAX_CHUNKS)
        assertTrue(ByteChunks.countFor(SketchContract.MAX_BYTES - 1) <= SketchContract.MAX_CHUNKS)
    }

    @Test
    fun thePageKeyRuleIsTheDocumentEditorsOwnConstant() {
        // Not a copy of the number: two constants that must agree are one constant.
        assertEquals(DocumentContract.MAX_PAGE_KEY_CHARS, SketchContract.MAX_PAGE_KEY_CHARS)
        assertEquals(64, SketchContract.MAX_PAGE_KEY_CHARS)
    }

    @Test
    fun theFlipDirectionsAreTheDocumentEditorsOwn() {
        // One wire vocabulary for "a page flip" across the whole seam.
        assertEquals(DocumentContract.PAGE_PREV, SketchContract.PAGE_PREV)
        assertEquals(DocumentContract.PAGE_NEXT, SketchContract.PAGE_NEXT)
        assertEquals(-1, SketchContract.PAGE_PREV)
        assertEquals(1, SketchContract.PAGE_NEXT)
    }

    @Test
    fun thePageBoundsAreASanityCheckNotADeviceLimit() {
        assertEquals(1, SketchContract.MIN_PAGE_PX)
        assertEquals(8192, SketchContract.MAX_PAGE_PX)
        // Every device this app runs on sits comfortably inside: the Nomad 1404×1685, the Manta
        // 1860×2480. The bound is what says a number is *wrong*, not what says a page is big.
        assertTrue(2480 < SketchContract.MAX_PAGE_PX)
    }

    @Test
    fun theShowPagesResultIsTheFirstUserCode() {
        // = android.app.Activity.RESULT_FIRST_USER, pinned as a literal so this stays a plain JVM
        // test (the module has no Robolectric and no returnDefaultValues).
        assertEquals(1, SketchContract.RESULT_SKETCH_SHOW_PAGES)
        // …and it is the same code every other screen-owning point's first result uses.
        assertEquals(ExtensionContract.RESULT_SCRATCH_SEND, SketchContract.RESULT_SKETCH_SHOW_PAGES)
    }

    @Test
    fun theTypedRefusalsArePinnedVerbatim() {
        // Compared with == on both sides — never `contains`, never a prefix.
        assertEquals("SKETCH_TOO_LARGE", SketchContract.SKETCH_TOO_LARGE)
        assertEquals("SKETCH_BAD_PNG", SketchContract.SKETCH_BAD_PNG)
        assertTrue(SketchContract.SKETCH_TOO_LARGE != SketchContract.SKETCH_BAD_PNG)
    }
}
