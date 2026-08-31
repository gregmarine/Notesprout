package com.symmetricalpalmtree.notesproutsn.ext.document

import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 */
class DocumentSaver(
    /** Reads the live buffer. **Main thread only** — this class never calls it from anywhere else. */
    private val snapshot: () -> String,
) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushLock = Mutex()
    private val governor = AutosaveGovernor()

    /** The only target this screen ever saves to, learned at load. Null until then — and a save
     *  without one is not a save that goes to the wrong place, it is no save at all. */
    var pageKey: String? = null

    /** Set once the screen is leaving: late bookkeeping from an overtaken push must not roll
     *  [AutosaveGovernor.savedText] back to older words. */
    private var leaving = false

    private val debounceTick = Runnable { saveNow() }
    private val retryTick = Runnable { saveNow() }

    // ── What the screen asks ──────────────────────────────────────────────────

    /** The host handed this text over at load: it is what the host already holds. */
    fun markLoaded(text: String) = governor.markLoaded(text)

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
    fun saveNow() {
        main.removeCallbacks(debounceTick)
        main.removeCallbacks(retryTick)
        val key = pageKey ?: return
        when (val action = governor.request(snapshot())) {
            is AutosaveGovernor.SaveAction.Push -> launchPush(key, action.text)
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
        if (key == null || !governor.isDirty(text)) {
            then()
            return
        }
        scope.launch {
            val error = try {
                pushLock.withLock { pushBlocking(key, text) }
                null
            } catch (e: Exception) {
                e
            }
            main.post {
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

    // ── The push ──────────────────────────────────────────────────────────────

    private fun launchPush(key: String, text: String) {
        scope.launch {
            val error = try {
                pushLock.withLock { pushBlocking(key, text) }
                null
            } catch (e: Exception) {
                e
            }
            main.post { finishPush(key, text, error) }
        }
    }

    /** Bookkeeping for a finished push. **Main thread**, so the governor is single-threaded. */
    private fun finishPush(key: String, text: String, error: Exception?) {
        if (error == null) {
            // The park goes first: it is only ever a stale copy of what just landed.
            EditorSession.pending.clear(key)
            Slog.d(TAG) { "saved ${text.length} chars" }
            if (leaving) return
            val next = governor.onSaved(text)
            if (next is AutosaveGovernor.SaveAction.Push) {
                // Newer words arrived while that push was in flight; they go now, after it.
                pageKey?.let { launchPush(it, next.text) }
            }
            return
        }
        // The class name and the length only: an exception's message from either side of this seam
        // could carry a path, and the text certainly carries the document.
        Slog.d(TAG) { "save failed: ${error.javaClass.simpleName} (${text.length} chars)" }
        EditorSession.pending.park(key, text)
        governor.onFailed()
        if (leaving) return
        main.postDelayed(retryTick, RETRY_DELAY_MS)
    }

    /** Blocking, on IO: the whole document through the host's callback binder, chunk by chunk. */
    private fun pushBlocking(key: String, text: String) {
        val host = EditorSession.host ?: throw IllegalStateException("no showing")
        ChunkPush.push({ pageKey, index, chunk, last, drafted ->
            host.saveChunk(pageKey, index, chunk, last, drafted)
        }, key, text)
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
