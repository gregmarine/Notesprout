package com.notesprout.notesprout.undo

class UndoStack {

    private val undoStack = ArrayDeque<UndoAction>()
    private val redoStack = ArrayDeque<UndoAction>()

    companion object {
        private const val MAX_SIZE = 20
    }

    fun push(action: UndoAction) {
        undoStack.addLast(action)
        if (undoStack.size > MAX_SIZE) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): UndoAction? {
        val action = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(action)
        if (redoStack.size > MAX_SIZE) redoStack.removeFirst()
        return action
    }

    fun redo(): UndoAction? {
        val action = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(action)
        if (undoStack.size > MAX_SIZE) undoStack.removeFirst()
        return action
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
