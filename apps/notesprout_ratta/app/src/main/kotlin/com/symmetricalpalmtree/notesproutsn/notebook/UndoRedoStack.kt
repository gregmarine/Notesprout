package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The notebook's in-memory undo/redo history. g-paper keeps no history by design
 * (`host-responsibilities.md`) — the host records what happened and replays it — so this is the
 * record.
 *
 * **Notebook-level, not page-level:** every entry carries the page it happened on, so history
 * survives a page turn (an insert or a delete *is* a page turn, and undoing one has to reverse
 * that turn too). The stack is cleared only when the screen dies — never on a flip. Recording a
 * fresh edit clears the redo side. Bounded at [MAX] entries, oldest dropped: an [Action.Erased]
 * (or [Action.Deleted]) holds the full geometry of every stroke it must be able to put back.
 *
 * Pure ordering only. Applying an action back onto the paper and the store lives in
 * [NotebookActivity], where the paper, the session and the store are all in reach — and where the
 * SN rule holds that a replay mutates the store first and then reloads the page, because the `.soil`
 * is the source of truth.
 */
class UndoRedoStack {

    sealed interface Action {
        val pageId: String

        data class Drew(override val pageId: String, val stroke: Stroke) : Action

        data class Erased(override val pageId: String, val strokes: List<Stroke>) : Action

        /**
         * A lasso selection deleted through the selection toolbar. The strokes replay exactly like
         * [Erased] — kept as its own kind because the two are different acts to the user (a sweep
         * of the eraser vs. "delete these"), and a future undo *label* must be able to say which.
         * [headingIds] (N2) are the selected headings deleted in the same tap — ids only, because
         * heading rows are revived in place with their geometry intact. One gesture, one entry.
         */
        data class Deleted(
            override val pageId: String,
            val strokes: List<Stroke>,
            val headingIds: List<String> = emptyList(),
            /**
             * Links deleted in the same act (K1) — full [PageLink] snapshots, because restoring a
             * link means re-upserting its row *and* reviving its wrapped children
             * (`LinkStore.restore`); ids alone couldn't rebuild a row the writer never saw.
             */
            val links: List<PageLink> = emptyList(),
        ) : Action

        /**
         * One selection drag. [headingIds] (N2) are the headings that rode along — a mixed lasso
         * moves strokes and headings in one gesture, and one gesture must stay one undo step
         * (this is the plan's `HeadingMoved`, folded in rather than split).
         */
        data class Moved(
            override val pageId: String,
            val ids: List<String>,
            val dx: Float,
            val dy: Float,
            val headingIds: List<String> = emptyList(),
            /** Links that rode the same drag (K1) — ids only: `LinkStore.move` shifts a link row
             *  and its wrapped children from ids, in either direction. */
            val linkIds: List<String> = emptyList(),
        ) : Action

        /**
         * A heading conversion: the new heading plus the ink it consumed. Undo deletes the heading
         * row and **revives the strokes in place** (writing order is load-bearing — the arc-3
         * trap); redo revives the heading row and re-deletes the strokes. Ids suffice on the
         * stroke side because every row survives soft-deleted with its geometry.
         */
        data class HeadingCreated(
            override val pageId: String,
            val heading: Heading,
            val strokeIds: List<String>,
        ) : Action

        /** Headings deleted without strokes in the act: an eraser sweep over a heading
         *  (`onContentErased`) or an edit dialog's empty Save. Rows revive in place. */
        data class HeadingDeleted(override val pageId: String, val headingIds: List<String>) : Action

        /** An edit-dialog Save that changed the text. Both sides carry the full [Heading]
         *  (text, level, re-measured box) — replay writes one side's content over the row. */
        data class HeadingTextEdited(
            override val pageId: String,
            val before: Heading,
            val after: Heading,
        ) : Action

        /** A level pick on an existing heading. Same replay as [HeadingTextEdited]; kept as its
         *  own kind for the same reason [Deleted] is not [Erased] — the label must be able to
         *  say which act it reverses. */
        data class HeadingLevelChanged(
            override val pageId: String,
            val before: Heading,
            val after: Heading,
        ) : Action

        /**
         * A wrap (arc 6 / K1): the new link with its wrapped-children snapshots. Undo is exactly
         * an unlink (`LinkStore.unlink` — children re-parent back to the page, row soft-deleted);
         * redo is a `relink`. One act, one entry.
         */
        data class LinkCreated(override val pageId: String, val link: PageLink) : Action

        /** An Unlink from the selection toolbar. Undo re-wraps (`relink`); redo unlinks again. */
        data class LinkUnlinked(override val pageId: String, val link: PageLink) : Action

        /**
         * A payload edit (K2's Edit — the contract lands with K1, exercised when the picker
         * exists). Both sides carry the payload string; replay writes one side over the row
         * (`LinkStore.updatePayload`) — bounds and children are untouched by an edit.
         */
        data class LinkEdited(
            override val pageId: String,
            val linkId: String,
            val before: String,
            val after: String,
        ) : Action

        /**
         * An object paste (arc 8) — [Deleted] run in reverse. Undo soft-deletes the rows the paste
         * created (a link's [PageLink] snapshot takes its wrapped children down with it, exactly as
         * a delete does); redo restores them in place, geometry and rebased `"order"` intact,
         * because a soft-deleted row keeps everything. Ids suffice on the stroke and heading sides
         * for the same reason.
         *
         * Its own kind rather than a `Deleted` with the arms swapped, for [PagePasted]'s reason: an
         * entry whose ids run the opposite direction cannot share a replay arm without one of the
         * two undoing itself.
         */
        data class ObjectsPasted(
            override val pageId: String,
            val strokeIds: List<String>,
            val headingIds: List<String>,
            val links: List<PageLink>,
        ) : Action

        /** A page insert or delete, replayable both ways through [NotebookSession.reconcile]. */
        data class Page(val snapshot: NotebookSession.Structural) : Action {
            override val pageId: String get() = snapshot.afterCurrentId
        }

        /**
         * A page **paste** (arc 7) — the same [NotebookSession.Structural] snapshot, replayed
         * through the same [NotebookSession.reconcile], but its own kind because `objectIds` runs
         * the **opposite direction**: a delete's are rows to put *back* on undo, a paste's are rows
         * to take *away*. Folding the two into one arm would restore what the paste created.
         *
         * The template row a paste may have inserted is deliberately not in the snapshot: it is
         * left in place, harmless, and the next paste's dedupe reuses it.
         */
        data class PagePasted(val snapshot: NotebookSession.Structural) : Action {
            override val pageId: String get() = snapshot.afterCurrentId
        }
    }

    private val undo = ArrayDeque<Action>()
    private val redo = ArrayDeque<Action>()

    /**
     * Bumped by every [record]. A replay in flight snapshots it before reverting and compares
     * after: a change means a fresh edit landed mid-replay (and cleared redo) — the replayer must
     * not push the undone entry onto redo, or record-clears-redo silently breaks.
     */
    var generation: Int = 0
        private set

    /** Record an edit that just happened. Clears the redo history. */
    fun record(action: Action) {
        undo.addLast(action)
        while (undo.size > MAX) undo.removeFirst()
        redo.clear()
        generation++
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Take the newest edit off the undo side; the caller reverts it, then [pushRedo]s it. */
    fun popUndo(): Action? = undo.removeLastOrNull()

    fun pushRedo(action: Action) {
        redo.addLast(action)
    }

    /** Take the newest undone edit off the redo side; the caller re-applies it, then [pushUndo]s it. */
    fun popRedo(): Action? = redo.removeLastOrNull()

    fun pushUndo(action: Action) {
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
