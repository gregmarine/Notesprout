package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAssemblyTest {

    private fun row(id: String, name: String, type: String) = ObjectSummary(
        id = id, type = type, name = name, parentId = null,
        createdAt = 0L, updatedAt = 0L, pageCount = null, flags = null, templateKind = null,
    )

    private fun folder(id: String, name: String) = row(id, name, ObjectType.FOLDER)
    private fun notebook(id: String, name: String) = row(id, name, ObjectType.NOTEBOOK)

    private fun ids(
        folders: List<ObjectSummary>,
        notebooks: List<ObjectSummary>,
        query: String,
    ) = SearchAssembly.rank(folders, notebooks, query).map { it.id }

    /** The library's standing rule outranks the score: containers before contents, everywhere. */
    @Test
    fun `every matching folder comes before every matching notebook`() {
        val folders = listOf(folder("f1", "Work notes"))
        val notebooks = listOf(notebook("n1", "Work"))
        // "Work" is the exact answer and it is a notebook — it still sorts second.
        assertEquals(listOf("f1", "n1"), ids(folders, notebooks, "work"))
    }

    @Test
    fun `relevance orders each group`() {
        val folders = listOf(folder("f1", "Meeting Notes"), folder("f2", "Meet"))
        val notebooks = listOf(notebook("n1", "Amount Meeting"), notebook("n2", "Meeting"))
        assertEquals(listOf("f2", "f1", "n2", "n1"), ids(folders, notebooks, "meet"))
    }

    @Test
    fun `non-matching rows are dropped from both groups`() {
        val folders = listOf(folder("f1", "Groceries"), folder("f2", "Meetings"))
        val notebooks = listOf(notebook("n1", "Recipes"), notebook("n2", "Meet"))
        assertEquals(listOf("f2", "n2"), ids(folders, notebooks, "meet"))
    }

    @Test
    fun `a blank query finds nothing at all`() {
        val folders = listOf(folder("f1", "Work"))
        val notebooks = listOf(notebook("n1", "Work"))
        assertTrue(ids(folders, notebooks, "   ").isEmpty())
    }

    @Test
    fun `an empty library is not an error`() {
        assertTrue(ids(emptyList(), emptyList(), "work").isEmpty())
    }
}
