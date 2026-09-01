package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [TagStore] over the statement-recording fake (arc 22 / X3): what the tag manager declares, what it
 * reads, and the exact statements every edit emits — including the two things arc 21's blob could
 * not express at all, a cap that refuses inside the insert and a concurrent creator of the same tag.
 */
class TagStoreTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val t1 = "cccccccc-1111-4111-8111-111111111111"
    private val t2 = "dddddddd-2222-4222-8222-222222222222"

    private fun text(cell: Cell) = (cell as Cell.Text).value

    // ── Declaring ────────────────────────────────────────────────────────────

    /** The schema is the ONE door — the host's gate refuses exec/query before it, so every public
     *  method applies it first. */
    @Test
    fun everyCallDeclaresTheSchemaFirst() {
        val fake = FakeTagStore()
        TagStore(fake).tags()
        assertEquals(TagSchema.V1, fake.schema)
        assertEquals("applySchema", fake.calls.first())

        val second = FakeTagStore()
        TagStore(second).usageOf(t1)
        assertEquals(listOf("applySchema", "query(usage)"), second.calls)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    fun tagsReadsTheBrowseOrderAndStopsOnAShortPage() {
        val fake = FakeTagStore()
        fake.tag(t1, "Zebra")
        fake.tag(t2, "Apple")

        val tags = TagStore(fake).tags()
        assertEquals(listOf("Apple", "Zebra"), tags.map { it.display })
        // One page, and it was short — nothing more was asked for.
        assertEquals(listOf("applySchema", "query(tags)"), fake.calls)
        assertEquals(Cell.Integer(ExtensionContract.TAGS_PAGE.toLong()), fake.queries.single().args[0])
        assertEquals(Cell.Integer(0), fake.queries.single().args[1])
    }

    @Test
    fun assignmentsOfNotebookIsScopedToThatNotebook() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n1)
        fake.assign(t1, n1, p1)
        fake.assign(t1, n2)

        val rows = TagStore(fake).assignmentsOfNotebook(n1)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.notebookId == n1 })
        assertEquals(listOf(null, p1), rows.map { it.pageIdOrNull })
    }

    /** An empty selection is a real answer and touches no store at all. */
    @Test
    fun assignmentsOfNothingAsksNothing() {
        val fake = FakeTagStore()
        assertEquals(emptyList<Any>(), TagStore(fake).assignmentsOf(emptyList(), 0))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun assignmentsOfPagesTheNamedTags() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.tag(t2, "done")
        fake.assign(t1, n1)
        fake.assign(t2, n2, p1)

        val rows = TagStore(fake).assignmentsOf(listOf(t1, t2), 0)
        assertEquals(2, rows.size)
        assertEquals(listOf(t1, t2), rows.map { it.tagId })
    }

    /** `SUM` over no rows is NULL, and NULL is 0 here — not a decode failure. */
    @Test
    fun usageDecodesBothCountsAndNullSums() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n1)
        fake.assign(t1, n2)
        fake.assign(t1, n1, p1)

        val used = TagStore(fake).usageOf(t1)
        assertEquals(2, used.notebooks)
        assertEquals(1, used.pages)
        assertEquals(3, used.total)

        val unused = TagStore(fake).usageOf(t2)
        assertEquals(0, unused.notebooks)
        assertEquals(0, unused.pages)
        assertEquals(0, unused.total)
    }

    /** A row that will not become a record is a dropped record, never a lost index. */
    @Test
    fun aBadRowIsDroppedAndTheRestLoads() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.tag("not-a-uuid", "broken")
        fake.tag(t2, "done")

        val tags = TagStore(fake).tags()
        assertEquals(listOf("done", "draft"), tags.map { it.display })
    }

    // ── assign ───────────────────────────────────────────────────────────────

    @Test
    fun assigningANewTagIsATwoStatementBatchAndARereadAfterIt() {
        val fake = FakeTagStore()
        val assigned = TagStore(fake).assign("Reading List", n1, null)

        assertTrue(assigned.changed)
        assertEquals("Reading List", assigned.display)
        // read, one transaction of two, read again.
        assertEquals(
            listOf("applySchema", "query(identity)", "exec(2)", "query(identity)"),
            fake.calls,
        )
        val batch = fake.execs.single()
        assertTrue(batch[0].sql.startsWith("INSERT OR IGNORE INTO tag"))
        assertTrue(batch[1].sql.startsWith("INSERT OR IGNORE INTO assignment"))
        // The display is normalized and the identity is folded, both by TagRules.
        assertEquals("Reading List", text(batch[0].args[1]))
        assertEquals("reading list", text(batch[0].args[2]))
        // A notebook tag's page is "" on the wire and in the row.
        assertEquals("", text(batch[1].args[1]))
        assertEquals(1, fake.tags.size)
        assertEquals(1, fake.assignments.size)
    }

    /** An existing identity writes ONE statement — the tag is not created twice, and the answer is
     *  the casing that was entered first. */
    @Test
    fun assigningAnExistingTagWritesOnlyTheAssignment() {
        val fake = FakeTagStore()
        fake.tag(t1, "Reading List")

        val assigned = TagStore(fake).assign("  reading   LIST ", n1, p1)
        assertTrue(assigned.changed)
        assertEquals("Reading List", assigned.display)
        val batch = fake.execs.single()
        assertEquals(1, batch.size)
        assertTrue(batch[0].sql.startsWith("INSERT OR IGNORE INTO assignment"))
        assertEquals(setOf(Triple(t1, n1, p1)), fake.assignments)
    }

    /** Already attached: nothing is written at all, and the tap still names the tag. */
    @Test
    fun assigningWhatIsAlreadyThereWritesNothing() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n1)

        val assigned = TagStore(fake).assign("DRAFT", n1, null)
        assertFalse(assigned.changed)
        assertEquals("draft", assigned.display)
        assertEquals(listOf("applySchema", "query(identity)"), fake.calls)
        assertTrue(fake.execs.isEmpty())
    }

    /**
     * The concurrent-create shape: this writer read "no such tag", but another one created it
     * between the read and the batch. The `INSERT OR IGNORE` inserts **nothing** and the assignment
     * still lands, because it resolves the tag id by identity inside its own statement — and the
     * answer is the **stored** display, which is the other writer's casing.
     */
    @Test
    fun aConcurrentCreatorOfTheSameTagStillLeavesTheAssignmentAttached() {
        val fake = FakeTagStore()
        // The other writer lands between this one's pre-read and its batch.
        fake.beforeExec = { fake.tag(t1, "Reading List"); fake.beforeExec = null }

        val assigned = TagStore(fake).assign("READING LIST", n1, null)

        assertTrue(assigned.changed)
        // The STORED display — the other writer's casing, not the one just handed over.
        assertEquals("Reading List", assigned.display)
        // Two statements were still sent; the first inserted nothing (the identity was taken), and
        // the second attached to the row that DOES exist because it resolves the id by identity.
        val batch = fake.execs.single()
        assertEquals(2, batch.size)
        assertTrue(batch[0].sql.startsWith("INSERT OR IGNORE INTO tag"))
        assertEquals(1, fake.tags.size)
        assertEquals(t1, fake.tags.values.single().id)
        assertEquals(setOf(Triple(t1, n1, "")), fake.assignments)
    }

    /** The tag cap refuses inside the insert; the re-read finds no tag and says so, with nothing
     *  written — not even the assignment, which has no tag to resolve. */
    @Test
    fun theTagCapRefusesAndNothingIsWritten() {
        val fake = FakeTagStore()
        fake.tag(t1, "one")
        fake.tag(t2, "two")

        try {
            TagStore(fake, maxTags = 2).assign("three", n1, null)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(ExtensionContract.TAG_INDEX_FULL, expected.message)
        }
        assertEquals(2, fake.tags.size)
        assertTrue(fake.assignments.isEmpty())
    }

    /** The assignment cap refuses the second statement; the tag may exist, but it is not attached —
     *  which is the other half of the same sentence to a user. */
    @Test
    fun theAssignmentCapRefusesAndSaysSo() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n2)

        try {
            TagStore(fake, maxAssignments = 1).assign("draft", n1, null)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(ExtensionContract.TAG_INDEX_FULL, expected.message)
        }
        assertEquals(setOf(Triple(t1, n2, "")), fake.assignments)
    }

    /** A NEW tag that the assignment cap would refuse to attach is not created either: the tag
     *  insert is gated on both caps, so "nothing was written" stays true — no orphan tag row. */
    @Test
    fun theAssignmentCapRefusesANewTagWithoutCreatingIt() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n2)

        try {
            TagStore(fake, maxAssignments = 1).assign("brand new", n1, null)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(ExtensionContract.TAG_INDEX_FULL, expected.message)
        }
        assertEquals(1, fake.tags.size)
        assertEquals(setOf(Triple(t1, n2, "")), fake.assignments)
    }

    @Test
    fun assignRefusesTextThatIsNotATagAndIdsThatAreNotUuids() {
        val fake = FakeTagStore()
        val store = TagStore(fake)
        for (bad in listOf<() -> Unit>(
            { store.assign("   ", n1, null) },
            { store.assign("x".repeat(ExtensionContract.MAX_TAG_CHARS + 1), n1, null) },
            { store.assign("ok", "n1", null) },
            { store.assign("ok", n1, "p1") },
        )) {
            try {
                bad()
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
            }
        }
        // Nothing reached the store: the requires run before the schema is even declared.
        assertTrue(fake.calls.isEmpty())
    }

    // ── unassign / deleteTag ─────────────────────────────────────────────────

    /** Detaching leaves the tag standing — the wizard's lifecycle call. */
    @Test
    fun unassignRemovesOneRowAndKeepsTheTag() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.assign(t1, n1)
        fake.assign(t1, n1, p1)

        val store = TagStore(fake)
        assertTrue(store.unassign(t1, n1, null))
        assertEquals(setOf(Triple(t1, n1, p1)), fake.assignments)
        assertEquals(1, fake.tags.size)
        // A row that is not there is not an error, and nothing changed.
        assertFalse(store.unassign(t1, n2, null))
    }

    @Test
    fun deleteTagIsOneStatementAndTheCascadeTakesTheAssignments() {
        val fake = FakeTagStore()
        fake.tag(t1, "draft")
        fake.tag(t2, "done")
        fake.assign(t1, n1)
        fake.assign(t1, n2, p1)
        fake.assign(t2, n1)

        assertTrue(TagStore(fake).deleteTag(t1))
        assertEquals(listOf("DELETE FROM tag WHERE id = ?"), fake.sql())
        assertEquals(setOf(Triple(t2, n1, "")), fake.assignments)
        assertEquals(1, fake.tags.size)
    }

    @Test
    fun deletingATagThatIsNotThereChangesNothing() {
        val fake = FakeTagStore()
        assertFalse(TagStore(fake).deleteTag(t1))
    }

    // ── Failure ──────────────────────────────────────────────────────────────

    /** Every store failure is the same answer: unavailable. The one thing that is not is the cap
     *  refusal, which is a decision and not a broken store. */
    @Test
    fun everyStoreFailureIsStoreUnavailable() {
        for (thrown in listOf<() -> Throwable>(
            { IllegalStateException("disk I/O error") },
            { IllegalArgumentException("statement refused") },
            { SecurityException("revoked") },
            { RuntimeException("boom") },
        )) {
            val fake = FakeTagStore()
            fake.failWith = thrown
            val store = TagStore(fake)
            assertUnavailable { store.tags() }
            assertUnavailable { store.tagsPage(0) }
            assertUnavailable { store.assignmentsOfNotebook(n1) }
            assertUnavailable { store.assignmentsOf(listOf(t1), 0) }
            assertUnavailable { store.usageOf(t1) }
            assertUnavailable { store.assign("draft", n1, null) }
            assertUnavailable { store.unassign(t1, n1, null) }
            assertUnavailable { store.deleteTag(t1) }
            assertUnavailable { store.load() }
        }
    }

    private fun assertUnavailable(block: () -> Unit) {
        try {
            block()
            fail("expected StoreUnavailable")
        } catch (expected: StoreUnavailable) {
        }
    }
}
