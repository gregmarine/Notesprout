package com.notesprout.android.history

import kotlinx.serialization.Serializable

/**
 * Discriminated union of every reversible user action in a notebook.
 *
 * Only IDs are stored — never stroke point data.  The database is the source of truth;
 * undo/redo operations restore or soft-delete rows by ID.
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
}
