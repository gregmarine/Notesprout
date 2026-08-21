package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
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
 * become [OutlineTree.Item]s (`pageIndex` from the session's pages, `x`/`y` from the row) →
 * [Result.Ok] with the built tree. The candidate rows are sorted into document order and capped at
 * `MAX_OUTLINE_ENTRIES` **before** the bind (**truncated** — C2), so the bind's budget is bounded. Objects whose
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
        if (capable.isEmpty() || !session.isOpen) return@withContext false
        session.writer.drain()
        val pages = session.pages.mapTo(HashSet()) { it.id }
        session.db.dao().liveObjectIdentities().any { row ->
            row.parentId in pages && ExtensionContract.parseIdentity(row.style ?: "")?.first in capable
        }
    }

    suspend fun gather(context: Context, session: NotebookSession, providers: ObjectProviders): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val t0 = System.currentTimeMillis()
        if (!session.isOpen) return@withContext Result.Ok(emptyList(), 0, false)
        session.writer.drain()
        val pageIndex = HashMap<String, Int>(session.pages.size * 2)
        session.pages.forEachIndexed { i, p -> pageIndex[p.id] = i }
        val rows = session.db.dao().liveObjectsAll()
        val capable = providers.outlineProviders
        // The candidate rows in **document order** (page, y, x), capped at MAX_OUTLINE_ENTRIES *before* the
        // bind (C2 review): what is sent — and so the bind's budget — is bounded by the cap, not by the
        // notebook; the footer's "first N" is the first N candidate objects in document order (today
        // every heading is an entry, so candidates = entries).
        val candidates = rows.asSequence()
            .filter { it.parentId in pageIndex }
            .mapNotNull { row -> ExtensionContract.parseIdentity(row.style ?: "")?.takeIf { it.first in capable }?.let { row to it } }
            .sortedWith(compareBy<Pair<SoilObjectEntity, Pair<String, String>>> { pageIndex.getValue(it.first.parentId) }.thenBy { it.first.y ?: 0f }.thenBy { it.first.x ?: 0f })
            .toList()
        val truncated = candidates.size > ExtensionContract.MAX_OUTLINE_ENTRIES
        val kept = if (truncated) candidates.subList(0, ExtensionContract.MAX_OUTLINE_ENTRIES) else candidates
        // providerKey → typeId → (document order) — one bind per provider, payloads batched per type.
        val byProvider = LinkedHashMap<String, LinkedHashMap<String, ArrayList<SoilObjectEntity>>>()
        for ((row, identity) in kept) {
            byProvider.getOrPut(identity.first) { LinkedHashMap() }.getOrPut(identity.second) { ArrayList() } += row
        }
        val items = ArrayList<OutlineTree.Item>(kept.size)
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
        val roots = OutlineTree.build(items)   // sorts by OutlineTree.DOCUMENT_ORDER itself
        Slog.d(TAG) { "gather: objects=${rows.size} candidates=${candidates.size} sent=${kept.size} providers=$askedProviders entries=${items.size} roots=${roots.size} truncated=$truncated in ${System.currentTimeMillis() - t0} ms" }
        Result.Ok(roots, items.size, truncated)
    }
}
