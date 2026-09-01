package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The tag model's rules (arc 21 / W1) — creation, attachment, the lifecycle call, the caps. */
class TagIndexTest {

    private val nb = TagShowing.TARGET_NOTEBOOK
    private val page = TagShowing.TARGET_PAGE

    private fun index(vararg assigns: Triple<String, Int, String>): TagIndex {
        var i = TagIndex.EMPTY
        for ((text, kind, id) in assigns) i = i.assign(text, kind, id).index
        return i
    }

    @Test
    fun assignCreatesThenReuses() {
        val first = TagIndex.EMPTY.assign("Reading List", nb, "n1")
        assertTrue(first.created)
        assertEquals("Reading List", first.display)
        assertEquals(1, first.index.tags.size)

        // A different casing of the same identity attaches the EXISTING tag and answers with the
        // spelling that was entered first — the wizard's display rule.
        val second = first.index.assign("  reading   list ", page, "p1")
        assertFalse(second.created)
        assertEquals("Reading List", second.display)
        assertEquals(1, second.index.tags.size)
        assertEquals(2, second.index.assignments.size)
    }

    @Test
    fun assignIsIdempotent() {
        val once = TagIndex.EMPTY.assign("draft", nb, "n1").index
        val twice = once.assign("DRAFT", nb, "n1")
        assertEquals(1, twice.index.assignments.size)
        assertSame(once, twice.index)
        assertEquals("draft", twice.display)
    }

    private fun assertSame(a: TagIndex, b: TagIndex) =
        assertTrue("expected the same index instance", a === b)

    @Test
    fun assignRefusesTextThatIsNotATag() {
        try {
            TagIndex.EMPTY.assign("   ", nb, "n1"); fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            TagIndex.EMPTY.assign("x".repeat(ExtensionContract.MAX_TAG_CHARS + 1), nb, "n1")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            TagIndex.EMPTY.assign("ok", 7, "n1"); fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** Removing the last assignment leaves the tag standing — the wizard's lifecycle call: a tag
     *  persists until it is explicitly deleted, so it stays in the suggestion list. */
    @Test
    fun unassignKeepsTheTag() {
        val before = index(Triple("draft", nb, "n1"))
        val after = before.unassign(before.find("draft")!!.id, nb, "n1")
        assertEquals(1, after.tags.size)
        assertEquals(0, after.assignments.size)
        assertNotNull(after.find("Draft"))
    }

    @Test
    fun deleteTagRemovesEveryAssignment() {
        val before = index(Triple("draft", nb, "n1"), Triple("draft", page, "p1"), Triple("done", nb, "n2"))
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
        val i = index(Triple("draft", nb, "n1"))
        assertSame(i, i.deleteTag("nope"))
    }

    @Test
    fun tagsOfIsSortedAndScopedToOneTarget() {
        val i = index(
            Triple("zebra", nb, "n1"),
            Triple("Apple", nb, "n1"),
            Triple("other", page, "p1"),
        )
        assertEquals(listOf("Apple", "zebra"), i.tagsOf(nb, "n1").map { it.display })
        assertEquals(listOf("other"), i.tagsOf(page, "p1").map { it.display })
        assertEquals(emptyList<String>(), i.tagsOf(nb, "n2").map { it.display })
    }

    @Test
    fun suggestRanksExactThenPrefixThenSubstring() {
        val i = index(
            Triple("read", nb, "n1"),
            Triple("reading list", nb, "n1"),
            Triple("unread", nb, "n1"),
            Triple("zzz", nb, "n1"),
        )
        assertEquals(listOf("read", "reading list", "unread"), i.suggest("read").map { it.display })
        // A blank query offers the whole library, in browse order.
        assertEquals(listOf("read", "reading list", "unread", "zzz"), i.suggest("  ").map { it.display })
    }

    @Test
    fun capsRefuseWithoutChangingAnything() {
        var i = TagIndex.EMPTY
        for (n in 0 until ExtensionContract.MAX_TAGS) i = i.assign("tag $n", nb, "n1").index
        assertEquals(ExtensionContract.MAX_TAGS, i.tags.size)
        try {
            i.assign("one too many", nb, "n1"); fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(ExtensionContract.TAG_INDEX_FULL, expected.message)
        }
        // Nothing changed: the tag list is exactly as long as it was.
        assertEquals(ExtensionContract.MAX_TAGS, i.tags.size)
        // An EXISTING tag can still be attached elsewhere — the tag cap is not an assignment cap.
        val reused = i.assign("tag 0", nb, "n2")
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
                TagIndex.Assignment("0", nb, "n1"),
                TagIndex.Assignment("0", nb, "n1"),       // repeated
                TagIndex.Assignment("9", nb, "n1"),       // no such tag
                TagIndex.Assignment("0", 7, "n1"),        // unknown kind
                TagIndex.Assignment("0", nb, ""),         // no target
                TagIndex.Assignment("4", page, "p1"),
            ),
        )
        assertEquals(listOf("draft", "spaced out"), i.tags.map { it.display })
        assertEquals(2, i.assignments.size)
    }

    @Test
    fun filterAliveDropsDeadTargetsOnly() {
        val i = index(Triple("draft", nb, "n1"), Triple("draft", nb, "n2"), Triple("draft", page, "p1"))
        val alive = i.filterAlive(aliveNotebooks = setOf("n1"), alivePages = setOf("p1"))
        assertEquals(2, alive.assignments.size)
        // The tag itself is untouched — a dead assignment is not a dead tag.
        assertEquals(1, alive.tags.size)
    }

    @Test
    fun mintedIdsAreShortAndUnique() {
        var i = TagIndex.EMPTY
        for (n in 0 until 200) i = i.assign("tag $n", nb, "n1").index
        assertEquals(200, i.tags.map { it.id }.toSet().size)
        assertTrue(i.tags.all { it.id.length <= TagCodec.MAX_TAG_ID_CHARS })
    }
}
