package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.IScratchPad
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle

/**
 * The SCRATCH_PAD point (arc 11 / J3) — the host's **held** bind for one showing of the screen.
 * Every method: `HostCallerCheck.enforce` first. `begin` holds the store binder for the screen's
 * life in [ScratchSession] (a second `begin` while one is held replaces it — the host restarted) and
 * reads the page list on the Binder thread (first run creates one blank page; the count is logged).
 * `end` clears everything. `receiveInk` / `takeOutgoing` are J5 — until then they throw
 * `UnsupportedOperationException`, which Binder marshals intact (`EX_UNSUPPORTED_OPERATION`); the
 * J3/J4 host wires nothing to them.
 *
 * Exceptions that cross Binder intact are the only ones thrown — anything else kills the transaction
 * silently and the caller reads an empty reply as success. Logs: counts + durations — never ink.
 *
 * A debug-only `StoreProbe` briefly lived here: a once-per-process cross-process 4 MiB
 * `putLarge` / `getLarge` round trip from `begin`, the arc's open question about ashmem over a **real**
 * Binder (the host's own self-test never leaves its process). It answered — **916 ms on the Nomad**,
 * inside `begin`'s 2 s budget, matching Paper's 917 ms — and was removed in the same phase, as Paper
 * removed its own: left in, it would sit inside the first pad open of every session and muddy J4's
 * timings.
 */
class ScratchPadService : Service() {

    private val binder = object : IScratchPad.Stub() {

        override fun begin(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            val t0 = SystemClock.elapsedRealtime()
            ScratchSession.clear()
            ScratchSession.store = store
            val pages = try { ScratchStore(store).load().ids.size } catch (e: StoreUnavailable) { -1 }
            Slog.d(TAG) { "begin: pages=$pages in ${SystemClock.elapsedRealtime() - t0} ms" }
        }

        override fun receiveInk(chunk: InkBundle?, placement: Int, last: Boolean) {
            enforce()
            throw UnsupportedOperationException("receiveInk arrives in J5")
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            throw UnsupportedOperationException("takeOutgoing arrives in J5")
        }

        override fun end() {
            enforce()
            ScratchSession.clear()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@ScratchPadService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "ScratchPadService"
    }
}
