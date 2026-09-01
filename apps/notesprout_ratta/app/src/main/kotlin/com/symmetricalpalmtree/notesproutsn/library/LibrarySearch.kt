package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagClient
import com.symmetricalpalmtree.notesproutsn.notebook.TagTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    /** Whether a tag manager is installed **right now** — the answer the library's own entry last
     *  got. It decides the dialog's hint and nothing else: what the shelf does about tags is
     *  settled per run by [tagSearch], which asks again. */
    private val tagsAvailable: () -> Boolean = { false },
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
            hintRes = if (tagsAvailable()) R.string.library_search_hint_tags
            else R.string.library_search_hint,
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
     *
     * **Tags join the query** (arc 21 / W4, two queries since arc 22 / X3). One bind-per-call run
     * per query, inside which the tag list is read and matched and then **only the matched tags'
     * assignments** are fetched; no tag extension installed means names only, silently — the same
     * rule every other tag door follows, except that this one has nothing to make GONE, so it simply
     * answers arc 20's shelf. A tagged **page** becomes its own card, which needs the page's number,
     * which needs the notebook's page list: see [pageCards] for what that costs and when it is paid.
     */
    suspend fun cards(pinnedIds: Set<String>): List<CardItem> {
        // Unreachable by the dialog, which refuses a blank query — a safety net, not a state.
        if (!FuzzyRank.isRunnable(query)) return emptyList()
        val folders = repo.allFolders()
        val notebooks = repo.allNotebooks()
        val folderNames = folders.associate { it.id to it.name }
        val root = activity.getString(R.string.recents_parent_root)
        val tags = tagSearch()
        // Ranking is pure CPU over the whole library and, with tags, over the assignments that were
        // actually fetched. Off Main: this is called from the listing coroutine.
        val shelf = withContext(Dispatchers.Default) {
            if (tags == null) SearchAssembly.rank(folders, notebooks, query)
            else SearchAssembly.rank(folders, notebooks, query, tags.matches, tags.assignments)
        }

        val cards = ArrayList<CardItem>(shelf.folders.size + shelf.notebooks.size + shelf.pages.size)
        for (f in shelf.folders) cards += CardItem.Folder(f, subtitle = parentLabel(f, folderNames, root))
        for (hit in shelf.notebooks) {
            val where = parentLabel(hit.notebook, folderNames, root)
            cards += CardItem.Notebook(
                hit.notebook,
                pinned = hit.notebook.id in pinnedIds,
                subtitle = hit.matchedTag?.let { activity.getString(R.string.search_where_and_tag, where, it) } ?: where,
            )
        }
        cards += pageCards(shelf.pages, folderNames, root)
        return cards
    }

    /**
     * The page hits as cards — the one part of a run that reads anything but the index.
     *
     * A page's **number** is its position among its notebook's live page rows, and only that
     * notebook's `.soil` knows it. So one file is opened per notebook that produced a page hit —
     * never per hit, never for a notebook that produced none, and not at all when nothing was
     * tagged. [PageNumbers] caches the answer against the notebook's `updatedAt`, so a second search
     * over an unchanged library reads no files at all.
     *
     * That read is also the **aliveness check**: a page that is no longer in the list has been
     * deleted under its tag, and its card is dropped. A notebook that will not open drops its page
     * cards and keeps its notebook card — the shelf says less rather than saying something wrong.
     */
    private suspend fun pageCards(
        hits: List<SearchAssembly.PageHit>,
        folderNames: Map<String, String>,
        root: String,
    ): List<CardItem> {
        if (hits.isEmpty()) return emptyList()
        val pagesByNotebook = HashMap<String, List<String>?>()
        val cards = ArrayList<CardItem>(hits.size)
        for (hit in hits) {
            val pages = pagesByNotebook.getOrPut(hit.notebook.id) {
                PageNumbers.pagesOf(activity, hit.notebook.id, hit.notebook.updatedAt)
            } ?: continue
            val number = TagTargets.pageNumber(pages, hit.pageId) ?: continue
            val where = parentLabel(hit.notebook, folderNames, root)
            cards += CardItem.Page(
                hit.notebook,
                pageId = hit.pageId,
                pageLabel = activity.getString(R.string.tag_page_label, number),
                subtitle = activity.getString(R.string.search_where_and_tag, where, hit.matchedTag),
            )
        }
        return cards
    }

    /** What one run read out of the tag extension: which tags the query touched, and the
     *  assignments of exactly those. */
    private class TagRun(val matches: SearchAssembly.TagMatches, val assignments: List<AssignmentRecord>)

    /**
     * The two tag queries, or null when there is no tag extension — in which case search is names
     * only and says nothing about it, because there is no control to hide and nothing was promised.
     * A store that cannot be read is null here too: a search shelf is not the place to learn about
     * it, and a read never writes over anything.
     *
     * The matching runs **inside** the call (arc 22 / X3) because its answer is what the second
     * query asks for. It is pure CPU over at most five thousand short strings, on the IO thread the
     * call block already occupies, and the alternative — ranking here and calling back in — would be
     * a second bind for the same run.
     */
    private suspend fun tagSearch(): TagRun? {
        val ref = ExtensionRegistry.tagManager(activity) ?: return null
        return try {
            var matches: SearchAssembly.TagMatches? = null
            val result = TagClient.search(activity, ref) { tags ->
                SearchAssembly.matchTags(tags, query).also { matches = it }.ids
            }
            // The matcher always ran (the block is what produced `result`); the fallback exists so
            // this reads as total rather than as an assertion.
            TagRun(matches ?: SearchAssembly.matchTags(result.tags, query), result.assignments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "tag search unavailable: ${e.javaClass.simpleName}" }
            null
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
