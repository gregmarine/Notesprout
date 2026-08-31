package com.symmetricalpalmtree.notesproutsn.ext.document

import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The plumbing half of autosave: timers, threads and the binder. Every *decision* it makes is
 * [AutosaveGovernor]'s and every *rule about words it could not deliver* is [PendingPark]'s — this
 * class owns only the parts that need Android, which is what keeps the interesting logic testable.
 *
 * The shape, and why each piece is where it is:
 *
 * - **The snapshot is taken on Main, at the moment of the trigger.** An `Editable` read from a
 *   background thread is a race with the writer's next keystroke; a `String` taken on Main is a
 *   fact. Everything after that point works on that immutable copy.
 * - **The push runs on IO, one at a time** (the [Mutex]). `saveChunk` is a blocking Binder call and
 *   the host accumulates chunks per target — two overlapping pushes would interleave into one
 *   accumulator and commit a document that was never written.
 * - **The bookkeeping runs back on Main.** `savedText`, the queue and the retry timer are read and
 *   written from one thread only, so there is no window where the buffer is both saved and dirty.
 * - **A failure never advances anything.** The snapshot is parked in [EditorSession.pending], the
 *   retry is re-armed, and the buffer stays dirty. Only the exception's **class name and the text
 *   length** are logged — never a character of the document, on any path.
 *
 * The coroutine scope is deliberately **not** cancelled when the screen goes: a save armed by
 * `onPause` must land even though the Activity is on its way out, and the extension process outlives
 * the screen for exactly as long as the host holds its bind.
 *
 * M6 added two things, both of which are decisions kept in pure classes here too:
 *
 * - **The draft claim** ([DraftAnchor]). A seed or a Bring in puts text on screen that the host has
 *   not stored; the save that lands it carries `drafted = true`, which is what stamps the host's
 *   parked watermark. Every push snapshots the claim at its trigger and carries it through.
 * - **The flip's no-save zone** ([suspended], [prepareFlip], [pushForFlip]). A flip pushes the
 *   outgoing page first and only then lets the target move, and every other save trigger is inert
 *   in between.
 */
class DocumentSaver(
    /** Reads the live buffer. **Main thread only** — this class never calls it from anywhere else. */
    private val snapshot: () -> String,
    /** Reads the caret. **Main thread only**, like [snapshot]. */
    private val caretSnapshot: () -> Int,
    /**
     * Where the caret goes at every save trigger (M5). Fire-and-forget: it is called on Main and
     * must not block, and nothing here waits on it or cares whether it landed.
     *
     * It fires on **every** trigger, including one where the words are unchanged — og's rule. A
     * writer who reads to the bottom of a page and leaves without typing has still moved, and the
     * next open should land where they were looking.
     */
    private val caretSink: (String, Int) -> Unit,
    /**
     * A drafted push landed (M6): the seed or the Bring in is stored and the source strip may say
     * "drafted from this page" and mean it. **Main thread**, and only ever fired for a push that
     * carried the flag.
     */
    private val onDraftAnchored: () -> Unit = {},
    /**
     * A drafted push was refused with [com.symmetricalpalmtree.notesproutsn.extension.DocumentContract.NO_DRAFT_PENDING]:
     * the host had no watermark parked, nothing was written, and the claim is gone. The words go
     * again immediately as an ordinary save; the strip must stop claiming provenance. **Main
     * thread.**
     */
    private val onDraftDowngraded: () -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushLock = Mutex()
    private val governor = AutosaveGovernor()
    private val anchor = DraftAnchor()

    /** The only target this screen ever saves to, learned at load. Null until then — and a save
     *  without one is not a save that goes to the wrong place, it is no save at all. */
    var pageKey: String? = null

    /** Set once the screen is leaving: late bookkeeping from an overtaken push must not roll
     *  [AutosaveGovernor.savedText] back to older words. */
    private var leaving = false

    /**
     * **The flip gap's no-save zone** (M6), guarded editor-side; the host guards its side by key.
     *
     * Between the moment a flip pushes the outgoing page and the moment the incoming page's text is
     * installed, the buffer belongs to nobody: the host is being keyed to the incoming page while
     * the screen still shows the outgoing one, so a save landing in the gap would write one page's
     * words onto another. The trigger set is real — an autosave from typing through a slow seed, an
     * `onPause`, a Preview tap, a Done. While this is set [saveNow] returns at once and
     * [flushAndThen] skips its push, because the outgoing text was already pushed as the flip began.
     */
    var suspended: Boolean = false

    private val debounceTick = Runnable { saveNow() }
    private val retryTick = Runnable { saveNow() }

    // ── What the screen asks ──────────────────────────────────────────────────

    /** True while a seed / Bring in is on screen and unstored — the next push carries the flag. */
    val draftPending: Boolean get() = anchor.pending

    /** A seed or a Bring in was adopted: the next push is the one that makes it real. */
    fun armDraft() = anchor.arm()

    /**
     * Take on a freshly loaded read window — at open, or at the far end of a flip. [seeded] is the
     * host's word that it **built** this text and has **not** stored it. Returns whether a draft
     * claim was armed, which is also "does this need a save arming".
     *
     * A seeded window is treated as **unsaved**: the host's document is still what it was (blank,
     * for a seed), so `savedText` is empty and the first push writes the whole draft with the claim
     * on it.
     *
     * **An empty seed is not a draft.** The host answers `seeded = true` with an empty window when
     * there was nothing to read — no recognizer ready, or a page with nothing on it — and og's rule
     * there is "open empty, write no row; the page stays seedable". Claiming provenance for that
     * would eventually stamp "drafted from this page" over words the writer typed themselves.
     */
    fun adoptWindow(text: String, seeded: Boolean): Boolean {
        val draft = seeded && text.isNotBlank()
        if (draft) {
            governor.markLoaded("")
            anchor.arm()
        } else {
            governor.markLoaded(text)
            anchor.clear()
        }
        return draft
    }

    /** True when [text] is not what the host is known to hold. */
    fun isDirty(text: String): Boolean = governor.isDirty(text)

    /** Restart the idle timer; a burst of typing coalesces into one write. */
    fun schedule() {
        main.removeCallbacks(debounceTick)
        main.postDelayed(debounceTick, AUTOSAVE_DELAY_MS)
    }

    /** Drop the timers — the screen is gone, or a save is happening right now instead. */
    fun cancelTimers() {
        main.removeCallbacks(debounceTick)
        main.removeCallbacks(retryTick)
    }

    /** The unsaved buffer as `pageKey to text`, or null when nothing is owed. **Main thread only.** */
    fun unsavedSnapshot(): Pair<String, String>? {
        val key = pageKey ?: return null
        val text = snapshot()
        return if (governor.isDirty(text)) key to text else null
    }

    /**
     * A save trigger: the debounce, a mode switch, `onPause`, a retry. Snapshots the buffer and asks
     * the governor what to do with it. **Main thread only.**
     */
    fun saveNow() = saveTrigger(forceDraft = false)

    /**
     * A **Bring in** just armed the draft claim: push the buffer NOW, even when its text is
     * unchanged — og's rule, and the reason [AutosaveGovernor.requestDraft] exists: both Bring in
     * choices re-anchor the watermark to the state just recognized, *even when the draft came out
     * identical*. The ordinary [saveNow] would drop the unchanged text, the host's parked watermark
     * would never be consumed, and the strip would claim a provenance the row does not carry.
     * **Main thread only.**
     */
    fun saveDraftNow() = saveTrigger(forceDraft = true)

    /** The one trigger body — [forceDraft] picks the governor door (unchanged-drop vs push-anyway). */
    private fun saveTrigger(forceDraft: Boolean) {
        // The flip gap: the outgoing page's text went as the flip began, and what is in the buffer
        // now is nobody's until the incoming page lands.
        if (suspended) return
        main.removeCallbacks(debounceTick)
        main.removeCallbacks(retryTick)
        val key = pageKey ?: return
        caretSink(key, caretSnapshot())
        // Snapshotted with the text, at the trigger: a Bring in that arms the claim after this
        // point belongs to the push it triggers itself, not to this one.
        val drafted = anchor.pending
        val text = snapshot()
        val action = if (forceDraft) governor.requestDraft(text) else governor.request(text)
        when (action) {
            is AutosaveGovernor.SaveAction.Push -> launchPush(key, action.text, drafted)
            // Wait: a push is in flight and this snapshot is queued behind it — nothing to start.
            // Idle / Retry: nothing new to write.
            else -> Unit
        }
    }

    /**
     * The host restarted and says it is showing [currentKey]. Push only when that is still this
     * screen's target and the buffer holds something the host has not got — a key that differs is a
     * different document, and these words would be corruption there. **Main thread only.**
     */
    fun flushOnReconnect(currentKey: String) {
        if (governor.shouldFlushOnReconnect(currentKey, pageKey, snapshot())) saveNow()
    }

    /**
     * The leave path (Done and Close both — writing is not cancellable, so neither of them discards).
     * Pushes the final snapshot, then calls [then] on Main whatever happened: a failure here has
     * already parked its words, and the service's `end()` backstop is still to come.
     */
    fun flushAndThen(then: () -> Unit) {
        cancelTimers()
        leaving = true
        val key = pageKey
        val text = snapshot()
        // Before the early return: leaving without typing is still a move worth remembering.
        if (key != null) caretSink(key, caretSnapshot())
        if (suspended) {
            // Done pressed mid-flip. The outgoing page's words were pushed as the flip began, and
            // the buffer may show a page the host is no longer keyed to — there is nothing safe to
            // write here, so the leave just proceeds. (The flip's own coroutine finds the screen
            // finishing and abandons its adopt.)
            then()
            return
        }
        if (key == null || !governor.isDirty(text)) {
            then()
            return
        }
        val drafted = anchor.pending
        scope.launch {
            val error = runPush(key, text, drafted)
            main.post {
                settleDraft(drafted, error)
                if (error == null) {
                    EditorSession.pending.clear(key)
                    governor.onSaved(text)
                    Slog.d(TAG) { "final save landed (${text.length} chars)" }
                } else {
                    EditorSession.pending.park(key, text)
                    governor.onFailed()
                    Slog.d(TAG) { "final save failed: ${error.javaClass.simpleName} (${text.length} chars)" }
                }
                then()
            }
        }
    }

    // ── The flip's two halves ─────────────────────────────────────────────────

    /**
     * Everything a flip must do on Main **before** it goes async and **before** [pageKey] moves,
     * in one call so nothing can slip between the pieces (the M5 handoff trap: `caretSink` files
     * the caret under whatever `pageKey` says at the time, so the outgoing caret has to be handed
     * over while the outgoing key is still the current one).
     *
     * Returns the snapshot to push, or null when the buffer holds nothing the host has not got.
     * **Main thread only.**
     */
    fun prepareFlip(): FlipSnapshot? {
        cancelTimers()
        // The queue dies here, not at the flip push's bookkeeping: a snapshot queued behind an
        // in-flight push is OLDER than the one taken below, and without this an in-flight push's
        // own completion would relaunch it — behind the flip's push on the same lock — landing
        // stale text over the newest words as the page is left. This snapshot supersedes anything
        // queued, by construction.
        governor.abandonQueue()
        val key = pageKey ?: return null
        caretSink(key, caretSnapshot())
        val text = snapshot()
        if (!governor.isDirty(text)) return null
        return FlipSnapshot(key, text, anchor.pending)
    }

    /**
     * The flip's outgoing push: behind the same lock as every other save, with the same bookkeeping
     * — this is one save that happens to be the last one the page will get. **Call from a
     * background dispatcher**; the Binder calls block and the bookkeeping hops to Main itself.
     *
     * Returns true when the words landed. A false aborts the flip: moving on would leave a page
     * unwritten with no way back to it.
     */
    suspend fun pushForFlip(snapshot: FlipSnapshot): Boolean {
        // NonCancellable throughout: this rides the SCREEN's lifecycle scope (unlike every other
        // push, which rides this class's uncancellable one), and a Done/destroy landing mid-flip
        // must not cancel the bookkeeping — a push that succeeded but never ran `pending.clear`
        // would leave a stale park for this key that the teardown flush then writes OVER the words
        // that just landed.
        val error = withContext(NonCancellable) {
            runPush(snapshot.pageKey, snapshot.text, snapshot.drafted)
        }
        withContext(Dispatchers.Main + NonCancellable) {
            finishPush(snapshot.pageKey, snapshot.text, snapshot.drafted, error, fromFlip = true)
        }
        return error == null
    }

    /** One page's words on their way out, taken together on Main so they cannot disagree. */
    class FlipSnapshot(val pageKey: String, val text: String, val drafted: Boolean)

    // ── The push ──────────────────────────────────────────────────────────────

    private fun launchPush(key: String, text: String, drafted: Boolean) {
        scope.launch {
            val error = runPush(key, text, drafted)
            main.post { finishPush(key, text, drafted, error) }
        }
    }

    /** The one push-under-the-lock, error-captured — every path's shared middle (the completion
     *  bookkeeping deliberately stays per-path: the three callers end differently on purpose). */
    private suspend fun runPush(key: String, text: String, drafted: Boolean): Exception? =
        try {
            pushLock.withLock { pushBlocking(key, text, drafted) }
            null
        } catch (e: Exception) {
            e
        }

    /**
     * The service's teardown push (M11): [key]/[text] through the SAME lock every other save
     * takes, blocking the calling **Binder** thread until it landed or threw. Without the lock the
     * `end()` backstop could interleave its chunks with an in-flight autosave push on the host's
     * one accumulator — out-of-order refusals, or an older snapshot committing after a newer one.
     * No bookkeeping: the showing is over, and the caller parks on a throw.
     */
    fun pushLockedBlocking(key: String, text: String) {
        kotlinx.coroutines.runBlocking {
            pushLock.withLock { pushBlocking(key, text, drafted = false) }
        }
    }

    /**
     * Bookkeeping for a finished push. **Main thread**, so the governor and the anchor are
     * single-threaded. [fromFlip] marks the outgoing push of a page the editor is leaving.
     */
    private fun finishPush(
        key: String,
        text: String,
        drafted: Boolean,
        error: Exception?,
        fromFlip: Boolean = false,
    ) {
        val outcome = settleDraft(drafted, error)
        if (error == null) {
            // The park goes first: it is only ever a stale copy of what just landed.
            EditorSession.pending.clear(key)
            Slog.d(TAG) { "saved ${text.length} chars${if (drafted) " (drafted)" else ""}" }
            if (leaving) return
            val next = governor.onSaved(text)
            if (fromFlip) {
                // A snapshot queued behind this push is the OUTGOING page's words, and the target is
                // about to move — pushing them after that would write them onto the incoming page.
                // The governor is told the queue is gone, so it does not go on believing a push is
                // running and swallow every later save into it.
                governor.abandonQueue()
                return
            }
            if (next is AutosaveGovernor.SaveAction.Push) {
                // Newer words arrived while that push was in flight; they go now, after it.
                pageKey?.let { launchPush(it, next.text, anchor.pending) }
            }
            return
        }
        // The class name and the length only: an exception's message from either side of this seam
        // could carry a path, and the text certainly carries the document.
        Slog.d(TAG) { "save failed: ${error.javaClass.simpleName} (${text.length} chars)" }
        EditorSession.pending.park(key, text)
        governor.onFailed()
        if (leaving) return
        if (outcome == DraftAnchor.Outcome.DOWNGRADED && !suspended) {
            // Nothing was written and the provenance is gone, but the words are still owed: they go
            // again right now as an ordinary save rather than waiting out a retry beat.
            saveNow()
            return
        }
        main.postDelayed(retryTick, RETRY_DELAY_MS)
    }

    /** The draft claim's half of a finished push, and the one callback it owes the screen. */
    private fun settleDraft(drafted: Boolean, error: Exception?): DraftAnchor.Outcome {
        val outcome = if (error == null) {
            anchor.onPushSucceeded(drafted)
        } else {
            anchor.onPushFailed(drafted, error.message)
        }
        when (outcome) {
            DraftAnchor.Outcome.ANCHORED -> onDraftAnchored()
            DraftAnchor.Outcome.DOWNGRADED -> onDraftDowngraded()
            else -> Unit
        }
        return outcome
    }

    /** Blocking, on IO: the whole document through the host's callback binder, chunk by chunk. */
    private fun pushBlocking(key: String, text: String, drafted: Boolean) {
        val host = EditorSession.host ?: throw IllegalStateException("no showing")
        ChunkPush.push({ pageKey, index, chunk, last, isDrafted ->
            host.saveChunk(pageKey, index, chunk, last, isDrafted)
        }, key, text, drafted)
    }

    private companion object {
        const val TAG = "DocumentSaver"

        /** og's idle debounce, unchanged: long enough to coalesce a burst of typing, short enough
         *  that what is lost to a kill is a sentence rather than a page. */
        const val AUTOSAVE_DELAY_MS = 2_000L

        /** A failed push waits the same beat before trying again — the usual cause is a host that
         *  is restarting, and its database open is asynchronous. */
        const val RETRY_DELAY_MS = 2_000L
    }
}
