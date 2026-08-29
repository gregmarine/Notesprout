package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.LinkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remap's pure half (arc 16 / I1): a link payload follows the notebook's new identity, or it is
 * left exactly as it came. The stakes are the E3 round-trip finding — a link "to this notebook by
 * id" that does not follow the rename dies as a dead target.
 */
class NotebookRemapTest {

    private val oldId = "aaaaaaaa-0000-4000-8000-00000000old0"
    private val newId = "bbbbbbbb-0000-4000-8000-00000000new0"
    private val otherId = "cccccccc-0000-4000-8000-0000000other"
    private val pageId = "dddddddd-0000-4000-8000-00000000page"

    @Test
    fun anOwnPageLinkIsUntouched() {
        // KIND_PAGE carries no notebookId at all — it is already "this notebook", whatever it is
        // called, and rewriting it would be a change with no meaning.
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, pageId,
        )
        assertNull(NotebookRemap.remapLinkPayload(payload, oldId, newId))
    }

    @Test
    fun aNotebookLinkAtTheOldIdIsRePointed() {
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, oldId, null,
        )
        val remapped = NotebookRemap.remapLinkPayload(payload, oldId, newId)
        val decoded = LinkPayload.decode(remapped!!)!!
        assertEquals(newId, decoded.notebookId)
        assertNull(decoded.pageId)
        assertEquals(LinkPayload.CHROME_NONE, decoded.chrome)
        assertEquals(LinkPayload.KIND_NOTEBOOK, decoded.kind)
    }

    @Test
    fun aNotebookPageLinkAtTheOldIdKeepsItsPage() {
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_NOTEBOOK_PAGE, oldId, pageId,
        )
        val remapped = NotebookRemap.remapLinkPayload(payload, oldId, newId)
        val decoded = LinkPayload.decode(remapped!!)!!
        assertEquals(newId, decoded.notebookId)
        // Child ids are minted UUIDs with no meaning outside the file: only the notebook moved.
        assertEquals(pageId, decoded.pageId)
        assertEquals(LinkPayload.CHROME_UNDERLINE, decoded.chrome)
        assertEquals(LinkPayload.KIND_NOTEBOOK_PAGE, decoded.kind)
    }

    @Test
    fun aLinkToAnotherNotebookIsUntouched() {
        val payload = LinkPayload.encode(
            LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, otherId, null,
        )
        assertNull(NotebookRemap.remapLinkPayload(payload, oldId, newId))
    }

    @Test
    fun anUnusablePayloadIsLeftExactlyAsItCame() {
        // Rewriting a grammar we cannot read would corrupt it — a foreign or future payload is
        // simply not our business.
        assertNull(NotebookRemap.remapLinkPayload("L2|1|1|$oldId|", oldId, newId))
        assertNull(NotebookRemap.remapLinkPayload("", oldId, newId))
        assertNull(NotebookRemap.remapLinkPayload("nonsense", oldId, newId))
        assertNull(NotebookRemap.remapLinkPayload("L1|9|1|$oldId|", oldId, newId))
        assertNull(NotebookRemap.remapLinkPayload("L1|1|7|$oldId|", oldId, newId))
    }
}
