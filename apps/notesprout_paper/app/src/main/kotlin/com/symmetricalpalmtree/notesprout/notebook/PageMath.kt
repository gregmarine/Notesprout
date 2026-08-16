package com.symmetricalpalmtree.notesprout.notebook

/**
 * The page-list arithmetic behind insert / delete / reconcile — pure so it is JVM-tested. The DB
 * side (soft-delete, renumber, mirrors) lives in [NotebookSession]; this is only the index/set math.
 */
object PageMath {

    /**
     * Landing index after deleting the page at [deletedIndex] from a list of [oldSize] pages.
     * Previous page (or the first page if the deleted one was first). Assumes ≥ 2 pages before delete.
     */
    fun indexAfterDelete(deletedIndex: Int, oldSize: Int): Int =
        (deletedIndex - 1).coerceIn(0, oldSize - 2)

    /** Insertion position for an insert relative to [currentIndex]: after → +1, before → same slot. */
    fun insertPosition(currentIndex: Int, after: Boolean): Int =
        if (after) currentIndex + 1 else currentIndex

    /** Ids that must be un-soft-deleted to make the live set exactly [target] (order preserved). */
    fun toRestore(currentlyAlive: Set<String>, target: List<String>): List<String> =
        target.filter { it !in currentlyAlive }

    /** Ids that must be soft-deleted to make the live set exactly [target]. */
    fun toDelete(currentlyAlive: Collection<String>, target: List<String>): List<String> {
        val keep = target.toSet()
        return currentlyAlive.filter { it !in keep }
    }
}
