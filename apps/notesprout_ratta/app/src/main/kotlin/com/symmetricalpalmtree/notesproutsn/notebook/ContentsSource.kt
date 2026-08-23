package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Contents gather (arc 4 / C1 — IO): drain the writer (a heading created a moment ago must be
 * in its row — the shared [SoilWriter] makes one drain cover strokes and headings) → every live
 * `heading` row (`SoilDao.liveHeadingsAll`, blob-free in effect: heading writes never set `blob`) →
 * the pure [items] pass (live-page filter, label from `stripHeadingPrefix`, level from the row's
 * authoritative `flags` via [HeadingRows.toHeading], document order, the cap) →
 * [OutlineTree.build]. Rebuilt on every open — no cache, nothing to invalidate; the dialog is a
 * modal snapshot. Logs counts + durations — never a label.
 *
 * **Deliberate deltas from Paper's arc-5 `ContentsSource`** (SN headings are core rows, not
 * extension objects): no provider layer, no probe, no sanitize, and no `Failed` result — a gather
 * over our own rows cannot "not answer". [MAX_ENTRIES] survives the port not for Paper's Binder
 * budget but as a memory/UI bound: the `.soil` is importable, so row counts aren't under this
 * app's control, and "the first N in document order" is the honest degradation.
 */
object ContentsSource {

    private const val TAG = "ContentsSource"

    /** Cap on listed entries — applied in document order; the dialog's footer names the cut. */
    const val MAX_ENTRIES = 2000

    /** The gathered outline: [count] = entries listed (after the cap); [truncated] = the cap bit. */
    data class Outline(val roots: List<OutlineTree.Node>, val count: Int, val truncated: Boolean) {
        val isEmpty: Boolean get() = roots.isEmpty()
    }

    /**
     * Cheap availability — does the notebook hold **any** live heading on a live page? One
     * blob-free row read after a writer drain; exact, unlike Paper's provider-identity
     * approximation. The host shows the Contents button / arms the swipe only while true, and
     * re-asks after every heading mutation and page change.
     */
    suspend fun available(session: NotebookSession): Boolean = withContext(Dispatchers.IO) {
        if (!session.isOpen) return@withContext false
        session.writer.drain()
        val pages = session.pages.mapTo(HashSet()) { it.id }
        session.db.dao().liveHeadingsAll().any { it.parentId in pages }
    }

    suspend fun gather(session: NotebookSession): Outline = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (!session.isOpen) return@withContext Outline(emptyList(), 0, false)
        session.writer.drain()
        // One reference read of the immutable page list — a mid-gather page op swaps the list
        // out whole, it never mutates this snapshot.
        val pages = session.pages
        val pageIndexById = HashMap<String, Int>(pages.size * 2)
        pages.forEachIndexed { i, p -> pageIndexById[p.id] = i }
        val rows = session.db.dao().liveHeadingsAll()
        val (items, truncated) = items(rows, pageIndexById)
        val roots = OutlineTree.build(items)
        Slog.d(TAG) {
            "gather: rows=${rows.size} entries=${items.size} roots=${roots.size} truncated=$truncated " +
                "in ${System.currentTimeMillis() - t0} ms"
        }
        Outline(roots, items.size, truncated)
    }

    /**
     * The pure half of the gather (JVM-tested): rows → capped, document-ordered [OutlineTree.Item]s
     * + the truncation bit. Dropped, never crashed on: a row on a page not in [pageIndexById]
     * (soft-deleted page, foreign parent), a malformed row ([HeadingRows.toHeading] → null), and a
     * label that strips to blank (the empty-Save rule means one shouldn't exist — Paper's "blank is
     * not an outline item" kept for rows written by something else).
     */
    fun items(
        rows: List<SoilObjectEntity>,
        pageIndexById: Map<String, Int>,
    ): Pair<List<OutlineTree.Item>, Boolean> {
        val all = rows.asSequence()
            .mapNotNull { row ->
                val pageIndex = pageIndexById[row.parentId] ?: return@mapNotNull null
                val h = HeadingRows.toHeading(row) ?: return@mapNotNull null
                val label = HeadingPrefix.stripHeadingPrefix(h.text).trim()
                if (label.isEmpty()) return@mapNotNull null
                OutlineTree.Item(h.id, pageIndex, h.x, h.y, label, h.level)
            }
            .sortedWith(OutlineTree.DOCUMENT_ORDER)
            .toList()
        val truncated = all.size > MAX_ENTRIES
        return (if (truncated) all.subList(0, MAX_ENTRIES) else all) to truncated
    }
}
