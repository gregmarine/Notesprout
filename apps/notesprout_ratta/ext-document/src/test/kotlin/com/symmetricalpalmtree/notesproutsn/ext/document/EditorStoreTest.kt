package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [EditorStore] over the statement-recording fake (arc 22 / X4): what the editor declares, how each
 * stored value decodes, and the exact statements every write emits — including the two the blob era
 * could not express, an `OR IGNORE` re-add that moves nothing and an LRU trim that rides in the same
 * transaction as the write that caused it.
 */
class EditorStoreTest {

    private fun text(cell: Cell) = (cell as Cell.Text).value
    private fun int(cell: Cell) = (cell as Cell.Integer).value

    // ── Declaring ────────────────────────────────────────────────────────────

    /** The schema is the ONE door — the host's gate refuses exec/query before it, and the binder is
     *  fetched per call, so every public method applies it first. */
    @Test
    fun everyCallDeclaresTheSchemaFirst() {
        for (call in listOf<(EditorStore) -> Unit>(
            { it.textSize() },
            { it.saveTextSize(18f) },
            { it.proofreadEnabled() },
            { it.saveProofreadEnabled(false) },
            { it.userWords() },
            { it.addUserWord("colour") },
            { it.removeUserWord("colour") },
            { it.caret("pk-1") },
            { it.rememberCaret("pk-1", 5) },
        )) {
            val fake = FakeEditorStore()
            call(EditorStore(fake))
            assertEquals(EditorSchema.V1, fake.schema)
            assertEquals("applySchema", fake.calls.first())
        }
    }

    // ── Text size ────────────────────────────────────────────────────────────

    @Test
    fun textSizeReadsTheStoredValue() {
        val fake = FakeEditorStore()
        fake.prefs["size"] = "18.0"
        assertEquals(18f, EditorStore(fake).textSize(), 0f)
        assertEquals(listOf("applySchema", "query(pref)"), fake.calls)
    }

    /** Absent, garbage and NaN are all the same answer: the default. A size is comfort. */
    @Test
    fun anUnreadableSizeIsTheDefault() {
        val fake = FakeEditorStore()
        assertEquals(EditorPrefs.DEFAULT_TEXT_SIZE, EditorStore(fake).textSize(), 0f)

        for (bad in listOf("", "   ", "large", "NaN", "1.2.3")) {
            fake.prefs["size"] = bad
            assertEquals(bad, EditorPrefs.DEFAULT_TEXT_SIZE, EditorStore(fake).textSize(), 0f)
        }
    }

    /** A value from a future build with a wider ladder is coerced, never discarded — the editor must
     *  stay usable, and the nearest offered size is the honest reading of what was asked for. */
    @Test
    fun aSizeOutsideTheLadderIsCoerced() {
        val smallest = EditorPrefs.SIZES.first().second
        val largest = EditorPrefs.SIZES.last().second
        val fake = FakeEditorStore()

        fake.prefs["size"] = "2.0"
        assertEquals(smallest, EditorStore(fake).textSize(), 0f)
        fake.prefs["size"] = "400.0"
        assertEquals(largest, EditorStore(fake).textSize(), 0f)
    }

    /** Stored as the float's `toString` — the exact form `DocumentPdfMetrics.textSizeSp` parses when
     *  the host reads this table for a Document-PDF export. */
    @Test
    fun saveTextSizeWritesTheFloatsToString() {
        val fake = FakeEditorStore()
        EditorStore(fake).saveTextSize(21f)
        assertEquals(listOf("applySchema", "exec(1)"), fake.calls)
        val s = fake.statements.single()
        assertEquals(DocumentContract.PREF_TEXT_SIZE, text(s.args[0]))
        assertEquals(21f.toString(), text(s.args[1]))
        assertEquals("21.0", fake.prefs["size"])
        // Read back through the same store: the round trip is real, not a restatement.
        assertEquals(21f, EditorStore(fake).textSize(), 0f)
    }

    // ── Proofread ────────────────────────────────────────────────────────────

    /** Absent means ON — the feature's default — and anything that is not exactly "0" reads as on,
     *  so a value this build did not write cannot silently remove a feature. */
    @Test
    fun proofreadDecodesAbsentAsOn() {
        val fake = FakeEditorStore()
        assertTrue(EditorStore(fake).proofreadEnabled())

        fake.prefs["proofread"] = "0"
        assertFalse(EditorStore(fake).proofreadEnabled())
        for (on in listOf("1", "", "yes", "true", "  1 ")) {
            fake.prefs["proofread"] = on
            assertTrue(on, EditorStore(fake).proofreadEnabled())
        }
    }

    @Test
    fun saveProofreadWritesOneOrZero() {
        val fake = FakeEditorStore()
        EditorStore(fake).saveProofreadEnabled(false)
        assertEquals("0", fake.prefs["proofread"])
        assertFalse(EditorStore(fake).proofreadEnabled())

        EditorStore(fake).saveProofreadEnabled(true)
        assertEquals("1", fake.prefs["proofread"])
        assertTrue(EditorStore(fake).proofreadEnabled())
    }

    // ── The user dictionary ──────────────────────────────────────────────────

    @Test
    fun userWordsComeBackOldestFirst() {
        val fake = FakeEditorStore()
        fake.words["newest"] = 300L
        fake.words["oldest"] = 100L
        fake.words["middle"] = 200L

        assertEquals(
            listOf("oldest", "middle", "newest"),
            EditorStore(fake).userWords().toList(),
        )
        assertEquals(listOf("applySchema", "query(words)"), fake.calls)
    }

    @Test
    fun anEmptyDictionaryIsAnEmptySetNotAFailure() {
        assertTrue(EditorStore(FakeEditorStore()).userWords().isEmpty())
    }

    @Test
    fun addUserWordStoresTheWord() {
        val fake = FakeEditorStore()
        assertTrue(EditorStore(fake).addUserWord("colour"))
        assertEquals(listOf("applySchema", "exec(1)"), fake.calls)
        assertTrue("colour" in fake.words)
        assertEquals(setOf("colour"), EditorStore(fake).userWords())
    }

    /** A re-add answers true — the writer asked for a state and that state holds — and `OR IGNORE`
     *  leaves the original `addedAt`, so the manage list does not reshuffle under them. */
    @Test
    fun reAddingAWordAnswersTrueAndMovesNothing() {
        val fake = FakeEditorStore()
        fake.words["colour"] = 100L
        fake.words["kerne"] = 200L

        assertTrue(EditorStore(fake).addUserWord("colour"))
        assertEquals(100L, fake.words["colour"])
        assertEquals(listOf("colour", "kerne"), EditorStore(fake).userWords().toList())
    }

    /** An empty word is not a word: no store call at all, and the answer is false. */
    @Test
    fun anEmptyWordTouchesNothing() {
        val fake = FakeEditorStore()
        assertFalse(EditorStore(fake).addUserWord(""))
        EditorStore(fake).removeUserWord("")
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun removeUserWordIsAHardDrop() {
        val fake = FakeEditorStore()
        fake.words["colour"] = 100L
        fake.words["kerne"] = 200L

        EditorStore(fake).removeUserWord("colour")
        assertEquals(listOf("applySchema", "exec(1)"), fake.calls)
        assertEquals(setOf("kerne"), EditorStore(fake).userWords())
        // Removing what is not there is not an error.
        EditorStore(fake).removeUserWord("colour")
    }

    // ── Caret ────────────────────────────────────────────────────────────────

    @Test
    fun caretReadsTheStoredOffset() {
        val fake = FakeEditorStore()
        fake.caret("pk-1", 42, 100L)
        assertEquals(42, EditorStore(fake).caret("pk-1"))
        assertEquals(listOf("applySchema", "query(caret)"), fake.calls)
    }

    /** A page never seen opens at the top; so does a stored negative, which would otherwise be a
     *  `setSelection` crash rather than a lost scroll. */
    @Test
    fun anAbsentOrNegativeCaretIsTheTop() {
        val fake = FakeEditorStore()
        assertEquals(0, EditorStore(fake).caret("pk-unknown"))
        fake.caret("pk-1", -12, 100L)
        assertEquals(0, EditorStore(fake).caret("pk-1"))
    }

    @Test
    fun anEmptyPageKeyTouchesNothing() {
        val fake = FakeEditorStore()
        assertEquals(0, EditorStore(fake).caret(""))
        EditorStore(fake).rememberCaret("", 5)
        assertTrue(fake.calls.isEmpty())
    }

    /** ONE `exec` of exactly two statements — the upsert then the trim, in one transaction, so the
     *  row just written is inside the window the trim measures. */
    @Test
    fun rememberCaretIsOneBatchOfTheUpsertThenTheTrim() {
        val fake = FakeEditorStore()
        EditorStore(fake).rememberCaret("pk-1", 42)

        assertEquals(listOf("applySchema", "exec(2)"), fake.calls)
        val batch = fake.execs.single()
        assertEquals(2, batch.size)
        assertTrue(batch[0].sql.startsWith("INSERT OR REPLACE INTO caret"))
        assertTrue(batch[1].sql.startsWith("DELETE FROM caret WHERE pageKey NOT IN"))
        assertEquals("pk-1", text(batch[0].args[0]))
        assertEquals(42L, int(batch[0].args[1]))
        assertEquals(EditorSql.CARET_LIMIT.toLong(), int(batch[1].args[0]))
        assertEquals(42, EditorStore(fake).caret("pk-1"))
    }

    /** Re-writing a page moves its `updatedAt`, which is what makes eviction least-recently-written
     *  and keeps the page being typed in the last one to go. */
    @Test
    fun rememberCaretReplacesInPlace() {
        val fake = FakeEditorStore()
        val store = EditorStore(fake)
        store.rememberCaret("pk-1", 5)
        store.rememberCaret("pk-1", 9)
        assertEquals(1, fake.carets.size)
        assertEquals(9, store.caret("pk-1"))
    }

    /** The trim evicts the oldest once the memory would stand at `CARET_LIMIT + 1`. */
    @Test
    fun theTrimEvictsTheOldestPastTheLimit() {
        val fake = FakeEditorStore()
        // A full memory, oldest first — "page-0" is the one that has to go.
        for (i in 0 until EditorSql.CARET_LIMIT) fake.caret("page-$i", i, i.toLong())
        assertEquals(EditorSql.CARET_LIMIT, fake.carets.size)

        EditorStore(fake).rememberCaret("page-new", 7)

        assertEquals(EditorSql.CARET_LIMIT, fake.carets.size)
        assertFalse("page-0" in fake.carets)
        assertTrue("page-new" in fake.carets)
        assertTrue("page-${EditorSql.CARET_LIMIT - 1}" in fake.carets)
    }

    // ── Failure ──────────────────────────────────────────────────────────────

    /**
     * The store lets every exception through — it is [EditorPrefs] that decides they all mean
     * "unavailable". Keeping the two apart is what lets the SQL layer stay honest about failing.
     */
    @Test
    fun everyFailurePropagates() {
        for (thrown in listOf<() -> Throwable>(
            { IllegalStateException("disk I/O error") },
            { IllegalArgumentException("statement refused") },
            { SecurityException("revoked") },
            { RuntimeException("boom") },
        )) {
            val fake = FakeEditorStore()
            fake.failWith = thrown
            val store = EditorStore(fake)
            assertThrows { store.textSize() }
            assertThrows { store.saveTextSize(18f) }
            assertThrows { store.proofreadEnabled() }
            assertThrows { store.saveProofreadEnabled(true) }
            assertThrows { store.userWords() }
            assertThrows { store.addUserWord("colour") }
            assertThrows { store.removeUserWord("colour") }
            assertThrows { store.caret("pk-1") }
            assertThrows { store.rememberCaret("pk-1", 5) }
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected the store's failure to propagate")
        } catch (expected: Exception) {
        }
    }
}
