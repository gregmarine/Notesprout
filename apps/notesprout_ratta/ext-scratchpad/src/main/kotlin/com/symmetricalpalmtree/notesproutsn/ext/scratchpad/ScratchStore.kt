package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads
import com.symmetricalpalmtree.notesproutsn.ink.InkStore
import com.symmetricalpalmtree.notesproutsn.ink.PageInk
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import java.util.UUID

/**
 * The scratch pad's tables over the host's `IExtensionStore` (arc 11 / J3, rewritten onto rows in
 * arc 22 / X2, on `:ext-ink`'s [InkStore] base since arc 23 / Y1). **Blocking** — every call runs
 * on `Dispatchers.IO` (the screen) or the Binder thread (`begin` / `receiveInk`), never Main. The
 * extension writes nothing to disk itself: this store is the host's, lent for the showing.
 *
 * The schema is [ScratchSchema.V1]; [load] applies it and is the only door — the host's gate
 * refuses `exec` / `query` on a binder that has not declared, so nothing may reach the store
 * before it. Every SQL string lives in [ScratchSql]; every write goes through [execAll], which
 * splits by `StoreBatches` and runs each batch as one transaction.
 *
 * **There is no page ceiling.** Arc 11's `PageFullException` existed because a page was one store
 * value; a page is now rows, and the only failure left is the store being gone — any exception at
 * all becomes [StoreUnavailable], which is what the screen and the service both answer to.
 */
class ScratchStore(
    store: IExtensionStore,
    maxPayloadBytes: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    maxBatchStatements: Int = ExtensionContract.STORE_MAX_BATCH_STATEMENTS,
) : InkStore(store, maxPayloadBytes, maxBatchStatements, TAG) {

    class Loaded(val ids: List<String>, val currentId: String)

    // ── Loading ──────────────────────────────────────────────────────────────

    /**
     * Declare the schema (idempotent — a no-op is one SELECT host-side), then read the page list and
     * the current page. First run creates one blank page and names it current, in one batch. A
     * `current` that is not in the list is clamped and the row corrected, so the disagreement never
     * survives a second open.
     */
    fun load(): Loaded = guard {
        store.applySchema(ScratchSchema.V1)
        val ids = StoreReads.all(store, ScratchSql.selectPages()).rows.map { it.text("id") }
        if (ids.isEmpty()) {
            val id = newId()
            val now = System.currentTimeMillis()
            run(listOf(ScratchSql.insertPage(id, 0, 0f, 0f, now), ScratchSql.setCurrent(id)))
            return@guard Loaded(listOf(id), id)
        }
        val stored = StoreReads.all(store, ScratchSql.selectCurrent()).rows.firstOrNull()?.textOrNull("value")
        val current = ScratchPages.clampCurrent(ids, stored)
        if (current != stored) run(listOf(ScratchSql.setCurrent(current)))
        Loaded(ids, current)
    }

    /**
     * One page's size and ink. The strokes are read in planned ranges ([InkStore.readStrokes]) so a
     * page of any size comes back without ever asking for one result the host would refuse.
     *
     * A missing page row is a page that went away underneath us (only reachable through a host
     * restart mid-showing): it reads as empty and says so, rather than throwing.
     */
    fun readPage(id: String): PageInk = guard {
        val size = StoreReads.all(store, ScratchSql.selectPageSize(id)).rows.firstOrNull()
        if (size == null) Log.w(TAG, "page row is gone — reading it as empty")
        val width = size?.real("width")?.toFloat() ?: 0f
        val height = size?.real("height")?.toFloat() ?: 0f
        PageInk(width, height, readStrokes(id, ScratchSql.selectStrokeLens(id)) { ScratchSql.selectStrokes(id, it) })
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    fun setCurrent(id: String) = execAll(listOf(ScratchSql.setCurrent(id)))

    /** Insert a new blank page after [afterId]; returns (new id list, new id). It becomes current. */
    fun insertPage(ids: List<String>, afterId: String?): Pair<List<String>, String> {
        val id = newId()
        return insert(ScratchPages.insertAfter(ids, afterId, id), id)
    }

    /** Insert a new blank page before [beforeId]; returns (new id list, new id). It becomes current. */
    fun insertPageBefore(ids: List<String>, beforeId: String?): Pair<List<String>, String> {
        val id = newId()
        return insert(ScratchPages.insertBefore(ids, beforeId, id), id)
    }

    private fun insert(next: List<String>, id: String): Pair<List<String>, String> {
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>(next.size + 2)
        statements += ScratchSql.insertPage(id, next.indexOf(id), 0f, 0f, now)
        statements += renumber(next)
        statements += ScratchSql.setCurrent(id)
        execAll(statements)
        return next to id
    }

    /**
     * Delete [id] and its strokes; returns (new id list, landing id), and the landing page becomes
     * current. Never below one page: a lone page keeps its id and is emptied ([ScratchPages.delete]).
     */
    fun deletePage(ids: List<String>, id: String): Pair<List<String>, String> {
        val (rest, landing) = ScratchPages.delete(ids, id)
        val statements = ArrayList<Statement>(rest.size + 2)
        if (id in ids) {
            if (rest.size == ids.size) {
                statements += ScratchSql.clearPage(id)          // the lone page is emptied, not removed
            } else {
                statements += ScratchSql.deletePage(id)          // the declared cascade takes its strokes
                statements += renumber(rest)
            }
        }
        statements += ScratchSql.setCurrent(landing)
        execAll(statements)
        return rest to landing
    }

    // ── The notebook → pad placement (J5, the Binder thread) ─────────────────

    /** What [receive] placed: the page it landed on + the ids of the placed strokes (for "open
     *  selected"), and — so the screen can record the placement as one undo step — whether a page was
     *  inserted ([newPage]) and the page list + current page as they were before. */
    class Received(val pageId: String, val strokeIds: List<String>, val newPage: Boolean, val pagesBefore: List<String>, val currentBefore: String)

    /**
     * Place [strokes] — on a **new page** inserted after the current one (its size = the bundle's
     * page size) or appended to the **current page** (its own size kept; the bundle's if it has none
     * yet) — and make that page current, so the next screen launch opens on it.
     *
     * The whole placement is one statement list. Under the batch cap that is one transaction and the
     * promise "nothing was placed" is the transaction's; above it the batches run in order and a
     * failure part-way is **compensated** — the new page is deleted (cascade) and the positions put
     * back, or each minted stroke is deleted by id, one statement each (never an `IN (…)` list: the
     * 999-argument cap) — before [StoreUnavailable] is thrown. Either way the host's "nothing was
     * sent" is never contradicted by a stray page or half a placement.
     */
    fun receive(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newPage: Boolean): Received = guard {
        val loaded = load()
        val ids = strokes.map { it.id }
        val now = System.currentTimeMillis()
        if (newPage) {
            val id = newId()
            val next = ScratchPages.insertAfter(loaded.ids, loaded.currentId, id)
            val statements = ArrayList<Statement>(next.size + strokes.size + 2)
            statements += ScratchSql.insertPage(id, next.indexOf(id), pageWidth, pageHeight, now)
            statements += renumber(next)
            strokes.forEachIndexed { i, s -> statements += ScratchSql.putStroke(id, i.toLong(), s) }
            statements += ScratchSql.setCurrent(id)
            compensated(statements) { listOf(ScratchSql.deletePage(id)) + renumber(loaded.ids) }
            Slog.d(TAG) { "receive: ${strokes.size} strokes on a new page" }
            return@guard Received(id, ids, true, loaded.ids, loaded.currentId)
        }
        val current = loaded.currentId
        val maxOrder = StoreReads.all(store, ScratchSql.selectMaxOrder(current)).rows.firstOrNull()?.long("maxOrder") ?: -1L
        val size = StoreReads.all(store, ScratchSql.selectPageSize(current)).rows.firstOrNull()
        val known = (size?.real("width")?.toFloat() ?: 0f) > 0f && (size?.real("height")?.toFloat() ?: 0f) > 0f
        val statements = ArrayList<Statement>(strokes.size + 2)
        // The page keeps the size it already had — it is the pad's page, not the sender's.
        if (!known && pageWidth > 0f && pageHeight > 0f) statements += ScratchSql.sizePage(current, pageWidth, pageHeight, now)
        strokes.forEachIndexed { i, s -> statements += ScratchSql.putStroke(current, maxOrder + 1 + i, s) }
        statements += ScratchSql.setCurrent(current)
        compensated(statements) { ids.map { ScratchSql.dropStroke(it) } }
        Slog.d(TAG) { "receive: ${strokes.size} strokes on the current page" }
        Received(current, ids, false, loaded.ids, loaded.currentId)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Every page's position, for the list as it now stands. Page counts are tens — renumber all. */
    private fun renumber(ids: List<String>): List<Statement> = ids.mapIndexed { i, id -> ScratchSql.position(id, i) }

    companion object {
        private const val TAG = "ScratchStore"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
