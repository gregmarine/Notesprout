package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.BoundedWait
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentHostBinder
import com.symmetricalpalmtree.notesproutsn.extension.DocumentHostSession
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The notebook's half of the DOCUMENT_EDITOR seam (arc 19 / M3) — the two blocking hooks
 * [DocumentHostBinder] calls when the extension's editor asks for state or pushes a save.
 * Everything the `.soil` knows about a document is read and written here, in the host, which is
 * og's invariant 3 with a process boundary now enforcing it.
 *
 * **Threading.** Both methods run on the arbitrary pooled **Binder thread** the extension's call
 * arrived on — never Main — so the `runBlocking` each one opens over the suspending DAO work is
 * exactly the allowed case of it. A Binder transaction cannot be cancelled; the extension's own
 * call timeout is the only clock these run against.
 *
 * **A sealed session is never written to.** Both hooks check [alive] and [NotebookSession.isOpen]
 * first and throw `IllegalStateException` otherwise (one of the three that cross Binder intact) —
 * the host may have been closing behind the editor, and a write onto a sealed session would be
 * lost at best. A session that is merely **not open yet** is waited for, briefly and boundedly —
 * see [openSession] for why the reconnect path needs that.
 *
 * Document text is never logged here; the binder above logs the counts.
 */
class DocumentHostHooks(
    /** The open session — read at call time, never captured: the screen may be on its way out. */
    private val notebook: () -> NotebookSession,
    /** The page whose strokes are on the paper (the R6 torn-read rule): what the user is looking
     *  at is the page the document belongs to, never `currentIndex` read off IO. */
    private val displayedPageId: () -> String,
    /** The notebook's display name for the editor's header — display only, never a path. */
    private val notebookName: () -> String,
    /** False once the screen is finishing/closing — see the class doc. */
    private val alive: () -> Boolean,
) : DocumentHostBinder.Hooks {

    /**
     * The current target's state, with its text parked in the read window ([DocumentHostSession] is
     * the window). M3's target is always the displayed **page** — [DocumentContract.SCOPE_NOTEBOOK]
     * is M7's and [DocumentPageState.textDocument] is M8's.
     *
     * The source strip's state is the staleness comparison M2 built: no row (or a row that was
     * never drafted, `srcUpdatedAt == null`) is [DocumentContract.SOURCE_NONE]; otherwise the page's
     * content watermark now against the one parked at the draft — greater means the page has moved
     * on ([DocumentContract.SOURCE_STALE]), equal or less means the draft still describes it.
     *
     * `setWindow` is the last thing done and its answer is the state's `textChunks`: window and
     * state are loaded together, which is the contract's atomicity. It also enforces
     * [DocumentContract.MAX_DOCUMENT_CHARS] — a stored document over the cap throws here, `begin`'s
     * `current()` never answers, and the entry button explains that the editor could not be opened.
     * That is the whole cap story at M3, and it needs no separate pre-check.
     */
    override fun loadCurrent(session: DocumentHostSession): DocumentPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val pageId = displayedPageId()
            val pages = nb.pages
            val index = pages.indexOfFirst { it.id == pageId }
            check(index >= 0) { "page is not in the notebook" }
            val doc = nb.documents.get(pageId)
            val text = doc?.text.orEmpty()
            val watermark = doc?.srcUpdatedAt
            val source = when {
                watermark == null -> DocumentContract.SOURCE_NONE
                nb.db.documentDao().maxContentUpdatedAt(pageId) > watermark -> DocumentContract.SOURCE_STALE
                else -> DocumentContract.SOURCE_DRAFTED
            }
            val chunks = session.setWindow(pageId, text)
            DocumentPageState(
                pageKey = pageId,
                scope = DocumentContract.SCOPE_PAGE,
                pageIndex = index,
                pageCount = pages.size,
                // Truncated rather than refused: a name too long for the header is a display
                // problem, and failing the whole open over one would be absurd.
                title = notebookName().take(DocumentContract.MAX_TITLE_CHARS),
                // M3 always says false. Nothing can set the flag yet — the create screen's type
                // radio is M8's, and M8 is what wires the real bit
                // (`NotebookFlags.TEXT_DOCUMENT` / `notebook_meta.textDocument`) through to here.
                textDocument = false,
                source = source,
                textChars = text.length,
                textChunks = chunks,
            )
        }
    }

    /**
     * A completed save, straight into the `.soil` through the session's one serial writer
     * ([NotebookSession.writeDocument] — see its KDoc for why the write is drained rather than
     * fire-and-forget). `draftWatermark` non-null is a seed/merge anchoring itself (M6/M7); M3's
     * editor only ever sends ordinary edits, and an ordinary edit can never invent a watermark —
     * the accumulator refuses a drafted commit with nothing parked.
     */
    override fun commit(commit: DocumentHostSession.Commit) = runBlocking {
        withContext(Dispatchers.IO) {
            openSession().writeDocument(commit.pageKey, commit.text, commit.draftWatermark)
        }
    }

    /**
     * The open session, or the one marshalable refusal — never a write onto a sealed session.
     *
     * **Why this waits (M4).** The host process can be killed or config-destroyed while the
     * extension's editor is up; the recreated screen re-opens the client without launching
     * anything, and the fresh `begin` is the extension's signal to flush the text it still holds.
     * That `current()`/`saveChunk` lands on a Binder thread within milliseconds — while this
     * screen's own `openSession()` is still on IO opening the `.soil`. Refusing there would throw
     * away the one save the reconnect exists to land, so we wait for it instead: the same
     * pendingDocumentFlush staging og does inside one process, in its two-process form.
     *
     * [OPEN_WAIT_MS] sits inside both clocks that bound this call — the extension's own retry
     * window and the client's 15 s `end()` timeout — so a wait that runs its full length still
     * answers before anything above it gives up. Blocking is allowed here (see the class doc), and
     * the happy path pays nothing: the check runs before any sleep.
     *
     * [alive] false is **not** waited on. A screen that is finishing or destroyed never opens its
     * DB again, so there is nothing to wait for; the refusal is immediate.
     */
    private fun openSession(): NotebookSession {
        if (!ready()) {
            check(alive()) { "notebook closed" }
            BoundedWait.until(OPEN_WAIT_MS, OPEN_POLL_MS) { !alive() || notebook().isOpen }
            check(ready()) { "notebook closed" }
        }
        return notebook()
    }

    /** [alive] first, always: the session is `lateinit` on the screen and only [alive] knows
     *  whether it has been constructed yet. */
    private fun ready(): Boolean = alive() && notebook().isOpen

    private companion object {
        /** The whole wait, end to end. */
        const val OPEN_WAIT_MS = 8_000L

        /** One poll step. Long enough to cost nothing, short enough that a save landing just after
         *  the open does not sit visibly waiting. */
        const val OPEN_POLL_MS = 200L
    }
}
