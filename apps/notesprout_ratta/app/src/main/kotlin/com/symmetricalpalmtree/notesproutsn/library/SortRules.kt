package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder

/**
 * One sort model for the whole library. Two rules, in this order:
 *
 *  1. **Folders always come before notebooks** — the containers are the map, and reversing the sort
 *     must not scatter them through the cards. The chosen order applies *within* each group.
 *  2. Name is compared case-insensitively (`Bravo` sits between `alpha` and `Charlie`, not before
 *     both); Modified compares `updatedAt`, the index's real-edit timestamp.
 *
 * Pure and JVM-tested — the screens only pass the prefs in.
 */
object SortRules {

    fun comparator(field: SortField, order: SortOrder): Comparator<ObjectSummary> {
        val base: Comparator<ObjectSummary> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        return if (order == SortOrder.DESC) base.reversed() else base
    }

    /** Sort one homogeneous list (all folders, or all notebooks). */
    fun sort(items: List<ObjectSummary>, field: SortField, order: SortOrder): List<ObjectSummary> =
        items.sortedWith(comparator(field, order))

    /** Folders first, then notebooks; the chosen order applied inside each group. */
    fun foldersFirst(items: List<ObjectSummary>, field: SortField, order: SortOrder): List<ObjectSummary> {
        val (folders, rest) = items.partition { it.type == ObjectType.FOLDER }
        return sort(folders, field, order) + sort(rest, field, order)
    }
}
