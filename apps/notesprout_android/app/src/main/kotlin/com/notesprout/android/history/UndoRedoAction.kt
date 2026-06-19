package com.notesprout.android.history

import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.LinkTarget
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender
import kotlinx.serialization.Serializable

/**
 * Discriminated union of every reversible user action in a notebook.
 *
 * Most actions store only IDs — the database is the source of truth and undo/redo
 * operations restore or soft-delete rows by ID.  [StrokesMoved] is the exception:
 * it carries full point arrays so undo/redo can reposition strokes without a DB round-trip.
 *
 * [layerId] is the parentId of the stroke row (= the content layer UUID) captured at
 * action-record time from NotebookActivity.currentLayerId.
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

    /**
     * User deleted several pages at once from the page index — undo restores them all,
     * redo soft-deletes them all, as a single atomic undo step.
     *
     * Each [DeletedPageRef] carries that page's own [DeletedPageRef.deletedAt] (the timestamp
     * used by its original soft-delete) so [restoreChildrenDeletedSince] can recover exactly its
     * child rows on undo. [DeletedPageRef.pageIndex] is the pre-delete 0-based index, used to
     * land the open page near the restored block.
     */
    @Serializable
    data class PagesDeleted(
        val pages: List<DeletedPageRef>,
    ) : UndoRedoAction()

    /** One page deleted as part of a [PagesDeleted] batch. */
    @Serializable
    data class DeletedPageRef(
        val pageId: String,
        val pageIndex: Int,
        val deletedAt: Long,
    )

    /** User changed a page template — undo applies [previousTemplateId]; redo applies [newTemplateId]. */
    @Serializable
    data class TemplateChanged(
        val pageId: String,
        val previousTemplateId: String?,
        val newTemplateId: String?,
    ) : UndoRedoAction()

    /**
     * User erased all strokes from a page — undo restores every stroke soft-deleted
     * at [deletedAt]; redo soft-deletes all surviving strokes on [layerId] again.
     *
     * [deletedAt] is the single timestamp used for all soft-delete calls during the erase,
     * allowing [restoreChildrenDeletedSince] to identify exactly which rows to bring back.
     */
    @Serializable
    data class PageEraseAll(
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
     * User erased a batch of strokes/headings/text objects via the lasso eraser tool — undo
     * restores all; redo soft-deletes them again as a single atomic batch.
     *
     * [strokeIds] contains ALL erased IDs (strokes, heading IDs, and text IDs) for unified DB ops.
     * [headingIds] is the heading subset of [strokeIds].
     * [headings] carries full heading data so undo can rebuild the in-memory list without a
     * DB round-trip for heading deserialization.
     * [textIds] is the text-object subset of [strokeIds].
     * [textObjects] carries full TextRender data so undo can rebuild the in-memory list without
     * a DB round-trip.
     */
    @Serializable
    data class LassoErased(
        val strokeIds: List<String>,
        val pageId: String,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
        val textIds: List<String> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineIds: List<String> = emptyList(),
        val lines: List<LineRender> = emptyList(),
        val linkIds: List<String> = emptyList(),
        val links: List<LinkRender> = emptyList(),
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
        val originalTextObjects: List<TextRender> = emptyList(),
        val movedTextObjects: List<TextRender> = emptyList(),
        val originalLineObjects: List<LineRender> = emptyList(),
        val movedLineObjects: List<LineRender> = emptyList(),
        val originalLinks: List<LinkRender> = emptyList(),
        val movedLinks: List<LinkRender> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User pasted strokes/headings/text objects from the lasso clipboard onto the current page.
     * Undo soft-deletes all [strokeIds] + [headingIds] + [textIds]; redo restores them by ID.
     *
     * [insertedAt] is the timestamp used when the objects were inserted into the DB.
     * [headingIds] are the heading IDs pasted alongside strokes (may be empty).
     * [textIds] are the text-object IDs pasted (may be empty).
     * [textObjects] carries full TextRender data so redo can rebuild the in-memory list without
     * a DB round-trip.
     */
    @Serializable
    data class LassoPasted(
        val strokeIds: List<String>,
        val pageId: String,
        val insertedAt: Long,
        val headingIds: List<String> = emptyList(),
        val textIds: List<String> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineIds: List<String> = emptyList(),
        val lines: List<LineRender> = emptyList(),
        val linkIds: List<String> = emptyList(),
        val links: List<LinkRender> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User cut selected strokes/headings/text objects via the lasso cut tool — objects are
     * soft-deleted and simultaneously written to [NotesproutClipboard].
     *
     * Undo: restores all [strokeIds] + [headingIds] + [textIds] from the DB (does not touch
     *       clipboard).
     * Redo: re-soft-deletes all three sets and repopulates [NotesproutClipboard] with
     *       [strokes] + [headings] + [textObjects] + their union bounding box.
     *
     * [deletedAt] is the timestamp used for all soft-delete calls during the original cut.
     * [strokes]/[headings]/[textObjects] carry full data so redo can rebuild the clipboard
     * without a DB read.
     */
    @Serializable
    data class LassoCut(
        val strokeIds: List<String>,
        val pageId: String,
        val deletedAt: Long,
        val strokes: List<LiveStroke>,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
        val textIds: List<String> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineIds: List<String> = emptyList(),
        val lines: List<LineRender> = emptyList(),
        val linkIds: List<String> = emptyList(),
        val links: List<LinkRender> = emptyList(),
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
        val textIds: List<String> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineIds: List<String> = emptyList(),
        val lines: List<LineRender> = emptyList(),
        val linkIds: List<String> = emptyList(),
        val links: List<LinkRender> = emptyList(),
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

    /**
     * User inserted a text object via text placement mode.
     *
     * Undo: soft-deletes the text row, clears selection, redraws canvas.
     * Redo: restores the text row, re-selects the object, redraws canvas.
     *
     * [textRender] carries the full render data (id, boundingBox, text) so redo
     * can rebuild the in-memory list and restore selection without a DB read.
     */
    @Serializable
    data class TextInserted(
        val textId: String,
        val pageId: String,
        val layerId: String,
        val textRender: TextRender,
    ) : UndoRedoAction()

    /**
     * User edited a text object via the TextEditDialog (non-empty confirm path).
     *
     * Undo: restore [oldTextRender] (text + boundingBox) to the DB row; update in-memory list;
     *       refresh selection highlight to [oldTextRender.boundingBox].
     * Redo: restore [newTextRender] (text + boundingBox) to the DB row; update in-memory list;
     *       refresh selection highlight to [newTextRender.boundingBox].
     */
    @Serializable
    data class TextEdited(
        val textId: String,
        val pageId: String,
        val oldTextRender: TextRender,
        val newTextRender: TextRender,
    ) : UndoRedoAction()

    /**
     * User confirmed an empty edit on a text object, triggering soft-delete.
     *
     * Undo: un-soft-delete the row; add [textRender] back to the canvas; re-select the object
     *       and show the floating toolbar.
     * Redo: re-soft-delete the row; remove from canvas; clear selection.
     *
     * NOTE: For type="text" objects produced by lasso stroke→text conversion (which carry
     * non-null [TextRender.strokes]), tap-to-edit is GATED: the dialog only opens when text
     * is non-blank. So if a recognized text object (non-blank text) is edited down to empty
     * via the dialog, soft-deleting the entire row (including embedded strokes) is intentional —
     * the user chose to clear it. This action is correct for all text objects regardless of
     * whether they carry embedded strokes.
     */
    @Serializable
    data class TextRemoved(
        val textId: String,
        val pageId: String,
        val textRender: TextRender,
        val deletedAt: Long,
    ) : UndoRedoAction()

    /**
     * User drew a scribble gesture (high density, zigzag path) that erased all content
     * objects it crossed over.  The scribble stroke itself is a pure gesture — it is never
     * persisted to the DB and does not appear on undo.
     *
     * Undo: restore all [erasedObjectIds] (clear deletedAt); add erased content back to canvas.
     * Redo: re-soft-delete all [erasedObjectIds].
     *
     * [erasedObjectIds] contains ALL erased IDs (strokes, heading IDs, text IDs).
     * [headingIds]/[headings] are the heading subset of [erasedObjectIds], with full data
     *   for in-memory restoration on undo without a DB round-trip.
     * [textIds]/[textObjects] are the text-object subset, similarly with full data.
     * [deletedAt] is the single timestamp used for all soft-delete calls during the erase,
     *   allowing targeted restoration on undo via [restoreById].
     */
    @Serializable
    data class ScribbleErased(
        val erasedObjectIds: List<String>,
        val pageId: String,
        val layerId: String,
        val deletedAt: Long,
        val headingIds: List<String> = emptyList(),
        val headings: List<HeadingStroke> = emptyList(),
        val textIds: List<String> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineIds: List<String> = emptyList(),
        val lines: List<LineRender> = emptyList(),
        val linkIds: List<String> = emptyList(),
        val links: List<LinkRender> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User inserted one or more line objects via the line creation dialog.
     *
     * Undo: soft-deletes all [lineIds]; removes from in-memory list; rebuilds bitmap.
     * Redo: restores all [lineIds]; re-adds [lines] to in-memory list; rebuilds bitmap.
     *
     * [lines] carries full render data so redo can rebuild the in-memory list without a DB read.
     */
    @Serializable
    data class LinesInserted(
        val lineIds: List<String>,
        val pageId: String,
        val layerId: String,
        val lines: List<LineRender>,
        val deletedAt: Long = 0,
    ) : UndoRedoAction()

    /**
     * User deleted line objects (single or batch outside the lasso path).
     *
     * Undo: restores all [lineIds]; re-adds [lines] to in-memory list; rebuilds bitmap.
     * Redo: soft-deletes all [lineIds]; removes from in-memory list; rebuilds bitmap.
     */
    @Serializable
    data class LinesRemoved(
        val lineIds: List<String>,
        val pageId: String,
        val lines: List<LineRender>,
        val deletedAt: Long,
    ) : UndoRedoAction()

    /**
     * User converted a lasso selection of strokes into a type="text" object via the
     * floating "Text" toolbar button.
     *
     * Undo: soft-delete the text row, restore the original stroke rows, clear selection.
     * Redo: re-soft-delete the original strokes, restore the text row, re-select it.
     *
     * [deletedAt] is the timestamp used to soft-delete the original strokes — used by undo
     * to identify which rows to restore.
     * [textRender] carries the full render state of the new text row (id, boundingBox,
     * text = recognised string or "", strokes = embedded originals with fresh UUIDs) so redo
     * can rebuild the in-memory list and selection without a DB read.
     */
    @Serializable
    data class TextConverted(
        val textId: String,
        val pageId: String,
        val layerId: String,
        val deletedAt: Long,
        val originalStrokeIds: List<String>,
        val textRender: TextRender,
    ) : UndoRedoAction()

    /**
     * User wrapped a heterogeneous lasso selection into a `type = "link"` object.
     *
     * Undo: soft-delete the link row, restore all original (held) rows by ID.
     * Redo: re-soft-delete the originals, restore the link row.
     *
     * The original held rows are still present in the DB (soft-deleted at [deletedAt]); both
     * undo and redo flip soft-delete state by ID. [link] carries full render data so a future
     * optimised same-page handler could rebuild the link in-memory without a DB read; the current
     * full-reload path re-reads it from the DB.
     */
    @Serializable
    data class LinkCreated(
        val linkId: String,
        val pageId: String,
        val layerId: String,
        val deletedAt: Long,
        val originalStrokeIds: List<String> = emptyList(),
        val originalHeadingIds: List<String> = emptyList(),
        val originalTextIds: List<String> = emptyList(),
        val originalLineIds: List<String> = emptyList(),
        val link: LinkRender,
    ) : UndoRedoAction()

    /**
     * User removed a link, dispersing its embedded held objects back onto the layer as their own
     * live rows (fresh UUIDs).
     *
     * Undo: soft-delete the restored rows by ID, restore the link row.
     * Redo: soft-delete the link row, restore the restored rows by ID.
     */
    @Serializable
    data class LinkRemoved(
        val linkId: String,
        val pageId: String,
        val restoredStrokeIds: List<String> = emptyList(),
        val restoredHeadingIds: List<String> = emptyList(),
        val restoredTextIds: List<String> = emptyList(),
        val restoredLineIds: List<String> = emptyList(),
    ) : UndoRedoAction()

    /**
     * User edited a link's chrome and/or target in place (Session 2). The embedded held objects are
     * untouched — only the serialized `data` column changes.
     *
     * Undo: rewrite the link row's data with [oldChrome] + [oldTarget].
     * Redo: rewrite it with [newChrome] + [newTarget].
     * Both take the full-reload path, re-reading the link from the DB.
     */
    @Serializable
    data class LinkEdited(
        val linkId: String,
        val pageId: String,
        val oldChrome: LinkChrome,
        val oldTarget: LinkTarget,
        val newChrome: LinkChrome,
        val newTarget: LinkTarget,
    ) : UndoRedoAction()
}
