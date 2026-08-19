package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Contents gather (arc 5 / C0 — IO): drain the writer (a heading created a moment ago must be
 * in its row) → every live object row (`SoilDao.liveObjectsAll`, blob-free) → keep rows whose
 * `parentId` is a live page and whose `style` parses (`parseIdentity`) → group by provider key,
 * **only keys in [ObjectProviders.outlineProviders]** → per provider **one**
 * `ObjectProviderClient.describeOutlineAll` bind (payloads batched per type, chunked by `OutlineCaps`)
 * in [ObjectProviders.contributions] order → a null reply from a provider → [Result.Failed] naming
 * it (**stop** — the flow shows the failure dialog, nothing opens; C0 Q4) → entries with `level ≥ 1`
 * become [OutlineTree.Item]s (`pageIndex` from the session's pages, `x`/`y` from the row) → sorted +
 * capped at `MAX_OUTLINE_ENTRIES` (**truncated**) → [Result.Ok] with the built tree. Objects whose
 * provider is absent / disabled / not outline-capable are simply not listed. Rebuilt on every open —
 * no cache, nothing to invalidate (Q6). Logs counts + durations — never a label or payload.
 */
object ContentsSource {

    private const val TAG = "ContentsSource"

    sealed class Result {
        /** [roots] = the tree; [count] = the entries listed (after the cap); [truncated] = the cap bit. */
        class Ok(val roots: List<OutlineTree.Node>, val count: Int, val truncated: Boolean) : Result() {
            val isEmpty: Boolean get() = roots.isEmpty()
        }
        /** A capable provider did not answer — [providerLabel] for the dialog. */
        class Failed(val providerLabel: String) : Result()
    }

    /**
     * Cheap availability (C1, user's call after item 9): does the notebook hold **any** live object of an
     * outline-capable provider on a live page? No bind — one blob-free row read after a writer drain. The
     * host shows the Contents button / arms the swipe only while true, and re-asks after every object
     * mutation and page change. (Counts objects by provider identity, not entries — a provider's
     * `level 0` objects would count; every heading is an entry, so today it is exact.)
     */
    suspend fun available(session: NotebookSession, providers: ObjectProviders): Boolean = withContext(Dispatchers.IO) {
        val capable = providers.outlineProviders
        if (capable.isEmpty()) return@withContext false
        session.writer.drain()
        val pages = session.pages.mapTo(HashSet()) { it.id }
        session.db.dao().liveObjectsAll().any { row ->
            row.parentId in pages && ExtensionContract.parseIdentity(row.style ?: "")?.first in capable
        }
    }

    suspend fun gather(context: Context, session: NotebookSession, providers: ObjectProviders): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val t0 = System.currentTimeMillis()
        session.writer.drain()
        val pageIndex = HashMap<String, Int>(session.pages.size * 2)
        session.pages.forEachIndexed { i, p -> pageIndex[p.id] = i }
        val rows = session.db.dao().liveObjectsAll()
        val capable = providers.outlineProviders
        // providerKey → typeId → (row order) — one bind per provider, payloads batched per type.
        val byProvider = LinkedHashMap<String, LinkedHashMap<String, ArrayList<com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity>>>()
        var kept = 0
        for (row in rows) {
            if (row.parentId !in pageIndex) continue
            val identity = ExtensionContract.parseIdentity(row.style ?: "") ?: continue
            if (identity.first !in capable) continue
            byProvider.getOrPut(identity.first) { LinkedHashMap() }.getOrPut(identity.second) { ArrayList() } += row
            kept++
        }
        val items = ArrayList<OutlineTree.Item>(kept)
        var askedProviders = 0
        for (key in capable) {
            val byType = byProvider[key] ?: continue
            val client = providers.clientFor(app, key) ?: continue
            askedProviders++
            val payloads = byType.mapValues { (_, list) -> list.map { it.text ?: "" } }
            val replies = client.describeOutlineAll(payloads)
                ?: run {
                    Slog.d(TAG) { "gather: $key did not answer the outline — failed after ${System.currentTimeMillis() - t0} ms" }
                    return@withContext Result.Failed(providers.labelOf(key))
                }
            for ((typeId, list) in byType) {
                val entries = replies[typeId] ?: continue
                for ((i, row) in list.withIndex()) {
                    val e = entries.getOrNull(i) ?: continue
                    if (e.level < 1) continue
                    items += OutlineTree.Item(row.id, pageIndex.getValue(row.parentId), row.x ?: 0f, row.y ?: 0f, e.label, e.level)
                }
            }
        }
        val sorted = items.sortedWith(compareBy<OutlineTree.Item> { it.pageIndex }.thenBy { it.y }.thenBy { it.x })
        val truncated = sorted.size > ExtensionContract.MAX_OUTLINE_ENTRIES
        val capped = if (truncated) sorted.subList(0, ExtensionContract.MAX_OUTLINE_ENTRIES) else sorted
        val roots = OutlineTree.build(capped)
        Slog.d(TAG) { "gather: objects=${rows.size} kept=$kept providers=$askedProviders entries=${capped.size} roots=${roots.size} truncated=$truncated in ${System.currentTimeMillis() - t0} ms" }
        Result.Ok(roots, capped.size, truncated)
    }
}
