package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two watermark sweeps (arc 19 / M2) — what counts as "the page has changed" and what does not.
 * The whitelist is the load-bearing part: a `document` row inside
 * [DocumentDao.maxContentUpdatedAt] would make every document instantly stale against itself, and a
 * page-parented one missing from [DocumentDao.notebookMaxContentUpdatedAt] would let an edited page
 * document sit invisibly under a merged draft that no longer matches it. **Soft-deleted rows count
 * at every level** (og's rule): an erase is a change, and so is a deleted page.
 *
 * The comparison itself (watermark vs. sweep) is deliberately **not** here: the repository exposes
 * the stored watermark and the DAO answers the maxima; whoever needs "is this stale" combines them.
 */
class DocumentStalenessTest {

    private val rootId = "nb-root"
    private val pageA = "page-a"
    private val pageB = "page-b"

    private class Fixture {
        val soil = FakeSoilDao()
        val docs = FakeDocumentDao(soil)
    }

    private suspend fun Fixture.put(
        id: String,
        parentId: String,
        type: String,
        updatedAt: Long,
        deletedAt: Long? = null,
    ) = soil.upsert(
        SoilObjectEntity(
            id = id, parentId = parentId, type = type, order = 0,
            createdAt = 1L, updatedAt = updatedAt, deletedAt = deletedAt,
        )
    )

    /** Root, two live pages, one stroke each — the floor every case builds on. */
    private suspend fun Fixture.notebook() {
        put("nb", SoilSchema.ROOT_PARENT, SoilSchema.TYPE_NOTEBOOK, 1L)
        put(pageA, rootId, SoilSchema.TYPE_PAGE, 1L)
        put(pageB, rootId, SoilSchema.TYPE_PAGE, 1L)
        put("s-a", pageA, SoilSchema.TYPE_STROKE, 100L)
        put("s-b", pageB, SoilSchema.TYPE_STROKE, 200L)
    }

    // ── The page sweep ───────────────────────────────────────────────────────

    @Test
    fun `an empty page has no watermark rather than a null one`() = runBlocking {
        val f = Fixture()
        f.notebook()
        assertEquals(0L, f.docs.maxContentUpdatedAt("page-empty"))
    }

    @Test
    fun `the sweep takes the page's own strokes, headings and links`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("h-a", pageA, SoilSchema.TYPE_HEADING, 300L)
        f.put("l-a", pageA, SoilSchema.TYPE_LINK, 400L)
        assertEquals(400L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a link's wrapped child counts — wrapping must not hide ink from the sweep`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("l-a", pageA, SoilSchema.TYPE_LINK, 150L)
        f.put("s-wrapped", "l-a", SoilSchema.TYPE_STROKE, 900L)
        assertEquals(900L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `the page's document is excluded — a document never invalidates itself`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("doc-a", pageA, SoilSchema.TYPE_DOCUMENT, 9_000L)
        assertEquals(100L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `another page's content is not this page's`() = runBlocking {
        val f = Fixture()
        f.notebook()
        assertEquals(100L, f.docs.maxContentUpdatedAt(pageA))
        assertEquals(200L, f.docs.maxContentUpdatedAt(pageB))
    }

    @Test
    fun `an erased stroke raises the watermark — an erase is a change to the page`() = runBlocking {
        // og's rule, and the reason neither sweep filters on `deletedAt`: a soft-delete stamps
        // `updatedAt` with the deletion time. Live-only would make erasing a page's ink invisible
        // to the draft written from it.
        val f = Fixture()
        f.notebook()
        f.put("s-erased", pageA, SoilSchema.TYPE_STROKE, 9_000L, deletedAt = 9_000L)
        assertEquals(9_000L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `a wrapped child counts even once its link has been erased`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("l-a", pageA, SoilSchema.TYPE_LINK, 150L, deletedAt = 150L)
        f.put("s-wrapped", "l-a", SoilSchema.TYPE_STROKE, 9_000L, deletedAt = 9_000L)
        assertEquals(9_000L, f.docs.maxContentUpdatedAt(pageA))
    }

    @Test
    fun `the purge takes the erase's evidence with it — the accepted arc-17 wrinkle`() = runBlocking {
        // Arc 17's close-time purge hard-deletes soft-deleted rows, so staleness raised by an erase
        // lasts until the notebook's next close. Simulated the only honest way: the row is gone
        // from the file. After it, the maximum describes what remains — a draft may read current
        // again. Pinned deliberately so nobody "fixes" it into keeping erased rows forever.
        val f = Fixture()
        f.notebook()
        f.put("s-erased", pageA, SoilSchema.TYPE_STROKE, 9_000L, deletedAt = 9_000L)
        assertEquals(9_000L, f.docs.maxContentUpdatedAt(pageA))
        f.soil.rows.remove("s-erased")
        assertEquals(100L, f.docs.maxContentUpdatedAt(pageA))
    }

    // ── The notebook sweep ───────────────────────────────────────────────────

    @Test
    fun `the notebook sweep reaches every live page`() = runBlocking {
        val f = Fixture()
        f.notebook()
        assertEquals(200L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `a page document counts — an edited draft means the pages have changed`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("doc-a", pageA, SoilSchema.TYPE_DOCUMENT, 5_000L)
        assertEquals(5_000L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `the notebook document is excluded — the merge never invalidates itself`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("doc-nb", rootId, SoilSchema.TYPE_DOCUMENT, 9_000L)
        assertEquals(200L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `wrapped children count notebook-wide too`() = runBlocking {
        val f = Fixture()
        f.notebook()
        f.put("l-b", pageB, SoilSchema.TYPE_LINK, 250L)
        f.put("s-wrapped", "l-b", SoilSchema.TYPE_STROKE, 800L)
        assertEquals(800L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `a deleted page marks the notebook sweep — deleting a page is "pages have changed"`() = runBlocking {
        // The page row, its ink and its document all carry the delete time; the sweep reaches
        // through the dead page to see them (no `deletedAt` clause at any level).
        val f = Fixture()
        f.notebook()
        f.put("page-gone", rootId, SoilSchema.TYPE_PAGE, 300L, deletedAt = 300L)
        f.put("s-gone", "page-gone", SoilSchema.TYPE_STROKE, 9_000L, deletedAt = 9_000L)
        f.put("doc-gone", "page-gone", SoilSchema.TYPE_DOCUMENT, 9_500L, deletedAt = 9_500L)
        assertEquals(9_500L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `once the purge has taken the deleted page, the sweep describes what is left`() = runBlocking {
        // The notebook-wide half of the arc-17 wrinkle — the same accepted consequence as on a page.
        val f = Fixture()
        f.notebook()
        f.put("page-gone", rootId, SoilSchema.TYPE_PAGE, 300L, deletedAt = 300L)
        f.put("s-gone", "page-gone", SoilSchema.TYPE_STROKE, 9_000L, deletedAt = 9_000L)
        assertEquals(9_000L, f.docs.notebookMaxContentUpdatedAt(rootId))
        f.soil.rows.remove("page-gone")
        f.soil.rows.remove("s-gone")
        assertEquals(200L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }

    @Test
    fun `a notebook with nothing on it answers zero`() = runBlocking {
        val f = Fixture()
        f.put("nb", SoilSchema.ROOT_PARENT, SoilSchema.TYPE_NOTEBOOK, 1L)
        f.put(pageA, rootId, SoilSchema.TYPE_PAGE, 1L)
        assertEquals(0L, f.docs.notebookMaxContentUpdatedAt(rootId))
    }
}
