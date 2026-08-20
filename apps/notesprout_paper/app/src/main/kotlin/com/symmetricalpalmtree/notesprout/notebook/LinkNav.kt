package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The pure decisions behind link navigation (arc 7 / L4) — same-vs-cross notebook, dead-vs-live,
 * self-target — separated from [LinkFlow]'s IO so they are JVM-testable. Foreign existence (index
 * `alive`, the read-only page-row check) is the caller's: these functions only classify against
 * what the open session already knows. No Android imports.
 */
object LinkNav {

    /** What a resolved destination means from the open notebook. */
    sealed class Plan {
        /** Navigate within the open notebook (the id is re-looked-up under the page-op lock). */
        data class SamePage(val pageId: String) : Plan()
        /** Seal + relaunch into [notebookId]; [initialPageId] null = its own last-open page.
         *  The caller still validates the notebook (index) and the page (read-only row check). */
        data class OtherNotebook(val notebookId: String, val initialPageId: String?) : Plan()
        /** The destination names something the open notebook knows is gone — honest dialog. */
        object Dead : Plan()
        /** A self-target (this notebook as a whole) — silent no-op; the picker never creates one,
         *  but the payload is untrusted. */
        object NoOp : Plan()
    }

    /**
     * Classify a resolved `LinkDestination` (already `requireValid`-shaped: [kind] with the id
     * slots its kind requires) against the open notebook [currentNotebookId] with live page ids
     * [pageIds] in order.
     */
    fun planFollow(
        kind: Int,
        notebookId: String?,
        pageId: String?,
        currentNotebookId: String,
        pageIds: List<String>,
    ): Plan = when (kind) {
        ExtensionContract.DEST_PAGE ->
            if (pageId != null && pageId in pageIds) Plan.SamePage(pageId) else Plan.Dead
        ExtensionContract.DEST_NOTEBOOK ->
            when {
                notebookId == null -> Plan.Dead
                notebookId == currentNotebookId -> Plan.NoOp
                else -> Plan.OtherNotebook(notebookId, null)
            }
        ExtensionContract.DEST_NOTEBOOK_PAGE ->
            when {
                notebookId == null || pageId == null -> Plan.Dead
                notebookId == currentNotebookId ->
                    if (pageId in pageIds) Plan.SamePage(pageId) else Plan.Dead
                else -> Plan.OtherNotebook(notebookId, pageId)
            }
        else -> Plan.Dead
    }

    /** What one popped trail entry means from the open notebook. */
    sealed class BackStep {
        /** The entry points into the open notebook — navigate to its page. */
        data class SamePage(val pageId: String) : BackStep()
        /** The entry points at another notebook — the caller validates it (alive + page row) and
         *  seals + relaunches, or skips to the next pop when it is dead (L4 Q2). */
        data class OtherNotebook(val notebookId: String, val pageId: String) : BackStep()
        /** Dead where the open notebook can already tell (its own page gone) — skip silently. */
        object Skip : BackStep()
    }

    /** Classify one popped `TrailEntry` against the open notebook. */
    fun planBack(
        entryNotebookId: String,
        entryPageId: String,
        currentNotebookId: String,
        pageIds: List<String>,
    ): BackStep = when {
        entryNotebookId == currentNotebookId ->
            if (entryPageId in pageIds) BackStep.SamePage(entryPageId) else BackStep.Skip
        else -> BackStep.OtherNotebook(entryNotebookId, entryPageId)
    }
}
