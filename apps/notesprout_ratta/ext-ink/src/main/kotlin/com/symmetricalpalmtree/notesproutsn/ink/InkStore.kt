package com.symmetricalpalmtree.notesproutsn.ink

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** One page as it is stored: its size and its strokes, each with the `"order"` it holds. */
class PageInk(val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>) {
    companion object {
        /** A page with nothing on it and no size of its own yet. */
        val EMPTY = PageInk(0f, 0f, emptyList())
    }
}

/**
 * What every ink-on-rows store over the host's `IExtensionStore` shares (arc 22 / X2 as the guts of
 * the pad's `ScratchStore`; the base since arc 23 / Y1): the batch split, the compensated
 * multi-batch write, the one-rule error mapping, and the planned stroke read. **Blocking** — every
 * call runs on `Dispatchers.IO` (a screen) or the Binder thread (`begin` / `receiveInk`), never
 * Main. The extension writes nothing to disk itself: the store is the host's, lent for the showing.
 *
 * The schema, every SQL string and every read that is not a stroke read are the subclass's own —
 * a consumer's table shapes stay pinned by the consumer's own test.
 *
 * **There is no page ceiling.** A page is rows, and the only failure left is the store being gone —
 * any exception at all becomes [StoreUnavailable], which is what a screen and a service both
 * answer to.
 */
abstract class InkStore(
    protected val store: IExtensionStore,
    /** The payload caps — the contract's, overridden only by tests that need more than one batch.
     *  One number: it bounds an `exec` batch going in and a planned read coming back. */
    protected val maxPayloadBytes: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    protected val maxBatchStatements: Int = ExtensionContract.STORE_MAX_BATCH_STATEMENTS,
    private val tag: String,
) {

    /**
     * Run [statements] in order. One batch — every ordinary flush, page operation and placement —
     * is one transaction and therefore atomic; a write long enough to need several is several, and
     * the caller's retry converges because every statement a consumer builds is idempotent.
     */
    fun execAll(statements: List<Statement>) = guard { run(statements) }

    protected fun run(statements: List<Statement>) {
        for (batch in StoreBatches.split(statements, maxPayloadBytes, maxBatchStatements)) StoreReads.exec(store, batch)
    }

    /** [run], but a failure after at least one batch has landed runs [compensation] first, so a
     *  multi-batch placement keeps the "nothing was placed" promise a single transaction gives for free. */
    protected fun compensated(statements: List<Statement>, compensation: () -> List<Statement>) {
        val batches = StoreBatches.split(statements, maxPayloadBytes, maxBatchStatements)
        var landed = 0
        try {
            for (batch in batches) {
                StoreReads.exec(store, batch)
                landed++
            }
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            if (landed > 0) {
                Log.w(tag, "placement failed after $landed of ${batches.size} batches — compensating")
                runCatching { run(compensation()) }
            }
            throw StoreUnavailable(e)
        }
    }

    /** Every store failure is [StoreUnavailable] — the one rule. */
    protected fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }

    /**
     * A page's strokes, read in planned ranges ([StrokeReadPlan]) so a page of any size comes back
     * without ever asking for one result the host would refuse. [selectLens] is the consumer's
     * `"order", LENGTH(blob) AS len` index read; [selectRange] its `BETWEEN` read for one range. A
     * row that will not decode is dropped and counted, never a lost page ([StrokeRows]).
     */
    protected fun readStrokes(pageId: String, selectLens: Statement, selectRange: (LongRange) -> Statement): List<Pair<Long, Stroke>> {
        val index = StoreReads.all(store, selectLens).rows
        val orders = index.map { it.long("order") }
        val lengths = index.map { it.long("len").toInt() }
        val strokes = ArrayList<Pair<Long, Stroke>>(orders.size)
        var dropped = 0
        for (range in StrokeReadPlan.ranges(orders, lengths, maxPayloadBytes)) {
            for (row in StoreReads.all(store, selectRange(range)).rows) {
                val decoded = StrokeRows.decode(row)
                if (decoded == null) dropped++ else strokes += decoded
            }
        }
        if (dropped > 0) Log.w(tag, "page $pageId: $dropped stroke row(s) dropped")
        return strokes
    }
}
