package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sketch state parcelable's constructor `require`s — unmarshal is the validation (family rule),
 * and because they run in `init` there is no way for `read(parcel)` to build an invalid instance.
 *
 * **A real `Parcel` round trip is not available here** (the [DocumentPageStateTest] note):
 * `:extension-api` runs plain JVM tests with no Robolectric and no `returnDefaultValues`, so
 * `Parcel.obtain()` throws "not mocked". What can be pinned is the shape either side of the wire —
 * the field order in `writeToParcel` mirrored by `read` — and every rule the constructor enforces.
 */
class SketchPageStateTest {

    private fun state(
        pageKey: String = "page-1",
        pageIndex: Int = 0,
        pageCount: Int = 3,
        width: Int = 1404,
        height: Int = 1685,
        graphiteBytes: Int = 0,
        graphiteChunks: Int = 1,
        inkBytes: Int = 0,
        inkChunks: Int = 1,
        structuralToken: String = "",
    ) = SketchPageState(
        pageKey, pageIndex, pageCount, width, height,
        graphiteBytes, graphiteChunks, inkBytes, inkChunks, structuralToken,
    )

    private fun assertRefused(build: () -> SketchPageState) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aPlainStateHolds() {
        val s = state(graphiteBytes = 4_000, graphiteChunks = 1, inkBytes = 900, inkChunks = 1)
        assertEquals("page-1", s.pageKey)
        assertEquals(1404, s.width)
        assertEquals(1685, s.height)
        assertEquals(4_000, s.graphiteBytes)
        assertEquals(1, s.graphiteChunks)
        assertEquals(900, s.inkBytes)
        assertEquals(1, s.inkChunks)
    }

    @Test
    fun anAbsentRasterIsZeroBytesInOneChunk() {
        val s = state()
        assertFalse(s.hasSketch)
        assertEquals(0, s.graphiteBytes)
        assertEquals(1, s.graphiteChunks)
        assertEquals(0, s.inkBytes)
        assertEquals(1, s.inkChunks)
    }

    @Test
    fun hasSketchIsEitherRasterLive() {
        // Blank means absent, per raster (G2): a page with only ink has no graphite row and is
        // still a sketched page — "start from blank paper" is false for it.
        assertTrue(state(graphiteBytes = 1).hasSketch)
        assertTrue(state(inkBytes = 1).hasSketch)
        assertTrue(state(graphiteBytes = 1, inkBytes = 1).hasSketch)
        assertFalse(state().hasSketch)
    }

    @Test
    fun thePerLayerReadsAnswerByLayerAndRefuseTheRest() {
        val s = state(graphiteBytes = 4_000, inkBytes = SketchContract.SKETCH_CHUNK_BYTES + 1, inkChunks = 2)
        assertEquals(4_000, s.bytesFor(SketchContract.LAYER_GRAPHITE))
        assertEquals(1, s.chunksOf(SketchContract.LAYER_GRAPHITE))
        assertEquals(SketchContract.SKETCH_CHUNK_BYTES + 1, s.bytesFor(SketchContract.LAYER_INK))
        assertEquals(2, s.chunksOf(SketchContract.LAYER_INK))
        assertTrue(s.hasLayer(SketchContract.LAYER_GRAPHITE))
        assertTrue(s.hasLayer(SketchContract.LAYER_INK))
        assertFalse(state(inkBytes = 1).hasLayer(SketchContract.LAYER_GRAPHITE))
        for (bad in listOf(-1, 2)) {
            try { s.bytesFor(bad); fail("bytesFor($bad)") } catch (expected: IllegalArgumentException) {}
            try { s.chunksOf(bad); fail("chunksOf($bad)") } catch (expected: IllegalArgumentException) {}
        }
    }

    @Test
    fun pageKeyIsBoundedAndPathFree() {
        assertRefused { state(pageKey = "") }
        assertRefused { state(pageKey = "k".repeat(SketchContract.MAX_PAGE_KEY_CHARS + 1)) }
        assertRefused { state(pageKey = "a/b") }
        assertRefused { state(pageKey = "a\u0000b") }
        state(pageKey = "k".repeat(SketchContract.MAX_PAGE_KEY_CHARS))
    }

    @Test
    fun pageCountMustBeAtLeastOne() {
        assertRefused { state(pageCount = 0, pageIndex = 0) }
        assertRefused { state(pageCount = -1, pageIndex = 0) }
        state(pageCount = 1, pageIndex = 0)
    }

    @Test
    fun pageIndexMustSitInsideTheCount() {
        // Unlike the document editor's, there is no notebook scope here: a sketch is always a
        // page's, so −1 is never legal.
        assertRefused { state(pageIndex = 3, pageCount = 3) }
        assertRefused { state(pageIndex = -1, pageCount = 3) }
        state(pageIndex = 2, pageCount = 3)
    }

    @Test
    fun theSizeIsBoundedInBothDimensions() {
        assertRefused { state(width = SketchContract.MIN_PAGE_PX - 1) }
        assertRefused { state(width = SketchContract.MAX_PAGE_PX + 1) }
        assertRefused { state(height = SketchContract.MIN_PAGE_PX - 1) }
        assertRefused { state(height = SketchContract.MAX_PAGE_PX + 1) }
        state(width = SketchContract.MIN_PAGE_PX, height = SketchContract.MIN_PAGE_PX)
        state(width = SketchContract.MAX_PAGE_PX, height = SketchContract.MAX_PAGE_PX)
    }

    @Test
    fun eachByteTotalIsBoundedByTheHardRefusal() {
        // The refusal is per raster (decision 3): each stream may reach the cap on its own, and
        // two rasters at the cap together are a legal state.
        val max = SketchContract.MAX_BYTES
        val chunks = ByteChunks.countFor(max)
        state(graphiteBytes = max, graphiteChunks = chunks)
        state(inkBytes = max, inkChunks = chunks)
        state(graphiteBytes = max, graphiteChunks = chunks, inkBytes = max, inkChunks = chunks)
        assertRefused { state(graphiteBytes = max + 1, graphiteChunks = SketchContract.MAX_CHUNKS) }
        assertRefused { state(inkBytes = max + 1, inkChunks = SketchContract.MAX_CHUNKS) }
        assertRefused { state(graphiteBytes = -1) }
        assertRefused { state(inkBytes = -1) }
    }

    @Test
    fun eachChunkCountIsBounded() {
        assertRefused { state(graphiteBytes = 0, graphiteChunks = 0) }
        assertRefused { state(inkBytes = 0, inkChunks = 0) }
        assertRefused {
            state(graphiteBytes = SketchContract.MAX_BYTES, graphiteChunks = SketchContract.MAX_CHUNKS + 1)
        }
        assertRefused {
            state(inkBytes = SketchContract.MAX_BYTES, inkChunks = SketchContract.MAX_CHUNKS + 1)
        }
    }

    @Test
    fun anEmptyRasterIsExactlyOneChunk() {
        assertRefused { state(graphiteBytes = 0, graphiteChunks = 2) }
        assertRefused { state(inkBytes = 0, inkChunks = 2) }
        state(graphiteBytes = 0, graphiteChunks = 1, inkBytes = 0, inkChunks = 1)
    }

    @Test
    fun eachChunkCountMustMatchItsByteTotal() {
        // The relation is pinned rather than recomputed at every call site: a hand-built state can
        // never disagree with the chunker that will actually serve it — and each stream is pinned
        // to its OWN total, never the other's.
        val cap = SketchContract.SKETCH_CHUNK_BYTES
        state(graphiteBytes = cap, graphiteChunks = 1)
        state(graphiteBytes = cap + 1, graphiteChunks = 2)
        state(inkBytes = cap, inkChunks = 1)
        state(inkBytes = cap + 1, inkChunks = 2)
        assertRefused { state(graphiteBytes = cap + 1, graphiteChunks = 1) }
        assertRefused { state(graphiteBytes = cap, graphiteChunks = 2) }
        assertRefused { state(inkBytes = cap + 1, inkChunks = 1) }
        assertRefused { state(inkBytes = cap, inkChunks = 2) }
        // Crossed: a graphite count that would fit the ink total is still wrong.
        assertRefused { state(graphiteBytes = cap, graphiteChunks = 2, inkBytes = cap + 1, inkChunks = 2) }
    }

    // ── The structural token, K5b's compatible tail ──────

    @Test
    fun theStructuralTokenDefaultsToEmpty() {
        // The default is what makes the tail compatible in Kotlin as well as on the wire: a call
        // site constructing a state without it means exactly "this answer is not a page insert or
        // delete". The same value `read()` builds from an exhausted parcel, where `readString()`
        // answers null. (G2 reshaped the fields BEFORE it and moved the action floor for that; the
        // token stays the last field so the next tail is compatible again.)
        assertEquals("", state().structuralToken)
        assertEquals("s7", state(structuralToken = "s7").structuralToken)
    }

    @Test
    fun theStructuralTokenIsBoundedAndOneWord() {
        assertRefused { state(structuralToken = "k".repeat(SketchContract.MAX_STRUCTURAL_TOKEN_CHARS + 1)) }
        state(structuralToken = "k".repeat(SketchContract.MAX_STRUCTURAL_TOKEN_CHARS))
        // A SPACE, not NUL — a token is one word in a log line. The distinction is the point of the
        // check, so both are pinned: the space is refused, and nothing else here pretends to be it.
        assertRefused { state(structuralToken = "s 7") }
        assertRefused { state(structuralToken = " ") }
        state(structuralToken = "s-7_a.b")
    }

    @Test
    fun chunksForIsTheChunkersOwnArithmetic() {
        assertEquals(1, SketchPageState.chunksFor(0))
        for (n in listOf(0, 1, SketchContract.SKETCH_CHUNK_BYTES, SketchContract.MAX_BYTES)) {
            assertEquals("n=$n", ByteChunks.countFor(n), SketchPageState.chunksFor(n))
        }
    }
}
