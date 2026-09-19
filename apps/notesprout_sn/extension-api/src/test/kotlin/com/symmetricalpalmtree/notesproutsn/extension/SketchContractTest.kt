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
    fun theFloorIsTheShapeNumber() {
        // Floors pin to the number at which the point's shape was settled, never to API_VERSION
        // (the arc-31 / HV1 lesson that made CloudContractTest re-pin the cloud's to 8). Born at 17
        // (K2); 20 since arc 45 / G2, when the point's own chunk calls changed shape in place —
        // the user's decision 4, no legacy. Bumping API_VERSION again must not move this.
        assertEquals(20, SketchContract.MIN_API_VERSION_FOR_SKETCH)
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH <= ExtensionContract.API_VERSION)
    }

    @Test
    fun `the two method floors are history below the action floor`() {
        // K5b (18) and T2 (19) were METHOD floors above a 17 action floor. Since G2 moved the
        // action floor to 20, every screen the host can bind at all already clears both — they are
        // kept as the ledger of when those tails arrived, and are inert as gates.
        assertEquals(18, SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES)
        assertEquals(19, SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS)
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES < SketchContract.MIN_API_VERSION_FOR_SKETCH)
        assertTrue(SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS < SketchContract.MIN_API_VERSION_FOR_SKETCH)
        // The point is listed at its action floor and nowhere else; nothing below it binds.
        assertEquals(20, ExtensionContract.minApiVersion(SketchContract.ACTION_SKETCH))
        assertTrue(!ExtensionContract.accepts(SketchContract.ACTION_SKETCH, 19))
        assertTrue(ExtensionContract.accepts(SketchContract.ACTION_SKETCH, 20))
    }

    @Test
    fun `the two rasters are pinned, distinct and the whole list`() {
        // Arc 45 / G2: the wire names a raster by a small int. Stored nowhere (a layer is a row
        // TYPE on the host's side, not a number), but compared on every chunk call, so pinned.
        assertEquals(0, SketchContract.LAYER_GRAPHITE)
        assertEquals(1, SketchContract.LAYER_INK)
        assertEquals(listOf(0, 1), SketchContract.LAYERS)
        assertTrue(SketchContract.isLayer(SketchContract.LAYER_GRAPHITE))
        assertTrue(SketchContract.isLayer(SketchContract.LAYER_INK))
        assertTrue(!SketchContract.isLayer(-1))
        assertTrue(!SketchContract.isLayer(2))
        // Graphite first: the order the host announces and flattens them.
        assertEquals(SketchContract.LAYER_GRAPHITE, SketchContract.LAYERS.first())
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
        // 6 MiB — the SQLCipher cursor window. Above it a stored image can never be read back, which
        // is the one place in this app where refusing beats absorbing. Per raster row since G2.
        // Changing it is a data event.
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
        // SKETCH_BAD_PNG until G2 — renamed with the format; no legacy, both sides rebuilt.
        assertEquals("SKETCH_BAD_IMAGE", SketchContract.SKETCH_BAD_IMAGE)
        assertTrue(SketchContract.SKETCH_TOO_LARGE != SketchContract.SKETCH_BAD_IMAGE)
    }
}
