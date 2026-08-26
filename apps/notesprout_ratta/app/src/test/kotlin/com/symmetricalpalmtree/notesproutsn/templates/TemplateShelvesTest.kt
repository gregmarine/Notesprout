package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateShelvesTest {

    private fun row(id: String, name: String, type: String = ObjectType.TEMPLATE) = ObjectSummary(
        id = id, type = type, name = name, parentId = null,
        createdAt = 0L, updatedAt = 0L, pageCount = null, flags = null, templateKind = "IMAGE",
    )

    private val labels = listOf("Lined", "Dotted", "Grid")

    // ── isPinnable ───────────────────────────────────────────────────────────

    @Test
    fun `the three built-ins are pinnable`() {
        assertTrue(TemplateShelves.isPinnable(ListIds.TEMPLATE_LINED_ID))
        assertTrue(TemplateShelves.isPinnable(ListIds.TEMPLATE_DOTTED_ID))
        assertTrue(TemplateShelves.isPinnable(ListIds.TEMPLATE_GRID_ID))
    }

    /** Blank is the absence of paper and is already card #1 at the root, forever; the Default
     *  folder is a place. Neither is a thing to put on a shelf of paper. */
    @Test
    fun `blank and the default folder are not pinnable`() {
        assertFalse(TemplateShelves.isPinnable(ListIds.TEMPLATE_BLANK_ID))
        assertFalse(TemplateShelves.isPinnable(ListIds.TEMPLATE_DEFAULT_ID))
    }

    @Test
    fun `an ordinary row id is pinnable`() {
        assertTrue(TemplateShelves.isPinnable("11111111-2222-3333-4444-555555555555"))
    }

    // ── pinnedCards ──────────────────────────────────────────────────────────

    @Test
    fun `built-ins lead, in their fixed order, whatever the sort did to the rows`() {
        val cards = TemplateShelves.pinnedCards(
            pinnedIds = setOf(ListIds.TEMPLATE_GRID_ID, ListIds.TEMPLATE_LINED_ID, "r1"),
            sortedRows = listOf(row("r1", "Aardvark")),
            builtInLabels = labels,
        )
        assertEquals(listOf(ListIds.TEMPLATE_LINED_ID, ListIds.TEMPLATE_GRID_ID, "r1"), cards.map { it.id })
        assertEquals(TemplateKind.LINED, (cards[0] as TemplateCard.BuiltIn).kind)
    }

    @Test
    fun `an unpinned built-in is left off`() {
        val cards = TemplateShelves.pinnedCards(setOf("r1"), listOf(row("r1", "Ruled")), labels)
        assertEquals(listOf("r1"), cards.map { it.id })
    }

    /** A folder can never be pinned, but a corrupt or hand-edited list must not put one on a shelf
     *  whose taps mean "pick this paper". */
    @Test
    fun `a folder row among the pinned rows is dropped`() {
        val cards = TemplateShelves.pinnedCards(
            pinnedIds = setOf("f1"),
            sortedRows = listOf(row("f1", "Somewhere", type = ObjectType.TEMPLATE_FOLDER)),
            builtInLabels = labels,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `an empty pinned list is an empty shelf`() {
        assertTrue(TemplateShelves.pinnedCards(emptySet(), emptyList(), labels).isEmpty())
    }

    // ── recentIds ────────────────────────────────────────────────────────────

    @Test
    fun `keeps the stored order rather than sorting`() {
        val entries = listOf(RecentEntry("b", 1L), RecentEntry("a", 2L), RecentEntry("c", 3L))
        assertEquals(listOf("b", "a", "c"), TemplateShelves.recentIds(entries, setOf("a", "b", "c")))
    }

    @Test
    fun `drops ids whose rows are gone`() {
        val entries = listOf(RecentEntry("a", 1L), RecentEntry("gone", 2L), RecentEntry("b", 3L))
        assertEquals(listOf("a", "b"), TemplateShelves.recentIds(entries, setOf("a", "b")))
    }

    /** The asymmetry that makes this file exist: a built-in has no row, so an aliveness test that
     *  went to the database alone would drop the three papers most likely to be on the shelf. */
    @Test
    fun `keeps a built-in even though no row exists for it`() {
        val entries = listOf(RecentEntry(ListIds.TEMPLATE_GRID_ID, 1L), RecentEntry("a", 2L))
        assertEquals(
            listOf(ListIds.TEMPLATE_GRID_ID, "a"),
            TemplateShelves.recentIds(entries, setOf("a")),
        )
    }

    @Test
    fun `blank is never kept, even if a stale store names it`() {
        val entries = listOf(RecentEntry(ListIds.TEMPLATE_BLANK_ID, 1L))
        assertTrue(TemplateShelves.recentIds(entries, emptySet()).isEmpty())
    }

    @Test
    fun `a duplicated id appears once, newest first`() {
        val entries = listOf(RecentEntry("a", 3L), RecentEntry("b", 2L), RecentEntry("a", 1L))
        assertEquals(listOf("a", "b"), TemplateShelves.recentIds(entries, setOf("a", "b")))
    }

    // ── pruneable ────────────────────────────────────────────────────────────

    /** What goes to `RecentsPrefs.pruneDeleted`. A sentinel missing from this set would be swept
     *  out of the store permanently the first time the shelf was opened. */
    @Test
    fun `pruneable keeps every built-in alongside the alive rows`() {
        val keep = TemplateShelves.pruneable(setOf("a"))
        assertTrue("a" in keep)
        assertTrue(ListIds.TEMPLATE_LINED_ID in keep)
        assertTrue(ListIds.TEMPLATE_DOTTED_ID in keep)
        assertTrue(ListIds.TEMPLATE_GRID_ID in keep)
        assertFalse(ListIds.TEMPLATE_BLANK_ID in keep)
    }

    // ── rowIdsAmong ──────────────────────────────────────────────────────────

    @Test
    fun `keeps the sentinels out of the ids the database is asked about`() {
        val ids = listOf(ListIds.TEMPLATE_GRID_ID, "a", ListIds.TEMPLATE_LINED_ID, "b")
        assertEquals(listOf("a", "b"), TemplateShelves.rowIdsAmong(ids))
    }

    // ── search composition ───────────────────────────────────────────────────

    @Test
    fun `search finds a built-in by its label, case-insensitively`() {
        val cards = TemplateShelves.searchSentinelCards("grid", "Blank", labels)
        assertEquals(listOf(ListIds.TEMPLATE_GRID_ID), cards.map { it.id })
    }

    /** Not pinnable, but findable: a user who types "blank" and gets nothing has been told
     *  something false about a card that is right there. */
    @Test
    fun `search finds blank`() {
        val cards = TemplateShelves.searchSentinelCards("bla", "Blank", labels)
        assertEquals(listOf(ListIds.TEMPLATE_BLANK_ID), cards.map { it.id })
    }

    @Test
    fun `search returns the built-ins in their fixed order`() {
        // "d" is in Dotted and in Lined ("Lined" has no d... it does not) — use a shared letter.
        val cards = TemplateShelves.searchSentinelCards("i", "Blank", labels)
        assertEquals(listOf(ListIds.TEMPLATE_LINED_ID, ListIds.TEMPLATE_GRID_ID), cards.map { it.id })
    }

    @Test
    fun `search matching nothing composes no sentinel cards`() {
        assertTrue(TemplateShelves.searchSentinelCards("zzz", "Blank", labels).isEmpty())
    }

    @Test
    fun `a search shelf holds templates, never folders`() {
        val rows = listOf(
            row("f1", "Grid folder", type = ObjectType.TEMPLATE_FOLDER),
            row("t1", "Grid scan"),
        )
        assertEquals(listOf("t1"), TemplateShelves.searchRowCards(rows).map { it.id })
    }

    // ── isPlace ──────────────────────────────────────────────────────────────

    @Test
    fun `the default folder is a place`() {
        assertTrue(TemplateShelves.isPlace(ListIds.TEMPLATE_DEFAULT_ID))
        assertFalse(TemplateShelves.isPlace(ListIds.TEMPLATE_GRID_ID))
    }
}
