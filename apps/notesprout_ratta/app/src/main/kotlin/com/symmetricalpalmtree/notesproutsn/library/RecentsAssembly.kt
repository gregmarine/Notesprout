package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry

/**
 * What the Recents shelf actually shows, as arithmetic — pure and JVM-tested so the ordering rule
 * cannot quietly drift into the Activity's sorting code.
 *
 * The rule is one sentence: **stored order wins**. Recents is a history, so the library's sort
 * preference must not touch it — Name ↑ would turn "what I was just working on" into an alphabet.
 * Dead ids are dropped (a notebook deleted on another screen, or a prefs blob that outlived its
 * index rows), and a duplicate id can only be a corrupted store, so the first — newest — wins.
 */
object RecentsAssembly {

    /**
     * The ids to render, newest first: [entries] in their stored order, keeping only ids present in
     * [aliveIds], each at most once.
     */
    fun visibleIds(entries: List<RecentEntry>, aliveIds: Set<String>): List<String> {
        val seen = HashSet<String>(entries.size)
        return entries.mapNotNull { e ->
            e.id.takeIf { it in aliveIds && seen.add(it) }
        }
    }
}
