package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.BoundedWait
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchHostBinder
import com.symmetricalpalmtree.notesproutsn.extension.SketchHostSession
import com.symmetricalpalmtree.notesproutsn.extension.SketchPageState
import com.symmetricalpalmtree.notesproutsn.extension.TransferCaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The notebook's half of the SKETCH seam (arc 43 / K4) — [DocumentHostHooks]' shape for pixels: the
 * four blocking hooks [SketchHostBinder] calls when the extension's sketch screen asks for state,
 * turns a page, pushes a save, or asks for the page's ink. Everything the `.soil` knows about a
 * sketch is read and written here, in the host, which is og's invariant 3 with a process boundary
 * enforcing it.
 *
 * **Threading.** Every method runs on the arbitrary pooled **Binder thread** the extension's call
 * arrived on — never Main — so the `runBlocking` each one opens over the suspending DAO work is
 * exactly the allowed case of it. A Binder transaction cannot be cancelled; the extension's own
 * call timeout is the only clock these run against.
 *
 * **A sealed session is never written to.** Every hook checks [alive] and [NotebookSession.isOpen]
 * first and throws `IllegalStateException` otherwise (one of the three that cross Binder intact).
 * A session that is merely **not open yet** is waited for, briefly and boundedly — see
 * [openSession], and [DocumentHostHooks.openSession] for the reconnect case that made that wait
 * necessary: a host killed behind a live face comes back with its `.soil` still opening while the
 * face's first call is already on a Binder thread, and refusing there would throw away the save the
 * reconnect exists to land.
 *
 * **The target is the host's memory of where the face is** (decision 7). The sketch screen turns
 * its own pages; the notebook underneath stays exactly where it was and catches up when the showing
 * ends. A [requestPage] moves [target] and nothing else does. It survives the host's death through
 * the screen's saved state ([restoreTarget]), and a target naming a page that is no longer in the
 * notebook falls back to the displayed one ([DocumentTargetRules.resolveTarget] — the same rule,
 * reused rather than re-written).
 *
 * **Pixels are never logged here**; the binder above logs the byte counts.
 */
class SketchHostHooks(
    /** The open session — read at call time, never captured: the screen may be on its way out. */
    private val notebook: () -> NotebookSession,
    /** The page whose strokes are on the paper (the R6 torn-read rule) — the **fallback** for the
     *  face's own target, which a turn moves and the notebook does not follow. */
    private val displayedPageId: () -> String,
    /** False once the screen's session has begun sealing (or the screen will never open one) —
     *  deliberately NOT `opened && !closing`: [openSession]'s wait runs while the session is still
     *  opening, and the face's teardown flush must land while the screen is closing but the seal
     *  has not started (flush-before-seal, the M4 invariant applied to pixels, where it matters
     *  more: a lost sketch has no other copy). */
    private val alive: () -> Boolean,
    /** Whether the session lateinit is constructed and open — [alive] says the screen still takes
     *  writes; this says the `.soil` is there to take them. */
    private val sessionOpen: () -> Boolean,
) : SketchHostBinder.Hooks {

    /**
     * Where the face is. `@Volatile`: written from Binder threads (a page turn) and from Main (the
     * saved-state restore, the reset at the end of a showing), read from both.
     */
    @Volatile
    private var target: String? = null

    /** The page the face is on — the screen's saved state, its catch-up on close, and the page the
     *  seal's cover is drawn from (decision 6). */
    val targetPageId: String? get() = target

    /** Hand back what saved state carried, **before** the reconnect's `begin` can ask for state
     *  (`onCreate`): a host killed behind the face must come back pointing at the page the face is
     *  showing, not at the page the notebook happens to be on. */
    fun restoreTarget(pageId: String?) {
        target = pageId
    }

    /** The showing is over: the next one starts from the displayed page again. */
    fun resetTarget() {
        target = null
    }

    /**
     * The current target page's state, with its stored PNG parked in the read window.
     *
     * `setWindow` is the last thing done and its answer is the state's `sketchChunks`: window and
     * state are loaded together, which is the contract's atomicity.
     */
    override fun loadCurrent(session: SketchHostSession): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val pages = nb.pages
            val pageIds = pages.map { it.id }
            val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
            val index = pageIds.indexOf(pageId)
            check(index >= 0) { "page is not in the notebook" }
            target = pageId
            state(session, nb, index)
        }
    }

    /**
     * Turn the target one page in [direction] and load the window with that page's pixels.
     *
     * **At either edge the same page is answered, unchanged** — the contract's rule, and the reason
     * this hook has no null: a turn is never an exception and never a null the screen has to word.
     * Anything that fails on the way is a genuine failure and crosses as one; there is nothing to
     * "stay put" on the way to, because the window is only touched once the page has been found.
     */
    override fun requestPage(session: SketchHostSession, direction: Int): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val pages = nb.pages
            val pageIds = pages.map { it.id }
            val from = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
            val index = pageIds.indexOf(from)
            check(index >= 0) { "page is not in the notebook" }
            // null = no page that way: the edge, where the same page is the answer.
            val newIndex = DocumentTargetRules.flipIndex(index, direction, pages.size) ?: index
            val state = state(session, nb, newIndex)
            target = pages[newIndex].id
            state
        }
    }

    /**
     * A completed save, straight into the `.soil` through the session's one serial writer
     * ([NotebookSession.writeSketch] — see its KDoc for why the write is drained **and awaited
     * exceptionally**: the extension's `saveSketchChunk` returning is the screen's only "it
     * landed", so a failure reported as success would drop a drawing that has no other copy).
     *
     * The page size is the notebook's own, never anything the extension said, and the two typed
     * refusals ([SketchContract.SKETCH_TOO_LARGE] / [SketchContract.SKETCH_BAD_PNG]) come back out
     * of the repository with nothing written. A key naming a page this notebook no longer has is
     * an `IllegalArgumentException` from `writeSketch` — which is the structural half of "a save is
     * accepted for any **live** page".
     */
    override fun commit(commit: SketchHostSession.Commit) = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            nb.writeSketch(commit.pageKey, commit.png)
        }
    }

    /**
     * "Bring in ink" (decision 8) — the page's **bare** strokes staged in the ink window, and the
     * chunk count the screen loops on. Zero is a legal answer and the only one for a page with no
     * bare ink; the screen words its own "No ink on this page" from the count.
     *
     * **Bare is structural, not a filter.** A link's strokes are re-parented to the link row and a
     * sticky's ink lives inside the sticky, so the page's own stroke children *are* exactly the
     * bare ink ([StrokeStore.loadPage] reads that one parent). Nothing here has to know what a link
     * is, which is the only way this can stay true when a later arc adds another composite.
     *
     * **The writer is drained first.** Ink committed a moment ago is a queued row, and a bake that
     * read the file before the queue emptied would quietly leave out the last stroke drawn — the
     * one the person most likely meant to bring in.
     *
     * A page dense past the transfer caps is **cut, not refused**: the prefix that fits crosses and
     * the rest is logged. The pad's Send refuses instead, because there the user chose a selection
     * and can choose a smaller one; here the alternative to a partial bake is no bake at all.
     */
    override fun requestInk(session: SketchHostSession, pageKey: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            require(nb.pages.any { it.id == pageKey }) { "Unknown page" }
            nb.store.drain()
            val strokes = nb.store.loadPage(pageKey)
            val fitted = capped(strokes)
            val chunks = TransferCaps.chunk(TransferCaps.toWireStrokes(fitted))
            val count = session.setInkWindow(chunks)
            Slog.d(TAG) { "requestInk: ${fitted.size} bare stroke(s) staged in $count chunk(s)" }
            count
        }
    }

    /** The prefix of [strokes], in writing order, that fits the transfer caps — the whole list when
     *  it already does. Writing order is kept because the bake composites them as drawn. */
    private fun capped(strokes: List<Stroke>): List<Stroke> {
        if (TransferCaps.withinLimits(strokes.size, TransferCaps.pointCount(strokes))) return strokes
        val out = ArrayList<Stroke>(strokes.size)
        var points = 0
        for (s in strokes) {
            if (out.size >= ExtensionContract.MAX_TRANSFER_STROKES) break
            if (points + s.points.size > ExtensionContract.MAX_TRANSFER_POINTS) break
            out += s
            points += s.points.size
        }
        Slog.d(TAG) { "requestInk: page cut to ${out.size} of ${strokes.size} stroke(s) at the transfer caps" }
        return out
    }

    /**
     * The one place a [SketchPageState] is built, so the two window-loading hooks cannot drift on
     * the page facts. [SketchHostSession.setWindow] runs here and its answer is the state's
     * `sketchChunks` — the window and the state that describes it are loaded together, which is the
     * contract's atomicity.
     */
    private suspend fun state(session: SketchHostSession, nb: NotebookSession, index: Int): SketchPageState {
        val page = nb.pages[index]
        // Null for a page with no sketch AND for a stored row the header guard refused (which it
        // soft-deletes on the way past) — the screen starts from blank paper either way.
        val png = nb.readSketch(page.id) ?: ByteArray(0)
        val chunks = session.setWindow(page.id, png)
        return SketchPageState(
            pageKey = page.id,
            pageIndex = index,
            pageCount = nb.pages.size,
            width = page.width,
            height = page.height,
            sketchBytes = png.size,
            sketchChunks = chunks,
        )
    }

    /**
     * The open session, or the one marshalable refusal — never a write onto a sealed session. See
     * [DocumentHostHooks]' twin for the whole argument; the shape is deliberately identical, and
     * the numbers are too.
     */
    private fun openSession(): NotebookSession {
        if (!ready()) {
            check(alive()) { "notebook closed" }
            BoundedWait.until(OPEN_WAIT_MS, OPEN_POLL_MS) { !alive() || sessionOpen() }
            check(ready()) { "notebook closed" }
        }
        return notebook()
    }

    /** [sessionOpen], never `notebook().isOpen`: the session is `lateinit` on the screen and only
     *  the screen knows whether it has been constructed yet. */
    private fun ready(): Boolean = alive() && sessionOpen()

    private companion object {
        const val TAG = "SketchHostHooks"

        /** The whole wait, end to end — [DocumentHostHooks]' number, inside both clocks that bound
         *  this call (the extension's retry window and the client's 15 s `end()` timeout). */
        const val OPEN_WAIT_MS = 8_000L

        /** One poll step. */
        const val OPEN_POLL_MS = 200L
    }
}
