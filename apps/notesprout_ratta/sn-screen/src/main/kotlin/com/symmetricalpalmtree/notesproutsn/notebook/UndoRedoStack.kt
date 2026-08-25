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
 * Pure ordering only. Applying an action back onto the paper and the store lives in the host
 * screen, where the paper, the session and the store are all in reach — and where the SN rule holds
 * that a replay mutates the store first and then reloads the page, because the `.soil` is the
 * source of truth.
 */
class UndoRedoStack<A : Any> {

    private val undo = ArrayDeque<A>()
    private val redo = ArrayDeque<A>()

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
        while (undo.size > MAX) undo.removeFirst()
        redo.clear()
        generation++
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Take the newest edit off the undo side; the caller reverts it, then [pushRedo]s it. */
    fun popUndo(): A? = undo.removeLastOrNull()

    fun pushRedo(action: A) {
        redo.addLast(action)
    }

    /** Take the newest undone edit off the redo side; the caller re-applies it, then [pushUndo]s it. */
    fun popRedo(): A? = redo.removeLastOrNull()

    fun pushUndo(action: A) {
        undo.addLast(action)
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    private companion object {
        const val MAX = 100
    }
}
