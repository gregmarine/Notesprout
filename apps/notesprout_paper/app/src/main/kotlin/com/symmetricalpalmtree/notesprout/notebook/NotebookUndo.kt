package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The notebook screen's undo action set (arc 6 / S0 — lifted out of `UndoRedoStack`, which is now
 * the generic `UndoRedoStack<A>` in `:paper-screen`; the notebook's stack is
 * `UndoRedoStack<NotebookUndo.Action>`) **and its replay** ([undo] / [redo] — arc 6 / S1, a pure move
 * out of `NotebookActivity`). Each entry knows the page it happened on, so undo survives page turns.
 * Every replay is store → drain → reload the affected page (strokes + objects) through the screen's
 * [refreshToPage], so the DB stays the source of truth and the paper never desyncs. Called under the
 * screen's page-op lock.
 */
object NotebookUndo {

    /** Pop the last edit, revert it on [session], move it to the redo side. */
    suspend fun undo(session: NotebookSession, stack: UndoRedoStack<Action>, refreshToPage: suspend (pageId: String) -> Unit) {
        val a = stack.popUndo() ?: return
        session.store.drain()
        revert(session, a, refreshToPage)
        stack.pushRedo(a)
    }

    /** Pop the last undone edit, re-apply it on [session], move it back to the undo side. */
    suspend fun redo(session: NotebookSession, stack: UndoRedoStack<Action>, refreshToPage: suspend (pageId: String) -> Unit) {
        val a = stack.popRedo() ?: return
        session.store.drain()
        reapply(session, a, refreshToPage)
        stack.pushUndo(a)
    }

    private suspend fun revert(session: NotebookSession, a: Action, refreshToPage: suspend (String) -> Unit) {
        val store = session.store; val objects = session.objectStore; val links = session.linkStore
        when (a) {
            is Action.Drew -> store.remove(listOf(a.stroke.id))
            is Action.Erased -> store.restore(a.pageId, a.strokes)
            is Action.Moved -> { store.move(a.ids, -a.dx, -a.dy); objects.move(a.objectIds, -a.dx, -a.dy); links.move(a.linkIds, -a.dx, -a.dy) }
            is Action.ObjectCreated -> { objects.remove(listOf(a.obj.id)); store.restore(a.pageId, a.removedStrokes) }
            is Action.ObjectsDeleted -> { store.restore(a.pageId, a.strokes); objects.restore(a.pageId, a.objects); links.restore(a.pageId, a.links) }
            is Action.ObjectEdited -> a.before.let { objects.updatePayloadAndBounds(it.id, it.payload, it.x, it.y, it.width, it.height) }
            is Action.Pasted -> store.remove(a.strokes.map { it.id })
            is Action.LinkCreated -> links.unlink(a.pageId, a.link)
            is Action.LinkUnlinked -> links.relink(a.pageId, a.link)
            is Action.LinkEdited -> links.updatePayload(a.linkId, a.beforePayload)
            is Action.Page -> {
                session.reconcile(a.snapshot.before, a.snapshot.childIds, emptyList(), a.snapshot.beforeCurrentId)
                refreshToPage(session.currentPage.id)
                return
            }
        }
        session.writer.drain()
        refreshToPage(a.pageId)
    }

    private suspend fun reapply(session: NotebookSession, a: Action, refreshToPage: suspend (String) -> Unit) {
        val store = session.store; val objects = session.objectStore; val links = session.linkStore
        when (a) {
            is Action.Drew -> store.restore(a.pageId, listOf(a.stroke))
            is Action.Erased -> store.remove(a.strokes.map { it.id })
            is Action.Moved -> { store.move(a.ids, a.dx, a.dy); objects.move(a.objectIds, a.dx, a.dy); links.move(a.linkIds, a.dx, a.dy) }
            is Action.ObjectCreated -> { objects.restore(a.pageId, listOf(a.obj)); store.remove(a.removedStrokes.map { it.id }) }
            is Action.ObjectsDeleted -> { store.remove(a.strokes.map { it.id }); objects.remove(a.objects.map { it.id }); links.remove(a.links) }
            is Action.ObjectEdited -> a.after.let { objects.updatePayloadAndBounds(it.id, it.payload, it.x, it.y, it.width, it.height) }
            is Action.Pasted -> store.restore(a.pageId, a.strokes)
            is Action.LinkCreated -> links.relink(a.pageId, a.link)
            is Action.LinkUnlinked -> links.unlink(a.pageId, a.link)
            is Action.LinkEdited -> links.updatePayload(a.linkId, a.afterPayload)
            is Action.Page -> {
                session.reconcile(a.snapshot.after, emptyList(), a.snapshot.childIds, a.snapshot.afterCurrentId)
                refreshToPage(session.currentPage.id)
                return
            }
        }
        session.writer.drain()
        refreshToPage(a.pageId)
    }

    sealed interface Action {
        val pageId: String
        data class Drew(override val pageId: String, val stroke: Stroke) : Action
        data class Erased(override val pageId: String, val strokes: List<Stroke>) : Action
        /** A selection drag: [ids] = strokes, [objectIds] = content objects (arc 4), [linkIds] =
         *  links (arc 7 — [LinkStore.move] translates each link row **and** its wrapped children),
         *  same delta for all. */
        data class Moved(
            override val pageId: String,
            val ids: List<String>,
            val dx: Float,
            val dy: Float,
            val objectIds: List<String> = emptyList(),
            val linkIds: List<String> = emptyList(),
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
        /** A selection (or an eraser sweep, arc 7) was deleted: strokes, objects and/or whole links,
         *  together. A deleted link takes its wrapped children with it; restore brings both back. */
        data class ObjectsDeleted(
            override val pageId: String,
            val strokes: List<Stroke>,
            val objects: List<PageObject>,
            val links: List<PageLink> = emptyList(),
        ) : Action
        /** An object's payload and/or bounds changed (edit, action, re-size). [before]/[after] hold the
         *  whole object so either direction is one `updatePayloadAndBounds`. */
        data class ObjectEdited(override val pageId: String, val before: PageObject, val after: PageObject) : Action

        // ── Links (arc 7 / L1) — structure is core-owned; the payload stays opaque even here ──

        /** A selection was wrapped into [link] (children re-parented page → link, one transaction).
         *  Undo unwraps (children back to the page, link row gone); redo re-wraps. */
        data class LinkCreated(override val pageId: String, val link: PageLink) : Action
        /** A link was unwrapped ([LinkStore.unlink]). Undo re-wraps; redo unwraps again. */
        data class LinkUnlinked(override val pageId: String, val link: PageLink) : Action
        /** A link's payload was replaced by the picker's Edit (arc 7 / L2 — the shape exists from L1).
         *  Wrapped content untouched; either direction is one [LinkStore.updatePayload]. */
        data class LinkEdited(
            override val pageId: String,
            val linkId: String,
            val beforePayload: String,
            val afterPayload: String,
        ) : Action

        // ── Scratch Pad (arc 6 / S2) ──

        /** Ink pasted from the scratch pad — one step for the whole paste (fresh ids, minted by the
         *  core). Undo soft-deletes the rows; redo restores them in place. */
        data class Pasted(override val pageId: String, val strokes: List<Stroke>) : Action
    }
}
