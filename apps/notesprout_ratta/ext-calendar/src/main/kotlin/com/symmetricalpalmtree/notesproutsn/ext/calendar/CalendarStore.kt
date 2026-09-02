package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads
import com.symmetricalpalmtree.notesproutsn.ink.InkStore
import java.time.LocalDate
import java.util.UUID

/**
 * The calendar's tables over the host's `IExtensionStore` (arc 23 / Y1), on `:ext-ink`'s
 * [InkStore] base. **Blocking** — every call runs on `Dispatchers.IO` (the screen) or the Binder
 * thread (`begin` / `receiveInk`), never Main. The extension writes nothing to disk itself: this
 * store is the host's, lent for the showing.
 *
 * The schema is [CalendarSchema.V1]; [open] applies it and is the only door — the host's gate
 * refuses `exec` / `query` on a binder that has not declared, so nothing may reach the store before
 * it. Every SQL string lives in [CalendarSql]; every write goes through `execAll`, which splits by
 * `StoreBatches` and runs each batch as one transaction.
 *
 * **Reading a page writes nothing.** [readPage] answers what is there — a period row or not, a page
 * row or not, its strokes — and the document mints the missing rows only in the flush that carries
 * the first stroke. Browsing empty months leaves the row counts exactly where they were, which is
 * what [counts] is for: `sqlite3` cannot read a SQLCipher file, so the walk's proof is this log line.
 */
class CalendarStore(
    store: IExtensionStore,
    maxPayloadBytes: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    maxBatchStatements: Int = ExtensionContract.STORE_MAX_BATCH_STATEMENTS,
) : InkStore(store, maxPayloadBytes, maxBatchStatements, TAG) {

    /** The bookmark: the page the organizer was left open at. */
    class Position(val kind: Int, val date: LocalDate, val half: Int) {
        val target: CalendarTarget get() = CalendarTarget.of(kind, date, half)
    }

    /**
     * One page as it is stored — or is not. [periodId] is null when no period row exists for the
     * target's `(kind, date)`, [pageId] null when no page row exists under it; a null page carries
     * no size and no strokes. The document mints what is missing on the first stroke.
     */
    class StoredPage(val periodId: String?, val pageId: String?, val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>)

    class Counts(val periods: Long, val pages: Long, val strokes: Long)

    // ── Opening ──────────────────────────────────────────────────────────────

    /**
     * Declare the schema (idempotent — a no-op is one SELECT host-side), then read the bookmark. A
     * bookmark that does not parse — a row missing, a kind out of range, a date that is not an ISO
     * day or not normalized for its kind, a half that is not legal — reads as **no bookmark** (null)
     * rather than throwing: the screen opens on today's Month, which is the first-run answer anyway.
     */
    fun open(): Position? = guard {
        store.applySchema(CalendarSchema.V1)
        val rows = StoreReads.all(store, CalendarSql.selectState()).rows
        val state = HashMap<String, String>(rows.size)
        for (r in rows) state[r.text("key")] = r.text("value")
        val kind = state[CalendarSql.KEY_LAST_VIEW]?.toIntOrNull() ?: return@guard null
        val date = state[CalendarSql.KEY_LAST_DATE]?.let { CalendarDates.parse(it) } ?: return@guard null
        val half = state[CalendarSql.KEY_LAST_HALF]?.toIntOrNull() ?: return@guard null
        val valid = runCatching { CalendarTarget.requireValid(kind, CalendarDates.format(date), half) }.isSuccess
        if (!valid) return@guard null
        Position(kind, date, half)
    }

    /** The three row counts — logged at `begin`, never used for anything else. */
    fun counts(): Counts = guard {
        val row = StoreReads.all(store, CalendarSql.selectCounts()).rows.first()
        Counts(row.long("periods"), row.long("pages"), row.long("strokes"))
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * The page for [target] as stored. Two reads when the page exists (the join, then the strokes in
     * planned ranges), two when it does not (the join comes back empty; the period is looked up on
     * its own so the document knows whether the day's other half already minted it). Writes nothing.
     */
    fun readPage(target: CalendarTarget): StoredPage = guard {
        val header = readHeader(target)
        val id = header.pageId ?: return@guard header
        StoredPage(
            periodId = header.periodId,
            pageId = id,
            width = header.width,
            height = header.height,
            strokes = readStrokes(id, CalendarSql.selectStrokeLens(id)) { CalendarSql.selectStrokes(id, it) },
        )
    }

    /**
     * The page's rows without its ink — which rows exist and the page's size. What [receive] needs
     * (it places *onto* the page and never looks at what is there), and a read that stays one or two
     * small queries however much ink the page holds; the full stroke read is [readPage]'s alone.
     */
    fun readHeader(target: CalendarTarget): StoredPage = guard {
        val page = StoreReads.all(store, CalendarSql.selectPage(target.kind, target.date, target.half)).rows.firstOrNull()
        if (page == null) {
            val period = StoreReads.all(store, CalendarSql.selectPeriod(target.kind, target.date)).rows.firstOrNull()?.text("id")
            return@guard StoredPage(period, null, 0f, 0f, emptyList())
        }
        StoredPage(
            periodId = page.text("periodId"),
            pageId = page.text("id"),
            width = page.real("width").toFloat(),
            height = page.real("height").toFloat(),
            strokes = emptyList(),
        )
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /** The bookmark — written on every navigation, one batch of three rows. */
    fun saveState(target: CalendarTarget) = execAll(
        listOf(
            CalendarSql.setState(CalendarSql.KEY_LAST_VIEW, target.kind.toString()),
            CalendarSql.setState(CalendarSql.KEY_LAST_DATE, target.date),
            CalendarSql.setState(CalendarSql.KEY_LAST_HALF, target.half.toString()),
        ),
    )

    /**
     * The statements that mint a page's rows if they are missing — the lead of the flush that
     * carries a page's first stroke, and of a placement onto a page that does not exist yet. Both
     * are `INSERT OR IGNORE`, so a row that is already there is left exactly as it is; the page's
     * `periodId` is resolved from `(kind, date)` inside the statement, so [periodId] only has to be
     * *a* fresh id for the case where no period row exists.
     */
    fun mintRows(target: CalendarTarget, periodId: String, pageId: String, width: Float, height: Float, now: Long): List<Statement> =
        listOf(
            CalendarSql.insertPeriod(periodId, target.kind, target.date),
            CalendarSql.insertPage(pageId, target.kind, target.date, target.half, width, height, now),
        )

    // ── The notebook → calendar placement (Y3's host half; the Binder thread) ────

    /** What [receive] placed: the page (minted or existing) + the ids of the placed strokes, so the
     *  screen can open on it, select them and record one undo step. */
    class Received(val target: CalendarTarget, val pageId: String, val strokeIds: List<String>, val mintedPage: Boolean)

    /**
     * Place [strokes] on [target]'s page — minting its rows if it has none (at `0 × 0`: the page
     * takes the screen's size the first time a screen shows it, exactly as the pad's does; the
     * sender's page size is the sender's) — numbered after whatever is already there.
     *
     * The whole placement is one statement list. Under the batch cap that is one transaction and the
     * promise "nothing was placed" is the transaction's; above it the batches run in order and a
     * failure part-way is **compensated** — each minted stroke is deleted by id, one statement each
     * (never an `IN (…)` list: the 999-argument cap) — before `StoreUnavailable` is thrown. A period
     * or page row minted by the failed placement stays: an empty page is not a placement, and
     * nothing deletes a `period` in this arc.
     */
    fun receive(strokes: List<Stroke>, target: CalendarTarget): Received = guard {
        // The header only: a placement onto a page already holding megabytes of ink must not pay a
        // full stroke read inside the host's placement budget — a Binder call cannot be cancelled,
        // and a budget blown here reports a failure for ink that then lands anyway.
        val stored = readHeader(target)
        val ids = strokes.map { it.id }
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>(strokes.size + 3)
        val pageId: String
        val minted = stored.pageId == null
        if (minted) {
            pageId = newId()
            statements += mintRows(target, stored.periodId ?: newId(), pageId, 0f, 0f, now)
        } else {
            pageId = stored.pageId
        }
        val maxOrder = if (minted) -1L else StoreReads.all(store, CalendarSql.selectMaxOrder(pageId)).rows.firstOrNull()?.long("maxOrder") ?: -1L
        strokes.forEachIndexed { i, s -> statements += CalendarSql.putStroke(pageId, maxOrder + 1 + i, s) }
        statements += CalendarSql.touchPage(pageId, now)
        compensated(statements) { ids.map { CalendarSql.dropStroke(it) } }
        Slog.d(TAG) { "receive: ${strokes.size} strokes on ${if (minted) "a minted" else "the existing"} page" }
        Received(target, pageId, ids, minted)
    }

    companion object {
        private const val TAG = "CalendarStore"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
