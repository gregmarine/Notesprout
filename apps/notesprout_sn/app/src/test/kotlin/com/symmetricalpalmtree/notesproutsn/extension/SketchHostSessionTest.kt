package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pure half of the host's `ISketchHost` stub (arc 43 / K4; two windows and two accumulators
 * since arc 45 / G2) — the read windows, the ink window and the save accumulators.
 * `DocumentHostSessionTest`'s shape, and it pins the two places this seam genuinely differs: **a
 * sketch save is accepted for any page, not only the window's**, and **the two rasters are
 * independent of each other in both directions**.
 */
class SketchHostSessionTest {

    private val key = "page-1"
    private val graphite = SketchContract.LAYER_GRAPHITE
    private val ink = SketchContract.LAYER_INK

    private fun bytes(n: Int, fill: Byte = 7): ByteArray = ByteArray(n) { fill }

    private fun session(
        graphiteBytes: ByteArray = bytes(10),
        inkBytes: ByteArray = ByteArray(0),
    ): SketchHostSession {
        val s = SketchHostSession()
        s.setWindows(key, graphiteBytes, inkBytes)
        return s
    }

    private fun stroke(n: Int = 2) = WireStroke(
        FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), 3f, -0x1000000, "PEN",
    )

    // ── The two read windows ──────

    @Test
    fun bothWindowsServeTheirChunksAndRefuseOutside() {
        val s = session(bytes(10, 1), bytes(20, 2))
        assertEquals(key, s.currentKey)
        assertEquals(10, s.windowByteCount(graphite))
        assertEquals(20, s.windowByteCount(ink))
        assertEquals(1, s.readChunk(graphite, 0)[0].toInt())
        assertEquals(2, s.readChunk(ink, 0)[0].toInt())
        for (layer in SketchContract.LAYERS) {
            try { s.readChunk(layer, 1); fail() } catch (expected: IllegalArgumentException) {}
            try { s.readChunk(layer, -1); fail() } catch (expected: IllegalArgumentException) {}
        }
    }

    /** One call loads both, and its one answer carries both counts — the contract's atomicity, and
     *  the reason there is no `setWindow(layer, …)` to get half-called. */
    @Test
    fun setWindowsIsAtomicAndAnswersBothCounts() {
        val s = SketchHostSession()
        val big = bytes(SketchContract.SKETCH_CHUNK_BYTES * 2 + 5)
        val windows = s.setWindows(key, big, bytes(4))
        assertEquals(3, windows.graphiteChunks)
        assertEquals(1, windows.inkChunks)
        assertEquals(big.size, s.windowByteCount(graphite))
        assertEquals(4, s.windowByteCount(ink))
    }

    @Test
    fun anEmptyRasterIsOneEmptyChunk() {
        val s = SketchHostSession()
        val windows = s.setWindows(key, ByteArray(0), ByteArray(0))
        assertEquals(1, windows.graphiteChunks)
        assertEquals(1, windows.inkChunks)
        for (layer in SketchContract.LAYERS) {
            assertEquals(0, s.readChunk(layer, 0).size)
            assertEquals(0, s.windowByteCount(layer))
        }
    }

    @Test
    fun aLargeRasterChunksAndReassembles() {
        val image = bytes(SketchContract.SKETCH_CHUNK_BYTES * 2 + 5)
        val s = SketchHostSession()
        val n = s.setWindows(key, image, image).inkChunks
        assertEquals(3, n)
        assertArrayEquals(image, ByteChunks.join((0 until n).map { s.readChunk(ink, it) }))
    }

    @Test
    fun aWindowOverTheCapIsRefused() {
        val s = SketchHostSession()
        try {
            s.setWindows(key, bytes(SketchContract.MAX_BYTES + 1), ByteArray(0))
            fail()
        } catch (expected: IllegalArgumentException) {}
        try {
            s.setWindows(key, ByteArray(0), bytes(SketchContract.MAX_BYTES + 1))
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun aMalformedKeyIsRefusedOnTheWindowsAndOnASave() {
        val s = SketchHostSession()
        try { s.setWindows("", bytes(4), bytes(4)); fail() } catch (expected: IllegalArgumentException) {}
        try { s.setWindows("a/b", bytes(4), bytes(4)); fail() } catch (expected: IllegalArgumentException) {}
        try {
            s.setWindows("x".repeat(SketchContract.MAX_PAGE_KEY_CHARS + 1), bytes(4), bytes(4))
            fail()
        } catch (expected: IllegalArgumentException) {}
        try {
            s.acceptChunk("a/b", graphite, 0, bytes(4), last = true)
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    // ── The layer itself ──────

    /** An unknown layer is refused on **every** call that names one, and before anything else about
     *  the call is read — a malformed key on an unknown layer is still a layer problem. */
    @Test
    fun anUnknownLayerIsRefusedOnEveryCall() {
        val s = session()
        for (layer in listOf(-1, 2, 99)) {
            try { s.readChunk(layer, 0); fail() } catch (expected: IllegalArgumentException) {}
            try { s.windowByteCount(layer); fail() } catch (expected: IllegalArgumentException) {}
            try { s.pendingSaveBytes(layer); fail() } catch (expected: IllegalArgumentException) {}
            try {
                s.acceptChunk("a/b", layer, 0, bytes(4), last = true)
                fail()
            } catch (expected: IllegalArgumentException) {}
        }
    }

    // ── The save accumulators ──────

    @Test
    fun aOneChunkSaveCommitsOnTheLayerItNamed() {
        for (layer in SketchContract.LAYERS) {
            val image = bytes(12, 3)
            val commit = session().acceptChunk(key, layer, 0, image, last = true)
            assertNotNull(commit)
            assertEquals(key, commit!!.pageKey)
            assertEquals(layer, commit.layer)
            assertArrayEquals(image, commit.bytes)
        }
    }

    @Test
    fun aMultiChunkSaveCommitsTheJoinedBytes() {
        val s = session()
        val a = bytes(SketchContract.SKETCH_CHUNK_BYTES, 1)
        val b = bytes(9, 2)
        assertNull(s.acceptChunk(key, ink, 0, a, last = false))
        val commit = s.acceptChunk(key, ink, 1, b, last = true)
        assertNotNull(commit)
        assertArrayEquals(ByteChunks.join(listOf(a, b)), commit!!.bytes)
    }

    @Test
    fun anEmptyLastChunkIsTheClear() {
        val commit = session().acceptChunk(key, ink, 0, ByteArray(0), last = true)
        assertNotNull(commit)
        assertEquals(0, commit!!.bytes.size)
        assertEquals(key, commit.pageKey)
        assertEquals(ink, commit.layer)
    }

    @Test
    fun aSaveForAnyPageIsAcceptedNotOnlyTheWindowsOwn() {
        // The one place this seam differs from the document editor's: the face turns its own pages,
        // so a flush a moment after a turn still belongs to the page it was drawn on.
        val s = session()
        val commit = s.acceptChunk("some-other-page", graphite, 0, bytes(4), last = true)
        assertNotNull(commit)
        assertEquals("some-other-page", commit!!.pageKey)
        // …and the windows are untouched by it.
        assertEquals(key, s.currentKey)
    }

    @Test
    fun anOutOfOrderChunkIsRefusedAndResets() {
        val s = session()
        assertNull(s.acceptChunk(key, graphite, 0, bytes(4), last = false))
        try {
            s.acceptChunk(key, graphite, 2, bytes(4), last = false)
            fail()
        } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes(graphite))
        // The restart is chunk 0 again, and it commits on its own.
        val commit = s.acceptChunk(key, graphite, 0, bytes(5, 9), last = true)
        assertNotNull(commit)
        assertEquals(5, commit!!.bytes.size)
    }

    @Test
    fun aKeyThatChangesMidSaveIsRefusedAndResets() {
        val s = session()
        assertNull(s.acceptChunk(key, graphite, 0, bytes(4), last = false))
        try {
            s.acceptChunk("page-2", graphite, 1, bytes(4), last = true)
            fail()
        } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes(graphite))
    }

    @Test
    fun anOversizeChunkIsRefusedAndResets() {
        val s = session()
        try {
            s.acceptChunk(key, ink, 0, bytes(SketchContract.SKETCH_CHUNK_BYTES + 1), last = true)
            fail()
        } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes(ink))
    }

    @Test
    fun aSaveOverTheCapThrowsTheTypedRefusalAndWritesNothing() {
        val s = session()
        val full = bytes(SketchContract.SKETCH_CHUNK_BYTES)
        var i = 0
        while (i < SketchContract.MAX_CHUNKS) {
            try {
                assertNull(s.acceptChunk(key, graphite, i, full, last = false))
            } catch (e: IllegalStateException) {
                assertEquals(SketchContract.SKETCH_TOO_LARGE, e.message)
                assertEquals(0, s.pendingSaveBytes(graphite))
                return
            }
            i++
        }
        fail("the cap was never reached")
    }

    // ── The two accumulations are independent (arc 45 / G2) ──────

    /** A graphite stream and an ink stream may cross chunk-for-chunk: two accumulations, two keys,
     *  two indices, and both commit exactly what they were given. */
    @Test
    fun interleavedGraphiteAndInkStreamsBothCommit() {
        val s = session()
        val g0 = bytes(SketchContract.SKETCH_CHUNK_BYTES, 1)
        val g1 = bytes(7, 2)
        val i0 = bytes(SketchContract.SKETCH_CHUNK_BYTES, 3)
        val i1 = bytes(9, 4)

        assertNull(s.acceptChunk(key, graphite, 0, g0, last = false))
        assertNull(s.acceptChunk(key, ink, 0, i0, last = false))
        assertEquals(g0.size, s.pendingSaveBytes(graphite))
        assertEquals(i0.size, s.pendingSaveBytes(ink))

        val inkCommit = s.acceptChunk(key, ink, 1, i1, last = true)
        assertNotNull(inkCommit)
        assertEquals(ink, inkCommit!!.layer)
        assertArrayEquals(ByteChunks.join(listOf(i0, i1)), inkCommit.bytes)
        // The graphite stream did not notice.
        assertEquals(g0.size, s.pendingSaveBytes(graphite))

        val graphiteCommit = s.acceptChunk(key, graphite, 1, g1, last = true)
        assertNotNull(graphiteCommit)
        assertEquals(graphite, graphiteCommit!!.layer)
        assertArrayEquals(ByteChunks.join(listOf(g0, g1)), graphiteCommit.bytes)
    }

    /** A refusal on one layer resets that layer and nothing else — a too-large ink push must never
     *  throw away the graphite the face is halfway through sending. */
    @Test
    fun aRefusalOnOneLayerLeavesTheOtherAccumulationIntact() {
        val s = session()
        val kept = bytes(64, 5)
        assertNull(s.acceptChunk(key, graphite, 0, kept, last = false))

        try {
            s.acceptChunk(key, ink, 0, bytes(SketchContract.SKETCH_CHUNK_BYTES + 1), last = true)
            fail()
        } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes(ink))
        assertEquals(kept.size, s.pendingSaveBytes(graphite))

        // …and out of order on ink, which is the other refusal shape.
        try { s.acceptChunk(key, ink, 3, bytes(4), last = false); fail() } catch (expected: IllegalArgumentException) {}
        assertEquals(kept.size, s.pendingSaveBytes(graphite))

        val commit = s.acceptChunk(key, graphite, 1, bytes(3, 6), last = true)
        assertNotNull(commit)
        assertEquals(kept.size + 3, commit!!.bytes.size)
    }

    /** A window swap mid-save touches neither accumulation — the existing rule, now twice over. */
    @Test
    fun aWindowSwapMidSaveLeavesBothAccumulations() {
        val s = session()
        assertNull(s.acceptChunk(key, graphite, 0, bytes(8, 1), last = false))
        assertNull(s.acceptChunk(key, ink, 0, bytes(16, 2), last = false))

        s.setWindows("page-2", bytes(4), bytes(6))

        assertEquals("page-2", s.currentKey)
        assertEquals(8, s.pendingSaveBytes(graphite))
        assertEquals(16, s.pendingSaveBytes(ink))
        // Both still commit for the page they named, not the one now in the windows.
        val g = s.acceptChunk(key, graphite, 1, bytes(1), last = true)
        val i = s.acceptChunk(key, ink, 1, bytes(1), last = true)
        assertEquals(key, g!!.pageKey)
        assertEquals(key, i!!.pageKey)
    }

    // ── The ink window ──────

    @Test
    fun theInkWindowServesItsChunks() {
        val s = session()
        val chunks = listOf(listOf(stroke(), stroke()), listOf(stroke()))
        assertEquals(2, s.setInkWindow(chunks))
        assertEquals(2, s.readInkChunk(0).size)
        assertSame(chunks[1][0], s.readInkChunk(1)[0])
        try { s.readInkChunk(2); fail() } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun anEmptyInkWindowIsZeroChunksAndRefusesEveryIndex() {
        val s = session()
        assertEquals(0, s.setInkWindow(emptyList()))
        try { s.readInkChunk(0); fail() } catch (expected: IllegalArgumentException) {}
    }

    // ── The revoke ──────

    @Test
    fun clearDropsBothWindowsTheInkWindowAndBothAccumulations() {
        val s = session(bytes(10), bytes(10))
        s.setInkWindow(listOf(listOf(stroke())))
        assertNull(s.acceptChunk(key, graphite, 0, bytes(4), last = false))
        assertNull(s.acceptChunk(key, ink, 0, bytes(4), last = false))
        assertTrue(s.pendingSaveBytes(graphite) > 0)
        assertTrue(s.pendingSaveBytes(ink) > 0)

        s.clear()

        assertNull(s.currentKey)
        for (layer in SketchContract.LAYERS) {
            assertEquals(0, s.windowByteCount(layer))
            assertEquals(0, s.pendingSaveBytes(layer))
            try { s.readChunk(layer, 0); fail() } catch (expected: IllegalArgumentException) {}
        }
        try { s.readInkChunk(0); fail() } catch (expected: IllegalArgumentException) {}
    }
}
