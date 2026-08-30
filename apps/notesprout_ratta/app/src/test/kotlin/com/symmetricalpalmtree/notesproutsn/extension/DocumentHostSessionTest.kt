package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The pure half of the host's `IDocumentHost` stub — window, accumulator, watermark rules. */
class DocumentHostSessionTest {

    private fun session(key: String = "page-1", text: String = "hello"): DocumentHostSession {
        val s = DocumentHostSession()
        s.setWindow(key, text)
        return s
    }

    // ── The read window ──────

    @Test
    fun windowServesItsChunksAndRefusesOutside() {
        val s = session(text = "hello")
        assertEquals("hello", s.readChunk(0))
        try { s.readChunk(1); fail() } catch (expected: IllegalArgumentException) {}
        try { s.readChunk(-1); fail() } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun emptyTextIsOneEmptyChunk() {
        val s = session(text = "")
        assertEquals(1, s.setWindow("page-1", ""))
        assertEquals("", s.readChunk(0))
    }

    @Test
    fun aLargeTextChunksAndReassembles() {
        val text = "x".repeat(DocumentContract.TEXT_CHUNK_CHARS * 2 + 5)
        val s = DocumentHostSession()
        val n = s.setWindow("k", text)
        assertEquals(3, n)
        assertEquals(text, (0 until n).joinToString("") { s.readChunk(it) })
    }

    @Test
    fun anOversizedWindowIsRefused() {
        val s = DocumentHostSession()
        try {
            s.setWindow("k", "x".repeat(DocumentContract.MAX_DOCUMENT_CHARS + 1))
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    // ── The save accumulator ──────

    @Test
    fun aOneChunkSaveCommits() {
        val commit = session().acceptChunk("page-1", 0, "new text", last = true, drafted = false)
        assertNotNull(commit)
        assertEquals("new text", commit!!.text)
        assertEquals("page-1", commit.pageKey)
        assertNull(commit.draftWatermark)
    }

    @Test
    fun aMultiChunkSaveAccumulatesInOrder() {
        val s = session()
        assertNull(s.acceptChunk("page-1", 0, "one ", last = false, drafted = false))
        assertNull(s.acceptChunk("page-1", 1, "two ", last = false, drafted = false))
        val commit = s.acceptChunk("page-1", 2, "three", last = true, drafted = false)
        assertEquals("one two three", commit!!.text)
    }

    @Test
    fun theWrongTargetKeyIsRefused() {
        val s = session(key = "page-1")
        try {
            s.acceptChunk("page-2", 0, "text", last = true, drafted = false)
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun anOutOfOrderChunkRefusesAndResets() {
        val s = session()
        assertNull(s.acceptChunk("page-1", 0, "one", last = false, drafted = false))
        try { s.acceptChunk("page-1", 2, "three", last = true, drafted = false); fail() }
        catch (expected: IllegalArgumentException) {}
        // The reset means a clean restart from 0 commits only what it carried.
        val commit = s.acceptChunk("page-1", 0, "fresh", last = true, drafted = false)
        assertEquals("fresh", commit!!.text)
    }

    @Test
    fun anOversizedChunkIsRefused() {
        val s = session()
        try {
            s.acceptChunk("page-1", 0, "x".repeat(DocumentContract.TEXT_CHUNK_CHARS + 1), last = true, drafted = false)
            fail()
        } catch (expected: IllegalArgumentException) {}
    }

    @Test
    fun theRunningTotalIsRecheckedOnReceipt() {
        val s = session()
        val chunk = "x".repeat(DocumentContract.TEXT_CHUNK_CHARS)
        var i = 0
        try {
            while (true) {
                s.acceptChunk("page-1", i, chunk, last = false, drafted = false)
                i++
            }
        } catch (expected: IllegalArgumentException) {
            // Refused exactly when one more chunk would cross the document cap.
            assertEquals(DocumentContract.MAX_DOCUMENT_CHARS / DocumentContract.TEXT_CHUNK_CHARS, i)
        }
    }

    @Test
    fun aWindowSwapDropsAHalfReceivedSave() {
        val s = session(key = "page-1")
        assertNull(s.acceptChunk("page-1", 0, "half", last = false, drafted = false))
        s.setWindow("page-2", "other")
        // The stale accumulation is gone: the new target starts from chunk 0…
        try { s.acceptChunk("page-2", 1, "tail", last = true, drafted = false); fail() }
        catch (expected: IllegalArgumentException) {}
        // …and the old key no longer names the target at all.
        try { s.acceptChunk("page-1", 0, "half", last = true, drafted = false); fail() }
        catch (expected: IllegalArgumentException) {}
    }

    // ── The draft watermark ──────

    @Test
    fun aDraftedSaveNeedsAParkedWatermark() {
        val s = session()
        try { s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true); fail() }
        catch (expected: IllegalStateException) {}
    }

    @Test
    fun aDraftedSaveConsumesTheParkedWatermark() {
        val s = session()
        s.parkWatermark(1234L)
        val commit = s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true)
        assertEquals(1234L, commit!!.draftWatermark)
        // Consumed: a second drafted save without a fresh park is refused.
        try { s.acceptChunk("page-1", 0, "again", last = true, drafted = true); fail() }
        catch (expected: IllegalStateException) {}
    }

    @Test
    fun anOrdinarySaveNeverCarriesTheWatermark() {
        val s = session()
        s.parkWatermark(1234L)
        val commit = s.acceptChunk("page-1", 0, "hand edit", last = true, drafted = false)
        assertNull(commit!!.draftWatermark)
        // Still parked for the drafted save that comes later.
        val drafted = s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true)
        assertEquals(1234L, drafted!!.draftWatermark)
    }

    @Test
    fun aRefusedDraftedSaveCanRetryAgainstTheSameDraft() {
        val s = session()
        s.parkWatermark(99L)
        try { s.acceptChunk("page-1", 1, "bad order", last = true, drafted = true); fail() }
        catch (expected: IllegalArgumentException) {}
        val commit = s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true)
        assertEquals(99L, commit!!.draftWatermark)
    }

    @Test
    fun clearDropsEverything() {
        val s = session()
        s.parkWatermark(7L)
        s.clear()
        try { s.readChunk(0); fail() } catch (expected: IllegalArgumentException) {}
        try { s.acceptChunk("page-1", 0, "text", last = true, drafted = false); fail() }
        catch (expected: IllegalArgumentException) {}
        assertNull(s.currentKey)
    }
}
