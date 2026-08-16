package com.symmetricalpalmtree.notesprout.library

import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.prefs.SortField
import com.symmetricalpalmtree.notesprout.data.prefs.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SortComparatorTest {

    private fun summary(name: String, updatedAt: Long = 0L) = ObjectSummary(
        id = name, type = ObjectType.NOTEBOOK, name = name, parentId = null,
        createdAt = 0L, updatedAt = updatedAt, pageCount = 1, flags = 1, templateKind = "BLANK",
    )

    private fun sort(items: List<ObjectSummary>, field: SortField, order: SortOrder): List<String> {
        val comparator: Comparator<ObjectSummary> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        val sorted = if (order == SortOrder.DESC) items.sortedWith(comparator.reversed()) else items.sortedWith(comparator)
        return sorted.map { it.name }
    }

    @Test
    fun nameAsc() {
        val items = listOf(summary("Charlie"), summary("alpha"), summary("Bravo"))
        assertEquals(listOf("alpha", "Bravo", "Charlie"), sort(items, SortField.NAME, SortOrder.ASC))
    }

    @Test
    fun nameDesc() {
        val items = listOf(summary("Charlie"), summary("alpha"), summary("Bravo"))
        assertEquals(listOf("Charlie", "Bravo", "alpha"), sort(items, SortField.NAME, SortOrder.DESC))
    }

    @Test
    fun modifiedAsc() {
        val items = listOf(summary("c", 300), summary("a", 100), summary("b", 200))
        assertEquals(listOf("a", "b", "c"), sort(items, SortField.MODIFIED, SortOrder.ASC))
    }

    @Test
    fun modifiedDesc() {
        val items = listOf(summary("c", 300), summary("a", 100), summary("b", 200))
        assertEquals(listOf("c", "b", "a"), sort(items, SortField.MODIFIED, SortOrder.DESC))
    }
}
