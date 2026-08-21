package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObjectRowsTest {

    private val obj = PageObject(
        id = "o1", providerIdentity = "com.example.ext:heading", payload = "## Meeting notes",
        x = 10f, y = 20.5f, width = 300f, height = 48f, order = 3,
    )

    @Test
    fun roundTrip() {
        val row = ObjectRows.toRow(obj, "page-1", now = 1234L)
        assertEquals(SoilSchema.TYPE_OBJECT, row.type)
        assertEquals("page-1", row.parentId)
        assertEquals("com.example.ext:heading", row.style)
        assertEquals("## Meeting notes", row.text)
        assertEquals(3, row.order)
        assertEquals(1234L, row.createdAt)
        assertNull(row.deletedAt)
        assertNull(row.refId); assertNull(row.color); assertNull(row.strokeWidth); assertNull(row.flags); assertNull(row.blob)
        assertEquals(obj, ObjectRows.toObject(row))
    }

    @Test
    fun boundsHelpers() {
        val b = obj.bounds
        assertEquals(10f, b.left, 0f); assertEquals(20.5f, b.top, 0f)
        assertEquals(310f, b.right, 0f); assertEquals(68.5f, b.bottom, 0f)
        val moved = obj.translated(5f, -0.5f)
        assertEquals(15f, moved.x, 0f); assertEquals(20f, moved.y, 0f)
        assertEquals(obj.width, moved.width, 0f)
    }

    @Test
    fun missingBoundsRejected() {
        val row = ObjectRows.toRow(obj, "p", 1L)
        assertNull(ObjectRows.toObject(row.copy(x = null)))
        assertNull(ObjectRows.toObject(row.copy(y = null)))
        assertNull(ObjectRows.toObject(row.copy(width = null)))
        assertNull(ObjectRows.toObject(row.copy(height = null)))
        assertNull(ObjectRows.toObject(row.copy(width = -1f)))
        assertNull(ObjectRows.toObject(row.copy(x = Float.NaN)))
    }

    @Test
    fun wrongTypeOrNoIdentityRejected() {
        val row = ObjectRows.toRow(obj, "p", 1L)
        assertNull(ObjectRows.toObject(row.copy(type = SoilSchema.TYPE_STROKE)))
        assertNull(ObjectRows.toObject(row.copy(style = null)))
        assertNull(ObjectRows.toObject(row.copy(style = "  ")))
    }

    @Test
    fun nullPayloadReadsAsEmpty() {
        val row = ObjectRows.toRow(obj, "p", 1L).copy(text = null)
        assertEquals("", ObjectRows.toObject(row)!!.payload)
    }

    @Test
    fun payloadCappedBothWays() {
        val long = "x".repeat(ExtensionContract.MAX_OBJECT_TEXT_CHARS + 500)
        val row = ObjectRows.toRow(obj.copy(payload = long), "p", 1L)
        assertEquals(ExtensionContract.MAX_OBJECT_TEXT_CHARS, row.text!!.length)
        // An over-long payload already in the file (untrusted input) is capped on the way in too.
        val fromFile: SoilObjectEntity = row.copy(text = long)
        assertEquals(ExtensionContract.MAX_OBJECT_TEXT_CHARS, ObjectRows.toObject(fromFile)!!.payload.length)
        val exact = "y".repeat(ExtensionContract.MAX_OBJECT_TEXT_CHARS)
        assertEquals(exact, ObjectRows.cap(exact))
    }

    @Test
    fun zOrderPreserved() {
        val row = ObjectRows.toRow(obj.copy(order = 7), "p", 1L)
        assertNotNull(ObjectRows.toObject(row))
        assertEquals(7, ObjectRows.toObject(row)!!.order)
    }
}
