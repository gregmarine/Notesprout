package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAssemblyTest {

    /** Notebook ids must be canonical UUIDs to carry a tag, so the fixtures use real ones and map
     *  them to the short names the assertions read by. */
    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val p2 = "bbbbbbbb-2222-4222-8222-222222222222"

    private fun row(id: String, name: String, type: String) = ObjectSummary(
        id = id, type = type, name = name, parentId = null,
        createdAt = 0L, updatedAt = 0L, pageCount = null, flags = null, templateKind = null,
    )

    private fun folder(id: String, name: String) = row(id, name, ObjectType.FOLDER)
    private fun notebook(id: String, name: String) = row(id, name, ObjectType.NOTEBOOK)

    /** Arc 20's shape: every card in one list, folders then notebooks. */
    private fun ids(
        folders: List<ObjectSummary>,
        notebooks: List<ObjectSummary>,
        query: String,
        tags: TagIndex? = null,
    ): List<String> {
        val shelf = SearchAssembly.rank(folders, notebooks, query, tags)
        return shelf.folders.map { it.id } + shelf.notebooks.map { it.notebook.id }
    }

    private fun tagged(vararg assigns: Triple<String, String, String?>): TagIndex {
        var i = TagIndex.EMPTY
        for ((text, nb, page) in assigns) i = i.assign(text, nb, page).index
        return i
    }

    // ── Arc 20: names ────────────────────────────────────────────────────────

    /** The library's standing rule outranks the score: containers before contents, everywhere. */
    @Test
    fun `every matching folder comes before every matching notebook`() {
        val folders = listOf(folder("f1", "Work notes"))
        val notebooks = listOf(notebook(n1, "Work"))
        // "Work" is the exact answer and it is a notebook — it still sorts second.
        assertEquals(listOf("f1", n1), ids(folders, notebooks, "work"))
    }

    @Test
    fun `relevance orders each group`() {
        val folders = listOf(folder("f1", "Meeting Notes"), folder("f2", "Meet"))
        val notebooks = listOf(notebook(n1, "Amount Meeting"), notebook(n2, "Meeting"))
        assertEquals(listOf("f2", "f1", n2, n1), ids(folders, notebooks, "meet"))
    }

    @Test
    fun `non-matching rows are dropped from both groups`() {
        val folders = listOf(folder("f1", "Groceries"), folder("f2", "Meetings"))
        val notebooks = listOf(notebook(n1, "Recipes"), notebook(n2, "Meet"))
        assertEquals(listOf("f2", n2), ids(folders, notebooks, "meet"))
    }

    @Test
    fun `a blank query finds nothing at all`() {
        val folders = listOf(folder("f1", "Work"))
        val notebooks = listOf(notebook(n1, "Work"))
        assertTrue(ids(folders, notebooks, "   ").isEmpty())
    }

    @Test
    fun `an empty library is not an error`() {
        assertTrue(ids(emptyList(), emptyList(), "work").isEmpty())
    }

    /** No tag extension is arc 20's shelf exactly — not a degraded one, and nothing says otherwise. */
    @Test
    fun `without a tag index the shelf is names only`() {
        val shelf = SearchAssembly.rank(emptyList(), listOf(notebook(n1, "Work")), "work", tags = null)
        assertEquals(listOf(n1), shelf.notebooks.map { it.notebook.id })
        assertNull(shelf.notebooks.single().matchedTag)
        assertTrue(shelf.pages.isEmpty())
    }

    // ── Arc 21 / W4: tags ────────────────────────────────────────────────────

    @Test
    fun `a notebook is found by a tag on it`() {
        val notebooks = listOf(notebook(n1, "Trip Journal"))
        val shelf = SearchAssembly.rank(emptyList(), notebooks, "packing", tagged(Triple("packing", n1, null)))
        assertEquals(listOf(n1), shelf.notebooks.map { it.notebook.id })
        assertEquals("packing", shelf.notebooks.single().matchedTag)
    }

    /** The subtitle answers "why is this here", so a name match — which already answers it — keeps
     *  the plain folder line even when the notebook also carries the matching tag. */
    @Test
    fun `the matched tag is shown only when the name did not match`() {
        val notebooks = listOf(notebook(n1, "Packing Lists"), notebook(n2, "Trip Journal"))
        val tags = tagged(Triple("packing", n1, null), Triple("packing", n2, null))
        val shelf = SearchAssembly.rank(emptyList(), notebooks, "packing", tags)
        val byId = shelf.notebooks.associateBy { it.notebook.id }
        assertNull("a name match explains itself", byId.getValue(n1).matchedTag)
        assertEquals("packing", byId.getValue(n2).matchedTag)
    }

    /** Matched by both, listed once — at whichever rank was better. */
    @Test
    fun `a notebook matching by name and by tag appears once`() {
        val notebooks = listOf(notebook(n1, "Packing Lists"))
        val shelf = SearchAssembly.rank(
            emptyList(), notebooks, "packing", tagged(Triple("packing", n1, null)),
        )
        assertEquals(1, shelf.notebooks.size)
    }

    /** A tag can outrank a name: an exact tag beats a name that only matches as a subsequence. */
    @Test
    fun `the better of the name and the tag decides the order`() {
        val notebooks = listOf(notebook(n1, "Planning and packing kit"), notebook(n2, "Trip Journal"))
        // "packing" is a whole word inside n1's name, and n2's tag is the query exactly.
        val shelf = SearchAssembly.rank(
            emptyList(), notebooks, "packing", tagged(Triple("packing", n2, null)),
        )
        assertEquals(listOf(n2, n1), shelf.notebooks.map { it.notebook.id })
    }

    // ── Page hits ────────────────────────────────────────────────────────────

    @Test
    fun `a tagged page becomes its own card, naming its notebook`() {
        val notebooks = listOf(notebook(n1, "Trip Journal"))
        val shelf = SearchAssembly.rank(
            emptyList(), notebooks, "packing", tagged(Triple("packing", n1, p1)),
        )
        // The notebook itself was not tagged and its name does not match, so it is not on the shelf.
        assertTrue(shelf.notebooks.isEmpty())
        val hit = shelf.pages.single()
        assertEquals(n1, hit.notebook.id)
        assertEquals(p1, hit.pageId)
        assertEquals("packing", hit.matchedTag)
    }

    /** A page carrying two matching tags is still one page — one card, named by the better tag. */
    @Test
    fun `one card per page, not per tag`() {
        val notebooks = listOf(notebook(n1, "Trip Journal"))
        val tags = tagged(Triple("pack", n1, p1), Triple("packing list", n1, p1))
        val shelf = SearchAssembly.rank(emptyList(), notebooks, "pack", tags)
        assertEquals(1, shelf.pages.size)
        assertEquals("pack", shelf.pages.single().matchedTag)
    }

    /** Two pages of one notebook are two cards; the notebook's own tag is a third, separate row. */
    @Test
    fun `pages and their notebook are separate rows`() {
        val notebooks = listOf(notebook(n1, "Trip Journal"))
        val tags = tagged(
            Triple("packing", n1, null),
            Triple("packing", n1, p1),
            Triple("packing", n1, p2),
        )
        val shelf = SearchAssembly.rank(emptyList(), notebooks, "packing", tags)
        assertEquals(1, shelf.notebooks.size)
        assertEquals(listOf(p1, p2), shelf.pages.map { it.pageId }.sorted())
    }

    /**
     * An assignment naming a notebook the index no longer lists never surfaces — no filtering pass
     * needed, because tags are read *through* the notebook listing.
     */
    @Test
    fun `a tag on a notebook that is gone surfaces nothing`() {
        val tags = tagged(Triple("packing", n1, null), Triple("packing", n1, p1))
        val shelf = SearchAssembly.rank(emptyList(), emptyList(), "packing", tags)
        assertTrue(shelf.notebooks.isEmpty())
        assertTrue(shelf.pages.isEmpty())
    }

    @Test
    fun `a tag that does not match the query brings nothing with it`() {
        val notebooks = listOf(notebook(n1, "Trip Journal"))
        val shelf = SearchAssembly.rank(
            emptyList(), notebooks, "packing", tagged(Triple("recipes", n1, p1)),
        )
        assertTrue(shelf.pages.isEmpty())
        assertTrue(shelf.notebooks.isEmpty())
    }

    /** Tags rank through the same matcher: subsequence, not typo tolerance (arc 20's rule). */
    @Test
    fun `tags match fuzzily and rank by relevance`() {
        val notebooks = listOf(notebook(n1, "A"), notebook(n2, "B"))
        val tags = tagged(Triple("meeting notes", n1, p1), Triple("mtg", n2, p2))
        val shelf = SearchAssembly.rank(emptyList(), notebooks, "mtg", tags)
        // "mtg" is exactly one tag and a subsequence of the other; exact wins.
        assertEquals(listOf(p2, p1), shelf.pages.map { it.pageId })
        // Letters that are not in order are not a subsequence, and find neither. ("mgt" *would*
        // find "meeting notes" — m·g·t are all there in that order — which is the honest edge of
        // arc 20's rule, not a bug: a subsequence forgives a dropped letter, never a moved one.)
        assertTrue(SearchAssembly.rank(emptyList(), notebooks, "gm", tags).pages.isEmpty())
    }

    @Test
    fun `isEmpty covers all three groups`() {
        assertTrue(SearchAssembly.rank(emptyList(), emptyList(), "x").isEmpty)
        val shelf = SearchAssembly.rank(
            emptyList(), listOf(notebook(n1, "A")), "packing", tagged(Triple("packing", n1, p1)),
        )
        assertTrue(!shelf.isEmpty)
    }
}
