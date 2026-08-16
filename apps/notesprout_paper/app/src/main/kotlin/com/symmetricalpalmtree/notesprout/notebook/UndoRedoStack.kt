package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * Notebook-level, in-memory undo/redo history. Each entry knows the page it happened on, so undo
 * survives page turns (an insert/delete turns the page, and undoing it must reverse that turn). The
 * whole stack is cleared only when the notebook closes — never on a page turn. Redo is cleared the
 * moment a new edit is recorded. Bounded at [MAX] entries (oldest dropped) to cap memory, since an
 * [Action.Erased] holds the full stroke geometry it needs to re-add.
 *
 * The stack is a plain LIFO — applying an action back onto the paper/store lives in [NotebookActivity]
 * where the paper, session, and store are all in reach. This class only orders history; it is pure.
 */
class UndoRedoStack {

    sealed interface Action {
        val pageId: String
        data class Drew(override val pageId: String, val stroke: Stroke) : Action
        data class Erased(override val pageId: String, val strokes: List<Stroke>) : Action
        data class Moved(override val pageId: String, val ids: List<String>, val dx: Float, val dy: Float) : Action
        /** A page insert or delete, replayable in both directions via [NotebookSession.reconcile]. */
        data class Page(val snapshot: NotebookSession.Structural) : Action {
            override val pageId: String get() = snapshot.afterCurrentId
        }
    }

    private val undo = ArrayDeque<Action>()
    private val redo = ArrayDeque<Action>()

    /** Record a freshly-performed edit. Clears the redo history. */
    fun record(action: Action) {
        undo.addLast(action)
        while (undo.size > MAX) undo.removeFirst()
        redo.clear()
    }

    fun canUndo(): Boolean = undo.isNotEmpty()
    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Pop the last edit for undoing; the caller reverts it, then this moves it to the redo side. */
    fun popUndo(): Action? = undo.removeLastOrNull()
    fun pushRedo(action: Action) { redo.addLast(action) }

    /** Pop the last undone edit for redoing; the caller re-applies it, then this moves it back to undo. */
    fun popRedo(): Action? = redo.removeLastOrNull()
    fun pushUndo(action: Action) { undo.addLast(action) }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    private companion object {
        const val MAX = 100
    }
}
