package com.symmetricalpalmtree.notesproutsn.templates

import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.SortRules

/**
 * **The three shelves** (arc 13 / G5) — Pinned, Recents and Search: flat, paginated, mutually
 * exclusive views that cut across the template folder tree.
 *
 * It sits *beside* [TemplateBrowser] rather than inside it, the way [TemplateTransfer] does, and
 * for the same kind of reason: the browser is about the tree — where you are standing, what is in
 * this folder, and what you may do to it. A shelf has no *where*. It ignores the folder entirely,
 * answers a different question ("what have I pinned / used / am looking for"), and the browser's
 * only business with it is asking which cards to draw and whether there is a path to show.
 *
 * Mutual exclusivity is not enforced anywhere — it is structural, because [mode] is one field.
 *
 * Nothing here persists. The browser opens in the tree, at the root, every time and in every host:
 * a shelf is a glance you take, not a place to live, and a picker that opened onto one would have
 * no visible way back to the paper the page is actually using. [query] survives in memory only, so
 * a second search can start from the first — a **name** never reaches device-local prefs.
 */
class TemplateShelfView(
    private val activity: AppCompatActivity,
    private val repo: IndexRepository,
    private val sortPrefs: SortPrefs,
    /** Re-list and redraw. The browser's own `reload`. */
    private val onChanged: () -> Unit,
) {

    enum class Mode { NONE, PINNED, RECENTS, SEARCH }

    var mode: Mode = Mode.NONE
        private set

    /** The query behind [Mode.SEARCH]; kept after the shelf closes so re-tapping Search offers it. */
    var query: String = ""
        private set

    val isOpen: Boolean get() = mode != Mode.NONE

    /**
     * Recently *applied* paper — device-local, ids only, written by the two hosts that turn a pick
     * into pixels ([TemplateRecents]), never by the browser. Read here to build the shelf and to
     * prune it.
     */
    private val recents = RecentsPrefs.templates(activity)

    // ── Switching ────────────────────────────────────────────────────────────

    /** Toggle a shelf: tapping Pinned while Pinned is up returns to the tree, so the same button
     *  is always the way out of what it opened. A no-op on the mode already showing. */
    fun toggle(next: Mode) {
        val target = if (mode == next) Mode.NONE else next
        if (mode == target) return
        mode = target
        Slog.d(TAG) { "shelf → $target" }
        onChanged()
    }

    /** Leave whatever shelf is up, and redraw. Safe to call in the tree. */
    fun close() {
        if (reset()) onChanged()
    }

    /**
     * Leave the shelf **without** redrawing, returning whether anything changed — for a caller that
     * is about to redraw anyway (the browser navigating into a folder). [close] would fire a second
     * concurrent listing over the same fields, which is a race for nothing.
     */
    fun reset(): Boolean {
        if (mode == Mode.NONE) return false
        mode = Mode.NONE
        Slog.d(TAG) { "shelf → NONE" }
        return true
    }

    /**
     * Ask for a query, in a dialog rather than a field in the chrome. `NewNotebookActivity` is
     * `adjustNothing` (G3 — it is a screen with a page on it), so a field inside the browser's own
     * bars would sit under the IME with no way to reach it; and on e-ink, filtering as you type is
     * a full repaint per keystroke.
     *
     * The last query comes back with it, selected: searching twice for nearly the same thing is the
     * common case, and re-typing it on the Supernote's on-screen keyboard is the expensive one.
     */
    fun openSearchDialog() {
        NameDialog.show(
            activity,
            titleRes = R.string.template_search_title,
            confirmRes = R.string.template_search_confirm,
            initial = query,
            hintRes = R.string.template_search_hint,
        ) { typed, dismiss ->
            // An empty query would open a shelf holding every sentinel and every row in the
            // library, which does not read as "you searched for nothing" — it reads as a result.
            // Its own words, not the naming dialog's: a query is not a name, and "that name won't
            // work" is an answer to a question the user did not ask.
            if (!FuzzyRank.isRunnable(typed)) {
                Dialogs.problem(
                    activity,
                    R.string.template_search_empty_title,
                    R.string.template_search_empty_body,
                )
                return@show
            }
            query = typed.trim()
            dismiss()
            // Assigned rather than toggled: re-searching while the search shelf is already up is
            // not a no-op, it is a different shelf with the same name.
            mode = Mode.SEARCH
            Slog.d(TAG) { "search shelf, ${query.length} chars" }
            onChanged()
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    /** The title that replaces the breadcrumbs. Meaningless in the tree; the browser does not ask. */
    fun title(): String = when (mode) {
        Mode.PINNED -> activity.getString(R.string.shelf_title_pinned_templates)
        Mode.RECENTS -> activity.getString(R.string.shelf_title_recent_templates)
        else -> activity.getString(R.string.shelf_title_search_templates, query)
    }

    /** What an empty shelf says. [Mode.NONE] answers with the folder tree's own empty state. */
    fun emptyTextRes(): Int = when (mode) {
        Mode.PINNED -> R.string.templates_pinned_empty
        Mode.RECENTS -> R.string.templates_recents_empty
        Mode.SEARCH -> R.string.templates_search_empty
        Mode.NONE -> R.string.templates_empty
    }

    // ── Contents ─────────────────────────────────────────────────────────────

    /**
     * The cards on the shelf now. [pinnedIds] is the browser's once-per-refresh read, passed in
     * rather than read again: the shelf and the badges must never be able to disagree.
     * Must not be called in [Mode.NONE] — the tree is the browser's own listing.
     */
    suspend fun cards(pinnedIds: Set<String>): List<TemplateCard> = when (mode) {
        Mode.PINNED -> pinnedCards(pinnedIds)
        Mode.RECENTS -> recentCards()
        Mode.SEARCH -> searchCards()
        Mode.NONE -> emptyList()
    }

    /**
     * The pinned shelf: the built-ins first in their fixed order, then the pinned rows **in the
     * screen's current sort** rather than in pin order. The membership edge does carry a
     * `sortOrder`, but making it the display order would hand the user a second, invisible
     * arrangement to reason about — the library's own call, and the same one here.
     */
    private suspend fun pinnedCards(pinnedIds: Set<String>): List<TemplateCard> {
        val alive = repo.aliveTemplates(TemplateShelves.rowIdsAmong(pinnedIds.toList()))
        val sorted = SortRules.sort(alive.values.toList(), sortPrefs.field, sortPrefs.order)
        return TemplateShelves.pinnedCards(pinnedIds, sorted, builtInLabels())
    }

    /**
     * The recents shelf: **stored order, never re-sorted** — a history that obeyed Name ↑ would
     * stop being a history. Reading it is also when the store is swept, so a template deleted on
     * another screen leaves for good rather than accumulating as a ghost. The sweep keeps every
     * built-in: a sentinel has no row, and pruning it would silently empty the shelf of the three
     * papers most likely to be on it.
     */
    private suspend fun recentCards(): List<TemplateCard> {
        val entries = recents.entries()
        val alive = repo.aliveTemplates(TemplateShelves.rowIdsAmong(entries.map { it.id }.distinct()))
        val order = TemplateShelves.recentIds(entries, alive.keys)
        recents.pruneDeleted(TemplateShelves.pruneable(alive.keys))
        val labels = builtInLabels()
        return order.mapNotNull { id ->
            val i = TemplateLibrary.BUILT_IN_KINDS.indexOfFirst { it.first == id }
            if (i >= 0) {
                val (sentinelId, kind) = TemplateLibrary.BUILT_IN_KINDS[i]
                TemplateCard.BuiltIn(sentinelId, labels[i], kind)
            } else {
                alive[id]?.let { TemplateCard.Static(it) }
            }
        }
    }

    /**
     * The search shelf: every template in the library and every sentinel, matched and **ranked
     * together** by relevance (arc 20 / Q1). Not the screen's sort — the sort control is GONE while
     * this shelf is up, because relevance *is* the order here and a live Sort would fight it.
     *
     * The database no longer filters: matching is fuzzy, which a `LIKE` cannot be, so the rows come
     * back whole (blob-free) and `TemplateShelves.searchCards` does the work with the same matcher
     * the library's own search uses.
     */
    private suspend fun searchCards(): List<TemplateCard> = TemplateShelves.searchCards(
        query,
        activity.getString(R.string.template_blank),
        builtInLabels(),
        repo.allTemplates(),
    )

    /** The built-in labels, in [TemplateLibrary.BUILT_IN_KINDS] order — a shelf composes its own
     *  sentinel cards, so it needs the words the Default folder would have used. */
    private fun builtInLabels(): List<String> = listOf(
        activity.getString(R.string.template_lined),
        activity.getString(R.string.template_dotted),
        activity.getString(R.string.template_grid),
    )

    // ── The recents store, for the browser's delete paths ────────────────────

    /**
     * Forget [ids] were ever used. The pin edge of a deleted row is scrubbed by the index itself;
     * a recents entry is in prefs and has to be removed by hand. [recentCards]'s own sweep would
     * catch it eventually, but "eventually" means "the next time Recents is opened", and until then
     * the store holds a live pointer at a dead row.
     */
    fun forget(ids: Collection<String>) = ids.forEach { recents.remove(it) }

    private companion object {
        const val TAG = "TemplateShelf"
    }
}
