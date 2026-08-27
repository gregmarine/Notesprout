package com.symmetricalpalmtree.notesproutsn.data.index

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned-templates list and the search query, against the real [IndexRepository] over an
 * in-memory `objects` table (arc 13 / G5).
 *
 * `deleteTemplateFolderRecursive` is not exercised here — it opens a Room transaction and so needs
 * Android; its cascade is G1's, unchanged, and the one thing G5 added to that path (scrubbing the
 * recents entries) lives in the browser, above the repository.
 */
class TemplateShelfStoreTest {

    private val dao = FakeObjectDao()
    private val repo = IndexRepository(dao)

    private fun template(
        id: String,
        name: String,
        parentId: String? = null,
        image: ByteArray? = null,
    ) = runBlocking {
        dao.upsert(ObjectEntity(
            id = id, type = ObjectType.TEMPLATE, name = name, parentId = parentId,
            createdAt = 0L, updatedAt = 0L, templateKind = "IMAGE", blob = image,
        ))
    }

    // ── The list row ─────────────────────────────────────────────────────────

    /** Nothing is seeded at bootstrap; the list appears the first time something is pinned. */
    @Test
    fun `the pinned list row is created on demand and only once`() = runBlocking {
        assertNull(dao.byId(ListIds.TEMPLATE_PINNED_LIST_ID))
        template("t1", "Ruled")
        repo.pinTemplate("t1")
        val created = dao.byId(ListIds.TEMPLATE_PINNED_LIST_ID)
        assertEquals(ObjectType.LIST, created?.type)
        repo.pinTemplate("t2")
        assertEquals(created?.createdAt, dao.byId(ListIds.TEMPLATE_PINNED_LIST_ID)?.createdAt)
    }

    /** Two shelves, two lists: pinning paper must never put anything on the notebook shelf. */
    @Test
    fun `template pins do not touch the notebook pinned list`() = runBlocking {
        template("t1", "Ruled")
        repo.pinTemplate("t1")
        assertNull(dao.byId(ListIds.PINNED_LIST_ID))
        assertEquals(emptyList<String>(), repo.pinnedNotebookIds())
    }

    // ── Toggle ───────────────────────────────────────────────────────────────

    @Test
    fun `pin then unpin round trips`() = runBlocking {
        template("t1", "Ruled")
        assertFalse(repo.isTemplatePinned("t1"))
        repo.pinTemplate("t1")
        assertTrue(repo.isTemplatePinned("t1"))
        assertEquals(listOf("t1"), repo.pinnedTemplateIds())
        repo.unpinTemplate("t1")
        assertFalse(repo.isTemplatePinned("t1"))
        assertEquals(emptyList<String>(), repo.pinnedTemplateIds())
    }

    /** On e-ink an unguarded row gets double-tapped; a second pin must not add a second edge. */
    @Test
    fun `pinning twice adds one edge`() = runBlocking {
        template("t1", "Ruled")
        repo.pinTemplate("t1")
        repo.pinTemplate("t1")
        assertEquals(listOf("t1"), repo.pinnedTemplateIds())
    }

    @Test
    fun `unpinning something that was never pinned is a no-op`() = runBlocking {
        repo.unpinTemplate("t1")
        assertEquals(emptyList<String>(), repo.pinnedTemplateIds())
    }

    @Test
    fun `pinned ids come back in pin order`() = runBlocking {
        listOf("t1", "t2", "t3").forEach { template(it, it) ; repo.pinTemplate(it) }
        assertEquals(listOf("t1", "t2", "t3"), repo.pinnedTemplateIds())
    }

    /** The built-ins have no rows and never will; the list must carry their sentinel ids as-is. */
    @Test
    fun `a built-in pins by its sentinel id`() = runBlocking {
        repo.pinTemplate(ListIds.TEMPLATE_GRID_ID)
        assertTrue(repo.isTemplatePinned(ListIds.TEMPLATE_GRID_ID))
        assertEquals(listOf(ListIds.TEMPLATE_GRID_ID), repo.pinnedTemplateIds())
    }

    // ── Scrub on delete ──────────────────────────────────────────────────────

    @Test
    fun `deleting a template drops its pin edge`() = runBlocking {
        template("t1", "Ruled")
        template("t2", "Dots")
        repo.pinTemplate("t1")
        repo.pinTemplate("t2")
        repo.deleteTemplate("t1")
        assertEquals(listOf("t2"), repo.pinnedTemplateIds())
        assertFalse(repo.isTemplatePinned("t1"))
    }

    /**
     * The row survives (soft deletes are the family's rule) but its pixels do not — an imported
     * template's blob is up to 6 MiB nothing can read again, and the delete dialog calls it
     * permanent. The order matters and is asserted by the code, not here: `softDelete` before
     * `clearBlob`, so no interruption can leave an **alive** row with no pixels.
     */
    @Test
    fun `deleting a template drops its stored pixels but keeps the row`() = runBlocking {
        template("t1", "Scan", image = ByteArray(64) { 7 })
        repo.deleteTemplate("t1")
        val row = dao.byId("t1")
        assertNotNull(row)
        assertNull(row?.blob)
        assertNotNull(row?.deletedAt)
    }

    // ── Batch reads ──────────────────────────────────────────────────────────

    @Test
    fun `aliveTemplates skips deleted rows and unknown ids`() = runBlocking {
        template("t1", "Ruled")
        template("t2", "Dots")
        repo.deleteTemplate("t2")
        val alive = repo.aliveTemplates(listOf("t1", "t2", "nope"))
        assertEquals(setOf("t1"), alive.keys)
    }

    @Test
    fun `an empty id list never reaches the database`() = runBlocking {
        assertEquals(emptyMap<String, ObjectSummary>(), repo.aliveTemplates(emptyList()))
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `search matches a substring anywhere in the tree`() = runBlocking {
        template("t1", "Grid scan", parentId = "somewhere")
        template("t2", "Ruled")
        assertEquals(listOf("t1"), repo.searchTemplates("grid").map { it.id })
    }

    @Test
    fun `search is case-insensitive`() = runBlocking {
        template("t1", "Grid scan")
        assertEquals(1, repo.searchTemplates("GRID").size)
    }

    @Test
    fun `search never returns folders`() = runBlocking {
        template("t1", "Grid scan")
        dao.upsert(ObjectEntity(
            id = "f1", type = ObjectType.TEMPLATE_FOLDER, name = "Grid folder", parentId = null,
            createdAt = 0L, updatedAt = 0L,
        ))
        assertEquals(listOf("t1"), repo.searchTemplates("Grid").map { it.id })
    }

    @Test
    fun `search never returns deleted rows`() = runBlocking {
        template("t1", "Grid scan")
        repo.deleteTemplate("t1")
        assertTrue(repo.searchTemplates("grid").isEmpty())
    }

    /** `_` is a legal character in a template name AND LIKE's any-single-character wildcard. */
    @Test
    fun `an underscore in the query is a literal, not a wildcard`() = runBlocking {
        template("t1", "my_grid")
        template("t2", "myXgrid")
        assertEquals(listOf("t1"), repo.searchTemplates("my_grid").map { it.id })
    }

    @Test
    fun `a blank query reads nothing rather than everything`() = runBlocking {
        template("t1", "Ruled")
        assertTrue(repo.searchTemplates("").isEmpty())
        assertTrue(repo.searchTemplates("   ").isEmpty())
    }
}
