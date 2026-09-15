package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseCache
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.LinkTrail
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.TrailEntry
import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteIndex
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a note row goes (arc 42 "Notes", N3) — **pure**, so the one decision that matters can be
 * read in a JVM test rather than inferred from a screen: a row of the reader's Notes panel names a
 * notebook, a page (or none) and a kind, and the host has to turn that into one of four hops.
 *
 * [BibleNoteFollow] is the effects around it; everything below is a table.
 */
object BibleNoteFollowRules {

    /** The four hops a note row can ask for. */
    sealed interface Plan {
        /** A link row on a live page of the notebook already open: flip to it. */
        data class SamePage(val pageId: String) : Plan

        /** A document row on the notebook already open. [pageId] is empty for the notebook
         *  document / a text document — the editor opens where it opens. */
        data class SameNotebookDocument(val pageId: String) : Plan

        /**
         * Another notebook. [pageId] is null for a page-less document row; [openEditor] asks the
         * notebook screen to raise the document editor over the page it lands on, which is only
         * honest when there IS a page (see [BibleNoteFollow.follow]).
         */
        data class Other(val notebookId: String, val pageId: String?, val openEditor: Boolean) : Plan

        /** The row points at a page this notebook no longer has — the index is stale. */
        data class DeadPage(val pageId: String) : Plan
    }

    /**
     * [target] against where the user is standing. [currentNotebookId] is null on the library,
     * which is why a `SamePage` can never come out of a library follow — there is no current
     * notebook for the target to match.
     */
    fun plan(target: BibleNoteTarget, currentNotebookId: String?, livePageIds: List<String>): Plan {
        if (currentNotebookId != null && target.notebookId == currentNotebookId) {
            // A page-bound row whose page is gone is the stale-index case, for either kind: the
            // flip would land nowhere and the editor would open on whatever page is showing.
            if (target.pageId.isNotEmpty() && !livePageIds.contains(target.pageId)) {
                return Plan.DeadPage(target.pageId)
            }
            return if (target.isDocument) Plan.SameNotebookDocument(target.pageId)
            else Plan.SamePage(target.pageId)
        }
        return Plan.Other(
            notebookId = target.notebookId,
            pageId = target.pageId.ifEmpty { null },
            openEditor = target.isDocument && target.pageId.isNotEmpty(),
        )
    }
}

/**
 * Following a row of the reader's Notes panel (arc 42 "Notes", decisions 7 + 8) — the host's half
 * of "open over the reader": the reader has already closed (the Send rule), and what the person
 * asked to look at is what the next screen shows.
 *
 * It is [LinkFollowFlow.followOut]'s ritual, applied to a [BibleNoteTarget] instead of a link
 * payload, and it differs from a link follow in exactly two ways:
 *
 *  - **The index is the host's own, so a dead target heals itself** rather than only explaining.
 *    A link row is the user's writing and is never touched when its target dies; a note row is a
 *    cache the host wrote, so a notebook that has gone is deleted from the index and a page that
 *    has gone takes a whole-notebook re-push — before the dialog says so, on a detached scope
 *    (see [healPage]).
 *  - **There is a caller with no notebook behind it.** The library follows note rows too; it has
 *    no trail origin to push and no page to flip to, which the plan's null `currentNotebookId`
 *    expresses.
 *
 * A `NOTEBOOK`-scope target prompts for its passphrase (decision 8): locked notebooks are indexed,
 * and following one is as deliberate an open as a link follow. A cancelled prompt is silent — the
 * person just said no, which is not a failure.
 *
 * One follow at a time ([busy]). A hop that *leaves* the screen keeps the latch set forever: the
 * seal → launch hand-off is asynchronous, and a second tap in that gap must stay harmless. Every
 * failure is a dialog or a log line; nothing here may throw at the caller.
 */
class BibleNoteFollow(
    private val activity: AppCompatActivity,
    /** The notebook this screen has open, or **null** on the library. */
    private val currentNotebookId: () -> String?,
    /** The page on the paper right now — the trail origin. Never read when there is no notebook. */
    private val displayedPageId: () -> String = { "" },
    /** The open notebook's live page ids, in order. Empty where there is no notebook. */
    private val livePageIds: () -> List<String> = { emptyList() },
    /** Hop within the open notebook — fire-and-forget under the host's page-op lock. */
    private val navigateToPage: (String) -> Unit = {},
    /** Raise the document editor over the page now displayed — sequenced after [navigateToPage]. */
    private val openDocumentEditor: () -> Unit = {},
    /** Seal (where there is something to seal) and launch — the caller's own leave ritual. */
    private val launch: (Intent) -> Unit,
    /** A row pointed at a page of THIS notebook that is gone: the caller's own self-heal. */
    private val onDeadPage: (pageId: String) -> Unit = {},
) {

    private var busy = false

    /**
     * Go where [target] points.
     *
     * The one thing the host cannot do is open another notebook's **notebook document**: the
     * editor's scope toggle lives inside the editor, and the surface replay can only raise it over
     * a page. So a page-less document row on another notebook opens that notebook and stops there
     * — which is already the whole answer when it is a text document, because a text-document
     * notebook opens into its editor by its own route ([TextDocRouting]).
     */
    fun follow(target: BibleNoteTarget) {
        if (busy) { Slog.d(TAG) { "follow: already in flight" }; return }
        if (activity.isFinishing || activity.isDestroyed) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                when (val plan = BibleNoteFollowRules.plan(target, currentNotebookId(), livePageIds())) {
                    is BibleNoteFollowRules.Plan.SamePage -> {
                        pushOrigin()
                        navigateToPage(plan.pageId)
                        busy = false
                        Slog.d(TAG) { "note: → a page of this notebook" }
                    }
                    is BibleNoteFollowRules.Plan.SameNotebookDocument -> {
                        // No trail entry: the editor is a child of this screen, not a hop away
                        // from it — Back comes straight back here.
                        if (plan.pageId.isNotEmpty()) navigateToPage(plan.pageId)
                        openDocumentEditor()
                        busy = false
                        Slog.d(TAG) { "note: → this notebook's document editor" }
                    }
                    is BibleNoteFollowRules.Plan.DeadPage -> {
                        busy = false
                        onDeadPage(plan.pageId)
                        problem(R.string.bible_note_page_gone_title, R.string.bible_note_page_gone_body)
                    }
                    is BibleNoteFollowRules.Plan.Other -> followOut(plan)   // busy may stay set
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                Log.w(TAG, "note follow failed", e)
            }
        }
    }

    // ── Another notebook ─────────────────────────────────────────────────────

    /** [LinkFollowFlow.followOut]'s order exactly: index row, passphrase, page row, park, leave. */
    private suspend fun followOut(plan: BibleNoteFollowRules.Plan.Other) {
        val summary = withContext(Dispatchers.IO) { IndexRepository().alive(plan.notebookId) }
            ?.takeIf { it.type == ObjectType.NOTEBOOK }
        if (summary == null) {
            busy = false
            forgetNotebook(plan.notebookId)
            problem(R.string.bible_note_notebook_gone_title, R.string.bible_note_notebook_gone_body)
            return
        }
        // Decision 8: a locked notebook is indexed, and following into it asks.
        var typed: String? = null
        if (KeyScope.of(summary.keyScope) == KeyScope.NOTEBOOK) {
            typed = NotebookPassphrasePrompt.ask(activity, plan.notebookId, summary.name)
            if (typed == null) { busy = false; return }
        }
        val pageId = plan.pageId
        if (pageId != null && !ForeignPageCheck.alive(activity, plan.notebookId, pageId, typed)) {
            busy = false
            healPage(plan.notebookId, summary.name, typed)
            problem(R.string.bible_note_page_gone_title, R.string.bible_note_page_gone_body)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) { busy = false; return }
        // One prompt per follow: the notebook screen prompts on its own open too, so park the
        // verified passphrase for that single open to take silently. Single-use and RAM-only.
        typed?.let { PassphraseCache.storeOnce(plan.notebookId, it) }
        val here = currentNotebookId()
        // Only a screen with a notebook behind it has an origin to come back to; the library is
        // where a walk-back ends anyway.
        if (here != null) LinkTrail(activity).push(TrailEntry(here, displayedPageId()))
        Slog.d(TAG) { "note: → another notebook${if (pageId != null) " at a page" else ""}" }
        launch(
            NotebookActivity.intent(
                activity, plan.notebookId, summary.name,
                viaLink = here != null,
                initialPageId = pageId,
                resumeAbove = if (plan.openEditor) listOf(Surface.DOCUMENT_EDITOR) else emptyList(),
            )
        )
        // busy stays set — this screen is on its way out.
    }

    // ── Self-heal ────────────────────────────────────────────────────────────

    /**
     * The notebook is gone from the library, so its rows are: fire-and-forget on a **detached**
     * scope, because the dialog that follows may be the last thing this screen does.
     */
    private fun forgetNotebook(notebookId: String) {
        val app = activity.applicationContext
        MainScope().launch { BibleNoteIndex.delete(app, notebookId) }
    }

    /**
     * The page is gone, so every ordinal below it moved too: re-push the whole notebook from its
     * own rows. Detached, and **not awaited** — an open of a cold `.soil` can be seconds (a raw-key
     * warm is ~9 s on the Nomad), and a tap that says nothing for that long reads as broken on
     * e-ink. The dialog's "has been refreshed" is therefore a promise kept a moment later, which
     * is the honest trade: nothing the user can do depends on it.
     *
     * A notebook that cannot be read at all (a key that is no longer there) is deleted from the
     * index instead — the Rebuild door puts it back if it ever opens again.
     */
    private fun healPage(notebookId: String, name: String, typed: String?) {
        val app = activity.applicationContext
        MainScope().launch {
            val read = withContext(Dispatchers.IO) {
                val block: suspend (SoilDao) -> Pair<List<LinkRow>, List<String>> =
                    { dao -> dao.liveLinkRows() to dao.livePageIds(notebookId) }
                if (typed == null) SoilDatabase.readOnce(app, notebookId, block)
                else SoilDatabase.readOnce(app, notebookId, KeyResolver.Resolved.Passphrases(typed), block)
            }
            if (read == null) {
                Slog.d(TAG) { "heal: could not read the notebook — dropping its rows" }
                BibleNoteIndex.delete(app, notebookId)
                return@launch
            }
            val (rows, pages) = read
            BibleNoteIndex.pushNotebook(app, notebookId, name, pages, BibleNoteIndex.notebookNotes(rows, pages))
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun pushOrigin() {
        val here = currentNotebookId() ?: return
        LinkTrail(activity).push(TrailEntry(here, displayedPageId()))
    }

    private fun problem(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, titleRes, bodyRes)
    }

    private companion object {
        const val TAG = "BibleNoteFollow"
    }
}
