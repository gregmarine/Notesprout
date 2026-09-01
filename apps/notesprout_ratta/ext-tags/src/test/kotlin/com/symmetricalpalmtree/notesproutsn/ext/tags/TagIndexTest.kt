package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag screen's in-memory queries (arc 21 / W1, moved into this module and reduced to queries in
 * arc 22 / X3 — the edits went to `TagStore`, where a statement and a transaction make them true).
 * What is left is what the screen asks over and over, per keystroke and per repaint, and never asks
 * the store for.
 */
class TagIndexTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private var minted = 0

    /** A tag with a fresh id — ids are UUIDs since X3 and mean nothing outside the store. */
    private fun tag(display: String): TagRecord {
        minted++
        return TagRecord("%08x-1111-4111-8111-111111111111".format(minted), display)
    }

    private fun index(vararg pairs: Pair<TagRecord, List<AssignmentRecord>>): TagIndex =
        TagIndex(pairs.map { it.first }, pairs.flatMap { it.second })

    private fun on(tag: TagRecord, notebookId: String, pageId: String? = null) =
        AssignmentRecord(tag.id, notebookId, pageId ?: "")

    @Test
    fun findIsByIdentityNotBySpelling() {
        val reading = tag("Reading List")
        val index = index(reading to emptyList())
        assertEquals(reading.id, index.find("  reading   list ")!!.id)
        assertEquals("Reading List", index.find("READING LIST")!!.display)
        assertNull(index.find("nope"))
    }

    @Test
    fun tagIsById() {
        val draft = tag("draft")
        val index = index(draft to emptyList())
        assertEquals("draft", index.tag(draft.id)!!.display)
        assertNull(index.tag(n1))
    }

    @Test
    fun sortedTagsIsTheBrowseOrder() {
        val zebra = tag("zebra")
        val apple = tag("Apple")
        val index = index(zebra to emptyList(), apple to emptyList())
        assertEquals(listOf("Apple", "zebra"), index.sortedTags().map { it.display })
    }

    @Test
    fun tagsOfIsSortedAndScopedToOneTarget() {
        val zebra = tag("zebra")
        val apple = tag("Apple")
        val other = tag("other")
        val index = index(
            zebra to listOf(on(zebra, n1)),
            apple to listOf(on(apple, n1)),
            other to listOf(on(other, n1, p1)),
        )
        assertEquals(listOf("Apple", "zebra"), index.tagsOf(n1).map { it.display })
        assertEquals(listOf("other"), index.tagsOf(n1, p1).map { it.display })
        assertEquals(emptyList<String>(), index.tagsOf(n2).map { it.display })
    }

    /** The same page id under two notebooks is two different targets — only expressible because the
     *  notebook is stored (the W4 rule, unchanged by the move onto rows). */
    @Test
    fun aPageIsIdentifiedByItsNotebookToo() {
        val draft = tag("draft")
        val index = index(draft to listOf(on(draft, n1, p1), on(draft, n2, p1)))
        assertEquals(listOf("draft"), index.tagsOf(n1, p1).map { it.display })
        assertEquals(listOf("draft"), index.tagsOf(n2, p1).map { it.display })
        assertTrue(index.isAssigned(draft.id, n1, p1))
        assertTrue(index.isAssigned(draft.id, n2, p1))
    }

    @Test
    fun isAssignedIsScopedToTheWholeTarget() {
        val draft = tag("draft")
        val index = index(draft to listOf(on(draft, n1)))
        assertTrue(index.isAssigned(draft.id, n1))
        assertTrue(!index.isAssigned(draft.id, n1, p1))
        assertTrue(!index.isAssigned(draft.id, n2))
    }

    @Test
    fun suggestRanksExactThenPrefixThenSubstring() {
        val read = tag("read")
        val list = tag("reading list")
        val unread = tag("unread")
        val zzz = tag("zzz")
        val index = index(
            read to emptyList(), list to emptyList(), unread to emptyList(), zzz to emptyList(),
        )
        assertEquals(listOf("read", "reading list", "unread"), index.suggest("read").map { it.display })
        // A blank query offers the whole library, in browse order.
        assertEquals(
            listOf("read", "reading list", "unread", "zzz"),
            index.suggest("  ").map { it.display },
        )
    }

    /** The store's UNIQUE index makes this unreachable from a real read; the tie-break is here so a
     *  hand-built index behaves the way a stored one does. */
    @Test
    fun aRepeatedIdentityKeepsTheFirst() {
        val first = tag("Draft")
        val second = tag("draft")
        val index = index(first to emptyList(), second to emptyList())
        assertEquals(first.id, index.find("DRAFT")!!.id)
        assertNotNull(index.tag(second.id))
    }

    @Test
    fun emptyIsEmpty() {
        assertTrue(TagIndex.EMPTY.tags.isEmpty())
        assertTrue(TagIndex.EMPTY.assignments.isEmpty())
        assertTrue(TagIndex.EMPTY.sortedTags().isEmpty())
        assertNull(TagIndex.EMPTY.find("draft"))
        assertEquals(emptyList<String>(), TagIndex.EMPTY.suggest("x").map { it.display })
    }
}
