package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's read-only page gather (K2): loose content stays loose, wrapped content arrives
 * inside its [PageLink], soft-deleted rows never show, and page rows map to [PickerPage].
 * Seeded through the real stores over [FakeSoilDao] — the rows are exactly the screen's.
 */
class PageReadsTest {

    private val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "target")

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))

    private fun heading(id: String) = Heading(
        id = id, text = "## T-$id", level = 2, x = 1f, y = 2f, width = 100f, height = 40f, order = 0,
    )

    @Test
    fun `content splits loose rows from wrapped ones`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        val headings = HeadingStore(dao, writer)
        val links = LinkStore(dao, writer) { block -> block() }
        strokes.commit("page", stroke("s1"))
        strokes.commit("page", stroke("s2"))
        headings.create("page", heading("h1"))
        writer.drain()
        links.create(
            "page",
            PageLink(
                id = "l1", payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
                x = 0f, y = 0f, width = 120f, height = 60f, order = 0,
                strokes = listOf(stroke("s2")), headings = listOf(heading("h1")),
            ),
        )
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(listOf("s1"), content.strokes.map { it.id })
        assertTrue(content.headings.isEmpty())
        assertEquals(1, content.links.size)
        assertEquals(listOf("s2"), content.links[0].strokes.map { it.id })
        assertEquals(listOf("h1"), content.links[0].headings.map { it.id })
        writer.close()
    }

    @Test
    fun `soft-deleted content never previews`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        strokes.commit("page", stroke("s1"))
        strokes.commit("page", stroke("s2"))
        strokes.erase(listOf("s2"))
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(listOf("s1"), content.strokes.map { it.id })
        writer.close()
    }

    @Test
    fun `pages maps live page rows in order with their authored size`() = runBlocking {
        val dao = FakeSoilDao()
        val now = 1L
        dao.upsert(SoilObjectEntity(
            id = "p2", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 1,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f,
        ))
        dao.upsert(SoilObjectEntity(
            id = "p1", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 0,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f,
        ))
        dao.upsert(SoilObjectEntity(
            id = "p3", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 2,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f, deletedAt = now,
        ))

        val pages = PageReads.pages(dao, "nb")
        assertEquals(listOf("p1", "p2"), pages.map { it.id })
        assertEquals(1404, pages[0].width)
        assertEquals(1872, pages[0].height)
    }
}
