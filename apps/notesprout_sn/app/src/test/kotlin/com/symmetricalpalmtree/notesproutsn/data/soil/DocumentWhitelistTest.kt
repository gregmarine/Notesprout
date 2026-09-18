package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arc-28 whitelist widening on [DocumentDao]'s two staleness sweeps: text, shape and sticky
 * rows now count as page content — but a sticky's content strokes, being parented to the sticky
 * rather than the page or a link, still fall outside the join at both levels ([DocumentDao] KDoc,
 * "a sticky's content strokes are not in the whitelist"). See [DocumentStalenessTest] for the
 * pre-existing sweep behaviour this extends.
 */
class DocumentWhitelistTest {

    private val rootId = "nb-root"
    private val pageA = "page-a"

    private class Fixture {
        val soil = FakeSoilDao()
        val docs = FakeDocumentDao(soil)
    }

    private suspend fun Fixture.put(id: String, parentId: String, type: String, updatedAt: Long) =
        soil.upsert(
            SoilObjectEntity(
                id = id, parentId = parentId, type = type, order = 0,
                createdAt = 1L, updatedAt = updatedAt,
            )
        )

    private suspend fun Fixture.notebook() {
        put("nb", SoilSchema.ROOT_PARENT, SoilSchema.TYPE_NOTEBOOK, 1L)
        put(pageA, rootId, SoilSchema.TYPE_PAGE, 1L)
        put("s-a", pageA, SoilSchema.TYPE_STROKE, 100L)
    }

    @Test
    fun `a newer text row raises the page watermark`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("t-a", pageA, SoilSchema.TYPE_TEXT, 5_000L)
        assertEquals(5_000L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a newer shape row raises the page watermark`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("sh-a", pageA, SoilSchema.TYPE_SHAPE, 5_000L)
        assertEquals(5_000L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a newer sticky icon row raises the page watermark`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("sn-a", pageA, SoilSchema.TYPE_STICKY, 5_000L)
        assertEquals(5_000L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a newer text, shape or sticky row raises the notebook watermark too`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("t-a", pageA, SoilSchema.TYPE_TEXT, 4_000L)
        f.put("sh-a", pageA, SoilSchema.TYPE_SHAPE, 5_000L)
        f.put("sn-a", pageA, SoilSchema.TYPE_STICKY, 3_000L)
        assertEquals(5_000L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `a newer stroke inside a sticky does not raise the page watermark`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("sn-a", pageA, SoilSchema.TYPE_STICKY, 200L)
        f.put("c-a", "sn-a", SoilSchema.TYPE_STROKE, 9_000L)   // deep inside the note
        // The sticky icon's own updatedAt (200) is what counts — the child's 9000 never reaches
        // the join because it is parented to the sticky, not the page or a link.
        assertEquals(200L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a newer stroke inside a sticky does not raise the notebook watermark either`() =
        runBlocking {
            val f = Fixture()
            f.notebook()
            f.put("sn-a", pageA, SoilSchema.TYPE_STICKY, 200L)
            f.put("c-a", "sn-a", SoilSchema.TYPE_STROKE, 9_000L)
            assertEquals(200L, f.docs.notebookMaxContentUpdatedAt(rootId))
        }

    /**
     * Arc 43 / K3, two rows since arc 45 / G2: a sketch raster is a *picture of* the page, not
     * content on it — the `document` rule applied to pixels. A drawing made after a draft was
     * written must not make the draft read "the page has changed since", because the page's words
     * did not change: nothing in a sketch ever reaches the recognized text a document is seeded
     * from. Neither raster does, and neither does a leftover arc-43 row.
     */
    @Test
    fun `a newer sketch raster raises neither watermark`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("sk-g", pageA, SoilSchema.TYPE_SKETCH_GRAPHITE, 9_000L)
        f.put("sk-i", pageA, SoilSchema.TYPE_SKETCH_INK, 9_001L)
        f.put("sk-old", pageA, SoilSchema.TYPE_SKETCH_DEAD, 9_002L)
        assertEquals(100L, f.docs.maxContentUpdatedAt(pageA))
        assertEquals(100L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `a newer stroke inside a sticky wrapped in a link still does not count`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("l-a", pageA, SoilSchema.TYPE_LINK, 150L)
        f.put("sn-a", "l-a", SoilSchema.TYPE_STICKY, 160L)
        f.put("c-a", "sn-a", SoilSchema.TYPE_STROKE, 9_000L)
        assertEquals(160L, f.docs.maxContentUpdatedAt(pageA))
    }
}
