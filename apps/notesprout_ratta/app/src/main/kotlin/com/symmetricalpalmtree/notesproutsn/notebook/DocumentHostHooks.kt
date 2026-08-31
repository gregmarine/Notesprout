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
 * **The scope is the target's second half** (M7). [targetScope] says whether the editor is on the
 * page target's document or the NOTEBOOK document (the merged final draft, a `document` row
 * parented to the notebook root). A scope switch moves it and nothing else does; the page target
 * is **retained** through a notebook-scope visit — switching back serves that page, and the close
 * catch-up still names it. It rides the same saved state ([restoreTarget]'s second argument —
 * og's `STATE_DOCUMENT_NOTEBOOK`), which is one half of the mode-routing guarantee; the other
 * half is structural: the notebook document's save key is [DocumentTargetRules.notebookKey],
 * which no page row can ever own.
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
    /** M8: whether this notebook is a text document ([NotebookFlags.TEXT_DOCUMENT] — the index
     *  bit, read once at session open). Gates the rename/close calls and rides every state. */
    private val isTextDocument: () -> Boolean = { false },
    /**
     * M8: apply a validated rename (text documents only — already gated here). Runs on a Binder
     * thread; the implementation validates against the name rules and siblings and refuses with
     * `IllegalArgumentException` whose message is shown as the refusal reason (never a path,
     * never document text), then writes index + meta + its own header.
     */
    private val rename: (String) -> Unit = { throw IllegalArgumentException("Not a text document.") },
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

    /** Which document the editor is on — [DocumentContract.SCOPE_PAGE] (the [target] page's) or
     *  [DocumentContract.SCOPE_NOTEBOOK] (the notebook document). Same writers as [target]. */
    @Volatile
    private var targetScope: Int = DocumentContract.SCOPE_PAGE

    /** The editor asked to abandon the merge in flight ([cancelRequest]) — read between pages by
     *  the merge loop, cleared at the start of every merge-running request. */
    @Volatile
    private var cancelled = false

    @Volatile
    private var staged: StagedSeed? = null

    /**
     * M8: how the editor asked the showing to end ([DocumentContract.CLOSE_SHOW_PAGES] /
     * [DocumentContract.CLOSE_TO_LIBRARY]) — advisory state written from a Binder thread by
     * [closeNotebook] and read on Main when the result lands. null = the editor never said
     * (process-death edges, the debug hook), which a text document treats as to-library —
     * the fail-safe direction: a wrongly-sealed notebook reopens, a wrongly-loaded canvas
     * cannot un-load.
     */
    @Volatile
    private var closeMode: Int? = null

    /** The page the editor is on, for the screen's saved state and its catch-up on close. Retained
     *  through a notebook-scope visit — the editor is still "at" that page for the way back. */
    val targetPageId: String? get() = target

    /** The scope half of the saved state (M7 — og's `STATE_DOCUMENT_NOTEBOOK`). */
    val scopeIsNotebook: Boolean get() = targetScope == DocumentContract.SCOPE_NOTEBOOK

    /** Hand back what saved state carried, **before** the reconnect's `begin` can ask for state
     *  (`onCreate`): a host killed behind the editor must come back pointing at the page — and,
     *  since M7, the scope — the editor is still showing, not at the page the notebook happens to
     *  be on. Restoring the scope is what keeps a recreated host from serving (and accepting) a
     *  page document to an editor whose buffer holds the notebook one. */
    fun restoreTarget(pageId: String?, notebookScope: Boolean) {
        target = pageId
        targetScope = if (notebookScope) DocumentContract.SCOPE_NOTEBOOK else DocumentContract.SCOPE_PAGE
    }

    /** The showing is over: the next one starts from the displayed page again, in page scope, and
     *  an unconsumed stage must not survive into it (an open that failed before `current()` leaves
     *  one). */
    fun resetTarget() {
        target = null
        targetScope = DocumentContract.SCOPE_PAGE
        staged = null
        cancelled = false
        closeMode = null
    }

    /** M8: the close advisory, consumed once by the result path (`onClosed`). */
    fun takeCloseMode(): Int? {
        val mode = closeMode
        closeMode = null
        return mode
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
     * the window).
     *
     * M6 added the staged seed: with no stored document and a stage naming this page, the window
     * holds recognized text the host has **not** written ([DocumentPageState.seeded] true,
     * [DocumentContract.SOURCE_DRAFTED], the watermark parked for the drafted save that stores it).
     *
     * M7 added the notebook branch: a showing restored in notebook scope serves the **stored**
     * notebook document (or an empty window) — never a merge, never a recognition. The auto-merge
     * belongs to the scope *switch*, where the editor is showing a popup for it; a reconnect's
     * `current()` must answer in milliseconds, and the recreated editor prefers its own buffer
     * anyway. A same-key `setWindow` keeps any parked watermark, so a recreated editor still
     * holding an unstored merge draft can land its drafted save.
     *
     * `setWindow` is the last thing done and its answer is the state's `textChunks`: window and
     * state are loaded together, which is the contract's atomicity. It also enforces
     * [DocumentContract.MAX_DOCUMENT_CHARS] — a stored document over the cap throws here, `begin`'s
     * `current()` never answers, and the entry button explains that the editor could not be opened.
     */
    override fun loadCurrent(session: DocumentHostSession): DocumentPageState = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            // Consume-once, whatever it holds: a stage that named another page is dropped here —
            // and one can never serve the notebook scope, whose branch drops it unread.
            val stage = staged
            staged = null
            if (scopeIsNotebook) {
                val doc = nb.documents.get(nb.notebookId)
                val nbMax = nb.db.documentDao().notebookMaxContentUpdatedAt(nb.notebookId)
                return@withContext state(
                    session,
                    pageKey = DocumentTargetRules.notebookKey(nb.notebookId),
                    scope = DocumentContract.SCOPE_NOTEBOOK,
                    index = -1,
                    pageCount = nb.pages.size,
                    text = doc?.text.orEmpty(),
                    source = DocumentTargetRules.source(doc?.srcUpdatedAt, nbMax),
                    seeded = false,
                )
            }
            val pages = nb.pages
            val pageIds = pages.map { it.id }
            val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
            val index = pageIds.indexOf(pageId)
            check(index >= 0) { "page is not in the notebook" }
            target = pageId
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
                    val state = pageState(session, pageId, index, pages.size, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
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
                    pageState(session, pageId, index, pages.size, serve.text, source, seeded = false)
                }
            }
        }
    }

    /**
     * A completed save, straight into the `.soil` through the session's one serial writer
     * ([NotebookSession.writeDocument] — see its KDoc for why the write is drained rather than
     * fire-and-forget). `draftWatermark` non-null is a seed/merge anchoring itself, and an ordinary
     * edit can never invent one — the accumulator refuses a drafted commit with nothing parked.
     *
     * [DocumentTargetRules.parentFor] is the M7 routing: the notebook document's key resolves to
     * the root row, every other key IS the page it names. The accumulator already refused any key
     * that is not the current window's, so this is resolution, not a second guard.
     */
    override fun commit(commit: DocumentHostSession.Commit) = runBlocking {
        withContext(Dispatchers.IO) {
            val nb = openSession()
            nb.writeDocument(
                DocumentTargetRules.parentFor(commit.pageKey, nb.notebookId),
                commit.text,
                commit.draftWatermark,
            )
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
                // The notebook scope has no neighbours (the editor's FlipRules already block the
                // tap; this is the seam's own answer to a caller that asked anyway).
                if (scopeIsNotebook) return@withContext null
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
                        val state = pageState(session, page.id, newIndex, pages.size, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
                        session.parkWatermark(pageMax)
                        target = page.id
                        state
                    }
                    is DocumentTargetRules.Serve.Stored -> {
                        val state = pageState(
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
            // The strip routes a notebook-scope refresh to requestMerge; a Bring in here would
            // recognize a page the editor is not showing.
            check(!scopeIsNotebook) { "not in page scope" }
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
            val state = pageState(session, pageId, index, pages.size, text, DocumentContract.SOURCE_DRAFTED, seeded = true)
            session.parkWatermark(pageMax)
            state
        }
    }

    /**
     * M7: switch the target between the page document and the notebook document — a flip in every
     * way that matters, [requestPage]'s recipe held to the letter: **null is the safety net**
     * (anything at all going wrong — a failed load, a merge the editor cancelled, a request for
     * the scope already current — and the editor stays exactly where it was), so nothing here
     * moves [targetScope] or touches the window until everything that can fail has succeeded, and
     * the whole body funnels through one catch.
     *
     * **Entering the notebook scope** serves the stored notebook document, or — when there is
     * none — runs the FIRST-toggle auto-merge ([mergeNotebook]) and serves the result as a seed:
     * unstored, `seeded = true`, the notebook-wide watermark (read **before** the loop) parked for
     * the drafted save that makes it real. A merge with nothing to give still lands the toggle on
     * an empty, seedable window. The page [target] is retained, untouched.
     *
     * **Leaving it** serves the retained page target exactly as a flip would — including seeding
     * an undocumented page, silently (og: toggling back can itself seed).
     */
    override fun requestScope(session: DocumentHostSession, scope: Int): DocumentPageState? = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                if ((scope == DocumentContract.SCOPE_NOTEBOOK) == scopeIsNotebook) {
                    return@withContext null   // already there — nothing moves
                }
                val nb = openSession()
                if (scope == DocumentContract.SCOPE_NOTEBOOK) {
                    val doc = nb.documents.get(nb.notebookId)
                    // Before the merge, always (the watermark-before-recognition rule, notebook-wide):
                    // a stroke drawn while the loop runs must read as "changed since this merge".
                    val nbMax = nb.db.documentDao().notebookMaxContentUpdatedAt(nb.notebookId)
                    val merged = if (doc == null) mergeNotebook(nb) else null
                    when (val serve = DocumentTargetRules.scopeDecision(doc?.text, merged)) {
                        is DocumentTargetRules.Serve.Seed -> {
                            val state = notebookState(session, nb, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
                            session.parkWatermark(nbMax)
                            targetScope = DocumentContract.SCOPE_NOTEBOOK
                            state
                        }
                        is DocumentTargetRules.Serve.Stored -> {
                            val source = DocumentTargetRules.source(doc?.srcUpdatedAt, nbMax)
                            val state = notebookState(session, nb, serve.text, source, seeded = false)
                            targetScope = DocumentContract.SCOPE_NOTEBOOK
                            state
                        }
                    }
                } else {
                    val pages = nb.pages
                    val pageIds = pages.map { it.id }
                    val pageId = DocumentTargetRules.resolveTarget(target, pageIds, displayedPageId())
                    val index = pageIds.indexOf(pageId)
                    check(index >= 0) { "page is not in the notebook" }
                    val doc = nb.documents.get(pageId)
                    val pageMax = nb.db.documentDao().maxContentUpdatedAt(pageId)
                    val recognized = if (doc == null) recognizePageText(pageId) else null
                    when (val serve = DocumentTargetRules.flipDecision(doc?.text, recognized)) {
                        is DocumentTargetRules.Serve.Seed -> {
                            val state = pageState(session, pageId, index, pages.size, serve.text, DocumentContract.SOURCE_DRAFTED, seeded = true)
                            session.parkWatermark(pageMax)
                            target = pageId
                            targetScope = DocumentContract.SCOPE_PAGE
                            state
                        }
                        is DocumentTargetRules.Serve.Stored -> {
                            val state = pageState(
                                session, pageId, index, pages.size, serve.text,
                                DocumentTargetRules.source(doc?.srcUpdatedAt, pageMax), seeded = false,
                            )
                            target = pageId
                            targetScope = DocumentContract.SCOPE_PAGE
                            state
                        }
                    }
                }
            } catch (e: Exception) {
                // MERGE_CANCELLED funnels here too — a cancelled switch is a switch that did not
                // happen, and og answers it with silence. Class name only, as everywhere.
                Slog.d(TAG) { "requestScope($scope) failed: ${e.javaClass.simpleName}" }
                null
            }
        }
    }

    /**
     * M7: the editor's **Merge** — the notebook-wide merge into the read window, [requestSeed]'s
     * asymmetry copied DELIBERATELY: non-null or a marshalable throw, mutations last, the document
     * row untouched (the draft becomes real only when the editor stores it). A cancelled merge is
     * `IllegalStateException` carrying exactly [DocumentContract.MERGE_CANCELLED] — nothing
     * written, window untouched, the editor silent.
     *
     * A merge with nothing to give answers **honestly with an empty window** — no park, no claim,
     * `seeded = false`, the stored row's own source — and the editor's call is a silent no-op
     * (og's null-draft), which is what keeps Replace-over-blank-pages from blanking a document.
     * Recognition never blocks: [mergeNotebook] takes page documents with or without a recognizer.
     *
     * [mode] is advisory here exactly as it is on [requestSeed] — the sheet ran editor-side.
     */
    override fun requestMerge(session: DocumentHostSession, mode: Int): DocumentPageState = runBlocking {
        withContext(Dispatchers.IO) {
            check(scopeIsNotebook) { "not in notebook scope" }
            val nb = openSession()
            val doc = nb.documents.get(nb.notebookId)
            // Before the loop, always — see requestScope.
            val nbMax = nb.db.documentDao().notebookMaxContentUpdatedAt(nb.notebookId)
            val merged = mergeNotebook(nb)
            if (merged.isBlank()) {
                notebookState(
                    session, nb, "",
                    DocumentTargetRules.source(doc?.srcUpdatedAt, nbMax), seeded = false,
                )
            } else {
                val state = notebookState(session, nb, merged, DocumentContract.SOURCE_DRAFTED, seeded = true)
                session.parkWatermark(nbMax)
                state
            }
        }
    }

    /**
     * M7: the editor's Cancel, arriving on its own Binder thread while a merge holds another. The
     * loop reads the flag between pages and abandons; with nothing running this is a no-op (the
     * next merge clears it first thing).
     */
    override fun cancelRequest() {
        cancelled = true
    }

    /** M8, text documents only: validation and the writes live in [rename] (the screen's half);
     *  the gate here is the seam's — a call against an ordinary notebook is a contract breach. */
    override fun renameNotebook(name: String) {
        require(isTextDocument()) { "Not a text document." }
        rename(name)
    }

    /** M8, text documents only: record the advisory — the editor still finishes normally and
     *  the result path reads this via [takeCloseMode]. */
    override fun closeNotebook(mode: Int) {
        require(isTextDocument()) { "Not a text document." }
        closeMode = mode
    }

    /**
     * og's per-page loop ([DocumentTargetRules.mergePagePart] is the row, [mergeText] the join):
     * a page's own document wins, an undocumented page contributes its silent recognition when one
     * can run, a page with nothing to give is dropped whole. Cancellation is checked **between
     * pages** — the one recognition in flight finishes, nothing after it starts.
     */
    private suspend fun mergeNotebook(nb: NotebookSession): String {
        cancelled = false
        val pages = nb.pages
        val parts = ArrayList<String?>(pages.size)
        var recognized = 0
        for (page in pages) {
            if (cancelled) throw IllegalStateException(DocumentContract.MERGE_CANCELLED)
            val docText = nb.documents.get(page.id)?.text
            val read = if (docText.isNullOrBlank()) {
                recognized++
                recognizePageText(page.id)
            } else null
            parts += DocumentTargetRules.mergePagePart(docText, read)
        }
        val text = DocumentTargetRules.mergeText(parts)
        Slog.d(TAG) {
            "merge: ${pages.size} pages ($recognized recognized) → ${text.length} chars"
        }
        return text
    }

    /** A page target's state — [state] with the page's own key and index. */
    private fun pageState(
        session: DocumentHostSession,
        pageId: String,
        index: Int,
        pageCount: Int,
        text: String,
        source: Int,
        seeded: Boolean,
    ): DocumentPageState =
        state(session, pageId, DocumentContract.SCOPE_PAGE, index, pageCount, text, source, seeded)

    /** The notebook document's state — the minted [DocumentTargetRules.notebookKey], index −1. */
    private fun notebookState(
        session: DocumentHostSession,
        nb: NotebookSession,
        text: String,
        source: Int,
        seeded: Boolean,
    ): DocumentPageState = state(
        session, DocumentTargetRules.notebookKey(nb.notebookId), DocumentContract.SCOPE_NOTEBOOK,
        index = -1, pageCount = nb.pages.size, text = text, source = source, seeded = seeded,
    )

    /**
     * The one place a [DocumentPageState] is built, so the window-loading hooks cannot drift on
     * the chrome facts. [DocumentHostSession.setWindow] runs here and its answer is the state's
     * `textChunks` — the window and the state that describes it are loaded together, which is the
     * contract's atomicity.
     */
    private fun state(
        session: DocumentHostSession,
        pageKey: String,
        scope: Int,
        index: Int,
        pageCount: Int,
        text: String,
        source: Int,
        seeded: Boolean,
    ): DocumentPageState {
        val chunks = session.setWindow(pageKey, text)
        return DocumentPageState(
            pageKey = pageKey,
            scope = scope,
            pageIndex = index,
            pageCount = pageCount,
            // Truncated rather than refused: a name too long for the header is a display problem,
            // and failing the whole open over one would be absurd.
            title = notebookName().take(DocumentContract.MAX_TITLE_CHARS),
            textDocument = isTextDocument(),
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
