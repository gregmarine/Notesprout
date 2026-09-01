package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex

/**
 * What the search shelf holds, and in what order — **pure Kotlin, no Android, JVM-tested**
 * (arc 20 / Q1, grown in arc 21 / W4). The rows come from the index, the tags come from the tag
 * extension's snapshot, and the ranking comes from [FuzzyRank]; the rules that live here are the
 * shelf's own.
 *
 * **Folders, then notebooks, then pages.** The library puts containers before contents everywhere
 * ([SortRules.foldersFirst]), and relevance never outranks the kind — a shelf that scattered folders
 * through the cards because one name matched a little worse would read as a different screen. Pages
 * come last for the same reason, one level further down.
 *
 * **One query, two kinds of answer** (W4). A name matches, and so does a tag; both rank through the
 * same matcher with the same total order, so `mtg` finds a notebook called "Meeting Notes" and a
 * notebook tagged `meeting` and puts them in one list rather than two. A notebook that matches both
 * ways appears **once**, at its better rank.
 *
 * **A page hit is its own card.** A tag on page 3 of a notebook is not a fact about the notebook —
 * the whole point of tagging a page is that the page is the thing you want back — so it gets a card
 * that opens there. That is only expressible because a W4 assignment names its notebook as well as
 * its page; before that, a tagged page could not be traced to the notebook holding it.
 *
 * It sits beside [RecentsAssembly] for the same reason that one exists: the ordering rule of a shelf
 * is a thing to reason about on its own, not a fragment of the Activity's listing code.
 */
object SearchAssembly {

    /**
     * A notebook on the shelf.
     *
     * @param matchedTag the tag that put it here, and **only when its name did not match** — the
     *   subtitle exists to answer "why is this here", and a name match has already answered it.
     */
    class NotebookHit(val notebook: ObjectSummary, val matchedTag: String?)

    /**
     * One page on the shelf: which notebook holds it, which page it is, and the tag that matched.
     *
     * [pageId] is a page row inside [notebook]'s `.soil`; the shelf still has to resolve its
     * **number**, which is not this file's business — a page number comes from the notebook's live
     * page list, and reading that is IO.
     */
    class PageHit(val notebook: ObjectSummary, val pageId: String, val matchedTag: String)

    /** The whole ranked shelf, in the order it is drawn. */
    class Shelf(
        val folders: List<ObjectSummary>,
        val notebooks: List<NotebookHit>,
        val pages: List<PageHit>,
    ) {
        val isEmpty: Boolean get() = folders.isEmpty() && notebooks.isEmpty() && pages.isEmpty()
    }

    /**
     * The ranked shelf for [query]: matching [folders], then matching [notebooks], then matching
     * pages within them.
     *
     * Both listings are whole-library — search has no folder, by decision (arc 20: "a search that
     * only looked in the folder you happen to be standing in would answer 'no' for a notebook two
     * folders over"). A query that is not [FuzzyRank.isRunnable] returns nothing at all.
     *
     * [tags] is the tag extension's snapshot, or **null** when no tag extension is installed — in
     * which case this is exactly arc 20's shelf and nothing says otherwise.
     *
     * **Dead assignments never surface, without a filtering pass.** Tags are read *through*
     * [notebooks]: an assignment naming a notebook that is not in that list is simply never looked
     * at, and [notebooks] is the index's own live listing. A page's aliveness is a different
     * question with a different source (the notebook's live page rows) and belongs to the caller,
     * which is the only side that can read one.
     */
    fun rank(
        folders: List<ObjectSummary>,
        notebooks: List<ObjectSummary>,
        query: String,
        tags: TagIndex? = null,
    ): Shelf {
        if (!FuzzyRank.isRunnable(query)) return Shelf(emptyList(), emptyList(), emptyList())
        val rankedFolders = FuzzyRank.rank(folders, query) { it.name }
        if (tags == null) {
            val hits = FuzzyRank.rank(notebooks, query) { it.name }.map { NotebookHit(it, null) }
            return Shelf(rankedFolders, hits, emptyList())
        }

        // Which tags answer the query at all, and how well. Computed once: a library can hold five
        // thousand tags and a notebook can carry many, so re-matching per notebook would be the
        // same string comparison run over and over for one shelf.
        val matching = HashMap<String, FuzzyRank.Match>()
        for (tag in tags.tags) {
            FuzzyRank.match(tag.display, query)?.let { matching[tag.id] = it }
        }

        val byNotebook = HashMap<String, ArrayList<TagIndex.Assignment>>()
        if (matching.isNotEmpty()) {
            for (a in tags.assignments) {
                if (a.tagId !in matching) continue
                byNotebook.getOrPut(a.notebookId) { ArrayList() } += a
            }
        }

        val notebookHits = ArrayList<Candidate<NotebookHit>>()
        val pageHits = ArrayList<Candidate<PageHit>>()
        for (nb in notebooks) {
            val nameMatch = FuzzyRank.match(nb.name, query)
            val mine = byNotebook[nb.id]

            // The notebook's own tags — the ones with no page. Its best is compared against its
            // name's, and the better label is the one the whole row is then ranked by.
            val bestOwnTag = mine
                ?.filter { it.pageId == null }
                ?.mapNotNull { a -> tags.tag(a.tagId)?.let { it.display to matching.getValue(a.tagId) } }
                ?.minWithOrNull(compareBy({ it.second }, { it.first.length }, { it.first }))

            // The row is ranked by whichever of the two answered better. [FuzzyRank.Match] orders
            // best first, so the smaller of the pair wins and a tie goes to the name.
            val label = when {
                bestOwnTag == null -> nb.name.takeIf { nameMatch != null }
                nameMatch == null -> bestOwnTag.first
                nameMatch <= bestOwnTag.second -> nb.name
                else -> bestOwnTag.first
            }
            if (label != null) {
                // Ranked by the tag is not the same as *shown* with the tag: the subtitle answers
                // "why is this here", which only an unmatched name leaves open.
                val shown = if (nameMatch == null) bestOwnTag?.first else null
                notebookHits += Candidate(NotebookHit(nb, shown), label)
            }

            // One card per page, not per tag: a page carrying two matching tags is still one page,
            // and it is named by whichever of them answered the query best.
            mine?.filter { it.pageId != null }
                ?.groupBy { it.pageId!! }
                ?.forEach { (pageId, assignments) ->
                    val best = assignments
                        .mapNotNull { a -> tags.tag(a.tagId)?.let { it.display to matching.getValue(a.tagId) } }
                        .minWithOrNull(compareBy({ it.second }, { it.first.length }, { it.first }))
                        ?: return@forEach
                    pageHits += Candidate(PageHit(nb, pageId, best.first), best.first)
                }
        }

        return Shelf(
            folders = rankedFolders,
            notebooks = FuzzyRank.rank(notebookHits, query) { it.label }.map { it.value },
            pages = FuzzyRank.rank(pageHits, query) { it.label }.map { it.value },
        )
    }

    /** A shelf row paired with the text that earned it its place — the label [FuzzyRank.rank] ranks
     *  by, which for a tag match is the tag and not the name. */
    private class Candidate<T>(val value: T, val label: String)
}
