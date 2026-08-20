package com.symmetricalpalmtree.notesprout.notebook

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.LinkClient
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The notebook's side of link objects (arc 7 / L1): the three core selection-toolbar actions with
 * their gating, the wrap / unwrap mutations, and the session chrome map. The picker flow (Link /
 * Edit taps) lands in L2 — until then those actions are inert; the debug ⋯ "Create test link"
 * drives [createFromSelection] with a fixed payload.
 *
 * **Wrap** ([createFromSelection]): union bounds + the underline clearance ([PageLink.unionBounds])
 * → one [LinkStore.create] (link row + children re-parented, one transaction) → one undoable
 * [NotebookUndo.Action.LinkCreated] → drain + reload the page (the same store → drain → reload
 * discipline every undo replay follows — no manual mirror surgery, writing order preserved).
 * **Unwrap** ([unlink]) mirrors it with [LinkStore.unlink] / `LinkUnlinked`. Both run under the
 * host's page-op lock (the host's `runPageOp` is handed in).
 *
 * **Chrome** is session state, never persisted (L0 Q4 — the heading precedent): [chromeOf] answers
 * from the map [ObjectRenderer] draws the underline by; [refreshChrome] asks the extension's
 * `chromeOf` one-shot for the current page's payloads (batched) and repaints pen-idle on a change;
 * with the extension missing the map empties and links render bare. A creation seeds the map
 * directly (the transient `LinkChoice.chrome` / the debug item's flag) so the underline is there
 * on the first composite frame without a round trip.
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
) {
    private var ref: ProviderRef? = null
    private var refreshGen = 0
    private var chromeGen = 0
    private val chrome = HashMap<String, Int>()

    /** True while a trusted `LINK_PROVIDER` extension is installed (drives Link / Edit gating). */
    val installed: Boolean get() = ref != null

    /** The session chrome flag for [linkId] — NONE while unknown (extension missing, fetch pending). */
    fun chromeOf(linkId: String): Int = chrome[linkId] ?: ExtensionContract.LINK_CHROME_NONE

    /** Re-discover the extension (IO); on a change the next toolbar show and chrome refresh see it. */
    fun refresh() {
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val found = try { ExtensionRegistry.linkProvider(activity) } catch (e: Exception) { Slog.d(TAG) { "discovery failed: ${e.message}" }; null }
            if (gen != refreshGen || activity.isFinishing || activity.isDestroyed) return@launch
            val changed = (found != null) != (ref != null)
            ref = found
            if (changed) refreshChrome()
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

    /**
     * Wrap the given selection into one link (one undoable step, page reloaded on completion). The
     * strokes come in writing order (the host filters `liveStrokes`); [payload] is opaque —
     * L1's caller is the debug item with a fixed grammar string, L2's is the picker's `LinkChoice`.
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
    }
}
