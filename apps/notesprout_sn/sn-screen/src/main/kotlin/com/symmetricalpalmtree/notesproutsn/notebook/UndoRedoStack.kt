package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * A paper-hosting screen's in-memory undo/redo history, over whatever action type [A] that screen
 * records (arc 11 / J1: genericised and moved to `:sn-screen`, so the notebook and the Scratch Pad
 * share the ordering rules without sharing an action set — the notebook's 14 kinds live in
 * `NotebookUndo.Action` in `:app`). g-paper keeps no history by design
 * (`host-responsibilities.md`) — the host records what happened and replays it — so this is the
 * record.
 *
 * **Screen-level, not page-level:** the notebook's actions each carry the page they happened on, so
 * history survives a page turn (an insert or a delete *is* a page turn, and undoing one has to
 * reverse that turn too). The stack is cleared only when the screen dies — never on a flip.
 * Recording a fresh edit clears the redo side. Bounded at [MAX] entries, oldest dropped: an erase
 * or a delete holds the full geometry of every stroke it must be able to put back.
 *
 * **And bounded again by bytes, for a screen whose entries are not ids** (arc 43 / K2, ported from
 * Paintsprout's raster sketchbook). Every screen up to now records *references* — stroke ids, page
 * ids, rows that are still in the file and merely stamped — so a hundred entries cost nothing worth
 * counting, and the default [cost] of zero and budget of [Long.MAX_VALUE] leave those screens
 * exactly as they were. A **raster** page has no rows to un-stamp: the only record of what was
 * there before a mark or a rub is the pixels that were there, and a page-wide erase's before-image
 * is the size of the page. So the undo side also keeps a running [undoBytes] and evicts while it is
 * over [budgetBytes] — and what it evicts is always the **oldest entry that actually costs
 * something**, never a free one, because dropping an id-only edit would shorten one screen's
 * history to pay for another's, which is a bill the wrong hand receives.
 *
 * Pure ordering only. Applying an action back onto the paper and the store lives in the host
 * screen, where the paper, the session and the store are all in reach — and where the SN rule holds
 * that a replay mutates the store first and then reloads the page, because the `.soil` is the
 * source of truth.
 *
 * @param cost how many bytes an entry holds — zero for every id-carrying screen, the tiles' size
 *   for a raster one. Read once per entry, at the moment it enters or leaves a side.
 * @param budgetBytes how many bytes the undo side may hold. A constructor parameter for one reason
 *   only: the eviction rule is arithmetic and deserves to be proved on a laptop, and proving it at
 *   the real number would mean allocating the real number per assertion.
 */
class UndoRedoStack<A : Any>(
    private val cost: (A) -> Long = { 0L },
    private val budgetBytes: Long = Long.MAX_VALUE,
) {

    private val undo = ArrayDeque<A>()
    private val redo = ArrayDeque<A>()

    /**
     * What the undo side is holding, kept as it goes rather than counted when it is asked for.
     * Adding an entry is one addition and evicting one is one subtraction; adding up a hundred
     * entries on every mark would put a walk of the whole history in the path of the pen.
     *
     * The redo side is deliberately not counted. A fresh [record] clears it outright, so the only
     * way bytes sit there at all is between an undo and the next mark — a moment, and one where the
     * alternative is throwing away the thing the hand is about to ask for again.
     */
    var undoBytes: Long = 0L
        private set

    /**
     * Bumped by every [record]. A replay in flight snapshots it before reverting and compares
     * after: a change means a fresh edit landed mid-replay (and cleared redo) — the replayer must
     * not push the undone entry onto redo, or record-clears-redo silently breaks.
     */
    var generation: Int = 0
        private set

    /** Record an edit that just happened. Clears the redo history. */
    fun record(action: A) {
        undo.addLast(action)
        undoBytes += cost(action)
        while (undo.size > MAX) undoBytes -= cost(undo.removeFirst())
        evictForBudget()
        redo.clear()
        generation++
    }

    /**
     * Make room for what was just recorded by letting go of the oldest costed entry.
     *
     * The search walks from the old end, which is at most [MAX] entries and only on the rare edit
     * that actually overflows the budget — the total itself is never recounted, which is the part
     * that would otherwise sit in the path of every stroke.
     *
     * **The newest entry is never the one dropped.** It is the thing the hand is about to reach
     * for, and an undo that does nothing for the mark just made reads as broken. A single entry
     * cannot exceed the budget on its own anyway (a contact's before-image is bounded by the page),
     * so this only ever decides the case where the old entries have all gone already.
     */
    private fun evictForBudget() {
        while (undoBytes > budgetBytes) {
            val oldest = undo.indexOfFirst { cost(it) > 0 }
            if (oldest < 0 || oldest == undo.lastIndex) return
            undoBytes -= cost(undo.removeAt(oldest))
        }
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Take the newest edit off the undo side; the caller reverts it, then [pushRedo]s it. */
    fun popUndo(): A? = undo.removeLastOrNull()?.also { undoBytes -= cost(it) }

    fun pushRedo(action: A) {
        redo.addLast(action)
    }

    /** Take the newest undone edit off the redo side; the caller re-applies it, then [pushUndo]s it. */
    fun popRedo(): A? = redo.removeLastOrNull()

    /**
     * An edit coming back onto the undo side — from redo, or from a replay that could not land it.
     * Its bytes count again the moment it does: it is holding exactly what it was holding before,
     * so a total that did not move would drift further from the truth with every undo the hand
     * changed its mind about.
     */
    fun pushUndo(action: A) {
        undo.addLast(action)
        undoBytes += cost(action)
    }

    /**
     * Put [action] back **beneath** everything recorded since [sinceGeneration] — a replay's honest
     * answer when it popped an entry, waited, and found a fresh mark had landed meanwhile (arc 43 /
     * K2).
     *
     * A raster replay is not instantaneous: it may have to turn a page, then wait for the pen to go
     * idle before it can swap pixels under it, and across those waits a hand can land and finish a
     * mark. That mark went through [record], which cleared redo — so [pushRedo] is wrong, the
     * entry was never applied. But [pushUndo] is wrong too: putting it on *top* would make the next
     * undo reverse an edit that happened **before** the one still sitting under it, and history
     * would be out of order. It belongs where it chronologically belongs, which is
     * `undo.size - (generation - sinceGeneration)` — beneath exactly the entries recorded since the
     * replayer took its snapshot, clamped in case the bound dropped some of them meanwhile.
     *
     * Its bytes count again, as with [pushUndo]. The generation does **not** move (nothing new
     * happened) and redo is **not** cleared (the entry never landed, so nothing forward became
     * unreachable).
     */
    fun pushUndoBeneath(action: A, sinceGeneration: Int) {
        val since = (generation - sinceGeneration).coerceAtLeast(0)
        val at = (undo.size - since).coerceIn(0, undo.size)
        undo.add(at, action)
        undoBytes += cost(action)
    }

    fun clear() {
        undo.clear()
        redo.clear()
        undoBytes = 0L
    }

    private companion object {
        const val MAX = 100
    }
}
