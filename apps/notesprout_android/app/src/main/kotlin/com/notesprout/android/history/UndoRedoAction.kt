package com.notesprout.android.history

import com.notesprout.android.data.HeadingStroke
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
        val headingIds: List<String> = emptyList(),
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
     * User erased a batch of strokes/headings via the lasso eraser tool — undo restores all;
     * redo soft-deletes them again as a single atomic batch.
     *
     * [strokeIds] contains ALL erased IDs (both strokes and heading IDs) for unified DB ops.
     * [headingIds] is the heading subset of [strokeIds].
     * [headings] carries full heading data so undo can rebuild the in-memory list without a
     * DB round-trip for heading deserialization.
     */
    @Serializable
    data class LassoErased(
        val strokeIds: List<String>,
        val pageId: String,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User moved selected strokes/headings via lasso drag — undo restores original positions;
     * redo re-applies the moved positions.  Both lists carry complete objects
     * (id + full data) so the operation never needs a DB read to reconstruct state.
     * All IDs in the original and moved lists are identical; only coordinates differ.
     *
     * [originalHeadings]/[movedHeadings] carry full [HeadingStroke] objects including
     * translated [boundingBox] and embedded stroke points.
     */
    @Serializable
    data class StrokesMoved(
        val pageId: String,
        val originalStrokes: List<LiveStroke>,
        val movedStrokes: List<LiveStroke>,
        val originalHeadings: List<HeadingStroke> = emptyList(),
        val movedHeadings: List<HeadingStroke> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User pasted strokes/headings from the lasso clipboard onto the current page.
     * Undo soft-deletes all [strokeIds] + [headingIds]; redo restores them by ID.
     *
     * [insertedAt] is the timestamp used when the objects were inserted into the DB.
     * [headingIds] are the heading IDs pasted alongside strokes (may be empty for
     * pure-stroke pastes).
     */
    @Serializable
    data class LassoPasted(
        val strokeIds: List<String>,
        val pageId: String,
        val insertedAt: Long,
        val headingIds: List<String> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User cut selected strokes/headings via the lasso cut tool — objects are soft-deleted
     * and simultaneously written to [NotesproutClipboard].
     *
     * Undo: restores all [strokeIds] + [headingIds] from the DB (does not touch clipboard).
     * Redo: re-soft-deletes [strokeIds] + [headingIds] and repopulates [NotesproutClipboard]
     *       with [strokes] + [headings] + their union bounding box.
     *
     * [deletedAt] is the timestamp used for all soft-delete calls during the original cut.
     * [strokes]/[headings] carry full data so redo can rebuild the clipboard without a DB read.
     */
    @Serializable
    data class LassoCut(
        val strokeIds: List<String>,
        val pageId: String,
        val deletedAt: Long,
        val strokes: List<LiveStroke>,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User deleted selected strokes/headings via the lasso delete action — objects are
     * soft-deleted but the clipboard is NOT touched.
     *
     * Undo: restores all [strokeIds] + [headingIds] from the DB.
     * Redo: re-soft-deletes [strokeIds] + [headingIds].
     *
     * [strokes]/[headings] carry full data so undo can rebuild the canvas without a DB read.
     */
    @Serializable
    data class LassoDeleted(
        val strokeIds: List<String>,
        val pageId: String,
        val deletedAt: Long,
        val strokes: List<LiveStroke>,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User converted a lasso selection of strokes into a heading object.
     *
     * Undo: soft-delete the heading row, restore the original stroke rows.
     * Redo: re-soft-delete the original strokes, re-insert the heading row.
     *
     * [deletedAt] is the timestamp used to soft-delete the original strokes — used by
     * undo to restore them via [restoreChildrenDeletedSince] on the layer.
     * [embeddedStrokes] carries full point data (with fresh UUIDs matching the heading's
     * internal copy) so redo can rebuild the heading row without a DB read.
     * [recognizedText] carries the heading's recognized text so redo can restore the
     * heading with its text rendering intact (null for legacy headings).
     */
    @Serializable
    data class HeadingCreated(
        val headingId: String,
        val pageId: String,
        val layerId: String,
        val deletedAt: Long,
        val originalStrokeIds: List<String>,
        val embeddedStrokes: List<LiveStroke>,
        val recognizedText: String? = null,
    ) : UndoRedoAction()

    /**
     * User edited the recognized text of a heading via the text edit dialog.
     *
     * Undo: restore [previousText] (may be null for the defensive edge case where a
     * heading had no recognized text before editing).
     * Redo: re-apply [newText].
     *
     * Both undo and redo update the DB row and rebuild the heading in-memory so the
     * canvas reflects the change immediately.
     */
    @Serializable
    data class HeadingTextEdited(
        val headingId: String,
        val pageId: String,
        val previousText: String? = null,
        val newText: String,
    ) : UndoRedoAction()

    /**
     * User removed a heading, dispersing its embedded strokes back onto the layer as
     * individual live stroke rows with fresh UUIDs.
     *
     * Undo: soft-delete all [restoredStrokes] by ID, restore the heading row by ID.
     * Redo: soft-delete the heading row, restore [restoredStrokes] by ID.
     *
     * [embeddedStrokes] carries the heading's original embedded stroke data so the heading's
     * bounding box can be reconstructed in-memory on undo (same 8dp padding as creation).
     * [recognizedText] carries the heading's recognized text so undo can restore the heading
     * with its text rendering intact (null for legacy headings with no recognized text).
     */
    @Serializable
    data class HeadingRemoved(
        val headingId: String,
        val pageId: String,
        val restoredStrokes: List<LiveStroke>,
        val embeddedStrokes: List<LiveStroke>,
        val recognizedText: String? = null,
    ) : UndoRedoAction()
}
