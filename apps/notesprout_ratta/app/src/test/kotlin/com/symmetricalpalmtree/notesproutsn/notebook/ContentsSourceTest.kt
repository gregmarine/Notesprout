package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Contents gather (arc 4 / C1): heading rows → capped, document-ordered
 * outline items. Everything malformed degrades to "not listed" — never a crash.
 */
class ContentsSourceTest {

    private fun row(
        id: String,
        pageId: String,
        text: String? = "# $id",
        level: Int? = 1,
        y: Float = 0f,
        x: Float = 0f,
    ) = SoilObjectEntity(
        id = id, parentId = pageId, type = SoilSchema.TYPE_HEADING, order = 0,
        createdAt = 0L, updatedAt = 0L,
        text = text, flags = level, x = x, y = y, width = 100f, height = 40f,
    )

    private val pages = mapOf("p0" to 0, "p1" to 1, "p2" to 2)

    @Test
    fun `an item takes the stripped label and the row's level`() {
        val (items, truncated) = ContentsSource.items(
            listOf(row("a", "p1", text = "### Meeting notes", level = 3, y = 12f, x = 34f)),
            pages,
        )
        assertFalse(truncated)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("a", item.objectId)
        assertEquals("p1", item.pageId)
        assertEquals("Meeting notes", item.label)
        assertEquals(3, item.level)
        assertEquals(1, item.pageIndex)
        assertEquals(12f, item.y, 0f)
        assertEquals(34f, item.x, 0f)
    }

    @Test
    fun `the level is clamped the way the row mapping clamps it`() {
        val (items, _) = ContentsSource.items(
            listOf(row("deep", "p0", level = 9), row("shallow", "p0", level = 0, y = 10f)),
            pages,
        )
        assertEquals(listOf(6, 1), items.map { it.level })
    }

    @Test
    fun `a row on a page outside the map is dropped`() {
        val (items, _) = ContentsSource.items(
            listOf(row("kept", "p0"), row("gone", "deletedPage"), row("alsoGone", "")),
            pages,
        )
        assertEquals(listOf("kept"), items.map { it.objectId })
    }

    @Test
    fun `a malformed row is dropped without crashing`() {
        val (items, _) = ContentsSource.items(
            listOf(row("noText", "p0", text = null), row("kept", "p0", y = 10f)),
            pages,
        )
        assertEquals(listOf("kept"), items.map { it.objectId })
    }

    @Test
    fun `a label that strips to blank is dropped`() {
        val (items, _) = ContentsSource.items(
            listOf(
                row("emptyH2", "p0", text = "## ", level = 2),
                row("spaceOnly", "p0", text = "#  ", y = 10f),
                row("blank", "p0", text = "", y = 20f),
                row("kept", "p0", text = "# Real", y = 30f),
            ),
            pages,
        )
        assertEquals(listOf("kept"), items.map { it.objectId })
    }

    @Test
    fun `items come back in document order across pages`() {
        val (items, _) = ContentsSource.items(
            listOf(
                row("p2top", "p2", y = 5f),
                row("p0bottom", "p0", y = 900f),
                row("p1only", "p1", y = 400f),
                row("p0top", "p0", y = 40f),
            ),
            pages,
        )
        assertEquals(listOf("p0top", "p0bottom", "p1only", "p2top"), items.map { it.objectId })
    }

    @Test
    fun `x breaks a tie on y`() {
        val (items, _) = ContentsSource.items(
            listOf(
                row("right", "p0", y = 100f, x = 500f),
                row("left", "p0", y = 100f, x = 20f),
                row("middle", "p0", y = 100f, x = 260f),
            ),
            pages,
        )
        assertEquals(listOf("left", "middle", "right"), items.map { it.objectId })
        assertEquals(3, items.size)
    }

    @Test
    fun `the cap keeps the first entries in document order and reports the cut`() {
        // Written in reverse so the cap can only be right if the sort ran first.
        val rows = (ContentsSource.MAX_ENTRIES downTo 0).map { i ->
            row("h$i", "p0", text = "# h$i", y = i.toFloat())
        }
        assertEquals(ContentsSource.MAX_ENTRIES + 1, rows.size)
        val (items, truncated) = ContentsSource.items(rows, pages)
        assertTrue(truncated)
        assertEquals(ContentsSource.MAX_ENTRIES, items.size)
        assertEquals("h0", items.first().objectId)
        assertEquals("h${ContentsSource.MAX_ENTRIES - 1}", items.last().objectId)
    }

    @Test
    fun `exactly the cap is not truncated`() {
        val rows = (0 until ContentsSource.MAX_ENTRIES).map { i -> row("h$i", "p0", y = i.toFloat()) }
        val (items, truncated) = ContentsSource.items(rows, pages)
        assertFalse(truncated)
        assertEquals(ContentsSource.MAX_ENTRIES, items.size)
    }

    // --- Wrapped headings (arc 15): a heading parented to a link is placed on the link's page ---

    @Test
    fun `a heading wrapped in a link is listed on the link's page`() {
        val (items, _) = ContentsSource.items(
            listOf(row("wrapped", "lnk", text = "## Inside", level = 2, y = 50f)),
            pages,
            mapOf("lnk" to "p2"),
        )
        assertEquals(1, items.size)
        assertEquals("p2", items[0].pageId)
        assertEquals(2, items[0].pageIndex)
        assertEquals("Inside", items[0].label)
        assertEquals(2, items[0].level)
    }

    @Test
    fun `a wrapped heading keeps its page-absolute place in document order`() {
        // The wrap only re-parents; (x, y) stay page-absolute, so the wrapped one sorts between
        // the two loose headings on the same page rather than at either end.
        val (items, _) = ContentsSource.items(
            listOf(
                row("late", "p1", y = 300f),
                row("wrapped", "lnk", y = 200f),
                row("early", "p1", y = 100f),
            ),
            pages,
            mapOf("lnk" to "p1"),
        )
        assertEquals(listOf("early", "wrapped", "late"), items.map { it.objectId })
    }

    @Test
    fun `a heading wrapped in a link on a dead page is dropped`() {
        val (items, _) = ContentsSource.items(
            listOf(row("wrapped", "lnk")),
            pages,
            mapOf("lnk" to "gone"),
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `a heading whose parent is neither a page nor a live link is dropped`() {
        val (items, _) = ContentsSource.items(listOf(row("orphan", "lnk")), pages, emptyMap())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `a page id always wins over the link map`() {
        // Defensive: ids are UUIDs so a collision cannot happen, but the page hop must be the
        // first one tried — a heading on a page is never redirected.
        val (items, _) = ContentsSource.items(
            listOf(row("loose", "p1")),
            pages,
            mapOf("p1" to "p2"),
        )
        assertEquals("p1", items[0].pageId)
        assertEquals(1, items[0].pageIndex)
    }

    @Test
    fun `no rows is an empty, untruncated result`() {
        val (items, truncated) = ContentsSource.items(emptyList(), pages)
        assertTrue(items.isEmpty())
        assertFalse(truncated)
    }
}
