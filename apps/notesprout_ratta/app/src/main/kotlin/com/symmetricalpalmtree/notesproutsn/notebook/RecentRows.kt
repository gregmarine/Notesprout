package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry
import kotlin.math.roundToInt

/**
 * What the notebook's Recents panel shows, as arithmetic (arc 10 / T1 — pure Kotlin, JVM-tested).
 *
 * The library's shelf has the same job and the same rule — **stored order wins**, because recents is
 * a history and a sort would turn "what I was just working on" into an alphabet — so this is
 * [com.symmetricalpalmtree.notesproutsn.library.RecentsAssembly] with the notebook's one extra
 * clause: the notebook you are *in* is never offered as somewhere to go.
 *
 * The two are kept separate rather than shared because they answer to different screens; if a third
 * caller ever appears, merge them then.
 */
object RecentRows {

    /**
     * The notebook ids to render, newest first: [entries] in their stored order, keeping only ids in
     * [aliveIds], never [currentId], each at most once (a duplicate can only be a corrupted store,
     * so the first — newest — wins).
     */
    fun select(entries: List<RecentEntry>, aliveIds: Set<String>, currentId: String): List<String> {
        val seen = HashSet<String>(entries.size)
        return entries.mapNotNull { e ->
            e.id.takeIf { it != currentId && it in aliveIds && seen.add(it) }
        }
    }

    /**
     * A row's folder line: the [root] label followed by each folder from the root down, joined the
     * way the library's breadcrumb reads. A notebook at the root is just [root].
     */
    fun breadcrumb(root: String, segments: List<String>): String =
        (listOf(root) + segments).joinToString(SEPARATOR)

    /**
     * The panel's share of the window width. **Narrower than the Contents' 60 %** — a recents row is
     * a name, a time and a path, and the ToC's width would be empty space. The 480 dp full-screen
     * breakpoint is still shared with [ContentsLayout] (one rule for "a sidebar doesn't fit here");
     * only the width differs.
     */
    const val SIDEBAR_WIDTH_FRACTION = 0.50f

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /**
     * How many rows fit a body of [bodyHeightPx] when one row measures [rowHeightPx] — at least 1,
     * and 1 for a nonsense row height. Unlike the Contents, the row is **measured** rather than
     * taken from a dp constant: three lines of two text sizes is not a number worth hard-coding.
     */
    fun itemsPerPage(bodyHeightPx: Int, rowHeightPx: Int): Int {
        if (rowHeightPx <= 0) return 1
        return maxOf(1, bodyHeightPx / rowHeightPx)
    }

    /** The library's crumb separator — one vocabulary for "where this lives". */
    const val SEPARATOR = " › "
}
