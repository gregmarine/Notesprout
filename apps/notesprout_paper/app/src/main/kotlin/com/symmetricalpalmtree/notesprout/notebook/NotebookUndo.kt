package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The notebook screen's undo action set (arc 6 / S0 — lifted out of `UndoRedoStack`, which is now
 * the generic `UndoRedoStack<A>` in `:paper-screen`; the notebook's stack is
 * `UndoRedoStack<NotebookUndo.Action>`). Each entry knows the page it happened on, so undo survives
 * page turns. Replaying an action onto the paper / store lives in [NotebookActivity] (S1 moves the
 * replay here, next to the actions).
 */
object NotebookUndo {

    sealed interface Action {
        val pageId: String
        data class Drew(override val pageId: String, val stroke: Stroke) : Action
        data class Erased(override val pageId: String, val strokes: List<Stroke>) : Action
        /** A selection drag: [ids] = strokes, [objectIds] = content objects (arc 4), same delta for both. */
        data class Moved(
            override val pageId: String,
            val ids: List<String>,
            val dx: Float,
            val dy: Float,
            val objectIds: List<String> = emptyList(),
        ) : Action
        /** A page insert or delete, replayable in both directions via [NotebookSession.reconcile]
         *  (its `childIds` = the strokes **and** objects the delete took with it). */
        data class Page(val snapshot: NotebookSession.Structural) : Action {
            override val pageId: String get() = snapshot.afterCurrentId
        }

        // ── Content objects (arc 4 / H1) — every object action is one undoable step (rule 22) ──

        /** An object was created — from ink ([removedStrokes] = the strokes it consumed, soft-deleted
         *  in the same step) or from nothing (empty). Undo restores the ink and removes the object. */
        data class ObjectCreated(override val pageId: String, val obj: PageObject, val removedStrokes: List<Stroke>) : Action
        /** A selection was deleted: strokes and/or objects, together. */
        data class ObjectsDeleted(override val pageId: String, val strokes: List<Stroke>, val objects: List<PageObject>) : Action
        /** An object's payload and/or bounds changed (edit, action, re-size). [before]/[after] hold the
         *  whole object so either direction is one `updatePayloadAndBounds`. */
        data class ObjectEdited(override val pageId: String, val before: PageObject, val after: PageObject) : Action
    }
}
