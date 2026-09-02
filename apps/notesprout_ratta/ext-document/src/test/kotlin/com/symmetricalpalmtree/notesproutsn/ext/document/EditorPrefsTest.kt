package com.symmetricalpalmtree.notesproutsn.ext.document

import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facade half of the editor's per-device state (arc 22 / X4): [EditorPrefs] decides what a
 * failure means, and there is exactly one answer — the default, silently. This state is comfort, not
 * content, and no failure of it may ever surface as a problem the writer has to deal with.
 *
 * The store is reached the way the screen reaches it: [EditorSession.begin], which is what the host
 * calls at the start of a showing. That keeps the test honest about the **fetched per call** rule —
 * nothing here can pass a store in by the side door.
 */
class EditorPrefsTest {

    /** The host's callback binder is not exercised at all — nothing in [EditorPrefs] touches it —
     *  but `begin` takes both, so the showing needs one. Every method refuses loudly. */
    private class FakeHost : IDocumentHost {
        private fun no(): Nothing = throw UnsupportedOperationException("not part of this test")
        override fun current(): DocumentPageState = no()
        override fun readChunk(chunkIndex: Int): String = no()
        override fun saveChunk(pageKey: String?, chunkIndex: Int, chunk: String?, last: Boolean, drafted: Boolean) = no()
        override fun requestPage(direction: Int): DocumentPageState = no()
        override fun requestScope(scope: Int): DocumentPageState = no()
        override fun requestSeed(mode: Int): DocumentPageState = no()
        override fun requestMerge(mode: Int): DocumentPageState = no()
        override fun cancelRequest() = no()
        override fun renameNotebook(name: String?) = no()
        override fun closeNotebook(mode: Int) = no()
        override fun asBinder(): IBinder? = null
    }

    private fun showing(store: FakeEditorStore) = EditorSession.begin(store, FakeHost())

    @After
    fun endTheShowing() = EditorSession.end()

    // ── No showing ───────────────────────────────────────────────────────────

    /** Outside a showing there is no binder to fetch: every read is its default and no write is
     *  attempted. This is the state the screen is in before `begin` and after `end`. */
    @Test
    fun withNoShowingEveryReadIsItsDefaultAndNothingIsWritten() {
        EditorSession.end()

        assertEquals(EditorPrefs.DEFAULT_TEXT_SIZE, EditorPrefs.textSize(), 0f)
        assertTrue(EditorPrefs.proofreadEnabled())
        assertTrue(EditorPrefs.userWords().isEmpty())
        assertEquals(0, EditorPrefs.caret("pk-1"))
        assertFalse(EditorPrefs.addUserWord("colour"))

        // The writes are no-ops rather than throws — there is nothing to write to.
        EditorPrefs.saveTextSize(21f)
        EditorPrefs.saveProofreadEnabled(false)
        EditorPrefs.removeUserWord("colour")
        EditorPrefs.rememberCaret("pk-1", 42)
    }

    // ── A store that is there ────────────────────────────────────────────────

    /** The ordinary path, so the defaults above are read as a *fallback* and not as the only
     *  behaviour this facade has. */
    @Test
    fun withAStoreTheValuesRoundTrip() {
        val fake = FakeEditorStore()
        showing(fake)

        EditorPrefs.saveTextSize(18f)
        EditorPrefs.saveProofreadEnabled(false)
        assertTrue(EditorPrefs.addUserWord("colour"))
        EditorPrefs.rememberCaret("pk-1", 42)

        assertEquals(18f, EditorPrefs.textSize(), 0f)
        assertFalse(EditorPrefs.proofreadEnabled())
        assertEquals(setOf("colour"), EditorPrefs.userWords())
        assertEquals(42, EditorPrefs.caret("pk-1"))
    }

    // ── A store that fails ───────────────────────────────────────────────────

    /**
     * Every store failure is the same answer as no store at all. Proofread is the one that matters
     * most: an unavailable read must answer **true**, because a wrongly-on pass costs heap while a
     * wrongly-off one silently removes a feature.
     */
    @Test
    fun aFailingStoreReadsAsTheDefaultsAndWritesQuietly() {
        for (thrown in listOf<() -> Throwable>(
            { IllegalStateException("disk I/O error") },
            { IllegalArgumentException("statement refused") },
            { SecurityException("revoked") },
            { RuntimeException("boom") },
        )) {
            val fake = FakeEditorStore()
            fake.failWith = thrown
            showing(fake)

            assertEquals(EditorPrefs.DEFAULT_TEXT_SIZE, EditorPrefs.textSize(), 0f)
            assertTrue(EditorPrefs.proofreadEnabled())
            assertTrue(EditorPrefs.userWords().isEmpty())
            assertEquals(0, EditorPrefs.caret("pk-1"))
            assertFalse(EditorPrefs.addUserWord("colour"))

            EditorPrefs.saveTextSize(21f)
            EditorPrefs.saveProofreadEnabled(false)
            EditorPrefs.removeUserWord("colour")
            EditorPrefs.rememberCaret("pk-1", 42)
        }
    }

    // ── The ladder ───────────────────────────────────────────────────────────

    /** The screen lays itself out with these, and the host's export mirrors their ends
     *  (`DocumentPdfMetrics`: 14 / 25 / 16 / a 2sp preview bump). */
    @Test
    fun theSizeLadderIsTheOneTheExportMirrors() {
        assertEquals(listOf(14f, 16f, 18f, 21f, 25f), EditorPrefs.SIZES.map { it.second })
        assertEquals(16f, EditorPrefs.DEFAULT_TEXT_SIZE, 0f)
        assertEquals(2f, EditorPrefs.PREVIEW_BUMP, 0f)
    }
}
