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
 * The pure half of the host's `ISketchHost` stub (arc 43 / K4) — the read window, the ink window
 * and the save accumulator. `DocumentHostSessionTest`'s shape, and it pins the one place the two
 * seams genuinely differ: **a sketch save is accepted for any page, not only the window's**.
 */
class SketchHostSessionTest {

    private val key = "page-1"

    private fun bytes(n: Int, fill: Byte = 7): ByteArray = ByteArray(n) { fill }

    private fun session(png: ByteArray = bytes(10)): SketchHostSession {
        val s = SketchHostSession()
        s.setWindow(key, png)
        return s
    }

    private fun stroke(n: Int = 2) = WireStroke(
        FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), 3f, -0x1000000, "PEN",
    )

    // ── The read window ──────

    @Test
    fun windowServesItsChunksAndRefusesOutside() {
        val s = session(bytes(10))
        assertEquals(10, s.readChunk(0).size)
        assertEquals(key, s.currentKey)
        assertEquals(10, s.windowByteCount)
        try { s.readChunk(1); fail() } catch (expected: IllegalArgumentException) {}
        try { s.readChunk(-1); fail() } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun anEmptyPngIsOneEmptyChunk() {
        val s = SketchHostSession()
        assertEquals(1, s.setWindow(key, ByteArray(0)))
        assertEquals(0, s.readChunk(0).size)
        assertEquals(0, s.windowByteCount)
    }

    @Test
    fun aLargePngChunksAndReassembles() {
        val png = bytes(SketchContract.SKETCH_CHUNK_BYTES * 2 + 5)
        val s = SketchHostSession()
        val n = s.setWindow(key, png)
        assertEquals(3, n)
        assertArrayEquals(png, ByteChunks.join((0 until n).map { s.readChunk(it) }))
    }

    @Test
    fun aWindowOverTheCapIsRefused() {
        val s = SketchHostSession()
        try {
            s.setWindow(key, bytes(SketchContract.MAX_BYTES + 1))
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun aMalformedKeyIsRefusedOnTheWindowAndOnASave() {
        val s = SketchHostSession()
        try { s.setWindow("", bytes(4)); fail() } catch (expected: IllegalArgumentException) {}
        try { s.setWindow("a/b", bytes(4)); fail() } catch (expected: IllegalArgumentException) {}
        try {
            s.setWindow("x".repeat(SketchContract.MAX_PAGE_KEY_CHARS + 1), bytes(4))
            fail()
        } catch (expected: IllegalArgumentException) {}
        try { s.acceptChunk("a/b", 0, bytes(4), last = true); fail() } catch (expected: IllegalArgumentException) {}
    }

    // ── The save accumulator ──────

    @Test
    fun aOneChunkSaveCommits() {
        val png = bytes(12, 3)
        val commit = session().acceptChunk(key, 0, png, last = true)
        assertNotNull(commit)
        assertEquals(key, commit!!.pageKey)
        assertArrayEquals(png, commit.png)
    }

    @Test
    fun aMultiChunkSaveCommitsTheJoinedBytes() {
        val s = session()
        val a = bytes(SketchContract.SKETCH_CHUNK_BYTES, 1)
        val b = bytes(9, 2)
        assertNull(s.acceptChunk(key, 0, a, last = false))
        val commit = s.acceptChunk(key, 1, b, last = true)
        assertNotNull(commit)
        assertArrayEquals(ByteChunks.join(listOf(a, b)), commit!!.png)
    }

    @Test
    fun anEmptyLastChunkIsTheClear() {
        val commit = session().acceptChunk(key, 0, ByteArray(0), last = true)
        assertNotNull(commit)
        assertEquals(0, commit!!.png.size)
        assertEquals(key, commit.pageKey)
    }

    @Test
    fun aSaveForAnyPageIsAcceptedNotOnlyTheWindowsOwn() {
        // The one place this seam differs from the document editor's: the face turns its own pages,
        // so a flush a moment after a turn still belongs to the page it was drawn on.
        val s = session()
        val commit = s.acceptChunk("some-other-page", 0, bytes(4), last = true)
        assertNotNull(commit)
        assertEquals("some-other-page", commit!!.pageKey)
        // …and the window is untouched by it.
        assertEquals(key, s.currentKey)
    }

    @Test
    fun anOutOfOrderChunkIsRefusedAndResets() {
        val s = session()
        assertNull(s.acceptChunk(key, 0, bytes(4), last = false))
        try { s.acceptChunk(key, 2, bytes(4), last = false); fail() } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes)
        // The restart is chunk 0 again, and it commits on its own.
        val commit = s.acceptChunk(key, 0, bytes(5, 9), last = true)
        assertNotNull(commit)
        assertEquals(5, commit!!.png.size)
    }

    @Test
    fun aKeyThatChangesMidSaveIsRefusedAndResets() {
        val s = session()
        assertNull(s.acceptChunk(key, 0, bytes(4), last = false))
        try { s.acceptChunk("page-2", 1, bytes(4), last = true); fail() } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes)
    }

    @Test
    fun anOversizeChunkIsRefusedAndResets() {
        val s = session()
        try {
            s.acceptChunk(key, 0, bytes(SketchContract.SKETCH_CHUNK_BYTES + 1), last = true)
            fail()
        } catch (expected: IllegalArgumentException) {}
        assertEquals(0, s.pendingSaveBytes)
    }

    @Test
    fun aSaveOverTheCapThrowsTheTypedRefusalAndWritesNothing() {
        val s = session()
        val full = bytes(SketchContract.SKETCH_CHUNK_BYTES)
        var i = 0
        while (i < SketchContract.MAX_CHUNKS) {
            try {
                assertNull(s.acceptChunk(key, i, full, last = false))
            } catch (e: IllegalStateException) {
                assertEquals(SketchContract.SKETCH_TOO_LARGE, e.message)
                assertEquals(0, s.pendingSaveBytes)
                return
            }
            i++
        }
        fail("the cap was never reached")
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
    fun clearDropsTheWindowTheInkWindowAndTheAccumulation() {
        val s = session()
        s.setInkWindow(listOf(listOf(stroke())))
        assertNull(s.acceptChunk(key, 0, bytes(4), last = false))
        assertTrue(s.pendingSaveBytes > 0)
        s.clear()
        assertNull(s.currentKey)
        assertEquals(0, s.windowByteCount)
        assertEquals(0, s.pendingSaveBytes)
        try { s.readChunk(0); fail() } catch (expected: IllegalArgumentException) {}
        try { s.readInkChunk(0); fail() } catch (expected: IllegalArgumentException) {}
    }
}
