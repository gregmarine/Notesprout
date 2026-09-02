package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import java.util.TreeMap

/**
 * One page of ink, in memory, with the op log that gets it to the store (arc 11 / J4 as the heart
 * of the pad's `ScratchDocument`, on rows since arc 22 / X2; shared as `:ext-ink` since arc 23 /
 * Y1). A consumer's document owns *which* page is showing and everything structural around it —
 * the pad's page list, the calendar's period rows — and delegates *what is on the page* here.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move], the replays) are
 * synchronous and run on Main, straight out of the g-paper callbacks: they touch only the in-memory
 * page, so a pen-up never waits on IO. [flushUntilClean] is `suspend` and hands the statements to
 * the consumer's `exec`, which hops to IO for the store call. The consumer serialises the
 * suspending half behind one mutex, exactly as the notebook serialises its page ops.
 *
 * **The page is a `TreeMap` keyed on the stroke's `"order"`.** That column is the writing order and
 * it is load-bearing: recognition and every render read ink as a sequence, and an erase that came
 * back at the end rather than in place would change the page. Orders are unique per page and
 * monotone — a new stroke takes the next order — so a restore can put a stroke back at the order it
 * held and nothing can have taken it. "Monotone" is a **high-water mark** ([highWater]), not the
 * last key in the map: erasing the tail stroke lowers the last key, and a new stroke that reused
 * its order would collide with the erased one when an undo brought it back.
 *
 * **What is unwritten is an op log, not a dirty flag.** Each edit records one entry per stroke id —
 * a `Put` (the row as it should now read) or a `Drop` — in a [LinkedHashMap], so a second edit to
 * the same stroke coalesces onto the first and a flush is one statement per touched stroke rather
 * than a re-encode of the page. The SQL for the two is the consumer's ([StrokeSql]), so each table
 * keeps its own pinned strings.
 *
 * **Re-flush until clean.** [flushUntilClean] snapshots the log and clears it *before* the IO hop,
 * then loops while it has been re-dirtied: a stroke committed during a write would otherwise be in
 * a snapshot that was already sent. A failed flush merges its snapshot back **under** anything
 * newer and rethrows. A consumer with something of its own to write (the pad's page size, the
 * calendar's first-stroke rows) says so through `extraDirty` and prepends it inside `exec`.
 */
class InkDocument(
    private val sql: StrokeSql,
    private val tag: String = "InkDocument",
) {

    /** The two stroke statements, the consumer's own so its SQL stays pinned by its own test. */
    interface StrokeSql {
        fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement
        fun dropStroke(id: String): Statement
    }

    /** One unwritten change to one stroke row. */
    private sealed interface Op {
        /** The row as it should now read — an added stroke, a moved one, a restored one. */
        class Put(val stroke: Stroke, val order: Long) : Op

        /** The row should not be there. `DELETE` tolerates a row that never landed, so an
         *  add-then-erase inside one flush window is safely one `DELETE`. */
        object Drop : Op
    }

    /** The page this document is showing — the consumer's id for it. `""` before the first [reset]. */
    var pageId: String = ""
        private set

    /** The page, `"order"` → stroke. Iteration order IS the writing order. */
    private val page = TreeMap<Long, Stroke>()

    /** id → the order it sits at, so an edit named by id can find its row. */
    private val orders = HashMap<String, Long>()

    /** The unwritten changes, coalesced per stroke id, in the order they were first made. */
    private val ops = LinkedHashMap<String, Op>()

    /** One past the highest order this page has held since it was loaded — never lowered by an
     *  erase, so an order a live undo entry remembers can never be handed out again. */
    private var highWater = 0L

    val strokes: List<Stroke> get() = page.values.toList()

    val hasUnsavedChanges: Boolean get() = ops.isNotEmpty()

    /** The order [id] sits at on this page, or null if it is not on it. */
    fun orderOf(id: String): Long? = orders[id]

    /** The page as it stands, `(order, stroke)` in writing order — what a consumer's undo carries. */
    fun entries(): List<Pair<Long, Stroke>> = page.map { it.key to it.value }

    // ── Loading ──────────────────────────────────────────────────────────────

    /**
     * Show [id] with [ink] as read from the store. Anything unwritten for the previous page is
     * forgotten — the consumer flushes before it swaps (or reads the target first and flushes
     * second, the pad's rule). Two stored rows at one order (never written by this code — but a row
     * is a row): the second is moved past the end and re-put, rather than silently hiding the first.
     */
    fun reset(id: String, ink: List<Pair<Long, Stroke>>) {
        page.clear()
        orders.clear()
        ops.clear()
        pageId = id
        highWater = 0L
        for ((order, stroke) in ink) {
            val at = if (page.containsKey(order)) highWater else order
            page[at] = stroke
            orders[stroke.id] = at
            highWater = maxOf(highWater, at + 1)
            if (at != order) ops[stroke.id] = Op.Put(stroke, at)
        }
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /** Take one committed stroke, at the end of the page's writing order. */
    fun addStroke(stroke: Stroke) {
        put(stroke, nextOrder())
    }

    /** Take a whole set at once, appended in the order given. Ids are the caller's (they were
     *  minted when the ink arrived and never change). */
    fun addStrokes(strokes: List<Stroke>) {
        for (s in strokes) put(s, nextOrder())
    }

    /** Put strokes back at the orders they held — an [InkAction.Pasted] redo. */
    fun addStrokesAt(strokes: List<Stroke>, orders: List<Long>) {
        require(strokes.size == orders.size) { "${strokes.size} strokes for ${orders.size} orders" }
        for (i in strokes.indices) put(strokes[i], orders[i])
    }

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    fun erase(ids: Collection<String>): InkAction.Erased? {
        if (ids.isEmpty()) return null
        val entries = ArrayList<InkAction.Erased.Entry>(ids.size)
        for (id in ids) {
            val order = orders[id] ?: continue
            val stroke = page[order] ?: continue
            entries += InkAction.Erased.Entry(order, stroke)
        }
        if (entries.isEmpty()) return null
        entries.sortBy { it.order }
        for (e in entries) removeStroke(e.stroke.id)
        return InkAction.Erased(pageId, entries)
    }

    /** Translate [ids] by ([dx], [dy]); each moved stroke's row is rewritten at the order it holds.
     *  Returns the undo action, or null when nothing moved. */
    fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? {
        val touched = translate(ids, dx, dy)
        if (touched.isEmpty()) return null
        return InkAction.Moved(pageId, touched, dx, dy)
    }

    // ── Undo / redo replay (Main, synchronous — the consumer navigates first and flushes after) ──

    /** Reverse [a] on this page. The caller has already landed on `a.pageId`. */
    fun revert(a: InkAction) {
        when (a) {
            is InkAction.Drew -> removeStroke(a.stroke.id)
            is InkAction.Erased -> for (e in a.entries) put(e.stroke, e.order)
            is InkAction.Moved -> translate(a.ids, -a.dx, -a.dy)
            is InkAction.Pasted -> for (s in a.strokes) removeStroke(s.id)
        }
    }

    /** Re-apply [a] — [revert]'s mirror. */
    fun reapply(a: InkAction) {
        when (a) {
            is InkAction.Drew -> addStroke(a.stroke)
            is InkAction.Erased -> for (e in a.entries) removeStroke(e.stroke.id)
            is InkAction.Moved -> translate(a.ids, a.dx, a.dy)
            is InkAction.Pasted -> addStrokesAt(a.strokes, a.orders)
        }
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write this page until it stays written. The op log is snapshotted and cleared *before* [exec]
     * runs, so a stroke that commits during the write re-dirties the page and takes another pass;
     * the guard bounds a pathological writer, and what it leaves behind the next debounce picks up.
     * A failure merges the snapshot back **under** anything recorded since (a newer entry for the
     * same stroke wins — it already describes the row's latest state) and rethrows; because every
     * statement is idempotent, the retry converges.
     *
     * [extraDirty] is the consumer's own unwritten state (the pad's page size); while it answers true
     * a pass runs even with an empty op log, and [exec] is where the consumer prepends its statements
     * — and restores its own flag if the write throws.
     *
     * [maxPasses] bounds the loop: the debounced save passes [MAX_FLUSH_PASSES] and, still dirty
     * past it, returns **false** with the leftover ops kept for the next debounce. A **leave** path —
     * a page swap, `onPause`, an exit — passes [UNBOUNDED] (the default): there is no next debounce
     * after a swap ([reset] forgets the log), so it runs until the page is clean. The loop cannot
     * spin: the only writer is the pen, every pass writes what it committed, and it ends the moment
     * the pen pauses.
     */
    suspend fun flushUntilClean(
        extraDirty: () -> Boolean = { false },
        maxPasses: Int = UNBOUNDED,
        exec: suspend (List<Statement>) -> Unit,
    ): Boolean {
        var pass = 0
        while (ops.isNotEmpty() || extraDirty()) {
            if (pass++ >= maxPasses) {
                Slog.d(tag) { "flush still dirty after $maxPasses passes — leaving it to the next save" }
                return false
            }
            val id = pageId
            val snapshot = LinkedHashMap(ops)
            val statements = ArrayList<Statement>(snapshot.size)
            for ((strokeId, op) in snapshot) {
                statements += when (op) {
                    is Op.Put -> sql.putStroke(id, op.order, op.stroke)
                    Op.Drop -> sql.dropStroke(strokeId)
                }
            }
            ops.clear()
            try {
                exec(statements)
            } catch (t: Throwable) {
                for ((strokeId, op) in snapshot) if (strokeId !in ops) ops[strokeId] = op
                throw t
            }
            Slog.d(tag) { "flushed ${statements.size} stroke statement(s)" }
        }
        return true
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** The next writing order on this page: the high-water mark (0 on a page that never held ink). */
    private fun nextOrder(): Long = highWater

    private fun put(stroke: Stroke, order: Long) {
        val previous = orders.put(stroke.id, order)
        if (previous != null && previous != order) page.remove(previous)
        page[order] = stroke
        highWater = maxOf(highWater, order + 1)
        ops[stroke.id] = Op.Put(stroke, order)
    }

    private fun removeStroke(id: String): Boolean {
        val order = orders.remove(id) ?: return false
        page.remove(order)
        // Always a Drop, never "forget the Put": a Put may be a move of a row that is already
        // stored, and dropping the entry would leave that row behind. DELETE tolerates the rest.
        ops[id] = Op.Drop
        return true
    }

    /** Translate what of [ids] is on this page; returns the ids actually moved. */
    private fun translate(ids: Collection<String>, dx: Float, dy: Float): List<String> {
        val moved = ArrayList<String>(ids.size)
        for (id in ids) {
            val order = orders[id] ?: continue
            val stroke = page[order] ?: continue
            put(stroke.translated(dx, dy), order)
            moved += id
        }
        return moved
    }

    companion object {
        /** The debounced save's pass bound — enough to outrun a hand that keeps writing; what it
         *  leaves behind, the next debounce picks up. */
        const val MAX_FLUSH_PASSES = 8

        /** No bound: a leave path's flush, which has no next debounce to leave anything to. */
        const val UNBOUNDED = Int.MAX_VALUE
    }
}
