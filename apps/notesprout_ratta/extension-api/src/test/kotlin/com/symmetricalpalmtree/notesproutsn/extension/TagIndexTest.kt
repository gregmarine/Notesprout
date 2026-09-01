package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The tag model's rules (arc 21 / W1, reshaped in W4) — creation, attachment, the lifecycle call,
 * the caps, and the W4 rule that **every assignment names a notebook**.
 */
class TagIndexTest {

    /** Real UUIDs: since W4 a target id that is not one is not a target ([CompactId.isId]). */
    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val p2 = "bbbbbbbb-2222-4222-8222-222222222222"

    /** One assignment to build an index from: text, its notebook, and a page when it is a page tag. */
    private class A(val text: String, val notebookId: String, val pageId: String? = null)

    private fun index(vararg assigns: A): TagIndex {
        var i = TagIndex.EMPTY
        for (a in assigns) i = i.assign(a.text, a.notebookId, a.pageId).index
        return i
    }

    @Test
    fun assignCreatesThenReuses() {
        val first = TagIndex.EMPTY.assign("Reading List", n1)
        assertTrue(first.created)
        assertEquals("Reading List", first.display)
        assertEquals(1, first.index.tags.size)

        // A different casing of the same identity attaches the EXISTING tag and answers with the
        // spelling that was entered first — the wizard's display rule.
        val second = first.index.assign("  reading   list ", n1, p1)
        assertFalse(second.created)
        assertEquals("Reading List", second.display)
        assertEquals(1, second.index.tags.size)
        assertEquals(2, second.index.assignments.size)
    }

    /** The W4 shape: a notebook tag has no page, a page tag has both, and the kind falls out of
     *  that rather than being carried. */
    @Test
    fun anAssignmentAlwaysNamesItsNotebook() {
        val i = index(A("draft", n1), A("draft", n1, p1))
        val notebookTag = i.assignments.first { it.pageId == null }
        val pageTag = i.assignments.first { it.pageId != null }

        assertEquals(n1, notebookTag.notebookId)
        assertEquals(TagShowing.TARGET_NOTEBOOK, notebookTag.targetKind)
        assertEquals(n1, notebookTag.targetId)

        assertEquals(n1, pageTag.notebookId)
        assertEquals(p1, pageTag.pageId)
        assertEquals(TagShowing.TARGET_PAGE, pageTag.targetKind)
        assertEquals(p1, pageTag.targetId)
    }

    /** The same page id under two notebooks is two different targets — which is only expressible
     *  because the notebook is stored. Before W4 these were one row. */
    @Test
    fun aPageIsIdentifiedByItsNotebookToo() {
        val i = index(A("draft", n1, p1), A("draft", n2, p1))
        assertEquals(2, i.assignments.size)
        assertEquals(listOf("draft"), i.tagsOf(n1, p1).map { it.display })
        assertEquals(listOf("draft"), i.tagsOf(n2, p1).map { it.display })
    }

    @Test
    fun assignIsIdempotent() {
        val once = TagIndex.EMPTY.assign("draft", n1).index
        val twice = once.assign("DRAFT", n1)
        assertEquals(1, twice.index.assignments.size)
        assertSame(once, twice.index)
        assertEquals("draft", twice.display)
    }

    private fun assertSame(a: TagIndex, b: TagIndex) =
        assertTrue("expected the same index instance", a === b)

    @Test
    fun assignRefusesTextThatIsNotATag() {
        try {
            TagIndex.EMPTY.assign("   ", n1); fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            TagIndex.EMPTY.assign("x".repeat(ExtensionContract.MAX_TAG_CHARS + 1), n1)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** An id that is not a canonical UUID is not a target: the codec stores ids compacted, and the
     *  worst-case arithmetic assumes it can. */
    @Test
    fun assignRefusesAnIdThatIsNotAUuid() {
        try {
            TagIndex.EMPTY.assign("ok", "n1"); fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            TagIndex.EMPTY.assign("ok", n1, "p1"); fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** Removing the last assignment leaves the tag standing — the wizard's lifecycle call: a tag
     *  persists until it is explicitly deleted, so it stays in the suggestion list. */
    @Test
    fun unassignKeepsTheTag() {
        val before = index(A("draft", n1))
        val after = before.unassign(before.find("draft")!!.id, n1)
        assertEquals(1, after.tags.size)
        assertEquals(0, after.assignments.size)
        assertNotNull(after.find("Draft"))
    }

    /** Detaching the notebook's own tag leaves a page's alone, and the other way round. */
    @Test
    fun unassignIsScopedToOneTarget() {
        val before = index(A("draft", n1), A("draft", n1, p1))
        val id = before.find("draft")!!.id
        val after = before.unassign(id, n1)
        assertEquals(1, after.assignments.size)
        assertEquals(p1, after.assignments.single().pageId)
    }

    @Test
    fun deleteTagRemovesEveryAssignment() {
        val before = index(A("draft", n1), A("draft", n1, p1), A("done", n2))
        val draft = before.find("draft")!!.id
        val usage = before.usageOf(draft)
        assertEquals(1, usage.notebooks)
        assertEquals(1, usage.pages)
        assertEquals(2, usage.total)

        val after = before.deleteTag(draft)
        assertNull(after.find("draft"))
        assertEquals(1, after.assignments.size)
        assertEquals(1, after.tags.size)
    }

    @Test
    fun deleteTagOfAnUnknownIdIsANoOp() {
        val i = index(A("draft", n1))
        assertSame(i, i.deleteTag("nope"))
    }

    @Test
    fun tagsOfIsSortedAndScopedToOneTarget() {
        val i = index(
            A("zebra", n1),
            A("Apple", n1),
            A("other", n1, p1),
        )
        assertEquals(listOf("Apple", "zebra"), i.tagsOf(n1).map { it.display })
        assertEquals(listOf("other"), i.tagsOf(n1, p1).map { it.display })
        assertEquals(emptyList<String>(), i.tagsOf(n2).map { it.display })
    }

    /** What the host's search merge groups by: everything inside one notebook, its pages included. */
    @Test
    fun assignmentsInGathersTheWholeNotebook() {
        val i = index(A("draft", n1), A("wip", n1, p1), A("done", n2), A("done", n2, p2))
        assertEquals(2, i.assignmentsIn(n1).size)
        assertEquals(2, i.assignmentsIn(n2).size)
        assertEquals(0, i.assignmentsIn(p1).size)
    }

    @Test
    fun suggestRanksExactThenPrefixThenSubstring() {
        val i = index(
            A("read", n1),
            A("reading list", n1),
            A("unread", n1),
            A("zzz", n1),
        )
        assertEquals(listOf("read", "reading list", "unread"), i.suggest("read").map { it.display })
        // A blank query offers the whole library, in browse order.
        assertEquals(listOf("read", "reading list", "unread", "zzz"), i.suggest("  ").map { it.display })
    }

    @Test
    fun capsRefuseWithoutChangingAnything() {
        var i = TagIndex.EMPTY
        for (n in 0 until ExtensionContract.MAX_TAGS) i = i.assign("tag $n", n1).index
        assertEquals(ExtensionContract.MAX_TAGS, i.tags.size)
        try {
            i.assign("one too many", n1); fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(ExtensionContract.TAG_INDEX_FULL, expected.message)
        }
        // Nothing changed: the tag list is exactly as long as it was.
        assertEquals(ExtensionContract.MAX_TAGS, i.tags.size)
        // An EXISTING tag can still be attached elsewhere — the tag cap is not an assignment cap.
        val reused = i.assign("tag 0", n2)
        assertFalse(reused.created)
        assertEquals(i.assignments.size + 1, reused.index.assignments.size)
    }

    /** `of` is where a foreign blob becomes trustworthy: bad records are dropped, never thrown. */
    @Test
    fun ofDropsRecordsItCannotHonour() {
        val i = TagIndex.of(
            tags = listOf(
                TagIndex.Tag("0", "draft"),
                TagIndex.Tag("0", "duplicate id"),
                TagIndex.Tag("1", "DRAFT"),               // a second record folding to a taken identity
                TagIndex.Tag("2", "   "),                 // not a tag
                TagIndex.Tag("3", "x".repeat(ExtensionContract.MAX_TAG_CHARS + 1)),
                TagIndex.Tag("", "empty id"),
                TagIndex.Tag("4", "  spaced   out  "),    // normalized on the way in
            ),
            assignments = listOf(
                TagIndex.Assignment("0", n1),
                TagIndex.Assignment("0", n1),             // repeated
                TagIndex.Assignment("9", n1),             // no such tag
                TagIndex.Assignment("0", "n1"),           // notebook id is not a UUID
                TagIndex.Assignment("0", ""),             // no notebook at all
                TagIndex.Assignment("0", n1, "p1"),       // page id is not a UUID
                TagIndex.Assignment("4", n1, p1),
            ),
        )
        assertEquals(listOf("draft", "spaced out"), i.tags.map { it.display })
        assertEquals(2, i.assignments.size)
    }

    /** The same tag on the same page of two different notebooks is not a repeat — the dedupe key is
     *  the whole target, notebook included. */
    @Test
    fun ofDedupesOnTheWholeTarget() {
        val i = TagIndex.of(
            tags = listOf(TagIndex.Tag("0", "draft")),
            assignments = listOf(
                TagIndex.Assignment("0", n1, p1),
                TagIndex.Assignment("0", n2, p1),
                TagIndex.Assignment("0", n1, p1),   // this one is the repeat
            ),
        )
        assertEquals(2, i.assignments.size)
    }

    /** A page assignment must clear **both** gates: its notebook alive, and the page still in it.
     *  Only the first of those is a question W1 could have asked. */
    @Test
    fun filterAliveDropsDeadTargetsOnly() {
        val i = index(A("draft", n1), A("draft", n2), A("draft", n1, p1))
        val alive = i.filterAlive(aliveNotebooks = setOf(n1), alivePages = setOf(p1))
        assertEquals(2, alive.assignments.size)
        // The tag itself is untouched — a dead assignment is not a dead tag.
        assertEquals(1, alive.tags.size)
    }

    @Test
    fun filterAliveDropsAPageWhoseNotebookIsGone() {
        val i = index(A("draft", n1, p1))
        val alive = i.filterAlive(aliveNotebooks = emptySet(), alivePages = setOf(p1))
        assertEquals(0, alive.assignments.size)
    }

    @Test
    fun mintedIdsAreShortAndUnique() {
        var i = TagIndex.EMPTY
        for (n in 0 until 200) i = i.assign("tag $n", n1).index
        assertEquals(200, i.tags.map { it.id }.toSet().size)
        assertTrue(i.tags.all { it.id.length <= TagCodec.MAX_TAG_ID_CHARS })
    }
}
