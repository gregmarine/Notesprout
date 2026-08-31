package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document's read and write rules (arc 19 / M2) — the three that can lose a user's writing or
 * lie about it: **blank means absent**, **a save that changes nothing writes nothing**, and **the
 * watermark moves only in [DocumentRepository.saveDrafted]**.
 *
 * Every case runs against both parents — a page id and the notebook root id — because the notebook
 * document is the same row shape one level up, and a rule that held for only one of them would be a
 * feature that worked on pages and quietly misbehaved on the merged draft.
 *
 * The last section pins the **batch read** the text export assembles through
 * ([DocumentDao.pageDocumentsIn], arc 19 / M11) against the same rows: it must answer exactly what
 * asking [DocumentDao.documentFor] page by page answered, or an export would quietly lose a page's
 * writing.
 */
class DocumentRepositoryTest {

    private val pageId = "page-1"
    private val rootId = "nb-root"
    private val watermark = 1_756_500_000_000L   // past Int.MAX_VALUE — the retype's whole point

    /** Both parents, run through every rule below. */
    private val parents = listOf(pageId, rootId)

    private class Fixture {
        val soil = FakeSoilDao()
        val docs = FakeDocumentDao(soil)
        var minted = 0
        val repo = DocumentRepository(docs, soil) { "doc-${minted++}" }
    }

    private fun fixture() = Fixture()

    private suspend fun Fixture.seed(parentId: String, text: String, flags: Long?, at: Long = 100L) {
        soil.upsert(
            SoilObjectEntity(
                id = "doc-existing-$parentId", parentId = parentId, type = SoilSchema.TYPE_DOCUMENT,
                order = 0, createdAt = at, updatedAt = at, text = text, flags = flags,
            )
        )
        soil.events.clear()
    }

    private fun Fixture.row(parentId: String) =
        soil.rows.values.singleOrNull { it.type == SoilSchema.TYPE_DOCUMENT && it.parentId == parentId }

    // ── Blank means absent ───────────────────────────────────────────────────

    @Test
    fun `a parent with no row has no document`() = runBlocking {
        val f = fixture()
        for (p in parents) assertNull(f.repo.get(p))
    }

    @Test
    fun `a row holding only blank text reads as absent`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "   \n ", flags = watermark)
            assertNull("blank text must read as no document", f.repo.get(p))
        }
    }

    @Test
    fun `the SQL blank gate trims what Kotlin isBlank calls whitespace`() {
        // `SoilDao.hasLiveDocument` asks the blank-means-absent question in SQL, and SQLite's
        // one-argument TRIM strips spaces only — so the trim set has to be spelled out, or a
        // foreign-written row holding one newline opens an exporter entry that can only refuse.
        // Anything ASCII that Kotlin calls blank and this does not is listed as accepted, in one
        // place, rather than discovered later on a device.
        val accepted = setOf('\u001C', '\u001D', '\u001E', '\u001F')   // the ASCII separators
        for (code in 0..127) {
            val ch = code.toChar()
            if (!ch.isWhitespace() || ch in accepted) continue
            val named = if (ch == ' ') "' '" else "char($code)"
            assertTrue(
                "TRIM must strip U+%04X, which isBlank() counts".format(code),
                SoilSql.BLANK_CHARS.contains(named),
            )
        }
        // And nothing beyond that set: an over-wide trim would eat real content off the ends.
        assertEquals(6, Regex("""char\(\d+\)|' '""").findAll(SoilSql.BLANK_CHARS).count())
    }

    @Test
    fun `saving blank text over a live row soft-deletes it`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = watermark)
            f.repo.save(p, "", now = 200L)
            assertNull(f.repo.get(p))
            assertEquals(200L, f.soil.rows.getValue("doc-existing-$p").deletedAt)
        }
    }

    @Test
    fun `saving blank text with no row writes nothing at all`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.repo.save(p, "  ", now = 200L)
            f.repo.saveDrafted(p, "", srcUpdatedAt = watermark, now = 200L)
            assertTrue(f.soil.rows.isEmpty())
            assertEquals(0, f.minted)
        }
    }

    @Test
    fun `a blank draft clears the row too — a re-seed of an emptied page leaves nothing behind`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = null)
            f.repo.saveDrafted(p, "\n\n", srcUpdatedAt = watermark, now = 300L)
            assertNull(f.repo.get(p))
            assertEquals(300L, f.soil.rows.getValue("doc-existing-$p").deletedAt)
        }
    }

    // ── Insert ───────────────────────────────────────────────────────────────

    @Test
    fun `a hand-authored first save inserts with no watermark`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.repo.save(p, "# Mine", now = 400L)
            val row = f.row(p)!!
            assertEquals(SoilSchema.TYPE_DOCUMENT, row.type)
            assertEquals(p, row.parentId)
            assertEquals("# Mine", row.text)
            assertNull("authored by hand ⇒ never drafted from the page", row.flags)
            assertEquals(0, row.order)
            assertEquals(400L, row.createdAt)
            assertEquals(400L, row.updatedAt)
            assertNull(row.deletedAt)
        }
    }

    @Test
    fun `a seeded first save inserts with the watermark stamped`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.repo.saveDrafted(p, "# Seeded", srcUpdatedAt = watermark, now = 400L)
            val doc = f.repo.get(p)!!
            assertEquals("# Seeded", doc.text)
            assertEquals(watermark, doc.srcUpdatedAt)
            assertEquals(f.row(p)!!.id, doc.id)
        }
    }

    // ── Drop-unchanged: `updatedAt` is sacred ────────────────────────────────

    @Test
    fun `re-saving identical text writes nothing and leaves updatedAt where it was`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = watermark, at = 100L)
            f.repo.save(p, "# Draft", now = 999L)
            assertEquals(100L, f.soil.rows.getValue("doc-existing-$p").updatedAt)
            assertTrue("no write may reach the DAO", f.soil.events.isEmpty())
        }
    }

    @Test
    fun `an identical draft at an unchanged watermark writes nothing`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = watermark, at = 100L)
            f.repo.saveDrafted(p, "# Draft", srcUpdatedAt = watermark, now = 999L)
            assertEquals(100L, f.soil.rows.getValue("doc-existing-$p").updatedAt)
            assertTrue(f.soil.events.isEmpty())
        }
    }

    @Test
    fun `an identical draft at a moved watermark still writes — the refresh re-anchors`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = watermark, at = 100L)
            f.repo.saveDrafted(p, "# Draft", srcUpdatedAt = watermark + 5_000L, now = 999L)
            val row = f.soil.rows.getValue("doc-existing-$p")
            assertEquals(watermark + 5_000L, row.flags)
            assertEquals(999L, row.updatedAt)
            assertEquals(listOf("setDocumentDrafted:doc-existing-$p"), f.soil.events)
        }
    }

    // ── The watermark moves in exactly one place ─────────────────────────────

    @Test
    fun `a hand edit rewrites the text and keeps the watermark`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Draft", flags = watermark, at = 100L)
            f.repo.save(p, "# Draft, edited", now = 500L)
            val doc = f.repo.get(p)!!
            assertEquals("# Draft, edited", doc.text)
            assertEquals("a keystroke must never re-anchor the draft", watermark, doc.srcUpdatedAt)
            assertEquals(500L, f.soil.rows.getValue("doc-existing-$p").updatedAt)
            assertEquals(listOf("setDocumentText:doc-existing-$p"), f.soil.events)
        }
    }

    @Test
    fun `a hand edit on a hand-authored document leaves it hand-authored`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Mine", flags = null)
            f.repo.save(p, "# Mine, longer", now = 500L)
            assertNull(f.repo.get(p)!!.srcUpdatedAt)
        }
    }

    @Test
    fun `a refresh stamps the watermark onto a hand-authored document`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Mine", flags = null)
            f.repo.saveDrafted(p, "# From the page", srcUpdatedAt = watermark, now = 500L)
            val doc = f.repo.get(p)!!
            assertEquals("# From the page", doc.text)
            assertEquals(watermark, doc.srcUpdatedAt)
        }
    }

    // ── Row-level invariants ─────────────────────────────────────────────────

    @Test
    fun `a soft-deleted row is invisible — the next save inserts a fresh one`() = runBlocking {
        for (p in parents) {
            val f = fixture()
            f.seed(p, "# Gone", flags = watermark)
            f.soil.softDelete(listOf("doc-existing-$p"), 200L)
            assertNull(f.repo.get(p))
            f.repo.save(p, "# New", now = 300L)
            val live = f.soil.rows.values.single {
                it.type == SoilSchema.TYPE_DOCUMENT && it.parentId == p && it.deletedAt == null
            }
            assertEquals("# New", live.text)
            assertTrue("the dead row stays dead, in place", f.soil.rows.getValue("doc-existing-$p").deletedAt != null)
        }
    }

    @Test
    fun `the page document and the notebook document never see each other`() = runBlocking {
        val f = fixture()
        f.repo.saveDrafted(pageId, "# Page", srcUpdatedAt = watermark, now = 100L)
        f.repo.save(rootId, "# Merged", now = 200L)
        assertEquals("# Page", f.repo.get(pageId)!!.text)
        assertEquals("# Merged", f.repo.get(rootId)!!.text)
        assertEquals(watermark, f.repo.get(pageId)!!.srcUpdatedAt)
        assertNull(f.repo.get(rootId)!!.srcUpdatedAt)

        f.repo.save(pageId, "", now = 300L)
        assertNull(f.repo.get(pageId))
        assertNotNull("clearing one must not touch the other", f.repo.get(rootId))
    }

    @Test
    fun `a watermark past Int MAX_VALUE survives the round trip`() = runBlocking {
        val f = fixture()
        val far = 4_100_000_000L   // > Int.MAX_VALUE: the value the flags retype exists for
        f.repo.saveDrafted(pageId, "# Seeded", srcUpdatedAt = far, now = 100L)
        assertEquals(far, f.repo.get(pageId)!!.srcUpdatedAt)
    }

    // ── The page-document batch read ─────────────────────────────────────────

    /** A live page of [rootId]. The batch read reaches its document through this row. */
    private suspend fun Fixture.page(id: String, order: Int) {
        soil.upsert(
            SoilObjectEntity(
                id = id, parentId = rootId, type = SoilSchema.TYPE_PAGE,
                order = order, createdAt = 10L, updatedAt = 10L,
            )
        )
    }

    @Test
    fun `the batch read answers every page's document and never the notebook's`() = runBlocking {
        val f = fixture()
        f.page("p1", 0)
        f.page("p2", 1)
        f.repo.saveDrafted("p1", "# One", srcUpdatedAt = watermark, now = 100L)
        f.repo.save("p2", "# Two", now = 100L)
        // The merged draft hangs off the root, not off a page — the exclusion is structural, and
        // the text export reads it separately.
        f.repo.save(rootId, "# Merged", now = 100L)

        val byPage = f.docs.pageDocumentsIn(rootId).associate { it.parentId to it.text }
        assertEquals(mapOf("p1" to "# One", "p2" to "# Two"), byPage)
    }

    @Test
    fun `the batch read skips soft-deleted documents and undocumented pages`() = runBlocking {
        val f = fixture()
        f.page("p1", 0)
        f.page("p2", 1)   // never written on
        f.page("p3", 2)
        f.repo.save("p1", "# One", now = 100L)
        f.repo.save("p3", "# Three", now = 100L)
        f.repo.save("p3", "", now = 200L)   // cleared: soft-deleted, and absent from here on

        assertEquals(listOf("p1"), f.docs.pageDocumentsIn(rootId).map { it.parentId })
    }

    @Test
    fun `a page of another notebook is not this notebook's document`() = runBlocking {
        val f = fixture()
        f.page("p1", 0)
        f.repo.save("p1", "# One", now = 100L)
        f.soil.upsert(
            SoilObjectEntity(
                id = "other-page", parentId = "nb-other", type = SoilSchema.TYPE_PAGE,
                order = 0, createdAt = 10L, updatedAt = 10L,
            )
        )
        f.repo.save("other-page", "# Elsewhere", now = 100L)

        assertEquals(listOf("p1"), f.docs.pageDocumentsIn(rootId).map { it.parentId })
    }
}
