package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.ink.InkPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The calendar's page — in memory, over [CalendarStore] (arc 23 / Y1). The screen owns the paper and
 * the chrome; this owns *which* page is showing (a [CalendarTarget]), whether its rows exist yet,
 * and its size; **what is on the page** — the strokes, the op log, the re-flush rule and the four
 * stroke-level replays — is `:ext-ink`'s [InkDocument], shared with the pad so the two never drift.
 *
 * Since arc 24 / Z4 it also carries the showing page's **[marks]** — the events the grid draws,
 * read through a [MarkSource] in the same IO hop as the strokes, because they are as much part of
 * "which page is showing" as its size is. They are read, never written: the events screen owns
 * every write.
 *
 * **Rows are minted on the first stroke, never on open.** [show] reads what is there and writes
 * nothing; a page with no row is shown blank at the surface's size with ids minted in memory. The
 * flush that carries the page's first `Put` leads with the two `INSERT OR IGNORE`s
 * ([CalendarStore.mintRows]) — a flush that is nothing but `DELETE`s (a stroke drawn and undone
 * before the debounce) mints nothing, because there is nothing to keep. A page that exists at
 * `0 × 0` (a placement minted it before any screen saw it) learns the surface's size the way the
 * pad's does: one `UPDATE`, ahead of the strokes, put back if that write fails.
 *
 * [show] reads the target page **first** and flushes the departing one **second**, so the swap
 * itself has no suspension point for a commit to fall into (the pad's rule). Every page shown is
 * remembered by its id, so an undo action recorded on another page can navigate back to it.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move]) are synchronous
 * and run on Main, straight out of the g-paper callbacks; everything that reaches the store is
 * `suspend` and hops to [Dispatchers.IO] for the store call itself. The screen serialises the
 * suspending half behind one mutex.
 */
class CalendarDocument(
    private val store: CalendarStore,
    /** Where the showing page's [DayMark]s come from — [EventStore] in the app, a fake in tests.
     *  Held under its own name because the *loaded* marks are the public [marks] property. */
    marks: MarkSource,
    /** The paper surface in px — the size a page with no recorded size of its own takes. */
    private val surfaceSize: () -> Pair<Float, Float>,
) : InkPage {

    private val ink = InkDocument(CalendarSql, TAG)

    private val markSource = marks

    /**
     * The showing page's events, by day (arc 24 / Z4) — **empty before the first [show]**, and
     * read in the same IO hop as the page's strokes, so a page and its marks are never one
     * navigation apart. [CalendarActivity] bakes them into the page's template and compares them
     * structurally in its bake key.
     */
    var marks: Map<LocalDate, List<DayMark>> = emptyMap()
        private set

    /** The page showing. Set by the first [show]; the screen never asks before it. */
    lateinit var target: CalendarTarget
        private set

    val isOpen: Boolean get() = ::target.isInitialized

    /** The showing page's id — read from its row, or minted in memory for a page with none. */
    override val pageId: String get() = ink.pageId

    /** A fresh id for the period row, used only if no period row exists at flush time. */
    private var periodId: String = ""

    /** Whether the page row exists in the store (minted by a flush, a placement, or an earlier showing). */
    private var pageMinted = false

    /** The page's own width/height is unwritten (a minted `0 × 0` page just learned it). */
    private var sizeDirty = false

    override var pageWidth: Float = 0f
        private set
    override var pageHeight: Float = 0f
        private set

    /** Every page this showing has put on the paper, by id — where an undo entry's page is. */
    private val targetsByPage = HashMap<String, CalendarTarget>()

    override val strokes: List<Stroke> get() = ink.strokes
    val hasUnsavedChanges: Boolean get() = ink.hasUnsavedChanges || sizeDirty

    /** The order [id] sits at on the showing page, or null if it is not on it. */
    fun orderOf(id: String): Long? = ink.orderOf(id)

    // ── Showing ──────────────────────────────────────────────────────────────

    /**
     * Show [next]. The target's page **and its marks** are read in one IO hop **before** the
     * departing page is flushed, and the bookmark is written **before** the in-memory swap: every
     * store round-trip a show makes comes first, so a show that throws leaves the document — and
     * with it the paper and the organizer — exactly where it was. That includes [marks], which is
     * assigned in the swap and nowhere else. (A bookmark that names a page the swap then fails to
     * reach cannot happen: the swap is memory only.)
     *
     * Returns without a store round-trip when [next] is already showing — **unless** [refreshMarks]
     * says to re-read them, and then it is one hop that reads the marks alone: no page read, no
     * flush, no bookmark. That path exists for the way back from the **events screen**, which is
     * the one thing in this app that changes what a page's marks are while the page itself has not
     * moved: an event added, edited or deleted there must show on the grid the calendar comes back
     * to, and the return usually lands on the very page the person left.
     */
    suspend fun show(next: CalendarTarget, refreshMarks: Boolean = false) {
        if (isOpen && next == target) {
            if (!refreshMarks) return
            marks = withContext(Dispatchers.IO) { readMarks(next) }
            return
        }
        val (stored, fresh) = withContext(Dispatchers.IO) { store.readPage(next) to readMarks(next) }
        if (isOpen) flushUntilClean()
        withContext(Dispatchers.IO) { store.saveState(next) }
        target = next
        marks = fresh
        periodId = stored.periodId ?: CalendarStore.newId()
        pageMinted = stored.pageId != null
        val id = stored.pageId ?: CalendarStore.newId()
        ink.reset(id, stored.strokes)
        targetsByPage[id] = next
        sizeDirty = false
        pageWidth = stored.width
        pageHeight = stored.height
        if (pageWidth <= 0f || pageHeight <= 0f) {
            val (w, h) = surfaceSize()
            if (w > 0f && h > 0f) {
                pageWidth = w
                pageHeight = h
                // An existing row at 0 × 0 owes the store its size; a page with no row yet carries
                // it in the mint that comes with its first stroke.
                sizeDirty = pageMinted
            }
        }
    }

    /** The marks a page of [t] shows — the range is [GridMarks]', never guessed here. Blocking. */
    private fun readMarks(t: CalendarTarget): Map<LocalDate, List<DayMark>> {
        val (from, to) = GridMarks.rangeOf(t)
        return markSource.marksFor(from, to)
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /** Take one committed stroke, at the end of the page's writing order. */
    override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)

    /** Translate [ids] by ([dx], [dy]). Returns the undo action, or null when nothing moved. */
    override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write the showing page until it stays written ([InkDocument.flushUntilClean]). The page's rows
     * are minted ahead of the first pass that puts a stroke; a `0 × 0` row's size leads when it is
     * owed; the page's `updatedAt` follows any stroke write. One batch, one transaction, in that
     * order — the stroke rows can only land under a page row that exists.
     */
    override suspend fun flushUntilClean(maxPasses: Int): Boolean =
        ink.flushUntilClean(extraDirty = { sizeDirty }, maxPasses = maxPasses) { statements -> write(statements) }

    /** The statements a page's [strokes] flush needs around them — pure, so the shape is JVM-tested. */
    fun statementsFor(strokeStatements: List<Statement>, now: Long): List<Statement> {
        val puts = strokeStatements.any { it.sql.startsWith("INSERT") }
        val out = ArrayList<Statement>(strokeStatements.size + 3)
        if (!pageMinted && puts) {
            out += store.mintRows(target, periodId, pageId, pageWidth, pageHeight, now)
        } else if (pageMinted && sizeDirty) {
            out += CalendarSql.sizePage(pageId, pageWidth, pageHeight, now)
        }
        out += strokeStatements
        if (strokeStatements.isNotEmpty() && (pageMinted || puts)) out += CalendarSql.touchPage(pageId, now)
        return out
    }

    private suspend fun write(strokeStatements: List<Statement>) {
        val mintedBefore = pageMinted
        val sizeBefore = sizeDirty
        val puts = strokeStatements.any { it.sql.startsWith("INSERT") }
        val all = statementsFor(strokeStatements, System.currentTimeMillis())
        sizeDirty = false
        if (all.isEmpty()) return
        try {
            withContext(Dispatchers.IO) { store.execAll(all) }
        } catch (t: Throwable) {
            sizeDirty = sizeDirty || sizeBefore
            pageMinted = mintedBefore
            throw t
        }
        if (puts) pageMinted = true
    }

    // ── Undo / redo replay ───────────────────────────────────────────────────

    /**
     * Reverse [a]. A replay lands the document on the action's page first — an action names its
     * page, and this showing remembers every page it put on the paper — and leaves the store
     * written, so what the screen reloads afterwards is what a reopen would show. Returns false when
     * the page cannot be found (never for an action this showing recorded).
     */
    suspend fun revert(a: InkAction): Boolean {
        if (!landOn(a.pageId)) return false
        ink.revert(a)
        flushUntilClean()
        return true
    }

    /** Re-apply [a] — [revert]'s mirror. */
    suspend fun reapply(a: InkAction): Boolean {
        if (!landOn(a.pageId)) return false
        ink.reapply(a)
        flushUntilClean()
        return true
    }

    private suspend fun landOn(id: String): Boolean {
        if (id == pageId) return true
        val t = targetsByPage[id]
        if (t == null) {
            Slog.d(TAG) { "replay skipped: page $id was not shown this showing" }
            return false
        }
        show(t)
        // A page with no row keeps the id it was minted with only while it is showing; a second
        // showing mints another. The action's strokes are still what they are, so land the replay
        // on the page that is showing now.
        return true
    }

    private companion object {
        const val TAG = "CalendarDocument"
    }
}
