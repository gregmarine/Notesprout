package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The notebook screen's undo action set (arc 11 / J1 — lifted out of `UndoRedoStack`, which is now
 * the generic `UndoRedoStack<A>` in `:sn-screen`; the notebook's stack is
 * `UndoRedoStack<NotebookUndo.Action>`, and the Scratch Pad extension will bring its own set).
 * Every entry carries the page it happened on, so history survives a page turn.
 *
 * The replay stays in [NotebookActivity], where the paper, the session and the store are all in
 * reach — and where the SN rule holds that a replay mutates the store first and then reloads the
 * page, because the `.soil` is the source of truth.
 */
object NotebookUndo {

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
            /** Arc 28 (H1) — texts and shapes deleted in the same tap. Ids only, for the heading's
             *  reason: their rows survive soft-deleted with every column intact and revive in place. */
            val textIds: List<String> = emptyList(),
            val shapeIds: List<String> = emptyList(),
            /**
             * Stickies deleted in the same tap — full [PageSticky] snapshots **with their content**
             * ([StickyStore.withContent]), never ids: `StickyStore.restore` revives the snapshot's
             * `childIds`, and an icon carrying no children would come back as an empty note.
             */
            val stickies: List<PageSticky> = emptyList(),
        ) : Action

        /**
         * A scribble-erase gesture (arc 14) — a dense zigzag that crossed out ink, headings and
         * links in one act. The engine reports all three kinds in a single `onScribbleErased`
         * for exactly this reason: one gesture is one undo step, and a scribble that took a
         * stroke and a heading together must not cost the user two undos.
         *
         * Replayed identically to [Deleted] (strokes revive by id, heading rows revive in place,
         * links need their full [PageLink] snapshots to bring back the row *and* its wrapped
         * children). Kept as its own kind for the reason [Erased] and [Deleted] are separate
         * kinds: a scribble is a different act to the user than a Delete tap, and a future undo
         * *label* has to be able to say which one it is reversing.
         */
        data class ScribbleErased(
            override val pageId: String,
            val strokes: List<Stroke>,
            val headingIds: List<String> = emptyList(),
            val links: List<PageLink> = emptyList(),
            /** Arc 28 (H1) — the three new kinds a scribble can take, on [Deleted]'s terms: ids for
             *  texts and shapes, whole content-carrying snapshots for stickies. */
            val textIds: List<String> = emptyList(),
            val shapeIds: List<String> = emptyList(),
            val stickies: List<PageSticky> = emptyList(),
        ) : Action

        /**
         * A **lasso-erase** gesture (arc 29 / LE2, g-paper 0.1.28's `Tool.LASSO_ERASER`) — one
         * closed outline that took everything it holds: ink, headings, links, texts, shapes and
         * stickies together. The engine reports it all in a single `onLassoErased` for
         * [ScribbleErased]'s reason: **one gesture is one undo step**, and a loop that swallowed a
         * stroke and the heading above it must not cost the user two undos.
         *
         * Replayed identically to [Deleted] and [ScribbleErased] — strokes revive by id, heading /
         * text / shape rows revive in place, links and stickies need their full snapshots. It is
         * kept as its **own kind** for the reason those two are separate kinds and not one: drawing
         * a loop around something is a different act to the user than crossing it out or tapping
         * Delete, and a future undo *label* has to be able to say which one it is reversing.
         */
        data class LassoErased(
            override val pageId: String,
            val strokes: List<Stroke>,
            val headingIds: List<String> = emptyList(),
            val links: List<PageLink> = emptyList(),
            val textIds: List<String> = emptyList(),
            val shapeIds: List<String> = emptyList(),
            val stickies: List<PageSticky> = emptyList(),
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
            /**
             * Arc 28 (H1) — texts, shapes and stickies that rode the same drag. **Ids only, all
             * three**, including the stickies: a move is a delta on two columns and nothing is
             * created or destroyed, so there is no row to rebuild from a snapshot. A sticky's
             * content does not move at all (it is local to the note), which is exactly why its
             * icon's id is the whole of what a move has to remember.
             */
            val textIds: List<String> = emptyList(),
            val shapeIds: List<String> = emptyList(),
            val stickyIds: List<String> = emptyList(),
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
         * A **Bible reference** made (arc 38 / R3) — the lasso bar's Bible button or the Insert
         * bar's: one text object holding the user's own words, wrapped in a [link] whose payload
         * names the passage, and (for a conversion) the ink both replaced. Three rows in one act,
         * so **one entry**: [TextCreated] and [LinkCreated] composed, never recorded as two, or the
         * user would undo half a reference and be left with a text object that had been a link.
         *
         * Undo unwraps, deletes the text and revives the ink **in place** (writing order is
         * load-bearing — the arc-3 trap); redo restores the text, re-wraps it and re-deletes the
         * ink. [strokeIds] is empty for an insert, which makes the same arm cover both.
         */
        data class BibleRefCreated(
            override val pageId: String,
            val link: PageLink,
            val text: PageText,
            val strokeIds: List<String> = emptyList(),
        ) : Action

        /**
         * A **Bible reference edited** (arc 38 / R3) — the reference dialog's Save on a lone Bible
         * link: the wrapped text's words, the link's payload and the link's re-derived box all
         * change together, so all three ride one entry.
         *
         * Both sides are the whole [PageLink] **carrying its one wrapped text**, and the replay
         * writes one side over the rows (the text's content, the link's payload, the link's box).
         * Nothing is created or destroyed — [before] and [after] are the same two row ids — which
         * is why a snapshot pair is enough and neither side needs an id list.
         */
        data class BibleRefEdited(
            override val pageId: String,
            val before: PageLink,
            val after: PageLink,
        ) : Action

        /**
         * A text object created (arc 28 / H2): either an **insert** from the Insert bar, whose
         * [strokeIds] is empty, or a **conversion** of lassoed ink, whose [strokeIds] is the ink
         * the text replaced. Undo erases the text row and revives the ink **in place** (writing
         * order is load-bearing — the arc-3 trap); redo restores the row and re-deletes the ink.
         * The heading conversion's entry, with the two cases folded into one kind because an
         * insert is a conversion of nothing.
         */
        data class TextCreated(
            override val pageId: String,
            val text: PageText,
            val strokeIds: List<String> = emptyList(),
        ) : Action

        /** An edit-dialog Save that changed a text object. Both sides carry the whole [PageText]
         *  (source and the re-measured box, the top-left unchanged) — replay writes one side's
         *  content over the row ([TextStore.updateContent]), exactly as [HeadingTextEdited] does. */
        data class TextEdited(
            override val pageId: String,
            val before: PageText,
            val after: PageText,
        ) : Action

        /** A shape placed from the Insert bar (arc 28 / H4). Undo soft-deletes the row, redo
         *  revives it in place — geometry, rotation and z-order all still on it. */
        data class ShapeInserted(override val pageId: String, val shape: PageShape) : Action

        /**
         * One finished transform (arc 28 / H4 — g-paper's transform mode reports before/after in
         * one callback, so a whole drag of a handle is one entry). Both sides carry the whole
         * [PageShape]; replay writes one side's geometry word over the row
         * ([ShapeStore.transform]). Nothing is created or destroyed, so ids suffice on neither
         * side and the snapshots are the entry.
         */
        data class ShapeTransformed(
            override val pageId: String,
            val before: PageShape,
            val after: PageShape,
        ) : Action

        /**
         * A sticky note inserted from the Insert bar (arc 28 / H5). The [sticky] is the icon row
         * as created — no content yet, because the editor has not run: an insert's undo takes an
         * empty note away. Undo is `StickyStore.remove` (which takes any children with it), redo
         * `StickyStore.restore`, which re-inserts the row if the undo's soft-delete is not what it
         * finds.
         */
        data class StickyInserted(override val pageId: String, val sticky: PageSticky) : Action

        /**
         * One **showing** of the sticky editor that changed the note (arc 28 / H5): the content
         * before it opened and the content it left. Recorded once per showing from the result
         * callback — the editor's own undo stack is alive inside the showing only, and the page's
         * history must not fill with one entry per stroke drawn inside a note.
         *
         * Both sides are whole [Stroke] lists in the note's **local** space; replay is
         * `StickyStore.setContent(stickyId, side)`, which makes the set the note's whole content
         * (rows not in it soft-deleted, rows in it upserted in order) — so either direction is
         * the same call with the other list.
         */
        data class StickyContentEdited(
            override val pageId: String,
            val stickyId: String,
            val before: List<Stroke>,
            val after: List<Stroke>,
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
            /** Arc 28 (H1) — the pasted texts and shapes, ids only ([Deleted]'s reason). */
            val textIds: List<String> = emptyList(),
            val shapeIds: List<String> = emptyList(),
            /** The pasted stickies as whole snapshots **with the content the paste wrote**: the
             *  redo has to revive the note's children by id, and only the snapshot names them. */
            val stickies: List<PageSticky> = emptyList(),
        ) : Action

        /**
         * A whole page **erased** (arc 30 / PE1): every live object on the page dated out in one
         * transaction, the page row itself untouched. Ids only, for the heading's reason writ
         * large — nothing moves and nothing is re-minted, so the soft-deleted rows keep every
         * column and revive in place; sticky children and link-wrapped children are in the list
         * already ([SoilDao.liveDescendantIds]). Revert = [NotebookSession.restoreIds], reapply =
         * [NotebookSession.eraseIds]. Its own kind rather than a widened [Deleted]: a `Deleted`
         * needs snapshots for links and stickies because a lasso delete re-minted nothing either
         * but its stores replay per type; the erase replays through the two DAO calls alone.
         * Never recorded empty — an erase of an empty page is nothing.
         */
        data class PageErased(override val pageId: String, val objectIds: List<String>) : Action

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

        /**
         * A page **received whole from the calendar** (arc 31 / HV5) — a new page after the one on
         * screen, papered with the view that was sent and carrying its ink, made in one
         * transaction and undone in one step.
         *
         * [PagePasted]'s snapshot and [PagePasted]'s replay to the line: `objectIds` are rows the
         * receive **created**, so undo soft-deletes them along with the page they hang under and
         * redo revives them in place. Its own kind all the same, for the same reason the paste is
         * not a `Page`: what an entry *is* is what a future undo label reads off it, and "the page
         * from the calendar" is not "a page you pasted".
         *
         * The template row the receive may have minted is left standing, exactly as a paste's is:
         * a template is cheap, deleting one is not undoable, and leaving it is what makes the same
         * send land on the same row the second time.
         */
        data class PageReceived(val snapshot: NotebookSession.Structural) : Action {
            override val pageId: String get() = snapshot.afterCurrentId
        }

        /**
         * **Several pages received from the calendar in one gesture** (arc 35 / HA1 — a Day's two
         * halves), each made by its own `receivePage` in order, undone and redone **together**:
         * undo reconciles to the list the first insert saw with every row any of them created
         * soft-deleted; redo to the list the last insert left with them revived. The snapshots
         * chain — each `before` is the previous `after` — which is what makes the two ends enough.
         * Reports the last page, where the notebook was left.
         */
        data class PagesReceived(val snapshots: List<NotebookSession.Structural>) : Action {
            init { require(snapshots.isNotEmpty()) { "a multi-page receive has at least one page" } }
            val first: NotebookSession.Structural get() = snapshots.first()
            val last: NotebookSession.Structural get() = snapshots.last()
            val objectIds: List<String> get() = snapshots.flatMap { it.objectIds }
            override val pageId: String get() = last.afterCurrentId
        }

        /**
         * One page re-papered (arc 12) — the two template-row ids the page moved between, `""` for
         * blank. Replayed through [NotebookSession.applyTemplate] in either direction; no rows are
         * created or destroyed by the replay, because the template row the change may have minted
         * is left standing (see [NotebookSession.changeTemplate]) exactly as a page paste's is.
         */
        data class TemplateChanged(
            override val pageId: String,
            val from: String,
            val to: String,
        ) : Action
    }
}
