package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentEditor
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * The DOCUMENT_EDITOR point (arc 19) — the host's **held** bind for one showing of the editor
 * screen, and the second screen-owning point after the scratch pad. Every method:
 * `HostCallerCheck.enforce` first, before anything is read out of the arguments.
 *
 * `begin` parks both lent binders in [EditorSession] for the screen's life; `end` clears them. What
 * M4 added is what happens *around* those two lines, because they are the two moments a showing can
 * come apart with words in it that the host has never seen:
 *
 * - **`begin` while a showing already exists means the host restarted.** Its process died and came
 *   back; the screen is still standing in this one, holding a buffer keyed to a target the new host
 *   may or may not be showing. A live screen is told, and answers by asking the new host what it is
 *   showing (see `DocumentEditorActivity.onHostBegan`). With no screen alive, a parked snapshot is
 *   pushed here — but only after `current()` confirms the target, because a mismatched key means
 *   another document and writing there would be corruption.
 * - **`end` is the last moment the host binder is valid.** The host is about to seal, so anything
 *   still unsaved has to go *now*: the live screen's buffer through the flush hook, then whatever is
 *   parked. This is flush-before-seal, carried across a process boundary.
 *
 * `end` runs on a Binder thread, where blocking is allowed and correct — the host's `end()` call
 * returning is what tells it the flush is done.
 *
 * **Only marshalable exceptions leave.** `SecurityException` (a caller that is not the host),
 * `IllegalArgumentException` (a null binder) and `IllegalStateException` are the three Binder
 * carries intact; anything else kills the transaction *silently* and the host reads the empty reply
 * as success. Everything the backstop does is therefore inside its own `try`.
 *
 * Logs: counts, lengths and class names. **Never a character of the document.**
 */
class DocumentEditorService : Service() {

    private val binder = object : IDocumentEditor.Stub() {

        override fun begin(store: IExtensionStore?, host: IDocumentHost?) {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(host) { "host is null" }
            val restarted = EditorSession.host != null
            EditorSession.begin(store, host)
            Slog.d(TAG) { "begin${if (restarted) " (host restarted)" else ""}" }
            if (!restarted) return
            val listener = EditorSession.beginListener
            if (listener != null) {
                // A screen is alive: it owns the buffer and the decision. Its own reconnect path
                // re-reads the target and flushes if it still matches.
                listener.onHostBegan()
            } else if (EditorSession.pending.isParked) {
                // Nothing is alive to ask; the park is all that is left of that text.
                pushPendingInBackground()
            }
        }

        override fun end() {
            enforce()
            val host = EditorSession.host
            if (host != null) flushBeforeRevoke(host)
            EditorSession.end()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@DocumentEditorService, BuildConfig.HOST_PACKAGE)
    }

    /**
     * The teardown backstop. The live screen's snapshot is the newest text there is, so it goes
     * last: a park for the *same* target can only be an older copy of it and is dropped rather than
     * written over the top. A park for a *different* target is still owed its write and goes first.
     */
    private fun flushBeforeRevoke(host: IDocumentHost) {
        // The read (the screen's buffer) comes before the write ordering is decided — the park is
        // only resolvable once it is known whether the screen has something newer for that target.
        val live = try {
            EditorSession.flushHook?.unsavedSnapshot()
        } catch (e: Exception) {
            Slog.d(TAG) { "flush hook failed: ${e.javaClass.simpleName}" }
            null
        }
        val parked = EditorSession.pending.take()
        if (parked != null && parked.first != live?.first) {
            pushOrDrop(host, parked.first, parked.second)
        }
        if (live != null) pushOrDrop(host, live.first, live.second)
    }

    /** One synchronous push, on whatever thread the caller is. The host is revoking regardless, so a
     *  failure here is logged and dropped — there is nothing left to retry against. */
    private fun pushOrDrop(host: IDocumentHost, pageKey: String, text: String) {
        try {
            ChunkPush.push({ key, index, chunk, last, drafted ->
                host.saveChunk(key, index, chunk, last, drafted)
            }, pageKey, text)
            Slog.d(TAG) { "teardown flush pushed ${text.length} chars" }
        } catch (e: Exception) {
            Slog.d(TAG) { "teardown flush failed: ${e.javaClass.simpleName} (${text.length} chars)" }
        }
    }

    /**
     * No screen, but text nobody has taken: try the restarted host on a background thread (never the
     * Binder thread `begin` arrives on — the host is waiting on that call to return).
     *
     * `current()` first, because the answer is what decides whether there is a write at all: it
     * loads the read window and names the target, and only a matching key is pushed. A recreated
     * host opens its database asynchronously, so the first attempts are expected to fail; the ladder
     * gives it a few seconds and then gives up, which is the honest end of a save nobody is waiting
     * for any more.
     */
    private fun pushPendingInBackground() {
        Thread {
            for (attempt in 1..PENDING_ATTEMPTS) {
                val host = EditorSession.host ?: return@Thread
                val key = try {
                    host.current().pageKey
                } catch (e: Exception) {
                    Slog.d(TAG) { "pending state attempt $attempt failed: ${e.javaClass.simpleName}" }
                    Thread.sleep(PENDING_RETRY_MS)
                    continue
                }
                when (val resolution = EditorSession.pending.resolve(key)) {
                    is PendingPark.Resolution.Push -> pushOrDrop(host, resolution.pageKey, resolution.text)
                    // A key that differs is another document: these words are not its, and writing
                    // them there would be corruption. Dropped, deliberately.
                    PendingPark.Resolution.Drop -> Slog.d(TAG) { "pending dropped — target changed" }
                    PendingPark.Resolution.Nothing -> Unit
                }
                return@Thread
            }
            Slog.d(TAG) { "pending gave up after $PENDING_ATTEMPTS attempts" }
        }.apply { isDaemon = true }.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "DocumentEditorService"

        /** ~5 s of ladder: a recreated host's database open is asynchronous. */
        const val PENDING_ATTEMPTS = 10
        const val PENDING_RETRY_MS = 500L
    }
}
