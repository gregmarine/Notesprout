package com.notesprout.android.history

import com.notesprout.android.data.LiveStroke
import kotlinx.serialization.Serializable

/**
 * Discriminated union of every reversible user action in a notebook.
 *
 * Most actions store only IDs — the database is the source of truth and undo/redo
 * operations restore or soft-delete rows by ID.  [StrokesMoved] is the exception:
 * it carries full point arrays so undo/redo can reposition strokes without a DB round-trip.
 *
 * [layerId] is the parentId of the stroke row (= the content layer UUID) captured at
 * action-record time from DrawingActivity.currentLayerId.
 */
@Serializable
sealed class UndoRedoAction {

    /** User drew a stroke — undo soft-deletes it; redo restores it. */
    @Serializable
    data class StrokeAdded(
        val pageId: String,
        val layerId: String,
        val strokeId: String,
    ) : UndoRedoAction()

    /** User erased a stroke — undo restores it; redo soft-deletes it again. */
    @Serializable
    data class StrokeErased(
        val pageId: String,
        val layerId: String,
        val strokeId: String,
    ) : UndoRedoAction()

    /** User added a page — undo soft-deletes it + children; redo restores it. */
    @Serializable
    data class PageAdded(
        val pageId: String,
        val pageIndex: Int,
        val insertedBefore: Boolean = false,
    ) : UndoRedoAction()

    /**
     * User deleted a page — undo restores page + all children deleted at the same instant;
     * redo soft-deletes page + all surviving children.
     *
     * [deletedAt] is the timestamp passed to all soft-delete calls during the original
     * delete operation — used to identify which child rows to restore on undo.
     */
    @Serializable
    data class PageDeleted(
        val pageId: String,
        val pageIndex: Int,
        val deletedAt: Long,
    ) : UndoRedoAction()

    /** User changed a page template — undo applies [previousTemplateId]; redo applies [newTemplateId]. */
    @Serializable
    data class TemplateChanged(
        val pageId: String,
        val previousTemplateId: String?,
        val newTemplateId: String?,
    ) : UndoRedoAction()

    /**
     * User cleared all strokes from a page — undo restores every stroke soft-deleted
     * at [deletedAt]; redo soft-deletes all surviving strokes on [layerId] again.
     *
     * [deletedAt] is the single timestamp used for all soft-delete calls during the clear,
     * allowing [restoreChildrenDeletedSince] to identify exactly which rows to bring back.
     */
    @Serializable
    data class PageCleared(
        val pageId: String,
        val layerId: String,
        val deletedAt: Long,
    ) : UndoRedoAction()

    /**
     * User pasted a copied page — undo soft-deletes the page and all its content;
     * redo restores it with strokes intact.
     *
     * [undoDeletedAt] is 0 until undo executes, then set to the timestamp used for
     * the soft-delete so redo can restore exactly those rows via [restoreChildrenDeletedSince].
     */
    @Serializable
    data class PagePasted(
        val pageId: String,
        val pageIndex: Int,
        val undoDeletedAt: Long = 0,
    ) : UndoRedoAction()

    /**
     * User moved a page to a new position — undo moves it back; redo moves it forward again.
     *
     * [previousAfterPageId] is the ID of the page that was immediately before the moved page
     * before the move (null = the moved page was first). Used by undo to restore the original
     * position. [targetPageId] is the page it was moved after; used by redo.
     *
     * Only [order] values are changed — no rows are created or deleted.
     */
    @Serializable
    data class PageMoved(
        val pageId: String,
        val previousAfterPageId: String?,
        val targetPageId: String,
    ) : UndoRedoAction()

    /**
     * User erased a batch of strokes via the lasso eraser tool — undo restores all of them;
     * redo soft-deletes them again as a single atomic batch.
     */
    @Serializable
    data class LassoErased(
        val strokeIds: List<String>,
        val pageId: String,
    ) : UndoRedoAction()

    /**
     * User moved selected strokes via lasso drag — undo restores original positions;
     * redo re-applies the moved positions.  Both lists carry complete [LiveStroke] objects
     * (id + full point arrays) so the operation never needs a DB read to reconstruct state.
     * All IDs in [originalStrokes] and [movedStrokes] are identical; only coordinates differ.
     */
    @Serializable
    data class StrokesMoved(
        val pageId: String,
        val originalStrokes: List<LiveStroke>,
        val movedStrokes: List<LiveStroke>,
    ) : UndoRedoAction()
}
