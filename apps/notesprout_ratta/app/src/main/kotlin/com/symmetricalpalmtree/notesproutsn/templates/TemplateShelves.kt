package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry

/**
 * What the Pinned and Recents shelves are made of — **pure Kotlin, no Android, JVM-tested**
 * (arc 13 / G5). The screen decides how a shelf *looks*; this decides what is on it.
 *
 * The awkward thing both shelves share, and the reason they are one file: a stored id can name
 * either a **row** (a static template the user imported) or a **sentinel** (one of the three
 * built-in papers, which has no row and never will). So neither shelf can answer "does this still
 * exist" with a single index read, and neither may prune a sentinel — a built-in that vanished off
 * the pinned shelf because the database had never heard of it would be a bug the user could only
 * fix by re-pinning it, over and over.
 */
object TemplateShelves {

    /** The three built-ins, by sentinel id. Blank and the Default folder are **not** here: a folder
     *  is not paper, and Blank is already the first card at the root, forever. */
    val PINNABLE_SENTINELS: Set<String> = TemplateLibrary.BUILT_IN_KINDS.map { it.first }.toSet()

    /** True when [id] may carry a pin at all — a built-in paper, or anything that is not a sentinel
     *  (i.e. a real row; whether that row is *alive* is the database's question, not this one). */
    fun isPinnable(id: String): Boolean = id in PINNABLE_SENTINELS || !TemplateLibrary.isSentinel(id)

    /**
     * The pinned shelf's cards, in the order the caller's sort produced for [sortedRows], with the
     * built-ins **first** in their fixed Lined/Dotted/Grid order.
     *
     * The built-ins lead rather than being sorted in among the rows for the same reason Blank and
     * Default lead the root: they are the app's own paper, they are the floor of the library, and
     * a name sort that buried Grid between two imports would make the shelf's most reliable
     * contents the hardest to find.
     */
    fun pinnedCards(
        pinnedIds: Set<String>,
        sortedRows: List<ObjectSummary>,
        builtInLabels: List<String>,
    ): List<TemplateCard> = buildList {
        TemplateLibrary.BUILT_IN_KINDS.forEachIndexed { i, (id, kind) ->
            if (id in pinnedIds) add(TemplateCard.BuiltIn(id, builtInLabels[i], kind))
        }
        addAll(TemplateLibrary.rowCards(sortedRows).filterIsInstance<TemplateCard.Static>())
    }

    /**
     * The ids a Recents shelf shows, newest first: [entries] in their **stored order, never
     * re-sorted** (the library's rule — a history that obeyed Name ↑ would stop being a history),
     * each at most once, keeping only ids that still resolve.
     *
     * An id resolves when it is a pinnable sentinel (always) or is present in [aliveRowIds]. That
     * asymmetry is the whole point: [pruneable] below is what the caller feeds back to the prefs,
     * and it must never contain a sentinel.
     */
    fun recentIds(entries: List<RecentEntry>, aliveRowIds: Set<String>): List<String> {
        val seen = HashSet<String>(entries.size)
        return entries.mapNotNull { e ->
            e.id.takeIf { (it in PINNABLE_SENTINELS || it in aliveRowIds) && seen.add(it) }
        }
    }

    /**
     * The ids a Recents store may keep, for the self-healing prune: every sentinel plus every row
     * that is still alive. Passing this to `RecentsPrefs.pruneDeleted` drops the dead rows and
     * leaves the built-ins alone.
     */
    fun pruneable(aliveRowIds: Set<String>): Set<String> = aliveRowIds + PINNABLE_SENTINELS

    /**
     * The ids on the pinned list that need a row read, i.e. everything that is not a built-in.
     * Keeps the sentinel ids out of an `IN (…)` the database can only answer "no" to.
     */
    fun rowIdsAmong(ids: List<String>): List<String> = ids.filterNot { it in PINNABLE_SENTINELS }

    /**
     * The whole **search** shelf, sentinels and rows **ranked together** against [query]
     * (arc 20 / Q1 — arc 13 built these as two lists, sentinels first, ordered by the screen's sort).
     *
     * One list, because relevance is now the order: a user who types "grid" wants the Grid paper
     * first whether it is a built-in or an import, and two lists could only have put every sentinel
     * ahead of a better answer. [FuzzyRank] is the same matcher the library's search uses, so a name
     * that is findable on one screen is findable on the other.
     *
     * [rows] is templates only — folders are places, not paper, and a flat shelf whose taps mean
     * "pick this" must never hold a card whose tap means "go somewhere" with no breadcrumb back.
     * Blank is searchable even though it is not pinnable: a user who types "blank" expecting the
     * no-paper card and gets nothing has been told something false. The labels come in as
     * parameters, in [TemplateLibrary.BUILT_IN_KINDS] order — the strings are the screen's, the
     * composition is this file's.
     */
    fun searchCards(
        query: String,
        blankLabel: String,
        builtInLabels: List<String>,
        rows: List<ObjectSummary>,
    ): List<TemplateCard> {
        val candidates = ArrayList<TemplateCard>(rows.size + 1 + builtInLabels.size)
        candidates.add(TemplateCard.Blank(blankLabel))
        TemplateLibrary.BUILT_IN_KINDS.forEachIndexed { i, (id, kind) ->
            candidates.add(TemplateCard.BuiltIn(id, builtInLabels[i], kind))
        }
        candidates.addAll(TemplateLibrary.rowCards(rows).filterIsInstance<TemplateCard.Static>())
        return FuzzyRank.rank(candidates, query) { it.name }
    }

    /** The Default folder is never pinnable and never a recent — it is a place. Named here so the
     *  screen's guards read as one rule rather than an inline id comparison. */
    fun isPlace(id: String): Boolean = id == ListIds.TEMPLATE_DEFAULT_ID
}
