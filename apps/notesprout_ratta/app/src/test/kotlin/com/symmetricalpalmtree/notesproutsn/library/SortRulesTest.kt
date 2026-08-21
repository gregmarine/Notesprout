package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SortRulesTest {

    private fun nb(name: String, updatedAt: Long = 0L) = row(name, ObjectType.NOTEBOOK, updatedAt)
    private fun folder(name: String, updatedAt: Long = 0L) = row(name, ObjectType.FOLDER, updatedAt)

    private fun row(name: String, type: String, updatedAt: Long) = ObjectSummary(
        id = "$type:$name", type = type, name = name, parentId = null,
        createdAt = 0L, updatedAt = updatedAt, pageCount = 1, flags = 1, templateKind = "BLANK",
    )

    private fun names(list: List<ObjectSummary>) = list.map { it.name }

    @Test
    fun nameAscending() {
        val items = listOf(nb("Charlie"), nb("alpha"), nb("Bravo"))
        assertEquals(
            listOf("alpha", "Bravo", "Charlie"),
            names(SortRules.sort(items, SortField.NAME, SortOrder.ASC)),
        )
    }

    @Test
    fun nameDescending() {
        val items = listOf(nb("Charlie"), nb("alpha"), nb("Bravo"))
        assertEquals(
            listOf("Charlie", "Bravo", "alpha"),
            names(SortRules.sort(items, SortField.NAME, SortOrder.DESC)),
        )
    }

    @Test
    fun nameIsCaseInsensitive() {
        // A case-sensitive sort would put every capital before every lowercase.
        val items = listOf(nb("banana"), nb("Apple"), nb("cherry"), nb("Blueberry"))
        assertEquals(
            listOf("Apple", "banana", "Blueberry", "cherry"),
            names(SortRules.sort(items, SortField.NAME, SortOrder.ASC)),
        )
    }

    @Test
    fun modifiedAscending() {
        val items = listOf(nb("c", 300), nb("a", 100), nb("b", 200))
        assertEquals(listOf("a", "b", "c"), names(SortRules.sort(items, SortField.MODIFIED, SortOrder.ASC)))
    }

    @Test
    fun modifiedDescending() {
        val items = listOf(nb("c", 300), nb("a", 100), nb("b", 200))
        assertEquals(listOf("c", "b", "a"), names(SortRules.sort(items, SortField.MODIFIED, SortOrder.DESC)))
    }

    @Test
    fun foldersComeFirstInEveryOrder() {
        val items = listOf(nb("apple"), folder("zebra"), nb("zoo"), folder("ant"))
        assertEquals(
            listOf("ant", "zebra", "apple", "zoo"),
            names(SortRules.foldersFirst(items, SortField.NAME, SortOrder.ASC)),
        )
        assertEquals(
            listOf("zebra", "ant", "zoo", "apple"),
            names(SortRules.foldersFirst(items, SortField.NAME, SortOrder.DESC)),
        )
    }

    @Test
    fun foldersStayFirstWhenSortingByModified() {
        val items = listOf(nb("newNotebook", 900), folder("oldFolder", 100))
        assertEquals(
            listOf("oldFolder", "newNotebook"),
            names(SortRules.foldersFirst(items, SortField.MODIFIED, SortOrder.DESC)),
        )
    }

    @Test
    fun sortIsStableForEqualKeys() {
        val items = listOf(nb("a", 5), nb("b", 5), nb("c", 5))
        assertEquals(listOf("a", "b", "c"), names(SortRules.sort(items, SortField.MODIFIED, SortOrder.ASC)))
    }

    @Test
    fun emptyListIsHandled() {
        assertEquals(emptyList<String>(), names(SortRules.foldersFirst(emptyList(), SortField.NAME, SortOrder.ASC)))
    }
}
