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

    /**
     * The **1-based number the anchor page carries once a paste beside it has landed** — what the
     * "Pasted before/after page N" toast has to say (B3 review fixed an off-by-one here).
     *
     * The toast is read against the page indicator, which by then shows the post-paste numbering: a
     * paste *before* the anchor pushes it down one, a paste *after* leaves it where it was. Naming
     * the pre-paste number would have "Pasted before page 3" point at the pasted page itself.
     */
    fun anchorNumberAfterPaste(currentIndex: Int, before: Boolean): Int =
        currentIndex + 1 + if (before) 1 else 0

    /** Ids to un-soft-delete so the live set becomes exactly [target] (target order preserved). */
    fun toRestore(currentlyAlive: Set<String>, target: List<String>): List<String> =
        target.filter { it !in currentlyAlive }

    /** Ids to soft-delete so the live set becomes exactly [target]. */
    fun toDelete(currentlyAlive: Collection<String>, target: List<String>): List<String> {
        val keep = target.toSet()
        return currentlyAlive.filter { it !in keep }
    }
}
