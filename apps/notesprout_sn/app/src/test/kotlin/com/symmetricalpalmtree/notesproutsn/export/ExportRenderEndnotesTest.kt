package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import com.symmetricalpalmtree.notesproutsn.notebook.StickyFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The endnote source walk over the fake DAO: which notes, in what order, from which page. */
class ExportRenderEndnotesTest {

    private val dao = FakeSoilDao()

    private fun row(
        id: String, parent: String, type: String, order: Int = 0,
        x: Float? = null, y: Float? = null, w: Float? = null, h: Float? = null, flags: Long? = null,
    ) = SoilObjectEntity(
        id = id, parentId = parent, type = type, order = order, createdAt = 1L, updatedAt = 1L,
        x = x, y = y, width = w, height = h, flags = flags,
    ).also { dao.rows[id] = it }

    private fun sticky(id: String, parent: String, order: Int, contentW: Int = 800, contentH: Int = 600, withStroke: Boolean = true) {
        row(id, parent, SoilSchema.TYPE_STICKY, order, x = 10f, y = 20f, w = 72f, h = 72f, flags = StickyFlags.pack(contentW, contentH))
        if (withStroke) row("$id-stroke", id, SoilSchema.TYPE_STROKE)
    }

    @Test
    fun walksPagesInOrderLooseThenWrappedAndSkipsEmptyNotes() = runBlocking {
        row("p1", "nb", SoilSchema.TYPE_PAGE, 0, w = 1404f, h = 1872f)
        row("p2", "nb", SoilSchema.TYPE_PAGE, 1, w = 1404f, h = 1872f)
        sticky("empty", "p1", 0, withStroke = false)
        sticky("late", "p2", 0)
        sticky("second", "p1", 5)
        sticky("first", "p1", 1)
        row("link", "p1", SoilSchema.TYPE_LINK, 0, x = 0f, y = 0f, w = 10f, h = 10f)
        sticky("wrapped", "link", 0, contentW = 0, contentH = 0)

        val pages = listOf(
            ExportRender.PageBake("p1", 1404, 1872, "", number = 1),
            ExportRender.PageBake("p2", 1404, 1872, "", number = 2),
        )
        val sources = ExportRender.endnoteSources(dao, pages)
        assertEquals(listOf("first", "second", "wrapped", "late"), sources.map { it.stickyId })
        assertEquals(listOf(1, 1, 1, 2), sources.map { it.fromPage })
        assertEquals(listOf(1, 1, 1, 2), sources.map { it.fromPageLabel })
        // The icon rect is page-absolute; the row's content size travels, 0 = none carried.
        assertEquals(10f, sources[0].iconL); assertEquals(82f, sources[0].iconR)
        assertEquals(800, sources[0].contentW)
        assertEquals(0, sources[2].contentW); assertEquals(1404, sources[2].pageW)
    }

    @Test
    fun aSketchBeforeAPageShiftsWhatItsNotesLinkTo() = runBlocking {
        // Arc 43 / K7: page 1's sketch is the bundle's second page, so page 2's note points at
        // page 3 — while the caption still says page 2, which is what the NOTEBOOK calls it.
        row("p1", "nb", SoilSchema.TYPE_PAGE, 0, w = 1404f, h = 1872f)
        row("p2", "nb", SoilSchema.TYPE_PAGE, 1, w = 1404f, h = 1872f)
        sticky("one", "p1", 0)
        sticky("two", "p2", 0)

        val pages = listOf(
            ExportRender.PageBake("p1", 1404, 1872, "", number = 1, hasSketch = true),
            ExportRender.PageBake("p2", 1404, 1872, "", number = 2),
        )
        val sources = ExportRender.endnoteSources(dao, pages)
        assertEquals(listOf("one", "two"), sources.map { it.stickyId })
        assertEquals(listOf(1, 3), sources.map { it.fromPage })
        assertEquals(listOf(1, 2), sources.map { it.fromPageLabel })
    }

    @Test
    fun noNotesWithContentIsNoWalkAtAll() = runBlocking {
        row("p1", "nb", SoilSchema.TYPE_PAGE, 0, w = 1404f, h = 1872f)
        sticky("empty", "p1", 0, withStroke = false)
        assertTrue(ExportRender.endnoteSources(dao, listOf(ExportRender.PageBake("p1", 1404, 1872, "", number = 1))).isEmpty())
    }
}
