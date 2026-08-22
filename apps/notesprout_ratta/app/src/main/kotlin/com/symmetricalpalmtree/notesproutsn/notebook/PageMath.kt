package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * The page-list arithmetic behind insert / delete / reconcile. Pure — no Android, no DB — so it is
 * JVM-tested (`PageMathTest`) and the risky part of a structural edit is provable off-device. The
 * row work (soft delete, restore, renumber, the index mirrors) lives in [NotebookSession]; this is
 * only the index and set math it asks for.
 */
object PageMath {

    /**
     * Landing index after the page at [deletedIndex] leaves a list of [oldSize] pages: the previous
     * page, or the new first page when the deleted one was first. Assumes ≥ 2 pages before the
     * delete — deleting the *only* page is a replacement, not a delete, and never asks this.
     */
    fun indexAfterDelete(deletedIndex: Int, oldSize: Int): Int =
        (deletedIndex - 1).coerceIn(0, oldSize - 2)

    /** Slot a new page takes relative to [currentIndex]: after → the next slot, before → this one. */
    fun insertPosition(currentIndex: Int, after: Boolean): Int =
        if (after) currentIndex + 1 else currentIndex

    /** Ids to un-soft-delete so the live set becomes exactly [target] (target order preserved). */
    fun toRestore(currentlyAlive: Set<String>, target: List<String>): List<String> =
        target.filter { it !in currentlyAlive }

    /** Ids to soft-delete so the live set becomes exactly [target]. */
    fun toDelete(currentlyAlive: Collection<String>, target: List<String>): List<String> {
        val keep = target.toSet()
        return currentlyAlive.filter { it !in keep }
    }
}
