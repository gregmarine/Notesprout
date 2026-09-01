package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row → record (arc 22 / X3): **a bad row is a dropped record, never a lost index.** */
class TagRowsTest {

    private val t1 = "11111111-1111-4111-8111-111111111111"
    private val n1 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun tagRow(vararg cells: Cell) = Row(listOf("id", "display"), cells.toList())
    private fun assignmentRow(vararg cells: Cell) =
        Row(listOf("tagId", "notebookId", "pageId"), cells.toList())

    @Test
    fun aGoodTagRowDecodes() {
        val record = TagRows.tag(tagRow(Cell.Text(t1), Cell.Text("Reading List")))!!
        assertEquals(t1, record.id)
        assertEquals("Reading List", record.display)
        assertEquals("reading list", record.identityKey)
    }

    @Test
    fun aBadTagRowIsNull() {
        // An id that is not a UUID.
        assertNull(TagRows.tag(tagRow(Cell.Text("t0"), Cell.Text("draft"))))
        // A display that is not a tag, and one that is not the stored (normalized) form.
        assertNull(TagRows.tag(tagRow(Cell.Text(t1), Cell.Text("   "))))
        assertNull(TagRows.tag(tagRow(Cell.Text(t1), Cell.Text(" draft"))))
        // The wrong storage class — `Row`'s accessor throws and this catches it.
        assertNull(TagRows.tag(tagRow(Cell.Integer(1), Cell.Text("draft"))))
        assertNull(TagRows.tag(tagRow(Cell.Text(t1), Cell.Null)))
    }

    @Test
    fun aGoodAssignmentRowDecodesBothKinds() {
        val notebook = TagRows.assignment(
            assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Text("")),
        )!!
        assertTrue(notebook.isNotebookTag)
        assertNull(notebook.pageIdOrNull)

        val page = TagRows.assignment(
            assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Text(p1)),
        )!!
        assertEquals(p1, page.pageIdOrNull)
    }

    @Test
    fun aBadAssignmentRowIsNull() {
        assertNull(TagRows.assignment(assignmentRow(Cell.Text("t0"), Cell.Text(n1), Cell.Text(""))))
        assertNull(TagRows.assignment(assignmentRow(Cell.Text(t1), Cell.Text("n1"), Cell.Text(""))))
        assertNull(TagRows.assignment(assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Text("p1"))))
        assertNull(TagRows.assignment(assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Null)))
    }

    /** The batch decoders keep the good ones and COUNT the rest — the count is all that may ever be
     *  logged, because a tag is the user's own words. */
    @Test
    fun theBatchDecodersDropAndCount() {
        val tags = TagRows.tags(
            listOf(
                tagRow(Cell.Text(t1), Cell.Text("draft")),
                tagRow(Cell.Text("broken"), Cell.Text("draft")),
                tagRow(Cell.Text(n1), Cell.Text("done")),
            ),
        )
        assertEquals(listOf("draft", "done"), tags.records.map { it.display })
        assertEquals(1, tags.dropped)

        val assignments = TagRows.assignments(
            listOf(
                assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Text("")),
                assignmentRow(Cell.Text(t1), Cell.Text(n1), Cell.Text("nope")),
            ),
        )
        assertEquals(1, assignments.records.size)
        assertEquals(1, assignments.dropped)
    }
}
