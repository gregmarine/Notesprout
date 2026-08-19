package com.symmetricalpalmtree.notesprout.notebook

/**
 * Screen-level, in-memory undo/redo history over any action type [A] (arc 6 / S0: made generic and
 * moved to `:paper-screen`; the notebook's own `Action` set lives in `NotebookUndo.kt` in `:app`,
 * the Scratch Pad's in its `ScratchUndo`). Each entry knows the page it happened on, so undo
 * survives page turns (an insert/delete turns the page, and undoing it must reverse that turn). The
 * whole stack is cleared only when the screen closes — never on a page turn. Redo is cleared the
 * moment a new edit is recorded. Bounded at [MAX] entries (oldest dropped) to cap memory, since an
 * erase action holds the full stroke geometry it needs to re-add.
 *
 * The stack is a plain LIFO — applying an action back onto the paper/store lives in the host screen
 * where the paper, session, and store are all in reach. This class only orders history; it is pure.
 */
class UndoRedoStack<A : Any> {

    private val undo = ArrayDeque<A>()
    private val redo = ArrayDeque<A>()

    /** Record a freshly-performed edit. Clears the redo history. */
    fun record(action: A) {
        undo.addLast(action)
        while (undo.size > MAX) undo.removeFirst()
        redo.clear()
    }

    fun canUndo(): Boolean = undo.isNotEmpty()
    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Pop the last edit for undoing; the caller reverts it, then this moves it to the redo side. */
    fun popUndo(): A? = undo.removeLastOrNull()
    fun pushRedo(action: A) { redo.addLast(action) }

    /** Pop the last undone edit for redoing; the caller re-applies it, then this moves it back to undo. */
    fun popRedo(): A? = redo.removeLastOrNull()
    fun pushUndo(action: A) { undo.addLast(action) }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    private companion object {
        const val MAX = 100
    }
}
