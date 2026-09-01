package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary

/**
 * What the search shelf holds, and in what order — **pure Kotlin, no Android, JVM-tested**
 * (arc 20 / Q1). The rows come from the index and the ranking comes from [FuzzyRank]; the one
 * rule that lives here is the one the library has everywhere else:
 *
 * **Folders first, then notebooks** — and *then* relevance inside each group. The score never
 * outranks the kind. Every other view in this app puts containers before contents
 * ([SortRules.foldersFirst]), and a shelf that scattered them through the cards because a folder
 * name happened to match a little worse would read as a different screen.
 *
 * It sits beside [RecentsAssembly] for the same reason that one exists: the ordering rule of a
 * shelf is a thing to reason about on its own, not a fragment of the Activity's listing code.
 */
object SearchAssembly {

    /**
     * The ranked shelf for [query]: matching [folders] first, then matching [notebooks].
     *
     * Both lists are whole-library listings — search has no folder, by decision (arc 20: "a search
     * that only looked in the folder you happen to be standing in would answer 'no' for a notebook
     * two folders over"). A query that is not [FuzzyRank.isRunnable] returns nothing at all.
     */
    fun rank(
        folders: List<ObjectSummary>,
        notebooks: List<ObjectSummary>,
        query: String,
    ): List<ObjectSummary> =
        FuzzyRank.rank(folders, query) { it.name } + FuzzyRank.rank(notebooks, query) { it.name }
}
