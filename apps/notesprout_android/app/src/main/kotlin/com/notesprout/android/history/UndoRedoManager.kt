package com.notesprout.android.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Notebook-level undo/redo history manager.
 *
 * Two [ArrayDeque] stacks: [undoStack] (actions to reverse) and [redoStack] (actions to re-apply).
 * Both are capped at [MAX_DEPTH] = 50 entries; oldest entries drop silently when exceeded.
 *
 * Actions are ID-only references — no stroke point data is stored here.
 * The database is the source of truth; [UndoRedoAction] carries only the IDs needed to
 * soft-delete or restore rows.
 *
 * Thread safety: all public methods are called from the main thread inside DrawingActivity.
 * No synchronisation needed.
 */
class UndoRedoManager {

    private val undoStack = ArrayDeque<UndoRedoAction>()
    private val redoStack = ArrayDeque<UndoRedoAction>()

    // ── Serialization envelope ────────────────────────────────────────────────

    @Serializable
    private data class ManagerData(
        val undoStack: List<UndoRedoAction>,
        val redoStack: List<UndoRedoAction>,
    )

    companion object {
        private const val MAX_DEPTH = 50

        private val json = Json { ignoreUnknownKeys = true }

        /** Deserialise a manager previously produced by [toJson]. */
        fun fromJson(jsonString: String): UndoRedoManager {
            val data = json.decodeFromString<ManagerData>(jsonString)
            val mgr = UndoRedoManager()
            mgr.undoStack.addAll(data.undoStack)
            mgr.redoStack.addAll(data.redoStack)
            return mgr
        }
    }

    // ── Stack operations ──────────────────────────────────────────────────────

    /**
     * Record a new action.  Clears the redo stack (branching history).
     * If the undo stack exceeds [MAX_DEPTH], the oldest entry is dropped.
     */
    fun push(action: UndoRedoAction) {
        redoStack.clear()
        undoStack.addLast(action)
        if (undoStack.size > MAX_DEPTH) undoStack.removeFirst()
    }

    /**
     * Pop the most-recent undoable action, push it onto the redo stack, and return it.
     * Returns null if there is nothing to undo.
     */
    fun undo(): UndoRedoAction? {
        val action = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(action)
        if (redoStack.size > MAX_DEPTH) redoStack.removeFirst()
        return action
    }

    /**
     * Pop the most-recent redoable action, push it back onto the undo stack, and return it.
     * Returns null if there is nothing to redo.
     */
    fun redo(): UndoRedoAction? {
        val action = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(action)
        if (undoStack.size > MAX_DEPTH) undoStack.removeFirst()
        return action
    }

    /**
     * Replace the top entry of the redo stack with [updated].
     * Used by paste undo to store the soft-delete timestamp back into the redo entry
     * so that redo can restore exactly the rows deleted during undo.
     * No-op if the redo stack is empty.
     */
    fun amendLastRedo(updated: UndoRedoAction) {
        if (redoStack.isNotEmpty()) redoStack[redoStack.lastIndex] = updated
    }

    /** Empty both stacks. Call on notebook close. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    // ── State queries ─────────────────────────────────────────────────────────

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun isEmpty(): Boolean = undoStack.isEmpty() && redoStack.isEmpty()

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Serialise both stacks to JSON for focus-loss persistence.
     * Restore with [fromJson].
     */
    fun toJson(): String = json.encodeToString(
        ManagerData(
            undoStack = undoStack.toList(),
            redoStack = redoStack.toList(),
        )
    )
}
