package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture → envelope → plan, the part of copy/paste that can corrupt a notebook silently: which id
 * becomes which, what re-parents onto what, and whether `"order"` survives.
 */
class PageClipTest {

    private val notebookId = "nb-src"
    private val pageId = "page-1"
    private val templateId = "tpl-1"
    private val now = 5_000L

    private fun row(
        id: String, parentId: String, type: String, order: Int = 0,
        refId: String? = null, text: String? = null, blob: ByteArray? = null,
        width: Float? = null, height: Float? = null, flags: Int? = null,
    ) = SoilObjectEntity(
        id = id, parentId = parentId, type = type, order = order,
        createdAt = 1L, updatedAt = 2L, deletedAt = null,
        text = text, refId = refId, width = width, height = height, flags = flags, blob = blob,
    )

    private val templateRow = row(templateId, notebookId, SoilSchema.TYPE_TEMPLATE, text = "LINED",
        width = 1404f, height = 1872f, blob = byteArrayOf(1, 2, 3, 4))
    private val pageRow = row(pageId, notebookId, SoilSchema.TYPE_PAGE, order = 2,
        refId = templateId, width = 1404f, height = 1872f)

    /** A page with loose ink, a heading, and a link wrapping a stroke of its own (two levels). */
    private fun content() = listOf(
        row("s-loose", pageId, SoilSchema.TYPE_STROKE, order = 0, blob = byteArrayOf(9, 8, 7)),
        row("h-1", pageId, SoilSchema.TYPE_HEADING, order = 1, text = "## Title", flags = 2),
        row("lnk-1", pageId, SoilSchema.TYPE_LINK, order = 0, text = "L1|u|p||page-9"),
        row("s-wrapped", "lnk-1", SoilSchema.TYPE_STROKE, order = 5, blob = byteArrayOf(4, 5)),
    )

    private fun envelope() = PageClip.capture(pageRow, templateRow, content(), notebookId, now)

    /** Deterministic ids so a plan is assertable. */
    private fun ids(): () -> String {
        var n = 0
        return { "new-${n++}" }
    }

    // ── capture ──────────────────────────────────────────────────────────────

    @Test
    fun `capture carries the template, the page and every descendant`() {
        val env = envelope()
        assertEquals(ClipEnvelope.KIND_PAGE, env.kind)
        assertEquals(ClipEnvelope.VERSION, env.version)
        assertEquals(notebookId, env.sourceNotebookId)
        assertEquals(now, env.copiedAt)
        assertEquals(
            listOf(templateId, pageId, "s-loose", "h-1", "lnk-1", "s-wrapped"),
            env.rows.map { it.id },
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), env.rows.first { it.type == "template" }.blobBytes())
    }

    @Test
    fun `capture survives a round trip through the codec`() {
        val back = ClipEnvelope.decode(ClipEnvelope.encode(envelope()))
        assertEquals(envelope(), back)
    }

    @Test
    fun `a page with no content captures the page and its template`() {
        val env = PageClip.capture(pageRow, templateRow, emptyList(), notebookId, now)
        assertEquals(listOf(templateId, pageId), env.rows.map { it.id })
    }

    @Test
    fun `a blank page captures with no template row`() {
        val blank = row("page-b", notebookId, SoilSchema.TYPE_PAGE, refId = "", width = 100f, height = 200f)
        val env = PageClip.capture(blank, null, emptyList(), notebookId, now)
        assertEquals(listOf("page-b"), env.rows.map { it.id })
    }

    // ── plan: ids and lineage ────────────────────────────────────────────────

    @Test
    fun `every row gets a fresh id and nothing keeps a source id`() {
        val env = envelope()
        val plan = PageClip.plan(env, "nb-dest", 4, PageClip.Template.Reuse(templateId), now, ids())!!
        val sourceIds = setOf(pageId, "s-loose", "h-1", "lnk-1", "s-wrapped")
        for (r in plan.rows) assertTrue("$r kept a source id", r.id !in sourceIds)
        assertNotEquals(pageId, plan.pageId)
        assertEquals(4, plan.contentIds.size)
    }

    @Test
    fun `content re-parents onto the copied page and the copied link`() {
        val env = envelope()
        val plan = PageClip.plan(env, "nb-dest", 0, PageClip.Template.Reuse(templateId), now, ids())!!
        val byType = plan.rows.groupBy { it.type }
        val newPage = byType.getValue(SoilSchema.TYPE_PAGE).single()
        val newLink = byType.getValue(SoilSchema.TYPE_LINK).single()

        assertEquals("nb-dest", newPage.parentId)
        assertEquals(newPage.id, newLink.parentId)

        val strokes = byType.getValue(SoilSchema.TYPE_STROKE)
        // The loose stroke hangs off the page; the wrapped one off the copied link.
        assertEquals(setOf(newPage.id, newLink.id), strokes.map { it.parentId }.toSet())
        assertEquals(newPage.id, byType.getValue(SoilSchema.TYPE_HEADING).single().parentId)
    }

    @Test
    fun `order is preserved on content and rewritten only on the page`() {
        val env = envelope()
        val plan = PageClip.plan(env, "nb-dest", 7, PageClip.Template.Reuse(templateId), now, ids())!!
        assertEquals(7, plan.rows.first { it.type == SoilSchema.TYPE_PAGE }.order)
        // Loose stroke 0, heading 1, link 0, wrapped stroke 5 — all verbatim, in capture order.
        assertEquals(
            listOf(0, 1, 0, 5),
            plan.rows
                .filter { it.type != SoilSchema.TYPE_PAGE && it.type != SoilSchema.TYPE_TEMPLATE }
                .map { it.order },
        )
    }

    @Test
    fun `payload columns and blobs come across verbatim`() {
        val env = envelope()
        val plan = PageClip.plan(env, "nb-dest", 0, PageClip.Template.Reuse(templateId), now, ids())!!
        val heading = plan.rows.first { it.type == SoilSchema.TYPE_HEADING }
        assertEquals("## Title", heading.text)
        assertEquals(2, heading.flags)
        val wrapped = plan.rows.first { it.type == SoilSchema.TYPE_STROKE && it.order == 5 }
        assertArrayEquals(byteArrayOf(4, 5), wrapped.blob)
        val page = plan.rows.first { it.type == SoilSchema.TYPE_PAGE }
        assertEquals(1404f, page.width!!, 0f)
        assertEquals(1872f, page.height!!, 0f)
    }

    @Test
    fun `every pasted row is stamped now and alive`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.Reuse(templateId), 99L, ids())!!
        for (r in plan.rows) {
            assertEquals(99L, r.createdAt)
            assertEquals(99L, r.updatedAt)
            assertNull(r.deletedAt)
        }
    }

    @Test
    fun `contentIds are the page's new descendants, never the template or the page`() {
        val env = envelope()
        val plan = PageClip.plan(env, "nb-dest", 0, PageClip.Template.Insert(templateId), now, ids())!!
        assertTrue(plan.pageId !in plan.contentIds)
        assertTrue(templateId !in plan.contentIds)
        assertEquals(
            plan.rows.filter {
                it.type != SoilSchema.TYPE_PAGE && it.type != SoilSchema.TYPE_TEMPLATE
            }.map { it.id },
            plan.contentIds,
        )
    }

    // ── plan: templates ──────────────────────────────────────────────────────

    @Test
    fun `Reuse points at the existing template and inserts nothing`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.Reuse("tpl-existing"), now, ids())!!
        assertTrue(plan.rows.none { it.type == SoilSchema.TYPE_TEMPLATE })
        assertEquals("tpl-existing", plan.rows.first { it.type == SoilSchema.TYPE_PAGE }.refId)
    }

    @Test
    fun `Insert brings the carried template row in under the destination notebook`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.Insert(templateId), now, ids())!!
        val tpl = plan.rows.first { it.type == SoilSchema.TYPE_TEMPLATE }
        assertEquals(templateId, tpl.id)
        assertEquals("nb-dest", tpl.parentId)
        assertEquals("LINED", tpl.text)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), tpl.blob)
        assertEquals(templateId, plan.rows.first { it.type == SoilSchema.TYPE_PAGE }.refId)
        // Inserted first, so the page it is referenced by lands after it.
        assertEquals(SoilSchema.TYPE_TEMPLATE, plan.rows.first().type)
    }

    @Test
    fun `None leaves the page blank`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.None, now, ids())!!
        assertTrue(plan.rows.none { it.type == SoilSchema.TYPE_TEMPLATE })
        assertEquals("", plan.rows.first { it.type == SoilSchema.TYPE_PAGE }.refId)
    }

    @Test
    fun `Insert with no carried template row degrades to blank rather than a dangling refId`() {
        val env = PageClip.capture(pageRow, null, emptyList(), notebookId, now)
        val plan = PageClip.plan(env, "nb-dest", 0, PageClip.Template.Insert(templateId), now, ids())!!
        assertTrue(plan.rows.none { it.type == SoilSchema.TYPE_TEMPLATE })
        assertEquals("", plan.rows.first { it.type == SoilSchema.TYPE_PAGE }.refId)
    }

    // ── plan: untrusted payloads ─────────────────────────────────────────────

    @Test
    fun `a payload with no page row plans nothing`() {
        val env = ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_PAGE,
            sourceNotebookId = notebookId, copiedAt = now,
            rows = PageClip.capture(pageRow, null, content(), notebookId, now).rows
                .filter { it.type != SoilSchema.TYPE_PAGE },
        )
        assertNull(PageClip.plan(env, "nb-dest", 0, PageClip.Template.None, now, ids()))
    }

    @Test
    fun `an orphaned child is dropped, never re-parented onto the page`() {
        val orphan = row("s-orphan", "lnk-gone", SoilSchema.TYPE_STROKE, blob = byteArrayOf(1))
        val env = PageClip.capture(pageRow, null, content() + orphan, notebookId, now)
        val plan = PageClip.plan(env, "nb-dest", 0, PageClip.Template.None, now, ids())!!
        // Four descendants travelled, the orphan did not.
        assertEquals(4, plan.contentIds.size)
        assertEquals(3, plan.rows.count { it.type == SoilSchema.TYPE_STROKE || it.type == SoilSchema.TYPE_HEADING })
    }
}
