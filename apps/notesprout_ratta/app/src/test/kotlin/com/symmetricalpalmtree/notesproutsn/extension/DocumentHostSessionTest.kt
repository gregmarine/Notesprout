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

    /** The typed refusal, matched with `==` on the far side — never a prefix, never a `contains`. */
    @Test
    fun theNoDraftRefusalCarriesExactlyTheContractMessage() {
        val s = session()
        try {
            s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true)
            fail()
        } catch (expected: IllegalStateException) {
            assertEquals(DocumentContract.NO_DRAFT_PENDING, expected.message)
        }
    }

    /** M6: a recreated editor re-`current()`s the same target, and its drafted save is still owed
     *  the anchor the seed was served with. */
    @Test
    fun theParkedWatermarkSurvivesASameKeyReload() {
        val s = session(key = "page-1")
        s.parkWatermark(4242L)
        s.setWindow("page-1", "the same target, reloaded")
        val commit = s.acceptChunk("page-1", 0, "seeded", last = true, drafted = true)
        assertEquals(4242L, commit!!.draftWatermark)
    }

    /** M6: a flip is a new target — the old page's unconsumed anchor must never be stamped onto it. */
    @Test
    fun aDifferentKeyReloadClearsTheParkedWatermark() {
        val s = session(key = "page-1")
        s.parkWatermark(4242L)
        s.setWindow("page-2", "another page")
        try { s.acceptChunk("page-2", 0, "seeded", last = true, drafted = true); fail() }
        catch (expected: IllegalStateException) {
            assertEquals(DocumentContract.NO_DRAFT_PENDING, expected.message)
        }
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

    // ── M7: the notebook document's key rides the same guards ──────

    @Test
    fun theNotebookKeyIsATargetLikeAnyOther() {
        // The mode-routing guard, notebook form: with the notebook window loaded, a save under a
        // PAGE key is refused — and vice versa — by the accumulator itself.
        val s = session(key = "nb:notebook-1", text = "the final draft")
        try { s.acceptChunk("page-1", 0, "words", last = true, drafted = false); fail() }
        catch (expected: IllegalArgumentException) {}
        val commit = s.acceptChunk("nb:notebook-1", 0, "the final draft, edited", last = true, drafted = false)
        assertEquals("nb:notebook-1", commit!!.pageKey)
    }

    @Test
    fun aScopeSwitchClearsAPageDraftsPark() {
        // A parked page watermark must never anchor the notebook document: the different-key
        // window swap a scope switch does drops it, so a drafted commit there refuses typed.
        val s = session(key = "page-1")
        s.parkWatermark(555L)
        s.setWindow("nb:notebook-1", "merged")
        try {
            s.acceptChunk("nb:notebook-1", 0, "merged", last = true, drafted = true)
            fail()
        } catch (expected: IllegalStateException) {
            assertEquals(DocumentContract.NO_DRAFT_PENDING, expected.message)
        }
    }

    @Test
    fun aMergeDraftAnchorsUnderTheNotebookKey() {
        // The serve order the hooks use: setWindow(nb key) then park(notebookMax) — the drafted
        // commit that stores the merge consumes exactly that park.
        val s = session(key = "page-1")
        s.setWindow("nb:notebook-1", "merged pages")
        s.parkWatermark(777L)
        val commit = s.acceptChunk("nb:notebook-1", 0, "merged pages", last = true, drafted = true)
        assertEquals(777L, commit!!.draftWatermark)
        assertEquals("nb:notebook-1", commit.pageKey)
    }
}
