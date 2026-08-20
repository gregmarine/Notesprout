package com.symmetricalpalmtree.notesprout.notebook

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.LinkCatalogSource
import com.symmetricalpalmtree.notesprout.extension.LinkChoice
import com.symmetricalpalmtree.notesprout.extension.LinkClient
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.TrailEntry
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.data.soilFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The notebook's side of link objects (arc 7): the three core selection-toolbar actions with their
 * gating, the wrap / unwrap mutations, the session chrome map, and — since L2 — the **pick flow**
 * behind the Link / Edit taps: the arc-6 held-bind recipe over [LinkClient] ([beginCreate] /
 * [beginEdit] → `openPick` → the extension's picker through [launcher] → [onResult] drains the
 * [LinkChoice] and applies it). The debug ⋯ "Create test link" still drives [createFromSelection]
 * directly with a fixed payload (removed in L5). Since L4 it also owns **follow + the trail**:
 * [followAt] (finger tap → resolve → [LinkNav.planFollow] → validate → push → navigate or seal +
 * relaunch via [openNotebook]), [walkBack] (swipe-up / via-link Back — pop, skip dead silently,
 * navigate), and the fresh-open [requestTrailClear].
 *
 * **Wrap** ([createFromSelection]): union bounds + the underline clearance ([PageLink.unionBounds])
 * → one [LinkStore.create] (link row + children re-parented, one transaction) → one undoable
 * [NotebookUndo.Action.LinkCreated] → drain + reload the page (the same store → drain → reload
 * discipline every undo replay follows — no manual mirror surgery, writing order preserved).
 * **Unwrap** ([unlink]) mirrors it with [LinkStore.unlink] / `LinkUnlinked`. **Edit** ([applyEdit],
 * L2) patches only the payload — one [LinkStore.updatePayload] + `LinkEdited`; the wrapped content
 * is untouched and the underline repaints from the re-seeded chrome map, no composite rebuild.
 * All three run under the host's page-op lock (the host's `runPageOp` is handed in).
 *
 * **Chrome** is session state, never persisted (L0 Q4 — the heading precedent): [chromeOf] answers
 * from the map [ObjectRenderer] draws the underline by; [refreshChrome] asks the extension's
 * `chromeOf` one-shot for the current page's payloads (batched) and repaints pen-idle on a change;
 * with the extension missing the map empties and links render bare. A creation seeds the map
 * directly (the transient `LinkChoice.chrome` / the debug item's flag) so the underline is there
 * on the first composite frame without a round trip.
 *
 * **The picker's catalog** sees the current notebook only through the [LinkCatalogSource] built in
 * [buildSource]: page labels composed host-side ("Page n", plus the outline heading where the
 * Contents has one — L2 Q2, via [LinkPickerLabels]) and the current page excluded (L2 Q1 — the
 * exclusion lives beside the numbering so "Page n" stays true to position).
 */
class LinkFlow(
    private val activity: AppCompatActivity,
    private val alive: () -> Boolean,
    private val session: () -> NotebookSession,
    private val liveStrokes: () -> LinkedHashMap<String, Stroke>,
    private val liveObjects: () -> LinkedHashMap<String, PageObject>,
    private val liveLinks: () -> LinkedHashMap<String, PageLink>,
    private val undo: UndoRedoStack<NotebookUndo.Action>,
    /** The host's page-op runner: serialised with flips / undo, dropped while closing. */
    private val runPageOp: (suspend () -> Unit) -> Unit,
    /** Drain + reload the current page (the host's `refreshToPage(currentPage.id)`). */
    private val refreshCurrentPage: suspend () -> Unit,
    private val whenPenIdle: (() -> Unit) -> Unit,
    /** Repaint the committed layer (chrome map changed — the underline is drawn live). */
    private val notifyContentChanged: () -> Unit,
    /** The loaded object providers (the picker's page labels come from their outline — L2 Q2). */
    private val providers: () -> ObjectProviders,
    /** EPD chrome-release before a dialog / the picker launch ([PaperView.releaseRender]). */
    private val releaseRender: () -> Unit,
    /** The picker created a page in THIS notebook (arc 7 / L3): the host refreshes its page
     *  indicator + Contents availability. Called on the picker's return, after the undo clear. */
    private val onPagesChanged: () -> Unit = {},
    /** Navigate the open notebook to a page index — called inside [runPageOp] only (arc 7 / L4). */
    private val navigateToPage: suspend (Int) -> Unit = {},
    /** Seal + relaunch into another notebook with `EXTRA_VIA_LINK` (+ the initial page), arc 7 / L4. */
    private val openNotebook: (notebookId: String, name: String, initialPageId: String?) -> Unit = { _, _, _ -> },
) {
    private var ref: ProviderRef? = null
    private var refreshGen = 0
    private var chromeGen = 0
    private val chrome = HashMap<String, Int>()
    private val repo = IndexRepository()

    /** What the open pick showing is for — applied when the picker's result drains. */
    private sealed class Pending {
        class Create(val strokes: List<Stroke>, val objects: List<PageObject>) : Pending()
        class Edit(val link: PageLink) : Pending()
    }

    private var client: LinkClient? = null
    private var pending: Pending? = null
    private var busy = false

    /** A follow / walk-back in flight (arc 7 / L4) — blocks a second tap through the seal. */
    private var navBusy = false
    /** A fresh open (no `EXTRA_VIA_LINK`) clears the persisted trail once a provider is
     *  discovered; kept set until a clear succeeds so a failed one-shot retries at next resume. */
    private var trailClearPending = false

    /** Set when a pick showing's `createPage` inserted into the live session (arc 7 / L3): on the
     *  picker's return — ANY result (Q4) — the undo stack is cleared (older `Structural` snapshots
     *  predate the new page; replaying one would soft-delete it) and the host told to refresh. */
    private var pagesChanged = false

    /** Registered at construction — the host builds the flow in `onCreate`. Launch-for-a-result is
     *  the only way in: the extension's caller check needs `callingPackage` (rule 25). */
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onResult(it.resultCode) }

    /** True while a trusted `LINK_PROVIDER` extension is installed (drives Link / Edit gating). */
    val installed: Boolean get() = ref != null

    /** The session chrome flag for [linkId] — NONE while unknown (extension missing, fetch pending). */
    fun chromeOf(linkId: String): Int = chrome[linkId] ?: ExtensionContract.LINK_CHROME_NONE

    /** A fresh notebook open (no `EXTRA_VIA_LINK`): the trail is stale history — clear it when
     *  the extension turns up (L4; the original's `EXTRA_VIA_LINK`-absent rule). */
    fun requestTrailClear() { trailClearPending = true }

    /** Re-discover the extension (IO); on a change the next toolbar show and chrome refresh see it. */
    fun refresh() {
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val found = try { ExtensionRegistry.linkProvider(activity) } catch (e: Exception) { Slog.d(TAG) { "discovery failed: ${e.message}" }; null }
            if (gen != refreshGen || activity.isFinishing || activity.isDestroyed) return@launch
            val changed = (found != null) != (ref != null)
            ref = found
            if (changed) refreshChrome()
            if (found != null && trailClearPending) {
                try {
                    LinkClient(activity, found).clearTrail()
                    trailClearPending = false
                } catch (e: Exception) { Slog.d(TAG) { "clearTrail failed: ${e.message}" } }
            }
        }
    }

    /** The core actions for this selection [shape] (appended after Delete / the pad's action):
     *  "Link" for every shape while the extension is installed and the selection holds no link
     *  (no link-inside-link); "Edit" (extension-gated) + "Unlink" (structural — never gated) on
     *  exactly one link. */
    fun coreActions(shape: SelectionActions.Shape, containsLink: Boolean): List<ToolbarAction> {
        val out = ArrayList<ToolbarAction>(3)
        if (shape is SelectionActions.Shape.OneLink) {
            if (installed) out += action(SelectionActions.CORE_LINK_EDIT_ID, R.string.link_edit_label, R.drawable.ic_edit, R.string.link_edit_hint)
            out += action(SelectionActions.CORE_LINK_UNLINK_ID, R.string.link_unlink_label, R.drawable.ic_link_off, R.string.link_unlink_hint)
        } else if (installed && !containsLink) {
            out += action(SelectionActions.CORE_LINK_ID, R.string.link_action_label, R.drawable.ic_link, R.string.link_action_hint)
        }
        return out
    }

    private fun action(id: String, label: Int, icon: Int, hint: Int) =
        ToolbarAction(id, activity.getString(label), icon, activity.getString(hint), ActionApplies.ALL, 0)

    // ── The pick flow (arc 7 / L2) ────────────────────────────────────────────

    /** The "Link" tap: open the picker for the lassoed [strokes] + [objects] (captured now — the
     *  result applies to exactly this selection). Guards run before any bind; the page-cap refusal
     *  is a dialog here (the real UI), unlike [createFromSelection]'s race-window re-check. */
    fun beginCreate(strokes: List<Stroke>, objects: List<PageObject>) {
        if (busy || !alive()) return
        val r = ref ?: return
        if (strokes.isEmpty() && objects.isEmpty()) return
        releaseRender()
        if (liveLinks().size >= ExtensionContract.MAX_OBJECTS_PER_PAGE) {
            Dialogs.problem(activity, R.string.links_problem_title, R.string.links_page_full)
            return
        }
        launchPick(r, editPayload = null, Pending.Create(strokes, objects))
    }

    /** The "Edit" tap on a selected link: reopen the picker pre-populated; only the payload changes. */
    fun beginEdit(link: PageLink) {
        if (busy || !alive()) return
        val r = ref ?: return
        if (liveLinks()[link.id] == null) return
        releaseRender()
        launchPick(r, editPayload = link.payload, Pending.Edit(link))
    }

    /** Hold the showing ([LinkClient.openPick]) and launch the picker; every failure releases the
     *  client and says why ([Dialogs.problem] — never a toast), then re-discovers (a bind that fails
     *  because the package was disabled meanwhile should retract Link / Edit now, not at resume). */
    private fun launchPick(r: ProviderRef, editPayload: String?, p: Pending) {
        busy = true
        val c = LinkClient(activity, r)
        client = c
        pending = p
        activity.lifecycleScope.launch {
            val intent = c.openPick(buildSource(), editPayload)
            if (intent == null || !alive() || activity.isFinishing || activity.isDestroyed) {
                client = null; pending = null; busy = false
                if (intent != null) c.finish()
                else if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.links_problem_title, R.string.links_picker_gone)
                    refresh()
                }
                return@launch
            }
            try {
                launcher.launch(intent)
            } catch (e: Exception) {   // ActivityNotFound / SecurityException: the screen vanished between the bind and the launch
                Slog.d(TAG) { "launch failed: ${e.javaClass.simpleName}: ${e.message}" }
                client = null; pending = null; busy = false
                c.finish()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.links_problem_title, R.string.links_picker_gone)
                    refresh()
                }
            }
        }
    }

    /** The picker came back: drain the choice on the still-held bind ([LinkClient.takeChoice] tears
     *  the showing down in its own `finally`), then apply it to what the showing was for. */
    private fun onResult(resultCode: Int) {
        Slog.d(TAG) { "picker result $resultCode" }
        val c = client
        val p = pending
        if (c == null) {
            // The host process was killed and this screen recreated while the picker was up: the
            // launcher survives, the client (and the captured selection) did not. A picked choice
            // has nowhere to land — say so rather than drop it silently.
            if (resultCode == ExtensionContract.RESULT_LINK_PICKED && !activity.isFinishing && !activity.isDestroyed) {
                Dialogs.problem(activity, R.string.links_problem_title, R.string.links_result_lost)
            }
            return
        }
        activity.lifecycleScope.launch {
            val choice = try {
                c.takeChoice()
            } finally {
                if (client === c) { client = null; pending = null; busy = false }
            }
            // A page created in this notebook survives ANY result (L3 Q4 — creation is an explicit
            // act, not part of the pick): clear the undo stack (its Structural snapshots predate the
            // page) and refresh the host's page indicator + Contents before the choice is applied.
            if (pagesChanged) {
                pagesChanged = false
                undo.clear()
                if (alive()) onPagesChanged()
            }
            if (choice == null) {
                // Cancelled is silent; a parked result that wouldn't drain (dead bind, malformed
                // LinkChoice → the unmarshal rejected it) is an honest dialog.
                if (resultCode == ExtensionContract.RESULT_LINK_PICKED && !activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.links_problem_title, R.string.links_choice_invalid)
                }
                return@launch
            }
            if (!alive()) return@launch
            when (p) {
                is Pending.Create -> createFromSelection(p.strokes, p.objects, choice.payload, choice.chrome)
                is Pending.Edit -> applyEdit(p.link, choice)
                null -> Unit
            }
        }
    }

    /** The host's `onDestroy`: an open client is finished on a scope that outlives the screen. */
    fun close() {
        val c = client ?: return
        client = null
        pending = null
        busy = false
        appScope.launch { withContext(NonCancellable) { c.finish() } }
    }

    /**
     * The catalog lens for one pick showing: the outline headings are gathered **once, now** (IO —
     * best-effort, plain "Page n" on any failure) and the source's callback composes labels from the
     * live page list on the binder thread. The current page is excluded with the numbering kept true
     * to position (`mapIndexedNotNull` over the full list).
     */
    private suspend fun buildSource(): LinkCatalogSource {
        val s = session()
        val headings = LinkPickerLabels.headings(activity, s, providers())
        val currentPageId = s.currentPage.id
        return LinkCatalogSource(
            s.notebookId,
            currentPages = {
                if (!alive()) null
                else session().pages.mapIndexedNotNull { i, page ->
                    if (page.id == currentPageId) null
                    else page.id to LinkPickerLabels.compose(activity, i + 1, headings[page.id])
                }
            },
            createPage = { anchor, before -> createPageBlocking(anchor, before) },
        )
    }

    /**
     * The binder-thread bridge into the host's page-op lock (arc 7 / L3): schedule the insert
     * through [runPageOp] and block for its outcome. `runPageOp` silently drops ops while the
     * screen is closing — the timeout turns a dropped (or wedged) op into an
     * `IllegalStateException` the catalog wrapper reports honestly instead of hanging the picker.
     */
    private fun createPageBlocking(anchorPageId: String?, before: Boolean): String {
        if (!alive()) throw IllegalStateException("notebook not ready")
        val outcome = CompletableDeferred<Result<String>>()
        runPageOp {
            outcome.complete(runCatching {
                val id = session().insertPageAt(anchorPageId, before)
                pagesChanged = true
                id
            })
        }
        return runBlocking { withTimeout(CREATE_PAGE_TIMEOUT_MS) { outcome.await() } }.getOrThrow()
    }

    // ── Follow + the trail (arc 7 / L4) ───────────────────────────────────────

    /**
     * The finger tap (escrowed by [PageGestures]): topmost link under the point by z-order
     * ([liveLinks] is insertion-ordered ascending), else nothing. No extension but a link hit →
     * the honest `links_required` dialog (the plan's uninstalled consequence). The follow itself:
     * resolve → classify ([LinkNav.planFollow]) → validate foreign ids (index alive + a read-only
     * page-row check) → push the origin (Q3 — every follow, best-effort) → navigate.
     */
    fun followAt(x: Float, y: Float) {
        if (navBusy || busy || !alive()) return
        val link = liveLinks().values.lastOrNull { it.bounds.contains(x, y) } ?: return
        val r = ref
        if (r == null) { problem(R.string.links_required); return }
        navBusy = true
        activity.lifecycleScope.launch {
            try { follow(r, link) } finally { navBusy = false }
        }
    }

    private suspend fun follow(r: ProviderRef, link: PageLink) {
        val t0 = System.currentTimeMillis()
        val dest = try {
            LinkClient(activity, r).resolve(link.payload)
        } catch (e: Exception) {
            Slog.d(TAG) { "follow: resolve failed ${e.message}" }
            problem(R.string.links_picker_gone)
            return
        }
        Slog.d(TAG) { "follow: tap→resolve ${System.currentTimeMillis() - t0} ms" }   // Q4 metric — warm-bind decided on these
        if (dest == null || !alive()) { if (dest == null) problem(R.string.links_target_gone); return }
        when (val plan = LinkNav.planFollow(dest.kind, dest.notebookId, dest.pageId, session().notebookId, session().pages.map { it.id })) {
            is LinkNav.Plan.SamePage -> {
                pushTrail(r)
                runPageOp {
                    val idx = session().pages.indexOfFirst { it.id == plan.pageId }
                    if (idx >= 0) navigateToPage(idx)   // re-looked-up under the lock; gone meanwhile = silent
                }
            }
            is LinkNav.Plan.OtherNotebook -> {
                val row = withContext(Dispatchers.IO) { runCatching { repo.alive(plan.notebookId) }.getOrNull() }
                if (row == null || row.type != ObjectType.NOTEBOOK) { problem(R.string.links_target_gone); return }
                if (plan.initialPageId != null && !foreignPageAlive(plan.notebookId, plan.initialPageId)) {
                    problem(R.string.links_target_gone); return
                }
                pushTrail(r)
                if (alive()) openNotebook(row.id, row.name, plan.initialPageId)
            }
            LinkNav.Plan.Dead -> problem(R.string.links_target_gone)
            LinkNav.Plan.NoOp -> Slog.d(TAG) { "follow: self target, no-op" }
        }
    }

    /**
     * Swipe-up, and the Back button in a via-link notebook: pop entries until a live one navigates,
     * skipping dead ones silently (Q2), capped at `MAX_TRAIL_ENTRIES` pops; an empty trail (or the
     * extension gone / not answering) → [onEmpty] — the swipe passes nothing (silent), Back passes
     * the normal close-to-library.
     */
    fun walkBack(onEmpty: () -> Unit = {}) {
        if (navBusy || busy || !alive()) return
        val r = ref ?: run { onEmpty(); return }
        navBusy = true
        activity.lifecycleScope.launch {
            try {
                val c = LinkClient(activity, r)
                repeat(ExtensionContract.MAX_TRAIL_ENTRIES) {
                    val entry = try { c.popTrail() } catch (e: Exception) { Slog.d(TAG) { "popTrail failed: ${e.message}" }; null }
                    if (entry == null || !alive()) { if (entry == null) onEmpty(); return@launch }
                    when (val step = LinkNav.planBack(entry.notebookId, entry.pageId, session().notebookId, session().pages.map { it.id })) {
                        is LinkNav.BackStep.SamePage -> {
                            runPageOp {
                                val idx = session().pages.indexOfFirst { it.id == step.pageId }
                                if (idx >= 0) navigateToPage(idx)
                            }
                            return@launch
                        }
                        is LinkNav.BackStep.OtherNotebook -> {
                            val row = withContext(Dispatchers.IO) { runCatching { repo.alive(step.notebookId) }.getOrNull() }
                            if (row != null && row.type == ObjectType.NOTEBOOK && foreignPageAlive(step.notebookId, step.pageId)) {
                                if (alive()) openNotebook(row.id, row.name, step.pageId)
                                return@launch
                            }   // dead notebook or dead page — skip to the next pop (Q2)
                        }
                        LinkNav.BackStep.Skip -> Unit   // own page gone — skip silently (Q2)
                    }
                }
                onEmpty()
            } finally { navBusy = false }
        }
    }

    /** Push the origin (this notebook, the page under the tap) — best-effort: a failed push never
     *  blocks the follow the user asked for (logged; the walk just has one hop fewer). */
    private suspend fun pushTrail(r: ProviderRef) {
        val entry = TrailEntry(session().notebookId, session().currentPage.id)
        try { LinkClient(activity, r).pushTrail(entry) } catch (e: Exception) { Slog.d(TAG) { "pushTrail failed: ${e.message}" } }
    }

    /** Alive-page check of a notebook that is NOT the open session's — a read-only open sealed in
     *  `finally` (the `LinkCatalogBinder.foreignPageIds` shape). False on any failure: the caller
     *  treats it as dead (honest dialog on a follow, silent skip on a walk-back). */
    private suspend fun foreignPageAlive(notebookId: String, pageId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pass = KeySession.get() ?: return@withContext false
            val file = soilFile(activity.applicationContext, notebookId)
            if (!file.exists() || file.length() == 0L) return@withContext false
            val db = SoilDatabase.open(activity.applicationContext, notebookId, file, pass)
            try {
                val nb = db.dao().notebookRow() ?: return@withContext false
                db.dao().childrenOfType(nb.id, SoilSchema.TYPE_PAGE).any { it.id == pageId }
            } finally {
                db.seal(file)
            }
        } catch (e: Exception) {
            Slog.d(TAG) { "foreignPageAlive failed: ${e.message}" }
            false
        }
    }

    private fun problem(message: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        releaseRender()
        Dialogs.problem(activity, R.string.links_problem_title, message)
    }

    // ── The mutations ─────────────────────────────────────────────────────────

    /**
     * Wrap the given selection into one link (one undoable step, page reloaded on completion). The
     * strokes come in writing order (the host filters `liveStrokes`); [payload] is opaque — the
     * picker's `LinkChoice` (L2) or the debug item's fixed grammar string (L1, removed in L5).
     * Refused (logged, nothing changes) when empty, when the selection already holds a link
     * (guarded by the caller's gating too), or when the page is at the link cap.
     */
    fun createFromSelection(strokes: List<Stroke>, objects: List<PageObject>, payload: String, chromeFlag: Int) {
        val r = ref ?: return
        if (strokes.isEmpty() && objects.isEmpty()) return
        if (liveLinks().size >= ExtensionContract.MAX_OBJECTS_PER_PAGE) { Slog.d(TAG) { "create refused: page at the link cap" }; return }
        val clearance = 2f * activity.resources.displayMetrics.densityDpi / 160f
        val bounds = PageLink.unionBounds(strokes, objects, clearance) ?: return
        val link = PageLink(
            id = UUID.randomUUID().toString(),
            providerIdentity = ExtensionContract.objectIdentity(r.packageName, "link"),
            payload = payload,
            x = bounds.left, y = bounds.top, width = bounds.width, height = bounds.height,
            order = (liveLinks().values.maxOfOrNull { it.order } ?: -1) + 1,
            strokes = strokes, objects = objects,
        )
        runPageOp {
            val pageId = session().currentPage.id
            val stillLive = strokes.all { it.id in liveStrokes() } && objects.all { it.id in liveObjects() }
            if (!stillLive) { Slog.d(TAG) { "create dropped: selection content gone" }; return@runPageOp }
            session().linkStore.create(pageId, link)
            undo.record(NotebookUndo.Action.LinkCreated(pageId, link))
            chrome[link.id] = chromeFlag
            refreshCurrentPage()
            Slog.d(TAG) { "created link ${link.id} wrapping ${strokes.size} stroke(s) + ${objects.size} object(s)" }
        }
    }

    /** Unwrap [link] (one undoable step, page reloaded — the content comes back as page children). */
    fun unlink(link: PageLink) {
        runPageOp {
            val pageId = session().currentPage.id
            if (liveLinks()[link.id] == null) return@runPageOp
            session().linkStore.unlink(pageId, link)
            undo.record(NotebookUndo.Action.LinkUnlinked(pageId, link))
            chrome.remove(link.id)
            refreshCurrentPage()
            Slog.d(TAG) { "unlinked ${link.id} releasing ${link.childIds.size}" }
        }
    }

    /** The Edit result: patch the payload (wrapped content untouched — one [LinkStore.updatePayload],
     *  one `LinkEdited`), re-seed the chrome map and repaint the underline pen-idle. A choice equal to
     *  the current payload is a no-op (chrome rides inside the payload, so equality covers both). */
    private fun applyEdit(link: PageLink, choice: LinkChoice) {
        runPageOp {
            val live = liveLinks()[link.id] ?: return@runPageOp
            if (choice.payload == live.payload) { Slog.d(TAG) { "edit: unchanged" }; return@runPageOp }
            val pageId = session().currentPage.id
            session().linkStore.updatePayload(live.id, choice.payload)
            liveLinks()[live.id] = live.copy(payload = choice.payload)
            undo.record(NotebookUndo.Action.LinkEdited(pageId, live.id, live.payload, choice.payload))
            chrome[live.id] = choice.chrome
            whenPenIdle { if (alive()) notifyContentChanged() }
            Slog.d(TAG) { "edited ${live.id}" }
        }
    }

    /**
     * Ask the extension for the current page's chrome flags (one batched one-shot) and repaint
     * pen-idle on a change. No extension → the map empties (links render bare). A failed call keeps
     * the map as-is — a stale underline beats a flickering one; the next load asks again.
     */
    fun refreshChrome() {
        val gen = ++chromeGen
        val links = liveLinks().values.toList()
        val r = ref
        activity.lifecycleScope.launch {
            val fresh: Map<String, Int>? = if (r == null || links.isEmpty()) {
                emptyMap()
            } else {
                try {
                    val flags = LinkClient(activity, r).chromeOf(links.map { it.payload })
                    links.indices.associate { links[it].id to flags[it] }
                } catch (e: Exception) {
                    Slog.d(TAG) { "chromeOf failed: ${e.message}" }
                    null
                }
            }
            if (fresh == null || gen != chromeGen || !alive()) return@launch
            if (fresh != chrome) {
                chrome.clear()
                chrome.putAll(fresh)
                whenPenIdle { if (alive()) notifyContentChanged() }
            }
        }
    }

    private companion object {
        const val TAG = "LinkFlow"
        /** Outlives the Activity so `endPick` → unbind → revoke always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        /** How long the binder thread waits on the page-op lock for a picker page insert (L3). */
        const val CREATE_PAGE_TIMEOUT_MS = 10_000L
    }
}
