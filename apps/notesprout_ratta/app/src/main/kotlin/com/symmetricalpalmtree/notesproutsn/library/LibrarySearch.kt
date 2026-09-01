package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType

/**
 * The library's **search shelf** (arc 20 / Q1) — the query, the dialog that asks for it, and the
 * cards it produces. It sits beside [LibraryActivity] the way [com.symmetricalpalmtree.notesproutsn.templates.TemplateShelfView]
 * sits beside the template browser, and for the same reason: the library is about *where you are
 * standing*, and a search has no where.
 *
 * Four decisions are worth reading before changing anything here:
 *
 *  - **A dialog asks for the query, and the shelf's title is the answer** — the template browser's
 *    shape, adopted here on the user's call so the two searches in this app are one interaction.
 *    An inline field in the top bar was built first and replaced: this is the shape.
 *  - **The whole library, always.** Folders and notebooks from anywhere in the tree, whatever folder
 *    the user is in. A search scoped to the current folder answers "no" for a notebook two folders
 *    over, which is the one question a search exists to answer.
 *  - **It runs when the dialog is accepted, never as you type.** Filtering per keystroke is a full
 *    card page repaint — covers included — on an e-ink panel.
 *  - **The query lives in memory only.** Prefs hold ids and enum names, never a display name, and a
 *    typed query is a name; it also means a relaunch never reopens a stale shelf. It *does* survive
 *    within the process, and comes back in the dialog select-all'd, because retyping on the
 *    Supernote's on-screen keyboard is the expensive part.
 */
class LibrarySearch(
    private val activity: Activity,
    private val repo: IndexRepository,
    /** A query was accepted: show the shelf (entering the mode if needed) and re-list. */
    private val onQueryRun: () -> Unit,
) {

    /** What is being searched for. Empty until the first accepted query; never written to disk. */
    var query: String = ""
        private set

    // ── Asking ───────────────────────────────────────────────────────────────

    /**
     * Ask for a query. The last one comes back in the field, selected, ready to be typed over —
     * searching twice for nearly the same thing is the common case, and re-typing it on the
     * Supernote's on-screen keyboard is the expensive one.
     *
     * Cancelling leaves the library exactly as it was: the shelf is only ever entered by an
     * accepted query, so there is no such thing as a search shelf with nothing on it.
     */
    fun openDialog() {
        NameDialog.show(
            activity,
            titleRes = R.string.library_search_title,
            confirmRes = R.string.library_search_confirm,
            initial = query,
            hintRes = R.string.library_search_hint,
        ) { typed, dismiss ->
            // A blank query would open a shelf holding the entire library, which does not read as
            // "you searched for nothing" — it reads as a result. Its own words, not the naming
            // dialog's: a query is not a name, and "that name won't work" answers a question the
            // user did not ask.
            if (!FuzzyRank.isRunnable(typed)) {
                Dialogs.problem(
                    activity,
                    R.string.library_search_empty_title,
                    R.string.library_search_empty_body,
                )
                return@show
            }
            query = typed.trim()
            dismiss()
            Slog.d(TAG) { "search shelf, ${query.length} chars" }
            onQueryRun()
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    /** The title that replaces the breadcrumbs: the query itself, quoted. Meaningless outside the
     *  search shelf; the library does not ask. */
    fun title(): String = activity.getString(R.string.mode_title_search, query)

    // ── The shelf ────────────────────────────────────────────────────────────

    /**
     * The cards, ranked by [SearchAssembly]. Two blob-free whole-library listings per run — names
     * and dates, no covers; the library fetches the covers for the visible page only, exactly as it
     * does for every other shelf.
     *
     * Every card's second line is its **parent folder's name** (the Recents shelf's call — on a flat
     * shelf "where is it" beats "when"), resolved from the folder listing that is already in hand,
     * so the subtitles cost no extra reads at all. Folders get one too: this shelf is flat, and
     * folder names are only unique per parent, so two called "Notes" would be one card twice.
     */
    suspend fun cards(pinnedIds: Set<String>): List<CardItem> {
        // Unreachable by the dialog, which refuses a blank query — a safety net, not a state.
        if (!FuzzyRank.isRunnable(query)) return emptyList()
        val folders = repo.allFolders()
        val notebooks = repo.allNotebooks()
        val folderNames = folders.associate { it.id to it.name }
        val root = activity.getString(R.string.recents_parent_root)
        return SearchAssembly.rank(folders, notebooks, query).map { s ->
            val where = parentLabel(s, folderNames, root)
            if (s.type == ObjectType.FOLDER) CardItem.Folder(s, subtitle = where)
            else CardItem.Notebook(s, pinned = s.id in pinnedIds, subtitle = where)
        }
    }

    /** The folder a card lives in. Unknown (a parent that is not an alive folder) reads as the
     *  root, the same fallback the Recents shelf makes — a card must always say where it is. */
    private fun parentLabel(s: ObjectSummary, folderNames: Map<String, String>, root: String): String =
        s.parentId?.let { folderNames[it] } ?: root

    private companion object {
        const val TAG = "LibrarySearch"
    }
}
