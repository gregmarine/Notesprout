package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ISketch
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost

/**
 * The SKETCH point (arc 43 / K5) — the host's **held** bind for one showing of the sketch screen,
 * and SN's tenth capability point. Every method: `HostCallerCheck.enforce` first, before anything is
 * read out of the arguments.
 *
 * **`begin` does nothing but park the binder.** The host's clock on it is two seconds and the screen
 * asks for its own state the moment it is up, so any work here would be work done twice, once
 * against a budget. A second `begin` while a showing exists means the host **restarted**: its
 * process died and came back, and the screen is still standing in this one holding a page the new
 * host has never seen. A live screen is told ([SketchSession.beginListener]) and re-offers what it
 * holds; with no screen alive, a parked page is pushed here on a background thread.
 *
 * **`end` is the last moment the host binder is valid**, and on this seam that matters more than on
 * any other: a sketch is one image that exists nowhere until a save lands — there are no rows in the
 * `.soil` the way a page of ink has rows. So `end` flushes the live screen's page through that
 * screen's own push lock, then re-pushes whatever is parked, under its own key (a sketch save is
 * accepted for **any live page**, so the park needs no match to be worth writing). A park whose push
 * fails a second time is dropped with a line — the binder is about to be revoked and there is
 * nowhere left to put it.
 *
 * `end` runs on a Binder thread, where blocking is allowed and correct: the host's `end()` call
 * returning is what tells it the flush is done, and the host gives it fifteen seconds for exactly
 * this reason.
 *
 * **Only marshalable exceptions leave.** `SecurityException`, `IllegalArgumentException` and
 * `IllegalStateException` are the three Binder carries intact; anything else kills the transaction
 * *silently* and the host reads the empty reply as success. Everything the backstop does is
 * therefore inside its own `try`.
 *
 * Logs: counts, byte totals, durations and exception class names. **Never a pixel.**
 */
class SketchService : Service() {

    private val binder = object : ISketch.Stub() {

        override fun begin(host: ISketchHost?) {
            enforce()
            requireNotNull(host) { "host is null" }
            val restarted = SketchSession.host != null
            SketchSession.begin(host)
            Slog.d(TAG) { "begin${if (restarted) " (host restarted)" else ""}" }
            if (!restarted) return
            val listener = SketchSession.beginListener
            if (listener != null) {
                // A screen is alive: it owns the page and the decision. Its own reconnect path
                // re-offers the park and flushes anything the new host has not got.
                listener.onHostBegan()
            } else if (SketchSession.pending.isParked) {
                // Nothing is alive to ask; the park is all that is left of those pixels.
                pushPendingInBackground()
            }
        }

        override fun end() {
            enforce()
            val host = SketchSession.host
            if (host != null) flushBeforeRevoke(host)
            SketchSession.clear()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@SketchService, BuildConfig.HOST_PACKAGE)
    }

    /**
     * The teardown backstop, in the order the two owed things have to go:
     *
     * 1. **The live screen's page first**, through [SketchSession.FlushHook.flushBlocking] — the
     *    newest pixels there are. It never throws; a failure inside it has already parked them.
     * 2. **Then whatever is parked**, which after step 1 is either an older failure for another page
     *    or the very bytes step 1 just failed to deliver. Either way it is pushed through the
     *    screen's push lock when there is a screen (so it cannot interleave with a save still in the
     *    air on the host's one accumulator) and straight down the chunk stream when there is not.
     *
     * A second failure is the end of the road: the binder is revoked the moment this returns, so the
     * bytes are dropped with a line rather than held in a process that has nothing left to do with
     * them.
     */
    private fun flushBeforeRevoke(host: ISketchHost) {
        val hook = SketchSession.flushHook
        val t0 = SystemClock.elapsedRealtime()
        try {
            hook?.flushBlocking()
        } catch (e: Exception) {
            Log.w(TAG, "teardown flush hook failed: ${e.javaClass.simpleName}")
        }
        val parked = SketchSession.pending.take()
        if (parked == null) {
            Slog.d(TAG) { "teardown: nothing parked (${SystemClock.elapsedRealtime() - t0} ms)" }
            return
        }
        try {
            if (hook != null) hook.pushBlocking(parked.pageKey, parked.png) else SketchPush.push(host, parked.pageKey, parked.png)
            Slog.d(TAG) { "teardown: parked ${parked.png.size} B re-pushed in ${SystemClock.elapsedRealtime() - t0} ms" }
        } catch (e: Exception) {
            Log.w(TAG, "a parked sketch (${parked.png.size} B) could not be re-pushed at end(); dropped: ${e.javaClass.simpleName}")
        }
    }

    /**
     * No screen, but pixels nobody has taken: try the restarted host on a background thread — never
     * the Binder thread `begin` arrives on, which the host is waiting to return.
     *
     * A recreated host opens its `.soil` asynchronously, so the first attempts are expected to fail;
     * the ladder gives it a few seconds and then gives up, which is the honest end of a save nobody
     * is waiting for any more. The park is only taken once a push is about to be attempted, so a
     * `begin` that races this one does not find an empty park and conclude there was nothing owed.
     */
    private fun pushPendingInBackground() {
        Thread {
            for (attempt in 1..PENDING_ATTEMPTS) {
                val host = SketchSession.host ?: return@Thread
                val parked = SketchSession.pending.take() ?: return@Thread
                try {
                    SketchPush.push(host, parked.pageKey, parked.png)
                    Slog.d(TAG) { "pending: ${parked.png.size} B pushed on attempt $attempt" }
                    return@Thread
                } catch (e: Exception) {
                    SketchSession.pending.park(parked.pageKey, parked.png)
                    Slog.d(TAG) { "pending attempt $attempt failed: ${e.javaClass.simpleName}" }
                }
                Thread.sleep(PENDING_RETRY_MS)
            }
            Slog.d(TAG) { "pending gave up after $PENDING_ATTEMPTS attempts" }
        }.apply { isDaemon = true }.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "SketchService"

        /** ~5 s of ladder: a recreated host's `.soil` open is asynchronous. */
        const val PENDING_ATTEMPTS = 10
        const val PENDING_RETRY_MS = 500L
    }
}
