package com.symmetricalpalmtree.notesproutsn.data.clip

import com.symmetricalpalmtree.notesproutsn.data.index.FakeObjectDao
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The clipboard's single index row: one slot, replaced by every copy, never soft-deleted. */
class ClipStoreTest {

    private val dao = FakeObjectDao()
    private val store = ClipStore(dao)

    private fun envelope(id: String, at: Long, source: String = "nb-1") = ClipEnvelope(
        version = ClipEnvelope.VERSION,
        kind = ClipEnvelope.KIND_PAGE,
        sourceNotebookId = source,
        copiedAt = at,
        rows = listOf(ClipRow(id = id, parentId = source, type = "page")),
    )

    @Test
    fun `an empty clipboard reads as nothing`() = runBlocking {
        assertNull(store.readHeader())
        assertNull(store.readEnvelope())
    }

    @Test
    fun `write then read gives the header and the payload back`() = runBlocking {
        val header = store.write(envelope("page-1", 111L))
        assertNotNull(header)
        assertEquals(ClipEnvelope.KIND_PAGE, header!!.kind)
        assertEquals("nb-1", header.sourceNotebookId)
        assertEquals(111L, header.copiedAt)
        assertEquals(ClipEnvelope.VERSION, header.version)

        assertEquals(header, store.readHeader())
        assertEquals("page-1", store.readEnvelope()!!.rows.single().id)
    }

    @Test
    fun `the row lands at the sentinel id, typed and alive`() = runBlocking {
        store.write(envelope("page-1", 111L))
        val row = dao.rows.getValue(ListIds.CLIPBOARD_ID)
        assertEquals(ObjectType.CLIPBOARD, row.type)
        assertEquals(ClipEnvelope.KIND_PAGE, row.name)
        assertNull(row.parentId)
        assertNull(row.deletedAt)
        assertEquals(ClipEnvelope.VERSION, row.flags)
    }

    @Test
    fun `a second copy replaces the slot rather than adding one`() = runBlocking {
        store.write(envelope("page-1", 111L))
        store.write(envelope("page-2", 222L, source = "nb-2"))
        assertEquals(1, dao.rows.size)
        assertEquals("page-2", store.readEnvelope()!!.rows.single().id)
        assertEquals("nb-2", store.readHeader()!!.sourceNotebookId)
        assertEquals(222L, store.readHeader()!!.copiedAt)
    }

    /**
     * One slot, kind wins (arc 8): a copy of either kind replaces the other, so no surface can ever
     * be offering a Paste for a payload that is no longer there.
     */
    @Test
    fun `a copy of objects takes the page's slot, and a page copy takes it back`() = runBlocking {
        store.write(envelope("page-1", 111L))
        val objects = envelope("s-1", 222L).copy(
            kind = ClipEnvelope.KIND_OBJECTS,
            rows = listOf(ClipRow(id = "s-1", parentId = "page-1", type = "stroke")),
        )
        assertEquals(ClipEnvelope.KIND_OBJECTS, store.write(objects)!!.kind)
        assertEquals(1, dao.rows.size)
        assertEquals(ClipEnvelope.KIND_OBJECTS, store.readHeader()!!.kind)
        assertEquals(ClipEnvelope.KIND_OBJECTS, dao.rows.getValue(ListIds.CLIPBOARD_ID).name)

        store.write(envelope("page-3", 333L))
        assertEquals(1, dao.rows.size)
        assertEquals(ClipEnvelope.KIND_PAGE, store.readHeader()!!.kind)
        assertEquals("page-3", store.readEnvelope()!!.rows.single().id)
    }

    @Test
    fun `an over-cap payload writes nothing and leaves the previous clipboard standing`() = runBlocking {
        store.write(envelope("page-1", 111L))
        val huge = envelope("page-2", 222L).copy(
            rows = listOf(ClipRow(
                id = "s", parentId = "page-2", type = "stroke",
                blob = ClipRow.encodeBlob(ByteArray(ClipEnvelope.MAX_BYTES)),
            )),
        )
        assertNull(store.write(huge))
        assertEquals("page-1", store.readEnvelope()!!.rows.single().id)
        assertEquals(111L, store.readHeader()!!.copiedAt)
    }

    @Test
    fun `a corrupt payload reads as an empty clipboard, not a crash`() = runBlocking {
        store.write(envelope("page-1", 111L))
        val row = dao.rows.getValue(ListIds.CLIPBOARD_ID)
        dao.rows[ListIds.CLIPBOARD_ID] = row.copy(blob = byteArrayOf(0x7f, 0x00, 0x11))
        assertNull(store.readEnvelope())
        // The header still says "page" — the paste path is what discovers the payload is unusable,
        // and it clears the in-memory mirror when it does.
        assertNotNull(store.readHeader())
    }

    /** B3 review: clearing only the in-memory mirror let the still-valid header come back at the
     *  next notebook open and advertise a Paste that could only fail again — forever. */
    @Test
    fun `clear retires an unusable row so it stops advertising a paste`() = runBlocking {
        store.write(envelope("page-1", 111L))
        store.clear(999L)
        assertNull(store.readHeader())
        assertNull(store.readEnvelope())
        // The pixels go with it — a dead payload should not keep costing megabytes in the index.
        assertNull(dao.rows.getValue(ListIds.CLIPBOARD_ID).blob)
        assertEquals(999L, dao.rows.getValue(ListIds.CLIPBOARD_ID).deletedAt)
    }

    @Test
    fun `a copy after a clear revives the one slot`() = runBlocking {
        store.write(envelope("page-1", 111L))
        store.clear(999L)
        store.write(envelope("page-2", 222L))
        assertEquals(1, dao.rows.size)
        assertNull(dao.rows.getValue(ListIds.CLIPBOARD_ID).deletedAt)
        assertEquals("page-2", store.readEnvelope()!!.rows.single().id)
    }

    /** A row a laxer build wrote can be too big for the cursor window to hand back — the read
     *  throws rather than returning bytes, and the clipboard must read as unusable, not crash. */
    @Test
    fun `a read that throws reads as an empty clipboard`() = runBlocking {
        store.write(envelope("page-1", 111L))
        dao.clipBlobThrows = true
        assertNull(store.readEnvelope())
    }
}
