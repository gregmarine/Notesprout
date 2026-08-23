package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * The pure follow / walk-back planner (arc 6 / K4) — Paper L4's `LinkNav` fresh-written for SN.
 * Classification only: it turns a stored payload (or a trail entry) plus the current notebook id
 * into a [Follow] / [Back] plan, and never touches a database — every existence check (index row
 * alive, page row live) is [LinkFollowFlow]'s, performed *after* planning and *before* navigating.
 *
 * Two Paper rules carried verbatim:
 * - A [LinkPayload.KIND_NOTEBOOK_PAGE] naming the **current** notebook is an in-notebook hop
 *   ([Follow.SamePage]) — no seal, no relaunch.
 * - A self-referential [LinkPayload.KIND_NOTEBOOK] is a silent [Follow.NoOp]: the payload is
 *   untrusted file input (our picker refuses to compose one), and "reopen the notebook you are in"
 *   has no honest meaning.
 *
 * Plans carry page **ids**, never indexes — an index is looked up under the page-op lock at
 * navigation time, because the page list can change between the tap and the hop.
 *
 * Pure Kotlin — JVM-tested ([LinkNavTest]).
 */
object LinkNav {

    /** What a tap on a link should do — before any existence check. */
    sealed interface Follow {
        /** Navigate to a page of the open notebook. */
        data class SamePage(val pageId: String) : Follow

        /** Seal and relaunch into another notebook; a null [pageId] is a whole-notebook target,
         *  which opens at its own remembered page (`refId`). */
        data class OtherNotebook(val notebookId: String, val pageId: String?) : Follow

        /** Unusable payload (foreign, future, corrupt) — the dead-target dialog. */
        object Dead : Follow

        /** Self-referential notebook target — silently nothing. */
        object NoOp : Follow
    }

    fun planFollow(payload: String, currentNotebookId: String): Follow {
        val d = LinkPayload.decode(payload) ?: return Follow.Dead
        return when (d.kind) {
            LinkPayload.KIND_PAGE -> Follow.SamePage(d.pageId!!)
            LinkPayload.KIND_NOTEBOOK ->
                if (d.notebookId == currentNotebookId) Follow.NoOp
                else Follow.OtherNotebook(d.notebookId!!, null)
            LinkPayload.KIND_NOTEBOOK_PAGE ->
                if (d.notebookId == currentNotebookId) Follow.SamePage(d.pageId!!)
                else Follow.OtherNotebook(d.notebookId!!, d.pageId)
            // decode() already refused unknown kinds — belt to its braces, never a crash.
            else -> Follow.Dead
        }
    }

    /** What popping a trail entry should do. Validation (dead entries skip silently) is the
     *  flow's — a [Back] plan is where to go, not a promise the target still exists. */
    sealed interface Back {
        data class SamePage(val pageId: String) : Back
        data class OtherNotebook(val notebookId: String, val pageId: String) : Back
    }

    fun planBack(entryNotebookId: String, entryPageId: String, currentNotebookId: String): Back =
        if (entryNotebookId == currentNotebookId) Back.SamePage(entryPageId)
        else Back.OtherNotebook(entryNotebookId, entryPageId)
}
