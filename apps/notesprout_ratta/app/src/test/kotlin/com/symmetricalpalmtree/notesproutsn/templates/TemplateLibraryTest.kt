package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateLibraryTest {

    private fun row(
        id: String,
        name: String,
        type: String = ObjectType.TEMPLATE,
        kind: String? = null,
        parentId: String? = null,
    ) = ObjectSummary(
        id = id, type = type, name = name, parentId = parentId,
        createdAt = 0L, updatedAt = 0L, pageCount = null, flags = null, templateKind = kind,
    )

    // ── Sentinel ids ─────────────────────────────────────────────────────────

    @Test
    fun `the five sentinels are distinct, well-formed and recognised`() {
        assertEquals(5, TemplateLibrary.SENTINEL_IDS.size)
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        for (id in TemplateLibrary.SENTINEL_IDS) {
            assertTrue("$id is not a UUID", uuid.matches(id))
            assertTrue(TemplateLibrary.isSentinel(id))
        }
    }

    @Test
    fun `sentinels never collide with the list sentinels`() {
        assertFalse(TemplateLibrary.isSentinel(ListIds.PINNED_LIST_ID))
        assertFalse(TemplateLibrary.isSentinel(ListIds.CLIPBOARD_ID))
        assertFalse(TemplateLibrary.isSentinel("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `a real row id is not a sentinel`() {
        assertFalse(TemplateLibrary.isSentinel("8f2b1c40-1111-4222-8333-444455556666"))
    }

    // ── Reserved name ────────────────────────────────────────────────────────

    @Test
    fun `Default is reserved at the templates root, in any casing, trimmed`() {
        assertTrue(TemplateLibrary.isReservedName(null, "Default"))
        assertTrue(TemplateLibrary.isReservedName(null, "default"))
        assertTrue(TemplateLibrary.isReservedName(null, "DEFAULT"))
        assertTrue(TemplateLibrary.isReservedName(null, "  Default  "))
    }

    @Test
    fun `Default is an ordinary name anywhere but the root`() {
        assertFalse(TemplateLibrary.isReservedName("some-folder-id", "Default"))
    }

    @Test
    fun `only that one name is reserved`() {
        assertFalse(TemplateLibrary.isReservedName(null, "Default stuff"))
        assertFalse(TemplateLibrary.isReservedName(null, "Gen"))
        assertFalse(TemplateLibrary.isReservedName(null, "Templates"))
    }

    // ── Root composition ─────────────────────────────────────────────────────

    @Test
    fun `the root leads with Blank then Default, then the sorted rows`() {
        val rows = listOf(
            row("f1", "Aaa", type = ObjectType.TEMPLATE_FOLDER),
            row("t1", "Bbb"),
            row("t2", "Ccc"),
        )
        val cards = TemplateLibrary.rootCards("Blank", "Default", rows)

        assertEquals(5, cards.size)
        assertTrue(cards[0] is TemplateCard.Blank)
        assertTrue(cards[1] is TemplateCard.Defaults)
        assertEquals(ListIds.TEMPLATE_BLANK_ID, cards[0].id)
        assertEquals(ListIds.TEMPLATE_DEFAULT_ID, cards[1].id)
        assertEquals(listOf("Aaa", "Bbb", "Ccc"), cards.drop(2).map { it.name })
    }

    @Test
    fun `an empty root is still Blank and Default`() {
        val cards = TemplateLibrary.rootCards("Blank", "Default", emptyList())
        assertEquals(listOf("Blank", "Default"), cards.map { it.name })
    }

    @Test
    fun `the two synthetic cards lead whatever order the rows arrive in`() {
        // The sort control reorders the rows; it must never move the furniture.
        val descending = listOf(row("t2", "Zzz"), row("t1", "Aaa"))
        val cards = TemplateLibrary.rootCards("Blank", "Default", descending)
        assertEquals(listOf("Blank", "Default", "Zzz", "Aaa"), cards.map { it.name })
    }

    // ── Default ──────────────────────────────────────────────────────────────

    @Test
    fun `Default holds exactly the three built-in papers in a fixed order`() {
        val cards = TemplateLibrary.defaultCards("Lined", "Dotted", "Grid")
        assertEquals(3, cards.size)
        assertEquals(listOf("Lined", "Dotted", "Grid"), cards.map { it.name })
        assertEquals(
            listOf(TemplateKind.LINED, TemplateKind.DOTTED, TemplateKind.GRID),
            cards.map { (it as TemplateCard.BuiltIn).kind },
        )
        assertEquals(
            listOf(ListIds.TEMPLATE_LINED_ID, ListIds.TEMPLATE_DOTTED_ID, ListIds.TEMPLATE_GRID_ID),
            cards.map { it.id },
        )
        assertTrue(cards.all { it.isSentinel })
    }

    @Test
    fun `Blank is never a built-in paper`() {
        // BLANK writes no template row at all; it is the absence of paper, not a fourth paper.
        assertFalse(TemplateLibrary.BUILT_IN_KINDS.any { it.second == TemplateKind.BLANK })
    }

    // ── Row cards ────────────────────────────────────────────────────────────

    @Test
    fun `a row becomes a folder or a static card by its type`() {
        val cards = TemplateLibrary.rowCards(
            listOf(
                row("f1", "Place", type = ObjectType.TEMPLATE_FOLDER),
                row("t1", "Paper", kind = TemplateKind.GRID.name),
            )
        )
        assertTrue(cards[0] is TemplateCard.Folder)
        assertTrue(cards[1] is TemplateCard.Static)
        assertFalse(cards[0].isSentinel)
        assertFalse(cards[1].isSentinel)
    }

    @Test
    fun `a static card knows the kind it draws from, and when it is pixels`() {
        val fromKind = TemplateCard.Static(row("t1", "Ruled", kind = TemplateKind.LINED.name))
        assertEquals(TemplateKind.LINED, fromKind.baseKind)
        assertFalse(fromKind.isImage)

        val imported = TemplateCard.Static(row("t2", "Scan", kind = TemplateLibrary.KIND_IMAGE))
        assertNull(imported.baseKind)
        assertTrue(imported.isImage)
    }

    @Test
    fun `a kind this build cannot parse stays unknown rather than guessing Blank`() {
        // T1 / P2's rule: a template from a later version shows no claim about its paper.
        val foreign = TemplateCard.Static(row("t3", "Future", kind = "HEXAGONAL"))
        assertNull(foreign.baseKind)
        assertFalse(foreign.isImage)
    }

    // ── Duplicate naming ─────────────────────────────────────────────────────

    @Test
    fun `the first duplicate is just copy`() {
        assertEquals("Ruled copy", TemplateLibrary.duplicateName("Ruled", emptySet()))
    }

    @Test
    fun `later duplicates number from two`() {
        assertEquals("Ruled copy 2", TemplateLibrary.duplicateName("Ruled", setOf("Ruled copy")))
        assertEquals(
            "Ruled copy 3",
            TemplateLibrary.duplicateName("Ruled", setOf("Ruled copy", "Ruled copy 2")),
        )
    }

    @Test
    fun `a gap in the run is filled rather than skipped`() {
        assertEquals(
            "Ruled copy 2",
            TemplateLibrary.duplicateName("Ruled", setOf("Ruled copy", "Ruled copy 3")),
        )
    }

    @Test
    fun `duplicating a duplicate keeps going`() {
        assertEquals("Ruled copy copy", TemplateLibrary.duplicateName("Ruled copy", emptySet()))
    }

    @Test
    fun `matching is exact, because the database's duplicate check is`() {
        // countSiblingsNamed compares with `=`, and SQLite's default TEXT collation is
        // case-sensitive: "ruled copy" is not a collision the insert would refuse.
        assertEquals("Ruled copy", TemplateLibrary.duplicateName("Ruled", setOf("ruled copy")))
    }

    @Test
    fun `every produced name is still a legal name`() {
        val names = buildSet {
            add("Ruled copy")
            repeat(4) { add("Ruled copy ${it + 2}") }
        }
        val next = TemplateLibrary.duplicateName("Ruled", names)
        assertEquals("Ruled copy 6", next)
        assertTrue(
            com.symmetricalpalmtree.notesproutsn.library.NameRules.isValid(next)
        )
    }
}
