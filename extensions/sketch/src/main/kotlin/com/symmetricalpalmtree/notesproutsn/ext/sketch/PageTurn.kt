package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract

/**
 * The page-turn arithmetic of the sketch screen (arc 43 / K5), kept apart from the Activity so the
 * one rule that is easy to get subtly wrong can be proved on a laptop.
 *
 * **The screen turns its own pages** (decision 7): it asks the host to move
 * ([com.symmetricalpalmtree.notesproutsn.extension.ISketchHost.requestPage]) and the host answers
 * with a state. **At either edge the host answers the SAME page**, unchanged — a turn is never an
 * exception and never a null the screen has to word — so the screen compares `pageKey` and stays
 * put ([isEdge]). No dialog, no toast, and the arrows never disable: a greyed control is invisible
 * on e-ink, so a bound is a tap that honestly does nothing.
 *
 * [targetIndex] is not used to *decide* the turn — only the host knows the live page list, and
 * asking it is one Binder call either way. It is used by the **undo replay**, which has to turn
 * back to the page an entry was made on and needs to know which way, and how many steps it may take
 * before it admits it cannot get there.
 */
object PageTurn {

    /** Whether [direction] is one of the two the contract knows. */
    fun isDirection(direction: Int): Boolean =
        direction == SketchContract.PAGE_PREV || direction == SketchContract.PAGE_NEXT

    /**
     * Where a turn from [index] of [count] in [direction] lands, or **null** at the edge — which is
     * exactly the case the host answers by handing the same page back.
     */
    fun targetIndex(index: Int, count: Int, direction: Int): Int? {
        if (count <= 0 || index !in 0 until count || !isDirection(direction)) return null
        val next = if (direction == SketchContract.PAGE_PREV) index - 1 else index + 1
        return next.takeIf { it in 0 until count }
    }

    /** Whether a turn from here would move at all. */
    fun canTurn(index: Int, count: Int, direction: Int): Boolean =
        targetIndex(index, count, direction) != null

    /**
     * The host answered a turn with the same page it was already on: the edge. The whole rule is
     * "compare the key", written here so both the pager and the replay say it the same way.
     */
    fun isEdge(fromKey: String, toKey: String): Boolean = fromKey == toKey

    /**
     * Which way an undo replay has to walk to reach the page [editIndex] names from [currentIndex].
     * Null when it is already there.
     */
    fun directionTowards(currentIndex: Int, editIndex: Int): Int? = when {
        editIndex < currentIndex -> SketchContract.PAGE_PREV
        editIndex > currentIndex -> SketchContract.PAGE_NEXT
        else -> null
    }

    /**
     * How many turns the replay may take before it gives up and drops the entry.
     *
     * The index recorded with an edit is where the page sat **then**; pages cannot be inserted or
     * deleted from this screen, but the notebook behind it is a live file and an index can be stale
     * in principle. So the walk is bounded rather than trusted: the recorded distance plus one step
     * of slack, and an edge (the same key twice) stops it early in any case.
     */
    fun maxSteps(currentIndex: Int, editIndex: Int): Int =
        kotlin.math.abs(editIndex - currentIndex) + 1
}
