package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Intent
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseCache
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.LinkTrail
import com.symmetricalpalmtree.notesproutsn.data.prefs.TrailCodec
import com.symmetricalpalmtree.notesproutsn.data.prefs.TrailEntry
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notebook screen's side of *following* a link (arc 6 / K4): a finger tap on a link goes
 * somewhere, and a Back — the swipe-up, or the system Back on a screen opened via a link — walks
 * the story home. Lives beside [NotebookActivity] like [LinkPickFlow] (the screen file is at its
 * documented size cap), and holds no state of its own beyond the one door.
 *
 * The division of labour:
 * - [LinkNav] decides *what kind* of hop a payload asks for — pure, JVM-tested, no database.
 * - This flow decides whether that hop is still *possible*, and it asks **before navigating**: an
 *   index row alive, and — for a page of another notebook — that the page row itself is live, via a
 *   one-shot read-only open ([foreignPageAlive]). Landing on the wrong page, or on a notebook's
 *   remembered page because the target died, would be a lie the user cannot see; a dialog is not.
 * - [LinkTrail] remembers where each hop came from. **Every successful follow pushes the origin**
 *   (this notebook + the displayed page) before navigating — including an in-notebook hop, so a
 *   page → page → page story walks back a page at a time.
 *
 * Two directions, two honesty rules that deliberately differ:
 * - A **follow** that cannot land explains itself — the dead-target dialog, whose positive button
 *   opens the picker prefilled so the user can retarget the link on the spot. The link row is never
 *   touched: a target that is gone today may be a target restored from a backup tomorrow.
 * - A **walk-back** that meets a dead entry **skips it silently** and keeps popping. The user asked
 *   to go back, not to be told about a page they deleted themselves; the trail is a convenience, and
 *   an exhausted one just means "nothing to go back to" (`onEmpty`).
 *
 * One door ([busy]) guards both entry points. A hop that *leaves* the screen keeps it set forever:
 * the seal → launch hand-off is asynchronous, and a second tap in that gap must stay harmless.
 *
 * No frame-silence exception is claimed here: every entry point is a finger gesture, and
 * [PageGestures] already gates those on pen idleness — nothing this flow shows can land under live
 * ink.
 */
class LinkFollowFlow(
    private val activity: AppCompatActivity,
    private val session: () -> NotebookSession,
    private val displayedPageId: () -> String,
    /** The displayed page's links, insertion order = z-order ascending (topmost last). */
    private val liveLinks: () -> Collection<PageLink>,
    private val alive: () -> Boolean,
    /** Hop within the open notebook — fire-and-forget under the host's page-op lock. */
    private val navigateToPage: (String) -> Unit,
    /** Seal this notebook, **then** launch — one live session per `.soil`, family-wide. */
    private val closeAndLaunch: (Intent) -> Unit,
    /** Open the picker prefilled with this link, from the dead-target dialog. */
    private val editLink: (PageLink) -> Unit,
) {

    private var busy = false

    /**
     * A finger tap at ([x], [y]) in paper-view coordinates. A tap that hits no link is not a link
     * gesture at all — it returns without taking the door, because bare taps on paper are constant.
     */
    fun followAt(x: Float, y: Float) {
        if (!alive() || busy) return
        // Topmost first: later rows draw over earlier ones, so the last match is what the user sees.
        val link = liveLinks().lastOrNull { it.bounds.contains(x, y) } ?: return
        busy = true
        activity.lifecycleScope.launch {
            when (val plan = LinkNav.planFollow(link.payload, session().notebookId)) {
                // Self-referential notebook target: nothing honest to do, and nothing to say.
                LinkNav.Follow.NoOp -> {
                    busy = false
                    Slog.d(TAG) { "follow: self-referential link ${link.id} — nothing to do" }
                }
                // Foreign or future payload — the content still renders, the follow explains itself.
                LinkNav.Follow.Dead -> {
                    busy = false
                    deadTarget(link, R.string.link_target_unreadable_body)
                }
                is LinkNav.Follow.SamePage -> {
                    if (plan.pageId == displayedPageId()) {
                        // A page targeting itself (foreign/hand-edited payload — our picker
                        // refuses to compose one): silent, like the notebook-self NoOp. Pushing
                        // would stack self-entries that eat real walk-back hops (K5 review).
                        Slog.d(TAG) { "follow: ${link.id} targets the displayed page — nothing to do" }
                    } else if (session().pages.none { it.id == plan.pageId }) {
                        deadTarget(link, R.string.link_target_page_gone_body)
                    } else {
                        pushOrigin()
                        navigateToPage(plan.pageId)
                        Slog.d(TAG) { "follow: ${link.id} → page ${plan.pageId} (same notebook)" }
                    }
                    busy = false
                }
                is LinkNav.Follow.OtherNotebook -> followOut(link, plan)
            }
        }
    }

    /**
     * Back / swipe-up: pop until something lands. Bounded by [TrailCodec.MAX_ENTRIES] — the same cap
     * the trail is stored under, so even a corrupt prefs blob costs a bounded number of reads rather
     * than a spin. [onEmpty] is what the caller does with "nowhere to go back to": the swipe-up
     * ignores it, the system Back on a via-link screen closes the notebook.
     */
    fun walkBack(onEmpty: () -> Unit) {
        if (!alive() || busy) return
        busy = true
        activity.lifecycleScope.launch {
            val trail = LinkTrail(activity)
            repeat(TrailCodec.MAX_ENTRIES) {
                val entry = trail.pop() ?: run {
                    busy = false
                    onEmpty()
                    return@launch
                }
                when (val plan = LinkNav.planBack(entry.notebookId, entry.pageId, session().notebookId)) {
                    is LinkNav.Back.SamePage ->
                        if (session().pages.any { it.id == plan.pageId }) {
                            navigateToPage(plan.pageId)
                            busy = false
                            Slog.d(TAG) { "back: → page ${plan.pageId} (same notebook)" }
                            return@launch
                        }
                    is LinkNav.Back.OtherNotebook -> {
                        val summary = notebookSummary(plan.notebookId)
                        // Arc 26 / U4: a walk-back into a NOTEBOOK-scope notebook is as deliberate
                        // an open as a follow, so it asks — a silent read would answer "locked",
                        // and this loop would skip a perfectly live notebook as though the page had
                        // been deleted. A cancelled prompt ends the walk and **puts the entry
                        // back**: "not now" must not cost the user their way home.
                        var typed: String? = null
                        if (summary != null && KeyScope.of(summary.keyScope) == KeyScope.NOTEBOOK) {
                            typed = NotebookPassphrasePrompt.ask(activity, plan.notebookId, summary.name)
                            if (typed == null) { trail.push(entry); busy = false; return@launch }
                        }
                        if (summary != null && foreignPageAlive(plan.notebookId, plan.pageId, typed)) {
                            if (!alive()) { busy = false; return@launch }
                            Slog.d(TAG) { "back: → ${plan.notebookId} page ${plan.pageId}" }
                            // One prompt per hop — the notebook screen's own open takes this.
                            typed?.let { PassphraseCache.storeOnce(plan.notebookId, it) }
                            leaveFor(plan.notebookId, summary.name, plan.pageId)   // busy stays set
                            return@launch
                        }
                    }
                }
                // A dead entry is skipped in silence — never a landing on the wrong page.
                Slog.d(TAG) { "back: skipping a dead trail entry" }
            }
            busy = false
            onEmpty()
        }
    }

    // ── Follow out ───────────────────────────────────────────────────────────

    /** Another notebook: index row alive, then (for a page target) the page row itself. */
    private suspend fun followOut(link: PageLink, plan: LinkNav.Follow.OtherNotebook) {
        val summary = notebookSummary(plan.notebookId)
        if (summary == null) {
            busy = false
            deadTarget(link, R.string.link_target_notebook_gone_body)
            return
        }
        // Arc 26 / U4: a NOTEBOOK-scope target is asked for on every deliberate open, and a follow
        // is one (decision 12). Cancelled = the tap did nothing, quietly — no dialog: the person
        // just said no, which is not a dead target.
        var typed: String? = null
        if (KeyScope.of(summary.keyScope) == KeyScope.NOTEBOOK) {
            typed = NotebookPassphrasePrompt.ask(activity, plan.notebookId, summary.name)
            if (typed == null) { busy = false; return }
        }
        // The pre-check carries the passphrase itself rather than waiting for the prompt's raw-key
        // warm to land (~9 s on the device); a GLOBAL target keeps the resolver's own answer.
        val alivePage = plan.pageId == null || foreignPageAlive(plan.notebookId, plan.pageId, typed)
        if (!alivePage) {
            busy = false
            deadTarget(link, R.string.link_target_page_gone_body)
            return
        }
        if (!alive()) { busy = false; return }
        pushOrigin()
        Slog.d(TAG) { "follow: ${link.id} → ${plan.notebookId} page ${plan.pageId ?: "(remembered)"}" }
        // One prompt per follow: the notebook screen prompts on its own open too, so park the
        // verified passphrase for that single open to take silently. Single-use and RAM-only —
        // every later open of that notebook asks again.
        typed?.let { PassphraseCache.storeOnce(plan.notebookId, it) }
        leaveFor(plan.notebookId, summary.name, plan.pageId)   // busy stays set — we are leaving
    }

    /**
     * Raise the "Opening…" box, and only once its frame is on the glass hand off to the host's
     * seal-then-launch. [busy] is deliberately **not** released: this screen is on its way out.
     */
    private fun leaveFor(notebookId: String, name: String, pageId: String?) {
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.showThen(activity) {
            closeAndLaunch(
                NotebookActivity.intent(activity, notebookId, name, viaLink = true, initialPageId = pageId)
            )
        }
    }

    /** Where the user is standing right now — pushed before every successful hop. */
    private fun pushOrigin() {
        LinkTrail(activity).push(TrailEntry(session().notebookId, displayedPageId()))
    }

    // ── Existence checks ─────────────────────────────────────────────────────

    /** The index row, only if it is an alive **notebook** (a payload is untrusted file input, and a
     *  folder id would otherwise launch a notebook screen onto nothing). */
    private suspend fun notebookSummary(notebookId: String) =
        withContext(Dispatchers.IO) { IndexRepository().alive(notebookId) }
            ?.takeIf { it.type == ObjectType.NOTEBOOK }

    /**
     * One-shot **read-only** pre-check of a page row in another notebook's `.soil`: live, still a
     * page, and still parented to that notebook — through [SoilDatabase.readOnce], the single
     * owner of the open → read → always-seal ritual. Any failure at all answers false: the follow
     * then explains rather than guessing.
     *
     * Only ever called for a genuinely foreign notebook — [LinkNav] routes every current-notebook
     * target to `SamePage`/`NoOp` — so this can never be a second connection to the live session's
     * own file.
     *
     * [typed] is the passphrase the follow just collected for a `NOTEBOOK`-scope target (arc 26 /
     * U4): the read carries it rather than resolving, which would answer `NeedsPrompt` until the
     * prompt's raw-key warm finishes and turn a live page into a dead one.
     */
    private suspend fun foreignPageAlive(notebookId: String, pageId: String, typed: String?): Boolean {
        val ctx = activity.applicationContext
        val check: suspend (SoilDao) -> Boolean = { dao ->
            val row = dao.byId(pageId)
            row != null &&
                row.deletedAt == null &&
                row.type == SoilSchema.TYPE_PAGE &&
                row.parentId == notebookId
        }
        val answer =
            if (typed == null) SoilDatabase.readOnce(ctx, notebookId, check)
            else SoilDatabase.readOnce(ctx, notebookId, KeyResolver.Resolved.Passphrases(typed), check)
        return answer ?: false
    }

    // ── The dead-target dialog ───────────────────────────────────────────────

    /**
     * Why the tap did nothing, and the one thing worth offering: retarget the link. Explicitly a
     * dialog rather than a toast (the e-ink rule), and explicitly two buttons — [Dialogs.problem] is
     * OK-only. The link row is left exactly as it is unless the user chooses to edit it.
     */
    private fun deadTarget(link: PageLink, @StringRes bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "follow: ${link.id} has no reachable target" }
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.link_target_gone_title)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.link_edit_action) { _, _ -> editLink(link) }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    private companion object {
        const val TAG = "LinkFollowFlow"
    }
}
