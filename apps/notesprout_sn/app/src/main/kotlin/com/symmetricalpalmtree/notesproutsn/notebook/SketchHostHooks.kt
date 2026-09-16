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
 * **The two undo histories are one history.** A page the face inserts or deletes is recorded on the
 * **notebook's** stack ([onStructural]) and never on the face's, because a page is the notebook's
 * object and undoing one after Show pages must work. But the person holding the pen is looking at
 * the face, and the user's follow-up decision (2026-09-15) is that the face's own undo gesture has
 * to reverse a delete too — so this class keeps a small **ledger** of the showing's structural
 * edits, names each one with a token the face carries in its history, and lets the face ask for that
 * edit back ([undoPage] / [redoPage]). Each replay runs the notebook's own reconcile arm and then
 * moves the matching entry across the notebook's stack ([onStructuralUndone] /
 * [onStructuralRedone]), so the two stacks are provably in step rather than merely likely to be.
 * The ledger dies with the showing ([resetTarget]): from then on the notebook's own undo owns it.
 *
 * **The target is the host's memory of where the face is** (decision 7, as K5b amended it). The
 * sketch screen turns — and, since K5b, inserts and deletes — its own pages; the notebook underneath
 * stays exactly where it was and catches up when the showing ends. [requestPage], [insertPage] and
 * [deletePage] move [target] and nothing else does. It survives the host's death through
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
    /**
     * K5b: a page was inserted or deleted from the face — record it on the **notebook's own** undo
     * stack ([NotebookUndo.Action.Page]) and mark the notes index structural, exactly as
     * `NotebookActivity.doInsert` / `doDelete` do for a page the notebook itself changed.
     *
     * Called from the **Binder thread** the structural hook is running on; the implementation posts
     * to Main, because the undo stack and the notes sync belong to the screen. It is deliberately
     * fire-and-forget: the extension's insert has already happened in the `.soil` by the time this
     * runs, and making a Binder transaction wait on the host's main thread would be the ANR this
     * whole class is built to avoid.
     */
    private val onStructural: (NotebookSession.Structural) -> Unit,
    /**
     * K5b: the face's undo gesture took one of those back — take the matching
     * [NotebookUndo.Action.Page] off the **notebook's** undo stack and put it on its redo stack, so
     * the notebook's own history says what the file says.
     *
     * Called from the Binder thread, posted to Main like [onStructural], and for the same reason.
     * The implementation checks that the entry it pops really is this snapshot before moving it: the
     * canvas is stopped behind the face so nothing else can be on top, and a check that can only
     * fail if that stops being true is exactly the check worth having.
     */
    private val onStructuralUndone: (NotebookSession.Structural) -> Unit,
    /** K5b: [onStructuralUndone]'s mirror — the entry comes off the notebook's redo stack and goes
     *  back onto its undo stack. */
    private val onStructuralRedone: (NotebookSession.Structural) -> Unit,
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

    /** The showing is over: the next one starts from the displayed page again, the page list is
     *  whatever the face left it as, and the structural ledger goes — a showing's page history dies
     *  with the showing, and from here the notebook's own undo stack owns every one of those edits
     *  (which is where they have been all along). */
    fun resetTarget() {
        target = null
        structuralChanged = false
        synchronized(ledger) {
            undoable.clear()
            redoable.clear()
        }
    }

    /**
     * K5b: whether the face inserted or deleted a page during this showing — read by
     * `NotebookActivity.sketchShowingEnded`, which must then reload the canvas **even when the face
     * ended on the page the canvas is already showing**: the page list under it has changed (count,
     * ordinals, possibly the displayed page itself is gone), and the ordinary catch-up's
     * same-page shortcut would leave a stale pager and a stale Contents behind.
     *
     * `@Volatile`: set on a Binder thread, read on Main.
     */
    @Volatile
    var structuralChanged: Boolean = false
        private set

    // ── The structural ledger (K5b) ────────────────────────────────────────

    /**
     * The showing's page edits, newest last, by the token the face carries: [undoable] holds the
     * ones that can be taken back, [redoable] the ones that have been. An edit is in exactly one of
     * them, or in neither once the showing ends.
     *
     * **Snapshots, not ids** — [NotebookSession.Structural] is the notebook's own undo record and is
     * exactly what the reconcile arms take, so the face's undo runs the notebook's undo rather than
     * something that resembles it.
     *
     * Guarded by [ledger] rather than left to chance: every touch happens on whichever pooled Binder
     * thread the face's call arrived on, and two calls in a showing are not guaranteed to be the
     * same thread even though the face serialises them.
     */
    private val ledger = Any()
    private val undoable = LinkedHashMap<String, NotebookSession.Structural>()
    private val redoable = LinkedHashMap<String, NotebookSession.Structural>()
    private var seq = 0

    /**
     * Name a fresh structural edit and file it as undoable.
     *
     * **Recording a new edit clears the redo side**, exactly as `UndoRedoStack.record` does: an
     * insert made after an undo forks the history, and the branch that was abandoned must not still
     * be reachable. The face's own stack does the same thing to its own entries in the same moment.
     *
     * The one place the two sides can differ, written down rather than chased: a **pixel** edit
     * clears the face's redo side and this stack knows nothing about it, so a page insert the face
     * has undone and then walked away from can still be redone from the notebook afterwards. That is
     * loose, not wrong — a redo of an insert brings the page back with everything it held — and the
     * alternative is a Binder call on every mark, which is the one thing that must never be in the
     * path of the pen. The next structural edit clears it either way.
     */
    private fun mint(snap: NotebookSession.Structural): String = synchronized(ledger) {
        val token = "s${++seq}"
        redoable.clear()
        undoable[token] = snap
        while (undoable.size > MAX_LEDGER) undoable.remove(undoable.keys.first())
        token
    }

    /** Take [token] off [from], or refuse — an edit can be taken back exactly once, and a token this
     *  showing never minted (or has already moved) is the caller's mistake, not a failure. */
    private fun claim(from: LinkedHashMap<String, NotebookSession.Structural>, token: String) =
        synchronized(ledger) { from.remove(token) } ?: throw IllegalArgumentException("Unknown edit")

    /** File a claimed edit on the other side, **after** its reconcile landed: a ledger that said an
     *  edit was reversible while the file disagreed would be worse than one that lost it. */
    private fun file(
        to: LinkedHashMap<String, NotebookSession.Structural>,
        token: String,
        snap: NotebookSession.Structural,
    ) = synchronized(ledger) {
        to[token] = snap
        while (to.size > MAX_LEDGER) to.remove(to.keys.first())
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

    // ── Page structure (K5b) ───────────────────────────────────────────────

    /**
     * Insert a blank page next to **the face's target** and land the target on it (K5b).
     *
     * Next to the *target*, not next to the notebook's displayed page: the face turns its own pages
     * and the notebook has not followed. [NotebookSession.insertBlank] works relative to the
     * session's current index, so the session is walked to the target first ([NotebookSession.goTo]
     * — a pure move, no structure) and the insert happens there.
     *
     * **The notebook's own undo stack owns it.** [onStructural] records the snapshot as
     * `NotebookUndo.Action.Page` exactly as `doInsert` does, so after Show pages the notebook's undo
     * reverses a page the face made — the whole reason this is a host call rather than something the
     * extension could do for itself. What is deliberately **not** reproduced from `doInsert` is the
     * `navigateTo`: there is no canvas to repaint (it is released behind the face), and the catch-up
     * at the end of the showing is what puts the paper back.
     */
    override fun insertPage(session: SketchHostSession, direction: Int): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            nb.goTo(targetIndex(nb))
            val snap = nb.insertBlank(after = direction == SketchContract.PAGE_NEXT)
            val token = mint(snap)
            structuralChanged = true
            onStructural(snap)
            target = nb.currentPage.id
            Slog.d(TAG) { "insertPage($token): ${nb.pages.size} pages, target at ${nb.currentIndex}" }
            state(session, nb, nb.currentIndex, token)
        }
    }

    /**
     * Soft-delete the face's target page and answer the page the notebook lands on (K5b).
     *
     * [pageKey] **must be the target** — an `IllegalArgumentException` otherwise. The face is showing
     * one page and the confirm it just ran named that page; a delete of anything else would be a
     * page destroyed at a distance, which is exactly the class of bug the save path's key check
     * exists to prevent.
     *
     * **The writer is drained first**, `doDelete`'s reason verbatim: a stroke commit still queued
     * would land *after* the delete's descendant snapshot and transaction, leaving a live orphan row
     * under a soft-deleted page that neither the snapshot nor redo's reconcile knows about.
     *
     * Deleting the only page of a notebook puts a **fresh blank page** in its place
     * ([NotebookSession.deleteCurrent]'s own rule) — the face simply loads whatever comes back.
     */
    override fun deletePage(session: SketchHostSession, pageKey: String): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val index = targetIndex(nb)
            require(nb.pages[index].id == pageKey) { "not the target page" }
            nb.goTo(index)
            nb.store.drain()
            val snap = nb.deleteCurrent()
            val token = mint(snap)
            structuralChanged = true
            onStructural(snap)
            target = nb.currentPage.id
            Slog.d(TAG) { "deletePage($token): ${nb.pages.size} pages, target at ${nb.currentIndex}" }
            state(session, nb, nb.currentIndex, token)
        }
    }

    /**
     * What else the live page [pageKey] names is carrying (K5b) — the two bits the delete confirm
     * words itself from, and nothing more.
     *
     * `SoilDao.liveErasableIds` is the ink bit's source deliberately: it is already "every live
     * descendant of the page **except** its sketch", which is exactly the question here — the face
     * is showing the sketch, so warning that it will go would be telling someone their drawing is
     * about to be deleted while they are looking at it.
     *
     * `documentFor` carries `deletedAt IS NULL` in its own SQL, so a non-null row is a live one.
     * The writer is drained first for [requestInk]'s reason: content committed a moment ago is a
     * queued row, and a confirm that said "nothing else here" about a page that has ink would be
     * wrong in the one direction that costs something.
     */
    override fun pageContent(pageKey: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            require(nb.pages.any { it.id == pageKey }) { "Unknown page" }
            nb.store.drain()
            var bits = 0
            if (nb.db.dao().liveErasableIds(pageKey).isNotEmpty()) bits = bits or SketchContract.PAGE_HAS_INK
            if (nb.db.documentDao().documentFor(pageKey) != null) bits = bits or SketchContract.PAGE_HAS_DOCUMENT
            bits
        }
    }

    /**
     * Take back the page insert or delete [token] names (K5b) — **the notebook's own undo, run from
     * the face**, so the user never has to leave for the notebook to put back a page they deleted.
     *
     * The body is `NotebookActivity`'s `is Action.Page ->` revert arm verbatim:
     * `reconcile(before, objectIds, emptyList(), beforeCurrentId)` — the page list goes back to what
     * it was, the rows the delete soft-deleted (its strokes, its objects, its document **and** its
     * sketch) come back with it, and the notebook lands where it was standing. One arm, one
     * behaviour, whichever screen asked.
     *
     * **The writer is drained first**, `deletePage`'s reason in the other direction: a stroke commit
     * still queued would land after the reconcile's transaction and sit on a page the reconcile has
     * just soft-deleted.
     *
     * The ledger is moved **after** the reconcile lands, and the notebook's own stack after that
     * ([onStructuralUndone]) — a failure anywhere leaves both saying what the file says.
     */
    override fun undoPage(session: SketchHostSession, token: String): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val snap = claim(undoable, token)
            nb.store.drain()
            nb.reconcile(snap.before, snap.objectIds, emptyList(), snap.beforeCurrentId)
            file(redoable, token, snap)
            structuralChanged = true
            onStructuralUndone(snap)
            target = nb.currentPage.id
            Slog.d(TAG) { "undoPage($token): ${nb.pages.size} pages, target at ${nb.currentIndex}" }
            state(session, nb, nb.currentIndex)
        }
    }

    /**
     * Put back the page insert or delete [token] names (K5b) — [undoPage]'s mirror, and
     * `NotebookActivity`'s `is Action.Page ->` **reapply** arm verbatim:
     * `reconcile(after, emptyList(), objectIds, afterCurrentId)`. The two directions are the same
     * call with the snapshot's sides swapped, which is what [NotebookSession.reconcile] exists for.
     */
    override fun redoPage(session: SketchHostSession, token: String): SketchPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val snap = claim(redoable, token)
            nb.store.drain()
            nb.reconcile(snap.after, emptyList(), snap.objectIds, snap.afterCurrentId)
            file(undoable, token, snap)
            structuralChanged = true
            onStructuralRedone(snap)
            target = nb.currentPage.id
            Slog.d(TAG) { "redoPage($token): ${nb.pages.size} pages, target at ${nb.currentIndex}" }
            state(session, nb, nb.currentIndex)
        }
    }

    /** Where the face is, as an index into [NotebookSession.pages] — the same resolve the two
     *  window-loading hooks run, so a target that has vanished falls back to the displayed page
     *  rather than throwing at a structural door. */
    private fun targetIndex(nb: NotebookSession): Int {
        val pageIds = nb.pages.map { it.id }
        val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
        val index = pageIds.indexOf(pageId)
        check(index >= 0) { "page is not in the notebook" }
        return index
    }

    /**
     * The one place a [SketchPageState] is built, so the hooks that answer with one cannot drift on
     * the page facts. [SketchHostSession.setWindow] runs here and its answer is the state's
     * `sketchChunks` — the window and the state that describes it are loaded together, which is the
     * contract's atomicity.
     *
     * [structuralToken] is empty for every answer but the two that **make** a structural edit
     * (K5b): a turn names no edit, and neither does a replay of one.
     */
    private suspend fun state(
        session: SketchHostSession,
        nb: NotebookSession,
        index: Int,
        structuralToken: String = "",
    ): SketchPageState {
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
            structuralToken = structuralToken,
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

        /**
         * Most structural edits either side of the ledger holds (K5b). It mirrors `UndoRedoStack`'s
         * own 100-entry bound: neither the face's history nor the notebook's stack can hold more
         * than that, so an edit beyond it is one no gesture can still reach. The eldest goes, which
         * is the end nothing is reaching for — and an edit that has fallen off is simply "Unknown
         * edit", which the face already knows how to take.
         */
        const val MAX_LEDGER = 100
    }
}
