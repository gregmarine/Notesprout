package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The heading row mapping — the arc-3 additive family row type's column contract. */
class HeadingRowsTest {

    private val heading = Heading(
        id = "h1", text = "## Title", level = 2,
        x = 10f, y = 20f, width = 300f, height = 60f, order = 4,
    )

    @Test
    fun `toRow writes the locked column contract`() {
        val row = HeadingRows.toRow(heading, "page1", now = 123L)
        assertEquals("h1", row.id)
        assertEquals("page1", row.parentId)
        assertEquals(SoilSchema.TYPE_HEADING, row.type)
        assertEquals(4, row.order)
        assertEquals(123L, row.createdAt)
        assertEquals(123L, row.updatedAt)
        assertEquals("## Title", row.text)
        assertEquals(2, row.flags)
        assertEquals(10f, row.x)
        assertEquals(20f, row.y)
        assertEquals(300f, row.width)
        assertEquals(60f, row.height)
        // Everything else stays null — headings never carry ink columns.
        assertNull(row.color)
        assertNull(row.strokeWidth)
        assertNull(row.style)
        assertNull(row.blob)
        assertNull(row.refId)
        assertNull(row.deletedAt)
    }

    @Test
    fun `round trip is lossless`() {
        val row = HeadingRows.toRow(heading, "page1", now = 5L)
        assertEquals(heading, HeadingRows.toHeading(row))
    }

    @Test
    fun `bounds derive from the box`() {
        val b = heading.bounds
        assertEquals(10f, b.left, 0f)
        assertEquals(20f, b.top, 0f)
        assertEquals(310f, b.right, 0f)
        assertEquals(80f, b.bottom, 0f)
    }

    @Test
    fun `translated shifts the box only`() {
        val t = heading.translated(5f, -5f)
        assertEquals(15f, t.x, 0f)
        assertEquals(15f, t.y, 0f)
        assertEquals(heading.width, t.width, 0f)
        assertEquals(heading.text, t.text)
    }

    @Test
    fun `a row with no text is dropped, not crashed on`() {
        val row = HeadingRows.toRow(heading, "page1", 1L).copy(text = null)
        assertNull(HeadingRows.toHeading(row))
    }

    @Test
    fun `a foreign level clamps instead of rejecting the row`() {
        val row = HeadingRows.toRow(heading, "page1", 1L).copy(flags = 99)
        assertEquals(6, HeadingRows.toHeading(row)!!.level)
        val nullFlags = HeadingRows.toRow(heading, "page1", 1L).copy(flags = null)
        assertEquals(1, HeadingRows.toHeading(nullFlags)!!.level)
    }
}
