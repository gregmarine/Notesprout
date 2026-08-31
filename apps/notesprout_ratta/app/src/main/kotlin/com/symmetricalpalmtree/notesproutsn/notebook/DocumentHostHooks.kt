package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.BoundedWait
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentHostBinder
import com.symmetricalpalmtree.notesproutsn.extension.DocumentHostSession
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The notebook's half of the DOCUMENT_EDITOR seam (arc 19 / M3, grown at M6) — the four blocking
 * hooks [DocumentHostBinder] calls when the extension's editor asks for state, flips a page, asks
 * for a fresh draft, or pushes a save. Everything the `.soil` knows about a document is read and
 * written here, in the host, which is og's invariant 3 with a process boundary now enforcing it.
 *
 * **Threading.** Every method runs on the arbitrary pooled **Binder thread** the extension's call
 * arrived on — never Main — so the `runBlocking` each one opens over the suspending DAO work is
 * exactly the allowed case of it. A Binder transaction cannot be cancelled; the extension's own
 * call timeout is the only clock these run against.
 *
 * **A sealed session is never written to.** Every hook checks [alive] and [NotebookSession.isOpen]
 * first and throws `IllegalStateException` otherwise (one of the three that cross Binder intact) —
 * the host may have been closing behind the editor, and a write onto a sealed session would be
 * lost at best. A session that is merely **not open yet** is waited for, briefly and boundedly —
 * see [openSession] for why the reconnect path needs that.
 *
 * **The target is the host's memory of where the editor is** (M6). Until the editor could flip
 * there was nothing to remember — the displayed page was the answer to every question. Now a flip
 * moves [target] and nothing else does, so the notebook underneath can stay exactly where it was
 * (it catches up when the showing ends, og's `navigateToPage(endedOn)`). It survives the host's
 * death through the screen's saved state ([restoreTarget]), and a target naming a page that is no
 * longer in the notebook falls back to the displayed one ([DocumentTargetRules.resolveTarget]).
 *
 * **The decision tables live in [DocumentTargetRules]**, which is pure and JVM-tested; this class
 * is the `.soil` work around them and holds no rule of its own.
 *
 * Document text is never logged here; the binder above logs the counts.
 */
class DocumentHostHooks(
    /** The open session — read at call time, never captured: the screen may be on its way out. */
    private val notebook: () -> NotebookSession,
    /** The page whose strokes are on the paper (the R6 torn-read rule): what the user is looking
     *  at is the page the document belongs to, never `currentIndex` read off IO. The **fallback**
     *  since M6 — see the class doc's note on [target]. */
    private val displayedPageId: () -> String,
    /** The notebook's display name for the editor's header — display only, never a path. */
    private val notebookName: () -> String,
    /** False once the screen is finishing/closing — see the class doc. */
    private val alive: () -> Boolean,
    /**
     * Recognize a page, silently (M6). **null** = recognition is not there to run (no extension
     * installed, the model not READY, or the call failed) — the typed
     * [DocumentContract.SEED_UNAVAILABLE] refusal for a Bring in, and an empty window for a flip.
     * **""** = it ran and the page had nothing to give.
     *
     * Silent by contract: this runs behind a **stopped** host with the editor on top, so it must
     * never show a dialog and must never start a model download (only `prepare()` may, and only
     * with the host's consent dialog in front of it — the point's standing rule). The consent flow
     * belongs to the open-time seed, where the notebook is front-most ([DocumentSeedFlow]).
     */
    private val recognizePageText: suspend (pageId: String) -> String?,
) : DocumentHostBinder.Hooks {

    /** A seed the notebook built **before** the launch and handed over for the first `current()`
     *  to serve — see [stageSeed]. Immutable, so the volatile read publishes all three fields. */
    private class StagedSeed(val pageId: String, val text: String, val watermark: Long)

    /**
     * Where the editor is. `@Volatile`: written from Binder threads (a flip) and from Main (the
     * saved-state restore, the reset at the end of a showing), read from both.
     */
    @Volatile
    private var target: String? = null

    @Volatile
    private var staged: StagedSeed? = null

    /** The page the editor is on, for the screen's saved state and its catch-up on close. */
    val targetPageId: String? get() = target

    /** Hand back what saved state carried, **before** the reconnect's `begin` can ask for state
     *  (`onCreate`): a host killed behind the editor must come back pointing at the page the editor
     *  is still showing, not at the page the notebook happens to be on. */
    fun restoreTarget(pageId: String?) {
        target = pageId
    }

    /** The showing is over: the next one starts from the displayed page again, and an unconsumed
     *  stage must not survive into it (an open that failed before `current()` leaves one). */
    fun resetTarget() {
        target = null
        staged = null
    }

    /**
     * Park a seed for the editor about to open on [pageId] — the notebook recognized the page at
     * the tap, with the user watching, and this is how the result reaches the first `current()`
     * without a `.soil` write. [watermark] is the page's content maximum read **before**
     * recognition, so a stroke drawn while it ran reads as "changed since", never as fresh.
     *
     * **Consumed once**, by the next [loadCurrent] — matching or not. A stage that does not name
     * the target is dropped rather than served (the editor flipped, or the open never happened),
     * and keeping it would mean serving a stale draft to some later page.
     */
    fun stageSeed(pageId: String, text: String, watermark: Long) {
        staged = StagedSeed(pageId, text, watermark)
    }

    /**
     * The current target's state, with its text parked in the read window ([DocumentHostSession] is
     * the window). The target is a **page** — [DocumentContract.SCOPE_NOTEBOOK] is M7's and
     * [DocumentPageState.textDocument] is M8's.
     *
     * M6 added the staged seed: with no stored document and a stage naming this page, the window
     * holds recognized text the host has **not** written ([DocumentPageState.seeded] true,
     * [DocumentContract.SOURCE_DRAFTED], the watermark parked for the drafted save that stores it).
     * Everything else is M3's path unchanged.
     *
     * `setWindow` is the last thing done and its answer is the state's `textChunks`: window and
     * state are loaded together, which is the contract's atomicity. It also enforces
     * [DocumentContract.MAX_DOCUMENT_CHARS] — a stored document over the cap throws here, `begin`'s
     * `current()` never answers, and the entry button explains that the editor could not be opened.
     */
    override fun loadCurrent(session: DocumentHostSession): DocumentPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val pages = nb.pages
            val pageIds = pages.map { it.id }
            val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
            val index = pageIds.indexOf(pageId)
            check(index >= 0) { "page is not in the notebook" }
            target = pageId
            // Consume-once, whatever it holds: a stage that named another page is dropped here.
            val stage = staged
            staged = null
            val doc = nb.documents.get(pageId)
            when (
                val serve = DocumentTargetRules.openDecision(
                    docText = doc?.text,
                    targetPageId = pageId,
                    stagedPageId = stage?.pageId,
                    stagedText = stage?.text,
                )
            ) {
                is DocumentTargetRules.Serve.Seed -> {
                    val state = state(session, pageId, index, pages.size, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
                    // After setWindow, always: a window swap to a new key clears the park. Only a
                    // non-null stage can produce a Seed here; the safe call is so that a rule
                    // changed later costs a lost anchor (the editor's own NO_DRAFT_PENDING
                    // recovery) rather than a crashed transaction.
                    stage?.let { session.parkWatermark(it.watermark) }
                    state
                }
                is DocumentTargetRules.Serve.Stored -> {
                    val source = DocumentTargetRules.source(
                        doc?.srcUpdatedAt,
                        nb.db.documentDao().maxContentUpdatedAt(pageId),
                    )
                    state(session, pageId, index, pages.size, serve.text, source, seeded = false)
                }
            }
        }
    }

    /**
     * A completed save, straight into the `.soil` through the session's one serial writer
     * ([NotebookSession.writeDocument] — see its KDoc for why the write is drained rather than
     * fire-and-forget). `draftWatermark` non-null is a seed/merge anchoring itself, and an ordinary
     * edit can never invent one — the accumulator refuses a drafted commit with nothing parked.
     */
    override fun commit(commit: DocumentHostSession.Commit) = runBlocking {
        withContext(Dispatchers.IO) {
            openSession().writeDocument(commit.pageKey, commit.text, commit.draftWatermark)
        }
    }

    /**
     * M6: flip the target one page in [direction] and load the window with what that page has.
     *
     * **null is the whole contract's safety net**: no page in that direction, or anything at all
     * going wrong, and the editor stays exactly where it was. So nothing here moves [target] or
     * touches the window until everything that can fail has already succeeded — the page lookup,
     * the document read, the watermark sweep and the recognition all run first, and the two
     * mutating calls are the last two statements. The whole body is funnelled through one catch
     * for the same reason: a failure that had already swapped the window would leave the editor
     * showing one page's text under another page's key, and its next save refused by key.
     *
     * An **undocumented** page is seeded exactly like opening one, silently (see
     * [recognizePageText]) — and a recognition that cannot run does not block the flip: the page
     * lands empty and stays seedable.
     */
    override fun requestPage(session: DocumentHostSession, direction: Int): DocumentPageState? = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                val nb = openSession()
                val pages = nb.pages
                val pageIds = pages.map { it.id }
                val from = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
                val index = pageIds.indexOf(from)
                val newIndex = DocumentTargetRules.flipIndex(index, direction, pages.size)
                    ?: return@withContext null
                val page = pages[newIndex]
                val doc = nb.documents.get(page.id)
                // Before recognition, always: a stroke drawn while it runs must read as "changed
                // since this draft", never as the state the draft was built from.
                val pageMax = nb.db.documentDao().maxContentUpdatedAt(page.id)
                val recognized = if (doc == null) recognizePageText(page.id) else null
                when (val serve = DocumentTargetRules.flipDecision(doc?.text, recognized)) {
                    is DocumentTargetRules.Serve.Seed -> {
                        val state = state(session, page.id, newIndex, pages.size, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
                        session.parkWatermark(pageMax)
                        target = page.id
                        state
                    }
                    is DocumentTargetRules.Serve.Stored -> {
                        val state = state(
                            session, page.id, newIndex, pages.size, serve.text,
                            DocumentTargetRules.source(doc?.srcUpdatedAt, pageMax), seeded = false,
                        )
                        target = page.id
                        state
                    }
                }
            } catch (e: Exception) {
                // The class name and nothing else — a message here could carry a path or a slice of
                // the user's document, and this is one log line away from a bug report.
                Slog.d(TAG) { "requestPage($direction) failed: ${e.javaClass.simpleName}" }
                null
            }
        }
    }

    /**
     * M6: the editor's **Bring in** — recognize the current target page and load the window with
     * the result, `seeded = true` and the pre-recognition watermark parked. **The document row is
     * not touched**: the draft becomes real only when the editor stores it, which is what makes a
     * Bring in the user cancels cost nothing.
     *
     * [mode] ([DocumentContract.BRING_REPLACE] / `BRING_APPEND`) is advisory — the sheet ran
     * editor-side before the request and the editor applies the mode through its own buffer, so
     * the host's job is the same either way: hand over the page's text.
     *
     * Recognition that cannot run is the typed refusal [DocumentContract.SEED_UNAVAILABLE] (matched
     * with `==` on the far side), so the editor can say *why* rather than "failed". A page that
     * simply had nothing to give is **not** a refusal — an empty draft is an honest answer.
     */
    override fun requestSeed(session: DocumentHostSession, mode: Int): DocumentPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            val pages = nb.pages
            val pageIds = pages.map { it.id }
            val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
            val index = pageIds.indexOf(pageId)
            check(index >= 0) { "page is not in the notebook" }
            target = pageId
            val pageMax = nb.db.documentDao().maxContentUpdatedAt(pageId)
            val text = recognizePageText(pageId)
                ?: throw IllegalStateException(DocumentContract.SEED_UNAVAILABLE)
            val state = state(session, pageId, index, pages.size, text, DocumentContract.SOURCE_DRAFTED, seeded = true)
            session.parkWatermark(pageMax)
            state
        }
    }

    /**
     * The one place a [DocumentPageState] is built, so the three window-loading hooks cannot drift
     * on the chrome facts. [DocumentHostSession.setWindow] runs here and its answer is the state's
     * `textChunks` — the window and the state that describes it are loaded together, which is the
     * contract's atomicity.
     */
    private fun state(
        session: DocumentHostSession,
        pageId: String,
        index: Int,
        pageCount: Int,
        text: String,
        source: Int,
        seeded: Boolean,
    ): DocumentPageState {
        val chunks = session.setWindow(pageId, text)
        return DocumentPageState(
            pageKey = pageId,
            scope = DocumentContract.SCOPE_PAGE,
            pageIndex = index,
            pageCount = pageCount,
            // Truncated rather than refused: a name too long for the header is a display problem,
            // and failing the whole open over one would be absurd.
            title = notebookName().take(DocumentContract.MAX_TITLE_CHARS),
            // Still false: the flag is M8's (`NotebookFlags.TEXT_DOCUMENT` / `notebook_meta`).
            textDocument = false,
            source = source,
            textChars = text.length,
            textChunks = chunks,
            seeded = seeded,
        )
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
        const val TAG = "DocumentHostHooks"

        /** The whole wait, end to end. */
        const val OPEN_WAIT_MS = 8_000L

        /** One poll step. Long enough to cost nothing, short enough that a save landing just after
         *  the open does not sit visibly waiting. */
        const val OPEN_POLL_MS = 200L
    }
}
