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
        sketchBytes: Int = 0,
        sketchChunks: Int = 1,
        structuralToken: String = "",
    ) = SketchPageState(
        pageKey, pageIndex, pageCount, width, height, sketchBytes, sketchChunks, structuralToken,
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
        val s = state(sketchBytes = 4_000, sketchChunks = 1)
        assertEquals("page-1", s.pageKey)
        assertEquals(1404, s.width)
        assertEquals(1685, s.height)
        assertEquals(1, s.sketchChunks)
    }

    @Test
    fun anAbsentSketchIsZeroBytesInOneChunk() {
        val s = state()
        assertFalse(s.hasSketch)
        assertEquals(0, s.sketchBytes)
        assertEquals(1, s.sketchChunks)
        assertTrue(state(sketchBytes = 1, sketchChunks = 1).hasSketch)
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
    fun theByteTotalIsBoundedByTheHardRefusal() {
        val max = SketchContract.MAX_BYTES
        state(sketchBytes = max, sketchChunks = ByteChunks.countFor(max))
        assertRefused { state(sketchBytes = max + 1, sketchChunks = SketchContract.MAX_CHUNKS) }
        assertRefused { state(sketchBytes = -1) }
    }

    @Test
    fun theChunkCountIsBounded() {
        assertRefused { state(sketchBytes = 0, sketchChunks = 0) }
        assertRefused {
            state(sketchBytes = SketchContract.MAX_BYTES, sketchChunks = SketchContract.MAX_CHUNKS + 1)
        }
    }

    @Test
    fun anEmptySketchIsExactlyOneChunk() {
        assertRefused { state(sketchBytes = 0, sketchChunks = 2) }
        state(sketchBytes = 0, sketchChunks = 1)
    }

    @Test
    fun theChunkCountMustMatchTheByteTotal() {
        // The relation is pinned rather than recomputed at every call site: a hand-built state can
        // never disagree with the chunker that will actually serve it.
        val cap = SketchContract.SKETCH_CHUNK_BYTES
        state(sketchBytes = cap, sketchChunks = 1)
        state(sketchBytes = cap + 1, sketchChunks = 2)
        assertRefused { state(sketchBytes = cap + 1, sketchChunks = 1) }
        assertRefused { state(sketchBytes = cap, sketchChunks = 2) }
    }

    // ── The structural token, K5b's compatible tail ──────

    @Test
    fun theStructuralTokenDefaultsToEmpty() {
        // The default is what makes the tail compatible in Kotlin as well as on the wire: every K5
        // call site constructs a state without it and means exactly what it meant — "this answer is
        // not a page insert or delete". The same value `read()` builds from an exhausted parcel,
        // where `readString()` answers null.
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
